# Sift: one container = the search API + the website + the index data.
# Build (from the repo root, after building the data locally; see DESIGN.md "Data packaging"):
#   docker build -t sift .                              # for this machine
#   docker buildx build --platform linux/amd64 -t sift .  # for Cloud Run / Render (x86 servers)
# Run:
#   docker run -p 8080:8080 sift        then open http://localhost:8080

# ---- stage 1: build the website (React + Vite) into web/dist
FROM node:24-bookworm-slim AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- stage 2: build one runnable jar with the website inside (Spring Boot plugin: bootJar)
FROM eclipse-temurin:21-jdk-noble AS jar
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/wrapper/ gradle/wrapper/
COPY engine/build.gradle.kts engine/
COPY engine/src/main/ engine/src/main/
COPY api/build.gradle.kts api/
COPY api/src/main/ api/src/main/
COPY --from=web /src/web/dist/ web/dist/
# the cache mount keeps Gradle and its downloads between builds (not part of the image)
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :api:bootJar -x test

# ---- stage 3: the small runtime image: Java runtime (no compiler) + jar + data
FROM eclipse-temurin:21-jre-noble
RUN useradd --system --uid 10001 sift
WORKDIR /app
COPY --from=jar /src/api/build/libs/sift-api.jar app.jar
# data last-but-one: it is the biggest layer and changes least often
COPY data/index/ data/index/
COPY data/model-bge/ data/model-bge/
COPY data/vectors-bge60/ data/vectors-bge60/
USER sift
# Cloud Run and Render set PORT; the app reads it (server.port=${PORT:8080}).
ENV PORT=8080
EXPOSE 8080
# 512 MB heap is enough (measured 275 MB after GC); the index and vectors are memory-mapped outside the heap.
ENTRYPOINT ["java", "-Xmx512m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
