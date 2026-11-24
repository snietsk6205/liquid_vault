package dev.liquid.vault.diag

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import dev.liquid.vault.Env
import dev.liquid.vault.admin.OwnerAppOps
import dev.liquid.vault.admin.VaultSetup
import dev.liquid.vault.bridge.AccessLog
import dev.liquid.vault.bridge.BridgeAllowlist
import dev.liquid.vault.bridge.BridgeProvider
import dev.liquid.vault.bridge.PickerFilters
import dev.liquid.vault.bridge.TestFolder
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.clone.Cloner
import dev.liquid.vault.core.common.AccessEntry
import dev.liquid.vault.core.common.BootRecord
import dev.liquid.vault.core.common.CheckResult
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.core.common.Confirm
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.PhaseZero
import dev.liquid.vault.core.common.ShellCommands
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.id
import dev.liquid.vault.shell.Shell
import dev.liquid.vault.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a check needs from the screen: an activity to start things from, and results of activities it starts. */
interface Host {
    val activity: Activity
    suspend fun startForResult(intent: Intent): Pair<Int, Intent?>
}

/** An answer to a "NEEDS YOU" question: its button label and what it makes of the check. */
data class Answer(val label: String, val status: CheckStatus, val note: String)

/**
 * Runs the Phase 0 checks (docs/PLAN.md). Every check is wrapped: an exception becomes a FAIL with the exception's
 * name and message. Each run is logged in plain words, and its result is saved ([CheckStore]).
 */
object Runner {
    /** A system permission (hidden from the SDK); Med grants it with adb. */
    private const val MODIFY_QUIET_MODE = "android.permission.MODIFY_QUIET_MODE"

    private val sees = listOf(
        Answer("It sees nothing now", CheckStatus.PASS, "you checked: the app could not see your files"),
        Answer("It still sees my files", CheckStatus.FAIL, "you checked: the app still saw your files"),
    )
    private val seesAgain = listOf(
        Answer("It sees my files", CheckStatus.PASS, "you checked: the app could see your files again"),
        Answer("It still sees nothing", CheckStatus.FAIL, "you checked: the app still could not see your files"),
    )
    val answers: Map<String, List<Answer>> = mapOf(
        "F1" to sees, "F4" to sees, "F6" to sees,
        "F2" to seesAgain, "F5" to seesAgain, "F7" to seesAgain,
        "B1" to listOf(
            Answer("Only “${TestFolder.NAME}”", CheckStatus.PASS, "you saw: the Work tab showed only our test folder"),
            Answer("Also the Vault's own folders", CheckStatus.PASS, "you saw: the Work tab ALSO showed the Vault's own folders (Internal storage, Images, Downloads…). Bridge A works but shows the whole Vault to any personal app you pick with."),
        ),
    )

    /** Checks that change nothing and need no screen: what "Run safe checks" runs. */
    val safe = listOf("A1", "A2", "S1", "S3", "V0", "V3", "L0", "L1", "Q0", "B3", "R1", "R2", "R3", "R4")

    private fun now() = System.currentTimeMillis()
    private fun r(id: String, status: CheckStatus, summary: String, evidence: List<String> = emptyList(), question: String? = null) =
        CheckResult(id, status, summary, evidence, now(), question)

    suspend fun run(id: String, host: Host?, extras: Map<String, String> = emptyMap()): CheckResult {
        CheckStore.load(Env.app)
        CheckStore.put(r(id, CheckStatus.RUNNING, "running…", CheckStore.get(id)?.evidence ?: emptyList()))
        val started = SystemClock.elapsedRealtime()
        val result = try {
            withContext(Dispatchers.IO) { dispatch(id, host, extras) }
        } catch (t: Throwable) {
            r(id, CheckStatus.FAIL, "the check stopped with an error", listOf(t.asEvidence()))
        }
        CheckStore.put(result)
        DebugLog.event("Check", "$id ${PhaseZero.checks.firstOrNull { it.id == id }?.title ?: ""}: ${result.status.label} — ${result.summary} (${SystemClock.elapsedRealtime() - started} ms)")
        return result
    }

    fun answer(id: String, index: Int) {
        val current = CheckStore.get(id) ?: return
        val a = answers[id]?.getOrNull(index) ?: return
        val evidence = current.evidence.filterNot { it.startsWith("you checked:") || it.startsWith("you saw:") } + a.note
        CheckStore.put(current.copy(status = a.status, evidence = evidence, question = null, atMillis = now()))
        DebugLog.event("Check", "$id answered \"${a.label}\": ${a.status.label}")
    }

    /** "Undo" for the checks that leave something changed. */
    suspend fun undo(id: String, host: Host?, extras: Map<String, String> = emptyMap()): CheckResult = run("$id-undo", host, extras).let { u ->
        val base = CheckStore.get(id)
        CheckStore.clear("$id-undo")
        val merged = (base ?: r(id, CheckStatus.NOT_RUN, "")).copy(
            evidence = (base?.evidence ?: emptyList()) + "undo: ${u.summary}" + u.evidence.map { "  $it" },
            atMillis = now(),
        )
        CheckStore.put(merged)
        merged
    }

