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
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
