package dev.liquid.vault.core.common

import dev.liquid.vault.core.common.StorageOps.Kind
import dev.liquid.vault.core.common.StorageOps.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageOpsTest {
    @Test
    fun defaultModes() {
        assertEquals("allow", StorageOps.defaultMode("READ_MEDIA_IMAGES"))
        assertEquals("allow", StorageOps.defaultMode("android:read_external_storage"))
        assertEquals("default", StorageOps.defaultMode("MANAGE_EXTERNAL_STORAGE"))
        assertEquals("default", StorageOps.defaultMode("android:manage_external_storage"))
        assertEquals("allow", StorageOps.defaultMode(StorageOps.CONTROL))
    }

    @Test
    fun namesAndPermissions() {
        assertEquals("android:read_media_images", StorageOps.opString("READ_MEDIA_IMAGES"))
        assertEquals("READ_MEDIA_IMAGES", StorageOps.shellName("android:read_media_images"))
        assertEquals("android.permission.MANAGE_EXTERNAL_STORAGE", StorageOps.permissionOf("android:manage_external_storage"))
        assertNull(StorageOps.permissionOf(StorageOps.CONTROL))
    }

    @Test
    fun savedModesRoundTrip() {
        val modes = linkedMapOf("READ_MEDIA_IMAGES" to "deny", "MANAGE_EXTERNAL_STORAGE" to StorageOps.NONE)
        assertEquals(modes, StorageOps.decode(StorageOps.encode(modes)))
        assertNull(StorageOps.decode(null))
        assertNull(StorageOps.decode(""))
        assertNull(StorageOps.decode("READ_MEDIA_IMAGES"))
        assertNull(StorageOps.decode("READ_MEDIA_IMAGES=,X=allow"))
    }

    // Chrome on the phone (0.1.0): asks for READ/WRITE_EXTERNAL_STORAGE only.
    private val chrome = listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.INTERNET")

    @Test
    fun whatAndroidDoesWithEachOp() {
        assertEquals(Kind.FOLLOWS_PERMISSION, StorageOps.kind("READ_EXTERNAL_STORAGE", chrome))
        assertEquals(Kind.FREE, StorageOps.kind("android:read_media_images", chrome))
        assertEquals(Kind.ALL_FILES_NOT_ASKED, StorageOps.kind("MANAGE_EXTERNAL_STORAGE", chrome))
        assertEquals(Kind.ALL_FILES_ASKED, StorageOps.kind("MANAGE_EXTERNAL_STORAGE", chrome + "android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertEquals(Kind.CONTROL, StorageOps.kind(StorageOps.CONTROL, chrome))
    }

    private fun r(op: String, kind: Kind, later: String, target: String = "deny") = Reading(op, kind, "allow", target, later, later)

    @Test
    fun theOnesThatFollowAPermissionNeverCount() {
        // What the phone showed for Chrome (F4): READ/WRITE went back, as Android designs it. With the test op staying
        // denied, the owner's app-ops do reach the app.
        val v = StorageOps.verdict(
            listOf(
                r("READ_EXTERNAL_STORAGE", Kind.FOLLOWS_PERMISSION, "allow"),
                r("WRITE_EXTERNAL_STORAGE", Kind.FOLLOWS_PERMISSION, "allow"),
                r("READ_MEDIA_IMAGES", Kind.FREE, "deny"),
                r("MANAGE_EXTERNAL_STORAGE", Kind.ALL_FILES_NOT_ASKED, "default"),
                r(StorageOps.CONTROL, Kind.CONTROL, "deny"),
            ),
            "the owner", "Chrome", deny = true,
        )
        assertEquals(CheckStatus.PASS, v.status)
        assertTrue(v.summary, v.summary.contains("READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE went back"))
        assertTrue(v.summary, v.summary.contains("All files: Chrome does not ask for it"))
    }

    @Test
    fun nothingStayingMeansTheChangeNeverReachedTheApp() {
        val v = StorageOps.verdict(
            listOf(r("READ_MEDIA_IMAGES", Kind.FREE, "allow"), r(StorageOps.CONTROL, Kind.CONTROL, "allow")),
            "the shell", "Chrome", deny = true,
        )
        assertEquals(CheckStatus.FAIL, v.status)
        assertTrue(v.summary, v.summary.contains("not even the test op"))
    }

    @Test
    fun aPartlyChangedAppIsAFail() {
        val v = StorageOps.verdict(
            listOf(r("READ_MEDIA_IMAGES", Kind.FREE, "allow"), r(StorageOps.CONTROL, Kind.CONTROL, "deny")),
            "the shell", "Chrome", deny = true,
        )
        assertEquals(CheckStatus.FAIL, v.status)
        assertTrue(v.summary, v.summary.startsWith("READ_MEDIA_IMAGES did not stay denied"))
    }

    @Test
    fun anAppWhereAppOpsControlNothingIsInfo() {
        val v = StorageOps.verdict(
            listOf(r("READ_EXTERNAL_STORAGE", Kind.FOLLOWS_PERMISSION, "allow", target = "allow")),
            "the owner", "Gallery", deny = false,
        )
        assertEquals(CheckStatus.INFO, v.status)
    }

    @Test
    fun appsThatCannotShowThePolicy() {
        val chromeTarget = 35
        assertNotNull(StorageOps.policyTestUnsuitable(chromeTarget, chrome))
        assertNull(StorageOps.policyTestUnsuitable(chromeTarget, chrome + "android.permission.READ_MEDIA_IMAGES"))
        // An app built for Android 12 or older still reads photos with READ_EXTERNAL_STORAGE.
        assertNull(StorageOps.policyTestUnsuitable(30, chrome))
    }
}
