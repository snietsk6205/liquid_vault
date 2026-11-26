package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsAndQueueTest {
    private val personal = listOf(
        AppRecord("org.mozilla.firefox", "Firefox", Place.PERSONAL),
        AppRecord("deckers.thibault.aves", "Aves", Place.PERSONAL),
        AppRecord("app.komikku", "Komikku", Place.PERSONAL),
        AppRecord("com.example.zeta", "aves helper", Place.PERSONAL),
    )
    private val vault = listOf(
        AppRecord("deckers.thibault.aves", "Aves", Place.VAULT),
        AppRecord("com.hihonor.photos", "Gallery", Place.VAULT, system = true, frozen = true),
        AppRecord("app.komikku", "Komikku", Place.VAULT, suspended = true),
    )

    @Test
    fun rowsAreSortedByNameAndMarkTheOtherSide() {
        val rows = AppLists.rows(Place.PERSONAL, personal, vault)
        assertEquals(listOf("Aves", "aves helper", "Firefox", "Komikku"), rows.map { it.app.label })
        assertTrue(rows.first { it.app.pkg == "deckers.thibault.aves" }.onOtherSide)
        assertFalse(rows.first { it.app.pkg == "org.mozilla.firefox" }.onOtherSide)
        assertEquals("In the Vault too", rows.first { it.app.label == "Aves" }.status)
    }

    @Test
    fun searchMatchesNameOrPackage() {
        assertEquals(listOf("Aves", "aves helper"), AppLists.rows(Place.PERSONAL, personal, vault, "AVES").map { it.app.label })
        assertEquals(listOf("Firefox"), AppLists.rows(Place.PERSONAL, personal, vault, "mozilla").map { it.app.label })
        assertTrue(AppLists.rows(Place.PERSONAL, personal, vault, "nothing like it").isEmpty())
    }

    @Test
    fun vaultRowsSayWhatStateTheAppIsIn() {
        val rows = AppLists.rows(Place.VAULT, personal, vault).associateBy { it.app.pkg }
        assertEquals("Frozen · Built into the phone", rows.getValue("com.hihonor.photos").status)
        assertEquals("Suspended · Also on the personal side", rows.getValue("app.komikku").status)
    }

    private fun q(id: Long, action: ShellAction, pkg: String = "org.mozilla.firefox") =
        QueuedAction(id, action, pkg, "Firefox", 0, createdAt = id)

    @Test
    fun anOppositeActionCancelsTheWaitingOne() {
        val (one, added) = ActionQueue.add(emptyList(), q(1, ShellAction.FREEZE))
        assertEquals(ActionQueue.Added.WAITING, added)
        val (two, again) = ActionQueue.add(one, q(2, ShellAction.FREEZE))
        assertEquals(ActionQueue.Added.REPLACED, again)
        assertEquals(1, two.size)
        val (none, cancelled) = ActionQueue.add(two, q(3, ShellAction.UNFREEZE))
        assertEquals(ActionQueue.Added.CANCELLED, cancelled)
        assertTrue(none.isEmpty())
        // Another app, or another kind of action, waits alongside.
        val (both, _) = ActionQueue.add(one, q(4, ShellAction.SUSPEND))
        val (three, _) = ActionQueue.add(both, q(5, ShellAction.FREEZE, "app.komikku"))
        assertEquals(3, three.size)
    }

    @Test
    fun failedActionsAreKeptNotDropped() {
        var queue = listOf(q(1, ShellAction.SUSPEND))
        repeat(ActionQueue.MAX_ATTEMPTS) { queue = ActionQueue.failed(queue, queue.first(), "exit 1") }
        assertEquals(1, queue.size)
        assertTrue(ActionQueue.givenUp(queue.first()))
        assertTrue(ActionQueue.due(queue).isEmpty())
        assertEquals("exit 1", queue.first().lastError)
    }

    @Test
    fun actionsBecomeShellCommandsAndReadTheAnswer() {
        // The shell may not `pm hide` (MANAGE_USERS, CI run 15): a personal app is frozen by turning it off.
        val a = q(1, ShellAction.FREEZE)
        assertEquals(listOf("pm", "disable-user", "--user", "0", "org.mozilla.firefox"), ActionQueue.command(a))
        assertTrue(ActionQueue.succeeded(a, "Package org.mozilla.firefox new state: disabled-user"))
        assertFalse(ActionQueue.succeeded(a, "Package org.mozilla.firefox new state: enabled"))
        assertFalse(ActionQueue.succeeded(a, "Exception occurred while executing 'disable-user':\njava.lang.SecurityException: Cannot disable a protected package: org.mozilla.firefox"))
        val u = q(3, ShellAction.UNFREEZE)
        assertEquals(listOf("pm", "enable", "--user", "0", "org.mozilla.firefox"), ActionQueue.command(u))
        assertTrue(ActionQueue.succeeded(u, "Package org.mozilla.firefox new state: enabled"))
        assertTrue(ActionQueue.succeeded(u, "Package org.mozilla.firefox new state: default"))
        assertFalse(ActionQueue.succeeded(u, "Package org.mozilla.firefox new state: disabled-user"))
        val s = q(2, ShellAction.UNSUSPEND)
        assertEquals(listOf("pm", "unsuspend", "--user", "0", "org.mozilla.firefox"), ActionQueue.command(s))
        assertTrue(ActionQueue.succeeded(s, "Package org.mozilla.firefox new suspended state: false"))
        assertEquals("Unsuspend Firefox", s.title)
    }

    @Test
    fun appShellCommands() {
        assertEquals(listOf("pm", "uninstall", "--user", "10", "app.komikku"), ShellCommands.uninstallForUser(10, "app.komikku"))
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.uninstallForUser(0, "app.komikku") }
        assertTrue(ShellCommands.uninstallSucceeded("Success\n"))
        assertFalse(ShellCommands.uninstallSucceeded("Failure [DELETE_FAILED_INTERNAL_ERROR]"))
        assertNull(ShellCommands.parseEnabledState("Package other.app new state: disabled-user", "app.komikku"))
        assertEquals("disabled-until-used", ShellCommands.parseEnabledState("Package app.komikku new state: disabled-until-used", "app.komikku"))
        assertEquals(true, ShellCommands.parseSuspended("Package app.komikku new suspended state: true", "app.komikku"))
    }

    @Test
    fun aFailedShellAnswerKeepsTheReason() {
        // What the shell said in CI run 15 when it was asked to `pm hide` (trimmed stack trace).
        val run15 = """
            Exception occurred while executing 'hide':
            java.lang.SecurityException: Neither user 2000 nor current process has android.permission.MANAGE_USERS.
            	at android.app.ContextImpl.enforceCallingOrSelfPermission(ContextImpl.java:2280)
            	at com.android.server.pm.PackageManagerService${'$'}IPackageManagerImpl.setApplicationHiddenSettingAsUser(PackageManagerService.java:5915)
        """.trimIndent()
        assertEquals(
            "Exception occurred while executing 'hide': java.lang.SecurityException: Neither user 2000 nor current process has android.permission.MANAGE_USERS.",
            ShellCommands.errorSummary(run15),
        )
        assertEquals("Error: package app.x not found", ShellCommands.errorSummary("\nError: package app.x not found\n"))
        assertEquals("Failure [not installed for 10]", ShellCommands.errorSummary("Failure [not installed for 10]"))
        assertNull(ShellCommands.errorSummary("  \n "))
        assertTrue(ShellCommands.errorSummary("x".repeat(500))!!.length <= 240)
    }

    @Test
    fun theQueueFileRoundTrips() {
        val file = QueueFile(actions = listOf(q(1, ShellAction.FREEZE), q(2, ShellAction.SUSPEND).copy(lastError = "x", attempts = 1)))
        assertEquals(file, VaultJson.decodeFromString(QueueFile.serializer(), VaultJson.encodeToString(QueueFile.serializer(), file)))
    }
}
