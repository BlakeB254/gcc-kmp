# Android builds on ms-a2

ms-a2 (`codexian@10.0.0.147`, x86-64) is **the** Android build host for the GCC
KMP app. Not one option of several — the only one.

| Host | Android | Why |
|---|---|---|
| **ms-a2** | ✅ builds + installs | x86-64, has `aapt2`, has the disk |
| DGX Spark | ❌ never | Google ships no ARM64-Linux `aapt2` |
| MacBook Air | ❌ by policy | Reserved for iOS; tight on space |

The MacBook is iOS-only on purpose. `.cdx-mobile.yaml` deliberately omits
`android_build`, so `cdx-mobile build android` errors and `cdx-mobile build both`
builds iOS alone. See `scripts/macbook-trim-android-sdk.sh`.

> The Mac still keeps JDK 17 and a minimal Android SDK. That is **required**, not
> leftover: `composeApp` applies the androidApplication plugin, Gradle configures
> every module on every invocation, and the Xcode build phase runs
> `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`. Removing the SDK
> there breaks iOS with "SDK location not found".

Conversely, ms-a2 can never take over iOS: Kotlin/Native compiles Apple
frameworks only on macOS. Two hosts is the floor for this app.

## Host layout

| What | Where |
|---|---|
| JDK 17 (Temurin 17.0.20.1) | `~/sdk/java/jdk-17.0.20.1+1` |
| Android SDK | `~/Android/Sdk` (cmdline-tools `latest`, platform-tools, platforms;android-36, build-tools 35.0.0 + 36.0.0) |
| Repo clone | `~/gcc-android` |
| SDK path config | `~/gcc-android/local.properties` → `sdk.dir=/home/codexian/Android/Sdk` |
| Env file | `~/sdk/android-env.sh` (sets `JAVA_HOME`, `ANDROID_HOME`, `PATH`; sourced from `~/.profile`) |

Everything is user-local; no sudo was used or needed.

### Remotes

`~/gcc-android` has two remotes:

| Remote | URL |
|---|---|
| `origin` | `https://github.com/BlakeB254/gcc.git` — **the truth** |
| `dgx` | `dgx-spark:cdx/dev-projects/gcc` — Spark's mirror |

Local `main` tracks `dgx/main`, so a bare `git pull` pulls from the Spark, not
GitHub. Pull explicitly until that is repointed:

```bash
git pull --ff-only origin main
```

## Build

```bash
ssh codexian@10.0.0.147
. ~/sdk/android-env.sh   # only needed for non-login shells
cd ~/gcc-android
git pull --ff-only origin main
./gradlew :composeApp:assembleDebug --console=plain
```

Output: `~/gcc-android/composeApp/build/outputs/apk/debug/composeApp-debug.apk`
(~82 MB). First build downloads Gradle 8.11.1 plus dependencies; warm builds are
much faster.

Check what you actually built:

```bash
cat composeApp/build/outputs/apk/debug/output-metadata.json
```

## Install on the Galaxy S26

`adb` runs on ms-a2 — the APK never needs to travel to the Spark.

One-time on the phone: **Settings → About phone → Software information → tap
Build number 7×**, then **Settings → Developer options → USB debugging**. Plug
into ms-a2 over USB and accept the RSA fingerprint prompt.

```bash
adb devices -l                 # must list the device as `device`, not `unauthorized`
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Wireless instead of USB (same LAN), after one USB pairing:

```bash
adb tcpip 5555
adb connect <phone-ip>:5555
```

Live logs while testing:

```bash
adb logcat --pid=$(adb shell pidof com.globalcommunitycleaners.worker)
```

## Release parity

Android `versionCode`/`versionName` must equal iOS
`CFBundleVersion`/`CFBundleShortVersionString`. This is enforced in CI by
`scripts/check-version-parity.sh`; run it locally before pushing a version bump:

```bash
./scripts/check-version-parity.sh
```
