#!/bin/bash
# ==========================================
# WebUI Script - Start/Stop frontend dev server
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WEBUI_DIR="${PROJECT_ROOT}/harnax-webui"
source "${SCRIPT_DIR}/env.conf"

PID_FILE="${SCRIPT_DIR}/pids/webui.pid"
mkdir -p "${SCRIPT_DIR}/pids"

log() { echo -e "\033[36m[WEBUI]\033[0m $1"; }
ok()  { echo -e "\033[32m[  OK]\033[0m $1"; }
err() { echo -e "\033[31m[ ERR]\033[0m $1"; }

start_dev() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        ok "WebUI dev server already running (PID $(cat "$PID_FILE"))"
        return
    fi

    if ! command -v node &> /dev/null; then
        err "Node.js not found. Please install Node.js 18+."
        exit 1
    fi

    cd "$WEBUI_DIR"

    if [ ! -d "node_modules" ]; then
        log "Installing dependencies..."
        npm install --silent
    fi

    log "Starting dev server on port ${WEBUI_PORT}..."
    PORT=${WEBUI_PORT} npm run dev > "${SCRIPT_DIR}/logs/webui.log" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    sleep 3
    ok "WebUI dev server started (PID ${pid}, port ${WEBUI_PORT})"
    echo "  → http://localhost:${WEBUI_PORT}"
    echo "  Logs: tail -f ${SCRIPT_DIR}/logs/webui.log"
}

stop_dev() {
    if [ ! -f "$PID_FILE" ]; then
        log "WebUI is not running"
        return
    fi

    local pid
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
        log "Stopping WebUI dev server (PID ${pid})..."
        kill "$pid" 2>/dev/null
        # Kill child processes (vite/umi dev server)
        pkill -P "$pid" 2>/dev/null || true
        ok "WebUI stopped"
    else
        log "WebUI was not running (stale PID file)"
    fi
    rm -f "$PID_FILE"
}

show_status() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo -e "  WebUI: \033[32mRUNNING\033[0m (PID $(cat "$PID_FILE"), port ${WEBUI_PORT})"
    else
        echo -e "  WebUI: \033[31mSTOPPED\033[0m"
    fi
}

case "${1:-start}" in
    start)
        start_dev
        ;;
    stop)
        stop_dev
        ;;
    restart)
        stop_dev
        sleep 2
        start_dev
        ;;
    status)
        show_status
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac
