// Settings shared by every Java module (engine, api).
subprojects {
    apply(plugin = "java")

    group = "dev.sift"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    // Compile and test with Java 21, no matter which JDK runs Gradle itself.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.junit.jupiter:junit-jupiter-params")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        // Tests tagged "needs-data" read the real index, vectors and model from data/ (built locally, never in
        // git). CI runs `./gradlew test -Pci`, which leaves them out.
        val ci = providers.gradleProperty("ci").isPresent
        useJUnitPlatform {
            if (ci) {
                excludeTags("needs-data")
            }
        }
        systemProperty("sift.dataDir", rootProject.file("data").absolutePath)
        maxHeapSize = "1g"
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
