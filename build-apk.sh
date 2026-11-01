#!/usr/bin/env bash
# Builds the RELEASE APK (signed, non-debuggable, minified) by default.
#   ./build-apk.sh          -> release  (what you install; no "debug app" warning)
#   ./build-apk.sh debug    -> debug    (only for USB debugging from Android Studio)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
MODE="${1:-release}"

if [ "$MODE" = "release" ]; then
  if [ ! -f keystore.properties ]; then
    echo "No keystore yet. Run ./make-keystore.sh once first, then re-run this script."
    exit 1
  fi
  echo "==> Building Youtubly RELEASE APK..."
  ./gradlew clean :app:assembleRelease
  APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
  OUT="youtubly-release.apk"
else
  echo "==> Building Youtubly debug APK..."
  ./gradlew clean :app:assembleDebug
  APK_PATH=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
  OUT="youtubly-debug.apk"
fi

if [ -z "${APK_PATH:-}" ]; then echo "Build failed: no APK found."; exit 1; fi
cp "$APK_PATH" "$SCRIPT_DIR/$OUT"
echo "==> APK copied to $SCRIPT_DIR/$OUT"
