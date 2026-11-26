package dev.liquid.vault.vault

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.liquid.vault.Env
import dev.liquid.vault.clone.Cloner
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.core.designsystem.LauncherTheme
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a foreground action in the Vault stands (VaultActionActivity shows it; the personal copy reads the last one). */
object VaultActionState {
    sealed class S {
        data object Idle : S()
        data class Working(val text: String) : S()
        data object Asking : S()
        data class Done(val ok: Boolean, val text: String) : S()
    }

    private val _state = MutableStateFlow<S>(S.Idle)
    val state: StateFlow<S> = _state

    fun working(text: String) {
        _state.value = S.Working(text)
    }

    fun asking() {
        _state.value = S.Asking
    }

    fun done(ok: Boolean, text: String) {
        _state.value = S.Done(ok, text)
        runCatching {
            Prefs.app.edit().putBoolean(Prefs.LAST_ACTION_OK, ok).putString(Prefs.LAST_ACTION_TEXT, text)
                .putLong(Prefs.LAST_ACTION_AT, System.currentTimeMillis()).apply()
        }
    }
}

/**
 * Vault copy: does what needs a screen in the Vault, for the personal copy (D43): copy an app into the Vault with an
 * installer session (Android asks you to confirm), remove one (Android asks too), set the Vault's own lock, open the
 * Vault copy. Reached by a forwarded intent (a cross-profile filter the owner sets) and accepted only with a one-time
 * token the personal copy got through the channel, since a forwarded intent does not say which app sent it.
 */
class VaultActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        if (savedInstanceState == null && !start()) {
            finish()
            return
        }
        setContent {
            LauncherTheme {
                val state by VaultActionState.state.collectAsState()
                LaunchedEffect(state) {
                    if (state is VaultActionState.S.Done) {
                        delay(if ((state as VaultActionState.S.Done).ok) 1_200 else 3_500)
                        finish()
                    }
                }
                ActionCard(state, label = intent.getStringExtra(EXTRA_LABEL) ?: "the app", onClose = { finish() })
            }
        }
    }

    /** Starts the asked action; false when the request is refused or needs no screen of its own. */
    private fun start(): Boolean {
        if (!Env.isVaultSide || !VaultService.takeToken(intent.getStringExtra(EXTRA_TOKEN))) {
            DebugLog.error("Vault", "refused a Vault action that came without a valid token")
            return false
        }
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg ?: "the app"
        when (intent.getStringExtra(EXTRA_DO)) {
            DO_CLONE -> {
                pkg ?: return false
                VaultActionState.working("Copying $label into the Vault…")
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { Cloner.withInstallerSession(this@VaultActionActivity, pkg, Cloner.PURPOSE_ACTION) }
                        .onFailure { VaultActionState.done(false, "could not start the copy: ${it.asEvidence()}") }
                }
            }
            DO_UNINSTALL -> {
                pkg ?: return false
                VaultActionState.working("Removing $label from the Vault…")
                runCatching { Cloner.uninstallHere(this, pkg, Cloner.PURPOSE_ACTION) }
                    .onFailure { VaultActionState.done(false, "could not ask Android: ${it.asEvidence()}") }
            }
            DO_PASSWORD -> {
                // Android's own screen for the work profile's lock (a lock of the Vault's own, D49).
                runCatching { startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)) }
                    .onFailure { DebugLog.error("Vault", "Android's lock screen setup did not open: ${it.asEvidence()}") }
                return false
            }
            DO_OPEN -> {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, intent.getStringExtra(EXTRA_SCREEN)))
                return false
            }
            else -> return false
        }
        return true
    }

    companion object {
        const val ACTION = "dev.liquid.vault.action.VAULT_ACTION"
        const val EXTRA_TOKEN = "dev.liquid.vault.extra.TOKEN"
        const val EXTRA_DO = "dev.liquid.vault.extra.DO"
        const val EXTRA_PKG = "dev.liquid.vault.extra.ACTION_PKG"
        const val EXTRA_LABEL = "dev.liquid.vault.extra.ACTION_LABEL"
        const val EXTRA_SCREEN = "dev.liquid.vault.extra.ACTION_SCREEN"
        const val DO_CLONE = "clone"
        const val DO_UNINSTALL = "uninstall"
        const val DO_PASSWORD = "password"
        const val DO_OPEN = "open"
    }
}

@Composable
private fun ActionCard(state: VaultActionState.S, label: String, onClose: () -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(none, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(32.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The Vault", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is VaultActionState.S.Working -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(state.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    VaultActionState.S.Asking -> Text("Android asks you to confirm for $label.", style = MaterialTheme.typography.bodyMedium)
                    is VaultActionState.S.Done -> Text(
                        (if (state.ok) "Done: " else "Not done: ") + state.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    VaultActionState.S.Idle -> Text("…", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
