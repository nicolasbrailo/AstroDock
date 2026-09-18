#!/usr/bin/env bash
# Sets the Portal up for alauncher: everything an app can't do for itself.
# Safe to run again at any time; it only sets values, and prints what the
# device looks like afterwards.
#
# Usage: tools/setup-device.sh
#
# It makes alauncher the home screen, turns screensavers off (so the screen
# switches off by itself when the Portal stops seeing people, instead of
# dreaming for 20 minutes first), hides the Portal's
# floating bug-report pill, and turns off the Portal's app verifier, which only
# accepts apps signed by Facebook and fails every other install with "App
# certificate rejected" (adb installs are never verified). To undo any of it:
#
#   adb shell cmd package set-home-activity com.facebook.alohaapps.launcher
#   adb shell settings put secure screensaver_enabled 1
#   adb shell settings put secure screensaver_components \
#     com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
#   adb shell appops set com.facebook.aloha.system.services SYSTEM_ALERT_WINDOW allow
#   adb shell settings put global package_verifier_enable 1
#
# The rest of what the app needs (device admin, system settings, notification
# access, installing apps) is granted from the System tab of its settings.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  awk '/^# Usage:/ { on = 1 } on && !/^#/ { exit } on { sub(/^# ?/, ""); print }' "$0" >&2
  exit 1
fi

PKG=com.nicobrailo.alauncher
# Draws the floating bug-report pill. There's no setting for it, so the overlay
# permission is taken away instead, and the package restarted to drop the
# window it already has.
OVERLAY_PKG=com.facebook.aloha.system.services

echo "Setting up $PKG..."
adb shell cmd package set-home-activity "$PKG/.SlideshowActivity" >/dev/null
# Screensavers off: with one running, the screen only sleeps after the secure
# sleep_timeout (20 minutes, and the Portal resets it), while with none the
# system screen_off_timeout switches the screen off, and the app can set that
# one itself. The component stays pointed at ours, so it's our slideshow and
# not the Portal's if screensavers are ever turned back on.
adb shell settings put secure screensaver_components "$PKG/.SlideshowDreamService"
adb shell settings put secure screensaver_enabled 0
adb shell appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW deny
adb shell am force-stop "$OVERLAY_PKG"
adb shell settings put global package_verifier_enable 0

echo
echo "home screen:      $(adb shell cmd package resolve-activity -a android.intent.action.MAIN \
  -c android.intent.category.HOME 2>/dev/null | sed -n 's/^ *name=//p' | head -1 | tr -d '\r')"
echo "screensaver:      $(adb shell settings get secure screensaver_components | tr -d '\r')"
echo "  enabled:        $(adb shell settings get secure screensaver_enabled | tr -d '\r')"
echo "screen off after: $(adb shell settings get system screen_off_timeout | tr -d '\r') ms (since the Portal last saw someone)"
echo "bug pill overlay: $(adb shell appops get "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW | tr -d '\r' | head -1)"
echo "app verifier:     $(adb shell settings get global package_verifier_enable | tr -d '\r') (1 rejects apps not signed by Facebook)"
