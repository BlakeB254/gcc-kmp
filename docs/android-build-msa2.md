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

## Headless emulator QA on ms-a2 (no physical device)

ms-a2 can run the app headless for Android QA — `/dev/kvm` is world-writable
(`crw-rw-rw-`), so KVM acceleration needs no group membership or sudo.

One-time:

```bash
# on ms-a2 (codexian@10.0.0.147)
sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n gcc-test -k "system-images;android-36;google_apis;x86_64" -d pixel_7
```

Boot + drive headless:

```bash
~/Android/Sdk/emulator/emulator -avd gcc-test \
  -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
adb install -r ~/gcc-android/composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell input tap <x> <y>          # drive the UI
adb exec-out screencap -p > shot.png # capture
adb logcat -d > run-logcat.txt       # pull the log (crash stacks land here)
```

⚠ Debug builds force DEMO/OFFLINE mode (`MainActivity.kt` FLAG_DEBUGGABLE →
`offlineAppModule`), so emulator QA exercises the offline module — the same
path that surfaced the MapFullScreen Koin crash. Online-only DI gaps only
show under a release-signed build or the real backend.
