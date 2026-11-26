package dev.liquid.vault.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.liquid.vault.Env
import dev.liquid.vault.admin.VaultSetup
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.channel.ChannelActivity
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.designsystem.LauncherTheme
import dev.liquid.vault.diag.CheckStore
import dev.liquid.vault.diag.Host
import dev.liquid.vault.diag.Runner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one activity, in both copies: the personal copy opens on Home (Phase 1), the Vault copy on its own small
 * page; Diagnostics is a page of either. [EXTRA_SCREEN] opens a page directly (e.g. from the other copy).
 */
class MainActivity : ComponentActivity(), Host {
    override val activity get() = this

    private var pending: CompletableDeferred<Pair<Int, Intent?>>? = null
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pending?.complete(res.resultCode to res.data)
        pending = null
    }

    override suspend fun startForResult(intent: Intent): Pair<Int, Intent?> = withContext(Dispatchers.Main) {
        pending?.cancel()
        val d = CompletableDeferred<Pair<Int, Intent?>>()
        pending = d
        launcher.launch(intent)
        d.await()
    }

    /** Bumped when something outside the screen changes (a profile appears, the channel connects): the header re-reads. */
    val refresh = MutableStateFlow(0)

    /** Pages asked for from outside the screen (an intent's [EXTRA_SCREEN]). */
    private val navigate = kotlinx.coroutines.channels.Channel<String>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)

    private val profileEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.decision("Screen", "Android says: ${intent.action?.substringAfterLast('.')}")
            refresh.value++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Env.init(this)
        CheckStore.load(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(ChannelActivity.ACTION_CHANNEL_CHANGED)
        }
        registerReceiver(profileEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null) handle(intent)
        setContent {
            LauncherTheme {
                AppRoot(host = this, refresh = refresh, navigate = navigate.receiveAsFlow(), onFinish = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh.value++
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(profileEvents) }
        super.onDestroy()
    }

    /** Opened by the shell path (V2) or by Android's setup: finish the Vault's setup, then connect the channel once. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SCREEN)?.let { navigate.trySend(it) }
        if (intent?.action != VaultSetup.ACTION_FINISH_SETUP || !Env.isVaultSide) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { VaultSetup.runIfOwner(this@MainActivity, "the Vault copy was opened to finish setup") }
            if (!Channel.ready()) {
                DebugLog.event("Setup", "connecting the channel to the personal copy (first start of the Vault copy)")
                Runner.run("L0", this@MainActivity, mapOf("connect" to "forwarded"))
            }
            refresh.value++
        }
    }

    companion object {
        /** A page to open: one of [Screen]'s keys ("diagnostics", "apps-vault", …). */
        const val EXTRA_SCREEN = "dev.liquid.vault.extra.SCREEN"
    }
}
