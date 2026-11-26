package dev.liquid.vault.diag

import android.content.Context
import android.content.SharedPreferences
import dev.liquid.vault.Env
import dev.liquid.vault.core.common.CheckBook
import dev.liquid.vault.core.common.CheckResult
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.VaultJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Small values each copy keeps (per user: each copy has its own). Keys never change once shipped. */
object Prefs {
    const val CREATED_BY = "created_by"
    const val SETUP_DONE_AT = "setup_done_at"
    const val SETUP_WHY = "setup_why"
    const val SETUP_STEPS = "setup_steps"
    const val CHANNEL_VIA = "channel_via"
    const val CHANNEL_AT = "channel_at"
    const val CHANNEL_HELLO = "channel_hello"
    const val CLONE_TARGET = "clone_target"
    const val VAULT_APP_TARGET = "vault_app_target"
    const val BRIDGE_ALLOWED = "bridge_allowed"
    const val B1_TREE = "b1_tree"
    const val B2_TREE = "b2_tree"
    const val B1_FILTERS_ON = "b1_filters_on"
    const val VAULT_USER_ID = "vault_user_id"
    const val SHELL_CREATED_USER = "shell_created_user"
    const val BOOT_RECORD = "boot_record"
    const val LAST_ANSWERS = "last_answers"
    const val F8_PENDING_MODE = "f8_pending_mode"
    const val F8_PENDING_BEFORE = "f8_pending_before"
    /** + a package name: that Vault app's own app-op entries before F6/F7 first changed them (StorageOps.encode). */
    const val SHELL_OPS_BEFORE = "shell_ops_before:"
    /** + a package name: that Vault app's app-op modes before F4/F5 first changed them, as the owner read them. */
    const val OWNER_OPS_BEFORE = "owner_ops_before:"
    /** When this copy (the Vault's owner) last lifted "no debugging features" (P2). */
    const val P2_LIFTED_AT = "p2_lifted_at"

    // ---- Phase 1 ----
    /** Vault copy: which version of the owner's setup this Vault has (2 adds the Vault-action filter, D43). */
    const val SETUP_VERSION = "setup_version"
    /** Vault copy: show its own icon in the Work tab (Q4: hidden by default). */
    const val VAULT_ICON_VISIBLE = "vault_icon_visible"
    /** Vault copy: how the last foreground action ended (VaultActionActivity), for the personal copy. */
    const val LAST_ACTION_OK = "last_action_ok"
    const val LAST_ACTION_TEXT = "last_action_text"
    const val LAST_ACTION_AT = "last_action_at"
    /** Personal copy: personal apps this app froze (Android lists frozen apps nowhere else). */
    const val FROZEN_PERSONAL = "frozen_personal"
    /** Personal copy, options (D50): at boot, turn wireless debugging on; at boot, remind to start Shevery. */
    const val OPT_BOOT_WIRELESS_DEBUGGING = "opt_boot_wireless_debugging"
    const val OPT_BOOT_REMIND_SHELL = "opt_boot_remind_shell"
    /** Vault copy: the last "no debugging features" state it saw, and when it changed (D37). */
    const val SHELL_BLOCKED_SEEN = "shell_blocked_seen"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    val app: SharedPreferences get() = of(Env.app)
}

/**
 * This copy's check results, kept in memory for the screen and saved as one JSON file (written to a temporary file,
 * then renamed into place, so a crash never leaves half a file; a file that cannot be read is kept aside, not deleted).
 */
object CheckStore {
    private val _book = MutableStateFlow(CheckBook("?", -1))
    val book: StateFlow<CheckBook> = _book
    @Volatile private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "checks.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        val side = Env.side()
        _book.value = runCatching {
            if (f.isFile) VaultJson.decodeFromString(CheckBook.serializer(), f.readText()) else null
        }.onFailure {
            DebugLog.error("Checks", "saved results could not be read (${it.message}); kept aside as checks.broken.json")
            f.renameTo(File(context.filesDir, "checks.broken.json"))
        }.getOrNull()?.copy(side = side.id, userId = Env.myUserId) ?: CheckBook(side.id, Env.myUserId)
        // A check still "running" in a saved file was cut short (the app closed, the phone restarted): say so, and give
        // its buttons back.
        val interrupted = _book.value.results.values.filter { it.status == dev.liquid.vault.core.common.CheckStatus.RUNNING }
        interrupted.forEach { r ->
            _book.value = _book.value.with(
                r.copy(status = dev.liquid.vault.core.common.CheckStatus.NOT_RUN, summary = "interrupted: the app closed while this ran (${r.summary})"),
            )
        }
        if (interrupted.isNotEmpty()) save()
        runCatching { Runner.finishPendingF8() }.onFailure { DebugLog.error("Checks", "could not finish F8: ${it.message}") }
    }

    fun get(id: String): CheckResult? = _book.value.results[id]

    @Synchronized
    fun put(result: CheckResult) {
        _book.value = _book.value.with(result)
        save()
    }

    @Synchronized
    fun clear(id: String) {
        _book.value = _book.value.without(id)
        save()
    }

    @Synchronized
    fun clearAll() {
        _book.value = CheckBook(_book.value.side, _book.value.userId)
        save()
    }

    private fun save() {
        val context = Env.app
        val f = file(context)
        val tmp = File(context.filesDir, "checks.json.tmp")
        runCatching {
            tmp.writeText(VaultJson.encodeToString(CheckBook.serializer(), _book.value))
            if (!tmp.renameTo(f)) error("rename failed")
        }.onFailure { DebugLog.error("Checks", "results could not be saved: ${it.message}") }
    }
}
