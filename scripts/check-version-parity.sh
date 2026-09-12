#!/usr/bin/env bash
# Fail if the Android and iOS release lines have drifted apart.
#
# composeApp/build.gradle.kts declares that versionCode tracks CFBundleVersion
# and versionName tracks CFBundleShortVersionString. That was only a comment,
# so it drifted: PR #280 shipped iOS build 189 and left Android on 188. This
# turns the convention into a gate.
#
# Usage: scripts/check-version-parity.sh
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradle_file="$repo_root/composeApp/build.gradle.kts"
plist_file="$repo_root/iosApp/iosApp/Info.plist"

for f in "$gradle_file" "$plist_file"; do
    [ -f "$f" ] || { echo "version-parity: missing $f" >&2; exit 2; }
done

android_code=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$gradle_file" | head -1)
android_name=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$gradle_file" | head -1)

ios_pair=$(python3 - "$plist_file" <<'PY'
import plistlib, sys
with open(sys.argv[1], "rb") as fh:
    p = plistlib.load(fh)
print(p.get("CFBundleVersion", ""), p.get("CFBundleShortVersionString", ""))
PY
)
ios_code=${ios_pair%% *}
ios_name=${ios_pair##* }

[ -n "$android_code" ] || { echo "version-parity: could not read versionCode from $gradle_file" >&2; exit 2; }
[ -n "$ios_code" ]     || { echo "version-parity: could not read CFBundleVersion from $plist_file" >&2; exit 2; }

status=0
if [ "$android_code" != "$ios_code" ]; then
    echo "version-parity: BUILD NUMBER DRIFT" >&2
    echo "  Android versionCode     = $android_code  (composeApp/build.gradle.kts)" >&2
    echo "  iOS     CFBundleVersion = $ios_code  (iosApp/iosApp/Info.plist)" >&2
    status=1
fi
if [ "$android_name" != "$ios_name" ]; then
    echo "version-parity: MARKETING VERSION DRIFT" >&2
    echo "  Android versionName                = $android_name" >&2
    echo "  iOS     CFBundleShortVersionString = $ios_name" >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo >&2
    echo "Both stores ship the same release line. Bump the other platform in the" >&2
    echo "same PR, or deliberately fork the line and update this check." >&2
    exit 1
fi

echo "version-parity OK: $android_name ($android_code) on both platforms"
