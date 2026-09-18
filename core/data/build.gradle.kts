plugins {
    id("notes.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "me.parham1995.notes.data"

    testOptions {
        unitTests {
            // Room and DataStore want a real Context and real resources;
            // Robolectric supplies both on the JVM, which keeps the suite
            // emulator-free.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    defaultConfig {
        // Room schemas are checked in so migrations can be tested without a device.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }
}

dependencies {
    api(projects.core.sync)
    api(projects.core.icons)
    api(projects.core.markdown)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Ships SQLite with the app instead of using the platform's. Gets FTS5
    // (Room only annotates FTS3/4), makes behaviour identical across ROMs, and
    // lets the search tests run on the JVM.
    implementation(libs.sqlite.bundled)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    // git-over-SSH.
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache)
    // sshd carries EdDSA support classes but no implementation; without this
    // Ed25519 is reported unsupported and key generation silently produces a
    // different algorithm.
    implementation(libs.eddsa)
    // Not optional: JGit and sshd call LoggerFactory on their own code paths,
    // so excluding slf4j makes them throw NoClassDefFoundError at runtime.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.sqlite.bundled.jvm)
    testRuntimeOnly(libs.slf4j.simple)
}
