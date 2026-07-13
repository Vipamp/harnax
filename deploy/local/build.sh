#!/bin/bash
# ==========================================
# Build Script - Compile all backend modules and frontend
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
source "${SCRIPT_DIR}/env.conf"

log() { echo -e "\033[36m[BUILD]\033[0m $1"; }
ok()  { echo -e "\033[32m[  OK]\033[0m $1"; }
err() { echo -e "\033[31m[ ERR]\033[0m $1"; }

# Check Java
check_java() {
    if ! command -v java &> /dev/null; then
        err "Java not found. Please install JDK 21+."
        exit 1
    fi
    local version
    version=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$version" -lt 21 ] 2>/dev/null; then
        err "Java 21+ required, found Java ${version}"
        exit 1
    fi
    ok "Java ${version} detected"
}

# Check Maven
check_maven() {
    if ! command -v mvn &> /dev/null; then
        err "Maven not found. Please install Maven 3.9+."
        exit 1
    fi
    ok "Maven $(mvn --version 2>/dev/null | head -1 | awk '{print $3}')"
}

# Check Node.js
check_node() {
    if ! command -v node &> /dev/null; then
        log "Node.js not found, skipping frontend build"
        return 1
    fi
    ok "Node.js $(node --version)"
    return 0
}

# Build backend
build_backend() {
    log "Building backend modules..."
    cd "$PROJECT_ROOT"

    local mvn_cmd="mvn clean package ${MAVEN_OPTS}"

    if [ "$1" = "fast" ]; then
        mvn_cmd="${mvn_cmd} -DskipTests -Dmaven.javadoc.skip=true"
        log "Fast build mode (skipping tests)"
    fi

    eval "$mvn_cmd"
    ok "Backend build complete"

    echo ""
    log "Built artifacts:"
    for jar in \
        harnax-admin/target/harnax-admin-*.jar \
        harnax-session-router/target/harnax-session-router-*.jar \
        harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar \
        harnax-channel/harnax-channel-service/target/harnax-channel-service-*.jar \
        harnax-scheduler/target/harnax-scheduler-*.jar; do
        if [ -f "${PROJECT_ROOT}/${jar}" ]; then
            local size
            size=$(du -h "$jar" | cut -f1)
            printf "  %-65s %s\n" "$jar" "$size"
        fi
    done
    echo ""
}

# Build frontend
build_frontend() {
    if ! check_node; then
        return
    fi

    log "Building frontend (harnax-webui)..."
    cd "${PROJECT_ROOT}/harnax-webui"

    if [ ! -d "node_modules" ]; then
        log "Installing npm dependencies..."
        npm install --silent
    fi

    npm run build
    ok "Frontend build complete → harnax-webui/dist/"
}

# Main
case "${1:-all}" in
    all)
        check_java
        check_maven
        build_backend
        build_frontend
        ;;
    backend|be)
        check_java
        check_maven
        build_backend "$2"
        ;;
    frontend|fe)
        build_frontend
        ;;
    fast)
        check_java
        check_maven
        build_backend fast
        build_frontend
        ;;
    *)
        echo "Usage: $0 {all|backend|frontend|fast} [fast]"
        echo ""
        echo "  all       Build backend + frontend (default)"
        echo "  backend   Build backend only (add 'fast' to skip tests)"
        echo "  frontend  Build frontend only"
        echo "  fast      Build all with tests skipped"
        exit 1
        ;;
esac
