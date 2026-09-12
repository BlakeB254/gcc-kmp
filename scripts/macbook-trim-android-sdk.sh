#!/usr/bin/env bash
# Reclaim disk on the MacBook Air by removing Android *testing* tooling while
# keeping the Android SDK pieces that iOS builds depend on.
#
# WHY THIS IS NOT "REMOVE THE ANDROID SDK":
#   composeApp/build.gradle.kts applies the androidApplication plugin. Gradle
#   configures every module on every invocation, and AGP resolves the Android
#   SDK at configuration time. The Xcode build phase runs
#       ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
#   so deleting the whole SDK breaks iOS builds with "SDK location not found".
#
#   KEEP (needed by iOS):  platforms/  build-tools/  platform-tools/  licenses/
#                          and JDK 17 at /opt/homebrew/opt/openjdk@17
#   REMOVE (Android-only): system-images/  emulator/  ~/.android/avd/
#
# The removed parts are only needed to run an Android emulator. GCC is tested
# on a physical Galaxy S26 over adb from ms-a2, so the Mac never needs them.
#
# Usage:
#   scripts/macbook-trim-android-sdk.sh           # dry run, shows sizes only
#   scripts/macbook-trim-android-sdk.sh --apply   # actually delete
set -euo pipefail

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script is for the MacBook (Darwin). Detected: $(uname -s)." >&2
    echo "ms-a2's own SDK is trimmed separately — it is the Android build host." >&2
    exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || { echo "Android SDK not found at $SDK" >&2; exit 1; }

echo "SDK: $SDK"
echo
echo "--- current footprint ---"
du -sh "$SDK" 2>/dev/null || true
du -sh "$SDK"/* 2>/dev/null | sort -rh || true
echo

TARGETS=("$SDK/system-images" "$SDK/emulator" "$HOME/.android/avd")
KEEP=("$SDK/platforms" "$SDK/build-tools" "$SDK/platform-tools" "$SDK/licenses")

echo "--- will REMOVE ---"
for t in "${TARGETS[@]}"; do
    [ -e "$t" ] && du -sh "$t" 2>/dev/null || echo "     (absent) $t"
done
echo
echo "--- will KEEP (iOS depends on these) ---"
for k in "${KEEP[@]}"; do
    [ -e "$k" ] && du -sh "$k" 2>/dev/null || echo "     (absent) $k"
done
echo

if [ "$APPLY" -ne 1 ]; then
    echo "Dry run. Re-run with --apply to delete."
    exit 0
fi

for t in "${TARGETS[@]}"; do
    if [ -e "$t" ]; then
        echo "removing $t"
        rm -rf "$t"
    fi
done

echo
echo "--- footprint after ---"
du -sh "$SDK" 2>/dev/null || true
echo
echo "NOW VERIFY iOS STILL BUILDS before trusting this:"
echo "    cd ~/CDX-DEV-PROJECTS/gcc-kmp"
echo "    ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode"
echo
echo "If it fails with 'SDK location not found', reinstall only what is needed:"
echo "    sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'"
