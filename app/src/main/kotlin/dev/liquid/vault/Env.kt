package dev.liquid.vault

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import dev.liquid.vault.admin.VaultAdminReceiver

/** Which copy of the app this is (DECISIONS.md, D7). */
enum class AppSide(val label: String, val id: String) {
    PERSONAL("Personal side", "personal"),
    VAULT("Vault side", "vault"),

    /** Installed in a work profile that another app owns (e.g. Insular's): this copy does nothing there. */
    FOREIGN_PROFILE("A work profile owned by another app", "foreign"),
}

/** The user id of a [UserHandle]. `getIdentifier()` is not public; `hashCode()` returns the same number (as Insular). */
val UserHandle.id: Int get() = hashCode()

/** System services and facts every part of the app needs, without passing a Context around. */
object Env {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    val dpm: DevicePolicyManager get() = app.getSystemService(DevicePolicyManager::class.java)
    val um: UserManager get() = app.getSystemService(UserManager::class.java)
    val crossProfile: CrossProfileApps get() = app.getSystemService(CrossProfileApps::class.java)

    val admin: ComponentName get() = ComponentName(app, VaultAdminReceiver::class.java)

    /** `dev.liquid.vault/.admin.VaultAdminReceiver`, as `dpm set-profile-owner` takes it. */
    val adminShort: String get() = admin.flattenToShortString()

    val myUser: UserHandle get() = Process.myUserHandle()
    val myUserId: Int get() = myUser.id

    fun side(): AppSide = when {
        runCatching { dpm.isProfileOwnerApp(app.packageName) }.getOrDefault(false) -> AppSide.VAULT
        runCatching { um.isManagedProfile }.getOrDefault(false) -> AppSide.FOREIGN_PROFILE
        else -> AppSide.PERSONAL
    }

    val isVaultSide: Boolean get() = side() == AppSide.VAULT

    /** The other profiles of this profile group (from the personal side: the work profile, if there is one). */
    fun otherProfiles(): List<UserHandle> = runCatching { um.userProfiles }.getOrDefault(emptyList()).filter { it != myUser }

    /** Profiles where this app is installed too (Android's own answer, no permission needed). */
    fun profilesWithThisApp(): List<UserHandle> = runCatching { crossProfile.targetUserProfiles }.getOrDefault(emptyList())

    /** From the personal side: the Vault's user (a profile where this app is installed), else any other profile. */
    fun vaultUser(): UserHandle? {
        if (isVaultSide) return myUser
        val withApp = profilesWithThisApp()
        val others = otherProfiles()
        return others.firstOrNull { it in withApp } ?: others.firstOrNull()
    }

    /** From the Vault side: the personal user (the parent). */
    fun personalUser(): UserHandle? = if (isVaultSide) otherProfiles().firstOrNull() else myUser

    fun versionName(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    @Suppress("DEPRECATION")
    fun versionCode(): Long = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)

    /** A system property, read the way Android's own code does (a greylisted hidden API; null when blocked). */
    fun systemProperty(name: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.ifEmpty { null }

    fun deviceLine(): String = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}"

    /** MagicOS writes its version in one of these properties, depending on the release. */
    fun magicOsVersion(): String? = listOf("ro.build.version.magic", "ro.magic.api.version", "ro.build.version.emui", "ro.honor.build.display.id")
        .firstNotNullOfOrNull { key -> systemProperty(key)?.let { "$key=$it" } }
}
