package dev.liquid.vault.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.liquid.vault.Env
import dev.liquid.vault.bridge.AccessLog
import dev.liquid.vault.bridge.SideStore
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.PersonalApps
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.control.VaultPower
import dev.liquid.vault.core.common.AccessEntry
import dev.liquid.vault.core.common.AccessRules
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.AppRule
import dev.liquid.vault.core.common.OwnFiles
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.Preset
import dev.liquid.vault.core.common.Reach
import dev.liquid.vault.core.common.RulesFile
import dev.liquid.vault.core.common.StoragePaths
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.core.designsystem.KitChips
import dev.liquid.vault.core.designsystem.KitGroup
import dev.liquid.vault.core.designsystem.KitNavRow
import dev.liquid.vault.core.designsystem.KitScaffold
import dev.liquid.vault.core.designsystem.KitScreenInfo
import dev.liquid.vault.core.designsystem.KitSection
import dev.liquid.vault.core.designsystem.KitSegments
import dev.liquid.vault.core.designsystem.LauncherIcons
import dev.liquid.vault.core.designsystem.LocalKitScreen
import dev.liquid.vault.core.designsystem.kitLoneCard
import dev.liquid.vault.diag.Host
import dev.liquid.vault.rules.OwnFilesControl
import dev.liquid.vault.rules.RulesStore
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where an app's own files are, and the other side's, in the words the rules screen uses. */
private fun ownSideName(place: Place) = if (place == Place.VAULT) "the Vault" else "the personal side"

/**
 * Phase 2: who may reach which files (D57–D59). A tab per side lists its apps with what each may reach; tapping one
 * opens its rule. Every change is saved at once, sent to the Vault copy and applied; the access log and the backup
 * are at the bottom.
 */
