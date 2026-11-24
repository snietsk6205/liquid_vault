plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from GitHub secrets (DECISIONS.md, D4): CI decodes SIGNING_KEYSTORE_BASE64 into a file and
// passes its path with the three passwords/alias. Without them a release build is signed with the debug key, its APK
// is named "test-key", and CI refuses to publish it. No key is ever committed (this repository is public).
val releaseKeystore = providers.environmentVariable("SIGNING_KEYSTORE_PATH").orNull
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "dev.liquid.vault"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.liquid.vault"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI passes the run number so every build installs over the previous one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("app.versionName").get()
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        // The app deliberately targets the phone's own API level (DECISIONS.md, D2) and asks for permissions only adb
        // or the system can grant (MODIFY_QUIET_MODE, which Med grants with adb).
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "ProtectedPermissions")
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
