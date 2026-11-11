#!/bin/bash
# Builds device/src/main/resources/touch/tl-touch.dex from Touch.java (needs the Android SDK: a platform's
# android.jar and build-tools' d8). The dex is committed, like the daemon's hs.jar, so builds need no SDK.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
JAR=$(ls -d "$SDK"/platforms/android-3*/android.jar | sort -V | tail -1)
D8=$(ls -d "$SDK"/build-tools/*/d8 | sort -V | tail -1)
OUT=$(mktemp -d)
javac --release 8 -cp "$JAR" -d "$OUT/classes" Touch.java 2>&1 | grep -v "warning: \[options\]" || true
(cd "$OUT/classes" && "$D8" --min-api 26 --output "$OUT" $(find . -name '*.class'))
cp "$OUT/classes.dex" ../../device/src/main/resources/touch/tl-touch.dex
rm -rf "$OUT"
echo "built device/src/main/resources/touch/tl-touch.dex ($(wc -c < ../../device/src/main/resources/touch/tl-touch.dex) bytes)"
