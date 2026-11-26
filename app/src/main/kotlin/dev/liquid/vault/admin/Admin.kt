package dev.liquid.vault.admin

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import dev.liquid.vault.Env
import dev.liquid.vault.SideComponents
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.ShellCommands
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.ui.MainActivity
import dev.liquid.vault.core.common.StorageOps
import java.lang.reflect.InvocationTargetException

/**
 * The profile owner's receiver: Android calls it when this app becomes the Vault's owner. Insular's
 * IslandDeviceAdminReceiver does the same job (Apache-2.0); the setup itself is [VaultSetup].
 */
class VaultAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        DebugLog.event("Admin", "Liquid Vault became an admin of user ${Env.myUserId}")
        VaultSetup.runIfOwner(context, "admin enabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        DebugLog.event("Admin", "Android's setup says the Vault profile is ready")
        VaultSetup.runIfOwner(context, "provisioning complete")
    }
}

/** Android's own setup (check V1) ends here, inside the new profile: finish setup, then show the Vault copy. */
class ProvisioningSuccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.of(this).edit().putString(Prefs.CREATED_BY, "Android's setup (provisioning intent)").apply()
        VaultSetup.runIfOwner(this, "Android's setup finished")
        startActivity(Intent(this, MainActivity::class.java).setAction(VaultSetup.ACTION_FINISH_SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Android 12+ asks a DPC which kind of setup it wants: a work profile. */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(
            RESULT_OK,
            Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE),
        )
        finish()
    }
}

/** Android 12+ lets a DPC apply its policies before setup ends. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VaultSetup.runIfOwner(this, "policy compliance step")
        setResult(RESULT_OK)
        finish()
    }
}

/** The shell path (V2) starts the new profile with this app in it: its first start (Insular's ManualProvisioningReceiver). */
class UserInitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.event("Admin", "first start of user ${Env.myUserId}")
        VaultSetup.runIfOwner(context, "first start of the profile")
    }
}

/**
 * What the profile owner sets up once, whichever way the Vault was made. Every step is idempotent (safe to run again)
 * and logged; a failing step is recorded and the others still run.
 *
 * The default cross-profile intent filters of a work profile (dialer, SMS, share, pickers) are not set here: since
 * Android 13 the framework sets them itself for every managed profile, also one made with `pm create-user` (AOSP
 * UserTypeFactory). Insular copies them only for older Android (DECISIONS.md, D9).
 */
object VaultSetup {
    const val ACTION_FINISH_SETUP = "dev.liquid.vault.action.FINISH_SETUP"
    const val VAULT_DISPLAY_NAME = ShellCommands.VAULT_NAME

    fun runIfOwner(context: Context, why: String): List<String> {
        Env.init(context)
        if (!Env.dpm.isProfileOwnerApp(context.packageName)) {
            DebugLog.decision("Setup", "not the profile owner of user ${Env.myUserId}: nothing to set up ($why)")
            return emptyList()
        }
        return run(context, why)
    }

    private fun run(context: Context, why: String): List<String> {
        val dpm = Env.dpm
        val admin = Env.admin
        val lines = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            lines += try {
                block()
                "ok: $name"
            } catch (t: Throwable) {
                "FAILED: $name — ${t.asEvidence()}"
            }
        }
        step("the profile is called \"$VAULT_DISPLAY_NAME\"") { dpm.setProfileName(admin, VAULT_DISPLAY_NAME) }
        step("organisation name \"Liquid Vault\"") { dpm.setOrganizationName(admin, "Liquid Vault") }
        step("profile enabled") { dpm.setProfileEnabled(admin) }
        step("channel: intents for our channel may cross both ways") { Channel.addCrossProfileFilters(dpm) }
        step("channel: this app may work across profiles (setCrossProfilePackages)") {
            dpm.setCrossProfilePackages(admin, setOf(context.packageName))
        }
        step("channel: INTERACT_ACROSS_PROFILES app-op allowed for the Vault copy (owner app-op)") {
            val op = AppOpsManager.permissionToOp(android.Manifest.permission.INTERACT_ACROSS_PROFILES)
                ?: error("no app-op for INTERACT_ACROSS_PROFILES")
            OwnerAppOps.setMode(op, android.os.Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        }
        step("any keyboard and accessibility service may be used in the Vault") {
            dpm.setPermittedInputMethods(admin, null)
            dpm.setPermittedAccessibilityServices(admin, null)
        }
        step("side-specific parts switched on") { SideComponents.apply(context) }
        Prefs.of(context).edit()
            .putLong(Prefs.SETUP_DONE_AT, System.currentTimeMillis())
            .putString(Prefs.SETUP_WHY, why)
            .putString(Prefs.SETUP_STEPS, lines.joinToString("\n"))
            .putInt(Prefs.SETUP_VERSION, CURRENT_VERSION)
            .apply()
        val failed = lines.count { it.startsWith("FAILED") }
        DebugLog.event("Setup", "Vault setup ran ($why): ${lines.size - failed} steps ok, $failed failed")
        lines.filter { it.startsWith("FAILED") }.forEach { DebugLog.error("Setup", it) }
        return lines
    }

