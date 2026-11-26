package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** One line of the bridge's access log: who asked for what, and what the bridge decided. */
@Serializable
data class AccessEntry(
    val atMillis: Long,
    /** "A" (Vault-side provider, Work tab) or "B" (personal-side provider) or "channel". */
    val via: String,
    val callerPackage: String?,
    val callerUser: Int,
    val action: String,
    val document: String,
    val allowed: Boolean,
    val reason: String,
)

/** What a copy found when the phone finished booting (reboot test, R1–R4). */
@Serializable
data class BootRecord(
    val side: String,
    val userId: Int,
    /** When the phone booted, on the wall clock (now minus the time since boot). */
    val bootWallMillis: Long,
    val recordedAtMillis: Long,
    val profileOwner: Boolean,
    val profileName: String? = null,
    val organizationName: String? = null,
    val screenCaptureDisabled: Boolean? = null,
    val channelGrants: Int = 0,
    val shellAlive: Boolean = false,
    val quietMode: Boolean? = null,
    /** Vault side: whether "no debugging features" kept the shell out of the Vault at this boot (D37). */
    val shellBlocked: Boolean? = null,
) {
    /** Two boot times within a minute of each other are the same boot (the clocks they come from drift a little). */
    fun sameBootAs(otherBootWallMillis: Long): Boolean = abs(bootWallMillis - otherBootWallMillis) < 60_000
}

/** The text that "Copy report" puts on the clipboard: facts, every check with its evidence, logs. */
object Report {
    private fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    fun checkLines(def: CheckDef, result: CheckResult?): List<String> {
        val head = buildString {
            append('[').append(result?.status?.label ?: CheckStatus.NOT_RUN.label).append("] ")
            append(def.id).append(' ').append(def.title)
            if (result != null && result.summary.isNotBlank()) append(" — ").append(result.summary)
        }
        val lines = mutableListOf(head)
        if (result != null) {
            if (result.atMillis > 0) lines += "    at ${time(result.atMillis)}"
            result.question?.let { lines += "    waiting for your answer: $it" }
            result.evidence.forEach { e -> e.lines().forEach { lines += "    · $it" } }
        }
        return lines
    }

    fun accessLines(entries: List<AccessEntry>): List<String> = entries.map { e ->
        val verdict = if (e.allowed) "allowed" else "REFUSED"
        "${time(e.atMillis)} [${e.via}] ${e.callerPackage ?: "?"} (user ${e.callerUser}) ${e.action} ${e.document}: $verdict — ${e.reason}"
    }

    fun render(
        title: String,
        facts: List<Pair<String, String>>,
        defs: List<CheckDef>,
        results: Map<String, CheckResult>,
        extraSections: List<Pair<String, List<String>>> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine(title)
        appendLine("Written ${time(nowMillis)}")
        facts.forEach { (k, v) -> appendLine("$k: $v") }
        val counts = defs.groupingBy { results[it.id]?.status ?: CheckStatus.NOT_RUN }.eachCount()
        appendLine(
            "Totals: " + CheckStatus.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${counts[it]} ${it.label}" },
        )
        defs.groupBy { it.group }.forEach { (group, inGroup) ->
            appendLine()
            appendLine("== $group ==")
            inGroup.forEach { def -> checkLines(def, results[def.id]).forEach { appendLine(it) } }
        }
        extraSections.forEach { (heading, lines) ->
            appendLine()
            appendLine("== $heading ==")
            if (lines.isEmpty()) appendLine("(nothing)") else lines.forEach { appendLine(it) }
        }
    }
}
