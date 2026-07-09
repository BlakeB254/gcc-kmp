# iosApp — Xcode wrapper + Swift native modules

The Xcode project that packages the `composeApp` Kotlin/Native framework into an iOS app, plus the **Swift-only native modules** that can't live in Kotlin (RealityKit has no ObjC headers, so it's unreachable from cinterop).

## Key files
- `iosApp/GccArNative.swift` — the RealityKit AR surface (`RealityKitArSurface`) implementing Kotlin's `ArNativeSurface` protocol. Registered from `AppDelegate`. Kotlin ships AR-local primitives; this renders entities. Read its KDoc for K/N interop gotchas (iOS-18-only APIs guarded for the 17.2 target, boxed KotlinInt/Double, cinterop pointer types).
- `iosApp/CrashDiagnostics.swift` — MetricKit crash capture.
- `iosApp/Info.plist` — `CFBundleVersion` (build number) + `CFBundleShortVersionString` (marketing version, currently 1.0.7).
- `fastlane/` — build/upload lanes (see `iosApp/fastlane/`).

## Build & TestFlight (on the MacBook)
- Sim smoke: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` then xcodebuild on `iosApp.xcodeproj` scheme `iosApp` (validates Swift + framework link without distribution signing).
- Release/TestFlight: `cd iosApp && fastlane ios release` — archives, uploads to internal TestFlight. Needs the ASC key at `~/.appstoreconnect/private_keys/AuthKey_XKAYZ4N88V.p8` (the real key may live at `~/private_keys/…` — copy it to the expected path). **Bump `Info.plist` `CFBundleVersion` above the latest in ASC first** — query `latest_testflight_build_number`; don't assume the plist number was already uploaded. The lane mutates `project.pbxproj` (manual signing) — revert after.

## Gotchas
- New Swift files need manual `project.pbxproj` entries (clone `CrashDiagnostics.swift`'s 4 lines with fresh UUIDs).
- SourceKit "No such module 'UIKit'" on Swift files = IDE indexing noise; `xcodebuild` is the truth.
- Never run a standalone `./gradlew` gate concurrently with `fastlane` (both spawn gradle → daemon race kills the build).
