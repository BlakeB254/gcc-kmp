plugins {
    // Version-less: the Kotlin plugin is already on the classpath via the KMP
    // modules (root declares kotlinMultiplatform apply false), so a JVM
    // subproject applies it without a version — same pattern as server/.
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.ktor)
    application
}

group = "com.gcc.backend"
version = "0.1.0"

application {
    mainClass.set("com.gcc.backend.ApplicationKt")
}

// Match the repo's JVM target (shared/ and composeApp/ compile to JVM 17) so
// this module consumes :shared cleanly and uses the ambient Gradle JDK rather
// than provisioning a separate toolchain.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // shared/ is the single source of DTOs — the backend parses R2 scan manifests
    // with the exact @Serializable models the mobile app writes.
    implementation(project(":shared"))

    // Ktor server (API + health check)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Postgres (Neon) via plain JDBC + Hikari — Drizzle remains the authoritative
    // schema owner; this module only reads/writes the scan-index tables.
    implementation(libs.postgresql)
    implementation(libs.hikari)

    // Cloudflare R2 (S3-compatible) via the AWS S3 SDK v2
    implementation(libs.awssdk.s3)
    implementation(libs.awssdk.apache.client)

    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.tests)
}

ktor {
    fatJar {
        archiveFileName.set("gcc-backend.jar")
    }
}
