plugins {
    // No version: AGP is already on the classpath through build-logic.
    id("com.android.test")
    alias(libs.plugins.baselineprofile)
}

/**
 * Records the app's baseline profile on a connected phone.
 *
 * A test module rather than part of the app: the recorder drives the app
 * from outside, through UI Automator, and writes what the runtime compiled
 * on the way. The result is committed under
 * `app/src/main/generated/baselineProfiles/`,
 * so CI never needs a device -- it only needs the file, which R8 and ART
 * then use to compile the hot paths ahead of time on install.
 *
 * `just baseline-profile` runs it. A phone, not an emulator: this project
 * runs none, and the profile should come from the hardware it is for.
 */
android {
    namespace = "me.parham1995.notes.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
