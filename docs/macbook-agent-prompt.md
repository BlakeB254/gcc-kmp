# GCC-KMP MacBook Agent Bootstrap Prompt

Copy everything below the line into a Claude Code session on the MacBook Air (`cdx-macbook`).

---

You are setting up and advancing mobile development for the **GCC-KMP** (Global Community Cleaners — Kotlin Multiplatform) project on this MacBook Air. This machine is a build endpoint in a multi-device dev pipeline. The primary development happens on a DGX Spark server (ARM64 Linux, Tailscale hostname `cdx`), code syncs here via GitHub, and this MacBook handles iOS and Android builds, simulator testing, and App Store deployment.

## Your Role

1. **Complete the mobile build environment** — install missing prerequisites (Homebrew, JDK 17), verify Gradle and Xcode builds work
2. **Get the iOS app building and running in the simulator** — the Xcode project exists but has never been built successfully
3. **Get the Android app building and running in the emulator** — the AVD and SDK exist but Gradle needs Java
4. **Make this machine a reliable build endpoint** — so the DGX can trigger builds remotely via `cdx-mobile` over SSH
5. **Advance the mobile UX** — fix any platform-specific issues, test the actual app behavior on simulators

## Network & Device Context

```
DGX Spark (cdx)           — 100.67.196.13  — Primary dev, all services, Ktor API on port 7310
MacBook Air (cdx-macbook) — 100.71.186.82  — THIS MACHINE — Xcode, iOS/Android builds
Mac Mini (cdx-mac-mini)   — 100.120.112.94 — Secondary build server
iPhone (icdx)             — 100.80.196.45  — Physical test device
```

All connected via Tailscale mesh VPN. The Ktor API server runs on the DGX at port 7310. From this MacBook, the API is reachable at `http://cdx:7310` via Tailscale MagicDNS.

## Project Architecture

KMP monorepo at `~/CDX-DEV-PROJECTS/gcc-kmp/`:

```
gcc-kmp/
├── shared/              # Shared Kotlin code (models, API client, auth)
│   └── src/commonMain/  # Cross-platform: model/*.kt, api/GccApiClient.kt, auth/TokenManager.kt
├── composeApp/          # Compose Multiplatform worker app (iOS + Android + Desktop)
│   ├── src/commonMain/  # Shared UI: screens, viewmodels, navigation, components
│   ├── src/androidMain/ # Android-specific: MainActivity.kt, PlatformConfig.kt
│   ├── src/iosMain/     # iOS-specific: MainViewController.kt, PlatformConfig.kt
│   └── src/desktopMain/ # Desktop-specific (not relevant here)
├── adminApp/            # Admin desktop app (JVM only, not relevant here)
├── server/              # Ktor API server (runs on DGX, not relevant here)
├── iosApp/              # Xcode project wrapper for iOS
│   ├── iosApp.xcodeproj/
│   └── iosApp/
│       ├── iosAppApp.swift      # SwiftUI entry point
│       ├── ContentView.swift    # Hosts ComposeView (bridges to Kotlin)
│       └── Info.plist           # Bundle ID: com.globalcommunitycleaners.worker
├── .cdx-mobile.yaml     # Build config for cdx-mobile sync tool
├── gradlew              # Gradle wrapper
└── scripts/             # Thin wrappers to cdx-mobile
```

### Key Tech Stack

- **Kotlin 2.2.0**, Compose Multiplatform 1.8.0
- **Decompose** for navigation (RootComponent → Screen enum → 18 screens)
- **Koin 4.0** for dependency injection
- **Ktor Client** for HTTP (with auth plugin, Bearer token auto-attach)
- **kotlinx.serialization** for JSON
- **compileSdk 36**, minSdk 26, targetSdk 35
- **AGP 8.9.1** (required by transitive Jetpack deps)

### API Connection

The app connects to the Ktor API server. Platform-specific base URLs:

| Platform | URL | Why |
|----------|-----|-----|
| Android emulator | `http://10.0.2.2:7310` | Android emulator's localhost alias |
| iOS simulator | `http://localhost:7310` | Direct localhost |
| Desktop | `http://localhost:7310` | Direct localhost |

**IMPORTANT**: The API server runs on the DGX (`cdx`), not on this MacBook. For the simulators to reach it:
- **Android emulator**: `10.0.2.2` maps to the host machine. You need to either run the server locally OR change the URL to `http://cdx:7310` (Tailscale).
- **iOS simulator**: `localhost` won't work unless the server runs locally. Change to `http://cdx:7310` for Tailscale.
- **Best approach**: For development, update the platform configs to use `http://cdx:7310` (the Tailscale hostname). This way the app talks to the real API on the DGX. OR use demo mode (see below).

