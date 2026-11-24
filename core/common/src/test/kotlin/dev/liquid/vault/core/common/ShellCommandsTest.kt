package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandsTest {
    @Test
    fun vaultCreationCommandsAreTheManualSetupSteps() {
        assertEquals(listOf("pm", "create-user", "--profileOf", "0", "--managed", "Vault"), ShellCommands.createVault())
        assertEquals(listOf("pm", "install-existing", "--user", "11", "dev.liquid.vault"), ShellCommands.installExisting(11, "dev.liquid.vault"))
        assertEquals(
            listOf("dpm", "set-profile-owner", "--user", "11", "dev.liquid.vault/.admin.VaultAdminReceiver"),
            ShellCommands.setProfileOwner(11, "dev.liquid.vault/.admin.VaultAdminReceiver"),
        )
        assertEquals(listOf("am", "start-user", "-w", "11"), ShellCommands.startUser(11))
    }

    @Test
    fun badValuesNeverReachACommand() {
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.installExisting(11, "dev.liquid.vault; reboot") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.installExisting(-1, "dev.liquid.vault") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.removeUser(0) }
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.appOpsSet(11, "a.b", "MANAGE_EXTERNAL_STORAGE", "always") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.appOpsSet(11, "a.b", "rm -rf", "deny") }
        assertThrows(IllegalArgumentException::class.java) { ShellCommands.setProfileOwner(11, "dev.liquid.vault/.X && reboot") }
    }

    @Test
    fun renderQuotesOnlyWhatNeedsIt() {
        assertEquals("dpm set-profile-owner --user 11 --name 'Liquid Vault' a.b/.C", ShellCommands.render(listOf("dpm", "set-profile-owner", "--user", "11", "--name", "Liquid Vault", "a.b/.C")))
        assertEquals("echo 'it'\\''s'", ShellCommands.render(listOf("echo", "it's")))
    }

    @Test
    fun parsesCreatedUserId() {
        assertEquals(11, ShellCommands.parseCreatedUserId("Success: created user id 11\n"))
        assertNull(ShellCommands.parseCreatedUserId("Error: couldn't create User."))
    }

    @Test
    fun parsesUserList() {
        val out = """
            Users:
            	UserInfo{0:Owner:c13} running
            	UserInfo{11:Insular:1030} running
            	UserInfo{12:Vault:10b0}
        """.trimIndent()
        val users = ShellCommands.parseUsers(out)
        assertEquals(3, users.size)
        assertEquals(0, users[0].id)
        assertFalse(users[0].managedProfile)
        assertEquals("Insular", users[1].name)
        assertTrue(users[1].managedProfile)
        assertTrue(users[1].running)
        assertFalse(users[1].quietMode)
        assertTrue(users[2].quietMode)
        assertFalse(users[2].running)
    }

    @Test
    fun parsesAppOpModes() {
        assertEquals("deny", ShellCommands.parseAppOpMode("MANAGE_EXTERNAL_STORAGE: deny; time=+5m3s ago", "MANAGE_EXTERNAL_STORAGE"))
        assertEquals("allow", ShellCommands.parseAppOpMode("Uid mode: x\nREAD_MEDIA_IMAGES: allow\n", "READ_MEDIA_IMAGES"))
        assertNull(ShellCommands.parseAppOpMode("No operations.", "READ_MEDIA_IMAGES"))
    }

    @Test
    fun readsCommandResults() {
        assertTrue(ShellCommands.installExistingSucceeded("Package com.x.y installed for user: 11", "com.x.y"))
        assertFalse(ShellCommands.installExistingSucceeded("Failure [not installed for 0]", "com.x.y"))
        assertTrue(ShellCommands.setProfileOwnerSucceeded("Success: Active admin and profile owner set to ComponentInfo{a/b} for user 11"))
        assertEquals(2000, ShellCommands.parseUid("uid=2000(shell) gid=2000(shell) groups=1004(input)"))
    }

    @Test
    fun packageNames() {
        assertTrue(ShellCommands.isPackageName("dev.liquid.vault"))
        assertTrue(ShellCommands.isPackageName("com.google.android.documentsui"))
        assertFalse(ShellCommands.isPackageName("vault"))
        assertFalse(ShellCommands.isPackageName("a.b c"))
    }
}
