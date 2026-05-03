#!/bin/bash

# VIPClaw 多版本一键构建脚本
# 用途: 依次构建个人版、企业版、公有云版

set -e  # 遇到错误立即退出

# 检查参数
BUILD_DOCKER=false
BUILD_NATIVE=false
for arg in "$@"; do
    case $arg in
        --docker)
        BUILD_DOCKER=true
        shift
        ;;
        --docker-native)
        BUILD_NATIVE=true
        shift
        ;;
    esac
done

echo "========================================="
echo "  VIPClaw 多版本构建脚本"
echo "========================================="
echo ""

if [ "$BUILD_DOCKER" = true ]; then
    echo -e "${YELLOW}Docker 镜像构建: 已启用${NC}"
else
    echo -e "${YELLOW}Docker 镜像构建: 已禁用 (使用 --docker 参数启用)${NC}"
fi
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 记录开始时间
START_TIME=$(date +%s)

# 构建函数
build_edition() {
    local edition=$1
    local profile=$2
    
    echo -e "${YELLOW}[${edition}] 开始构建...${NC}"
    echo ""
    
    # 进入后端目录
    cd vipclaw-admin
    
    # 执行 Maven 构建
    if mvn clean package -P${profile} -DskipTests; then
        echo -e "${GREEN}[${edition}] 构建成功!${NC}"
        
        # 显示 JAR 文件大小
        JAR_FILE=$(ls -lh target/vipclaw-admin-*.jar 2>/dev/null | head -n 1)
        if [ -n "$JAR_FILE" ]; then
            echo -e "${GREEN}[${edition}] JAR 文件: ${JAR_FILE}${NC}"
        fi
        
        # 创建输出目录
        mkdir -p ../dist/${edition}
        cp target/vipclaw-admin-*.jar ../dist/${edition}/
        
        echo -e "${GREEN}[${edition}] JAR 已复制到 dist/${edition}/${NC}"
    else
        echo -e "${RED}[${edition}] 构建失败!${NC}"
        exit 1
    fi
    
    # 返回根目录
    cd ..
    echo ""
}

# 构建前端
build_frontend() {
    local edition=$1
    
    echo -e "${YELLOW}[前端-${edition}] 开始构建...${NC}"
    echo ""
    
    cd vipclaw-webui
    
    # 执行 npm 构建
    if npm run build:${edition}; then
        echo -e "${GREEN}[前端-${edition}] 构建成功!${NC}"
        
        # 创建输出目录
        mkdir -p ../dist/${edition}/frontend
        cp -r dist/* ../dist/${edition}/frontend/
        
        echo -e "${GREEN}[前端-${edition}] 构建产物已复制到 dist/${edition}/frontend/${NC}"
    else
        echo -e "${RED}[前端-${edition}] 构建失败!${NC}"
        exit 1
    fi
    
    cd ..
    echo ""
}

# 主流程
echo "步骤 1/6: 清理旧构建产物..."
rm -rf dist/
mkdir -p dist/
echo ""

echo "步骤 2/6: 构建个人版后端..."
build_edition "个人版" "personal"

echo "步骤 3/6: 构建企业版后端..."
build_edition "企业版" "enterprise"

echo "步骤 4/6: 构建公有云版后端..."
build_edition "公有云版" "public"

echo "步骤 5/6: 构建前端(可选)..."
echo -e "${YELLOW}提示: 前端构建需要 Node.js 环境,跳过? (y/n)${NC}"
read -t 10 -r skip_frontend || skip_frontend="y"

if [[ ! $skip_frontend =~ ^[Yy]$ ]]; then
    build_frontend "personal"
    build_frontend "enterprise"
    build_frontend "public"
else
    echo -e "${YELLOW}[前端] 已跳过前端构建${NC}"
    echo ""
fi

echo "步骤 6/6: 生成构建报告..."
echo ""

# 记录结束时间
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# 显示构建报告
echo "========================================="
echo "  构建完成!"
echo "========================================="
echo ""
echo "构建产物目录: dist/"
echo ""

# 列出版本信息
for edition in personal enterprise public; do
    if [ -d "dist/${edition}" ]; then
        JAR_SIZE=$(du -sh dist/${edition}/*.jar 2>/dev/null | cut -f1)
        echo -e "${GREEN}✓ ${edition}版:${NC} ${JAR_SIZE:-未生成}"
    fi
done

echo ""
echo "总耗时: ${DURATION} 秒"
echo ""
echo "========================================="
echo "  验证构建产物"
echo "========================================="
echo ""

# 运行验证脚本
if [ -f "verify-edition.sh" ]; then
    chmod +x verify-edition.sh
    ./verify-edition.sh
else
    echo -e "${YELLOW}警告: verify-edition.sh 不存在,跳过验证${NC}"
fi

echo ""

# Docker 镜像构建(可选)
if [ "$BUILD_DOCKER" = true ] || [ "$BUILD_NATIVE" = true ]; then
    echo "========================================="
    echo "  Docker 镜像构建"
    echo "========================================="
    echo ""
        
    for edition in personal enterprise public; do
        if [ "$BUILD_NATIVE" = true ]; then
            echo -e "${YELLOW}[${edition}] 构建 Native Docker 镜像...${NC}"
                
            # 构建 Native 后端镜像
            if docker build --build-arg EDITION=${edition} -t vipclaw-admin:${edition}-native -f Dockerfile.backend.native .; then
                echo -e "${GREEN}[${edition}] Native 后端镜像构建成功: vipclaw-admin:${edition}-native${NC}"
                    
                # 显示镜像大小
                IMAGE_SIZE=$(docker images vipclaw-admin:${edition}-native --format "{{.Size}}")
                echo -e "${GREEN}[${edition}] Native 镜像大小: ${IMAGE_SIZE}${NC}"
            else
                echo -e "${RED}[${edition}] Native 后端镜像构建失败!${NC}"
                exit 1
            fi
        fi
            
        if [ "$BUILD_DOCKER" = true ]; then
            echo -e "${YELLOW}[${edition}] 构建 Docker 镜像...${NC}"
                
            # 构建后端镜像
            if docker build --build-arg EDITION=${edition} -t vipclaw-admin:${edition} -f Dockerfile.backend .; then
                echo -e "${GREEN}[${edition}] 后端镜像构建成功: vipclaw-admin:${edition}${NC}"
                    
                # 显示镜像大小
                IMAGE_SIZE=$(docker images vipclaw-admin:${edition} --format "{{.Size}}")
                echo -e "${GREEN}[${edition}] 镜像大小: ${IMAGE_SIZE}${NC}"
            else
                echo -e "${RED}[${edition}] 后端镜像构建失败!${NC}"
                exit 1
            fi
                
            # 构建前端镜像
            if docker build --build-arg EDITION=${edition} -t vipclaw-frontend:${edition} -f Dockerfile.frontend .; then
                echo -e "${GREEN}[${edition}] 前端镜像构建成功: vipclaw-frontend:${edition}${NC}"
                    
                # 显示镜像大小
                IMAGE_SIZE=$(docker images vipclaw-frontend:${edition} --format "{{.Size}}")
                echo -e "${GREEN}[${edition}] 前端镜像大小: ${IMAGE_SIZE}${NC}"
            else
                echo -e "${RED}[${edition}] 前端镜像构建失败!${NC}"
                exit 1
            fi
        fi
            
        echo ""
    done
        
    echo "========================================="
    echo "  Docker 镜像列表"
    echo "========================================="
    echo ""
    docker images | grep vipclaw
    echo ""
fi

echo -e "${GREEN}========================================="
echo "  所有版本构建完成!"
echo "=========================================${NC}"
