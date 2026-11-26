package dev.liquid.vault.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.liquid.vault.control.ActionQueueStore
import dev.liquid.vault.control.AppIcons
import dev.liquid.vault.control.AppLaunch
import dev.liquid.vault.control.Cloning
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.PersonalApps
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.control.VaultPower
import dev.liquid.vault.core.common.AppLists
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.AppRow
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.ShellAction
import dev.liquid.vault.core.designsystem.KitScaffold
import dev.liquid.vault.core.designsystem.KitScreenInfo
import dev.liquid.vault.core.designsystem.KitSegments
import dev.liquid.vault.core.designsystem.LauncherIcons
import dev.liquid.vault.core.designsystem.LocalKitScreen
import dev.liquid.vault.core.designsystem.kitLoneCard
import dev.liquid.vault.diag.Host
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Both sides' apps (Phase 1): a tab each, a search field, and a sheet of actions per app. Vault apps are changed by the
 * Vault's owner through the channel; personal apps by the shell (waiting in the queue while it is off).
 */
@Composable
fun AppsScreen(host: Host, start: Place, tick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var place by rememberSaveable { mutableStateOf(start) }
    var query by rememberSaveable { mutableStateOf("") }
    var local by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<AppRow?>(null) }
    val personal by produceState<List<AppRecord>?>(null, tick, local) { value = withContext(Dispatchers.IO) { PersonalApps.list(context) } }
    val vaultPaused = remember(tick, local) { VaultPower.paused() == true }
    var vaultProblem by remember { mutableStateOf<String?>(null) }
    val vault by produceState<List<AppRecord>?>(null, tick, local) {
        value = if (vaultPaused) emptyList() else VaultControl.apps().also {
            vaultProblem = if (it == null) "The Vault copy did not answer. Open Diagnostics › L0 to check the channel." else null
        } ?: emptyList()
    }
    val rows = remember(place, query, personal, vault) { AppLists.rows(place, personal.orEmpty(), vault.orEmpty(), query) }
    val accent = if (place == Place.VAULT) VaultPurple else PersonalBlue

    CompositionLocalProvider(LocalKitScreen provides KitScreenInfo(accent = accent, icon = if (place == Place.VAULT) LauncherIcons.Work else LauncherIcons.Apps)) {
        KitScaffold(title = "Apps", onBack = onBack, scroll = false) {
            KitSegments(
                labels = listOf("Vault${vault?.let { " (${it.size})" } ?: ""}", "Personal${personal?.let { " (${it.size})" } ?: ""}"),
                selected = if (place == Place.VAULT) 0 else 1,
            ) { place = if (it == 0) Place.VAULT else Place.PERSONAL }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search by name") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val loading = (if (place == Place.VAULT) vault else personal) == null
            when {
                place == Place.VAULT && vaultPaused -> Note("The Vault is paused: resume it on the Home screen to see and change its apps.")
                place == Place.VAULT && vaultProblem != null -> Note(vaultProblem!!)
                loading && place == Place.VAULT -> Note("Asking the Vault…")
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                rows.isEmpty() -> Note(if (query.isBlank()) "No apps here." else "No app matches \"$query\".")
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = { "${it.app.place}:${it.app.pkg}" }) { row -> AppRowItem(row) { chosen = row } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    chosen?.let { row ->
        AppSheet(host.activity, row, onDone = { outcome ->
            chosen = null
            outcome?.let { context.say(it) }
            local++
        })
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
}

@Composable
internal fun AppIcon(app: AppRecord, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.pkg, app.place) { value = AppIcons.get(context, app, px) }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
            ?: Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

@Composable
private fun AppRowItem(row: AppRow, onClick: () -> Unit) {
    val dim = row.app.frozen || row.app.suspended
    Row(
        Modifier.fillMaxWidth().kitLoneCard().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(if (dim) 0.45f else 1f)) { AppIcon(row.app, 40.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.status.isNotEmpty()) Text(row.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One action of the sheet: its words, what it explains, and what it does (null outcome: nothing to say). */
private data class SheetAction(val label: String, val explains: String, val danger: Boolean = false, val run: suspend () -> Outcome?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(activity: Activity, row: AppRow, onDone: (Outcome?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = row.app
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shellNow = Shell.unavailableReason() == null
    val actions = buildList {
        if (app.place == Place.VAULT) {
            if (!app.frozen && !app.suspended) add(SheetAction("Open", "Start it in the Vault") { AppLaunch.open(context, app) })
            add(
                if (app.frozen) SheetAction("Unfreeze", "Bring it back to the Vault's apps") { VaultControl.appAction("unfreeze", app.pkg) }
                else SheetAction("Freeze", "Gone from the Vault's apps and cannot run; its data stays") { VaultControl.appAction("freeze", app.pkg) },
            )
            add(
                if (app.suspended) SheetAction("Unsuspend", "It opens again") { VaultControl.appAction("unsuspend", app.pkg) }
                else SheetAction("Suspend", "Its icon stays, grey; it cannot open; its data stays") { VaultControl.appAction("suspend", app.pkg) },
            )
            if (!app.frozen) add(SheetAction("App info", "Android's page for it in the Vault (permissions, storage)") { AppLaunch.info(context, app).takeIf { !it.ok } })
            add(SheetAction("Remove from the Vault", "The app and its Vault data go; your personal copy stays", danger = true) { confirmRemove = true; null })
        } else {
            if (row.onOtherSide) add(SheetAction("Open its Vault copy", "Start the copy that is in the Vault") { AppLaunch.open(context, app.copy(place = Place.VAULT)) })
            else add(SheetAction("Copy into the Vault", if (shellNow) "Copied silently by the shell" else if (app.system) "Built into the phone: switched on in the Vault" else "Android asks you to confirm") { Cloning.copyIntoVault(activity, app) })
            val via = if (shellNow) "" else " (waits for the shell)"
            add(
                if (app.frozen) SheetAction("Unfreeze", "Back in your apps$via") { ActionQueueStore.request(context, ShellAction.UNFREEZE, app) }
                else SheetAction("Freeze", "Gone from your apps and cannot run; its data stays$via") { ActionQueueStore.request(context, ShellAction.FREEZE, app) },
            )
            add(
                if (app.suspended) SheetAction("Unsuspend", "It opens again$via") { ActionQueueStore.request(context, ShellAction.UNSUSPEND, app) }
                else SheetAction("Suspend", "Its icon stays, grey; it cannot open$via") { ActionQueueStore.request(context, ShellAction.SUSPEND, app) },
            )
        }
    }

    ModalBottomSheet(onDismissRequest = { onDone(null) }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.status.isNotEmpty()) Text(row.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (busy) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            actions.forEach { a ->
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            val outcome = runCatching { a.run() }.getOrElse { Outcome(false, it.message ?: "failed") }
                            busy = false
                            if (!confirmRemove) onDone(outcome)
                        }
                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(a.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(a.explains, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmRemove) {
        TypedConfirm(
            title = "Remove ${app.label} from the Vault?",
            text = "The app and everything it keeps in the Vault are deleted. Your personal copy of it (if any) stays.",
            phrase = "REMOVE",
            confirmLabel = "Remove",
            onDismiss = { confirmRemove = false },
        ) {
            confirmRemove = false
            scope.launch {
                busy = true
                val outcome = Cloning.removeFromVault(activity, app)
                busy = false
                onDone(outcome)
            }
        }
    }
}
