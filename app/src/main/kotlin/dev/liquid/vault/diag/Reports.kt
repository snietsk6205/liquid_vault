package dev.liquid.vault.diag

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import dev.liquid.vault.Env
import dev.liquid.vault.bridge.AccessLog
import dev.liquid.vault.channel.Channel
import dev.liquid.vault.core.common.BootRecord
import dev.liquid.vault.core.common.CheckResult
import dev.liquid.vault.core.common.CheckStatus
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.PhaseZero
import dev.liquid.vault.core.common.Report
import dev.liquid.vault.core.common.VaultJson
import dev.liquid.vault.core.common.asEvidence
import dev.liquid.vault.shell.Shell
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** "Copy report": this copy's facts, every check with its evidence, the access log, and the debug log. */
object Reports {
    fun build(context: Context, includeOtherSide: Boolean): String {
        Env.init(context)
        CheckStore.load(context)
        val side = Env.side()
        val prefs = Prefs.of(context)
        val facts = listOf(
            "App" to "Liquid Vault ${Env.versionName()} (build ${Env.versionCode()})",
            "This copy" to "${side.label}, user ${Env.myUserId}",
            "Device" to Env.deviceLine(),
            "MagicOS" to (Env.magicOsVersion() ?: "not found"),
            "Shell" to if (Shell.alive) "connected through ${Shell.state.value.door?.label ?: "?"}" else "not connected",
            "Channel" to if (Channel.ready()) "ready (${prefs.getString(Prefs.CHANNEL_VIA, "?")})" else "not ready",
        )
        val extra = mutableListOf<Pair<String, List<String>>>()
        if (side == dev.liquid.vault.AppSide.VAULT) {
            extra += "Vault setup steps" to (prefs.getString(Prefs.SETUP_STEPS, "") ?: "").lines().filter { it.isNotBlank() }
        }
        extra += "Boot record" to listOfNotNull(prefs.getString(Prefs.BOOT_RECORD, null))
        extra += "Bridge access log (last 40)" to Report.accessLines(AccessLog.entries().takeLast(40))
        extra += "Debug log (last 300 lines)" to DebugLog.format(DebugLog.snapshot().takeLast(300)).lines()
        val defs = PhaseZero.forSide(side == dev.liquid.vault.AppSide.VAULT)
        val mine = Report.render("Liquid Vault — Diagnostics report (${side.label})", facts, defs, CheckStore.book.value.results, extra)
        if (!includeOtherSide) return mine
        val other = if (Channel.ready()) runCatching { Channel.call("report")?.getString("text") }.getOrElse { "(the other copy could not be reached: ${it.asEvidence()})" }
        else "(the channel is not set up: open the other copy and use its own \"Copy report\")"
        return mine + "\n\n######## The other copy ########\n\n" + (other ?: "(no answer)")
    }
}

/** What the other copy may ask this copy to do through the channel (only things that need no screen). */
object ChannelCalls {
    private val headless = setOf("A1", "A2", "S1", "S3", "V3", "P1", "C1", "C4", "F3", "F8", "F8-undo", "L1", "B0", "B3", "R1", "R2", "R3", "R4")

    fun runHeadless(context: Context, id: String, extras: Bundle?): String {
        Env.init(context)
        if (id == "access-log") return Runner.accessLogJson()
        val result = if (id !in headless) {
            CheckResult(id, CheckStatus.SKIPPED, "this check cannot be run from the other copy", atMillis = System.currentTimeMillis())
        } else runBlocking {
            Runner.run(id, host = null, extras = extras?.keySet()?.associateWith { extras.getString(it) ?: "" } ?: emptyMap())
        }
        return VaultJson.encodeToString(CheckResult.serializer(), result)
    }

    fun destroyFromChannel(context: Context): Bundle {
        destroyNow(context, "the personal copy's Destroy (typed confirmation checked)")
        return Bundle().apply { putBoolean("accepted", true) }
    }

    /** Removes the Vault (the work profile and everything in it). Called only after the typed confirmation. */
    fun destroyNow(context: Context, why: String) {
        DebugLog.event("Destroy", "removing the Vault now ($why)")
        thread(name = "destroy") {
            SystemClock.sleep(400) // let the answer reach the caller before this process ends with the profile
            runCatching { Env.dpm.wipeData(0) }.onFailure { DebugLog.error("Destroy", "wipeData failed: ${it.asEvidence()}") }
        }
    }
}

/** At boot each copy writes down what it finds (reboot test R1–R4), before anyone opens the app. */
object BootRecorder {
    fun record(context: Context) {
        Env.init(context)
        val vault = Env.isVaultSide
        val rec = BootRecord(
            side = Env.side().id,
            userId = Env.myUserId,
            bootWallMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            recordedAtMillis = System.currentTimeMillis(),
            profileOwner = vault,
            profileName = null,
            organizationName = if (vault) runCatching { Env.dpm.getOrganizationName(Env.admin)?.toString() }.getOrNull() else null,
            screenCaptureDisabled = if (vault) runCatching { Env.dpm.getScreenCaptureDisabled(Env.admin) }.getOrNull() else null,
            channelGrants = runCatching { context.contentResolver.persistedUriPermissions.size }.getOrDefault(-1),
            shellAlive = Shell.alive,
            quietMode = if (!vault) Env.vaultUser()?.let { runCatching { Env.um.isQuietModeEnabled(it) }.getOrNull() } else null,
            shellBlocked = if (vault) runCatching { dev.liquid.vault.admin.ShellAccess.read().blocked }.getOrNull() else null,
        )
        Prefs.of(context).edit().putString(Prefs.BOOT_RECORD, VaultJson.encodeToString(BootRecord.serializer(), rec)).apply()
        DebugLog.event("Boot", "recorded the boot: owner ${rec.profileOwner}, lasting permissions ${rec.channelGrants}, shell ${rec.shellAlive}, Vault paused ${rec.quietMode}")
    }
}
