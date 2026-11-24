package dev.liquid.vault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquid.vault.AppSide
import dev.liquid.vault.Env
import dev.liquid.vault.bridge.BridgeAllowlist
import dev.liquid.vault.bridge.PickerFilters
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.core.common.CheckDef
import dev.liquid.vault.core.common.CheckResult
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.core.common.Confirm
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.PhaseZero
import dev.liquid.vault.core.designsystem.KitGroup
import dev.liquid.vault.core.designsystem.KitScaffold
import dev.liquid.vault.core.designsystem.KitScreenInfo
import dev.liquid.vault.core.designsystem.KitSection
import dev.liquid.vault.core.designsystem.LauncherIcons
import dev.liquid.vault.core.designsystem.LocalKitScreen
import dev.liquid.vault.core.designsystem.kitLoneCard
import dev.liquid.vault.diag.CheckStore
import dev.liquid.vault.diag.Host
import dev.liquid.vault.diag.Prefs
import dev.liquid.vault.diag.Reports
import dev.liquid.vault.diag.Runner
import dev.liquid.vault.id
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PickFor(val title: String) {
    CLONE("Choose an app to clone"),
    VAULT_APP("Choose a Vault app for the storage checks"),
    ALLOW("Allow an app on the bridge"),
}

private data class AppEntry(val label: String, val pkg: String)

private data class ConfirmAsk(val title: String, val text: String, val phrase: String, val onOk: () -> Unit)

/** A button a check row shows when it is open. */
private data class RowAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

