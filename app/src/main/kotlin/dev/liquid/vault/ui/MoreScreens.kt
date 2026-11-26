package dev.liquid.vault.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.liquid.vault.Env
import dev.liquid.vault.SideComponents
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.core.common.Changelog
import dev.liquid.vault.core.designsystem.KitGroup
import dev.liquid.vault.core.designsystem.KitNavRow
import dev.liquid.vault.core.designsystem.KitScaffold
import dev.liquid.vault.core.designsystem.KitScreenInfo
import dev.liquid.vault.core.designsystem.KitSection
import dev.liquid.vault.core.designsystem.LauncherIcons
import dev.liquid.vault.core.designsystem.LocalKitScreen
import dev.liquid.vault.core.designsystem.kitLoneCard
import dev.liquid.vault.diag.Host
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.vault.VaultActionActivity
import kotlinx.coroutines.launch

@Composable
private fun Page(title: String, icon: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = VaultPurple, icon = icon)) {
        KitScaffold(title = title, onBack = onBack) { content() }
    }
}

@Composable
private fun Words(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
}

/** The Vault's own lock (D49): a lock of its own, set on Android's screen in the Vault; "Lock now". */
@Composable
fun VaultLockScreen(host: Host, tick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableIntStateOf(0) }
    val lock by produceState<VaultControl.Lock?>(null, tick, local) { value = VaultControl.lock() }
    Page("The Vault's lock", LauncherIcons.Lock, onBack) {
        val l = lock
        val lines = when {
            l == null -> listOf("The Vault copy did not answer. Is the Vault paused? Resume it on the Home screen.")
            l.separate == true -> listOf(
                "The Vault has a lock of its own. While it is locked, its apps ask for this lock before they open.",
                "Locked now: ${if (l.locked) "yes" else "no"}.",
            )
            else -> listOf(
                "The Vault has no lock of its own: your phone's lock opens it too.",
                "A lock of its own keeps the Vault closed while your phone is unlocked. Android asks you to choose it in the Vault, then whether to use it.",
            )
        }
        InfoCard(if (l?.separate == true) "A lock of its own" else "No lock of its own", lines) {
            Button(enabled = l != null, onClick = {
                scope.launch { context.say(VaultControl.openInVault(host.activity, VaultActionActivity.DO_PASSWORD).let { if (it.ok) Outcome(true, "") else it }) }
            }) { Text(if (l?.separate == true) "Change it" else "Set a lock") }
            if (l?.separate == true) OutlinedButton(onClick = { scope.launch { context.say(VaultControl.lockNow()); local++ } }) { Text("Lock now") }
        }
        Words("To remove the Vault's own lock, choose \"None\" on the same Android screen; the phone's lock then opens the Vault again.")
    }
}

/** VPN for each side (D48): the Vault's always-on VPN by its owner; Android's settings for the personal side. */
@Composable
fun VpnScreen(host: Host, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableIntStateOf(0) }
    val vpn by produceState<VaultControl.Vpn?>(null, local) { value = VaultControl.vpn() }
    var choice by remember(vpn) { mutableStateOf(vpn?.current) }
    var lockdown by remember(vpn) { mutableStateOf(vpn?.lockdown ?: false) }
    Page("VPN", LauncherIcons.Web, onBack) {
        KitSection("The Vault's apps")
        val v = vpn
        when {
            v == null -> Words("The Vault copy did not answer. Is the Vault paused? Resume it on the Home screen.")
            v.apps.isEmpty() -> InfoCard(
                "No VPN app in the Vault",
                listOf("Copy your VPN app into the Vault (Apps › Personal › the app › Copy into the Vault), open it there once to sign in, then come back here."),
            )
            else -> {
                Words("Every app in the Vault goes through the VPN you choose, all the time. Your normal apps are not affected.")
                Column(Modifier.fillMaxWidth().kitLoneCard().padding(vertical = 4.dp)) {
                    (listOf<Pair<String?, String>>(null to "No VPN") + v.apps).forEach { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable { choice = pkg }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == pkg, onClick = { choice = pkg })
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                SwitchRow("Block connections without the VPN", "While the VPN is not connected, the Vault's apps have no internet at all", lockdown, enabled = choice != null) { lockdown = it }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = { scope.launch { context.say(VaultControl.setVpn(choice, lockdown)); local++ } }) { Text("Apply") }
                }
            }
        }
        KitSection("Your normal apps")
        InfoCard(
            "Android's VPN settings",
            listOf("Android sets the always-on VPN of your normal apps in its own settings: tap your VPN app's gear, then turn on \"Always-on VPN\"."),
        ) {
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } }) { Text("Open Android's VPN settings") }
        }
    }
}

