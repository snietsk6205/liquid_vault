package dev.liquid.vault.control

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.vault.VaultActionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The answer to something the app did, in plain words for the screen. */
data class Outcome(val ok: Boolean, val message: String)

/**
 * The personal copy's side of the Vault (Phase 1): everything the Vault's owner (the Vault copy) does for it, asked
 * through the channel ([VaultService] answers). Every call runs off the main thread and turns a failure into words.
 */
object VaultControl {
    /**
     * runCatching that lets a cancelled caller go: when the screen that asked has left, the call ends quietly instead
     * of being logged and shown as a failure (CI run 16: "could not list the Vault's apps: … left the composition").
     */
    private suspend inline fun <T> attempt(block: () -> T): Result<T> =
        runCatching(block).onFailure { if (it is CancellationException) currentCoroutineContext().ensureActive() }

    private suspend fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle? = withContext(Dispatchers.IO) {
        if (!Channel.ready()) error("the channel to the Vault copy is not set up (Diagnostics › L0)")
        Channel.call(method, arg, extras)
    }

    private fun Bundle?.outcome(): Outcome =
        if (this == null) Outcome(false, "the Vault copy gave no answer") else Outcome(getBoolean("ok"), getString("message") ?: "")

    private suspend fun ask(method: String, arg: String? = null, extras: Bundle? = null): Outcome =
        attempt { call(method, arg, extras).outcome() }.getOrElse { Outcome(false, it.message ?: it.asEvidence()) }

    /** The Vault's apps, frozen ones included; null when the Vault copy cannot be asked (no channel, paused Vault). */
    suspend fun apps(): List<AppRecord>? = attempt {
        val b = call("vaultApps") ?: return null
        val pkgs = b.getStringArray("pkgs") ?: return null
        val labels = b.getStringArray("labels") ?: pkgs
        val system = b.getBooleanArray("system") ?: BooleanArray(pkgs.size)
        val frozen = b.getBooleanArray("frozen") ?: BooleanArray(pkgs.size)
        val suspended = b.getBooleanArray("suspended") ?: BooleanArray(pkgs.size)
        pkgs.indices.map { i -> AppRecord(pkgs[i], labels[i], Place.VAULT, system[i], frozen[i], suspended[i]) }
    }.onFailure { DebugLog.error("Vault", "could not list the Vault's apps: ${it.asEvidence()}") }.getOrNull()

    /** "freeze", "unfreeze", "suspend", "unsuspend", "enable-system". */
    suspend fun appAction(action: String, pkg: String): Outcome =
        ask("vaultAppAction", action, Bundle().apply { putString("pkg", pkg) })

    data class Vpn(val current: String?, val lockdown: Boolean, val apps: List<Pair<String, String>>)

    suspend fun vpn(): Vpn? = attempt {
        val b = call("vaultVpn") ?: return null
        val pkgs = b.getStringArray("pkgs") ?: emptyArray()
        val labels = b.getStringArray("labels") ?: pkgs
        Vpn(b.getString("current"), b.getBoolean("lockdown"), pkgs.indices.map { pkgs[it] to labels[it] })
    }.getOrNull()

    suspend fun setVpn(pkg: String?, lockdown: Boolean): Outcome =
        ask("vaultVpnSet", null, Bundle().apply { putString("pkg", pkg); putBoolean("lockdown", lockdown) })

    data class Lock(val separate: Boolean?, val secure: Boolean, val locked: Boolean)

    suspend fun lock(): Lock? = attempt {
        val b = call("vaultLock") ?: return null
        Lock(if (b.containsKey("separate")) b.getBoolean("separate") else null, b.getBoolean("secure"), b.getBoolean("locked"))
    }.getOrNull()

    suspend fun lockNow(): Outcome = ask("vaultLockNow")

    /** Phase 2: a Vault app's own files, set by the Vault copy as the owner (D57). */
    suspend fun ownFiles(pkg: String, own: dev.liquid.vault.core.common.OwnFiles): Outcome =
        ask("vaultOwnFiles", own.name, Bundle().apply { putString("pkg", pkg) })

    /** The Vault side's storage as the Vault copy serves it: whether it may read all of it. */
    suspend fun storeInfo(): Boolean? = attempt { call("storeInfo")?.getBoolean("allFiles") }.getOrNull()

    /** The Vault copy's access log (Vault apps reaching personal files), newest last, as JSON lines. */
    suspend fun accessLog(): List<String> = attempt { call("accessLog")?.getStringArrayList("lines")?.toList() }.getOrNull() ?: emptyList()

    suspend fun setIconVisible(visible: Boolean): Outcome = ask("vaultIcon", if (visible) "on" else "off")

    data class State(val shellBlocked: Boolean, val iconVisible: Boolean, val setupVersion: Int)

    suspend fun state(): State? = attempt {
        val b = call("vaultState") ?: return null
        State(b.getBoolean("shellBlocked"), b.getBoolean("iconVisible"), b.getInt("setupVersion"))
    }.getOrNull()

    /** How the last screen in the Vault ended (copy or remove with Android's confirmation), if it ended after [since]. */
    suspend fun lastAction(since: Long): Outcome? = attempt {
        val b = call("vaultLastAction") ?: return null
        if (b.getLong("at") < since) null else Outcome(b.getBoolean("ok"), b.getString("text") ?: "")
    }.getOrNull()

    /**
     * Opens a screen in the Vault copy (D43): it asks the Vault copy for a one-time token through the channel, then
     * sends the request through the owner's cross-profile filter. [what] is one of VaultActionActivity's DO_ values.
     */
    suspend fun openInVault(activity: Activity, what: String, pkg: String? = null, label: String? = null, screen: String? = null, apks: List<String>? = null): Outcome {
        val token = attempt { call("vaultToken")?.getString("token") }.getOrElse { return Outcome(false, it.message ?: it.asEvidence()) }
            ?: return Outcome(false, "the Vault copy gave no token")
        val intent = Intent(VaultActionActivity.ACTION).addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(VaultActionActivity.EXTRA_TOKEN, token)
            .putExtra(VaultActionActivity.EXTRA_DO, what)
            .putExtra(VaultActionActivity.EXTRA_PKG, pkg)
            .putExtra(VaultActionActivity.EXTRA_LABEL, label)
            .putExtra(VaultActionActivity.EXTRA_SCREEN, screen)
            .putExtra(VaultActionActivity.EXTRA_APKS, apks?.toTypedArray())
        return withContext(Dispatchers.Main) {
            try {
                activity.startActivity(intent)
                Outcome(true, "opened in the Vault")
            } catch (e: ActivityNotFoundException) {
                Outcome(false, "the Vault copy cannot be reached: open Diagnostics › L0, or resume the Vault if it is paused")
            }
        }
    }
}
