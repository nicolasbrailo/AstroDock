#!/usr/bin/env bash
# Records what the AstroDock Portals (and anything else on the broker using the
# same topic layout) publish, one file per day, so a night that went wrong can
# be read back the next morning. Meant to run on the broker's host, which
# doesn't sleep: a recorder on a desktop goes blind when it suspends.
#
#   record-mqtt.sh [-h HOST] [-p PORT] [-d DIR] [-k DAYS] [PREFIX]...
#
# PREFIX is a device's topic prefix without the slash (portaloft); none means
# every device. Each line is "ISO time, topic, payload". It records each
# device's state, availability and displayed photo (the photo shows whether the
# pictures kept moving, which they don't under the night cover), and the
# commands sent to it, except the homeboard's SVG overlays, which are large and
# come several times a minute. Files older than DAYS days are deleted.
#
# Run it under nohup, tmux or a systemd user service, e.g.
#   nohup ~/tools/record-mqtt.sh -d ~/mqtt-log portaloft >/dev/null 2>&1 &
# and read a night with
#   grep ' portaloft/state ' ~/mqtt-log/mqtt-2026-11-04.log | cut -c1-400
set -euo pipefail

HOST=10.0.0.10
PORT=4500
DIR="$HOME/mqtt-log"
KEEP_DAYS=14

while getopts "h:p:d:k:" opt; do
    case "$opt" in
        h) HOST="$OPTARG" ;;
        p) PORT="$OPTARG" ;;
        d) DIR="$OPTARG" ;;
        k) KEEP_DAYS="$OPTARG" ;;
        *) sed -n '2,20p' "$0"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
PREFIXES=("$@")
[ ${#PREFIXES[@]} -eq 0 ] && PREFIXES=("+")

mkdir -p "$DIR"

TOPICS=()
for p in "${PREFIXES[@]}"; do
    TOPICS+=(-t "$p/state" -t "$p/availability" -t "$p/state/displayed_photo" -t "$p/cmd/#"
             -T "$p/cmd/ambience/set_svg_overlay")
done

# mosquitto_sub reconnects by itself, but exits if the broker can't be reached
# when it starts, so it is started again until this script is stopped
while true; do
    echo "$(date -u +%Y-%m-%dT%H:%M:%S+0000) recorder started on $HOST:$PORT for ${PREFIXES[*]}" \
        >> "$DIR/mqtt-$(date -u +%F).log"
    # Lines go to the file of the day they arrived, so a file is one UTC day,
    # and the old ones are cleared out as each new day starts
    mosquitto_sub -h "$HOST" -p "$PORT" -F '%I %t %p' "${TOPICS[@]}" |
        while IFS= read -r line; do
            day="${line:0:10}"
            if [ "$day" != "${last_day:-}" ]; then
                find "$DIR" -name 'mqtt-*.log' -mtime +"$KEEP_DAYS" -delete
                last_day="$day"
            fi
            printf '%s\n' "$line" >> "$DIR/mqtt-$day.log"
        done || true
    echo "$(date -u +%Y-%m-%dT%H:%M:%S+0000) recorder lost the broker, retrying in 10s" \
        >> "$DIR/mqtt-$(date -u +%F).log"
    sleep 10
done
