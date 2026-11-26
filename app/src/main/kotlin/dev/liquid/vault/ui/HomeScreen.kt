package dev.liquid.vault.ui

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.liquid.vault.Env
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.control.ActionQueueStore
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.PersonalApps
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.control.VaultPower
import dev.liquid.vault.core.common.ActionQueue
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.core.common.Confirm
import dev.liquid.vault.core.designsystem.KitGroup
import dev.liquid.vault.core.designsystem.KitNavRow
import dev.liquid.vault.core.designsystem.KitScaffold
import dev.liquid.vault.core.designsystem.KitScreenInfo
import dev.liquid.vault.core.designsystem.KitSection
import dev.liquid.vault.core.designsystem.LauncherIcons
import dev.liquid.vault.core.designsystem.LocalKitScreen
import dev.liquid.vault.core.designsystem.kitLoneCard
import dev.liquid.vault.diag.Host
import dev.liquid.vault.diag.Runner
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Liquid Vault's hues: the Vault purple, the personal side blue (the two copies' Diagnostics headings since 0.1.0). */
val VaultPurple = Color(0xFF7E57C2)
val PersonalBlue = Color(0xFF1E88E5)

/** Everything the Home screen shows, read off the main thread. */
private data class HomeState(
    val vault: UserHandle?,
    val ours: Boolean,
    val paused: Boolean?,
    val canPause: Boolean,
    val vaultApps: Int?,
    val personalApps: Int,
    val lock: VaultControl.Lock?,
    val vaultState: VaultControl.State?,
    val channel: Boolean,
    val wirelessDebugging: Boolean?,
) {
    companion object {
        suspend fun load(context: Context): HomeState {
            val vault = Env.vaultUser()
            val ours = vault != null && vault in Env.profilesWithThisApp()
            val paused = VaultPower.paused()
            val reachable = ours && paused == false && Channel.ready()
            return HomeState(
                vault = vault,
                ours = ours,
                paused = paused,
                canPause = VaultPower.canPause(context),
                vaultApps = if (reachable) VaultControl.apps()?.size else null,
                personalApps = runCatching { PersonalApps.list(context).size }.getOrDefault(0),
                lock = if (reachable) VaultControl.lock() else null,
                vaultState = if (reachable) VaultControl.state() else null,
                channel = Channel.ready(),
                wirelessDebugging = runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1 }.getOrNull(),
            )
        }
    }
}

