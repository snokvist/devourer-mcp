#!/usr/bin/env bash
# Start/stop/status for the local devourer-bridge.
#
# The socket lives in XDG_RUNTIME_DIR: it is per-user, tmpfs-backed, cleaned up
# at logout, and short enough for sun_path (108 bytes, which a path under a
# session scratch directory can blow past).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BIN="$ROOT/build/native-bridge/devourer-bridge"
SOCK="${DEVOURER_BRIDGE_SOCK:-${XDG_RUNTIME_DIR:-/tmp}/devourer-mcp/bridge.sock}"
PIDF="${XDG_RUNTIME_DIR:-/tmp}/devourer-mcp/bridge.pid"
LOG="${XDG_RUNTIME_DIR:-/tmp}/devourer-mcp/bridge.log"
mkdir -p "$(dirname "$PIDF")"

running() { [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }

case "${1:-status}" in
  start)
    running && { echo "already running (pid $(cat "$PIDF"))"; exit 0; }
    [ -x "$BIN" ] || { echo "not built: $BIN" >&2; exit 1; }
    sha=$(sed -n 's/^commit *= *//p' "$ROOT/vendor/DEVOURER_VERSION" 2>/dev/null || echo unknown)
    setsid "$BIN" --socket "$SOCK" --devourer-commit "$sha" \
      </dev/null >>"$LOG" 2>&1 &
    echo $! > "$PIDF"
    sleep 0.7
    running && echo "started pid $(cat "$PIDF") on $SOCK" || { echo "failed; see $LOG" >&2; tail -5 "$LOG" >&2; exit 1; }
    ;;
  stop)
    running || { echo "not running"; rm -f "$PIDF"; exit 0; }
    kill "$(cat "$PIDF")" 2>/dev/null || true
    for _ in $(seq 20); do running || break; sleep 0.1; done
    running && kill -9 "$(cat "$PIDF")" 2>/dev/null || true
    rm -f "$PIDF"; echo "stopped"
    ;;
  restart) "$0" stop; "$0" start ;;
  status)
    if running; then echo "running pid $(cat "$PIDF") sock $SOCK"; else echo "stopped"; fi ;;
  log) tail -n "${2:-40}" "$LOG" ;;
  *) echo "usage: $0 {start|stop|restart|status|log}" >&2; exit 2 ;;
esac