@Composable
fun DiagnosticsScreen(host: Host, refresh: StateFlow<Int>, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book by CheckStore.book.collectAsState()
    val shell by Shell.state.collectAsState()
    val tick by refresh.collectAsState()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf<PickFor?>(null) }
    var confirm by remember { mutableStateOf<ConfirmAsk?>(null) }
    var busy by remember { mutableStateOf(false) }
    var localTick by remember { mutableStateOf(0) }
    val side = remember(tick, localTick, book) { Env.side() }
    val vaultSide = side == AppSide.VAULT
    val defs = PhaseZero.forSide(vaultSide)

    fun run(id: String, extras: Map<String, String> = emptyMap()) {
        scope.launch {
            Runner.run(id, host, extras)
            localTick++
        }
    }

    fun undo(id: String, extras: Map<String, String> = emptyMap()) {
        scope.launch {
            Runner.undo(id, host, extras)
            localTick++
        }
    }

    val accent = if (vaultSide) Color(0xFF7E57C2) else Color(0xFF1E88E5)
    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = accent, icon = if (vaultSide) LauncherIcons.Work else LauncherIcons.Lock)) {
        KitScaffold(title = "Diagnostics", onBack = onClose) {
            Header(side, shell, tick + localTick)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        Runner.safe.filter { id -> defs.any { it.id == id } }.forEach { Runner.run(it, host) }
                        busy = false
                        localTick++
                    }
                }) { Text(if (busy) "Running…" else "Run safe checks") }
                FilledTonalButton(onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { Reports.build(context, includeOtherSide = true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Liquid Vault report", text))
                        Toast.makeText(context, "Report copied (${text.lines().size} lines)", Toast.LENGTH_SHORT).show()
                        DebugLog.event("Screen", "report copied (${text.length} characters)")
                    }
                }) { Text("Copy report") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { Reports.build(context, includeOtherSide = true) }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the report"))
                    }
                }) { Text("Share") }
                OutlinedButton(onClick = {
                    confirm = ConfirmAsk("Clear all results?", "Removes this copy's saved check results (not the Vault, not the logs).", "CLEAR") { CheckStore.clearAll() }
                }) { Text("Clear results") }
            }

            for (group in PhaseZero.groups) {
                val inGroup = defs.filter { it.group == group }
                if (inGroup.isEmpty()) continue
                KitSection(group)
                GroupExtras(group, vaultSide, tick + localTick, onPick = { picking = it }, onRemoveFromVault = { pkg -> removeFromVault(context, pkg) })
                KitGroup {
                    for (def in inGroup) {
                        val result = book.results[def.id]
                        CheckRow(
                            def = def,
                            result = result,
                            open = def.id in expanded,
                            onToggle = { expanded = if (def.id in expanded) expanded - def.id else expanded + def.id },
                            actions = actionsFor(def, vaultSide, ::run, ::undo) { confirm = it },
                            onAnswer = { i ->
                                Runner.answer(def.id, i)
                                localTick++
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { kind ->
        AppPicker(kind, vaultSide, onDismiss = { picking = null }) { app ->
            when (kind) {
                PickFor.CLONE -> Prefs.app.edit().putString(Prefs.CLONE_TARGET, app.pkg).apply()
                PickFor.VAULT_APP -> Prefs.app.edit().putString(Prefs.VAULT_APP_TARGET, app.pkg).apply()
                PickFor.ALLOW -> BridgeAllowlist.add(app.pkg)
            }
            DebugLog.event("Screen", "${kind.title.lowercase()}: ${app.label} (${app.pkg})")
            picking = null
            localTick++
        }
    }
    confirm?.let { ask ->
        TypedConfirm(ask, onDismiss = { confirm = null }) {
            confirm = null
            ask.onOk()
            localTick++
        }
    }
}

@Composable
private fun Header(side: AppSide, shell: Shell.State, tick: Int) {
    val context = LocalContext.current
    val facts = remember(side, shell, tick) {
        val lines = mutableListOf<String>()
        lines += "This copy: ${side.label} (user ${Env.myUserId}) · Liquid Vault ${Env.versionName()} (build ${Env.versionCode()})"
        if (side == AppSide.VAULT) {
            lines += "Owner of this work profile · setup ${if (Prefs.app.getLong(Prefs.SETUP_DONE_AT, 0) > 0) "done" else "not done yet"} · Work tab for personal apps ${if (PickerFilters.isOn()) "OPEN (bridge A)" else "closed"}"
        } else {
            val v = Env.vaultUser()
            lines += if (v == null) "Vault: none yet" else {
                val paused = runCatching { Env.um.isQuietModeEnabled(v) }.getOrDefault(false)
                "Vault: user ${v.id} · Liquid Vault ${if (v in Env.profilesWithThisApp()) "installed there" else "NOT installed there (not our Vault?)"} · ${if (paused) "paused" else "running"}"
            }
        }
        lines += if (Shell.alive) "Shell: Shevery connected through ${shell.door?.label ?: "?"} · ${if (Shell.permitted()) "allowed" else "not allowed yet (S2)"}" else "Shell: not connected (shell checks say SKIPPED)"
        lines += "Channel to the other copy: ${if (Channel.ready()) "ready" else "not set up (L0)"}"
        lines
    }
    Column(Modifier.fillMaxWidth().kitLoneCard().padding(16.dp)) {
        facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        val other = if (side == AppSide.VAULT) Env.personalUser() else Env.vaultUser()?.takeIf { it in Env.profilesWithThisApp() }
        if (other != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching { Env.crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), other) }
                    .onFailure { Toast.makeText(context, "Could not open the other copy: ${it.message}", Toast.LENGTH_LONG).show() }
            }) { Text(if (side == AppSide.VAULT) "Open the personal copy" else "Open the Vault copy") }
        }
    }
}

