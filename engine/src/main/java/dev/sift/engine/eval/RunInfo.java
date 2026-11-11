package dev.sift.engine.eval;

import java.util.Locale;

/** One header line for results files: which commit, machine and Java produced the numbers. */
public final class RunInfo {

    private RunInfo() {
    }

    public static String header() {
        return String.format(Locale.US, "# commit %s | %s | Java %s%n",
                System.getenv().getOrDefault("SIFT_COMMIT", "?"),
                System.getenv().getOrDefault("SIFT_MACHINE", "?"), Runtime.version());
    }
}
