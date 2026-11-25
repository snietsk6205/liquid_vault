package dev.liquid.vault.core.common

/** Where a check runs: in the personal copy of the app, in the Vault copy (the profile owner), or in both. */
enum class Side(val label: String) {
    PERSONAL("Personal side"),
    VAULT("Vault side"),
    BOTH("Both sides"),
}

/** A Phase 0 check as Diagnostics lists it: what it is called, where it runs, and what it does in plain words. */
data class CheckDef(
    val id: String,
    val group: String,
    val title: String,
    val side: Side,
    val explains: String,
)

/**
 * The Phase 0 checks (docs/PLAN.md, "The checks"), in the order Diagnostics shows them. The ids are kept forever: the
 * reports Med sends back refer to them.
 */
object PhaseZero {
    const val G_DEVICE = "Device"
    const val G_SHELL = "Shell (Shevery)"
    const val G_CREATE = "Create the Vault"
    const val G_POLICY = "Profile owner"
    const val G_CLONE = "Clone an app"
    const val G_STORAGE = "Storage for a Vault app"
    const val G_CHANNEL = "Channel between the two copies"
    const val G_BRIDGE = "The bridge"
    const val G_PAUSE = "Pause and resume"
    const val G_REBOOT = "After a reboot"
    const val G_DESTROY = "Destroy"

