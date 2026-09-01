# Android APK builds on ms-a2

ms-a2 (`codexian@10.0.0.147`, x86-64) is the canonical Android build host for the
GCC KMP app. The DGX Spark cannot assemble APKs — Google ships no ARM64-Linux
`aapt2` — so `assembleDebug` runs here.

## Host layout

| What | Where |
|---|---|
| JDK 17 (Temurin 17.0.20.1) | `~/sdk/java/jdk-17.0.20.1+1` |
| Android SDK | `~/Android/Sdk` (cmdline-tools `latest`, platform-tools 37.0.1, platforms;android-36, build-tools;35.0.0) |
| Repo clone | `~/gcc-android` (origin = `dgx-spark:cdx/dev-projects/gcc`, branch `main`) |
| SDK path config | `~/gcc-android/local.properties` → `sdk.dir=/home/codexian/Android/Sdk` |
| Env file | `~/sdk/android-env.sh` (sets `JAVA_HOME`, `ANDROID_HOME`, `PATH`; sourced from `~/.profile`) |

Everything is user-local; no sudo was used or needed.

## Build

```bash
ssh codexian@10.0.0.147
. ~/sdk/android-env.sh   # only needed for non-login shells
cd ~/gcc-android
git pull
./gradlew :composeApp:assembleDebug --console=plain
```

Output: `~/gcc-android/composeApp/build/outputs/apk/debug/composeApp-debug.apk`.
First build downloads the Gradle 8.11.1 wrapper plus all dependencies; warm
builds are much faster.

## Copy back to the Spark

```bash
# from the Spark
scp codexian@10.0.0.147:gcc-android/composeApp/build/outputs/apk/debug/composeApp-debug.apk \
    ~/Android/apk/gcc-msa2-build/composeApp-debug.apk
```

## Install on the Galaxy

With the Galaxy connected (USB or `adb connect <phone-ip>:5555` over the LAN)
to whichever host runs adb:

```bash
adb install -r ~/Android/apk/gcc-msa2-build/composeApp-debug.apk
```
