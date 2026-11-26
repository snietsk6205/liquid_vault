package dev.liquid.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import dev.liquid.vault.AppSide
import dev.liquid.vault.Env
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.designsystem.KitNavEasing
import dev.liquid.vault.core.designsystem.KitNavMs
import dev.liquid.vault.diag.Host
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The app's places (Phase 1). The personal copy opens on [HOME]; the Vault copy on [VAULT_COPY]. */
enum class Screen(val key: String) {
    HOME("home"),
    APPS_VAULT("apps-vault"),
    APPS_PERSONAL("apps-personal"),
    LOCK("lock"),
    VPN("vpn"),
    SETTINGS("settings"),
    DIAGNOSTICS("diagnostics"),
    WHATS_NEW("whats-new"),
    VAULT_COPY("vault-copy"),
    RULES("rules"),
    ACCESS_LOG("access-log");

    companion object {
        fun of(key: String?): Screen? = entries.firstOrNull { it.key == key }
    }
}

/** One entry of the back stack; [depth] gives a page push its direction. */
private data class Entry(val screen: Screen, val depth: Int)

/**
 * The app's frame: a back stack of [Screen]s with WM Keyboard's page push (the new page comes from the right, the one
 * behind drifts a third left). [navigate] receives places asked from outside (a notification, the other copy).
 */
@Composable
fun AppRoot(host: Host, refresh: StateFlow<Int>, navigate: Flow<String>, onFinish: () -> Unit) {
    val tick by refresh.collectAsState()
    val vaultSide = remember(tick) { Env.side() == AppSide.VAULT }
    val root = if (vaultSide) Screen.VAULT_COPY else Screen.HOME
    val stack = remember(vaultSide) { mutableStateListOf(Entry(root, 0)) }
    fun push(screen: Screen) {
        if (stack.last().screen != screen) stack.add(Entry(screen, stack.size))
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onFinish()
    }
    LaunchedEffect(navigate) { navigate.collect { key -> Screen.of(key)?.let(::push) } }
    BackHandler(enabled = stack.size > 1) { pop() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = {
            val forward = targetState.depth > initialState.depth
            val spec = tween<androidx.compose.ui.unit.IntOffset>(KitNavMs, easing = KitNavEasing)
            (slideInHorizontally(spec) { if (forward) it else -it / 3 } + fadeIn(tween(KitNavMs / 2)))
                .togetherWith(slideOutHorizontally(spec) { if (forward) -it / 3 else it } + fadeOut(tween(KitNavMs / 2)))
        },
        label = "page",
    ) { entry ->
        when (entry.screen) {
            Screen.HOME -> HomeScreen(host, tick, open = ::push, onClose = ::pop)
            Screen.APPS_VAULT -> AppsScreen(host, Place.VAULT, tick, onBack = ::pop)
            Screen.APPS_PERSONAL -> AppsScreen(host, Place.PERSONAL, tick, onBack = ::pop)
            Screen.LOCK -> VaultLockScreen(host, tick, onBack = ::pop)
            Screen.VPN -> VpnScreen(host, onBack = ::pop)
            Screen.SETTINGS -> SettingsScreen(host, onBack = ::pop)
            Screen.DIAGNOSTICS -> DiagnosticsScreen(host, refresh, onClose = ::pop)
            Screen.WHATS_NEW -> WhatsNewScreen(onBack = ::pop)
            Screen.VAULT_COPY -> VaultCopyScreen(host, open = ::push, onClose = ::pop)
            Screen.RULES -> AccessRulesScreen(host, tick, open = ::push, onBack = ::pop)
            Screen.ACCESS_LOG -> AccessLogScreen(onBack = ::pop)
        }
    }
}
