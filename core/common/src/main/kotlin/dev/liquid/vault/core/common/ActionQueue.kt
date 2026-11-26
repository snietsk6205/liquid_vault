package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable

/** What the shell does to a personal app for Liquid Vault (D45, D46). */
@Serializable
enum class ShellAction(val label: String, val done: String) {
    FREEZE("Freeze", "frozen"),
    UNFREEZE("Unfreeze", "unfrozen"),
    SUSPEND("Suspend", "suspended"),
    UNSUSPEND("Unsuspend", "unsuspended"),
    /** Phase 2: the app's own files ([QueuedAction.arg] is an [OwnFiles] name), D57. */
    OWN_FILES("Set files of", "set"),
}

/** An action that waits for the shell (Shevery) when it is off, kept in a file until it is done. */
@Serializable
data class QueuedAction(
    val id: Long,
    val action: ShellAction,
    val pkg: String,
    val label: String,
    val userId: Int,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** What the action needs besides the app: for [ShellAction.OWN_FILES], the [OwnFiles] name. */
    val arg: String? = null,
) {
    /** "Freeze Firefox", "Firefox: All files" — for the Shell card and the log. */
    val title: String get() = if (action == ShellAction.OWN_FILES) "$label: ${ownFiles?.label ?: arg} on its side" else "${action.label} $label"

    val ownFiles: OwnFiles? get() = arg?.let { a -> OwnFiles.entries.firstOrNull { it.name == a } }
}

/** The queue as it is saved: one file, written whole and renamed into place. */
@Serializable
data class QueueFile(val version: Int = 1, val actions: List<QueuedAction> = emptyList())

/**
 * Pure rules of the waiting-actions queue (D46), unit-tested: what adding an action does to what already waits, the
 * shell command for an action, and how its answer is read.
 */
object ActionQueue {
    fun opposite(a: ShellAction): ShellAction = when (a) {
        ShellAction.FREEZE -> ShellAction.UNFREEZE
        ShellAction.UNFREEZE -> ShellAction.FREEZE
        ShellAction.SUSPEND -> ShellAction.UNSUSPEND
        ShellAction.UNSUSPEND -> ShellAction.SUSPEND
        ShellAction.OWN_FILES -> ShellAction.OWN_FILES
    }

    /** What adding an action did, in plain words for the screen. */
    enum class Added(val words: String) {
        WAITING("waits for the shell"),
        REPLACED("already waited for the shell"),
        CANCELLED("cancelled the opposite action that was waiting: nothing to do"),
    }

    /**
     * Adds [action] for an app: the same action already waiting for that app and user is kept once; the opposite one
     * (freeze, then unfreeze) cancels both, since doing neither leaves the app as it is.
     */
    fun add(queue: List<QueuedAction>, action: QueuedAction): Pair<List<QueuedAction>, Added> {
        fun same(q: QueuedAction) = q.pkg == action.pkg && q.userId == action.userId
        // The app's own files: the newest choice replaces one still waiting.
        if (action.action == ShellAction.OWN_FILES) {
            val waiting = queue.any { same(it) && it.action == ShellAction.OWN_FILES }
            return queue.filterNot { same(it) && it.action == ShellAction.OWN_FILES } + action to (if (waiting) Added.REPLACED else Added.WAITING)
        }
        val oppositeWaiting = queue.firstOrNull { same(it) && it.action == opposite(action.action) }
        if (oppositeWaiting != null) return queue.filterNot { it.id == oppositeWaiting.id } to Added.CANCELLED
        val sameWaiting = queue.any { same(it) && it.action == action.action }
        if (sameWaiting) return queue to Added.REPLACED
        return queue + action to Added.WAITING
    }

    /** The shell command; [requested] is what the app asks for (its storage and media permissions, for OWN_FILES). */
    fun command(a: QueuedAction, requested: List<String> = emptyList()): List<String> = when (a.action) {
        ShellAction.FREEZE -> ShellCommands.disableUser(a.userId, a.pkg)
        ShellAction.UNFREEZE -> ShellCommands.enable(a.userId, a.pkg)
        ShellAction.SUSPEND -> ShellCommands.suspend(a.userId, a.pkg)
        ShellAction.UNSUSPEND -> ShellCommands.unsuspend(a.userId, a.pkg)
        ShellAction.OWN_FILES -> ShellCommands.ownFiles(a.userId, a.pkg, a.ownFiles ?: error("no files choice for ${a.pkg}"), requested)
    }

    /** Whether the shell's answer says the action took effect. */
    fun succeeded(a: QueuedAction, output: String): Boolean = when (a.action) {
        ShellAction.FREEZE -> ShellCommands.parseEnabledState(output, a.pkg) == "disabled-user"
        ShellAction.UNFREEZE -> ShellCommands.parseEnabledState(output, a.pkg) in setOf("enabled", "default")
        ShellAction.SUSPEND -> ShellCommands.parseSuspended(output, a.pkg) == true
        ShellAction.UNSUSPEND -> ShellCommands.parseSuspended(output, a.pkg) == false
        ShellAction.OWN_FILES -> ShellCommands.ownFilesDone(output)
    }

    /**
     * After a failed try the action stays, with one more attempt and the reason. After [MAX_ATTEMPTS] it is no longer
     * tried by itself, but it stays in the list (never dropped silently) until you retry or remove it.
     */
    fun failed(queue: List<QueuedAction>, a: QueuedAction, why: String): List<QueuedAction> =
        queue.map { q -> if (q.id != a.id) q else q.copy(attempts = q.attempts + 1, lastError = why) }

    fun givenUp(a: QueuedAction): Boolean = a.attempts >= MAX_ATTEMPTS

    /** The actions the shell should try now. */
    fun due(queue: List<QueuedAction>): List<QueuedAction> = queue.filterNot(::givenUp)

    const val MAX_ATTEMPTS = 3
}
