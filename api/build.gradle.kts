// The web API: a Spring Boot server that answers /search, /suggest, /stats and /health using the engine.
// The application plugin builds a launcher script (api/build/install/sift-api/bin/sift-api).
plugins {
    application
}

dependencies {
    implementation(project(":engine"))

    // Spring's "bill of materials": one list that fixes matching versions of all Spring libraries.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.6"))
    implementation("org.springframework.boot:spring-boot-starter-web")   // embedded Tomcat + JSON (Jackson)
    implementation("com.github.ben-manes.caffeine:caffeine")              // in-memory cache (version from Spring's list)

    testImplementation("org.springframework.boot:spring-boot-starter-test")   // MockMvc: call endpoints in tests
}

application {
    mainClass.set("dev.sift.api.SiftApplication")
    applicationName = "sift-api"
    // The index is memory-mapped, not in the Java heap, so a small heap is enough.
    applicationDefaultJvmArgs = listOf("-Xmx512m")
}
