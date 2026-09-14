import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Load signing config from properties file (if it exists)
val signingPropsFile = file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}
val disableNativeReleaseOptimization = providers.gradleProperty("gcc.disableNativeReleaseOptimization")
    .map { it.toBoolean() }
    .orElse(false)

kotlin {
    jvm("desktop")
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            if (disableNativeReleaseOptimization.get() && buildType == NativeBuildType.RELEASE) {
                optimized = false
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.animation)

            // Architecture
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // GPS — API only in commonMain (mobile impl in platform source sets)
            implementation(libs.compass.geolocation)

            // Settings (persistent key-value storage)
            implementation(libs.multiplatform.settings)

            // Animation
            implementation(libs.compottie)
            implementation(libs.compottie.resources)

            // Image Loading
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            // SLF4J binding → logcat. Without it SLF4J is a no-op on Android
            // ("No SLF4J providers were found" on first launch) and everything
            // Ktor logs is discarded. Config: src/androidMain/assets/logback.xml
            implementation(libs.logback.android)
            // Screenshot capture for sharing (Android-only AAR — no desktop/iOS variant)
            implementation(libs.capturable)
            // CameraX (Android camera hardware access)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            // MapLibre + Compass mobile implementation (Android)
            implementation(libs.maplibre.compose)
            implementation(libs.compass.geolocation.mobile)
            // Encrypted storage for secure token persistence
            implementation(libs.androidx.security.crypto)
            // Biometric authentication (fingerprint / face)
            implementation(libs.androidx.biometric)
            // SQLDelight driver (route cache)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            // MapLibre + Compass mobile implementation (iOS)
            implementation(libs.maplibre.compose)
            implementation(libs.compass.geolocation.mobile)
            // SQLDelight driver (route cache)
            implementation(libs.sqldelight.native.driver)
        }
        val desktopMain by getting
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Desktop uses canvas map fallback — no MapLibre/Compass needed
            // SQLDelight driver (route cache)
            implementation(libs.sqldelight.sqlite.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.gcc.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.globalcommunitycleaners.worker"
        minSdk = 26
        targetSdk = 35
        // Tracks the iOS release line: versionName matches
        // CFBundleShortVersionString, versionCode matches CFBundleVersion
        // at the time of each Android cut (Android had been parked at 2/1.0.1
        // since before the KMP consolidation).
        //
        // Enforced by scripts/check-version-parity.sh in CI. It is a gate
        // because this was only a comment when PR #280 bumped iOS to 189 and
        // left Android on 188. Bump both platforms in the same PR.
        versionCode = 189
        versionName = "1.1.0"
    }
    if (signingProps.containsKey("KEYSTORE_PATH")) {
        signingConfigs {
            create("release") {
                storeFile = file(signingProps.getProperty("KEYSTORE_PATH"))
                storePassword = signingProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.gcc.app.MainKt"
    }
}
