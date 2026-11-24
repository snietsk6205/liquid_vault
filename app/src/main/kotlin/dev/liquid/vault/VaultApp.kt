package dev.liquid.vault

import android.app.Application
import android.util.Log
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.LogLevel
import dev.liquid.vault.shell.Shell

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
    }

    companion object {
        const val LOG_TAG = "LiquidVault"
    }
}
