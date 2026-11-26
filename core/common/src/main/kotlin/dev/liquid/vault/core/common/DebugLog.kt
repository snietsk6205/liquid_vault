package dev.liquid.vault.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ordered by verbosity: a level is recorded when it is at or below [DebugLog.verbosity]. [mark] is how a line shows its
 * level in the log and the report: errors must stand out ("E" was both ERROR and EVENT before 0.3.0).
 */
enum class LogLevel(val mark: String) { ERROR("!"), EVENT("·"), DECISION("→"), TREE(" ") }

data class LogEntry(
    val wallTime: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * In-memory ring buffer shown on the debug log screen. It is deliberately free of Android
 * classes so the redirect engine can log from plain JVM unit tests; the app installs [sink]
 * to mirror entries to logcat.
 */
object DebugLog {
    @Volatile var enabled: Boolean = true
    @Volatile var verbosity: LogLevel = LogLevel.DECISION
    @Volatile var maxEntries: Int = 2000
    @Volatile var sink: ((LogEntry) -> Unit)? = null

    private val buffer = ArrayDeque<LogEntry>()
    private val _version = MutableStateFlow(0L)

    /** Increments on every change, so UI can observe it and call [snapshot]. */
    val version: StateFlow<Long> = _version

    fun log(level: LogLevel, tag: String, message: String) {
        if (!enabled || level.ordinal > verbosity.ordinal) return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > maxEntries) buffer.removeFirst()
        }
        _version.value = _version.value + 1
        sink?.invoke(entry)
    }

    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun event(tag: String, message: String) = log(LogLevel.EVENT, tag, message)
    fun decision(tag: String, message: String) = log(LogLevel.DECISION, tag, message)
    fun tree(tag: String, message: String) = log(LogLevel.TREE, tag, message)

    fun snapshot(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _version.value = _version.value + 1
    }

    fun format(entries: List<LogEntry> = snapshot()): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return entries.joinToString("\n") { e -> line(e, time) }
    }

    /** One line: time, level mark (! error, · event, → decision), tag and message. */
    fun line(e: LogEntry, time: SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)): String =
        "${time.format(Date(e.wallTime))} ${e.level.mark} ${e.tag}: ${e.message.replace('\n', ' ')}"

    /** The same line with the date, for the log kept on disk across restarts. */
    fun dated(e: LogEntry): String = line(e, SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US))
}