/** The personal copy's first screen (Phase 1): the Vault, its apps, the shell, and the rest under "More". */
@Composable
fun HomeScreen(host: Host, tick: Int, open: (Screen) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableIntStateOf(0) }
    val shell by Shell.state.collectAsState()
    val queue by ActionQueueStore.queue.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var createFailed by remember { mutableStateOf<String?>(null) }
    var confirmDestroy by remember { mutableStateOf(false) }
    val state by produceState<HomeState?>(null, tick, local, shell.connected) {
        ActionQueueStore.load(context)
        value = withContext(Dispatchers.IO) { HomeState.load(context) }
    }

    fun act(label: String, block: suspend () -> Outcome) {
        scope.launch {
            busy = label
            val outcome = runCatching { block() }.getOrElse { Outcome(false, it.message ?: "failed") }
            busy = null
            context.say(outcome)
            local++
        }
    }

    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = VaultPurple, icon = LauncherIcons.Lock)) {
        KitScaffold(title = "Liquid Vault", onBack = onClose) {
            val s = state
            if (s == null) {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                return@KitScaffold
            }
            busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = VaultPurple, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)) }

            // ---- the Vault ----
            KitSection("The Vault")
            when {
                s.vault == null -> InfoCard(
                    "Not created yet",
                    listOf(
                        "The Vault is Android's work profile: a separate space for apps and files that your normal apps cannot see. Liquid Vault owns it.",
                        createFailed?.let { "Android's setup did not make it: $it" } ?: "",
                    ),
                ) {
                    Button(enabled = busy == null, onClick = {
                        scope.launch {
                            busy = "Android's setup is open…"
                            val r = Runner.run("V1", host)
                            busy = null
                            createFailed = if (r.status == CheckStatus.PASS) null else r.summary
                            local++
                        }
                    }) { Text("Create the Vault") }
                    if (createFailed != null && Shell.unavailableReason() == null) {
                        OutlinedButton(enabled = busy == null, onClick = {
                            scope.launch {
                                busy = "Creating the Vault with the shell…"
                                val r = Runner.run("V2", host)
                                busy = null
                                context.say(Outcome(r.status == CheckStatus.PASS, r.summary))
                                local++
                            }
                        }) { Text("Use the shell") }
                    }
                }
                !s.ours -> InfoCard(
                    "Another app's work profile",
                    listOf("This phone has a work profile that Liquid Vault does not own. Remove it in Android's settings (Accounts › Work profile) before making the Vault."),
                )
                else -> {
                    val lines = mutableListOf<String>()
                    lines += when (s.paused) {
                        true -> "Paused: its apps are stopped and hidden until you resume it."
                        false -> "Running${s.vaultApps?.let { " · $it apps" } ?: ""}."
                        null -> "State unknown."
                    }
                    if (s.paused == false) lines += when (s.lock?.separate) {
                        true -> "It has a lock of its own${if (s.lock.locked) " and is locked now" else ""}."
                        false -> "It has no lock of its own (your phone's lock opens it)."
                        null -> ""
                    }
                    if (!s.canPause) lines += "To pause it, run once on your PC: adb shell pm grant ${context.packageName} android.permission.MODIFY_QUIET_MODE"
                    if (!s.channel) lines += "Liquid Vault cannot talk to its copy in the Vault yet: open Diagnostics › L0."
                    InfoCard("The Vault (user ${s.vault.hashCode()})", lines) {
                        if (s.paused == true) {
                            Button(enabled = busy == null && s.canPause, onClick = { act("Resuming the Vault…") { VaultPower.setPaused(context, false) } }) { Text("Resume") }
                        } else {
                            FilledTonalButton(enabled = busy == null && s.canPause, onClick = { act("Pausing the Vault…") { VaultPower.setPaused(context, true) } }) { Text("Pause") }
                        }
                        if (s.paused == false && s.lock?.separate == true) {
                            OutlinedButton(enabled = busy == null, onClick = { act("Locking the Vault…") { VaultControl.lockNow() } }) { Text("Lock now") }
                        }
                    }
                }
            }

            // ---- apps ----
            if (s.ours) {
                KitSection("Apps")
                KitGroup {
                    KitNavRow(
                        title = "Vault apps",
                        summary = "Open, freeze, suspend or remove the apps in the Vault",
                        icon = LauncherIcons.Work,
                        accent = VaultPurple,
                        value = s.vaultApps?.toString() ?: if (s.paused == true) "paused" else null,
                        onClick = { open(Screen.APPS_VAULT) },
                    )
                    KitNavRow(
                        title = "Personal apps",
                        summary = "Copy apps into the Vault; freeze or suspend your normal apps",
                        icon = LauncherIcons.Apps,
                        accent = PersonalBlue,
                        value = s.personalApps.toString(),
                        onClick = { open(Screen.APPS_PERSONAL) },
                    )
                    KitNavRow(
                        title = "Access rules",
                        summary = "Which files each app may reach, on its side and across",
                        icon = LauncherIcons.Lock,
                        accent = VaultPurple,
                        value = dev.liquid.vault.rules.RulesStore.rules(context).size.let { if (it == 0) "none yet" else "$it" },
                        onClick = { open(Screen.RULES) },
                    )
                }
            }

            // ---- the shell ----
            KitSection("The shell (Shevery)")
            ShellCard(context, s, queue.size, busy == null, onApply = { act("Running the waiting actions…") { ActionQueueStore.applyDue(context); Outcome(true, "") } })
            queue.forEach { a ->
                Row(Modifier.fillMaxWidth().kitLoneCard().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = MaterialTheme.typography.bodyLarge)
                        val note = when {
                            ActionQueue.givenUp(a) -> "Gave up after ${a.attempts} tries: ${a.lastError}"
                            a.lastError != null -> "Last try: ${a.lastError}"
                            else -> "Waits for the shell"
                        }
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ActionQueue.givenUp(a)) TextButton(onClick = { ActionQueueStore.retry(context, a.id) }) { Text("Retry") }
                    TextButton(onClick = { ActionQueueStore.remove(context, a.id) }) { Text("Cancel") }
                }
            }

            // ---- more ----
            KitSection("More")
            KitGroup {
                if (s.ours) {
                    KitNavRow("VPN", "An always-on VPN for the Vault's apps; Android's VPN settings for your normal apps", icon = LauncherIcons.Web, onClick = { open(Screen.VPN) })
                    KitNavRow("The Vault's lock", "A lock of its own for the Vault", icon = LauncherIcons.Lock, onClick = { open(Screen.LOCK) })
                }
                KitNavRow("Settings", "The Vault's icon, what happens at boot", icon = LauncherIcons.Settings, onClick = { open(Screen.SETTINGS) })
                KitNavRow("Diagnostics", "The proof checks and their reports", icon = LauncherIcons.Code, onClick = { open(Screen.DIAGNOSTICS) })
                KitNavRow("What's new", "What changed in each version", icon = LauncherIcons.Sparkle, onClick = { open(Screen.WHATS_NEW) })
                if (s.ours) KitNavRow("Destroy the Vault", "Removes the Vault and everything in it", icon = LauncherIcons.Delete, accent = MaterialTheme.colorScheme.error, onClick = { confirmDestroy = true })
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDestroy) {
        TypedConfirm(
            title = "Destroy the Vault?",
            text = "This removes the Vault and EVERYTHING in it: its apps, their data and its files. It cannot be undone.",
            phrase = Confirm.DESTROY_VAULT,
            confirmLabel = "Destroy",
            onDismiss = { confirmDestroy = false },
        ) {
            confirmDestroy = false
            act("Destroying the Vault…") {
                val r = Runner.run("D1", host, mapOf("confirm" to Confirm.DESTROY_VAULT))
                Outcome(r.status == CheckStatus.PASS, r.summary)
            }
        }
    }
}

