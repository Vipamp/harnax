#!/bin/bash
# ==========================================
# Services Script - Start/Stop backend Java services (本地模式)
# 不依赖 Docker / Redis / MinIO
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/env.conf"

LOG_DIR="${SCRIPT_DIR}/logs"
PID_DIR="${SCRIPT_DIR}/pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

log() { echo -e "\033[36m[SVC]\033[0m $1"; }
ok()  { echo -e "\033[32m[ OK]\033[0m $1"; }
err() { echo -e "\033[31m[ERR]\033[0m $1"; }

# 本地模式公共环境变量
COMMON_ENV="JWT_SECRET=${JWT_SECRET} ADMIN_INTERNAL_API_SECRET=${ADMIN_INTERNAL_API_SECRET} HARNAX_AUTH_SECRET=${HARNAX_AUTH_SECRET} SPRING_FLYWAY_ENABLED=true SPRING_FLYWAY_LOCATIONS=classpath:db/migration"

# 服务定义: name|jar_pattern|port|env_vars
SERVICES=(
    "admin|harnax-admin/target/harnax-admin-*.jar|${ADMIN_PORT}|${COMMON_ENV} SPRING_DATASOURCE_URL=${ADMIN_DB_URL} SPRING_DATASOURCE_USERNAME=${DB_USERNAME} SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD} SESSION_JDBC_URL=${ADMIN_DB_URL} SESSION_USERNAME=${DB_USERNAME} SESSION_PASSWORD=${DB_PASSWORD} HARNAX_ROUTER_URL=http://localhost:${ROUTER_PORT} HARNAX_SCHEDULER_URL=http://localhost:${SCHEDULER_PORT} HARNAX_ROUTER_EXTERNAL_URL=http://localhost:${ROUTER_PORT}"

    "router|harnax-session-router/target/harnax-session-router-*.jar|${ROUTER_PORT}|CACHE_TYPE=${CACHE_TYPE} ROUTER_DB_URL=${ROUTER_DB_URL} ROUTER_DB_DRIVER=${ROUTER_DB_DRIVER} ADMIN_SERVICE_URL=http://localhost:${ADMIN_PORT} ADMIN_INTERNAL_API_SECRET=${ADMIN_INTERNAL_API_SECRET} HARNAX_AUTH_SECRET=${HARNAX_AUTH_SECRET} ROUTER_CORS_ALLOWED_ORIGINS=http://localhost:*"

    "agent|harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar|${AGENT_SERVICE_PORT}|${COMMON_ENV} SPRING_DATASOURCE_URL=${AGENT_DB_URL} SPRING_DATASOURCE_USERNAME=${DB_USERNAME} SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD} SESSION_JDBC_URL=${AGENTSCOPE_DB_URL} SESSION_USERNAME=${DB_USERNAME} SESSION_PASSWORD=${DB_PASSWORD} ADMIN_INTERNAL_API_SECRET=${ADMIN_INTERNAL_API_SECRET} HARNAX_AUTH_SECRET=${HARNAX_AUTH_SECRET} HARNAX_ADMIN_URL=http://localhost:${ADMIN_PORT} HARNAX_ROUTER_URL=http://localhost:${ROUTER_PORT} HARNESS_MINIO_ENABLED=${MINIO_ENABLED} HARNESS_SANDBOX_ENABLED=${SANDBOX_ENABLED}"
)

