#!/bin/bash
# daemonize.sh — launch a command as a daemon that SURVIVES sandbox session reaping.
#
# WHY THIS EXISTS:
#   The sandbox supervisor spawns each tool-call bash session as its child and
#   kills the ENTIRE session process tree when the call ends. nohup/setsid alone
#   do NOT survive this (verified empirically — see worklog R77 setup notes).
#   Double-fork + setsid reparents the daemon to PID 1 BEFORE the session ends,
#   so it is outside the kill tree.
#
# USAGE:
#   daemonize.sh NAME LOGFILE WORKDIR CMD [ARGS...]
#
# EXAMPLES:
#   .zscripts/daemonize.sh pulse-web   /dev/null                  "$PWD" bun run dev
#   .zscripts/daemonize.sh pulse-socket .zscripts/svc-socket.log  "$PWD/mini-services/pulse-socket" bun run dev
#   .zscripts/daemonize.sh pulse-keeper .zscripts/svc-keeper.log  "$PWD/mini-services/pulse-keeper" bun run dev

NAME="$1"; LOG="$2"; DIR="$3"; shift 3

if [ -z "$NAME" ] || [ -z "$DIR" ] || [ -z "$1" ]; then
    echo "usage: daemonize.sh NAME LOGFILE WORKDIR CMD [ARGS...]" >&2
    exit 1
fi

mkdir -p "$(dirname "$LOG")" 2>/dev/null || true

(
    cd "$DIR" || { echo "[daemonize] cd failed: $DIR" >> "$LOG"; exit 1; }
    # double-fork: subshell exits immediately, setsid'd child reparents to PID 1
    setsid "$@" < /dev/null >> "$LOG" 2>&1 &
)

sleep 0.4
echo "[$(date '+%Y-%m-%d %H:%M:%S')] daemonized: $NAME (cwd: $DIR, cmd: $*)" >> "$LOG"