    private suspend fun dispatch(id: String, host: Host?, extras: Map<String, String>): CheckResult = when (id) {
        "A1" -> a1()
        "A2" -> a2()
        "S1" -> s1()
        "S2" -> s2()
        "S3" -> s3()
        "V0" -> v0()
        "V1" -> v1(host)
        "V2" -> v2()
        "V2-undo" -> v2Undo(extras)
        "V3" -> v3()
        "P1" -> p1()
        "C1" -> c1()
        "C2" -> c2()
        "C3" -> c3(host)
        "C4" -> c4()
        "F1" -> fPolicy("F1", DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
        "F2" -> fPolicy("F2", DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        "F2-undo" -> fPolicy("F2-undo", DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        "F3" -> f3()
        "F4" -> fOwnerOps("F4", AppOpsManager.MODE_ERRORED)
        "F5" -> fOwnerOps("F5", AppOpsManager.MODE_ALLOWED)
        "F5-undo" -> fOwnerOps("F5-undo", AppOpsManager.MODE_DEFAULT)
        "F6" -> fShellOps("F6", "deny")
        "F7" -> fShellOps("F7", "allow")
        "F7-undo" -> fShellOps("F7-undo", "default")
        "F8" -> f8(AppOpsManager.MODE_ALLOWED)
        "F8-undo" -> f8(AppOpsManager.MODE_DEFAULT)
        "L0" -> l0(host, extras)
        "L1" -> l1()
        "B0" -> b0()
        "B1" -> when {
            extras["selftest"] == "true" -> selfTest("B1", "${Env.app.packageName}.bridge.vault", vault = true)
            Env.isVaultSide -> b1Vault()
            else -> bPick("B1", host, Uri.EMPTY)
        }
        "B1-undo" -> sideOnly("B1-undo", vault = true)
            ?: r("B1-undo", CheckStatus.PASS, "the Work tab is closed to personal apps again", PickerFilters.disable())
        "B2" -> when {
            extras["selftest"] == "true" -> selfTest("B2", "${Env.app.packageName}.bridge.personal", vault = false)
            Env.isVaultSide -> b2Vault()
            else -> bPick("B2", host, DocumentsContract.buildDocumentUri("${Env.app.packageName}.bridge.personal", BridgeProvider.ROOT_DOC))
        }
        "B3" -> b3()
        "Q0" -> q0()
        "Q1" -> quiet(true)
        "Q2" -> quiet(false)
        "R1" -> r1()
        "R2" -> r2()
        "R3" -> r3()
        "R4" -> r4()
        "D1" -> d1(extras)
        else -> r(id, CheckStatus.SKIPPED, "unknown check")
    }

    private fun sideOnly(id: String, vault: Boolean): CheckResult? =
        if (Env.isVaultSide != vault) r(id, CheckStatus.SKIPPED, "runs on the ${if (vault) "Vault" else "personal"} side: open the ${if (vault) "Vault copy (badged icon in the Work tab)" else "personal copy"}") else null

    // ---------------------------------------------------------------- A: device

    private fun a1(): CheckResult {
        val pm = Env.app.packageManager
        val side = Env.side()
        return r(
            "A1", CheckStatus.INFO, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${side.label.lowercase()}",
            listOf(
                "device: ${Env.deviceLine()}",
                "MagicOS: ${Env.magicOsVersion() ?: "not found in the usual properties"}",
                "security patch: ${Build.VERSION.SECURITY_PATCH}",
                "app: Liquid Vault ${Env.versionName()} (build ${Env.versionCode()}), package ${Env.app.packageName}",
                "this copy: ${side.label} (user ${Env.myUserId})",
                "managed users supported: ${pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)}",
                "device admin supported: ${pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)}",
            ),
        )
    }

    private suspend fun a2(): CheckResult {
        val lines = mutableListOf<String>()
        val me = Env.myUser
        lines += "profiles Android reports: " + Env.um.userProfiles.joinToString { "user ${it.id}" + if (it == me) " (this one)" else "" }
        lines += "this app is also installed in: " + Env.profilesWithThisApp().joinToString { "user ${it.id}" }.ifEmpty { "no other profile" }
        lines += "this copy is in a work profile: ${Env.um.isManagedProfile}; profile owner: ${Env.isVaultSide}"
        if (!Env.isVaultSide) {
            lines += "Android would allow creating a work profile now: ${runCatching { Env.dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) }.getOrElse { it.asEvidence() }}"
            Env.vaultUser()?.let { lines += "the Vault's user, paused: ${runCatching { Env.um.isQuietModeEnabled(it) }.getOrNull()}" }
        }
        if (Shell.unavailableReason() == null) lines += Shell.run(ShellCommands.listUsers()).evidence()
        else lines += "(with Shevery running, `pm list users` would be added here)"
        return r("A2", CheckStatus.INFO, "${Env.um.userProfiles.size} profile(s); this app in ${Env.profilesWithThisApp().size + 1}", lines)
    }

    // ---------------------------------------------------------------- S: shell

    private fun s1(): CheckResult {
        val st = Shell.state.value
        if (!Shell.alive) {
            return r(
                "S1", CheckStatus.FAIL, "Shevery has not connected to this copy",
                listOf(
                    "is Shevery running? Open Shevery: it should say the service is running",
                    "Shevery connects when this app starts: after starting Shevery, swipe Liquid Vault away from recents and open it again",
                    "this app offers two doors: ${Env.app.packageName}.shevery (Shevery's own API) and ${Env.app.packageName}.shizuku (official Shizuku API)",
                    "last door used in this run of the app: ${st.door?.label ?: "none"}",
                ),
            )
        }
        val uid = Shell.uidOrNull()
        val lines = listOf(
            "door: ${st.door?.let { "${it.label} (${Env.app.packageName}${it.authoritySuffix})" } ?: "unknown (the binder came without a door: another process of this app)"}",
            "server version ${Shell.versionOrNull()} (patch ${Shell.patchOrNull()})",
            "server uid $uid (${when (uid) { 2000 -> "adb shell"; 0 -> "root"; else -> "other" }}), SELinux context ${Shell.seContextOrNull()}",
            "connected at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(st.connectedAt))}",
        )
        return r("S1", CheckStatus.PASS, "Shevery connected through ${st.door?.label ?: "a door"} (server v${Shell.versionOrNull()}, uid $uid)", lines)
    }

    private fun s2(): CheckResult {
        if (!Shell.alive) return r("S2", CheckStatus.SKIPPED, "needs shell: Shevery is not running")
        if (Shell.permitted()) return r("S2", CheckStatus.PASS, "Shevery allows Liquid Vault")
        val rationale = runCatching { rikka.shizuku.Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        if (rationale) {
            return r("S2", CheckStatus.FAIL, "Shevery remembers a \"deny\" for Liquid Vault", listOf("open Shevery › authorized apps and allow Liquid Vault, then run S2 again"))
        }
        Shell.requestPermission()
        return r("S2", CheckStatus.FAIL, "not allowed yet: Shevery's dialog was opened — tap Allow there, then run S2 again", listOf("asked Shevery at ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}"))
    }

    private suspend fun s3(): CheckResult {
        Shell.unavailableReason()?.let { return r("S3", CheckStatus.SKIPPED, it) }
        val id = Shell.run(ShellCommands.id())
        val release = Shell.run(ShellCommands.getprop("ro.build.version.release"))
        val magic = Shell.run(ShellCommands.getprop("ro.build.version.magic"))
        val uid = ShellCommands.parseUid(id.stdout)
        val ok = id.ok && (uid == 2000 || uid == 0)
        return r("S3", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "commands run as ${if (uid == 0) "root" else "the shell user (uid 2000)"}" else "the command did not run as the shell user", listOf(id.evidence(), release.evidence(), magic.evidence()))
    }

    // ---------------------------------------------------------------- V: create the Vault

    /** Null when no work profile exists; otherwise why creating must be refused (with the evidence). */
    private suspend fun existingProfile(lines: MutableList<String>): String? {
        val others = Env.otherProfiles()
        lines += "other profiles Android reports: " + others.joinToString { "user ${it.id}" }.ifEmpty { "none" }
        val shellManaged = if (Shell.unavailableReason() == null) {
            val res = Shell.run(ShellCommands.listUsers())
            lines += res.evidence()
            ShellCommands.parseUsers(res.stdout).filter { it.managedProfile }
        } else emptyList()
        shellManaged.forEach { lines += "work profile found by the shell: user ${it.id} \"${it.name}\"${if (it.quietMode) " (paused)" else ""}" }
        val allowed = runCatching { Env.dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) }.getOrNull()
        lines += "Android would allow creating a work profile now: $allowed"
        val first = others.firstOrNull()?.id ?: shellManaged.firstOrNull()?.id
        return first?.let { "a work profile already exists (user $it)" }
    }

    private suspend fun v0(): CheckResult {
        sideOnly("V0", vault = false)?.let { return it }
        val lines = mutableListOf<String>()
        val existing = existingProfile(lines)
        val ours = Env.vaultUser()?.takeIf { it in Env.profilesWithThisApp() }
        return when {
            existing == null -> r("V0", CheckStatus.PASS, "no work profile on this phone: the Vault can be created", lines)
            ours != null -> r("V0", CheckStatus.SKIPPED, "the Vault already exists (user ${ours.id}, Liquid Vault is in it)", lines)
            else -> r("V0", CheckStatus.FAIL, "$existing and it is not ours: creating the Vault is refused until you remove it", lines + "remove it in Settings › Accounts (Work) › Remove work profile, or from the app that made it (Insular)")
        }
    }

    private suspend fun v1(host: Host?): CheckResult {
        sideOnly("V1", vault = false)?.let { return it }
        host ?: return r("V1", CheckStatus.SKIPPED, "needs the screen: run it from Diagnostics")
        val lines = mutableListOf<String>()
        existingProfile(lines)?.let { return r("V1", CheckStatus.FAIL, "refused: $it", lines) }
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, Env.admin)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, true)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED, true)
            // Android 13+ hands work-profile setup to a "device management role holder" when one is configured (on
            // phones with Google services) and refuses with "Can't set up device" when it is not ready. Allowing
            // offline setup makes Android's own setup do it (emulator test run 4; AOSP PreProvisioningActivityController).
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true)
            .also { @Suppress("DEPRECATION") it.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true) }
        val handler = Env.app.packageManager.resolveActivity(intent, 0)
            ?: return r("V1", CheckStatus.FAIL, "no app on this phone handles Android's work-profile setup", lines)
        lines += "Android's setup app: ${handler.activityInfo.packageName}/${handler.activityInfo.name}"
        DebugLog.event("Create", "starting Android's work-profile setup (${handler.activityInfo.packageName})")
        val (code, _) = host.startForResult(intent)
        lines += "setup ended with result $code (${if (code == Activity.RESULT_OK) "OK" else "cancelled or refused"})"
        return if (code == Activity.RESULT_OK) {
            r("V1", CheckStatus.PASS, "Android's setup made the Vault: the Vault copy finishes its setup; then run V3", lines)
        } else {
            r("V1", CheckStatus.FAIL, "Android's setup ended without a Vault (MagicOS may block it): try V2, the shell way", lines)
        }
    }

    private suspend fun v2(): CheckResult {
        sideOnly("V2", vault = false)?.let { return it }
        Shell.unavailableReason()?.let { return r("V2", CheckStatus.SKIPPED, it) }
        val lines = mutableListOf<String>()
        existingProfile(lines)?.let { return r("V2", CheckStatus.FAIL, "refused: $it", lines) }
        val pkg = Env.app.packageName
        DebugLog.event("Create", "making the Vault with the shell (Insular's manual setup)")

        val create = Shell.run(ShellCommands.createVault(), 120_000).also { lines += it.evidence() }
        val user = ShellCommands.parseCreatedUserId(create.output)
            ?: return r("V2", CheckStatus.FAIL, "step 1 failed: Android did not create the profile", lines)
        Prefs.app.edit().putInt(Prefs.SHELL_CREATED_USER, user).putString(Prefs.CREATED_BY, "the shell (V2)").apply()

        fun halfMade(step: String) = r("V2", CheckStatus.FAIL, "$step failed; the half-made profile (user $user) is still there: \"Undo\" removes it", lines)

        val install = Shell.run(ShellCommands.installExisting(user, pkg)).also { lines += it.evidence() }
        if (!ShellCommands.installExistingSucceeded(install.output, pkg)) return halfMade("step 2 (install this app in it)")
        val owner = Shell.run(ShellCommands.setProfileOwner(user, Env.adminShort)).also { lines += it.evidence() }
        if (!ShellCommands.setProfileOwnerSucceeded(owner.output)) return halfMade("step 3 (make this app its owner)")
        val start = Shell.run(ShellCommands.startUser(user), 120_000).also { lines += it.evidence() }
        if (!ShellCommands.startUserSucceeded(start.output)) return halfMade("step 4 (start the profile)")
        val open = Shell.run(ShellCommands.startVaultActivity(user, "$pkg/${MainActivity::class.java.name}", VaultSetup.ACTION_FINISH_SETUP)).also { lines += it.evidence() }
        if (!open.ok) lines += "(opening the Vault copy failed; open it yourself from the Work tab to finish its setup)"
        DebugLog.event("Create", "the Vault was made by the shell: user $user")
        return r("V2", CheckStatus.PASS, "the Vault was made by the shell (user $user); the Vault copy opened to finish its setup", lines)
    }

    private suspend fun v2Undo(extras: Map<String, String>): CheckResult {
        if (!Confirm.matches(extras["confirm"] ?: "", Confirm.REMOVE_PROFILE)) return r("V2-undo", CheckStatus.FAIL, "not done: the typed confirmation did not match")
        Shell.unavailableReason()?.let { return r("V2-undo", CheckStatus.SKIPPED, it) }
        val user = Prefs.app.getInt(Prefs.SHELL_CREATED_USER, -1).takeIf { it > 0 }
            ?: return r("V2-undo", CheckStatus.SKIPPED, "no profile made by V2 is known")
        val res = Shell.run(ShellCommands.removeUser(user), 120_000)
        val ok = ShellCommands.removeUserSucceeded(res.output)
        if (ok) Prefs.app.edit().remove(Prefs.SHELL_CREATED_USER).apply()
        return r("V2-undo", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "removed the half-made profile (user $user)" else "could not remove user $user", listOf(res.evidence()))
    }

    private fun v3(): CheckResult {
        val lines = mutableListOf<String>()
        if (Env.isVaultSide) {
            val prefs = Prefs.app
            if (!VaultSetup.isDone(Env.app)) {
                lines += "setup had not run yet: running it now"
                VaultSetup.runIfOwner(Env.app, "V3 found it missing")
            }
            val managed = Env.um.isManagedProfile
            lines += "profile owner of user ${Env.myUserId}: true; work profile: $managed"
            lines += "made by: ${prefs.getString(Prefs.CREATED_BY, "unknown (Android's setup or the shell)")}"
            lines += "setup: ${prefs.getString(Prefs.SETUP_WHY, "?")}, at ${java.util.Date(prefs.getLong(Prefs.SETUP_DONE_AT, 0))}"
            lines += (prefs.getString(Prefs.SETUP_STEPS, "") ?: "").lines().filter { it.isNotBlank() }
            val failed = lines.count { it.startsWith("FAILED") }
            return r("V3", if (managed) CheckStatus.PASS else CheckStatus.FAIL, if (managed) "this copy owns the Vault (user ${Env.myUserId}); setup ${if (failed == 0) "complete" else "done with $failed failed step(s)"}" else "this copy is profile owner but not in a work profile", lines)
        }
        val v = Env.vaultUser() ?: return r("V3", CheckStatus.FAIL, "there is no Vault (no work profile)")
        val withApp = v in Env.profilesWithThisApp()
        lines += "work profile: user ${v.id}; Liquid Vault installed there: $withApp"
        if (Channel.ready()) {
            val ping = runCatching { Channel.call("ping") }.getOrNull()
            if (ping != null) {
                lines += "the Vault copy answers: profile owner ${ping.getBoolean("profileOwner")}, version ${ping.getString("version")}"
                return r("V3", if (ping.getBoolean("profileOwner")) CheckStatus.PASS else CheckStatus.FAIL, "the Vault copy (user ${v.id}) says it owns the Vault", lines)
            }
        }
        return if (withApp) r("V3", CheckStatus.PASS, "a work profile with Liquid Vault exists (user ${v.id}); ownership is confirmed by V3 in the Vault copy", lines)
        else r("V3", CheckStatus.FAIL, "a work profile exists but Liquid Vault is not in it: it is not our Vault", lines)
    }

    // ---------------------------------------------------------------- P: profile owner basics

    private fun p1(): CheckResult {
        sideOnly("P1", vault = true)?.let { return it }
        val dpm = Env.dpm
        val admin = Env.admin
        val before = dpm.getScreenCaptureDisabled(admin)
        dpm.setScreenCaptureDisabled(admin, true)
        val on = dpm.getScreenCaptureDisabled(admin)
        dpm.setScreenCaptureDisabled(admin, false)
        val off = dpm.getScreenCaptureDisabled(admin)
        val ok = on && !off
        return r("P1", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "\"block screenshots\" went on and off as asked" else "the policy did not change as asked", listOf("before: $before", "after turning on: $on", "after turning off: $off"))
    }

    // ---------------------------------------------------------------- C: clone

    private fun cloneTarget(): String? = Prefs.app.getString(Prefs.CLONE_TARGET, null)

    private fun c1(): CheckResult {
        sideOnly("C1", vault = true)?.let { return it }
        val pkg = cloneTarget() ?: return r("C1", CheckStatus.SKIPPED, "choose an app to clone first (in this copy)")
        if (Cloner.installedHere(Env.app, pkg)) return r("C1", CheckStatus.SKIPPED, "$pkg is already in the Vault: remove it from the Vault first, or choose another app")
        val (ok, lines) = Cloner.withOwnerInstallExisting(Env.app, pkg)
        return r("C1", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "$pkg cloned with the owner's install-existing" else "the owner's install-existing did not clone $pkg (expected: Android allows it only for affiliated profiles)", lines)
    }

    private suspend fun c2(): CheckResult {
        sideOnly("C2", vault = false)?.let { return it }
        val pkg = cloneTarget() ?: return r("C2", CheckStatus.SKIPPED, "choose an app to clone first (in this copy)")
        Shell.unavailableReason()?.let { return r("C2", CheckStatus.SKIPPED, it) }
        val vault = Env.vaultUser() ?: return r("C2", CheckStatus.SKIPPED, "there is no Vault yet")
        val res = Shell.run(ShellCommands.installExisting(vault.id, pkg))
        val there = runCatching { Env.app.getSystemService(LauncherApps::class.java).getApplicationInfo(pkg, 0, vault); true }.getOrDefault(false)
        return r("C2", if (there) CheckStatus.PASS else CheckStatus.FAIL, if (there) "$pkg is in the Vault (shell install-existing)" else "$pkg is not in the Vault after the command", listOf(res.evidence(), "in the Vault now (LauncherApps): $there"))
    }

    private suspend fun c3(host: Host?): CheckResult {
        sideOnly("C3", vault = true)?.let { return it }
        host ?: return r("C3", CheckStatus.SKIPPED, "needs the screen (Android may ask you to confirm)")
        val pkg = cloneTarget() ?: return r("C3", CheckStatus.SKIPPED, "choose an app to clone first (in this copy)")
        if (Cloner.installedHere(Env.app, pkg)) return r("C3", CheckStatus.SKIPPED, "$pkg is already in the Vault: remove it from the Vault first, or choose another app")
        val lines = Cloner.withInstallerSession(Env.app, pkg)
        return r("C3", CheckStatus.RUNNING, "installer session sent: waiting for Android (it may ask you to confirm)", lines)
    }

    private fun c4(): CheckResult {
        sideOnly("C4", vault = true)?.let { return it }
        val pkg = cloneTarget() ?: return r("C4", CheckStatus.SKIPPED, "choose an app to clone first (in this copy)")
        val info = runCatching { Cloner.personalAppInfo(Env.app, pkg) }.getOrNull()
        val system = info != null && info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        if (!system) return r("C4", CheckStatus.SKIPPED, "$pkg is not built into the phone (only system apps can be switched on this way)")
        if (Cloner.installedHere(Env.app, pkg)) return r("C4", CheckStatus.SKIPPED, "$pkg is already in the Vault")
        val (ok, lines) = Cloner.withEnableSystemApp(Env.app, pkg)
        return r("C4", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "system app $pkg switched on in the Vault" else "$pkg is still not in the Vault", lines)
    }

    // ---------------------------------------------------------------- F: storage for a Vault app

    private fun vaultApp(): String? = Prefs.app.getString(Prefs.VAULT_APP_TARGET, null)

    private val storagePermissions: List<String> = buildList {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private fun requested(pkg: String): List<String> = runCatching {
        Env.app.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES).requestedPermissions?.toList()
    }.getOrNull() ?: emptyList()

    private fun label(pkg: String): String = runCatching {
        val pm = Env.app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString()
    }.getOrDefault(pkg)

    private fun grantStateName(state: Int) = when (state) {
        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "granted by policy"
        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "denied by policy"
        else -> "user decides"
    }

    private fun fPolicy(id: String, state: Int): CheckResult {
        sideOnly(id, vault = true)?.let { return it }
        val pkg = vaultApp() ?: return r(id, CheckStatus.SKIPPED, "choose a Vault app first (in this copy)")
        val perms = requested(pkg).filter { it in storagePermissions }
        if (perms.isEmpty()) return r(id, CheckStatus.SKIPPED, "${label(pkg)} asks for no storage or media permission: choose another app")
        val lines = mutableListOf<String>()
        var anySet = false
        for (p in perms) {
            val set = runCatching { Env.dpm.setPermissionGrantState(Env.admin, pkg, p, state) }
            anySet = anySet || set.getOrDefault(false)
            val now = Env.dpm.getPermissionGrantState(Env.admin, pkg, p)
            val granted = Env.app.packageManager.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
            lines += "${p.substringAfterLast('.')}: set ${set.getOrElse { it.asEvidence() }} → ${grantStateName(now)}, permission ${if (granted) "granted" else "not granted"}"
        }
        val app = label(pkg)
        return when (state) {
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED ->
                if (anySet) r(id, CheckStatus.NEEDS_YOU, "denied by policy; now check $app", lines, "Open $app in the Vault. Does it still see your photos or files?")
                else r(id, CheckStatus.FAIL, "Android refused the policy for $app", lines)
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED ->
                if (anySet) r(id, CheckStatus.NEEDS_YOU, "granted by policy; now check $app", lines, "Open $app in the Vault. Does it see your photos or files now?")
                else r(id, CheckStatus.FAIL, "Android refused the policy for $app", lines)
            else -> r(id, CheckStatus.PASS, "$app's storage permissions are back in your hands", lines)
        }
    }

    private fun f3(): CheckResult {
        sideOnly("F3", vault = true)?.let { return it }
        val pkg = vaultApp() ?: return r("F3", CheckStatus.SKIPPED, "choose a Vault app first (in this copy)")
        val lines = mutableListOf<String>()
        lines += "${label(pkg)} asks for All files (MANAGE_EXTERNAL_STORAGE): ${Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested(pkg)}"
        val set = runCatching { Env.dpm.setPermissionGrantState(Env.admin, pkg, Manifest.permission.MANAGE_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        lines += "setPermissionGrantState(MANAGE_EXTERNAL_STORAGE, GRANTED): ${set.getOrElse { it.asEvidence() }}"
        val uid = Env.app.packageManager.getPackageUid(pkg, 0)
        val mode = OwnerAppOps.mode(OwnerAppOps.OP_ALL_FILES, uid, pkg)
        lines += "All files app-op now: ${OwnerAppOps.modeName(mode)}"
        runCatching { Env.dpm.setPermissionGrantState(Env.admin, pkg, Manifest.permission.MANAGE_EXTERNAL_STORAGE, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }
        val ok = mode == AppOpsManager.MODE_ALLOWED
        return r("F3", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "unexpected: the policy gave All files" else "the policy cannot give All files (as expected: it is an app-op)", lines)
    }

    private fun opsFor(pkg: String): List<String> {
        val req = requested(pkg)
        val ops = req.filter { it in storagePermissions }.mapNotNull { AppOpsManager.permissionToOp(it) }.toMutableList()
        if (Manifest.permission.MANAGE_EXTERNAL_STORAGE in req) ops += OwnerAppOps.OP_ALL_FILES
        return ops.distinct()
    }

    private fun fOwnerOps(id: String, mode: Int): CheckResult {
        sideOnly(id, vault = true)?.let { return it }
        val pkg = vaultApp() ?: return r(id, CheckStatus.SKIPPED, "choose a Vault app first (in this copy)")
        val ops = opsFor(pkg)
        if (ops.isEmpty()) return r(id, CheckStatus.SKIPPED, "${label(pkg)} asks for no storage, media or All files access: choose another app")
        val uid = Env.app.packageManager.getPackageUid(pkg, 0)
        val lines = mutableListOf("uid of ${label(pkg)} in the Vault: $uid")
        var anyOk = false
        var firstError: String? = null
        for (op in ops) {
            val before = OwnerAppOps.mode(op, uid, pkg)
            val set = runCatching { OwnerAppOps.setMode(op, uid, pkg, mode) }
            val after = OwnerAppOps.mode(op, uid, pkg)
            if (set.isSuccess && after == mode) anyOk = true
            set.exceptionOrNull()?.let { if (firstError == null) firstError = it.asEvidence() }
            lines += "$op: ${OwnerAppOps.modeName(before)} → ${OwnerAppOps.modeName(after)}${set.exceptionOrNull()?.let { " (error: ${it.asEvidence()})" } ?: ""}"
        }
        val app = label(pkg)
        if (!anyOk) return r(id, CheckStatus.FAIL, "the profile owner could not change app-ops here: ${firstError ?: "the modes did not change"}", lines)
        return when (mode) {
            AppOpsManager.MODE_ERRORED -> r(id, CheckStatus.NEEDS_YOU, "app-ops denied by the owner; now check $app", lines, "Open $app in the Vault. Does it still see your photos or files?")
            AppOpsManager.MODE_ALLOWED -> r(id, CheckStatus.NEEDS_YOU, "app-ops allowed by the owner; now check $app", lines, "Open $app in the Vault. Does it see your photos or files now?")
            else -> r(id, CheckStatus.PASS, "$app's app-ops are back to default", lines)
        }
    }

    private val shellOps = listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "MANAGE_EXTERNAL_STORAGE")

    private suspend fun fShellOps(id: String, mode: String): CheckResult {
        sideOnly(id, vault = false)?.let { return it }
        val pkg = vaultApp() ?: return r(id, CheckStatus.SKIPPED, "choose a Vault app first (in this copy)")
        Shell.unavailableReason()?.let { return r(id, CheckStatus.SKIPPED, it) }
        val vault = Env.vaultUser()?.id ?: return r(id, CheckStatus.SKIPPED, "there is no Vault yet")
        val lines = mutableListOf<String>()
        var matched = 0
        for (op in shellOps) {
            val set = Shell.run(ShellCommands.appOpsSet(vault, pkg, op, mode))
            val get = Shell.run(ShellCommands.appOpsGet(vault, pkg, op))
            val now = ShellCommands.parseAppOpMode(get.output, op)
            if (set.ok && (now == mode || (mode == "default" && now == null))) matched++
            lines += "$op: set exit ${set.exitCode}${if (set.output.isNotBlank()) " (${set.output.take(120)})" else ""} → ${now ?: "no entry (default)"}"
        }
        val app = label(pkg)
        if (matched == 0) return r(id, CheckStatus.FAIL, "the shell could not set the app-ops", lines)
        return when (mode) {
            "deny" -> r(id, CheckStatus.NEEDS_YOU, "app-ops denied by the shell ($matched of ${shellOps.size}); now check $app", lines, "Open $app in the Vault. Does it still see your photos or files?")
            "allow" -> r(id, CheckStatus.NEEDS_YOU, "app-ops allowed by the shell ($matched of ${shellOps.size}); now check $app", lines, "Open $app in the Vault. Does it see your photos or files now?")
            else -> r(id, CheckStatus.PASS, "$app's app-ops are back to default", lines)
        }
    }

    private fun f8(mode: Int): CheckResult {
        val id = if (mode == AppOpsManager.MODE_DEFAULT) "F8-undo" else "F8"
        sideOnly(id, vault = true)?.let { return it }
        val pkg = Env.app.packageName
        val uid = android.os.Process.myUid()
        val before = Environment.isExternalStorageManager()
        val set = runCatching { OwnerAppOps.setMode(OwnerAppOps.OP_ALL_FILES, uid, pkg, mode) }
        val after = Environment.isExternalStorageManager()
        val lines = mutableListOf(
            "All files for this app before: $before",
            "owner app-op set to ${OwnerAppOps.modeName(mode)}: ${set.exceptionOrNull()?.asEvidence() ?: "ok"}",
            "All files for this app now: $after",
        )
        if (after) lines += "the Vault's storage root lists ${Environment.getExternalStorageDirectory().list()?.size ?: 0} items for this app"
        if (mode == AppOpsManager.MODE_DEFAULT) return r(id, CheckStatus.PASS, "All files for this app is back to default ($after)", lines)
        return r(id, if (after) CheckStatus.PASS else CheckStatus.FAIL, if (after) "the owner app-op gave this app All files in the Vault" else "All files did not turn on", lines)
    }

    // ---------------------------------------------------------------- L: channel

    private suspend fun l0(host: Host?, extras: Map<String, String>): CheckResult {
        val lines = mutableListOf<String>()
        val other = Channel.otherUserId() ?: return r("L0", CheckStatus.SKIPPED, if (Env.isVaultSide) "no personal user found" else "there is no Vault yet")
        when (extras["connect"]) {
            "forwarded" -> {
                host ?: return r("L0", CheckStatus.SKIPPED, "connecting needs the screen")
                val action = if (Env.isVaultSide) Channel.ACTION_TO_PERSONAL else Channel.ACTION_TO_VAULT
                lines += "sent this copy's permission with a forwarded intent ($action)"
                val (code, data) = host.startForResult(Channel.grantIntent(action, Channel.VIA_FORWARDED))
                lines += "the other copy answered with result $code"
                lines += Channel.takeGrant(Env.app, data, "the other copy's answer (forwarded intent)")
            }
            "cpa" -> {
                host ?: return r("L0", CheckStatus.SKIPPED, "connecting needs the screen")
                val helloBefore = Prefs.app.getString(Prefs.CHANNEL_HELLO, null)
                lines += withContext(Dispatchers.Main) { Channel.pushWithCrossProfileApps(host.activity) }
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < deadline && Prefs.app.getString(Prefs.CHANNEL_HELLO, null) == helloBefore) delay(200)
                lines += if (Prefs.app.getString(Prefs.CHANNEL_HELLO, null) != helloBefore) "the personal copy said hello: it received the permission through CrossProfileApps"
                else "no hello from the personal copy within 8 s"
            }
        }
        val ready = Channel.ready()
        lines += "this copy may call the other copy (user $other): $ready"
        lines += "how it got that permission: ${Prefs.app.getString(Prefs.CHANNEL_VIA, "never")}"
        lines += "last hello from the other copy: ${Prefs.app.getString(Prefs.CHANNEL_HELLO, "never")}"
        if (Env.isVaultSide) lines += "CrossProfileApps.canInteractAcrossProfiles: ${runCatching { Env.crossProfile.canInteractAcrossProfiles() }.getOrElse { it.asEvidence() }}"
        lines += "lasting permissions this copy holds: " + Env.app.contentResolver.persistedUriPermissions.joinToString { it.uri.toString() }.ifEmpty { "none" }
        if (!ready) return r("L0", CheckStatus.FAIL, "this copy cannot call the other copy yet: tap \"Connect\"", lines)
        val ping = runCatching { Channel.call("ping") }
        ping.exceptionOrNull()?.let { lines += "calling the other copy failed: ${it.asEvidence()}" }
        val otherReady = ping.getOrNull()?.getBoolean("channelReady") ?: false
        lines += "the other copy may call this copy: $otherReady"
        BridgeProvider.notifyRootsChanged("${Env.app.packageName}.bridge.personal")
        return if (otherReady) r("L0", CheckStatus.PASS, "both copies can call each other", lines)
        else r("L0", CheckStatus.FAIL, "only one way works: tap \"Connect\" in the other copy too", lines)
    }

    private fun l1(): CheckResult {
        if (!Channel.ready()) return r("L1", CheckStatus.SKIPPED, "the channel is not set up yet (L0)")
        val started = SystemClock.elapsedRealtime()
        val b = Channel.call("ping") ?: return r("L1", CheckStatus.FAIL, "the other copy gave no answer")
        val ms = SystemClock.elapsedRealtime() - started
        val lines = listOf(
            "answer in $ms ms from user ${b.getInt("user")} (${b.getString("side")} side)",
            "version ${b.getString("version")}, profile owner ${b.getBoolean("profileOwner")}, shell ${b.getBoolean("shellAlive")}",
        )
        return r("L1", CheckStatus.PASS, "the other copy answered in $ms ms", lines)
    }

    // ---------------------------------------------------------------- B: bridge

    private fun b0(): CheckResult {
        sideOnly("B0", vault = true)?.let { return it }
        val lines = TestFolder.ensure()
        BridgeProvider.notifyRootsChanged("${Env.app.packageName}.bridge.vault")
        return r("B0", CheckStatus.PASS, "the test folder is ready in the Vault", lines)
    }

    private fun b1Vault(): CheckResult {
        val lines = TestFolder.ensure() + PickerFilters.enable()
        BridgeProvider.notifyRootsChanged("${Env.app.packageName}.bridge.vault")
        return r("B1", CheckStatus.PASS, "the Work tab is open to personal apps; now run B1 in the personal copy (\"Undo\" closes it)", lines + "allowed apps here: ${BridgeAllowlist.get().ifEmpty { setOf("only Liquid Vault") }}")
    }

    private fun b2Vault(): CheckResult {
        val lines = TestFolder.ensure().toMutableList()
        lines += "the channel to the personal copy is ready: ${Channel.ready()}"
        return r("B2", CheckStatus.PASS, "the test folder is ready; bridge B reads it through the channel. Now run B2 in the personal copy", lines)
    }

    private suspend fun bPick(id: String, host: Host?, initial: Uri): CheckResult {
        host ?: return r(id, CheckStatus.SKIPPED, "needs the screen: you pick the folder in the system picker")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (initial != Uri.EMPTY) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        DebugLog.event("Bridge", "$id: opening the system picker for a folder")
        val (code, data) = host.startForResult(intent)
        val tree = data?.data ?: return r(id, CheckStatus.FAIL, "no folder was picked (result $code)")
        val lines = mutableListOf("picked: $tree", "provider: ${tree.authority}")
        val expected = if (id == "B1") "${Env.vaultUser()?.id}@${Env.app.packageName}.bridge.vault" else "${Env.app.packageName}.bridge.personal"
        if (tree.authority != expected) lines += "note: this is not the provider this check expects ($expected)"
        val keep = runCatching { Env.app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        lines += "lasting folder grant kept: ${keep.exceptionOrNull()?.asEvidence() ?: "yes"}"
        Prefs.app.edit().putString(if (id == "B1") Prefs.B1_TREE else Prefs.B2_TREE, tree.toString()).apply()
        val listing = readTree(tree, lines)
        val ok = keep.isSuccess && listing
        val leakQuestion = if (id == "B1" && tree.authority?.endsWith(".bridge.vault") == true) "In the picker's Work tab, which folders did you see?" else null
        return when {
            !ok -> r(id, CheckStatus.FAIL, "the folder could not be kept or read", lines)
            tree.authority != expected -> r(id, CheckStatus.FAIL, "a folder was opened, but not through the bridge this check tests", lines)
            leakQuestion != null -> r(id, CheckStatus.NEEDS_YOU, "the Vault folder opened from the personal side with a lasting grant; one question", lines, leakQuestion)
            else -> r(id, CheckStatus.PASS, "the Vault folder opened from the personal side with a lasting grant", lines)
        }
    }

    /**
     * Without the picker (for the emulator test in CI): this copy reads its own bridge provider directly, which
     * exercises the provider, the rules and, for bridge B, the channel to the Vault copy. Needs no grant (same app).
     */
    private fun selfTest(id: String, authority: String, vault: Boolean): CheckResult {
        sideOnly(id, vault)?.let { return it }
        val tree = DocumentsContract.buildTreeDocumentUri(authority, BridgeProvider.ROOT_DOC)
        val lines = mutableListOf("self-test (no picker): $tree")
        val ok = readTree(tree, lines)
        return r(id, if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "self-test: the provider lists the test folder and reads hello.txt" else "self-test failed", lines)
    }

    /** Lists a picked folder and reads hello.txt from it. True when both worked. */
    private fun readTree(tree: Uri, lines: MutableList<String>): Boolean {
        val resolver = Env.app.contentResolver
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootDoc)
        val names = mutableListOf<Pair<String, String>>()
        val listed = runCatching {
            resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) names += c.getString(0) to c.getString(1)
            }
        }
        lines += "listing: ${listed.exceptionOrNull()?.asEvidence() ?: names.joinToString { it.second }.ifEmpty { "(empty)" }}"
        val hello = names.firstOrNull { it.second == "hello.txt" } ?: return listed.isSuccess.also { lines += "hello.txt not found in the folder" }
        val text = runCatching {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, hello.first))?.bufferedReader()?.use { it.readLine() }
        }
        lines += "hello.txt says: ${text.getOrElse { it.asEvidence() }}"
        return listed.isSuccess && text.isSuccess
    }

    private fun b3(): CheckResult {
        val own = Env.app.packageName
        val pickers = BridgeAllowlist.systemPickers()
        val mine = AccessLog.entries()
        val remote = if (Channel.ready()) runCatching {
            Channel.call("run", "access-log")?.getString("json")?.lines()?.filter { it.isNotBlank() }?.mapNotNull { runCatching { VaultJson.decodeFromString(AccessEntry.serializer(), it) }.getOrNull() }
        }.getOrNull() ?: emptyList() else emptyList()
        val refused = (mine + remote).filter { !it.allowed && it.callerPackage != own && it.callerPackage !in pickers }.sortedBy { it.atMillis }
        val lines = dev.liquid.vault.core.common.Report.accessLines(refused.takeLast(8))
        return if (refused.isNotEmpty()) r("B3", CheckStatus.PASS, "${refused.size} refused access(es) by ${refused.map { it.callerPackage }.distinct().joinToString()}", lines)
        else r("B3", CheckStatus.NOT_RUN, "no app has been refused yet: pick the test folder from another app's folder picker", listOf("access log entries on this side: ${mine.size}; from the other side: ${remote.size}"))
    }

    /** The access log as JSON lines, for the other copy's B3 (channel call "run" with "access-log"). */
    fun accessLogJson(): String = AccessLog.entries().takeLast(50).joinToString("\n") { VaultJson.encodeToString(AccessEntry.serializer(), it) }

    // ---------------------------------------------------------------- Q: pause / resume

    private fun q0(): CheckResult {
        sideOnly("Q0", vault = false)?.let { return it }
        val granted = Env.app.checkSelfPermission(MODIFY_QUIET_MODE) == PackageManager.PERMISSION_GRANTED
        return if (granted) r("Q0", CheckStatus.PASS, "MODIFY_QUIET_MODE is granted")
        else r("Q0", CheckStatus.FAIL, "not granted yet", listOf("on your PC: adb shell pm grant ${Env.app.packageName} android.permission.MODIFY_QUIET_MODE"))
    }

    private suspend fun quiet(pause: Boolean): CheckResult {
        val id = if (pause) "Q1" else "Q2"
        sideOnly(id, vault = false)?.let { return it }
        val vault = Env.vaultUser() ?: return r(id, CheckStatus.SKIPPED, "there is no Vault yet")
        if (Env.app.checkSelfPermission(MODIFY_QUIET_MODE) != PackageManager.PERMISSION_GRANTED) {
            return r(id, CheckStatus.SKIPPED, "needs MODIFY_QUIET_MODE (Q0)")
        }
        val before = Env.um.isQuietModeEnabled(vault)
        val answer = withContext(Dispatchers.Main) { Env.um.requestQuietModeEnabled(pause, vault) }
        val deadline = SystemClock.elapsedRealtime() + if (pause) 10_000 else 30_000
        while (SystemClock.elapsedRealtime() < deadline && Env.um.isQuietModeEnabled(vault) != pause) delay(250)
        val after = Env.um.isQuietModeEnabled(vault)
        val lines = listOf(
            "paused before: $before",
            "requestQuietModeEnabled($pause) returned $answer${if (!pause && !answer) " (Android asks for the Vault's unlock first)" else ""}",
            "paused after: $after",
        )
        val ok = after == pause
        return r(id, if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) (if (pause) "the Vault is paused" else "the Vault is running again") else "the Vault's state did not change", lines)
    }

    // ---------------------------------------------------------------- R: after a reboot

    private fun bootRecord(): BootRecord? = Prefs.app.getString(Prefs.BOOT_RECORD, null)?.let {
        runCatching { VaultJson.decodeFromString(BootRecord.serializer(), it) }.getOrNull()
    }

    private fun currentBootWall(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun r1(): CheckResult {
        val rec = bootRecord() ?: return r("R1", CheckStatus.SKIPPED, "no reboot recorded yet: restart the phone, unlock it, then run R1", listOf("if it still says this after a reboot, MagicOS stopped Liquid Vault from starting at boot: Settings › Apps › App launch › Liquid Vault › Manage manually › Auto-launch on"))
        val same = rec.sameBootAs(currentBootWall())
        val lines = listOf(
            "recorded at ${java.util.Date(rec.recordedAtMillis)} for the boot at ${java.util.Date(rec.bootWallMillis)}",
            "this boot: ${java.util.Date(currentBootWall())}",
            "then: profile owner ${rec.profileOwner}, organisation \"${rec.organizationName}\", screenshots blocked ${rec.screenCaptureDisabled}, lasting permissions ${rec.channelGrants}, shell ${rec.shellAlive}, Vault paused ${rec.quietMode}",
        )
        return if (same) r("R1", CheckStatus.PASS, "this copy started after the last reboot and recorded what it found", lines)
        else r("R1", CheckStatus.FAIL, "the record is from an earlier boot: this copy did not start at the last boot", lines + "MagicOS may block start at boot: allow Liquid Vault to start automatically")
    }

    private fun r2(): CheckResult {
        sideOnly("R2", vault = true)?.let { return it }
        val owner = Env.isVaultSide
        val org = runCatching { Env.dpm.getOrganizationName(Env.admin)?.toString() }.getOrNull()
        val shots = runCatching { Env.dpm.getScreenCaptureDisabled(Env.admin) }.getOrNull()
        val lines = listOf("profile owner: $owner", "organisation name: $org", "screenshots blocked: $shots (P1 leaves it off)", "Work tab for personal apps (bridge A) on: ${PickerFilters.isOn()}")
        val ok = owner && org == "Liquid Vault"
        return r("R2", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "still the owner; policies as they were" else "something changed", lines)
    }

    private fun r3(): CheckResult {
        val lines = mutableListOf<String>()
        val channel = Channel.ready()
        lines += "channel permission still held: $channel"
        val ping = if (channel) runCatching { Channel.call("ping") } else null
        ping?.let { lines += "the other copy answers: ${it.exceptionOrNull()?.asEvidence() ?: "yes"}" }
        var trees = 0
        var treesOk = 0
        if (!Env.isVaultSide) for (key in listOf(Prefs.B1_TREE, Prefs.B2_TREE)) {
            val uri = Prefs.app.getString(key, null)?.let(Uri::parse) ?: continue
            trees++
            val sub = mutableListOf<String>()
            val ok = runCatching { readTree(uri, sub) }.getOrDefault(false)
            if (ok) treesOk++
            lines += "${if (key == Prefs.B1_TREE) "B1" else "B2"} folder grant: ${if (ok) "still opens" else "does not open"}"
            sub.forEach { lines += "  $it" }
        }
        val ok = channel && ping?.isSuccess == true && treesOk == trees
        return r("R3", if (ok) CheckStatus.PASS else CheckStatus.FAIL, if (ok) "channel and $treesOk folder grant(s) work" else "something no longer works", lines)
    }

    private fun r4(): CheckResult {
        val alive = Shell.alive
        val lines = listOf("shell running now: $alive", "boot record says the shell was running at boot: ${bootRecord()?.shellAlive}")
        return if (!alive) r("R4", CheckStatus.PASS, "the shell is off: shell checks say SKIPPED (needs shell), everything else works without it", lines)
        else r("R4", CheckStatus.INFO, "the shell is running now; for this test, reboot and do not start Shevery", lines)
    }

    // ---------------------------------------------------------------- D: destroy

    private suspend fun d1(extras: Map<String, String>): CheckResult {
        if (!Confirm.matches(extras["confirm"] ?: "", Confirm.DESTROY_VAULT)) return r("D1", CheckStatus.FAIL, "not done: the typed confirmation did not match")
        if (Env.isVaultSide) {
            ChannelCalls.destroyNow(Env.app, "the Vault copy's Destroy button")
            return r("D1", CheckStatus.RUNNING, "removing the Vault now")
        }
        val vault = Env.vaultUser() ?: return r("D1", CheckStatus.SKIPPED, "there is no Vault")
        val lines = mutableListOf<String>()
        var asked = false
        if (Channel.ready()) {
            val res = runCatching { Channel.call("destroy", Confirm.DESTROY_VAULT) }
            lines += "asked the Vault copy through the channel: ${res.exceptionOrNull()?.asEvidence() ?: "it accepted"}"
            asked = res.isSuccess
        }
        if (!asked && Shell.unavailableReason() == null) {
            val res = Shell.run(ShellCommands.removeUser(vault.id), 120_000)
            lines += res.evidence()
            asked = ShellCommands.removeUserSucceeded(res.output)
        }
        if (!asked) return r("D1", CheckStatus.FAIL, "could not ask for the removal: open the Vault copy and use Destroy there", lines)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline && Env.otherProfiles().any { it == vault }) delay(500)
        val gone = Env.otherProfiles().none { it == vault }
        lines += "the Vault's user ${vault.id} is ${if (gone) "gone" else "still there"}"
        if (gone) Prefs.app.edit().remove(Prefs.B1_TREE).remove(Prefs.B2_TREE).remove(Prefs.CHANNEL_VIA).remove(Prefs.CHANNEL_HELLO).apply()
        return r("D1", if (gone) CheckStatus.PASS else CheckStatus.FAIL, if (gone) "the Vault was destroyed" else "the Vault is still there after 30 s", lines)
    }
}
