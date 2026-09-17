plugins {
    id("notes.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "me.parham1995.notes.data"

    defaultConfig {
        // Room schemas are checked in so migrations can be tested without a device.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }
}

dependencies {
    api(projects.core.sync)
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
    // git-over-SSH. JGit drags in a lot that Android neither needs nor can
    // dex, so the transitive surface is trimmed hard.
    implementation(libs.jgit) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.jgit.ssh.apache) {
        exclude(group = "org.slf4j")
    }
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
