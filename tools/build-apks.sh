#!/usr/bin/env bash
# Builds both APKs and puts them somewhere they can be uploaded to a GitHub
# release, under the names the app looks for (githubAssets in
# app/src/main/java/com/nicobrailo/astrodock/apps/Installable.kt).
#
# Usage: tools/build-apks.sh [OUT_DIR]
#   OUT_DIR is where the APKs are left, ~/Downloads by default.
#
# Upload the debug one. run-as only works on a debuggable app, so
# tools/push-config.sh and tools/force-uninstall.sh both need it, and the Apps
# tab prefers AstroDock-debug.apk when it updates the app. The release build is
# there for a device that doesn't need any of that.
#
# Both are signed with the Android debug keystore: app/build.gradle.kts has no
# signing config, and AGP falls back to that key for a release build too. It
# has to stay the same key, or an AstroDock already on a device can't be
# updated, only uninstalled first.
#
# The unit tests run first, since an APK that's about to be published is worth
# that much. Each APK's sha256 is printed, which is what the Apps tab compares
# against the digest GitHub publishes for the uploaded file.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  awk '/^# Usage:/ { on = 1 } on && /^#?$/ { exit } on { sub(/^# ?/, ""); print }' "$0" >&2
  exit 1
fi

OUT=${1:-$HOME/Downloads}
cd "$(dirname "$0")/.."

# Android Studio writes local.properties, but a plain shell has neither that
# nor, usually, ANDROID_HOME
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
[[ -d $SDK ]] || { echo "No Android SDK at $SDK; set ANDROID_HOME" >&2; exit 1; }
export ANDROID_HOME=$SDK
BUILD_TOOLS=$SDK/build-tools/$(ls -1 "$SDK/build-tools" | sort -V | tail -1)

mkdir -p "$OUT"
./gradlew --console=plain testDebugUnitTest assembleDebug assembleRelease

describe() {
  local apk=$1
  local manifest
  manifest=$("$BUILD_TOOLS/aapt2" dump xmltree --file AndroidManifest.xml "$apk")
  local version debuggable
  version=$(sed -n 's/.*versionName[^"]*"\([^"]*\)".*/\1/p' <<<"$manifest" | head -1)
  # Only a debug build has it, and it's the whole reason to prefer that one
  grep -q 'debuggable(0x0101000f)=true' <<<"$manifest" && debuggable=debuggable || debuggable=""
  printf '%-24s %5.1f MB  version %-8s %-11s sha256 %s\n' \
    "$(basename "$apk")" \
    "$(awk -v b="$(stat -c%s "$apk")" 'BEGIN { printf "%.1f", b / 1048576 }')" \
    "$version" "$debuggable" "$(sha256sum "$apk" | cut -d' ' -f1)"
}

cp app/build/outputs/apk/debug/app-debug.apk "$OUT/AstroDock-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT/AstroDock-release.apk"

echo
describe "$OUT/AstroDock-debug.apk"
describe "$OUT/AstroDock-release.apk"
echo
# Worth seeing: the same key has to sign every release, and a Studio build on
# another machine would use that machine's debug keystore
certs=$("$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/AstroDock-debug.apk" 2>/dev/null)
echo "signed by $(sed -n 's/^Signer #1 certificate DN: //p' <<<"$certs")" \
  "($(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs"))"
echo
echo "In $OUT, ready for https://github.com/nicolasbrailo/AstroDock/releases/new"
