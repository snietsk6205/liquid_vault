package dev.liquid.vault.boot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.liquid.vault.AppSide
import dev.liquid.vault.Env
import dev.liquid.vault.control.ActionQueueStore
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.shell.Shell
import dev.liquid.vault.ui.MainActivity

/**
 * What the personal copy does when the phone has started (D50), both only if you turned them on in Settings:
 * turn wireless debugging on (needs WRITE_SECURE_SETTINGS, granted once with adb) and remind you to start Shevery.
 * The reminder also comes, whatever the option, when actions wait for the shell.
 */
object BootActions {
    private const val CHANNEL = "shell"
    private const val SHEVERY = "com.hamondev.shevery"

    fun run(context: Context) {
        if (Env.side() != AppSide.PERSONAL) return
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(Prefs.OPT_BOOT_WIRELESS_DEBUGGING, false)) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                runCatching { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) }
                    .onSuccess { DebugLog.event("Boot", "wireless debugging turned on (option)") }
                    .onFailure { DebugLog.error("Boot", "could not turn wireless debugging on: ${it.asEvidence()}") }
            } else {
                DebugLog.error("Boot", "wireless debugging option is on, but WRITE_SECURE_SETTINGS is not granted")
            }
        }
        ActionQueueStore.load(context)
        val waiting = ActionQueueStore.queue.value.size
        if ((prefs.getBoolean(Prefs.OPT_BOOT_REMIND_SHELL, false) || waiting > 0) && !Shell.alive) remind(context, waiting)
    }

    private fun remind(context: Context, waiting: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            DebugLog.decision("Boot", "no reminder: notifications are not allowed for Liquid Vault")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "The shell", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders to start Shevery after the phone starts"
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(SHEVERY) ?: Intent(context, MainActivity::class.java)
        val tap = PendingIntent.getActivity(context, 1, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val text = if (waiting > 0) "$waiting action${if (waiting == 1) "" else "s"} wait for the shell; they run as soon as Shevery starts."
        else "Liquid Vault's personal-side actions need the shell."
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(dev.liquid.vault.core.designsystem.R.drawable.ic_lock)
            .setContentTitle("Start Shevery")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
        DebugLog.event("Boot", "reminded you to start Shevery ($waiting waiting)")
    }
}
