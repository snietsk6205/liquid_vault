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
        assertEquals("allow", ShellCommands.parseAppOpDefault("No operations.\nDefault mode: allow\n"))
        assertEquals("default", ShellCommands.parseAppOpDefault("No operations.\r\nDefault mode: default"))
        assertNull(ShellCommands.parseAppOpDefault("READ_MEDIA_IMAGES: deny"))
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

    @Test
    fun recognisesTheShellBlockOnAUser() {
        // Verbatim from the phone test (0.1.0, check C2).
        val out = "Exception occurred while executing 'install-existing':\njava.lang.SecurityException: Shell does not have permission to access user 10\n com.android.server.am.ActivityManagerService.handleIncomingUser:15535"
        assertTrue(ShellCommands.shellBlockedFromUser(out))
        assertFalse(ShellCommands.shellBlockedFromUser("package:dev.liquid.vault"))
    }

    @Test
    fun readsUidAndPackageModes() {
        val both = "Uid mode: READ_EXTERNAL_STORAGE: ignore\nREAD_EXTERNAL_STORAGE: deny; time=+1m2s ago; duration=+3ms"
        val r = ShellCommands.parseAppOpRead(both, "READ_EXTERNAL_STORAGE")
        assertEquals("ignore", r.uidMode)
        assertEquals("deny", r.packageMode)
        // A uid mode that is not the op's default wins.
        assertEquals("ignore", r.effective("allow"))
        val uidDefault = ShellCommands.parseAppOpRead("Uid mode: READ_MEDIA_IMAGES: allow\nREAD_MEDIA_IMAGES: deny", "READ_MEDIA_IMAGES")
        assertEquals("deny", uidDefault.effective("allow"))
        val none = ShellCommands.parseAppOpRead("No operations.\nDefault mode: allow\n", "PICTURE_IN_PICTURE")
        assertNull(none.packageMode)
        assertEquals("allow", none.effective("allow"))
        // A line for another op whose name starts the same is not this op's.
        val other = ShellCommands.parseAppOpRead("READ_MEDIA_IMAGES_X: deny\nNo operations.\nDefault mode: allow", "READ_MEDIA_IMAGES")
        assertNull(other.packageMode)
    }

    @Test
    fun readsAUsersRestrictionsFromDumpsys() {
        val dump = """
            Current user: 0

            Users:
              UserInfo{0:Owner:4c13} serialNo=0 isPrimary=true
                Type: android.os.usertype.full.SYSTEM
                Restrictions:
                  none
                Device policy restrictions:
                  null
                Effective restrictions:
                  none
              UserInfo{10:Vault:1030} serialNo=10 isPrimary=false parentId=0
                Type: android.os.usertype.profile.MANAGED
                Has profile owner: true
                Restrictions:
                  no_wallpaper
                  no_debugging_features
                Device policy restrictions:
                  no_bluetooth_sharing
                Effective restrictions:
                  no_wallpaper
                  no_debugging_features
                  no_bluetooth_sharing
                Started profile: true

            Device properties:
              Device policy global restrictions:
                no_debugging_features
        """.trimIndent()
        val vault = ShellCommands.parseUserRestrictions(dump, 10)!!
        assertEquals(listOf("no_wallpaper", "no_debugging_features"), vault.base)
        assertEquals(listOf("no_bluetooth_sharing"), vault.devicePolicy)
        assertEquals(3, vault.effective.size)
        assertEquals(ShellCommands.RestrictionSource.PROFILE_ITSELF, ShellCommands.sourceOf(vault, ShellCommands.NO_DEBUGGING_FEATURES))
        val owner = ShellCommands.parseUserRestrictions(dump, 0)!!
        assertTrue(owner.base.isEmpty() && owner.devicePolicy.isEmpty() && owner.effective.isEmpty())
        assertEquals(ShellCommands.RestrictionSource.NOT_SET, ShellCommands.sourceOf(owner, ShellCommands.NO_DEBUGGING_FEATURES))
        assertNull(ShellCommands.parseUserRestrictions(dump, 11))
        // Android 12/13 split device policy into global and local lists.
        val old = "  UserInfo{11:Work:1030} serialNo=11\n    Restrictions:\n      no_wallpaper\n    Device policy global restrictions:\n      null\n    Device policy local restrictions:\n      no_debugging_features\n"
        val r = ShellCommands.parseUserRestrictions(old, 11)!!
        assertEquals(ShellCommands.RestrictionSource.DEVICE_POLICY, ShellCommands.sourceOf(r, ShellCommands.NO_DEBUGGING_FEATURES))
    }
}
