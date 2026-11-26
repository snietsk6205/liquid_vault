package dev.liquid.vault.control

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import dev.liquid.vault.Env
import dev.liquid.vault.core.common.ActionQueue
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.QueueFile
import dev.liquid.vault.core.common.QueuedAction
import dev.liquid.vault.core.common.ShellAction
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Your normal apps, as the personal copy sees them (Phase 1). */
object PersonalApps {
    /**
     * The apps a launcher shows on the personal side, plus the ones Liquid Vault froze (Android lists frozen apps
     * nowhere else). Suspended apps stay in launchers, greyed, so they come with the others.
     */
    fun list(context: Context): List<AppRecord> {
        val pm = context.packageManager
        val own = context.packageName
        val la = context.getSystemService(LauncherApps::class.java)
        val visible = runCatching { la.getActivityList(null, Env.myUser) }.getOrDefault(emptyList())
            .filter { it.applicationInfo.packageName != own }
            .distinctBy { it.applicationInfo.packageName }
            .map { a ->
                val info = a.applicationInfo
                AppRecord(info.packageName, a.label.toString(), Place.PERSONAL, system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0, suspended = info.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
            }
        val shown = visible.map { it.pkg }.toSet()
        val frozen = frozenByUs(context).filter { it !in shown }.mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull() ?: return@mapNotNull null
            if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) return@mapNotNull null // removed meanwhile
            if (isVisible(pm, pkg)) return@mapNotNull null // unfrozen by someone else: it is in the launcher list again
            AppRecord(pkg, pm.getApplicationLabel(info).toString(), Place.PERSONAL, system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0, frozen = true)
        }
        return visible + frozen
    }

    /** A frozen (hidden) app is installed but not visible without MATCH_UNINSTALLED_PACKAGES. */
    private fun isVisible(pm: PackageManager, pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun frozenByUs(context: Context): Set<String> = Prefs.of(context).getStringSet(Prefs.FROZEN_PERSONAL, emptySet())?.toSet() ?: emptySet()

    fun noteFrozen(context: Context, pkg: String, frozen: Boolean) {
        val now = frozenByUs(context).let { if (frozen) it + pkg else it - pkg }
        Prefs.of(context).edit().putStringSet(Prefs.FROZEN_PERSONAL, now).apply()
    }
}

/**
 * Actions on personal apps wait here while the shell is off (D46): saved as one file (written whole, then renamed into
 * place), shown in the Home screen's Shell card, applied as soon as Shevery connects and allows Liquid Vault.
 */
object ActionQueueStore {
    private val _queue = MutableStateFlow<List<QueuedAction>>(emptyList())
    val queue: StateFlow<List<QueuedAction>> = _queue
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "action-queue.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        _queue.value = runCatching { if (f.isFile) VaultJson.decodeFromString(QueueFile.serializer(), f.readText()).actions else emptyList() }
            .onFailure {
                DebugLog.error("Queue", "the waiting actions could not be read (${it.message}); kept aside as action-queue.broken.json")
                f.renameTo(File(context.filesDir, "action-queue.broken.json"))
            }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(context: Context, actions: List<QueuedAction>) {
        _queue.value = actions
        val tmp = File(context.filesDir, "action-queue.json.tmp")
        runCatching {
            tmp.writeText(VaultJson.encodeToString(QueueFile.serializer(), QueueFile(actions = actions)))
            if (!tmp.renameTo(file(context))) error("rename failed")
        }.onFailure { DebugLog.error("Queue", "the waiting actions could not be saved: ${it.message}") }
    }

    /**
     * Asks for [action] on a personal app: done at once when the shell is ready, else it waits. Returns what happened,
     * in plain words.
     */
    suspend fun request(context: Context, action: ShellAction, app: AppRecord): Outcome {
        load(context)
        val item = QueuedAction(System.currentTimeMillis() * 1000 + (0..999).random(), action, app.pkg, app.label, Env.myUserId, System.currentTimeMillis())
        val (next, added) = mutex.withLock { ActionQueue.add(_queue.value, item).also { (list, _) -> save(context, list) } }
        DebugLog.event("Queue", "${item.title}: ${added.words}")
        if (added == ActionQueue.Added.CANCELLED) return Outcome(true, "${app.label}: ${added.words}")
        if (Shell.unavailableReason() == null) {
            applyDue(context)
            val still = queue.value.firstOrNull { it.pkg == app.pkg && it.action == action }
            return if (still == null) Outcome(true, "${app.label} ${action.done}") else Outcome(false, "${app.label}: ${still.lastError ?: "not done"}")
        }
        return Outcome(true, "${item.title} waits for the shell: start Shevery and it happens by itself (${next.size} waiting)")
    }

    fun remove(context: Context, id: Long) {
        load(context)
        save(context, _queue.value.filterNot { it.id == id })
    }

    fun retry(context: Context, id: Long) {
        load(context)
        save(context, _queue.value.map { if (it.id == id) it.copy(attempts = 0, lastError = null) else it })
    }

    /** Runs every waiting action that is due, one by one, through the shell. Safe to call any time. */
    suspend fun applyDue(context: Context) = withContext(Dispatchers.IO) {
        load(context)
        if (Shell.unavailableReason() != null) return@withContext
        mutex.withLock {
            for (a in ActionQueue.due(_queue.value)) {
                val result = runCatching { Shell.run(ActionQueue.command(a), 30_000) }
                val output = result.getOrNull()?.output ?: ""
                val ok = result.isSuccess && ActionQueue.succeeded(a, output)
                if (ok) {
                    when (a.action) {
                        ShellAction.FREEZE -> PersonalApps.noteFrozen(context, a.pkg, true)
                        ShellAction.UNFREEZE -> PersonalApps.noteFrozen(context, a.pkg, false)
                        else -> {}
                    }
                    save(context, _queue.value.filterNot { it.id == a.id })
                    DebugLog.event("Queue", "${a.title}: done by the shell")
                } else {
                    val why = result.exceptionOrNull()?.asEvidence() ?: output.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "no answer"
                    save(context, ActionQueue.failed(_queue.value, a, why))
                    DebugLog.error("Queue", "${a.title}: the shell said \"$why\"")
                }
            }
        }
    }
}
