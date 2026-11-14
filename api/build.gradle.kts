// The web API: a Spring Boot server that answers /search, /suggest, /stats and /health using the engine,
// and serves the website (web/dist) from the same address.
// - The Spring Boot plugin builds ONE runnable jar with every library inside (bootJar →
//   api/build/libs/sift-api.jar): the Docker image only needs a Java runtime and `java -jar`.
// - The application plugin still builds the local launcher script (api/build/install/sift-api/bin/sift-api).
plugins {
    application
    id("org.springframework.boot") version "3.5.6"
}

dependencies {
    implementation(project(":engine"))

    // Spring's "bill of materials": one list that fixes matching versions of all Spring libraries.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.6"))
    implementation("org.springframework.boot:spring-boot-starter-web")   // embedded Tomcat + JSON (Jackson)
    implementation("com.github.ben-manes.caffeine:caffeine")              // in-memory cache (version from Spring's list)

    testImplementation("org.springframework.boot:spring-boot-starter-test")   // MockMvc: call endpoints in tests
}

// If the website has been built (cd web && npm run build), put it inside the jar as static files:
// Spring Boot then serves web/dist/index.html at "/" and the files under /assets/.
tasks.processResources {
    from(rootProject.file("web/dist")) {
        into("static")
    }
}

tasks.bootJar {
    archiveFileName.set("sift-api.jar")
}

application {
    mainClass.set("dev.sift.api.SiftApplication")
    applicationName = "sift-api"
    // The index is memory-mapped, not in the Java heap, so a small heap is enough.
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}
