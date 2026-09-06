#!/bin/bash
set -e

echo "=========================================="
echo "  Harnax 全量重新打包部署脚本"
echo "=========================================="

# 定位项目根目录（脚本在 docker-new/ 下，项目根是上一级）
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# 1. 编译所有 Maven 模块
echo ""
echo "📦 步骤 1/6: 编译所有 Maven 模块..."
mvn clean package -Dmaven.test.skip=true -q

echo "✅ Maven 编译完成"

# 2. 编译前端项目
echo ""
echo "📦 步骤 2/6: 编译前端项目 (harnax-webui)..."
cd harnax-webui
npm install
npm run build
cd "$PROJECT_DIR"

echo "✅ 前端编译完成"

# 3. 复制构建产物到 docker-new/dist 目录
echo ""
echo "📋 步骤 3/6: 复制构建产物到部署目录..."

mkdir -p docker-new/dist/harnax-admin/
cp harnax-admin/target/harnax-admin-*-exec.jar docker-new/dist/harnax-admin/
echo "  ✓ harnax-admin"

mkdir -p docker-new/dist/agent-service/
cp harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar docker-new/dist/agent-service/
echo "  ✓ harnax-agent-service"

mkdir -p docker-new/dist/channel-service/
cp harnax-channel/harnax-channel-service/target/harnax-channel-service-*.jar docker-new/dist/channel-service/
echo "  ✓ harnax-channel-service"

mkdir -p docker-new/dist/router/
cp harnax-session-router/target/harnax-session-router-*.jar docker-new/dist/router/
echo "  ✓ harnax-session-router"

mkdir -p docker-new/dist/harnax-scheduler/
cp harnax-scheduler/target/harnax-scheduler-*.jar docker-new/dist/harnax-scheduler/
echo "  ✓ harnax-scheduler"

mkdir -p docker-new/dist/frontend/
cp -r harnax-webui/dist/* docker-new/dist/frontend/
echo "  ✓ harnax-frontend"

# 4. 构建 Docker 镜像
echo ""
echo "🐳 步骤 4/6: 构建 Docker 镜像..."

echo "  构建沙箱镜像 (harnax-cli 打包 + 默认沙箱 harnax-sandbox:py-node)..."
bash sandbox-plugins/build.sh

echo "  构建 harnax-admin..."
docker rmi -f harnax-admin:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.admin -t harnax-admin:latest .

echo "  构建 harnax-agent-service..."
docker rmi -f harnax-agent-service:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.agent-service -t harnax-agent-service:latest .

echo "  构建 harnax-channel-service..."
docker rmi -f harnax-channel-service:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.channel-service -t harnax-channel-service:latest .

echo "  构建 harnax-router..."
docker rmi -f harnax-router:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.router -t harnax-router:latest .

echo "  构建 harnax-scheduler..."
docker rmi -f harnax-scheduler:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.scheduler -t harnax-scheduler:latest .

echo "  构建 harnax-frontend..."
docker rmi -f harnax-frontend:latest 2>/dev/null || true
docker build --no-cache -f docker-new/Dockerfile.frontend -t harnax-frontend:latest .

echo "✅ 所有镜像构建完成"

# 5. 停止并删除旧容器
echo ""
echo "🛑 步骤 5/6: 停止旧容器..."
docker-compose -f docker-new/docker-compose.yml down || true

echo "✅ 旧容器已停止"

# 6. 启动所有服务（包括 redis、minio、mysql）
echo ""
echo "🚀 步骤 6/6: 启动所有服务..."
docker-compose -f docker-new/docker-compose.yml up -d

echo ""
echo "=========================================="
echo "✅ 部署完成！"
echo "=========================================="
echo ""
echo "等待服务启动..."
sleep 10

echo ""
echo "📊 服务状态："
docker-compose -f docker-new/docker-compose.yml ps

echo ""
echo "🔗 访问地址（对外入口固定 80/443，其余 = 20000 + 容器端口）："
echo "  - Frontend:   http://localhost  (HTTPS: https://localhost)"
echo "  - Admin:      http://localhost:28080"
echo "  - Router:     http://localhost:28081"
echo "  - Agent:      http://localhost:28082"
echo "  - Channel:    http://localhost:28083"
echo "  - Scheduler:  http://localhost:28084"
echo "  - MinIO:      http://localhost:29000 (Console: http://localhost:29001)"
echo "  - MCP Server: http://localhost:29002"
echo "  - Redis:      localhost:26379"
echo "  - MySQL:      localhost:23306"
echo ""
echo "📝 查看日志: docker-compose -f docker-new/docker-compose.yml logs -f [service-name]"
