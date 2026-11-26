package dev.liquid.vault

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.liquid.vault.bridge.PersonalBridgeProvider
import dev.liquid.vault.bridge.VaultBridgeProvider
import dev.liquid.vault.channel.ChannelToPersonalActivity
import dev.liquid.vault.channel.ChannelToVaultActivity
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.vault.VaultActionActivity

/**
 * The same APK runs on both sides, but some components belong to one side only: the Vault-side bridge provider must not
 * appear in the personal picker, and each channel activity must exist only on the side it delivers to (so an intent sent
 * across resolves to the other copy, not to ourselves). Component state is kept per user, so each copy switches its own.
 */
object SideComponents {
    private fun vaultOnly(c: Context) = listOf(
        ComponentName(c, VaultBridgeProvider::class.java),
        ComponentName(c, ChannelToVaultActivity::class.java),
        ComponentName(c, VaultActionActivity::class.java),
    )

    /** The launcher entry (an alias of MainActivity, so MainActivity itself stays startable when the icon is hidden). */
    fun launcherAlias(c: Context) = ComponentName(c.packageName, "${c.packageName}.ui.LauncherAlias")

    private fun personalOnly(c: Context) = listOf(
        ComponentName(c, PersonalBridgeProvider::class.java),
        ComponentName(c, ChannelToPersonalActivity::class.java),
    )

    fun apply(context: Context, side: AppSide = Env.side()) {
        val sideParts = when (side) {
            AppSide.VAULT -> vaultOnly(context)
            AppSide.PERSONAL -> personalOnly(context)
            AppSide.FOREIGN_PROFILE -> emptyList()
        }
        // The Vault copy's icon in the Work tab: hidden unless asked for (Q4); the personal copy's always shows.
        val iconOn = when (side) {
            AppSide.VAULT -> Prefs.of(context).getBoolean(Prefs.VAULT_ICON_VISIBLE, false)
            AppSide.PERSONAL -> true
            AppSide.FOREIGN_PROFILE -> false
        }
        val on = sideParts + if (iconOn) listOf(launcherAlias(context)) else emptyList()
        val off = (vaultOnly(context) + personalOnly(context) + launcherAlias(context)) - on.toSet()
        val pm = context.packageManager
        val changed = mutableListOf<String>()
        for (c in on) set(pm, c, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)?.let { changed += "on ${c.shortClassName}" }
        for (c in off) set(pm, c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)?.let { changed += "off ${c.shortClassName}" }
        if (changed.isNotEmpty()) DebugLog.decision("Setup", "parts switched for the ${side.label.lowercase()}: ${changed.joinToString(", ")}")
    }

    private fun set(pm: PackageManager, c: ComponentName, state: Int): Unit? {
        if (pm.getComponentEnabledSetting(c) == state) return null
        pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP)
        return Unit
    }
}
