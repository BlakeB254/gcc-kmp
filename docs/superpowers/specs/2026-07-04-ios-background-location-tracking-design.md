# iOS Background Location Tracking — Design Spec

**Date:** 2026-07-04
**Status:** Awaiting review
**Scope:** Wire up iOS background location tracking during active jobs — currently dead code, discovered during a foundational-tech audit of the mobile app. A worker backgrounding the app mid-job today loses location tracking entirely; the code to prevent this already exists but is never invoked.

**Explicitly iOS-only.** No changes to Android (already has its own correct, different mechanism — a foreground `Service`) or Desktop (no-op, untouched). No changes to the shared `expect class LocationTrackingController()` contract.

**Cannot be verified on this machine.** All affected code is `iosMain`-only Kotlin plus one native `Info.plist` change — this DGX cannot compile or run any of it (Kotlin/Native iOS compilation is disabled on this host entirely, not just untested). Verification requires the MacBook toolchain.

---

## 1. Background

`LocationProvider` (iOS, `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationProvider.kt:169-187`) has a fully correct `startBackgroundTracking()`/`stopBackgroundTracking()` pair: requests `CLLocationManager.requestAlwaysAuthorization()`, sets `allowsBackgroundLocationUpdates = true`, disables auto-pause, and starts `startMonitoringSignificantLocationChanges()` as a fallback for when the OS suspends the app. This is real, working, already-written code.

It's dead. `LocationTrackingController` (iOS, `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationTrackingController.kt`) — the actual class `ActiveJobViewModel` calls (`.startTracking()`/`.stopTracking()` at `ActiveJobViewModel.kt:104,132`) — is a pair of literal no-ops, with a comment incorrectly claiming "iOS background location is handled by CLLocationManager... No separate service management needed." It doesn't call `LocationProvider` at all.

Compounding this: `iosApp/iosApp/Info.plist` already declares `NSLocationAlwaysAndWhenInUseUsageDescription` (the user-facing permission-prompt string for "Always" access) but `UIBackgroundModes` only contains `remote-notification` — missing `location`. Even if the dead code were wired up as-is, `allowsBackgroundLocationUpdates = true` would throw at runtime without this entitlement declared.

The existing `NSLocationAlwaysAndWhenInUseUsageDescription` string strongly suggests "Always" background tracking during active jobs was the original intended design — this spec finishes connecting what was already built for it, rather than designing something new.

## 2. Design

**`LocationTrackingController.kt` (iOS):** replace the no-op body. Needs a reference to the same `LocationProvider` Koin singleton already used elsewhere in the app (constructing a fresh `LocationProvider()` instead would create a disconnected `CLLocationManager` with none of the location-update state actually in use). Rather than changing the shared `expect class LocationTrackingController()` constructor (which would force Android's and Desktop's actuals to accept/ignore a parameter they don't need — Android has its own independent mechanism via `LocationTrackingService`), follow the exact static-companion-init pattern this codebase already uses for the same class of problem (Android's own `LocationTrackingController.initContext(context)`, and the recent `PermissionManager.initActivity(activity)` fix):

```kotlin
actual class LocationTrackingController {
    actual fun startTracking() { sharedLocationProvider?.startBackgroundTracking() }
    actual fun stopTracking() { sharedLocationProvider?.stopBackgroundTracking() }

    companion object {
        private var sharedLocationProvider: LocationProvider? = null
        fun initLocationProvider(provider: LocationProvider) { sharedLocationProvider = provider }
    }
}
```

**`MainViewController.kt` (iOS):** call `LocationTrackingController.initLocationProvider(...)` once, right next to the existing `val tokenManager = KoinPlatform.getKoin().get<TokenManager>()` line (`MainViewController.kt:41`) — same Koin-resolution pattern, same place in the startup sequence, before the composable scope begins.

**`Info.plist`:** add `location` to the `UIBackgroundModes` array (currently `["remote-notification"]`, becomes `["remote-notification", "location"]`).

## 3. Testing / verification plan

No automated test is possible for this change — it's platform lifecycle/entitlement wiring, not testable business logic, and this host cannot compile iOS Kotlin/Native code at all (confirmed during earlier audit work: `kotlin.native.ignoreDisabledTargets=true` makes even a "cross-compile sanity check" a no-op `SKIPPED` task on this machine, not real verification). Verification requires: a real build on the MacBook, confirming (a) the app doesn't crash when `startTracking()` fires (the most likely failure mode if the `Info.plist` entry is missing or malformed), (b) the "Always" location permission prompt appears correctly, and (c) location updates genuinely continue to arrive with the app backgrounded during an active job — none of which can be confirmed from this machine.

## 4. Out of scope

No changes to Android or Desktop. No changes to `ActiveJobViewModel`'s existing call sites (already correct, calling into the common `expect` interface). No broader location-architecture changes (e.g., adjusting `distanceFilter`/accuracy settings) beyond what's needed to make the existing `startBackgroundTracking()`/`stopBackgroundTracking()` methods actually reachable.
