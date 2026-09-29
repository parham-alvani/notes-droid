plugins {
    id("notes.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Rebuilds the bundled dictionary from an Open English WordNet release in
// WNDB form. Run by `just dictionary`, which fetches and checks the release;
// the output is committed, so an ordinary build never touches the network.
tasks.register<JavaExec>("importWordNet") {
    description = "Converts a WordNet database directory into the app's dictionary assets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("me.parham1995.notes.dictionary.WordNetImport")
    // Read when the task runs, so configuring the build does not need the property.
    val source = providers.gradleProperty("wordnet")
    val out = rootProject.file("app/src/main/assets/dictionary").path
    argumentProviders.add(CommandLineArgumentProvider { listOf(source.get(), out) })
}
