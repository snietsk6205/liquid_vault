package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageOpsTest {
    @Test
    fun defaultModes() {
        assertEquals("allow", StorageOps.defaultMode("READ_MEDIA_IMAGES"))
        assertEquals("allow", StorageOps.defaultMode("android:read_external_storage"))
        assertEquals("default", StorageOps.defaultMode("MANAGE_EXTERNAL_STORAGE"))
        assertEquals("default", StorageOps.defaultMode("android:manage_external_storage"))
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
}
