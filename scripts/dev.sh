#!/usr/bin/env bash
# One entry point for humans and AI agents. Every verb is non-interactive and exits non-zero on failure.
#
#   ./scripts/dev.sh test                 exchange-core unit tests (seconds, no Minecraft)
#   ./scripts/dev.sh sim [steps] [seed]   headless market simulation -> build/sim/diamond.csv
#   ./scripts/dev.sh build                build everything, including the mod jar
#   ./scripts/dev.sh gametest             server GameTests in a headless Minecraft server
#   ./scripts/dev.sh check                test + gametest (run before calling a change done)
#   ./scripts/dev.sh server start|stop|status
#   ./scripts/dev.sh cmd "<command>"      send a command to the running dev server via RCON
#   ./scripts/dev.sh logs [n]             tail the dev server log
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
GRADLE="./gradlew"
[ -x "$GRADLE" ] || GRADLE="gradle"

RUN_DIR="$ROOT/mod/run"
PID_FILE="$ROOT/build/dev-server.pid"
LOG_FILE="$ROOT/build/dev-server.log"
RCON_PASSWORD="${RCON_PASSWORD:-dev}"
RCON_PORT="${RCON_PORT:-25575}"

prepare_server_dir() {
  mkdir -p "$RUN_DIR" "$ROOT/build"
  echo "eula=true" > "$RUN_DIR/eula.txt"
  if [ ! -f "$RUN_DIR/server.properties" ]; then
    cat > "$RUN_DIR/server.properties" <<EOF
online-mode=false
enable-rcon=true
rcon.password=$RCON_PASSWORD
rcon.port=$RCON_PORT
level-type=minecraft\:flat
spawn-protection=0
view-distance=6
simulation-distance=6
EOF
  fi
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  test)
    "$GRADLE" :exchange-core:test --console=plain -q ;;
  sim)
    "$GRADLE" :sim:run --console=plain -q --args="${1:-500} ${2:-42} build/sim/diamond.csv" ;;
  build)
    "$GRADLE" build --console=plain ;;
  gametest)
    "$GRADLE" :mod:runGameTest --console=plain ;;
  check)
    "$0" test && "$0" gametest ;;
  server)
    sub="${1:-status}"
    case "$sub" in
      start)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then echo "already running"; exit 0; fi
        prepare_server_dir
        nohup "$GRADLE" :mod:runServer --console=plain > "$LOG_FILE" 2>&1 &
        echo $! > "$PID_FILE"
        echo "starting (pid $(cat "$PID_FILE")); waiting for 'Done'..."
        for _ in $(seq 1 180); do
          if grep -q "Done (" "$LOG_FILE" 2>/dev/null; then echo "server ready"; exit 0; fi
          if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then echo "server exited:"; tail -40 "$LOG_FILE"; exit 1; fi
          sleep 1
        done
        echo "timed out waiting for server"; tail -40 "$LOG_FILE"; exit 1 ;;
      stop)
        python3 "$ROOT/scripts/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" "stop" || true
        sleep 3
        [ -f "$PID_FILE" ] && kill "$(cat "$PID_FILE")" 2>/dev/null || true
        rm -f "$PID_FILE"; echo "stopped" ;;
      status)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then echo "running"; else echo "stopped"; fi ;;
      *) echo "usage: dev.sh server start|stop|status"; exit 2 ;;
    esac ;;
  cmd)
    python3 "$ROOT/scripts/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" "$@" ;;
  logs)
    tail -n "${1:-60}" "$LOG_FILE" ;;
  help|*)
    sed -n '2,12p' "$0" ;;
esac