/** Phase 1's few options (D52): the Vault copy's icon, and what happens at boot (D50). */
@Composable
fun SettingsScreen(host: Host, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = Prefs.of(context)
    var local by remember { mutableIntStateOf(0) }
    val vaultState by produceState<VaultControl.State?>(null, local) { value = VaultControl.state() }
    var bootAdb by remember { mutableStateOf(prefs.getBoolean(Prefs.OPT_BOOT_WIRELESS_DEBUGGING, false)) }
    var remind by remember { mutableStateOf(prefs.getBoolean(Prefs.OPT_BOOT_REMIND_SHELL, false)) }
    val canWriteSecure = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    val grant = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    Page("Settings", LauncherIcons.Settings, onBack) {
        KitSection("The Vault")
        SwitchRow(
            "Show Liquid Vault in the Work tab",
            "Its own copy inside the Vault, with the work badge. You rarely need it: you control the Vault from here.",
            vaultState?.iconVisible ?: false,
            enabled = vaultState != null,
        ) { on -> scope.launch { context.say(VaultControl.setIconVisible(on)); local++ } }
        KitSection("When the phone starts")
        SwitchRow(
            "Turn wireless debugging on",
            if (canWriteSecure) "So Shevery can start right away (wireless debugging must be paired once)" else "Needs a one-time permission from your PC (below)",
            bootAdb,
            enabled = canWriteSecure,
        ) { bootAdb = it; prefs.edit().putBoolean(Prefs.OPT_BOOT_WIRELESS_DEBUGGING, it).apply() }
        if (!canWriteSecure) {
            Words("On your PC: $grant")
            Row(Modifier.padding(horizontal = 24.dp)) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", grant))
                    context.say(Outcome(true, "command copied"))
                }) { Text("Copy the command") }
            }
        }
        SwitchRow(
            "Remind me to start Shevery",
            "A notification after the phone starts; it also says when actions wait for the shell",
            remind,
        ) { on ->
            remind = on
            prefs.edit().putBoolean(Prefs.OPT_BOOT_REMIND_SHELL, on).apply()
            if (on && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                host.activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "What's new": the changelog the app carries (CI bundles CHANGELOG.md). */
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versions = remember { runCatching { Changelog.parse(context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
    Page("What's new", LauncherIcons.Sparkle, onBack) {
        if (versions.isEmpty()) Words("No changelog in this build.")
        versions.take(6).forEach { v ->
            KitSection("${v.version}${v.date?.let { " · $it" } ?: ""}")
            Column(Modifier.fillMaxWidth().kitLoneCard().padding(16.dp)) {
                v.note?.let { Text(plain(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                v.parts.forEach { part ->
                    Text(part.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                    part.items.forEach { Text("• " + plain(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

/** Changelog lines without their Markdown marks. */
private fun plain(text: String): String = text.replace("**", "").replace("`", "").replace(Regex("^\\*(.*)\\*$"), "$1")

/** The Vault copy's own page: what it is, the way to the personal copy, and its Diagnostics. */
@Composable
fun VaultCopyScreen(host: Host, open: (Screen) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Page("Liquid Vault", LauncherIcons.Work, onClose) {
        InfoCard(
            "The Vault's own copy",
            listOf("This copy runs inside the Vault and owns it. You control the Vault from Liquid Vault on your personal side; this copy does what it asks."),
        ) {
            Button(onClick = {
                val personal = Env.personalUser()
                val outcome = if (personal == null) Outcome(false, "no personal side found") else runCatching {
                    Env.crossProfile.startMainActivity(SideComponents.launcherAlias(context), personal)
                }.fold({ Outcome(true, "") }, { Outcome(false, it.message ?: "could not open it") })
                context.say(outcome)
            }) { Text("Open the personal copy") }
        }
        KitGroup {
            KitNavRow("Diagnostics", "The proof checks and their reports, Vault side", icon = LauncherIcons.Code, onClick = { open(Screen.DIAGNOSTICS) })
        }
    }
}
