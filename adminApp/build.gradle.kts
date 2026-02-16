plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation(compose.animation)

    // Architecture
    implementation(libs.decompose)
    implementation(libs.decompose.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    // KotlinX
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
}

compose.desktop {
    application {
        mainClass = "com.gcc.admin.MainKt"
    }
}