    val checks: List<CheckDef> = listOf(
        CheckDef("A1", G_DEVICE, "Device and app", Side.BOTH, "Phone, Android and MagicOS versions, this app's build, which side this copy runs on."),
        CheckDef("A2", G_DEVICE, "Profiles on this phone", Side.BOTH, "The profiles Android reports, whether a work profile may be created, and where this app is installed."),

        CheckDef("S1", G_SHELL, "Shell server found", Side.BOTH, "Whether Shevery sent its connection, through which door (Shevery's own client API or the official Shizuku API), its version and user. The Vault copy does not need the shell (the personal copy runs shell commands for the Vault), and Shevery cannot reach it while the Vault blocks the shell (P2)."),
        CheckDef("S2", G_SHELL, "Shell permission", Side.BOTH, "Whether Shevery allows this app; the button asks Shevery to show its Allow dialog."),
        CheckDef("S3", G_SHELL, "Run a command", Side.BOTH, "Runs `id` and `getprop` through the shell and checks they run as the shell user (uid 2000)."),

        CheckDef("V0", G_CREATE, "No work profile yet", Side.PERSONAL, "Looks for any work profile already on the phone. Creating the Vault is refused while one exists."),
        CheckDef("V1", G_CREATE, "Create with Android's setup", Side.PERSONAL, "Asks Android to create the work profile with this app as its owner (the normal way; HONOR may block it)."),
        CheckDef("V2", G_CREATE, "Create with the shell", Side.PERSONAL, "Insular's manual way through Shevery: create the profile, install this app in it, make it the owner, start it, open it once. Every command is logged."),
        CheckDef("V3", G_CREATE, "The Vault is ours", Side.BOTH, "This app owns the work profile, and its setup in the Vault finished."),

        CheckDef("P1", G_POLICY, "A harmless policy on and off", Side.VAULT, "Turns \"block screenshots in the Vault\" on, reads it back, turns it off, reads it back."),
        CheckDef("P2", G_POLICY, "Shell access to the Vault", Side.BOTH, "Android refuses every shell command (adb, Shevery) that names a profile with the restriction \"no debugging features\": C2's error. The Vault copy reads it and, as the owner, can lift it (\"Let the shell in\") or put it back. The personal copy checks with the shell whether the shell reaches the Vault, and where the restriction comes from (`dumpsys user`): the profile itself, or device policy."),

        CheckDef("C1", G_CLONE, "Clone: owner's install-existing", Side.VAULT, "DevicePolicyManager.installExistingPackage. Android normally refuses this for a work profile like ours; recorded either way."),
        CheckDef("C2", G_CLONE, "Clone: shell install-existing", Side.PERSONAL, "`pm install-existing --user <Vault> <app>` through Shevery."),
        CheckDef("C3", G_CLONE, "Clone: Vault installer", Side.VAULT, "Copies the app's APK files into an installer session inside the Vault (Insular's way, no shell). Android may ask you to confirm."),
        CheckDef("C4", G_CLONE, "Clone: system app", Side.VAULT, "enableSystemApp, only for apps built into the phone."),

        CheckDef("F1", G_STORAGE, "Deny storage + media (owner policy)", Side.VAULT, "setPermissionGrantState DENIED for every storage, photo, video and audio permission the chosen Vault app asks for, read back, then you check whether the app still shows the test photo. Needs an app that reads your photos itself (a gallery such as Aves): an app built for Android 13+ that asks for no photo permission uses the system picker, which needs no permission."),
        CheckDef("F2", G_STORAGE, "Allow storage + media (owner policy)", Side.VAULT, "setPermissionGrantState GRANTED for the same permissions, then you check the app; \"Undo\" gives control back to you (DEFAULT)."),
        CheckDef("F3", G_STORAGE, "All files (owner policy)", Side.VAULT, "setPermissionGrantState for MANAGE_EXTERNAL_STORAGE. Expected to fail: it is an app-op, not a runtime permission."),
        CheckDef("F4", G_STORAGE, "Deny with app-ops (owner, no shell)", Side.VAULT, "As the owner (Android lets a profile owner change app-ops in its own profile), sets the Vault app's storage, media and All files app-ops and a test op tied to no permission to deny, reads each right after and 1 s later, then puts the test op back. Android keeps the app-ops of permissions the app asks for in step with those permissions (it puts them back), so only the others count."),
        CheckDef("F5", G_STORAGE, "Allow with app-ops (owner, no shell)", Side.VAULT, "The same ops set to allow, read right after and 1 s later; \"Undo\" puts back what they were before F4/F5."),
        CheckDef("F6", G_STORAGE, "Deny with app-ops (shell)", Side.PERSONAL, "Like F4 with the shell: `appops set --user <Vault> <app> <op> deny`, read back with `appops get` right after and 1 s later, test op put back."),
        CheckDef("F7", G_STORAGE, "Allow with app-ops (shell)", Side.PERSONAL, "`appops set … allow`, read back right after and 1 s later; \"Undo\" puts back what they were before F6/F7."),
        CheckDef("F8", G_STORAGE, "All files for this app in the Vault", Side.VAULT, "Gives this app's Vault copy All files access with an owner app-op and checks Environment.isExternalStorageManager(): a proof that needs no eyes."),

        CheckDef("L0", G_CHANNEL, "Channel ready", Side.BOTH, "Each copy holds a lasting permission to call the other copy (Insular's Shuttle idea). Shows which way of setting it up worked."),
        CheckDef("L1", G_CHANNEL, "Ping the other copy", Side.BOTH, "Calls the other copy through the channel and shows its answer."),

        CheckDef("B0", G_BRIDGE, "Vault test folder", Side.VAULT, "Creates Documents/Liquid Vault test in the Vault with two small files, the folder every bridge check opens."),
        CheckDef("B1", G_BRIDGE, "Bridge A: Work tab of the system picker", Side.BOTH, "The Vault copy lets personal apps use the picker's Work tab; our Vault-side provider shows only the test folder. You pick it with this app (allowed), keep a lasting folder grant, then try with another app (refused)."),
        CheckDef("B2", G_BRIDGE, "Bridge B: \"Liquid Vault\" on the personal side", Side.BOTH, "The personal copy shows a \"Liquid Vault\" place in the normal picker and fetches the test folder through the channel. Same allowed / refused test, without opening the Work tab."),
        CheckDef("B3", G_BRIDGE, "Refused app", Side.BOTH, "Reads both copies' access logs. The system picker may browse (it works for whichever app opened it, and Android does not tell a folder provider which app that is); the app itself is checked by its own package name when it opens what it picked, with or without a lasting grant. PASS when an app that is not allowed was refused there."),

        CheckDef("Q0", G_PAUSE, "Permission to pause", Side.PERSONAL, "MODIFY_QUIET_MODE, which you grant once with adb."),
        CheckDef("Q1", G_PAUSE, "Pause the Vault", Side.PERSONAL, "requestQuietModeEnabled(true): the Vault's apps stop and its icons grey out."),
        CheckDef("Q2", G_PAUSE, "Resume the Vault", Side.PERSONAL, "requestQuietModeEnabled(false)."),

        CheckDef("R1", G_REBOOT, "Started after the reboot", Side.BOTH, "This copy recorded the reboot (BOOT_COMPLETED) and what it found then."),
        CheckDef("R2", G_REBOOT, "Still the owner, policies kept", Side.VAULT, "Profile owner, the Vault's name and the screenshot policy are as they were."),
        CheckDef("R3", G_REBOOT, "Channel and folder grants kept", Side.BOTH, "The channel still answers and the lasting folder grants (B1, B2) still open the test folder. Run it after B1 and B2."),
        CheckDef("R4", G_REBOOT, "Shell after reboot", Side.BOTH, "With Shevery off, shell checks say SKIPPED (needs shell) while everything above still works."),

        CheckDef("D1", G_DESTROY, "Destroy the Vault", Side.BOTH, "After you type DESTROY VAULT, the Vault copy removes the work profile (or the shell does, as a fallback)."),
    )

    val groups: List<String> = checks.map { it.group }.distinct()

    fun def(id: String): CheckDef = checks.first { it.id == id }

    /** The checks a copy shows: its own side's and the ones on both sides. */
    fun forSide(vaultSide: Boolean): List<CheckDef> = checks.filter {
        it.side == Side.BOTH || it.side == (if (vaultSide) Side.VAULT else Side.PERSONAL)
    }
}