@Composable
private fun GroupExtras(group: String, vaultSide: Boolean, tick: Int, onPick: (PickFor) -> Unit, onRemoveFromVault: (String) -> Unit) {
    val (text, kind) = when (group) {
        PhaseZero.G_CLONE -> "App to clone: ${labelOf(Prefs.app.getString(Prefs.CLONE_TARGET, null))}" to PickFor.CLONE
        PhaseZero.G_STORAGE -> "Vault app for these checks: ${labelOf(Prefs.app.getString(Prefs.VAULT_APP_TARGET, null))}" to PickFor.VAULT_APP
        PhaseZero.G_BRIDGE -> "Allowed besides Liquid Vault (this copy): ${BridgeAllowlist.get().joinToString { labelOf(it) }.ifEmpty { "none" }}" to PickFor.ALLOW
        else -> return
    }
    @Suppress("UNUSED_EXPRESSION") tick
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPick(kind) }) { Text(if (kind == PickFor.ALLOW) "Add an app" else "Choose") }
            if (kind == PickFor.ALLOW && BridgeAllowlist.get().isNotEmpty()) {
                TextButton(onClick = { BridgeAllowlist.get().forEach { BridgeAllowlist.remove(it) } }) { Text("Clear the list") }
            }
            val target = Prefs.app.getString(Prefs.CLONE_TARGET, null)
            if (kind == PickFor.CLONE && vaultSide && target != null && dev.liquid.vault.clone.Cloner.installedHere(Env.app, target)) {
                TextButton(onClick = { onRemoveFromVault(target) }) { Text("Remove it from the Vault") }
            }
        }
    }
}

private fun labelOf(pkg: String?): String {
    pkg ?: return "none chosen"
    val pm = Env.app.packageManager
    return runCatching { "${pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES))} ($pkg)" }.getOrDefault(pkg)
}

/** The buttons each check shows. Most only "Run"; some have their own words, an Undo, or a typed confirmation. */
private fun actionsFor(
    def: CheckDef,
    vaultSide: Boolean,
    run: (String, Map<String, String>) -> Unit,
    undo: (String, Map<String, String>) -> Unit,
    ask: (ConfirmAsk) -> Unit,
): List<RowAction> {
    val id = def.id
    fun runIt(label: String = "Run") = RowAction(label, primary = true) { run(id, emptyMap()) }
    return when (id) {
        "S2" -> listOf(runIt("Check / ask Shevery"))
        "V1" -> listOf(runIt("Start Android's setup"))
        "V2" -> listOf(
            runIt("Create with the shell"),
            RowAction("Remove a half-made profile…") {
                ask(ConfirmAsk("Remove the half-made profile?", "Removes the work profile V2 created (and anything in it) with `pm remove-user`.", Confirm.REMOVE_PROFILE) {
                    undo("V2", mapOf("confirm" to Confirm.REMOVE_PROFILE))
                })
            },
        )
        "F2", "F5", "F7", "F8" -> listOf(runIt(), RowAction("Undo") { undo(id, emptyMap()) })
        "L0" -> buildList {
            add(runIt("Check"))
            add(RowAction("Connect (forwarded intent)") { run(id, mapOf("connect" to "forwarded")) })
            if (vaultSide) add(RowAction("Connect (CrossProfileApps)") { run(id, mapOf("connect" to "cpa")) })
        }
        "B1" -> if (vaultSide) listOf(runIt("Open the Work tab"), RowAction("Undo: close it") { undo("B1", emptyMap()) }) else listOf(runIt("Open the system picker"))
        "B2" -> if (vaultSide) listOf(runIt("Prepare")) else listOf(runIt("Open the system picker"))
        "Q1" -> listOf(runIt("Pause the Vault"))
        "Q2" -> listOf(runIt("Resume the Vault"))
        "D1" -> listOf(
            RowAction("Destroy the Vault…", primary = true) {
                ask(ConfirmAsk("Destroy the Vault?", "This removes the work profile and EVERYTHING in it: its apps, their data and its files. It cannot be undone.", Confirm.DESTROY_VAULT) {
                    run("D1", mapOf("confirm" to Confirm.DESTROY_VAULT))
                })
            },
        )
        else -> listOf(runIt())
    }
}

