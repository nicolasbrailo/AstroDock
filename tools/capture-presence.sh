#!/usr/bin/env bash
# Records what the Portal does around presence, to find signals the launcher
# could use: screen on/off, screensaver (dream) start/stop, the top activity,
# the light sensor, and all logs and broadcasts. Read-only: changes nothing on
# the device.
#
# Usage: tools/capture-presence.sh OUT_DIR
# Runs until interrupted (Ctrl-C), then saves broadcast history and power state.
# Output:
#   OUT_DIR/logcat.txt       all log buffers
#   OUT_DIR/state.tsv        a line every time the polled state changes
#   OUT_DIR/broadcasts.txt   recent broadcast history (at the end)
#   OUT_DIR/power.txt        full power manager state (at the end)
set -uo pipefail

if [[ $# -ne 1 ]]; then
  sed -n 's/^# Usage: //p' "$0" >&2
  exit 1
fi
OUT=$1
mkdir -p "$OUT"

adb logcat -b all -v time > "$OUT/logcat.txt" &
LOGCAT_PID=$!

finish() {
  kill "$LOGCAT_PID" 2>/dev/null
  adb shell dumpsys activity broadcasts history > "$OUT/broadcasts.txt"
  adb shell dumpsys power > "$OUT/power.txt"
  echo "Saved to $OUT"
  exit 0
}
trap finish INT TERM

# One line describing the state that matters: wakefulness (Awake, Dreaming,
# Asleep), display power, the running dream, the top activity, and the newest
# light sensor reading (lux)
poll() {
  adb shell '
    p=$(dumpsys power)
    wake=$(echo "$p" | sed -n "s/^ *mWakefulness=//p")
    display=$(echo "$p" | sed -n "s/^Display Power: state=//p")
    reason=$(echo "$p" | sed -n "s/^ *mLastSleepReason=//p")
    dream=$(dumpsys dreams | sed -n "s/^mCurrentDreamName=ComponentInfo{\(.*\)}/\1/p")
    top=$(dumpsys activity activities | sed -n "s/.*mResumedActivity: ActivityRecord{[^ ]* [^ ]* \([^ ]*\).*/\1/p" | head -1)
    lux=$(dumpsys sensorservice | sed -n "/^light sensor: last/,/^[^\t]/p" | grep wall= | sort -t= -k3 | tail -1 | sed "s/.*) \([0-9.]*\),.*/\1/")
    printf "wake=%s\tdisplay=%s\tsleep_reason=%s\tdream=%s\ttop=%s\tlux=%s\n" "$wake" "$display" "$reason" "${dream:-none}" "$top" "$lux"
  ' | tr -d '\r'
}

last=""
last_lux=""
printf "time\tstate\n" > "$OUT/state.tsv"
while true; do
  state=$(poll)
  lux=${state##*lux=}
  without_lux=${state%	lux=*}
  # Lux changes constantly; only record it when it moves by more than 5
  lux_moved=0
  if [[ -n $lux && -n $last_lux ]]; then
    lux_moved=$(awk -v a="$lux" -v b="$last_lux" 'BEGIN { d = a - b; print (d > 5 || d < -5) ? 1 : 0 }')
  fi
  if [[ $without_lux != "$last" || $lux_moved == 1 || -z $last_lux ]]; then
    line="$(date +%H:%M:%S)	$state"
    echo "$line" | tee -a "$OUT/state.tsv"
    last=$without_lux
    last_lux=$lux
  fi
  sleep 2
done
