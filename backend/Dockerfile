# syntax=docker/dockerfile:1

# ---- Build stage: compile & assemble a runnable distribution ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Gradle downloads live in this dir; a BuildKit cache mount persists them
# across builds so dependencies are fetched once, not on every rebuild.
ENV GRADLE_USER_HOME=/gradle-cache

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src

# The cache mount keeps ~/.gradle warm even when source changes invalidate
# this layer, so rebuilds only pay for recompilation, not re-downloading.
RUN --mount=type=cache,target=/gradle-cache \
    chmod +x gradlew && ./gradlew --no-daemon installDist

# ---- Runtime stage: Playwright image ships Chromium + all OS deps ----
FROM mcr.microsoft.com/playwright/java:v1.47.0-jammy
WORKDIR /app

# Browsers are pre-installed here in the Playwright image.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
ENV PORT=8080

COPY --from=build /app/build/install/result-guesser-backend/ ./

EXPOSE 8080
ENTRYPOINT ["./bin/result-guesser-backend"]
