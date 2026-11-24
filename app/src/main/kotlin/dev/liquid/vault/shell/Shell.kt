package dev.liquid.vault.shell

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.liquid.vault.core.common.DebugLog
import dev.liquid.vault.core.common.ShellCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** What a shell command printed and how it ended. */
data class ShellResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val output: String get() = (stdout + (if (stderr.isNotBlank()) "\n" + stderr else "")).trim()

    /** The lines a check shows: the command as typed, its output, and how it ended. */
    fun evidence(): String = buildString {
        append("$ ").append(ShellCommands.render(command))
        if (output.isNotEmpty()) append('\n').append(output.take(4000))
        append('\n').append(if (timedOut) "(stopped after ${durationMs} ms: no answer)" else "(exit $exitCode, $durationMs ms)")
    }
}

/**
 * The shell, through Shevery (a Shizuku fork) with the official Shizuku client API 13.1.5 (D6). Commands run in the
 * server's process as the shell user (uid 2000) through `IShizukuService.newProcess`, the server's public AIDL call,
 * with arguments as an array (nothing goes through `sh -c`, so nothing needs quoting).
 */
object Shell {
    enum class Door(val label: String, val authoritySuffix: String) {
        SHEVERY("Shevery's own client API", ".shevery"),
        SHIZUKU("the official Shizuku API", ".shizuku"),
    }

    data class State(
        val connected: Boolean = false,
        val door: Door? = null,
        val connectedAt: Long = 0L,
        val permissionAsked: Boolean = false,
        val lastPermissionResult: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var lastDoor: Door? = null

    fun init() {
        Shizuku.addBinderReceivedListenerSticky {
            _state.value = _state.value.copy(connected = true, door = lastDoor, connectedAt = System.currentTimeMillis())
            DebugLog.event("Shell", "the shell server connected through ${lastDoor?.label ?: "an unknown door"} (server version ${versionOrNull() ?: "?"}, uid ${uidOrNull() ?: "?"})")
        }
        Shizuku.addBinderDeadListener {
            _state.value = _state.value.copy(connected = false)
            DebugLog.event("Shell", "the shell server went away (Shevery stopped, or the phone restarted)")
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            _state.value = _state.value.copy(lastPermissionResult = result)
            DebugLog.event("Shell", if (result == PackageManager.PERMISSION_GRANTED) "the shell server (Shevery) allowed this app" else "the shell server (Shevery) did not allow this app")
        }
    }

    internal fun onDoorUsed(door: Door) {
        lastDoor = door
        _state.value = _state.value.copy(door = door)
        DebugLog.decision("Shell", "the shell server knocked at ${door.label} (${door.authoritySuffix})")
    }

    val alive: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun versionOrNull(): Int? = runCatching { Shizuku.getVersion() }.getOrNull()
    fun patchOrNull(): Int? = runCatching { Shizuku.getServerPatchVersion() }.getOrNull()
    fun uidOrNull(): Int? = runCatching { Shizuku.getUid() }.getOrNull()
    fun seContextOrNull(): String? = runCatching { Shizuku.getSELinuxContext() }.getOrNull()

    /** Shevery has allowed this app (its Allow dialog was answered "allow"). */
    fun permitted(): Boolean = alive && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        _state.value = _state.value.copy(permissionAsked = true)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Why the shell cannot be used right now, in plain words, or null when it can. */
    fun unavailableReason(): String? = when {
        !alive -> "needs shell: Shevery is not running (start it, then come back)"
        !permitted() -> "needs shell: Shevery has not allowed Liquid Vault yet (S2)"
        else -> null
    }

    suspend fun run(command: List<String>, timeoutMs: Long = 60_000): ShellResult = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val binder = Shizuku.getBinder() ?: error("the shell server is not connected")
        val service = IShizukuService.Stub.asInterface(binder)
        val process = service.newProcess(command.toTypedArray(), null, null)
        runCatching { ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).close() }
        coroutineScope {
            val out = async(Dispatchers.IO) { readAll(process.inputStream) }
            val err = async(Dispatchers.IO) { readAll(process.errorStream) }
            val finished = process.waitForTimeout(timeoutMs, "MILLISECONDS")
            if (!finished) runCatching { process.destroy() }
            val code = if (finished) process.exitValue() else -1
            val result = ShellResult(command, code, out.await(), err.await(), !finished, SystemClock.elapsedRealtime() - started)
            DebugLog.decision("Shell", "ran ${ShellCommands.render(command)}: ${if (result.timedOut) "no answer" else "exit ${result.exitCode}"} in ${result.durationMs} ms")
            result
        }
    }

    private fun readAll(pfd: ParcelFileDescriptor?): String =
        pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() } } ?: ""

    const val REQUEST_CODE = 7101
}
