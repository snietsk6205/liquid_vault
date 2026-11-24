package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a Diagnostics check found (Phase 0, D11). Every check ends in one of these; an exception inside a check becomes
 * [FAIL] with the exception's name and message, never a crash.
 */
@Serializable
enum class CheckStatus(val label: String) {
    NOT_RUN("NOT RUN"),
    RUNNING("RUNNING"),
    PASS("PASS"),
    FAIL("FAIL"),
    /** Could not run here, with the reason in the summary (e.g. "needs shell", "needs the Vault"). */
    SKIPPED("SKIPPED"),
    /** Waiting for Med to look at something and answer. */
    NEEDS_YOU("NEEDS YOU"),
    /** A fact, not a test (device, versions). */
    INFO("INFO"),
}

/**
 * The last result of one check. [evidence] holds the lines that explain it: commands with their output, values read
 * back, answers given. [question] is set while the check waits for an answer ([CheckStatus.NEEDS_YOU]).
 */
@Serializable
data class CheckResult(
    val id: String,
    val status: CheckStatus,
    val summary: String,
    val evidence: List<String> = emptyList(),
    val atMillis: Long = 0L,
    val question: String? = null,
)

/** One side's results, saved whole (written to a temporary file and renamed into place). */
@Serializable
data class CheckBook(
    val side: String,
    val userId: Int,
    val results: Map<String, CheckResult> = emptyMap(),
) {
    fun with(result: CheckResult): CheckBook = copy(results = results + (result.id to result))
    fun without(id: String): CheckBook = copy(results = results - id)
}

/** The JSON settings shared by every file the app writes (unknown keys ignored, so older files still load). */
val VaultJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Turns an exception into the evidence line a FAIL shows: the exception's simple name and its message. */
fun Throwable.asEvidence(): String {
    val name = this::class.java.simpleName.ifEmpty { this::class.java.name }
    val cause = cause?.let { " (cause: ${it::class.java.simpleName}: ${it.message})" } ?: ""
    return "$name: ${message ?: "no message"}$cause"
}
