#!/bin/bash
# ==========================================
# One-Click Local Deploy (本地模式，无 Docker)
# check MySQL → build → start services → start webui
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/env.conf"

log() { echo -e "\n\033[36m══════════════════════════════════════════\033[0m"; echo -e "\033[36m  $1\033[0m"; echo -e "\033[36m══════════════════════════════════════════\033[0m\n"; }
ok()  { echo -e "\033[32m✓ $1\033[0m"; }

MODE="${1:-all}"

print_banner() {
    echo ""
    echo "  ╦ ╦╔═╗╦═╗╔╗╔╔═╗╔═╗╔═╗"
    echo "  ╠═╣╠═╣╠╦╝║║║╠═╣╠╣ ╚═╗"
    echo "  ╩ ╩╩ ╩╩╚═╝╚╝╩ ╩╚  ╚═╝  Local Dev"
    echo ""
    echo "  Mode: ${MODE}"
    echo "  Time: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
}

step_check() {
    log "Step 1/4: Checking Local Environment"

    # Check Java
    if ! command -v java &>/dev/null; then
        echo -e "\033[31m✗ Java not found. Install JDK 21+\033[0m"
        exit 1
    fi
    ok "Java $(java -version 2>&1 | head -1 | cut -d'"' -f2)"

    # Check Maven
    if ! command -v mvn &>/dev/null; then
        echo -e "\033[31m✗ Maven not found. Install Maven 3.9+\033[0m"
        exit 1
    fi
    ok "Maven $(mvn --version 2>/dev/null | head -1 | awk '{print $3}')"

    # Check MySQL
    bash "${SCRIPT_DIR}/infra.sh" check || exit 1

    # Check Node (optional)
    if command -v node &>/dev/null; then
        ok "Node.js $(node --version)"
    else
        echo -e "\033[33m⚠ Node.js not found (frontend will be skipped)\033[0m"
    fi
}

step_build() {
    log "Step 2/4: Building Project"
    local build_mode="all"
    [ "$MODE" = "fast" ] && build_mode="fast"
    bash "${SCRIPT_DIR}/build.sh" "$build_mode"
    ok "Build complete"
}

step_services() {
    log "Step 3/4: Starting Backend Services"
    bash "${SCRIPT_DIR}/services.sh" start
    ok "Backend services ready"
}

step_webui() {
    log "Step 4/4: Starting Frontend"
    if command -v node &>/dev/null; then
        bash "${SCRIPT_DIR}/webui.sh" start
        ok "Frontend ready"
    else
        echo -e "\033[33m⚠ Skipped (Node.js not installed)\033[0m"
    fi
}

print_summary() {
    echo ""
    echo "  ╔════════════════════════════════════════╗"
    echo "  ║       🎉 All Services Running         ║"
    echo "  ╠════════════════════════════════════════╣"
    echo "  ║                                        ║"
    echo "  ║  Frontend:  http://localhost:${WEBUI_PORT}      ║"
    echo "  ║  Admin API: http://localhost:${ADMIN_PORT}      ║"
    echo "  ║  Router:    http://localhost:${ROUTER_PORT}      ║"
    echo "  ║  Agent Svc: http://localhost:${AGENT_SERVICE_PORT}      ║"
    echo "  ║  Channel:   http://localhost:${CHANNEL_SERVICE_PORT}      ║"
    echo "  ║  Scheduler: http://localhost:${SCHEDULER_PORT}      ║"
    echo "  ║                                        ║"
    echo "  ║  MySQL:  ${MYSQL_HOST}:${MYSQL_PORT}                ║"
    echo "  ║  Redis:  not needed (local cache)     ║"
    echo "  ║  MinIO:  not needed (local fs)        ║"
    echo "  ║                                        ║"
    echo "  ╚════════════════════════════════════════╝"
    echo ""
    echo "  Quick commands:"
    echo "    ./deploy-all.sh stop       Stop everything"
    echo "    ./services.sh logs         Tail all logs"
    echo "    ./services.sh logs agent   Tail agent logs"
    echo "    ./services.sh status       Check status"
    echo ""
}

stop_all() {
    log "Stopping all services..."
    bash "${SCRIPT_DIR}/webui.sh" stop 2>/dev/null || true
    bash "${SCRIPT_DIR}/services.sh" stop
    echo ""
    ok "All services stopped"
}

print_banner

case "$MODE" in
    all|fast)
        step_check
        step_build
        step_services
        step_webui
        print_summary
        ;;
    check)
        step_check
        ;;
    build)
        step_build
        ;;
    services)
        step_services
        ;;
    webui)
        step_webui
        ;;
    stop)
        stop_all
        ;;
    restart)
        stop_all
        sleep 2
        step_services
        step_webui
        print_summary
        ;;
    *)
        echo "Usage: $0 {all|fast|check|build|services|webui|stop|restart}"
        echo ""
        echo "  all       Full deploy: check → build → services → webui"
        echo "  fast      Same as 'all' but skip tests"
        echo "  check     Check environment only"
        echo "  build     Build only"
        echo "  services  Start backend services only"
        echo "  webui     Start frontend only"
        echo "  stop      Stop everything"
        echo "  restart   Restart services (no rebuild)"
        exit 1
        ;;
esac
