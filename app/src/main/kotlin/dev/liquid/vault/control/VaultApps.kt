package dev.liquid.vault.control

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.liquid.vault.Env
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.ShellCommands
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.id
import dev.liquid.vault.shell.Shell
import dev.liquid.vault.vault.VaultActionActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Copying apps into the Vault and removing them (Phase 1, D44, D47), the quickest way that works right now:
 * 1. the shell's `install-existing` / `uninstall --user`, silent, when the shell reaches the Vault (D37);
 * 2. for an app built into the phone, the owner switches it on (nothing copied, D34);
 * 3. else the Vault copy's installer, on screen, and Android asks you to confirm.
 */
object Cloning {
    private suspend fun shellInVault(): Boolean = Shell.unavailableReason() == null

    suspend fun copyIntoVault(activity: Activity, app: AppRecord): Outcome {
        val vault = Env.vaultUser() ?: return Outcome(false, "there is no Vault yet")
        if (shellInVault()) {
            val res = withContext(Dispatchers.IO) { runCatching { Shell.run(ShellCommands.installExisting(vault.id, app.pkg), 60_000) } }
            val out = res.getOrNull()?.output ?: ""
            if (res.isSuccess && ShellCommands.installExistingSucceeded(out, app.pkg)) {
                DebugLog.event("Clone", "${app.pkg} copied into the Vault by the shell")
                return Outcome(true, "${app.label} is in the Vault (copied silently by the shell)")
            }
            DebugLog.decision("Clone", "the shell could not copy ${app.pkg} (${if (ShellCommands.shellBlockedFromUser(out)) "the Vault blocks the shell" else out.take(120)}): trying the owner's way")
        }
        if (app.system) {
            val done = VaultControl.appAction("enable-system", app.pkg)
            if (done.ok) return Outcome(true, "${app.label} is in the Vault (built into the phone: switched on)")
        }
        // The Vault copy cannot see an app that is only on this side (D55): this copy names its APK files.
        val apks = runCatching {
            activity.packageManager.getApplicationInfo(app.pkg, 0).let { listOf(it.sourceDir) + (it.splitSourceDirs?.toList() ?: emptyList()) }
        }.getOrElse { return Outcome(false, "${app.label}: Android does not show its files to Liquid Vault (${it.asEvidence()})") }
        val opened = VaultControl.openInVault(activity, VaultActionActivity.DO_CLONE, app.pkg, app.label, apks = apks)
        return if (opened.ok) Outcome(true, "Android asks you in the Vault to install ${app.label}") else opened
    }

    suspend fun removeFromVault(activity: Activity, app: AppRecord): Outcome {
        val vault = Env.vaultUser() ?: return Outcome(false, "there is no Vault")
        if (shellInVault()) {
            val res = withContext(Dispatchers.IO) { runCatching { Shell.run(ShellCommands.uninstallForUser(vault.id, app.pkg), 60_000) } }
            if (res.isSuccess && ShellCommands.uninstallSucceeded(res.getOrNull()?.output ?: "")) {
                DebugLog.event("Clone", "${app.pkg} removed from the Vault by the shell")
                return Outcome(true, "${app.label} and its Vault data are gone from the Vault")
            }
        }
        val opened = VaultControl.openInVault(activity, VaultActionActivity.DO_UNINSTALL, app.pkg, app.label)
        return if (opened.ok) Outcome(true, "Android asks you in the Vault to remove ${app.label}") else opened
    }
}

/** Starting apps and their info pages on either side (LauncherApps reaches the Vault from the personal side). */
object AppLaunch {
    private fun user(place: Place): UserHandle? = if (place == Place.VAULT) Env.vaultUser() else Env.myUser

    fun open(context: Context, app: AppRecord): Outcome {
        val la = context.getSystemService(LauncherApps::class.java)
        val user = user(app.place) ?: return Outcome(false, "there is no Vault")
        val activity = runCatching { la.getActivityList(app.pkg, user).firstOrNull() }.getOrNull()
            ?: return Outcome(false, "${app.label} has nothing to open${if (app.frozen) " while it is frozen" else ""}")
        return runCatching { la.startMainActivity(activity.componentName, user, null, null) }
            .fold({ Outcome(true, "opening ${app.label}") }, { Outcome(false, it.asEvidence()) })
    }

    fun info(context: Context, app: AppRecord): Outcome {
        val la = context.getSystemService(LauncherApps::class.java)
        val user = user(app.place) ?: return Outcome(false, "there is no Vault")
        val activity = runCatching { la.getActivityList(app.pkg, user).firstOrNull() }.getOrNull()
            ?: return Outcome(false, "Android shows no info page for ${app.label} while it is frozen")
        return runCatching { la.startAppDetailsActivity(activity.componentName, user, null, null) }
            .fold({ Outcome(true, "") }, { Outcome(false, it.asEvidence()) })
    }
}

/** App icons for the lists, loaded once off the main thread; Vault apps get Android's work badge. */
object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(300)

    suspend fun get(context: Context, app: AppRecord, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${app.place}:${app.pkg}"
        cache.get(key)?.let { return@withContext it }
        val pm = context.packageManager
        val drawable: Drawable = runCatching { pm.getApplicationInfo(app.pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadIcon(pm) }.getOrNull()
            ?: return@withContext null
        val shown = if (app.place == Place.VAULT) Env.vaultUser()?.let { pm.getUserBadgedIcon(drawable, it) } ?: drawable else drawable
        runCatching { shown.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()?.also { cache.put(key, it) }
    }
}

/** Pausing the Vault (Android's "pause work apps"), as Q1/Q2 proved on the phone. Needs MODIFY_QUIET_MODE (adb). */
object VaultPower {
    private const val MODIFY_QUIET_MODE = "android.permission.MODIFY_QUIET_MODE"

    fun canPause(context: Context): Boolean = context.checkSelfPermission(MODIFY_QUIET_MODE) == PackageManager.PERMISSION_GRANTED

    fun paused(): Boolean? = Env.vaultUser()?.let { runCatching { Env.um.isQuietModeEnabled(it) }.getOrNull() }

    suspend fun setPaused(context: Context, pause: Boolean): Outcome {
        val vault = Env.vaultUser() ?: return Outcome(false, "there is no Vault")
        if (!canPause(context)) return Outcome(false, "Liquid Vault may not pause the Vault yet: on your PC run adb shell pm grant ${context.packageName} $MODIFY_QUIET_MODE")
        val asked = withContext(Dispatchers.Main) { runCatching { Env.um.requestQuietModeEnabled(pause, vault) } }
        asked.exceptionOrNull()?.let { return Outcome(false, it.asEvidence()) }
        val deadline = SystemClock.elapsedRealtime() + if (pause) 10_000 else 30_000
        while (SystemClock.elapsedRealtime() < deadline && Env.um.isQuietModeEnabled(vault) != pause) delay(250)
        val now = Env.um.isQuietModeEnabled(vault)
        DebugLog.event("Vault", if (now) "paused" else "running")
        return when {
            now == pause -> Outcome(true, if (pause) "the Vault is paused: its apps are stopped" else "the Vault is running again")
            !pause && asked.getOrNull() == false -> Outcome(false, "Android asks for the Vault's lock first: unlock it, then try again")
            else -> Outcome(false, "the Vault did not change")
        }
    }
}