@Composable
fun AccessRulesScreen(host: Host, tick: Int, open: (Screen) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by rememberSaveable { mutableStateOf(Place.VAULT) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<AppRecord?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var restoring by remember { mutableStateOf<RulesFile?>(null) }
    remember { RulesStore.load(context) }
    val file by RulesStore.file.collectAsState()
    val personal by produceState<List<AppRecord>?>(null, tick, local) { value = withContext(Dispatchers.IO) { PersonalApps.list(context) } }
    val vaultPaused = remember(tick, local) { VaultPower.paused() == true }
    val vault by produceState<List<AppRecord>?>(null, tick, local) { value = if (vaultPaused) emptyList() else VaultControl.apps() ?: emptyList() }
    val personalAllFiles = remember(tick, local) { Environment.isExternalStorageManager() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { context.say(exportRules(context, uri, file)) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() }
            AccessRules.import(text ?: "").fold({ restoring = it }, { context.say(Outcome(false, "Not restored: ${it.message}")) })
        }
    }

    val accent = if (place == Place.VAULT) VaultPurple else PersonalBlue
    editing?.let { app ->
        CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = if (app.place == Place.VAULT) VaultPurple else PersonalBlue, icon = LauncherIcons.Lock)) {
            RuleEditor(app, AccessRules.ruleFor(file.rules, app.pkg, app.place), onBack = { editing = null; local++ })
        }
        return
    }

    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = accent, icon = LauncherIcons.Lock)) {
        KitScaffold(title = "Access rules", onBack = onBack, scroll = false) {
            KitSegments(labels = listOf("Vault apps", "Personal apps"), selected = if (place == Place.VAULT) 0 else 1) {
                place = if (it == 0) Place.VAULT else Place.PERSONAL
            }
            val apps = if (place == Place.VAULT) vault else personal
            val rows = remember(apps, file, place, query) { ruleRows(apps.orEmpty(), file.rules, place, query) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Text(
                        if (place == Place.VAULT) {
                            "What each Vault app may reach: its files in the Vault (Android's permissions, set by Liquid Vault as the Vault's owner), and your personal files, which it sees only through the \"Liquid Vault\" place of the file picker."
                        } else {
                            "What each of your apps may reach: its files on the personal side (Android's permissions, set through the shell), and the Vault's files, which it sees only through the \"Liquid Vault\" place of the file picker."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
                if (place == Place.VAULT && !personalAllFiles) item { PersonalAllFilesCard(context) { local++ } }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search by name") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when {
                    place == Place.VAULT && vaultPaused -> item { Note("The Vault is paused: resume it on the Home screen to change its apps' rules.") }
                    apps == null -> item { Note(if (place == Place.VAULT) "Asking the Vault…" else "Reading your apps…") }
                    rows.isEmpty() -> item { Note(if (query.isBlank()) "No apps here." else "No app matches \"$query\".") }
                }
                items(rows, key = { "${it.first.place}:${it.first.pkg}" }) { (app, rule) ->
                    Row(
                        Modifier.fillMaxWidth().kitLoneCard().clickable { editing = app }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                rule?.summary() ?: "No rule: Android decides; nothing of ${if (place == Place.VAULT) "your personal files" else "the Vault"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rule != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { KitSection("The log and your rules") }
                item {
                    KitGroup {
                        KitNavRow(title = "Access log", summary = "Every time an app used the bridge, allowed or refused", icon = LauncherIcons.Recents) { open(Screen.ACCESS_LOG) }
                        KitNavRow(title = "Back up the rules", summary = "Save them to a file (${file.rules.size} rules)", icon = LauncherIcons.Pin) {
                            exportLauncher.launch("liquid-vault-rules-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.json")
                        }
                        KitNavRow(title = "Restore rules from a backup", summary = "Replaces your rules; you type REPLACE first", icon = LauncherIcons.Undo) {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    restoring?.let { backup ->
        TypedConfirm(
            title = "Replace your rules?",
            text = "The backup has ${backup.rules.size} rules for ${backup.rules.map { it.pkg }.distinct().size} apps. They replace your ${file.rules.size} current rules, and each app's own files are set again.",
            phrase = "REPLACE",
            confirmLabel = "Replace",
            onDismiss = { restoring = null },
        ) {
            restoring = null
            scope.launch { context.say(RulesStore.replaceAll(context, backup)); local++ }
        }
    }
}

/** Apps with a rule first, then the others, by name; rules of apps that are gone stay listed (so they can be removed). */
private fun ruleRows(apps: List<AppRecord>, rules: List<AppRule>, place: Place, query: String): List<Pair<AppRecord, AppRule?>> {
    val q = query.trim().lowercase()
    val present = apps.filter { it.place == place }.map { it to AccessRules.ruleFor(rules, it.pkg, place) }
    val gone = rules.filter { r -> r.place == place && apps.none { it.pkg == r.pkg } }.map { AppRecord(it.pkg, "${it.label} (not installed)", place) to it }
    return (present + gone)
        .filter { (a, _) -> q.isEmpty() || a.label.lowercase().contains(q) || a.pkg.lowercase().contains(q) }
        .sortedWith(compareBy({ it.second == null }, { it.first.label.lowercase() }))
}

/** The personal copy needs All files on the personal side to serve your personal files to Vault apps (D58). */
@Composable
private fun PersonalAllFilesCard(context: Context, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    InfoCard(
        title = "Vault apps cannot see your personal files yet",
        lines = listOf(
            "To share personal folders with Vault apps, Liquid Vault needs \"All files access\" on the personal side (it reads them for the Vault apps you allow, nothing else).",
        ),
    ) {
        Button(onClick = {
            val pkgUri = Uri.parse("package:${context.packageName}")
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)) }
                .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            onChanged()
        }) { Text("Open Android's setting") }
        if (Shell.unavailableReason() == null) {
            OutlinedButton(onClick = {
                scope.launch {
                    val r = runCatching { Shell.run(listOf("appops", "set", "--user", Env.myUserId.toString(), context.packageName, "MANAGE_EXTERNAL_STORAGE", "allow"), 20_000) }
                    context.say(if (r.getOrNull()?.ok == true && Environment.isExternalStorageManager()) Outcome(true, "Liquid Vault may read your personal files now") else Outcome(false, r.exceptionOrNull()?.asEvidence() ?: r.getOrNull()?.output ?: "not allowed"))
                    onChanged()
                }
            }) { Text("With the shell") }
        }
    }
}

private suspend fun exportRules(context: Context, uri: Uri, file: RulesFile): Outcome = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(AccessRules.export(file).toByteArray()) } ?: error("could not open the file")
        Outcome(true, "${file.rules.size} rules saved")
    }.getOrElse { Outcome(false, "Not saved: ${it.asEvidence()}") }
}

/**
 * One app's rule on one side: presets, its own side's files, and the other side's files (none, chosen folders, all;
 * read-only or read-write). Every change is saved at once.
 */
@Composable
private fun RuleEditor(app: AppRecord, saved: AppRule?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rule by remember(app) { mutableStateOf(saved ?: AppRule(app.pkg, app.place, label = app.label)) }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val across = AccessRules.acrossName(app.place)
    // What the app asks for, from the copy that has it: own files change nothing for an app that asks for none.
    val asks by produceState<String?>(null, app) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (app.place == Place.PERSONAL) OwnFilesControl.asks(context, app.pkg).getString("words")
                else Channel.call("appAsks", app.pkg)?.getString("words")
            }.getOrNull()
        }
    }
    BackHandler { onBack() }

    fun save(next: AppRule) {
        rule = next
        scope.launch {
            busy = true
            val outcome = RulesStore.put(context, next.copy(label = app.label))
            busy = false
            context.say(outcome)
        }
    }

    KitScaffold(title = app.label, onBack = onBack) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (app.place == Place.VAULT) "In the Vault" else "On the personal side", style = MaterialTheme.typography.bodyMedium)
                Text(rule.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp))
        }

        KitSection("Presets")
        KitChips(
            options = Preset.entries.map { it.name to it.label },
            selected = { name -> Preset.valueOf(name).applyTo(rule).let { it.own == rule.own && it.reach == rule.reach && it.folders == rule.folders && it.write == rule.write } },
            modifier = Modifier.padding(horizontal = 24.dp),
            enabled = !busy,
        ) { name -> save(Preset.valueOf(name).applyTo(rule)) }
        Text(
            Preset.entries.joinToString("\n") { "${it.label}: ${it.words}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        KitSection("Its own files, in ${ownSideName(app.place)}")
        asks?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 2.dp))
        }
        KitSegments(labels = listOf("Android", "None", "Media", "All"), selected = OwnFiles.entries.indexOf(rule.own)) { i ->
            if (!busy) save(rule.copy(own = OwnFiles.entries[i]))
        }
        Text(
            "${rule.own.label}: ${rule.own.words}." + if (app.place == Place.PERSONAL && rule.own != OwnFiles.ANDROID) " Set through the shell (it waits for Shevery when it is off)." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )

        KitSection("$across, through Liquid Vault")
        KitSegments(labels = listOf("None", "Folders", "All"), selected = Reach.entries.indexOf(rule.reach)) { i ->
            if (!busy) save(rule.copy(reach = Reach.entries[i]))
        }
        Text(
            when (rule.reach) {
                Reach.NONE -> "It cannot see any of the ${across.lowercase()}."
                Reach.FOLDERS -> "It sees only the folders below, in the \"Liquid Vault\" place of the file picker."
                Reach.ALL -> "It sees all of the ${across.lowercase()} in the \"Liquid Vault\" place of the file picker."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
        )
        if (rule.reach == Reach.FOLDERS) {
            rule.folders.forEach { folder ->
                Row(Modifier.fillMaxWidth().kitLoneCard().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.replace("/", " › "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { save(rule.copy(folders = rule.folders - folder)) }) { Text("Remove") }
                }
            }
            KitNavRow(title = "Add a folder", summary = "Choose one of the ${across.lowercase()}", icon = LauncherIcons.Add) { choosing = true }
        }
        if (rule.reach != Reach.NONE) {
            SwitchRow(
                title = "Can change files",
                summary = if (rule.write) "It may write, create, rename and delete files there" else "Read-only: it may open files, not change them",
                checked = rule.write,
                enabled = !busy,
            ) { save(rule.copy(write = it)) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosing) {
        FolderChooser(
            title = "A folder of the ${across.lowercase()}",
            filesOn = if (app.place == Place.VAULT) Place.PERSONAL else Place.VAULT,
            onDismiss = { choosing = false },
        ) { chosen ->
            choosing = false
            save(rule.copy(folders = rule.folders + chosen))
        }
    }
}

/**
 * Browses one side's folders: the personal side's in this copy (it reads them itself), the Vault's through the Vault
 * copy. "Choose this folder" picks the one shown.
 */
@Composable
private fun FolderChooser(title: String, filesOn: Place, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    val listing by produceState<Result<List<String>>?>(null, path) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b = if (filesOn == Place.PERSONAL && !Env.isVaultSide) SideStore.list(path) else Channel.call("storeList", path) ?: error("no answer")
                val names = b.getStringArray("names").orEmpty()
                val dirs = b.getBooleanArray("dirs") ?: BooleanArray(names.size)
                names.indices.filter { dirs[it] }.map { names[it] }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(if (path.isEmpty()) "Top of the storage" else path.replace("/", " › "), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (path.isNotEmpty()) item {
                        Text("‹ Up", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { path = StoragePaths.parent(path) }.padding(vertical = 10.dp))
                    }
                    val result = listing
                    when {
                        result == null -> item { Text("Reading…", modifier = Modifier.padding(vertical = 10.dp)) }
                        result.isFailure -> item { Text(result.exceptionOrNull()?.message ?: "could not read it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp)) }
                        result.getOrThrow().isEmpty() -> item { Text("No folders inside.", modifier = Modifier.padding(vertical = 10.dp)) }
                        else -> items(result.getOrThrow()) { name ->
                            Text("$name ›", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { path = StoragePaths.child(path, name) }.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { Button(enabled = path.isNotEmpty(), onClick = { onChoose(path) }) { Text("Choose this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Every use of the bridge, from both copies (brief decision 4): personal apps reaching the Vault's files, and Vault
 * apps reaching your personal files. Newest first; "Refused" shows only what the rules stopped.
 */
@Composable
fun AccessLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var onlyRefused by rememberSaveable { mutableStateOf(false) }
    val entries by produceState<List<Pair<AccessEntry, Place>>?>(null) {
        val mine = withContext(Dispatchers.IO) { AccessLog.entries() }.map { it to Place.PERSONAL }
        val theirs = VaultControl.accessLog().mapNotNull { runCatching { VaultJson.decodeFromString(AccessEntry.serializer(), it) }.getOrNull() }.map { it to Place.VAULT }
        value = (mine + theirs).sortedByDescending { it.first.atMillis }
    }
    val time = remember { SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()) }
    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = VaultPurple, icon = LauncherIcons.Recents)) {
        KitScaffold(title = "Access log", onBack = onBack, scroll = false) {
            KitSegments(labels = listOf("All", "Refused"), selected = if (onlyRefused) 1 else 0) { onlyRefused = it == 1 }
            val shown = entries.orEmpty().filter { !onlyRefused || !it.first.allowed }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    entries == null -> item { Note("Reading both copies' logs…") }
                    shown.isEmpty() -> item { Note(if (onlyRefused) "Nothing was refused." else "No app has used the bridge yet.") }
                }
                items(shown) { (e, appsSide) ->
                    Column(Modifier.fillMaxWidth().kitLoneCard().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        val who = e.callerPackage ?: "an unknown app"
                        val side = if (appsSide == Place.PERSONAL) "personal app → Vault files" else "Vault app → personal files"
                        Text("${time.format(Date(e.atMillis))} · $who", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$side · ${e.action} · ${e.document}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            (if (e.allowed) "Allowed — " else "REFUSED — ") + e.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