    fun isDone(context: Context): Boolean = Prefs.of(context).getLong(Prefs.SETUP_DONE_AT, 0L) > 0L

    /** 2 (Phase 1): the Vault-action filter (D43). A fresh setup gets everything; an older Vault gets what it misses. */
    const val CURRENT_VERSION = 2

    /**
     * Brings a Vault set up by an older version of this app to [CURRENT_VERSION], once (each step adds only what is
     * new, so nothing is added twice). Called when the Vault copy starts.
     */
    fun upgrade(context: Context) {
        Env.init(context)
        if (!isDone(context) || !runCatching { Env.dpm.isProfileOwnerApp(context.packageName) }.getOrDefault(false)) return
        val prefs = Prefs.of(context)
        val from = prefs.getInt(Prefs.SETUP_VERSION, 1)
        if (from >= CURRENT_VERSION) return
        val done = mutableListOf<String>()
        if (from < 2) {
            done += runCatching { Channel.addVaultActionFilter(Env.dpm) }.fold({ "ok: the personal copy may open screens in the Vault (Vault actions)" }, { "FAILED: Vault actions — ${it.asEvidence()}" })
        }
        prefs.edit().putInt(Prefs.SETUP_VERSION, CURRENT_VERSION)
            .putString(Prefs.SETUP_STEPS, ((prefs.getString(Prefs.SETUP_STEPS, "") ?: "").lines().filter { it.isNotBlank() } + done).joinToString("\n"))
            .apply()
        DebugLog.event("Setup", "the Vault's setup went from version $from to $CURRENT_VERSION: ${done.joinToString("; ")}")
    }
}

/**
 * App-ops set by the profile owner, with no shell: AOSP's AppOpsService lets a profile owner change app-op modes for
 * apps in its own profile (enforceManageAppOpsModes). The method is hidden but on Android's allowed list for apps
 * (@UnsupportedAppUsage, no target limit), so it is called by reflection. Modes are read back with the public
 * [AppOpsManager.unsafeCheckOpRawNoThrow], which needs no permission (DECISIONS.md, D10).
 */
object OwnerAppOps {
    /** The All files app-op's name (AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE is not in the public SDK). */
    const val OP_ALL_FILES = "android:manage_external_storage"

    private val appOps: AppOpsManager get() = Env.app.getSystemService(AppOpsManager::class.java)

    fun opCode(op: String): Int = unwrap {
        AppOpsManager::class.java.getMethod("strOpToOp", String::class.java).invoke(null, op) as Int
    }

    fun setMode(op: String, uid: Int, packageName: String, mode: Int) {
        val code = opCode(op)
        unwrap {
            AppOpsManager::class.java
                .getMethod("setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .invoke(appOps, code, uid, packageName, mode)
        }
    }

    fun mode(op: String, uid: Int, packageName: String): Int = appOps.unsafeCheckOpRawNoThrow(op, uid, packageName)

    /** Android's own default for a storage, media or All files op (setting it removes the app's entry). */
    fun defaultMode(op: String): Int =
        if (StorageOps.defaultMode(op) == "default") AppOpsManager.MODE_DEFAULT else AppOpsManager.MODE_ALLOWED

    /** "allow" / "deny" / "ignore" / "foreground" / "default" → the AppOpsManager mode. */
    fun modeOf(name: String): Int = when (name) {
        "allow" -> AppOpsManager.MODE_ALLOWED
        "deny" -> AppOpsManager.MODE_ERRORED
        "ignore" -> AppOpsManager.MODE_IGNORED
        "foreground" -> AppOpsManager.MODE_FOREGROUND
        "default" -> AppOpsManager.MODE_DEFAULT
        else -> error("not an app-op mode: $name")
    }

    fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> "mode $mode"
    }

    private inline fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}

/**
 * The cross-profile intent filter directions, named for reading. Android's flag names say whose activities may handle
 * the other side's intents: FLAG_MANAGED_CAN_ACCESS_PARENT forwards intents sent on the personal side into the Vault,
 * FLAG_PARENT_CAN_ACCESS_MANAGED forwards intents sent in the Vault to the personal side (AOSP
 * DevicePolicyManagerService.addCrossProfileIntentFilter; emulator run 5 found them swapped here, D20).
 */
object Crossing {
    const val PERSONAL_TO_VAULT = DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
    const val VAULT_TO_PERSONAL = DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED

    fun filter(action: String, vararg categories: String): IntentFilter = IntentFilter(action).apply {
        categories.forEach { addCategory(it) }
    }
}
