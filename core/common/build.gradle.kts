// Plain logic with no Android classes where possible, so it tests on the JVM in seconds: the check model and report,
// shell command builders and output parsers, bridge rule decisions, the debug log.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.liquid.vault.core.common"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
