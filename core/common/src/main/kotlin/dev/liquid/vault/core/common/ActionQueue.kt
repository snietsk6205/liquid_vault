package dev.liquid.vault.core.common

import kotlinx.serialization.Serializable

/** What the shell does to a personal app for Liquid Vault (D45, D46). */
@Serializable
enum class ShellAction(val label: String, val done: String) {
    FREEZE("Freeze", "frozen"),
    UNFREEZE("Unfreeze", "unfrozen"),
    SUSPEND("Suspend", "suspended"),
    UNSUSPEND("Unsuspend", "unsuspended"),
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
) {
    /** "Freeze Firefox" — for the Shell card and the log. */
    val title: String get() = "${action.label} $label"
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
        val oppositeWaiting = queue.firstOrNull { same(it) && it.action == opposite(action.action) }
        if (oppositeWaiting != null) return queue.filterNot { it.id == oppositeWaiting.id } to Added.CANCELLED
        val sameWaiting = queue.any { same(it) && it.action == action.action }
        if (sameWaiting) return queue to Added.REPLACED
        return queue + action to Added.WAITING
    }

    fun command(a: QueuedAction): List<String> = when (a.action) {
        ShellAction.FREEZE -> ShellCommands.hide(a.userId, a.pkg)
        ShellAction.UNFREEZE -> ShellCommands.unhide(a.userId, a.pkg)
        ShellAction.SUSPEND -> ShellCommands.suspend(a.userId, a.pkg)
        ShellAction.UNSUSPEND -> ShellCommands.unsuspend(a.userId, a.pkg)
    }

    /** Whether the shell's answer says the action took effect. */
    fun succeeded(a: QueuedAction, output: String): Boolean = when (a.action) {
        ShellAction.FREEZE -> ShellCommands.parseHidden(output, a.pkg) == true
        ShellAction.UNFREEZE -> ShellCommands.parseHidden(output, a.pkg) == false
        ShellAction.SUSPEND -> ShellCommands.parseSuspended(output, a.pkg) == true
        ShellAction.UNSUSPEND -> ShellCommands.parseSuspended(output, a.pkg) == false
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
