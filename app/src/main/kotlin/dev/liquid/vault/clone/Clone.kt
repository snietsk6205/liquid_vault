package dev.liquid.vault.clone

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.UserManager
import dev.liquid.vault.Env
import dev.liquid.vault.admin.OwnerAppOps
import dev.liquid.vault.core.common.CheckResult
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.CheckStore
import java.io.File

/**
 * Cloning a personal app into the Vault, the ways the profile owner can try without the shell (checks C1, C3, C4).
 * C3 follows Insular's AppInstallerActivity (Apache-2.0): stream the app's own APK files into an installer session.
 */
object Cloner {
    /**
     * The app's info as installed on the phone. From inside the work profile, LauncherApps may not look at the personal
     * side (Android 11+ refuses profiles that ask about their parent), but the package manager still describes a package
     * installed for another user when asked with MATCH_UNINSTALLED_PACKAGES; its APK paths are the same for every user.
     */
    fun personalAppInfo(context: Context, pkg: String): ApplicationInfo =
        runCatching { context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
            ?: context.getSystemService(LauncherApps::class.java).getApplicationInfo(pkg, 0, Env.personalUser() ?: error("no personal user found"))

    fun installedHere(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** C1: Android's own "install existing" for device policy (normally allowed only for "affiliated" profiles). */
    fun withOwnerInstallExisting(context: Context, pkg: String): Pair<Boolean, List<String>> {
        val lines = mutableListOf("affiliated user (Android's condition): ${runCatching { Env.dpm.isAffiliatedUser }.getOrNull()}")
        val ok = try {
            Env.dpm.installExistingPackage(Env.admin, pkg).also { lines += "installExistingPackage returned $it" }
        } catch (t: Throwable) {
            lines += "installExistingPackage threw ${t.asEvidence()}"
            false
        }
        val there = installedHere(context, pkg)
        lines += "installed in the Vault now: $there"
        return (ok && there) to lines
    }

    /** C4: a system app (built into the phone) switched on in the Vault. */
    fun withEnableSystemApp(context: Context, pkg: String): Pair<Boolean, List<String>> {
        val lines = mutableListOf<String>()
        Env.dpm.enableSystemApp(Env.admin, pkg)
        lines += "enableSystemApp($pkg) returned"
        val there = installedHere(context, pkg)
        lines += "installed in the Vault now: $there"
        return there to lines
    }

    /**
     * C3: copies the app's APK files (readable by anyone in /data/app) into an installer session inside the Vault. The
     * profile owner first lifts what would block it: the "unknown sources" restriction and this app's own "install apps"
     * app-op. Android may still ask you to confirm; the result arrives in [InstallResultReceiver].
     */
    fun withInstallerSession(context: Context, pkg: String, purpose: String = PURPOSE_C3): List<String> {
        val lines = mutableListOf<String>()
        val info = personalAppInfo(context, pkg)
        val files = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        lines += "APK files: " + files.joinToString { "${File(it).name} (${File(it).length() / 1024} KB)" }

        runCatching {
            Env.dpm.clearUserRestriction(Env.admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            lines += "restriction \"no unknown sources\" cleared in the Vault"
        }.onFailure { lines += "could not clear \"no unknown sources\": ${it.asEvidence()}" }
        runCatching {
            val op = AppOpsManager.permissionToOp(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) ?: "android:request_install_packages"
            OwnerAppOps.setMode(op, android.os.Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
            lines += "this app may install apps in the Vault (owner app-op): ${OwnerAppOps.modeName(OwnerAppOps.mode(op, android.os.Process.myUid(), context.packageName))}"
        }.onFailure { lines += "could not allow installing for this app: ${it.asEvidence()}" }
        lines += "Android says this app may install packages: ${context.packageManager.canRequestPackageInstalls()}"

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        lines += "installer session $id created"
        installer.openSession(id).use { session ->
            files.forEachIndexed { i, path ->
                val name = if (i == 0) "base.apk" else "split$i.apk"
                File(path).inputStream().use { input ->
                    session.openWrite(name, 0, File(path).length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_PURPOSE, purpose),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
        lines += "session committed: waiting for Android's answer"
        DebugLog.event("Clone", "installer session $id for $pkg committed in the Vault")
        return lines
    }

    /**
     * Removes an app from the Vault through Android's installer: Android asks you to confirm (the owner of a work profile
     * cannot remove apps silently). The answer arrives in [InstallResultReceiver] with [purpose].
     */
    fun uninstallHere(context: Context, pkg: String, purpose: String) {
        val callback = PendingIntent.getBroadcast(
            context,
            pkg.hashCode() xor 0x5ee1,
            Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_PURPOSE, purpose).putExtra("uninstall", true),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.packageManager.packageInstaller.uninstall(pkg, callback.intentSender)
        DebugLog.event("Clone", "asked Android to remove $pkg from the Vault")
    }

    const val EXTRA_PKG = "dev.liquid.vault.extra.PKG"
    const val EXTRA_PURPOSE = "dev.liquid.vault.extra.PURPOSE"

    /** Diagnostics' C3 (its row records the result). */
    const val PURPOSE_C3 = "c3"

    /** Phase 1's VaultActionActivity (it shows the result and closes). */
    const val PURPOSE_ACTION = "action"
}

/** The Vault installer session's answer (C3): asks you to confirm when Android wants it, then records the result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Env.init(context)
        CheckStore.load(context)
        val pkg = intent.getStringExtra(Cloner.EXTRA_PKG) ?: "?"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (intent.getStringExtra(Cloner.EXTRA_PURPOSE) == Cloner.PURPOSE_ACTION) {
            val removing = intent.getBooleanExtra("uninstall", false)
            when (status) {
                // Android wants you to confirm. VaultActionActivity is on screen, so this app may open Android's question.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    dev.liquid.vault.vault.VaultActionState.asking()
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { runCatching { context.startActivity(it) }.onFailure { e -> dev.liquid.vault.vault.VaultActionState.done(false, "Android's question could not open: ${e.asEvidence()}") } }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    val there = Cloner.installedHere(context, pkg)
                    val ok = if (removing) !there else there
                    DebugLog.event("Clone", "$pkg ${if (removing) "removed from" else "installed in"} the Vault: ${if (ok) "done" else "Android said success but it is ${if (there) "still" else "not"} there"}")
                    dev.liquid.vault.vault.VaultActionState.done(ok, if (!ok) "Android said yes, but nothing changed" else if (removing) "removed from the Vault" else "in the Vault now")
                }
                else -> {
                    DebugLog.error("Clone", "${if (removing) "removing" else "installing"} $pkg in the Vault: status $status, $message")
                    dev.liquid.vault.vault.VaultActionState.done(false, if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else "Android refused (${message ?: "status $status"})")
                }
            }
            return
        }
        if (intent.getBooleanExtra("uninstall", false)) {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { runCatching { context.startActivity(it) } }
            } else {
                DebugLog.event("Clone", "removing $pkg from the Vault: ${if (status == PackageInstaller.STATUS_SUCCESS) "done" else "status $status, $message"}")
            }
            return
        }
        val previous = CheckStore.get("C3")?.evidence ?: emptyList()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                DebugLog.event("Clone", "Android asks you to confirm installing $pkg in the Vault")
                CheckStore.put(CheckResult("C3", CheckStatus.RUNNING, "Android asks you to confirm the install", previous + "Android asked for a confirmation (not silent)", System.currentTimeMillis()))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) }.onFailure { e -> DebugLog.error("Clone", "could not show the confirmation: ${e.asEvidence()}") } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val there = Cloner.installedHere(context, pkg)
                DebugLog.event("Clone", "$pkg installed in the Vault by the installer session")
                CheckStore.put(
                    CheckResult("C3", if (there) CheckStatus.PASS else CheckStatus.FAIL, if (there) "$pkg is in the Vault (installer session)" else "Android said success but $pkg is not in the Vault", previous + "result: success${message?.let { " ($it)" } ?: ""}", System.currentTimeMillis()),
                )
            }
            else -> {
                DebugLog.error("Clone", "installing $pkg in the Vault failed: status $status, $message")
                CheckStore.put(CheckResult("C3", CheckStatus.FAIL, "the installer session failed (status $status)", previous + "result: status $status, ${message ?: "no message"}", System.currentTimeMillis()))
            }
        }
    }
}
