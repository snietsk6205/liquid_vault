package dev.liquid.vault

import android.app.Application
import android.util.Log
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.LogLevel
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Env.init(this)
        DebugLog.verbosity = LogLevel.DECISION
        // Every line also goes to logcat under one tag: the emulator test in CI waits for these sentences.
        DebugLog.sink = { e ->
            val priority = when (e.level) {
                LogLevel.ERROR -> Log.WARN
                else -> Log.INFO
            }
            Log.println(priority, LOG_TAG, "${e.tag}: ${e.message}")
        }
        val side = Env.side()
        SideComponents.apply(this, side)
        Shell.init()
        DebugLog.event("App", "Liquid Vault ${Env.versionName()} (build ${Env.versionCode()}) started on the ${side.label.lowercase()} (user ${Env.myUserId})")
        when (side) {
            AppSide.VAULT -> {
                // A Vault set up by an older version gets what it misses (D43); and the shell restriction is noted (D37).
                runCatching { dev.liquid.vault.admin.VaultSetup.upgrade(this) }.onFailure { DebugLog.error("Setup", "upgrade failed: ${it.message}") }
                dev.liquid.vault.admin.ShellAccess.noteAtStart(this)
            }
            AppSide.PERSONAL -> {
                // Actions that wait for the shell run as soon as Shevery connects or allows this app (D46).
                val app = this
                val apply = { CoroutineScope(Dispatchers.IO).launch { dev.liquid.vault.control.ActionQueueStore.applyDue(app) }; Unit }
                rikka.shizuku.Shizuku.addBinderReceivedListenerSticky { apply() }
                rikka.shizuku.Shizuku.addRequestPermissionResultListener { _, result -> if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) apply() }
            }
            AppSide.FOREIGN_PROFILE -> {}
        }
    }

    companion object {
        const val LOG_TAG = "LiquidVault"
    }
}
