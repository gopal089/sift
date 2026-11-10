package dev.sift.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Smoke test for the build itself: proves JUnit 5 runs and that the
 * Gradle toolchain really gives us Java 21 (not the newer JDK on the machine).
 */
class BuildSetupTest {

    @Test
    void testsRunOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
