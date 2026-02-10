import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    // Shared models + API client
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
}

compose.desktop {
    application {
        mainClass = "com.gcc.admin.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "GCC Admin"
            packageVersion = "1.0.0"
            description = "Global Community Cleaners - Admin Dashboard"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}
