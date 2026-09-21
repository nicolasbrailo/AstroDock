#!/usr/bin/env bash
# Pushes the app's settings to the device over adb, so they don't have to be
# typed on the touch screen. Only works with debug builds (it uses run-as).
#
# It reads what is on the device first and only replaces the settings named on
# the command line, so pushing one of them leaves the rest alone, including the
# ones this script knows nothing about (the screen tab). Use --reset to start
# from an empty file instead.
#
# The keys must match Settings.kt and mqtt/MqttSettings.kt.
set -euo pipefail

PKG=com.nicobrailo.astrodock
PREFS="shared_prefs/${PKG}_preferences.xml"

usage() {
  cat <<'EOF'
Usage: tools/push-config.sh [OPTION]...

  --server-url URL         Immich server, e.g. http://192.168.1.10:2283
  --api-key KEY            Immich API key
  --max-pictures N         At most N pictures per album (0: no limit)
  --percent N              Show N% of each album (0: all of it)
  --slide-seconds N        Seconds each picture stays on screen (5-300)

  Which albums the pictures come from (see AlbumFilter.kt). The patterns are
  comma separated and '*' and '?' are wildcards, matching the whole name:

  --album-include PATTERNS Only albums whose name matches, e.g. 'portalgo-*'
  --album-exclude PATTERNS Albums to leave out, checked after the above
  --album-from-year YEAR   Only albums with pictures from YEAR on (0: no limit)
  --album-to-year YEAR     Only albums with pictures up to YEAR (0: no limit)
  Pass '' to any of the four to drop that part of the filter.

  Where the device reports its state (see mqtt/MqttSettings.kt). The rest of
  the MQTT settings are only on the device's screen:

  Current temperature and sky over the clock, from Open-Meteo (no account
  needed, so there is only somewhere to report on):

  --weather BOOL           Show the weather at all: true or false
  --weather-place PLACE    A town or city, e.g. Amsterdam. If several share the
                           name, add a comma and the region or country:
                           'Springfield, Illinois'. Or coordinates: '52.37, 4.89'.
                           The Slideshow tab shows what it was found as.

  --mqtt-enabled BOOL      Report to the broker at all: true or false
  --mqtt-host HOST         Broker's address, e.g. 192.168.1.10
  --mqtt-port PORT         Broker's port (default 1883)

  --show                   Print the settings on the device and exit
  --reset                  Replace every setting, instead of editing what's there
EOF
}

# The settings to write, as parallel arrays: name, value, and "string", "int"
# or "bool" for how SettingsActivity stores it (a SeekBarPreference stores an
# int, a SwitchPreferenceCompat a boolean).
keys=()
values=()
types=()

set_pref() {
  keys+=("$1")
  values+=("$2")
  types+=("${3:-string}")
}

boolean() {
  local value=$1 name=$2
  [[ $value == true || $value == false ]] && return 0
  echo "$name must be true or false, not \"$value\"" >&2
  exit 1
}

number() {
  local value=$1 name=$2 low=$3 high=$4
  [[ $value =~ ^[0-9]+$ ]] && ((value >= low && value <= high)) && return 0
  echo "$name must be a number between $low and $high, not \"$value\"" >&2
  exit 1
}

show=0
reset=0
while [[ $# -gt 0 ]]; do
  # Every option but --show and --reset takes a value, so complain here rather
  # than silently swallowing the next option as one
  case $1 in
    --show | --reset | -h | --help) ;;
    *) [[ $# -ge 2 ]] || { echo "$1 needs a value" >&2; exit 1; } ;;
  esac
  case $1 in
    --server-url) set_pref server_url "$2"; shift 2 ;;
    --api-key) set_pref api_key "$2"; shift 2 ;;
    --max-pictures)
      number "$2" "$1" 0 100000; set_pref max_pictures_per_album "$2"; shift 2 ;;
    --percent) number "$2" "$1" 0 100; set_pref percent_of_album "$2"; shift 2 ;;
    --slide-seconds) number "$2" "$1" 5 300; set_pref slide_seconds "$2" int; shift 2 ;;
    --album-include) set_pref album_name_include "$2"; shift 2 ;;
    --album-exclude) set_pref album_name_exclude "$2"; shift 2 ;;
    --album-from-year) number "$2" "$1" 0 9999; set_pref album_from_year "$2"; shift 2 ;;
    --album-to-year) number "$2" "$1" 0 9999; set_pref album_to_year "$2"; shift 2 ;;
    --weather) boolean "$2" "$1"; set_pref weather_enabled "$2" bool; shift 2 ;;
    --weather-place) set_pref weather_place "$2"; shift 2 ;;
    --mqtt-enabled) boolean "$2" "$1"; set_pref mqtt_enabled "$2" bool; shift 2 ;;
    --mqtt-host) set_pref mqtt_host "$2"; shift 2 ;;
    --mqtt-port) number "$2" "$1" 1 65535; set_pref mqtt_port "$2"; shift 2 ;;
    --show) show=1; shift ;;
    --reset) reset=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      echo "(The settings used to be positional arguments; they are options now.)" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ((show == 0)) && ((${#keys[@]} == 0)); then
  echo "Nothing to push." >&2
  usage >&2
  exit 1
fi

# What the app has now, empty if it was never configured. adb shell hands back
# CRLF line endings, which would end up in the file we write.
fetch() {
  adb shell "run-as $PKG cat $PREFS 2>/dev/null" 2>/dev/null | tr -d '\r' || true
}

if ((show == 1)); then
  current=$(fetch)
  if [[ -z $current ]]; then
    echo "No settings on the device yet."
  else
    echo "$current"
  fi
  ((${#keys[@]} > 0)) || exit 0
fi

xml_escape() {
  sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' <<<"$1"
}

# The file to write: what the device has, with the settings we were given taken
# out and added back with their new values. Android writes one entry per line,
# so dropping the old ones by name is enough, and every setting this script
# doesn't know about is carried over untouched.
prefs() {
  local body=$1 key inserts=""

  if [[ -z $body ]] || ! grep -q '</map>' <<<"$body"; then
    # Nothing there yet, or the empty "<map />" Android writes for no settings
    body=$'<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n</map>'
  fi
  for key in "${keys[@]}"; do
    body=$(sed "/name=\"$key\"/d" <<<"$body")
  done

  local i
  for i in "${!keys[@]}"; do
    if [[ ${types[i]} == int ]]; then
      inserts+="    <int name=\"${keys[i]}\" value=\"${values[i]}\" />"$'\n'
    elif [[ ${types[i]} == bool ]]; then
      inserts+="    <boolean name=\"${keys[i]}\" value=\"${values[i]}\" />"$'\n'
    else
      inserts+="    <string name=\"${keys[i]}\">$(xml_escape "${values[i]}")</string>"$'\n'
    fi
  done

  # Through the environment, because awk -v would read backslashes in a value
  # as escapes
  INSERTS="$inserts" awk '/<\/map>/ { printf "%s", ENVIRON["INSERTS"] } { print }' <<<"$body"
}

if ((reset == 1)); then
  current=""
else
  current=$(fetch)
fi

# Stop the app first: it keeps the settings in memory and would overwrite the
# file with the old ones
adb shell am force-stop "$PKG"
prefs "$current" | adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > $PREFS'"
adb shell am start -n "$PKG/.SlideshowActivity" >/dev/null
echo "Pushed ${keys[*]} to $(adb shell getprop ro.product.model), app restarted"
