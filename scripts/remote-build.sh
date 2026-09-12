#!/usr/bin/env bash
# Thin wrapper → cdx-mobile (platform-level tool)
# Full tool: ~/bin/cdx-mobile | Config: .cdx-mobile.yaml
#
# iOS only. Android was moved off the MacBook Air (it is reserved for iOS and
# tight on space) and now builds on ms-a2 — see docs/android-build-msa2.md.
set -euo pipefail

target="${1:-both}"
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

if [ "$target" = "android" ]; then
    cat >&2 <<'MSG'
remote-build: Android does not build through cdx-mobile.

cdx-mobile drives one build host for all platforms (cdx-macbook), and the
MacBook Air is reserved for iOS. Build the APK on ms-a2 instead:

    ssh codexian@10.0.0.147
    . ~/sdk/android-env.sh
    cd ~/gcc-android && git pull --ff-only origin main
    ./gradlew :composeApp:assembleDebug --console=plain

Full runbook: docs/android-build-msa2.md
MSG
    exit 2
fi

if [ ! -x "$HOME/bin/cdx-mobile" ]; then
    echo "remote-build: $HOME/bin/cdx-mobile not found." >&2
    echo "This wrapper runs from a host that has cdx-mobile installed (the Spark)." >&2
    echo "For Android on ms-a2, see docs/android-build-msa2.md." >&2
    exit 127
fi

exec "$HOME/bin/cdx-mobile" build "$target" --project "$repo_root" "${@:2}"
