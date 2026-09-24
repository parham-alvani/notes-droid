plugins {
    id("notes.jvm.library")
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.task.list)
    implementation(libs.commonmark.front.matter)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.footnotes)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
