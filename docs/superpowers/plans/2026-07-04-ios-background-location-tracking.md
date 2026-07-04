# iOS Background Location Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make iOS actually enable background location tracking during active jobs by wiring already-written, correct `LocationProvider` code to the currently-no-op `LocationTrackingController`.

**Architecture:** A one-time static-companion init (matching the existing `initContext`/`initActivity` pattern already used elsewhere in this codebase) hands `LocationTrackingController` (iOS) a reference to the app's single shared `LocationProvider` Koin instance, so `startTracking()`/`stopTracking()` can call the real `startBackgroundTracking()`/`stopBackgroundTracking()` methods. Plus one `Info.plist` entitlement addition.

**Tech Stack:** Kotlin/Native (iosMain), Koin DI, native iOS `Info.plist`.

## Global Constraints

- iOS-only — no changes to Android or Desktop `LocationTrackingController` actuals, and no change to the shared `expect class LocationTrackingController()` constructor signature.
- No changes to `ActiveJobViewModel`'s existing call sites (`.startTracking()`/`.stopTracking()`) — they already call the correct interface.
- **This host cannot compile or run any of this code.** Kotlin/Native iOS compilation is disabled entirely on this Linux machine (`kotlin.native.ignoreDisabledTargets=true` in `gradle.properties` — confirmed during prior work that even `compileKotlinIosSimulatorArm64` reports a no-op `SKIPPED` task here, not real verification). Every step in this plan that would normally be "run the tests" is replaced with careful manual code verification instead; real verification requires the MacBook toolchain.

---

### Task 1: Wire `LocationTrackingController` to `LocationProvider`, enable the background-location entitlement

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationTrackingController.kt` (currently 11 lines, both methods are no-ops)
- Modify: `composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt:43-45`
- Modify: `iosApp/iosApp/Info.plist:59-61`

**Interfaces:**
- Produces: `LocationTrackingController.Companion.initLocationProvider(provider: LocationProvider): Unit` (new, iOS-only, not part of the shared `expect` contract) — called once from `MainViewController.kt`.
- Consumes: `LocationProvider.startBackgroundTracking()`/`stopBackgroundTracking()` (both already exist, unchanged, at `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationProvider.kt:169-187`).

- [ ] **Step 1: Replace `LocationTrackingController.kt`'s no-op body**

Replace the full contents of `composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationTrackingController.kt` with:

```kotlin
package com.gcc.app.platform

/**
 * iOS background location tracking during active jobs. Delegates to the
 * app's shared [LocationProvider] Koin singleton (wired in once via
 * [initLocationProvider], called from MainViewController.kt at startup) —
 * calling `LocationProvider()` directly here would construct a disconnected
 * CLLocationManager with none of the location-update state actually in use
 * elsewhere in the app.
 */
actual class LocationTrackingController {
    actual fun startTracking() {
        sharedLocationProvider?.startBackgroundTracking()
    }

    actual fun stopTracking() {
        sharedLocationProvider?.stopBackgroundTracking()
    }

    companion object {
        private var sharedLocationProvider: LocationProvider? = null

        /** Call once at app startup with the Koin-managed LocationProvider instance. */
        fun initLocationProvider(provider: LocationProvider) {
            sharedLocationProvider = provider
        }
    }
}
```

- [ ] **Step 2: Wire the one-time init call in `MainViewController.kt`**

In `composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt`, add the import (alongside the existing `com.gcc.shared.auth.TokenManager` import at line 10):

```kotlin
import com.gcc.app.platform.LocationTrackingController
import com.gcc.app.platform.LocationProvider
```

Then change (lines 43-45), from:

```kotlin
    // Create Decompose component outside composable scope — must survive recomposition
    val lifecycle = LifecycleRegistry()
    val tokenManager = KoinPlatform.getKoin().get<TokenManager>()
```

to:

```kotlin
    // Create Decompose component outside composable scope — must survive recomposition
    val lifecycle = LifecycleRegistry()
    val tokenManager = KoinPlatform.getKoin().get<TokenManager>()
    LocationTrackingController.initLocationProvider(KoinPlatform.getKoin().get<LocationProvider>())
```

- [ ] **Step 3: Add the `location` background mode to `Info.plist`**

In `iosApp/iosApp/Info.plist`, change (lines 59-61), from:

```xml
	<key>UIBackgroundModes</key>
	<array>
		<string>remote-notification</string>
	</array>
```

to:

```xml
	<key>UIBackgroundModes</key>
	<array>
		<string>remote-notification</string>
		<string>location</string>
	</array>
```

- [ ] **Step 4: Manual verification (no automated test possible)**

This host cannot compile iOS Kotlin/Native code at all, so there is no build command to run here. Instead, careful manual verification:

1. Re-read the final `LocationTrackingController.kt` and confirm: `startTracking()`/`stopTracking()` call the real `LocationProvider` methods (not still no-ops), the companion object correctly holds a nullable `LocationProvider?` (so a call before `initLocationProvider()` runs is a safe no-op, not a crash), and the file still satisfies the shared `expect class LocationTrackingController()` contract (no-arg constructor, `startTracking()`/`stopTracking()` with no parameters, no return value) — compare directly against `composeApp/src/commonMain/kotlin/com/gcc/app/platform/LocationTrackingController.kt`'s `expect` declaration.
2. Re-read `MainViewController.kt` and confirm the new `LocationTrackingController.initLocationProvider(...)` call is placed AFTER Koin has started (`startKoin { ... }` block, lines 37-41) and BEFORE `ComposeUIViewController { App(root) }` runs — placing it before Koin starts would throw (`KoinPlatform.getKoin()` would fail), and placing it after the composable scope begins would be too late for anything that might call `startTracking()` early in app lifecycle.
3. Re-read the `Info.plist` diff and confirm the XML is well-formed (matching `<string>` sibling-element syntax exactly, no stray tags) — a malformed plist can silently fail to parse or crash app launch.
4. **This step's real completion requires a MacBook build.** Note in the commit message and PR description that on-device/simulator verification is still needed: confirm the app doesn't crash when an active job starts tracking, the "Always" location permission prompt appears, and location updates continue to arrive with the app backgrounded during an active job.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/iosMain/kotlin/com/gcc/app/platform/LocationTrackingController.kt composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt iosApp/iosApp/Info.plist
git commit -m "Wire iOS background location tracking to already-written LocationProvider code"
```

---

## Self-Review Notes

- **Spec coverage**: §2's three file changes (`LocationTrackingController.kt`, `MainViewController.kt`, `Info.plist`) are all covered by Steps 1-3. §3 (no automated test possible, needs a real MacBook verification pass) is covered by Step 4, explicitly not faked as a real automated test.
- **Placeholder scan**: no TBD/TODO. Step 4 explicitly documents *why* no build/test command exists here rather than silently omitting one.
- **Type consistency**: `LocationTrackingController.Companion.initLocationProvider(provider: LocationProvider)` is defined in Step 1 and called with the identical name/signature in Step 2. `LocationProvider.startBackgroundTracking()`/`stopBackgroundTracking()` (consumed, unchanged) match the exact method names confirmed against the real file during spec research.
