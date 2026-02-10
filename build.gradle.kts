plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
}

// Force kotlinx-datetime to 0.6.1 across all modules.
// Compottie 2.0.2 transitively pulls in 0.7.1, but 0.7.x replaces the
// Instant class with a typealias to kotlin.time.Instant (ExperimentalTime),
// breaking @Serializable on 20+ model classes that use Instant fields.
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            force("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.1")
        }
    }
}
