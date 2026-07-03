#!/bin/zsh
# Refresh everything the Xcode SIMULATOR build consumes from Gradle, then
# build/run the app via xcodebuild (or the xcodebuild MCP).
#
# Why this exists (2026-07-03 debugging): the Xcode project does NOT invoke
# Gradle — it copies a PREBUILT framework from
#   composeApp/build/xcode-frameworks/$CONFIGURATION/$SDK_NAME
# and compose resources from
#   composeApp/build/kotlin-multiplatform-resources/aggregated-resources/
# Both go stale silently: the app builds "successfully" and runs OLD Kotlin
# code, or crashes with MissingResourceException for newly added drawables.
# The aggregate task's up-to-date check is also unreliable (kept a stale dir
# while reporting success), hence the purge + --rerun-tasks.
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf composeApp/build/kotlin-multiplatform-resources/aggregated-resources/iosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --console=plain
./gradlew :composeApp:iosSimulatorArm64AggregateResources --rerun-tasks --console=plain

SDK_DIR=$(ls composeApp/build/xcode-frameworks/Debug/ 2>/dev/null | head -1)
SDK_DIR=${SDK_DIR:-iphonesimulator26.5}
mkdir -p "composeApp/build/xcode-frameworks/Debug/$SDK_DIR"
rm -rf "composeApp/build/xcode-frameworks/Debug/$SDK_DIR/ComposeApp.framework"
cp -R composeApp/build/bin/iosSimulatorArm64/debugFramework/ComposeApp.framework \
      "composeApp/build/xcode-frameworks/Debug/$SDK_DIR/"
echo "simulator framework + resources refreshed"