get_jar() {
    local pattern="$1"
    local jar
    jar=$(ls -t ${PROJECT_ROOT}/${pattern} 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        err "JAR not found: ${pattern}"
        err "Run './build.sh' first"
        exit 1
    fi
    echo "$jar"
}

# Start a single service
start_service() {
    local name="$1" jar_pattern="$2" port="$3" env_vars="$4"

    local pid_file="${PID_DIR}/${name}.pid"
    if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
        ok "${name} already running (PID $(cat "$pid_file"))"
        return
    fi

    local jar
    jar=$(get_jar "$jar_pattern")

    log "Starting ${name} on port ${port}..."

    # Build env export array
    local export_env=()
    IFS=$' \n\t' read -ra PAIRS <<< "$env_vars"
    for pair in "${PAIRS[@]}"; do
        if [[ "$pair" == *"="* && -n "${pair%%=*}" ]]; then
            export_env+=("$pair")
        fi
    done

    # Create temp dir for router SQLite
    if [ "$name" = "router" ]; then
        mkdir -p "${PROJECT_ROOT}/tmp/harnax-router"
    fi

    # Start with env vars (using array for safe quoting)
    env "${export_env[@]}" java \
        ${JVM_OPTS} \
        -Dserver.port="${port}" \
        -jar "${jar}" \
        > "${LOG_DIR}/${name}.log" 2>&1 &

    local pid=$!
    echo "$pid" > "$pid_file"
    ok "${name} started (PID ${pid}, port ${port})"
}

# Stop a single service
stop_service() {
    local name="$1"
    local pid_file="${PID_DIR}/${name}.pid"

    if [ ! -f "$pid_file" ]; then
        log "${name} is not running"
        return
    fi

    local pid
    pid=$(cat "$pid_file")

    if kill -0 "$pid" 2>/dev/null; then
        log "Stopping ${name} (PID ${pid})..."
        kill "$pid"
        local retries=15
        while [ $retries -gt 0 ] && kill -0 "$pid" 2>/dev/null; do
            sleep 1
            retries=$((retries - 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid"
        fi
        ok "${name} stopped"
    else
        log "${name} was not running (stale PID)"
    fi
    rm -f "$pid_file"
}

# Start all (dependency order: admin → router → agent → channel → scheduler)
start_all() {
    log "Starting all services (local mode)..."
    echo ""
    for svc in "${SERVICES[@]}"; do
        IFS='|' read -r name jar port env_vars <<< "$svc"
        start_service "$name" "$jar" "$port" "$env_vars"
        sleep 3
    done
    echo ""
    show_status
    echo "  Logs: tail -f ${LOG_DIR}/*.log"
}

# Stop all (reverse order)
stop_all() {
    log "Stopping all services..."
    for ((i=${#SERVICES[@]}-1; i>=0; i--)); do
        IFS='|' read -r name _ _ _ <<< "${SERVICES[$i]}"
        stop_service "$name"
    done
}

# Show status
show_status() {
    echo ""
    echo "============================================"
    echo "  Harnax Services (Local Mode)"
    echo "============================================"
    for svc in "${SERVICES[@]}"; do
        IFS='|' read -r name _ port _ <<< "$svc"
        local pid_file="${PID_DIR}/${name}.pid"
        if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
            printf "  %-12s :%-5s \033[32mRUNNING\033[0m (PID %s)\n" "$name" "$port" "$(cat "$pid_file")"
        else
            printf "  %-12s :%-5s \033[31mSTOPPED\033[0m\n" "$name" "$port"
        fi
    done
    echo "============================================"
    echo ""
}

# Tail logs
tail_logs() {
    local target="${1:-all}"
    if [ "$target" = "all" ]; then
        tail -f "${LOG_DIR}"/*.log
    else
        tail -f "${LOG_DIR}/${target}.log"
    fi
}

# Main
case "${1:-start}" in
    start)       start_all ;;
    stop)        stop_all ;;
    restart)     stop_all; sleep 2; start_all ;;
    status)      show_status ;;
    logs)        tail_logs "$2" ;;
    start-one)
        [ -z "$2" ] && { err "Usage: $0 start-one <admin|router|agent|channel|scheduler>"; exit 1; }
        for svc in "${SERVICES[@]}"; do
            IFS='|' read -r name jar port env_vars <<< "$svc"
            if [ "$name" = "$2" ]; then start_service "$name" "$jar" "$port" "$env_vars"; exit 0; fi
        done
        err "Unknown service: $2 (available: admin, router, agent, channel, scheduler)"
        ;;
    stop-one)
        [ -z "$2" ] && { err "Usage: $0 stop-one <name>"; exit 1; }
        stop_service "$2"
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status|logs|start-one|stop-one}"
        echo ""
        echo "  start              Start all services"
        echo "  stop               Stop all services"
        echo "  restart            Restart all"
        echo "  status             Show status"
        echo "  logs [service]     Tail logs"
        echo "  start-one <name>   Start single service"
        echo "  stop-one <name>    Stop single service"
        echo ""
        echo "  Services: admin, router, agent, channel, scheduler"
        exit 1
        ;;
esac