### Demo Mode

The app has a full demo mode that uses a mock HTTP engine — zero network calls, all fake data. This is the easiest way to test the UI without the API server:

- **Android**: Debug builds auto-enable demo mode (`ApplicationInfo.FLAG_DEBUGGABLE`)
- **iOS**: Set environment variable `GCC_DEMO=true` in Xcode scheme, or call `MainViewControllerDemo()` instead of `MainViewController()`
- **What it does**: Swaps the Koin module to use `DemoAppModule` which provides `createDemoApiClient()` (MockEngine) and `DemoTokenManager` (in-memory token storage)

## What's Already Installed on This Machine

| Component | Status | Details |
|-----------|--------|---------|
| macOS | 26.2 | Apple Silicon (arm64) |
| Xcode | 26.2 | Full install, CLI tools present |
| iOS Simulators | Available | iPhone 17 Pro (booted), iPhone Air, iPad Pro, etc. |
| Android SDK | Installed | `~/Library/Android/sdk`, platforms: android-35, android-36 |
| Android Emulator | Available | `Pixel_8_API_35` AVD exists, google_apis system image |
| Git | Installed | Repo cloned at `~/CDX-DEV-PROJECTS/gcc-kmp` |
| Xcode Project | Created | `iosApp/iosApp.xcodeproj` with scheme, MapLibre SPM dep |
| Homebrew | **NOT INSTALLED** | Needed for JDK |
| Java/JDK | **NOT INSTALLED** | Needed for Gradle (Android builds) |
| JAVA_HOME | **NOT SET** | |
| ANDROID_HOME | **NOT SET** | |

## What's Already Modified Locally (Uncommitted)

These files were modified on this MacBook during initial setup. They contain important fixes — DO NOT discard them:

- `composeApp/src/androidMain/kotlin/com/gcc/app/MainActivity.kt` — Koin init with demo mode detection, GlobalContext guard
- `composeApp/src/iosMain/kotlin/com/gcc/app/MainViewController.kt` — Koin init with demo mode, GlobalContext guard, `MainViewControllerDemo()` function
- `iosApp/iosApp/Info.plist` — Bundle ID, display name, privacy usage descriptions (camera, location, photos)
- `iosApp/iosApp.xcodeproj/` — New Xcode project with MapLibre SPM, build settings, scheme

## Tasks — In Priority Order

### Task 1: Install Prerequisites

```bash
# Install Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
eval "$(/opt/homebrew/bin/brew shellenv)"

# Install JDK 17
brew install openjdk@17
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
```

Add to `~/.zshrc`:
```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools/bin:$PATH"
```

Verify: `java -version` should show OpenJDK 17.

### Task 2: Sync Latest Code from GitHub

```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp
git stash push -m "local-ios-android-setup"
git pull --ff-only origin main
git stash pop
```

This preserves your local changes while pulling the latest from the DGX (which is ahead by several commits including admin map, feed system, demo mode, etc.).

### Task 3: Build Android APK

```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp
./gradlew :composeApp:assembleDebug
```

If successful, the APK will be at `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

To run on the emulator:
```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 &
adb install composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell am start -n com.gcc.app/.MainActivity
```

The debug build auto-enables demo mode, so it should show the full UI with mock data.

### Task 4: Build iOS App in Xcode

Open the project:
```bash
open iosApp/iosApp.xcodeproj
```

In Xcode:
1. Select the `iosApp` scheme
2. Select `iPhone 17 Pro` simulator as destination
3. Build (Cmd+B) — this will first run `./gradlew :composeApp:compileKotlinIosSimulatorArm64` to create the Kotlin framework, then compile Swift
4. Run (Cmd+R) to launch in simulator

**Expected issues and fixes:**
- If the ComposeApp framework isn't found, you may need a "Run Script" build phase that invokes Gradle. Check the build phases in the Xcode project.
- If MapLibre SPM fails to resolve, go to File → Packages → Reset Package Cache.
- For demo mode in simulator: Edit Scheme → Run → Arguments → Environment Variables → add `GCC_DEMO = true`.

### Task 5: Fix Platform API URLs for Tailscale

The current `PlatformConfig.kt` files use localhost URLs which won't work from simulators talking to the DGX. Update:

**`composeApp/src/iosMain/kotlin/com/gcc/app/platform/PlatformConfig.kt`:**
```kotlin
actual fun getApiBaseUrl(): String = "http://cdx:7310"
```

**`composeApp/src/androidMain/kotlin/com/gcc/app/platform/PlatformConfig.kt`:**
```kotlin
actual fun getApiBaseUrl(): String = "http://cdx:7310"
```

This uses Tailscale MagicDNS to reach the API server on the DGX. Both the simulator and emulator should resolve `cdx` because Tailscale is running on this MacBook.

**Note**: If Tailscale DNS doesn't work inside the Android emulator, use the DGX's Tailscale IP directly: `http://100.67.196.13:7310`.

