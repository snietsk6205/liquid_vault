package dev.liquid.vault.admin

import android.os.Bundle
import android.os.UserManager
import dev.liquid.vault.Env
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.Prefs

/**
 * Whether the shell (adb, Shevery) may reach the Vault, as the Vault's owner sees it (check P2, DECISIONS.md D26).
 *
 * Android refuses every shell command that names a user with the restriction "no debugging features"
 * (UserManager.DISALLOW_DEBUGGING_FEATURES; AOSP `UserController.handleIncomingUser`). The phone test of 0.1.0 found it
 * on the Vault (check C2), while stock Android does not put it on a new work profile. A profile owner may change this
 * restriction (it is not in AOSP's owner-immutable or device-owner-only lists), but only the part that is device
 * policy: a restriction put on the profile itself (a "base" restriction) stays whatever the owner does.
 */
object ShellAccess {
    const val KEY = UserManager.DISALLOW_DEBUGGING_FEATURES

    data class State(
        /** In force in the Vault (whatever set it). */
        val blocked: Boolean,
        /** Set as this app's device policy (null when Android would not say). */
        val ownPolicy: Boolean?,
        val inForce: List<String>,
        val setByThisApp: List<String>?,
    )

    private fun Bundle.trueKeys(): List<String> = keySet().filter { getBoolean(it, false) }.sorted()

    /** Reads it. Vault copy only (the owner). */
    fun read(): State {
        val inForce = runCatching { Env.um.userRestrictions.trueKeys() }.getOrDefault(emptyList())
        val ours = runCatching { Env.dpm.getUserRestrictions(Env.admin).trueKeys() }.getOrNull()
        return State(
            blocked = runCatching { Env.um.hasUserRestriction(KEY) }.getOrDefault(KEY in inForce),
            ownPolicy = ours?.contains(KEY),
            inForce = inForce,
            setByThisApp = ours,
        )
    }

    fun lines(s: State): List<String> = listOf(
        "\"no debugging features\" ($KEY) in force in the Vault: ${if (s.blocked) "yes, the shell is blocked" else "no"}",
        "set as Liquid Vault's own policy (as the Vault's owner): ${s.ownPolicy?.let { if (it) "yes" else "no" } ?: "Android did not say"}",
        "restrictions in force in the Vault: ${s.inForce.joinToString().ifEmpty { "none" }}",
        "of those, Liquid Vault's own policy: ${s.setByThisApp?.joinToString()?.ifEmpty { "none" } ?: "Android did not say"}",
    )

    /** Lifts it as the owner (the device-policy part; a base restriction stays). Returns what happened, for the row. */
    fun lift(): List<String> {
        val res = runCatching { Env.dpm.clearUserRestriction(Env.admin, KEY) }
        res.exceptionOrNull()?.let { DebugLog.error("Shell access", "could not clear \"no debugging features\": ${it.asEvidence()}") }
        val now = read()
        if (res.isSuccess) Prefs.app.edit().putLong(Prefs.P2_LIFTED_AT, System.currentTimeMillis()).apply()
        DebugLog.event("Shell access", "the owner cleared \"no debugging features\" in the Vault: now ${if (now.blocked) "still on" else "off (the shell may reach the Vault)"}")
        return listOf("cleared \"no debugging features\" as the Vault's owner: ${res.exceptionOrNull()?.asEvidence() ?: "done"}")
    }

    /** Puts it back on as the owner. */
    fun block(): List<String> {
        val res = runCatching { Env.dpm.addUserRestriction(Env.admin, KEY) }
        if (res.isSuccess) Prefs.app.edit().remove(Prefs.P2_LIFTED_AT).apply()
        DebugLog.event("Shell access", "the owner set \"no debugging features\" in the Vault again: ${res.exceptionOrNull()?.asEvidence() ?: "done"}")
        return listOf("set \"no debugging features\" as the Vault's owner: ${res.exceptionOrNull()?.asEvidence() ?: "done"}")
    }
}
