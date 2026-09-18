#!/usr/bin/env bash
# Sets up the Portal for alauncher, for the parts an app can't do itself.
# Everything else (home screen, device admin, system settings) is granted from
# the System tab of the app's settings.
#
# Usage: tools/setup-device.sh COMMAND [ARGS]
#   status                 show what is set up now
#   screensaver on|off     use alauncher's slideshow as the screensaver, or
#                          restore the Portal's
#   sleep-timeout SECONDS  how long the Portal waits after it last saw someone
#                          before turning the screen off (secure setting, adb
#                          only; the Portal's default is 1200)
#   home on|off            make alauncher the home screen, or restore the
#                          Portal's launcher
#   bugnub on|off          show or hide the Portal's floating bug-report pill
set -euo pipefail

PKG=com.nicobrailo.alauncher
OUR_DREAM="$PKG/.SlideshowDreamService"
OUR_HOME="$PKG/.SlideshowActivity"
PORTAL_DREAM=com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeDreamService
PORTAL_HOME=com.facebook.alohaapps.launcher
# Draws the floating bug-report pill
OVERLAY_PKG=com.facebook.aloha.system.services

usage() {
  sed -n 's/^# //p; s/^#$//p' "$0" | sed -n '/^Usage:/,$p' >&2
  exit 1
}

status() {
  echo "home screen:  $(adb shell cmd package resolve-activity -a android.intent.action.MAIN \
    -c android.intent.category.HOME 2>/dev/null | sed -n 's/^ *name=//p' | head -1 | tr -d '\r')"
  echo "screensaver:  $(adb shell settings get secure screensaver_components | tr -d '\r')"
  echo "  enabled:    $(adb shell settings get secure screensaver_enabled | tr -d '\r')"
  echo "screen off after: $(adb shell settings get system screen_off_timeout | tr -d '\r') ms (screensaver starts)"
  echo "sleep after:      $(adb shell settings get secure sleep_timeout | tr -d '\r') ms (since the Portal last saw someone)"
  echo "bug pill overlay: $(adb shell appops get "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW | tr -d '\r' | head -1)"
}

[[ $# -ge 1 ]] || usage
case "$1" in
  status)
    status
    ;;
  screensaver)
    [[ $# -eq 2 ]] || usage
    case "$2" in
      on)  adb shell settings put secure screensaver_components "$OUR_DREAM"
           adb shell settings put secure screensaver_enabled 1 ;;
      off) adb shell settings put secure screensaver_components "$PORTAL_DREAM" ;;
      *)   usage ;;
    esac
    status
    ;;
  sleep-timeout)
    [[ $# -eq 2 && $2 =~ ^[0-9]+$ ]] || usage
    adb shell settings put secure sleep_timeout $(( $2 * 1000 ))
    status
    ;;
  bugnub)
    [[ $# -eq 2 ]] || usage
    # The pill is an overlay window (BugnubPillViewService) drawn by a Portal
    # system package, and there is no setting for it, so the overlay permission
    # is taken away instead. The package has to be restarted to drop the window
    # it already has.
    case "$2" in
      on)  adb shell appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW allow ;;
      off) adb shell appops set "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW deny ;;
      *)   usage ;;
    esac
    adb shell am force-stop "$OVERLAY_PKG"
    echo "bug pill: $2 (overlays for $OVERLAY_PKG are now $(adb shell appops get "$OVERLAY_PKG" SYSTEM_ALERT_WINDOW | tr -d '\r'))"
    ;;
  home)
    [[ $# -eq 2 ]] || usage
    case "$2" in
      on)  adb shell cmd package set-home-activity "$OUR_HOME" ;;
      off) adb shell cmd package set-home-activity "$PORTAL_HOME" ;;
      *)   usage ;;
    esac
    status
    ;;
  *)
    usage
    ;;
esac