### Task 6: Verify Remote Build Endpoint

The DGX has a tool called `cdx-mobile` that triggers builds on this MacBook via SSH. For it to work, these must be true:

1. SSH works from DGX: `ssh cdx-macbook "echo ok"` (already verified)
2. Java is available in non-interactive SSH sessions (the `~/.zshrc` exports must work when sourced from `bash -s`)
3. `./gradlew :composeApp:assembleDebug` succeeds from command line
4. `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' build` succeeds from command line

Test the build commands work from a fresh terminal (not one where you've manually sourced env vars).

### Task 7: Test App Behavior in Simulators

Once both builds work, test the actual app:

**Demo mode checklist:**
- [ ] App launches without crash
- [ ] Login screen appears (or auto-skips in demo mode)
- [ ] Dashboard shows with stat cards, map card, activity feed
- [ ] Navigation works: tap sidebar items to switch screens
- [ ] Map renders (Canvas-based on iOS, shows Chicago community areas)
- [ ] Profile screen shows QR code, badge cards
- [ ] Bounty list loads with mock bounties
- [ ] Feed screen shows activity items with filter chips
- [ ] Pull-to-refresh works on Dashboard, Bounties, Leaderboard, Feed

**Report any issues** — note the screen, the error (crash log / visual bug), and the device.

### Task 8: Commit and Push Local Changes

The Kotlin mobile files (`MainActivity.kt`, `MainViewController.kt`) are **already committed** in `4823357`. Only the Xcode project and docs are uncommitted. After all builds work, commit and push:

```bash
cd ~/CDX-DEV-PROJECTS/gcc-kmp

# Check what's uncommitted
git status

# Stage the Xcode project (already allowed by .gitignore: !iosApp/iosApp.xcodeproj)
git add iosApp/iosApp.xcodeproj/
git add GCC-FULL-DEV-SETUP.md

# Also stage any new platform fixes you made during this session
git add composeApp/src/androidMain/
git add composeApp/src/iosMain/
git add iosApp/iosApp/

# Commit with descriptive message
git commit -m "feat: add iOS Xcode project and mobile build configuration

- Add iosApp.xcodeproj with MapLibre SPM dependency, build settings, scheme
- Configure Info.plist: bundle ID (com.globalcommunitycleaners.worker),
  privacy descriptions for camera, location, photos
- Add GCC-FULL-DEV-SETUP.md with MacBook setup documentation

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"

git push origin main
```

**IMPORTANT**: Do NOT force push or amend. The DGX has uncommitted work (admin map, Phase 11) that it will commit on top of this. Both sides touch completely different files, so pushes from either direction are conflict-free.

After pushing, notify that the DGX can now pull the Xcode project:
```bash
ssh cdx "cd ~/CDX-DEV-PROJECTS/gcc-kmp && git pull origin main"
```

## Build Reference

| Command | What it does |
|---------|-------------|
| `./gradlew :composeApp:assembleDebug` | Build Android debug APK |
| `./gradlew :composeApp:compileKotlinIosSimulatorArm64` | Compile Kotlin → iOS framework (arm64 simulator) |
| `./gradlew :composeApp:compileKotlinIosArm64` | Compile Kotlin → iOS framework (physical device) |
| `./gradlew :shared:compileKotlinIosSimulatorArm64` | Compile shared module for iOS simulator |
| `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' build` | Build iOS app for simulator |
| `xcrun simctl install booted <path-to.app>` | Install built app on booted simulator |
| `xcrun simctl launch booted com.globalcommunitycleaners.worker` | Launch app on simulator |

## Patterns to Follow

- **Koin init guard**: Always use `if (GlobalContext.getOrNull() == null) { startKoin { ... } }` — prevents double-init crashes
- **Demo mode**: Use environment variable `GCC_DEMO=true` or debug build detection, swap `appModule` for `demoAppModule`
- **iOS entry**: `MainViewControllerKt.MainViewController()` (normal) or `MainViewControllerKt.MainViewControllerDemo()` (demo)
- **expect/actual**: Platform code lives in `androidMain/` and `iosMain/` respectively. Shared logic in `commonMain/`.
- **Do NOT modify commonMain or desktopMain** — those are developed on the DGX. Only modify androidMain, iosMain, and iosApp/ here.
- **Commit and push** platform-specific changes so the DGX picks them up via `git pull`

## Git Sync State & Cross-Device Coordination

All three locations (DGX, GitHub, MacBook) are currently on the **same commit**:

```
DGX HEAD      = f4a5404  feat: add circle icon quick actions to Dashboard
GitHub        = f4a5404  (same)
MacBook HEAD  = f4a5404  (same)
```

The iOS/Android build work is already committed (`4823357 fix: add base map rendering to iOS/Android + fix Android ANR`) and synced everywhere. The DGX has **uncommitted Phase 11 changes** (admin map integration) that are entirely in `adminApp/` — zero overlap with mobile files.

### What's Uncommitted Where

| Location | Uncommitted Files | Overlap? |
|----------|------------------|----------|
| **DGX** | 9 new `adminApp/ui/map/` files, 5 modified `adminApp/` screens, `.cdx-mobile.yaml`, `scripts/`, `.gitignore`, `docs/` | No mobile files |
| **MacBook (HERE)** | `iosApp/iosApp.xcodeproj/` (untracked), `GCC-FULL-DEV-SETUP.md` (untracked) | iOS project only |

**These filesets have ZERO overlap** — they can be committed and pushed independently in any order.

### Safe Commit Ordering

```
Step 1: MacBook commits Xcode project + setup docs → push
        (iosApp/iosApp.xcodeproj/, GCC-FULL-DEV-SETUP.md, any new mobile fixes)

Step 2: DGX pulls (gets Xcode project, zero conflicts)

Step 3: DGX commits Phase 11 admin map + cdx-mobile → push
        (all adminApp/, scripts/, .cdx-mobile.yaml)

Step 4: MacBook pulls (gets admin map, zero conflicts)
```

Both directions are safe. If the DGX pushes first, MacBook can still push without conflicts because the files don't overlap.

### Cross-Device Build System (`cdx-mobile`)

The DGX has a CLI tool called `cdx-mobile` that triggers builds on this MacBook via SSH. Once prerequisites are installed (Task 1), the DGX can run:

```bash
cdx-mobile sync                    # Push to GitHub + pull here
cdx-mobile build ios               # Trigger iOS build on this MacBook
cdx-mobile build android           # Trigger Android build on this MacBook
cdx-mobile fetch                   # Pull APK/IPA artifacts back to DGX
```

For this to work, these must be true after setup:
1. `java -version` works in a non-interactive SSH session (`bash -s` from DGX)
2. `./gradlew :composeApp:assembleDebug` succeeds from CLI
3. `xcodebuild ... build` succeeds from CLI
4. Environment vars (`JAVA_HOME`, `ANDROID_HOME`) are in `~/.zshrc` (sourced by `bash -s`)

The per-project config is at `.cdx-mobile.yaml` in the repo root.

### API Server Location

The Ktor API server runs on the DGX (`cdx:7310`). From this MacBook:
- **Tailscale MagicDNS**: `http://cdx:7310` — works from macOS host
- **iOS Simulator**: `http://cdx:7310` — should work (simulators share host's Tailscale)
- **Android Emulator**: `http://10.0.2.2:7310` only works if server is local. Use `http://100.67.196.13:7310` (DGX Tailscale IP) or `http://cdx:7310` if emulator resolves Tailscale DNS
- **Demo mode**: No network needed — `DemoAppModule` uses `MockEngine` with fake data

## Known Issues

- **kotlinx-datetime 0.7.x conflict**: The root `build.gradle.kts` forces version 0.6.1. Do NOT upgrade — 0.7.x removes `Instant` class which breaks 20+ model files.
- **Skiko version**: Desktop builds pin `skiko-awt-runtime-linux-arm64:0.9.22.2` — not relevant for mobile but don't remove it.
- **MapLibre**: Declared in `libs.versions.toml` (maplibre-compose 0.12.1, compass 2.4.1). The iOS/Android GccMap implementations are stubs waiting to be wired to real MapLibre. The Canvas map is desktop-only.
- **Capturable library**: Android-only (AAR). Lives in androidMain, not commonMain. Used for screenshot sharing of ProfileCard/LeaderboardCard.
