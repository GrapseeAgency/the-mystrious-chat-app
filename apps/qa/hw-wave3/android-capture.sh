#!/usr/bin/env bash
# Pulse W3-HW — Android call-path log capture.
# Usage: ./android-capture.sh start|mark <text>|stop
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/logs"
PKG="${PULSE_PKG:-app.pulse.chat}"
mkdir -p "$DIR"
case "${1:-}" in
  start)
    adb logcat -c
    PID="$(adb shell pidof -s "$PKG" | tr -d '[:space:]')"
    adb logcat -v time --pid="${PID:?app $PKG not running - launch it first}" \
      > "$DIR/android-$(date +%Y%m%d-%H%M%S).log" &
    echo $! > "$DIR/.capture.pid"
    echo "capturing pid=$PID -> $DIR"
    ;;
  mark)
    adb logcat -v time -e "PULSE-MARK: ${2:-mark}" -d > /dev/null 2>&1 || true
    echo "PULSE-MARK: ${2:-mark} $(date +%H:%M:%S)" >> "$(ls -t "$DIR"/android-*.log | head -1)"
    ;;
  stop)
    kill "$(cat "$DIR/.capture.pid")" 2>/dev/null || true
    rm -f "$DIR/.capture.pid"
    echo "stopped. files:"; ls -t "$DIR"/android-*.log | head -3
    ;;
  *) echo "usage: $0 start | mark <text> | stop"; exit 1 ;;
esac
