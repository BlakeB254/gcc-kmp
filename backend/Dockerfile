# ---- build stage: compile the fat jar with Gradle ----
# Build from the REPO ROOT as context (needs :shared + gradle wrapper + catalog):
#   docker build -f backend/Dockerfile -t gcc-backend .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

# Warm the Gradle cache on dependency metadata first for faster rebuilds.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Source needed to assemble :backend (which depends on :shared).
COPY shared ./shared
COPY backend ./backend

RUN ./gradlew --no-daemon :backend:buildFatJar

# ---- runtime stage: slim JRE ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /src/backend/build/libs/gcc-backend.jar app.jar

# Container-aware heap. Leave headroom: the AWS SDK + Netty use off-heap/native
# memory, so cap heap well under the instance limit rather than -Xmx.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC"
ENV PORT=8080
EXPOSE 8080

# Graceful shutdown: the JVM receives SIGTERM; Ktor drains and the stop hook
# closes the pool + R2 client.
ENTRYPOINT ["java", "-jar", "app.jar"]
