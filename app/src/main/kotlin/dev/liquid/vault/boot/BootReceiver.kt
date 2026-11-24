package dev.liquid.vault.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.liquid.vault.Env
import dev.liquid.vault.SideComponents
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.diag.BootRecorder

/** Boot: each copy records what it finds (R1–R4). After an update: the side-specific parts are switched again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Env.init(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> BootRecorder.record(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DebugLog.event("App", "updated to ${Env.versionName()} (build ${Env.versionCode()})")
                SideComponents.apply(context)
            }
        }
    }
}