@Composable
private fun ShellCard(context: Context, s: HomeState, waiting: Int, enabled: Boolean, onApply: () -> Unit) {
    val lines = mutableListOf<String>()
    val reason = Shell.unavailableReason()
    lines += when {
        !Shell.alive -> "Not running. Personal-side actions wait until you start Shevery; everything in the Vault works without it."
        !Shell.permitted() -> "Running, but it has not allowed Liquid Vault yet."
        else -> "Connected through ${Shell.state.value.door?.label ?: "Shevery"}."
    }
    s.vaultState?.let {
        lines += if (it.shellBlocked) "In the Vault: Android keeps the shell out (\"no debugging features\"), so copying uses Android's installer." else "In the Vault: the shell may act (apps are copied silently)."
    }
    s.wirelessDebugging?.let { lines += "Wireless debugging: ${if (it) "on" else "off"}." }
    if (waiting > 0) lines += "$waiting action${if (waiting == 1) "" else "s"} wait for the shell (below)."
    InfoCard("Shell", lines) {
        OutlinedButton(onClick = {
            val launch = context.packageManager.getLaunchIntentForPackage("com.hamondev.shevery")
            if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else context.say(Outcome(false, "Shevery is not installed"))
        }) { Text("Open Shevery") }
        if (Shell.alive && !Shell.permitted()) TextButton(onClick = { Shell.requestPermission() }) { Text("Allow") }
        if (waiting > 0 && reason == null) FilledTonalButton(enabled = enabled, onClick = onApply) { Text("Apply now") }
    }
}
