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
