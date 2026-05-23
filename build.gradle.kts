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
//
// Why this exists:
//   kotlinx-datetime 0.7.x replaces the standalone Instant class with a
//   typealias to kotlin.time.Instant (which is annotated @ExperimentalTime),
//   breaking @Serializable on ~22 shared model files that use Instant fields
//   (96 usages across shared/src/commonMain/kotlin/com/gcc/shared/model/).
//
// What's actually pulling 0.7.x:
//   compottie:2.0.2  →  keight:0.0.04  →  kotlinx-datetime:0.7.1
//   No other dep in the graph wants 0.7.x. material3-desktop wants 0.6.0,
//   which is compatible with the pin.
//
// Why we don't just upgrade:
//   - Compottie 2.1.0 still pulls keight:0.0.04
//   - Compottie 2.2.0-beta01 pulls keight:0.0.05, which also still requests
//     kotlinx-datetime:0.7.1
//   - Until keight drops or downgrades its kotlinx-datetime requirement,
//     bumping Compottie buys nothing.
//
// When to revisit:
//   - keight publishes a release that drops kotlinx-datetime → remove this
//     block, drop the force(), upgrade Compottie if needed.
//   - We migrate model files to kotlin.time.Instant + @OptIn(ExperimentalTime)
//     (large refactor; only worth doing when another dep forces our hand).
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            force("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.1")
        }
    }
}
