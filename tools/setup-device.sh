#!/usr/bin/env bash
# Sets the Portal up for astrodock: everything an app can't do for itself.
# Safe to run again at any time; it only sets values, and prints what the
# device looks like afterwards.
#
# Usage: tools/setup-device.sh
#
# It makes astrodock the home screen and the screensaver, lets it write the
# secure setting that decides when the screen switches off, hides the Portal's
# floating bug-report pill, turns off the Portal's app verifier, which only
# accepts apps signed by Facebook and fails every other install with "App
# certificate rejected" (adb installs are never verified), makes the system's
# install dialog readable again (see below), and grants the notification access
# the media controls need and the device admin that turns the screen off at
# night, which on the Portal only adb can do (see below). It also grants the
# permissions the System tab could ask for on the device (changing system
# settings, drawing over other apps, installing apps), so one run leaves that
# tab with nothing missing.
# To undo any of it:
#
#   adb shell cmd package set-home-activity com.facebook.alohaapps.launcher
#   adb shell pm revoke com.nicobrailo.astrodock android.permission.WRITE_SECURE_SETTINGS
#   adb shell settings put secure screensaver_components \
#     com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
#   adb shell appops set com.facebook.aloha.system.services SYSTEM_ALERT_WINDOW allow
#   adb shell settings put global package_verifier_enable 1
#   adb shell cmd overlay enable com.facebook.aloha.rro.niu.android
#   adb shell settings put secure high_text_contrast_enabled 0
#   adb shell cmd notification disallow_listener \
#     com.nicobrailo.astrodock/com.nicobrailo.astrodock.media.MediaListenerService
#   adb shell appops set com.nicobrailo.astrodock WRITE_SETTINGS default
#   adb shell appops set com.nicobrailo.astrodock SYSTEM_ALERT_WINDOW default
#   adb shell appops set com.nicobrailo.astrodock REQUEST_INSTALL_PACKAGES default
#
# The device admin can't be removed with dpm (only a testOnly one can);
# tools/force-uninstall.sh gets rid of it along with the app.
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
# The Portal's own theme for the framework. It paints the system package
# installer's text in the same colour as the surface behind it: the title and
# the question come out dark on its dark header, and Cancel/Install come out
# white on the white button bar. The dialog still works and accessibility still
# reads it, but on screen it is a blank white page, which is what installing
# anything from F-Droid, or from the app's own Apps tab, looks like. Measured on
# the device: counting the colours in the button strip gives exactly one before
# this, and the stock dialog after. It targets the `android` package, so
# dropping it rethemes every app that uses DeviceDefault, not just the
# installer.
THEME_RRO=com.facebook.aloha.rro.niu.android

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
# Notification access, which is what lets the slideshow read and control what
# another app is playing. The System tab has a button for it, but the screen it
# opens (com.android.settings/.Settings$NotificationAccessSettingsActivity)
# closes itself the instant it appears on the Portal, so nothing on the device
# can grant this. Measured: it starts, resumes, and is gone ~40ms later, with
# no exception and no permission denial, whether or not astrodock is running;
# the screensaver and overlay screens in the same app stay up fine.
adb shell cmd notification allow_listener \
  "$PKG/com.nicobrailo.astrodock.media.MediaListenerService"
# The device admin, which is what lets the night rule turn the screen off. The
# System tab's button opens the system's dialog, but on the Portal that says
# "Device management policies are not supported" and activates nothing, while
# the same grant from adb works. Setting it again when it is already active is
# harmless.
adb shell dpm set-active-admin "$PKG/.ScreenAdminReceiver" >/dev/null
# These three have screens on the device, but granting them here saves the
# taps. They are app-ops, which is what those screens set.
adb shell appops set "$PKG" WRITE_SETTINGS allow
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow

adb shell appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW deny
adb shell am force-stop "$OVERLAY_PKG"
adb shell settings put global package_verifier_enable 0

# The overlay is what actually fixes the colours; high contrast text is the belt
# to its braces, since it draws every string with a contrasting outline, so no
# theme can make text invisible again. Both are set because the Portal may well
# put its own overlay back on boot, and then the outline is all that is left.
adb shell cmd overlay disable "$THEME_RRO"
adb shell settings put secure high_text_contrast_enabled 1

echo
echo "home screen:      $(adb shell cmd package resolve-activity -a android.intent.action.MAIN \
  -c android.intent.category.HOME 2>/dev/null | sed -n 's/^ *name=//p' | head -1 | tr -d '\r')"
echo "screensaver:      $(adb shell settings get secure screensaver_components | tr -d '\r')"
echo "  enabled:        $(adb shell settings get secure screensaver_enabled | tr -d '\r')"
echo "screensaver after: $(adb shell settings get system screen_off_timeout | tr -d '\r') ms (screen_off_timeout)"
echo "screen off after:  $(adb shell settings get secure sleep_timeout | tr -d '\r') ms (sleep_timeout, since the Portal last saw someone)"
echo "bug pill overlay: $(adb shell appops get "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW | tr -d '\r' | head -1)"
echo "app verifier:     $(adb shell settings get global package_verifier_enable | tr -d '\r') (1 rejects apps not signed by Facebook)"
echo "portal theme:     $(adb shell cmd overlay list | tr -d '\r' | grep "$THEME_RRO") ([x] hides the install dialog's text)"
echo "high contrast:    $(adb shell settings get secure high_text_contrast_enabled | tr -d '\r') (1 outlines every string, so none can vanish)"
echo "screen off admin: $(adb shell dumpsys device_policy | tr -d '\r' \
  | grep -q "$PKG/.ScreenAdminReceiver" && echo "granted" || echo "MISSING") (night screen off)"
for op in WRITE_SETTINGS SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES; do
  printf '%-18s%s\n' "$op:" "$(adb shell appops get "$PKG" "$op" | tr -d '\r' | head -1)"
done
echo "media controls:   $(adb shell settings get secure enabled_notification_listeners \
  | tr -d '\r' | grep -q "$PKG" && echo "granted" || echo "MISSING") (notification access)"
