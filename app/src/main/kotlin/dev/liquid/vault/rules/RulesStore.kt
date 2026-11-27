package dev.liquid.vault.rules

import android.content.Context
import dev.liquid.vault.Env
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.control.ActionQueueStore
import dev.liquid.vault.control.Outcome
import dev.liquid.vault.control.VaultControl
import dev.liquid.vault.core.common.AccessRules
import dev.liquid.vault.core.common.AppRecord
import dev.liquid.vault.core.common.AppRule
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.OwnFiles
import dev.liquid.vault.core.common.Place
import dev.liquid.vault.core.common.RulesFile
import dev.liquid.vault.core.common.ShellAction
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.core.common.asEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The access rules (Phase 2, D59). The personal copy keeps them (rules.json: the app lives there, D42) and sends every
 * change to the Vault copy, which keeps a mirror (rules-mirror.json) for its bridge, the one Vault apps use; the Vault
 * copy also asks for them when it starts, in case it was paused when they changed. Files are written whole and renamed
 * into place.
 */
object RulesStore {
    private val _file = MutableStateFlow(RulesFile())
    val file: StateFlow<RulesFile> = _file
    @Volatile private var loaded = false

    private fun path(context: Context) = File(context.filesDir, if (Env.isVaultSide) "rules-mirror.json" else "rules.json")

    fun rules(context: Context): List<AppRule> {
        load(context)
        return _file.value.rules
    }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = path(context)
        _file.value = runCatching { if (f.isFile) VaultJson.decodeFromString(RulesFile.serializer(), f.readText()) else RulesFile() }
            .onFailure {
                DebugLog.error("Rules", "the rules could not be read (${it.message}); kept aside as ${f.name}.broken")
                f.renameTo(File(f.parentFile, f.name + ".broken"))
            }.getOrDefault(RulesFile())
    }

    @Synchronized
    private fun save(context: Context, file: RulesFile) {
        _file.value = file
        // The pickers ask again whether this side's "Liquid Vault" place has anything to show.
        dev.liquid.vault.bridge.CrossBridgeProvider.notifyRootsChanged("${context.packageName}.bridge.${if (Env.isVaultSide) "vault" else "personal"}")
        val target = path(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            tmp.writeText(VaultJson.encodeToString(RulesFile.serializer(), file))
            if (!tmp.renameTo(target)) error("rename failed")
        }.onFailure { DebugLog.error("Rules", "the rules could not be saved: ${it.message}") }
    }

    /**
     * Personal copy: puts [rule] in place (a rule that changes nothing removes the app's rule), sends the rules to the
     * Vault copy, and applies the app's own files when they changed: as the Vault's owner for a Vault app, through the
     * shell for a personal app (it waits for the shell when it is off).
     */
    suspend fun put(context: Context, rule: AppRule): Outcome = withContext(Dispatchers.IO) {
        check(!Env.isVaultSide) { "the rules are changed in the personal copy" }
        load(context)
        val before = AccessRules.ruleFor(_file.value.rules, rule.pkg, rule.place)
        val next = AccessRules.put(_file.value.rules, rule)
        save(context, RulesFile(savedAt = System.currentTimeMillis(), rules = next))
        val kept = AccessRules.ruleFor(next, rule.pkg, rule.place)
        DebugLog.event("Rules", "${rule.label} (${rule.place.label.lowercase()}): ${kept?.summary() ?: "no rule (Android decides, nothing across)"}")
        val sync = syncToVault(context)
        val ownBefore = before?.own ?: OwnFiles.ANDROID
        val ownNow = kept?.own ?: OwnFiles.ANDROID
        val own = if (ownBefore != ownNow) applyOwn(context, rule.copy(own = ownNow)) else null
        val words = buildList {
            add(kept?.summary() ?: "${rule.label}: no rule, Android decides")
            own?.let { add(it.message) }
            if (!sync.ok) add(sync.message)
        }
        Outcome((own?.ok ?: true) && sync.ok, words.joinToString(" · "))
    }

    /** Personal copy: the rules from a backup replace all rules (after the typed confirmation); own files re-applied. */
    suspend fun replaceAll(context: Context, file: RulesFile): Outcome = withContext(Dispatchers.IO) {
        check(!Env.isVaultSide) { "the rules are changed in the personal copy" }
        load(context)
        val old = _file.value.rules
        save(context, file.copy(savedAt = System.currentTimeMillis()))
        val sync = syncToVault(context)
        // Every app whose own files differ from before gets them set (apps that lost their rule go back to Android).
        val touched = (old + file.rules).map { it.pkg to it.place }.distinct()
        var failed = 0
        for ((pkg, place) in touched) {
            val was = AccessRules.ruleFor(old, pkg, place)?.own ?: OwnFiles.ANDROID
            val now = AccessRules.ruleFor(file.rules, pkg, place)
            val nowOwn = now?.own ?: OwnFiles.ANDROID
            if (was != nowOwn) {
                val r = applyOwn(context, now ?: AppRule(pkg, place, own = OwnFiles.ANDROID))
                if (!r.ok) failed++
            }
        }
        DebugLog.event("Rules", "rules replaced from a backup: ${file.rules.size} rules")
        Outcome(sync.ok && failed == 0, "${file.rules.size} rules in place" + (if (failed > 0) " · $failed apps' own files not set" else "") + (if (!sync.ok) " · ${sync.message}" else ""))
    }

    private suspend fun applyOwn(context: Context, rule: AppRule): Outcome = when (rule.place) {
        Place.VAULT -> VaultControl.ownFiles(rule.pkg, rule.own)
        Place.PERSONAL -> ActionQueueStore.request(context, ShellAction.OWN_FILES, AppRecord(rule.pkg, rule.label, Place.PERSONAL), arg = rule.own.name)
    }

    /** Personal copy: sends the rules to the Vault copy (its bridge needs the Vault apps' rules). */
    suspend fun syncToVault(context: Context): Outcome = withContext(Dispatchers.IO) {
        if (Env.vaultUser() == null) return@withContext Outcome(true, "no Vault")
        if (!Channel.ready()) return@withContext Outcome(false, "the Vault copy will get the rules when the channel is ready")
        val json = VaultJson.encodeToString(RulesFile.serializer(), _file.value)
        runCatching { Channel.call("rulesSync", json) }.fold(
            { Outcome(true, "the Vault copy has the rules") },
            { Outcome(false, "the Vault copy did not get the rules (${it.asEvidence()}); it asks again when it starts") },
        )
    }

    /** Vault copy: the rules sent by the personal copy. */
    fun mirror(context: Context, json: String) {
        check(Env.isVaultSide) { "only the Vault copy keeps a mirror" }
        val file = VaultJson.decodeFromString(RulesFile.serializer(), json)
        loaded = true
        save(context, file)
        DebugLog.event("Rules", "rules from the personal copy: ${file.rules.size}")
    }

    /** Vault copy, at start: asks the personal copy for the rules, in case a change was missed while it was paused. */
    fun pullFromPersonal(context: Context) {
        if (!Env.isVaultSide || !Channel.ready()) return
        runCatching { Channel.call("rulesGet")?.getString("json") }
            .onSuccess { json -> if (json != null) mirror(context, json) }
            .onFailure { DebugLog.decision("Rules", "could not ask the personal copy for the rules: ${it.asEvidence()}") }
    }

    /** Personal copy: the rules as JSON, for the Vault copy. */
    fun json(context: Context): String {
        load(context)
        return VaultJson.encodeToString(RulesFile.serializer(), _file.value)
    }
}
