#!/bin/bash
# ==========================================
# Infrastructure Check Script (本地模式，无 Docker)
# 检查并初始化本地 MySQL 数据库
# ==========================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/env.conf"

log() { echo -e "\033[36m[INFRA]\033[0m $1"; }
ok()  { echo -e "\033[32m[  OK]\033[0m $1"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $1"; }
err() { echo -e "\033[31m[ ERR]\033[0m $1"; }

# 检查 MySQL 是否可用
check_mysql() {
    if command -v mysql &> /dev/null; then
        if mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${DB_USERNAME}" -p"${DB_PASSWORD}" -e "SELECT 1" &>/dev/null; then
            ok "MySQL is running (${MYSQL_HOST}:${MYSQL_PORT})"
            return 0
        fi
    fi

    # 尝试 mysqladmin
    if command -v mysqladmin &> /dev/null; then
        if mysqladmin -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${DB_USERNAME}" -p"${DB_PASSWORD}" ping &>/dev/null; then
            ok "MySQL is running (${MYSQL_HOST}:${MYSQL_PORT})"
            return 0
        fi
    fi

    err "Cannot connect to MySQL at ${MYSQL_HOST}:${MYSQL_PORT}"
    echo ""
    echo "  本地模式需要 MySQL 8.0+，请按以下方式安装："
    echo ""
    echo "  macOS:"
    echo "    brew install mysql"
    echo "    brew services start mysql"
    echo "    mysql_secure_installation"
    echo ""
    echo "  Ubuntu/Debian:"
    echo "    sudo apt update && sudo apt install mysql-server"
    echo "    sudo systemctl start mysql"
    echo ""
    echo "  Windows:"
    echo "    scoop install mysql  (或从 https://dev.mysql.com 下载)"
    echo ""
    echo "  安装后请修改 env.conf 中的 DB_USERNAME/DB_PASSWORD"
    echo ""
    return 1
}

# 初始化数据库（创建库和用户）
init_databases() {
    log "Initializing databases..."

    local init_sql="${SCRIPT_DIR}/init-db.sql"
    if [ ! -f "$init_sql" ]; then
        err "init-db.sql not found"
        exit 1
    fi

    mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${DB_USERNAME}" -p"${DB_PASSWORD}" < "$init_sql" 2>/dev/null
    ok "Databases initialized: ${ADMIN_DB_NAME}, ${AGENT_DB_NAME}, ${AGENTSCOPE_DB_NAME}"
}

# 检查数据库是否已存在
check_databases() {
    local dbs=("${ADMIN_DB_NAME}" "${AGENT_DB_NAME}" "${AGENTSCOPE_DB_NAME}")
    local missing=()

    for db in "${dbs[@]}"; do
        if mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${DB_USERNAME}" -p"${DB_PASSWORD}" -e "USE ${db}" &>/dev/null; then
            ok "Database '${db}' exists"
        else
            missing+=("$db")
        fi
    done

    if [ ${#missing[@]} -gt 0 ]; then
        warn "Missing databases: ${missing[*]}"
        log "Run './infra.sh init' to create them"
        return 1
    fi
    return 0
}

# 显示状态
show_status() {
    echo ""
    echo "============================================"
    echo "  Harnax Local Infrastructure Status"
    echo "============================================"

    if check_mysql 2>/dev/null; then
        echo -e "  MySQL:   \033[32mAVAILABLE\033[0m (${MYSQL_HOST}:${MYSQL_PORT})"
        check_databases 2>/dev/null || true
    else
        echo -e "  MySQL:   \033[31mNOT AVAILABLE\033[0m"
    fi

    echo ""
    echo "  Local mode features:"
    echo "    Redis:  \033[33mSKIPPED\033[0m (router uses Caffeine local cache)"
    echo "    MinIO:  \033[33mSKIPPED\033[0m (agent uses local filesystem)"
    echo "    Sandbox:\033[33mSKIPPED\033[0m (no Docker required)"
    echo ""
    echo "  Router DB: SQLite (tmp/harnax-router/call-log.db)"
    echo "============================================"
    echo ""
}

# Main
case "${1:-check}" in
    check)
        check_mysql
        check_databases
        show_status
        ;;
    init)
        check_mysql || exit 1
        init_databases
        show_status
        ;;
    status)
        show_status
        ;;
    *)
        echo "Usage: $0 {check|init|status}"
        echo ""
        echo "  check   Check MySQL connectivity and databases (default)"
        echo "  init    Create databases and run init SQL"
        echo "  status  Show infrastructure status"
        exit 1
        ;;
esac
