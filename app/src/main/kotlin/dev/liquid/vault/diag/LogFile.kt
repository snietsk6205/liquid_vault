package dev.liquid.vault.diag

import android.content.Context
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.LogEntry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The debug log kept on disk (D56): every line is appended to a file in this copy's private storage, so "Copy report"
 * still has what happened before the app was restarted (the 0.2.0 report lost step 12's error that way). Each copy
 * keeps its own file; it is trimmed to its newest half when it grows past [MAX_BYTES].
 */
object LogFile {
    private const val NAME = "debug-log.txt"
    private const val MAX_BYTES = 400_000L
    private const val KEEP_CHARS = 200_000
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "debug-log").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    fun attach(context: Context) {
        file = File(context.filesDir, NAME)
    }

    fun append(e: LogEntry) {
        val f = file ?: return
        val line = DebugLog.dated(e)
        io.execute {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) trim(f)
            }
        }
    }

    private fun trim(f: File) {
        val text = f.readText()
        val from = text.indexOf('\n', (text.length - KEEP_CHARS).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val tmp = File(f.parentFile, "$NAME.tmp")
        tmp.writeText(text.substring(from))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /** The newest [n] lines, across restarts, oldest first; empty when nothing was written yet. */
    fun tail(n: Int): List<String> {
        val f = file ?: return emptyList()
        runCatching { io.submit {}.get(2, TimeUnit.SECONDS) } // lines still being written come too
        return runCatching { if (f.isFile) f.readLines().takeLast(n) else emptyList() }.getOrDefault(emptyList())
    }
}
