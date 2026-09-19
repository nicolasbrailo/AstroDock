#!/usr/bin/env bash
# Sets the Portal up for astrodock: everything an app can't do for itself.
# Safe to run again at any time; it only sets values, and prints what the
# device looks like afterwards.
#
# Usage: tools/setup-device.sh
#
# It makes astrodock the home screen and the screensaver, lets it write the
# secure setting that decides when the screen switches off, hides the Portal's
# floating bug-report pill, and turns off the Portal's app verifier, which only
# accepts apps signed by Facebook and fails every other install with "App
# certificate rejected" (adb installs are never verified). To undo any of it:
#
#   adb shell cmd package set-home-activity com.facebook.alohaapps.launcher
#   adb shell pm revoke com.nicobrailo.astrodock android.permission.WRITE_SECURE_SETTINGS
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

PKG=com.nicobrailo.astrodock
# Draws the floating bug-report pill. There's no setting for it, so the overlay
# permission is taken away instead, and the package restarted to drop the
# window it already has.
OVERLAY_PKG=com.facebook.aloha.system.services

echo "Setting up $PKG..."
adb shell cmd package set-home-activity "$PKG/.SlideshowActivity" >/dev/null
# The screensaver has to stay on: the Portal's presence detection reports
# "someone is here" as an ambient-mode poke, which keeps a running screensaver
# alive but does not hold an awake screen on. Without one the screen goes dark
# on the plain inactivity timer even with somebody in the room.
adb shell settings put secure screensaver_components "$PKG/.SlideshowDreamService"
adb shell settings put secure screensaver_enabled 1

# sleep_timeout (how long after the Portal last saw someone the screen goes
# off) is a secure setting. This grant lets the app keep it at whatever the
# Slideshow tab says, which matters because the Portal resets it on its own.
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb shell appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW deny
adb shell am force-stop "$OVERLAY_PKG"
adb shell settings put global package_verifier_enable 0

echo
echo "home screen:      $(adb shell cmd package resolve-activity -a android.intent.action.MAIN \
  -c android.intent.category.HOME 2>/dev/null | sed -n 's/^ *name=//p' | head -1 | tr -d '\r')"
echo "screensaver:      $(adb shell settings get secure screensaver_components | tr -d '\r')"
echo "  enabled:        $(adb shell settings get secure screensaver_enabled | tr -d '\r')"
echo "screensaver after: $(adb shell settings get system screen_off_timeout | tr -d '\r') ms (screen_off_timeout)"
echo "screen off after:  $(adb shell settings get secure sleep_timeout | tr -d '\r') ms (sleep_timeout, since the Portal last saw someone)"
echo "bug pill overlay: $(adb shell appops get "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW | tr -d '\r' | head -1)"
echo "app verifier:     $(adb shell settings get global package_verifier_enable | tr -d '\r') (1 rejects apps not signed by Facebook)"
