// Lets Gradle download the right JDK (Java 21) automatically if it is not installed.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "sift"

include("engine") // search algorithms: tokenizer, index, BM25, PageRank, trie, HNSW
include("api")    // HTTP server (Spring Boot from Phase 2) that calls the engine
