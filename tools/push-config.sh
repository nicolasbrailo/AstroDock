#!/usr/bin/env bash
# Pushes the app's settings to the device over adb, so they don't have to be
# typed on the touch screen. Only works with debug builds (it uses run-as).
#
# Usage: tools/push-config.sh SERVER_URL API_KEY [MAX_PICTURES [PERCENT [SLIDE_SECONDS]]]
#   e.g. tools/push-config.sh http://192.168.1.10:2283 abc123
# The optional settings default to 20, 0 and 30.
#
# This replaces all the settings on the device. The keys must match Settings.kt.
set -euo pipefail

if [[ $# -lt 2 || $# -gt 5 ]]; then
  sed -n 's/^# Usage: //p' "$0" >&2
  exit 1
fi

PKG=com.nicobrailo.astrodock
SERVER_URL=$1
API_KEY=$2
MAX_PICTURES=${3:-20}
PERCENT=${4:-0}
SLIDE_SECONDS=${5:-30}
for n in "$MAX_PICTURES" "$PERCENT" "$SLIDE_SECONDS"; do
  [[ $n =~ ^[0-9]+$ ]] || { echo "Not a number: $n" >&2; exit 1; }
done

xml_escape() {
  sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' <<<"$1"
}

prefs() {
  echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
  echo "<map>"
  echo "    <string name=\"server_url\">$(xml_escape "$SERVER_URL")</string>"
  echo "    <string name=\"api_key\">$(xml_escape "$API_KEY")</string>"
  echo "    <string name=\"max_pictures_per_album\">$MAX_PICTURES</string>"
  echo "    <string name=\"percent_of_album\">$PERCENT</string>"
  echo "    <string name=\"slide_seconds\">$SLIDE_SECONDS</string>"
  echo "</map>"
}

# Stop the app first: it keeps the settings in memory and would overwrite the
# file with the old ones
adb shell am force-stop "$PKG"
prefs | adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/${PKG}_preferences.xml'"
adb shell am start -n "$PKG/.SlideshowActivity" >/dev/null
echo "Settings pushed to $(adb shell getprop ro.product.model), app restarted"
