#!/usr/bin/env bash
# Uninstalls an app that is an active device admin, which plain
# "adb uninstall" refuses with DELETE_FAILED_DEVICE_POLICY_MANAGER. astrodock
# registers ScreenAdminReceiver so it can turn the screen off, so it needs this
# to be removed; its predecessor com.nicobrailo.alauncher did too.
#
# Usage: tools/force-uninstall.sh [PACKAGE]
#   e.g. tools/force-uninstall.sh com.nicobrailo.alauncher
# PACKAGE defaults to this app.
#
# "adb shell dpm remove-active-admin" is no help: it only removes an admin whose
# app is marked android:testOnly, and ours isn't. What does work is an update.
# The system drops an active admin as soon as its receiver stops existing, so
# this builds a tiny APK with the same package name, a higher version code and
# no components at all, installs it over the app, and then uninstalls it. The
# replacement has to be signed with the same key, which is the Android debug
# keystore every debug build of this project uses, so this only works on debug
# builds, like tools/push-config.sh.
#
# Nothing here needs the touch screen, and the app's data goes with it.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  awk '/^# Usage:/ { on = 1 } on && /^#?$/ { exit } on { sub(/^# ?/, ""); print }' "$0" >&2
  exit 1
fi

PKG=${1:-com.nicobrailo.astrodock}
KEYSTORE=${ANDROID_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
[[ -d $SDK ]] || { echo "No Android SDK at $SDK; set ANDROID_HOME" >&2; exit 1; }
# The newest of each, so this keeps working as the SDK is updated.
BUILD_TOOLS=$(ls -1 "$SDK/build-tools" | sort -V | tail -1)
BUILD_TOOLS=$SDK/build-tools/$BUILD_TOOLS
ANDROID_JAR=$(ls -1 "$SDK"/platforms/*/android.jar | sort -V | tail -1)
[[ -x $BUILD_TOOLS/aapt2 && -f $ANDROID_JAR ]] || {
  echo "Incomplete SDK at $SDK: need build-tools and a platform" >&2; exit 1; }
[[ -f $KEYSTORE ]] || { echo "No debug keystore at $KEYSTORE" >&2; exit 1; }

VERSION=$(adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
if [[ -z $VERSION ]]; then
  echo "$PKG isn't installed"
  exit 0
fi
echo "Uninstalling $PKG (version code $VERSION)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# No components, so the system sees the device admin receiver disappear, and no
# code, so nothing has to be compiled. testOnly as well, in case the receiver
# going away isn't enough and "dpm remove-active-admin" below has to do it.
cat > "$WORK/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PKG"
    android:versionCode="$((VERSION + 1))"
    android:versionName="uninstalling">
    <application android:hasCode="false" android:testOnly="true" android:label="uninstalling" />
</manifest>
EOF

"$BUILD_TOOLS/aapt2" link --manifest "$WORK/AndroidManifest.xml" -I "$ANDROID_JAR" \
  --min-sdk-version 29 --target-sdk-version 29 -o "$WORK/unsigned.apk"
"$BUILD_TOOLS/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$WORK/replacement.apk" "$WORK/aligned.apk" 2>/dev/null

# -t because the replacement is testOnly. A signature error here means the app
# on the device wasn't built from this machine's debug keystore.
if ! adb install -r -t --no-incremental "$WORK/replacement.apk"; then
  echo "Couldn't replace $PKG. If that was a signature mismatch, the app on the" >&2
  echo "device was signed with another key, and only its own build can do this." >&2
  exit 1
fi

# Belt and braces: by now the admin is usually gone with its receiver, and this
# says "non-test admin" for an admin that no longer exists, so its failure is
# not worth reporting.
for admin in $(adb shell dumpsys device_policy |
    sed -n "s|^ *\($PKG/[A-Za-z0-9_.]*\):$|\1|p"); do
  adb shell dpm remove-active-admin "$admin" >/dev/null 2>&1 || true
done

adb uninstall "$PKG"

if adb shell pm list packages | grep -q "^package:$PKG\$"; then
  echo "$PKG is still installed" >&2
  exit 1
fi
echo "$PKG is gone"
