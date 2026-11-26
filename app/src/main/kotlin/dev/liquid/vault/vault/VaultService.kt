package dev.liquid.vault.vault

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import dev.liquid.vault.Env
import dev.liquid.vault.SideComponents
import dev.liquid.vault.admin.ShellAccess
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.Prefs
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the Vault copy, as the Vault's owner, does for the personal copy through the channel (Phase 1, D42): list the
 * Vault's apps with their state, freeze / suspend them, switch built-in apps on, the Vault's always-on VPN, its lock.
 * Vault side only: every entry point checks it. Answers are Bundles of plain values, read by [VaultControl].
 */
object VaultService {
    private fun requireVault() {
        if (!Env.isVaultSide) throw IllegalStateException("only the Vault copy owns the Vault")
    }

    private fun result(ok: Boolean, message: String) = Bundle().apply {
        putBoolean("ok", ok)
        putString("message", message)
    }

    // ---- apps ----

    /** The Vault's apps that a launcher would show (frozen ones included), with their state. */
    fun apps(context: Context): Bundle {
        requireVault()
        val pm = context.packageManager
        val own = context.packageName
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags)
            .map { it.activityInfo.packageName }.toSet()
        // Hidden (frozen) apps are still installed here: they keep FLAG_INSTALLED and are listed with MATCH_UNINSTALLED.
        val apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 && it.packageName != own && it.packageName in launchable }
        val dpm = Env.dpm
        val admin = Env.admin
        return Bundle().apply {
            putStringArray("pkgs", apps.map { it.packageName }.toTypedArray())
            putStringArray("labels", apps.map { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }.toTypedArray())
            putBooleanArray("system", apps.map { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }.toBooleanArray())
            putBooleanArray("frozen", apps.map { runCatching { dpm.isApplicationHidden(admin, it.packageName) }.getOrDefault(false) }.toBooleanArray())
            putBooleanArray("suspended", apps.map { runCatching { dpm.isPackageSuspended(admin, it.packageName) }.getOrDefault(false) }.toBooleanArray())
        }
    }

    /** Freeze, unfreeze, suspend, unsuspend or switch on (a built-in app) one app in the Vault, then read it back. */
    fun appAction(context: Context, action: String, pkg: String): Bundle {
        requireVault()
        val dpm = Env.dpm
        val admin = Env.admin
        return try {
            val message = when (action) {
                "freeze", "unfreeze" -> {
                    val hide = action == "freeze"
                    dpm.setApplicationHidden(admin, pkg, hide)
                    val now = dpm.isApplicationHidden(admin, pkg)
                    if (now != hide) return result(false, "Android kept it ${if (now) "frozen" else "unfrozen"}")
                    if (hide) "frozen: gone from the Vault's apps until you unfreeze it" else "unfrozen: back in the Vault"
                }
                "suspend", "unsuspend" -> {
                    val on = action == "suspend"
                    val refused = dpm.setPackagesSuspended(admin, arrayOf(pkg), on)
                    if (refused.isNotEmpty()) return result(false, "Android refused to ${action} it (it may be needed by the phone)")
                    if (dpm.isPackageSuspended(admin, pkg) != on) return result(false, "Android did not change it")
                    if (on) "suspended: its icon is grey and it cannot open" else "unsuspended: it opens again"
                }
                "enable-system" -> {
                    dpm.enableSystemApp(admin, pkg)
                    val there = runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
                    if (!there) return result(false, "Android did not switch it on in the Vault")
                    "switched on in the Vault (built into the phone: nothing copied)"
                }
                else -> return result(false, "unknown action $action")
            }
            DebugLog.event("Vault", "$pkg: $message")
            result(true, message)
        } catch (t: Throwable) {
            DebugLog.error("Vault", "$action $pkg failed: ${t.asEvidence()}")
            result(false, t.asEvidence())
        }
    }

    // ---- VPN (D48) ----

    fun vpn(context: Context): Bundle {
        requireVault()
        val pm = context.packageManager
        val dpm = Env.dpm
        val admin = Env.admin
        val services = pm.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.permission == Manifest.permission.BIND_VPN_SERVICE }
            .map { it.serviceInfo.packageName }.distinct().sorted()
        return Bundle().apply {
            putString("current", runCatching { dpm.getAlwaysOnVpnPackage(admin) }.getOrNull())
            putBoolean("lockdown", runCatching { dpm.isAlwaysOnVpnLockdownEnabled(admin) }.getOrDefault(false))
            putStringArray("pkgs", services.toTypedArray())
            putStringArray("labels", services.map { p ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            }.toTypedArray())
        }
    }

    /** [pkg] null switches the Vault's always-on VPN off. */
    fun setVpn(pkg: String?, lockdown: Boolean): Bundle {
        requireVault()
        return try {
            Env.dpm.setAlwaysOnVpnPackage(Env.admin, pkg, pkg != null && lockdown)
            val now = Env.dpm.getAlwaysOnVpnPackage(Env.admin)
            DebugLog.event("Vault", "always-on VPN in the Vault: ${now ?: "off"}${if (pkg != null && lockdown) ", connections without it blocked" else ""}")
            if (now == pkg) result(true, if (pkg == null) "the Vault has no always-on VPN" else "the Vault's apps go through it") else result(false, "Android kept ${now ?: "no VPN"}")
        } catch (t: Throwable) {
            // UnsupportedOperationException: the app does not support always-on (it opts out in its manifest).
            DebugLog.error("Vault", "always-on VPN $pkg refused: ${t.asEvidence()}")
            result(false, t.asEvidence())
        }
    }

    // ---- the Vault's lock (D49) ----

    fun lock(context: Context): Bundle {
        requireVault()
        val km = context.getSystemService(KeyguardManager::class.java)
        return Bundle().apply {
            // A work profile "uses the unified password" when it has no lock of its own.
            runCatching { !Env.dpm.isUsingUnifiedPassword(Env.admin) }.getOrNull()?.let { putBoolean("separate", it) }
            putBoolean("secure", km.isDeviceSecure)
            putBoolean("locked", km.isDeviceLocked)
        }
    }

    /** Locks the Vault now. Only with a lock of its own: without one, Android would lock the whole phone. */
    fun lockNow(): Bundle {
        requireVault()
        val separate = runCatching { !Env.dpm.isUsingUnifiedPassword(Env.admin) }.getOrDefault(false)
        if (!separate) return result(false, "the Vault has no lock of its own: set one first (locking now would lock the whole phone)")
        return runCatching { Env.dpm.lockNow() }.fold(
            { DebugLog.event("Vault", "the Vault was locked from the personal copy"); result(true, "the Vault is locked: its apps ask for its lock") },
            { result(false, it.asEvidence()) },
        )
    }

    // ---- the Vault copy's own icon (Q4) and state ----

    fun setIconVisible(context: Context, visible: Boolean): Bundle {
        requireVault()
        Prefs.of(context).edit().putBoolean(Prefs.VAULT_ICON_VISIBLE, visible).apply()
        SideComponents.apply(context)
        return result(true, if (visible) "Liquid Vault shows in the Work tab" else "Liquid Vault is hidden from the Work tab")
    }

    fun state(context: Context): Bundle {
        requireVault()
        val access = ShellAccess.read()
        return Bundle().apply {
            putBoolean("shellBlocked", access.blocked)
            putBoolean("iconVisible", Prefs.of(context).getBoolean(Prefs.VAULT_ICON_VISIBLE, false))
            putInt("setupVersion", Prefs.of(context).getInt(Prefs.SETUP_VERSION, 1))
        }
    }

    // ---- one-time tokens for the foreground actions (D43) ----

    private val tokens = ConcurrentHashMap<String, Long>()
    private const val TOKEN_LIFE_MS = 60_000L

    /** A token VaultActionActivity accepts once, within a minute: a forwarded intent does not say which app sent it. */
    fun issueToken(): String {
        requireVault()
        val now = SystemClock.elapsedRealtime()
        tokens.entries.removeIf { it.value < now }
        return UUID.randomUUID().toString().also { tokens[it] = now + TOKEN_LIFE_MS }
    }

    fun takeToken(token: String?): Boolean {
        token ?: return false
        val until = tokens.remove(token) ?: return false
        return until >= SystemClock.elapsedRealtime()
    }
}
