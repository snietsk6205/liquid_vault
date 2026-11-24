package dev.liquid.vault.core.common

/** Who is calling a bridge provider: the package Android reports and the user (profile) it runs in. */
data class BridgeCaller(val packageName: String?, val userId: Int)

sealed class BridgeDecision {
    abstract val reason: String

    data class Allowed(override val reason: String) : BridgeDecision()
    data class Refused(override val reason: String) : BridgeDecision()
}

/**
 * Phase 0's bridge rule (brief decision 4: rules by calling package, every access logged). Phase 2 replaces the
 * allowlist with the full rules (app × side × scope × read-only/read-write).
 *
 * - This app itself (either copy) is allowed: it is the built-in test client ("app A") and the channel.
 * - The system picker is allowed to browse: it acts for the app you are using, and Android does not tell a provider
 *   which app opened the picker. That app is checked when it opens what you picked.
 * - Packages in [allowed] are allowed; everything else is refused.
 */
object BridgeRules {
    fun decide(
        caller: BridgeCaller,
        ownPackage: String,
        allowed: Set<String>,
        systemPickers: Set<String>,
    ): BridgeDecision {
        val pkg = caller.packageName ?: return BridgeDecision.Refused("Android did not say which app is calling")
        return when {
            pkg == ownPackage -> BridgeDecision.Allowed("Liquid Vault itself (user ${caller.userId})")
            pkg in systemPickers -> BridgeDecision.Allowed("the system picker, browsing for the app you are using")
            pkg in allowed -> BridgeDecision.Allowed("$pkg is on the allowed list")
            else -> BridgeDecision.Refused("$pkg is not on the allowed list")
        }
    }
}

/** Typed confirmations for destructive actions (working rule: nothing destructive without one). */
object Confirm {
    const val DESTROY_VAULT = "DESTROY VAULT"
    const val REMOVE_PROFILE = "REMOVE PROFILE"

    /** Exact phrase, in capitals; only spaces around it are forgiven. */
    fun matches(typed: String, phrase: String): Boolean = typed.trim() == phrase
}