@Composable
private fun CheckRow(
    def: CheckDef,
    result: CheckResult?,
    open: Boolean,
    onToggle: () -> Unit,
    actions: List<RowAction>,
    onAnswer: (Int) -> Unit,
) {
    val status = result?.status ?: CheckStatus.NOT_RUN
    Column(
        Modifier.fillMaxWidth().kitLoneCard().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
            Text(def.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            StatusChip(status)
        }
        if (result != null && result.summary.isNotBlank()) {
            Text(result.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
        }
        val question = result?.question
        val answers = Runner.answers[def.id]
        if (status == CheckStatus.NEEDS_YOU && question != null && answers != null) {
            Text(question, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 30.dp, top = 6.dp))
            Row(Modifier.padding(start = 30.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                answers.forEachIndexed { i, a -> FilledTonalButton(onClick = { onAnswer(i) }) { Text(a.label) } }
            }
        }
        if (open) {
            Text("${def.side.label}. ${def.explains}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 30.dp, top = 6.dp))
            if (result != null && result.evidence.isNotEmpty()) {
                Box(
                    Modifier.padding(start = 30.dp, top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                ) {
                    Text(result.evidence.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
            Row(Modifier.padding(start = 30.dp, top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { a ->
                    if (a.primary) Button(enabled = status != CheckStatus.RUNNING, onClick = a.onClick) { Text(a.label) }
                    else OutlinedButton(enabled = status != CheckStatus.RUNNING, onClick = a.onClick) { Text(a.label) }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: CheckStatus) {
    val (bg, fg) = when (status) {
        CheckStatus.PASS -> Color(0xFF2E7D32) to Color.White
        CheckStatus.FAIL -> Color(0xFFC62828) to Color.White
        CheckStatus.SKIPPED -> Color(0xFF757575) to Color.White
        CheckStatus.NEEDS_YOU -> Color(0xFFF9A825) to Color.Black
        CheckStatus.RUNNING -> Color(0xFF1565C0) to Color.White
        CheckStatus.INFO -> Color(0xFF546E7A) to Color.White
        CheckStatus.NOT_RUN -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        status.label,
        color = fg,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Apps for a picker. From the Vault side, personal apps come from the package manager (LauncherApps may not look). */
private fun appsFor(kind: PickFor, vaultSide: Boolean): List<AppEntry> {
    val context = Env.app
    val own = context.packageName
    val pm = context.packageManager
    val la = context.getSystemService(LauncherApps::class.java)
    fun fromLauncher(user: android.os.UserHandle?): List<AppEntry> = user?.let { u ->
        runCatching { la.getActivityList(null, u).map { AppEntry(it.label.toString(), it.applicationInfo.packageName) } }.getOrDefault(emptyList())
    } ?: emptyList()
    val list = when (kind) {
        PickFor.CLONE -> if (!vaultSide) fromLauncher(Env.myUser) else {
            pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                .filter { it.flags and ApplicationInfo.FLAG_INSTALLED == 0 || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null || it.flags and ApplicationInfo.FLAG_INSTALLED == 0 }
                .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
        }
        PickFor.VAULT_APP -> fromLauncher(if (vaultSide) Env.myUser else Env.vaultUser())
        PickFor.ALLOW -> fromLauncher(Env.myUser)
    }
    return list.filter { it.pkg != own }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}

@Composable
private fun AppPicker(kind: PickFor, vaultSide: Boolean, onDismiss: () -> Unit, onChoose: (AppEntry) -> Unit) {
    val apps = remember(kind) { appsFor(kind, vaultSide) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            if (apps.isEmpty()) Text("No apps found here.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.pkg }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(app) }.padding(vertical = 10.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TypedConfirm(ask: ConfirmAsk, onDismiss: () -> Unit, onOk: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ask.title) },
        text = {
            Column {
                Text(ask.text)
                Spacer(Modifier.height(12.dp))
                Text("Type ${ask.phrase} to confirm:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(enabled = Confirm.matches(typed, ask.phrase), onClick = onOk) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks Android to uninstall an app from the Vault (it shows its own confirmation). */
private fun removeFromVault(context: android.content.Context, pkg: String) {
    val intent = Intent(context, dev.liquid.vault.clone.InstallResultReceiver::class.java).putExtra(dev.liquid.vault.clone.Cloner.EXTRA_PKG, pkg).putExtra("uninstall", true)
    val pi = android.app.PendingIntent.getBroadcast(context, pkg.hashCode(), intent, android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    context.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
    DebugLog.event("Clone", "asked Android to remove $pkg from the Vault")
}
