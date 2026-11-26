package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CopyRulesTest {
    @Test
    fun aDownloadedAppsFilesAreAccepted() {
        // What Android 15 reports for an app installed from a store as a bundle (base + config splits).
        val bundle = listOf(
            "/data/app/~~Qx3pZ2==/com.chethan616.clearpdf-Hk1w8A==/base.apk",
            "/data/app/~~Qx3pZ2==/com.chethan616.clearpdf-Hk1w8A==/split_config.arm64_v8a.apk",
            "/data/app/~~Qx3pZ2==/com.chethan616.clearpdf-Hk1w8A==/split_config.xxhdpi.apk",
        )
        assertNull(CopyRules.apkPathsProblem(bundle))
        assertNull(CopyRules.apkPathsProblem(listOf("/system/priv-app/Gallery2/Gallery2.apk")))
    }

    @Test
    fun wrongFilesAreRefusedWithTheReason() {
        assertEquals("no APK file was named", CopyRules.apkPathsProblem(emptyList()))
        assertNotNull(CopyRules.apkPathsProblem(listOf("data/app/x/base.apk")))
        assertNotNull(CopyRules.apkPathsProblem(listOf("/data/app/x/base.apk", "/data/data/x/secret.db")))
        assertNotNull(CopyRules.apkPathsProblem(listOf("/data/app/../data/x/base.apk")))
        assertEquals("an APK file is named twice", CopyRules.apkPathsProblem(listOf("/data/app/x/base.apk", "/data/app/x/base.apk")))
    }
}
