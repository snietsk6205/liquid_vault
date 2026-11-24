package dev.liquid.vault.shell

import android.os.Bundle
import rikka.shizuku.ShizukuProvider

/**
 * Where the shell server (Shevery, or official Shizuku) pushes its binder when this app starts. Shevery tries its own
 * client door first (`<package>.shevery`, for apps that ask for its PRIVILEGED_API permission), then the legacy Shizuku
 * door (`<package>.shizuku`); official Shizuku uses only the latter. Both doors hand the binder to the official Shizuku
 * client library; recording which one was used tells Med which protocol Shevery spoke (check S1, DECISIONS.md D6).
 *
 * Both are guarded by INTERACT_ACROSS_USERS_FULL (held by the shell and the system, never by apps), as Shizuku requires.
 */
abstract class ShellDoor(private val door: Shell.Door) : ShizukuProvider() {
    override fun onCreate(): Boolean {
        // Sui is a root-only variant of Shizuku: never probe for it (the phone is not rooted).
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_SEND_BINDER) Shell.onDoorUsed(door)
        return super.call(method, arg, extras)
    }
}

/** Shevery's own client door. */
class SheveryDoor : ShellDoor(Shell.Door.SHEVERY)

/** The official Shizuku client door. */
class ShizukuDoor : ShellDoor(Shell.Door.SHIZUKU)
