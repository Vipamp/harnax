#!/bin/bash
# Harnax 单服务部署脚本
# 用法: ./deploy-service.sh <service-name>
# 支持的服务: admin, router, agent-service, channel-service, frontend

set -e

SERVICE=$1

# 定位项目根目录（脚本在 docker-new/ 下，项目根是上一级）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -z "$SERVICE" ]; then
    echo "❌ 用法: ./deploy-service.sh <service-name>"
    echo ""
    echo "支持的服务:"
    echo "  - admin          (harnax-admin)"
    echo "  - router         (harnax-session-router)"
    echo "  - agent-service  (harnax-agent-service)"
    echo "  - channel-service (harnax-channel-service)"
    echo "  - frontend       (harnax-webui)"
    echo ""
    echo "示例:"
    echo "  ./deploy-service.sh router"
    exit 1
fi

cd "$PROJECT_DIR"

echo "=========================================="
echo "  部署服务: $SERVICE"
echo "=========================================="

case $SERVICE in
    admin)
        echo "📦 步骤 1/4: 编译 harnax-admin..."
        mvn clean package -Dmaven.test.skip=true -pl harnax-admin -am -q

        echo "📋 步骤 2/4: 复制 jar 包..."
        mkdir -p docker-new/dist/harnax-admin
        cp harnax-admin/target/harnax-admin-*.jar docker-new/dist/harnax-admin/

        echo "🐳 步骤 3/4: 构建 Docker 镜像..."
        docker rmi -f harnax-admin:latest 2>/dev/null || true
        docker build --no-cache -f docker-new/Dockerfile.admin -t harnax-admin:latest . -q

        echo "🚀 步骤 4/4: 重启服务..."
        docker-compose -f docker-new/docker-compose.yml up -d --force-recreate admin
        ;;

    router)
        echo "📦 步骤 1/4: 编译 harnax-session-router..."
        mvn clean package -Dmaven.test.skip=true -pl harnax-session-router -am -q

        echo "📋 步骤 2/4: 复制 jar 包..."
        mkdir -p docker-new/dist/router
        cp harnax-session-router/target/harnax-session-router-*.jar docker-new/dist/router/

        echo "🐳 步骤 3/4: 构建 Docker 镜像..."
        docker rmi -f harnax-router:latest 2>/dev/null || true
        docker build --no-cache -f docker-new/Dockerfile.router -t harnax-router:latest . -q

        echo "🚀 步骤 4/4: 重启服务..."
        docker-compose -f docker-new/docker-compose.yml up -d --force-recreate router
        ;;

    agent-service)
        echo "📦 步骤 1/4: 编译 harnax-agent-service..."
        mvn clean package -Dmaven.test.skip=true -pl harnax-agent/harnax-agent-service -am -q

        echo "📋 步骤 2/4: 复制 jar 包..."
        mkdir -p docker-new/dist/agent-service
        cp harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar docker-new/dist/agent-service/

        echo "🐳 步骤 3/4: 构建 Docker 镜像..."
        docker rmi -f harnax-agent-service:latest 2>/dev/null || true
        docker build --no-cache -f docker-new/Dockerfile.agent-service -t harnax-agent-service:latest . -q

        echo "🚀 步骤 4/4: 重启服务..."
        docker-compose -f docker-new/docker-compose.yml up -d --force-recreate agent-service
        ;;

    channel-service)
        echo "📦 步骤 1/4: 编译 harnax-channel-service..."
        mvn clean package -Dmaven.test.skip=true -pl harnax-channel/harnax-channel-service -am -q

        echo "📋 步骤 2/4: 复制 jar 包..."
        mkdir -p docker-new/dist/channel-service
        cp harnax-channel/harnax-channel-service/target/harnax-channel-service-*.jar docker-new/dist/channel-service/

        echo "🐳 步骤 3/4: 构建 Docker 镜像..."
        docker rmi -f harnax-channel-service:latest 2>/dev/null || true
        docker build --no-cache -f docker-new/Dockerfile.channel-service -t harnax-channel-service:latest . -q

        echo "🚀 步骤 4/4: 重启服务..."
        docker-compose -f docker-new/docker-compose.yml up -d --force-recreate channel-service
        ;;

    frontend)
        echo "📦 步骤 1/3: 编译前端..."
        cd harnax-webui
        npm install
        npm run build
        cd "$PROJECT_DIR"

        echo "📋 步骤 2/3: 复制构建产物..."
        mkdir -p docker-new/dist/frontend
        cp -r harnax-webui/dist/* docker-new/dist/frontend/

        echo "🐳 步骤 3/3: 构建并重启服务..."
        docker rmi -f harnax-frontend:latest 2>/dev/null || true
        docker build --no-cache -f docker-new/Dockerfile.frontend -t harnax-frontend:latest . -q
        docker-compose -f docker-new/docker-compose.yml up -d --force-recreate frontend
        ;;

    *)
        echo "❌ 不支持的服务: $SERVICE"
        echo ""
        echo "支持的服务: admin, router, agent-service, channel-service, frontend"
        exit 1
        ;;
esac

echo ""
echo "=========================================="
echo "✅ 部署完成: $SERVICE"
echo "=========================================="
echo ""
echo "等待服务启动..."
sleep 8

echo ""
echo "📊 服务状态:"
docker-compose -f docker-new/docker-compose.yml ps | grep "harnax-$SERVICE"
