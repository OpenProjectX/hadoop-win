plugins {
    id("kotlin-jvm")
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    implementation(libs.clouderaHadoopCommon)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
