import java.util.Properties

plugins {
    id("notes.android.application")
    id("notes.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing material, from a gitignored `keystore.properties` at the repo
 * root or, in CI, from the environment.
 *
 * When none is present the release build falls back to the debug key, which is
 * what keeps this repository buildable by anyone who clones it -- and is also
 * why the fallback must never be what ships. An app's identity is its signing
 * key: change it and the only way to update an installed copy is to uninstall
 * it, taking the synced vault, the token and the on-device SSH key with it.
 */
val keystoreProperties =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
            .asText
            .orNull
            ?.let { load(it.reader()) }
    }

fun signingValue(
    property: String,
    environment: String,
): String? = keystoreProperties.getProperty(property) ?: providers.environmentVariable(environment).orNull

val releaseKeystore: String? = signingValue("storeFile", "DAFTAR_KEYSTORE")

android {
    namespace = "me.parham1995.notes"

    defaultConfig {
        applicationId = "me.parham1995.notes"
        // Both are literals on purpose. F-Droid reads them out of this file
        // with a regex to decide an update is available, and a computed value
        // -- from the tag, from the commit count -- is invisible to it.
        // `just bump` keeps them in step; CI refuses a tag that disagrees.
        versionCode = 200
        versionName = "0.2.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = signingValue("storePassword", "DAFTAR_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DAFTAR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DAFTAR_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Only ever installed on a development device, and dropping the
            // other ABIs saves three quarters of the sqlite-bundled native
            // build on every incremental run.
            ndk { abiFilters += "arm64-v8a" }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A release carries every ABI: one artifact to publish, one to
            // verify, and it installs on whatever the person has. The native
            // side is 1.3MB per architecture, so universal is not the
            // expensive choice it usually is.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/INDEX.LIST",
            )
        // JGit ships OSGi bundle metadata in every artifact, so its two jars
        // collide on it. None of it means anything on Android.
        resources.pickFirsts +=
            setOf(
                "OSGI-INF/l10n/plugin.properties",
                "about.html",
                "plugin.properties",
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
    implementation(libs.highlights)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
