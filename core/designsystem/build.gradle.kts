// The Liquid kit, copied from Liquid Launcher 0.16.0 (core/designsystem): WM-Keyboard-style settings UI, liquid glass,
// icons and theme. Package renamed, names kept, so later kit changes can be copied over as they are.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.liquid.vault.core.designsystem"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":core:common"))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.kyant.backdrop)
    implementation(libs.androidx.core.ktx)
}
