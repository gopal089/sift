// The search engine core. A "library": other modules (api) use its classes.
// The application plugin also builds a launcher script for the terminal search program (CLI).
plugins {
    `java-library`
    application
}

dependencies {
    // JSON parsing for reading articles.jsonl (plumbing, not a search algorithm)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

application {
    mainClass.set("dev.sift.engine.cli.SearchCli")
    applicationName = "sift-cli"
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

// Prints the folder of the Java 21 that Gradle uses (its "toolchain").
// scripts/sift-cli uses this so the CLI always runs on Java 21, even if `java` on PATH is newer.
val java21 = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
tasks.register("printJavaHome") {
    val home = java21.map { it.metadata.installationPath.asFile.absolutePath }
    doLast { println(home.get()) }
}
