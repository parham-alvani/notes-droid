plugins {
    id("notes.android.application")
    id("notes.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.parham1995.notes"

    defaultConfig {
        applicationId = "me.parham1995.notes"
        versionCode = 1
        versionName = "0.1.0"

        // One target device. Dropping the other ABIs saves both APK size and
        // three quarters of the sqlite-bundled native build.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so the public repo carries no keystore. Personal
            // sideloading only.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.sync)
    implementation(projects.core.markdown)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.jlatexmath)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
