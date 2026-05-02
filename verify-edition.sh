#!/bin/bash

# VIPClaw 构建产物验证脚本
# 用途: 验证三个版本的 JAR 文件内容和配置是否正确

set -e  # 遇到错误立即退出

echo "========================================="
echo "  VIPClaw 构建产物验证"
echo "========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0

# 验证函数
check_edition() {
    local edition=$1
    local expected_edition=$2
    local expected_features=$3
    
    echo -e "${YELLOW}验证 ${edition} 版...${NC}"
    
    local jar_file="dist/${edition}/vipclaw-admin-*.jar"
    
    # 检查 JAR 文件是否存在
    if [ ! -f "$jar_file" ]; then
        # 尝试在 vipclaw-admin/target 中查找
        jar_file="vipclaw-admin/target/vipclaw-admin-*.jar"
        if [ ! -f "$jar_file" ]; then
            echo -e "${RED}✗ 未找到 JAR 文件: ${jar_file}${NC}"
            FAIL_COUNT=$((FAIL_COUNT + 1))
            echo ""
            return
        fi
    fi
    
    # 获取实际的 JAR 文件路径
    local actual_jar=$(ls $jar_file 2>/dev/null | head -n 1)
    
    echo "  JAR 文件: ${actual_jar}"
    
    # 检查 JAR 文件大小
    local jar_size=$(du -sh "$actual_jar" | cut -f1)
    echo "  文件大小: ${jar_size}"
    
    # 验证 application.yml 中的 edition.current
    echo "  检查 edition.current 配置..."
    local edition_value=$(unzip -p "$actual_jar" BOOT-INF/classes/application.yml 2>/dev/null | grep "edition.current" | head -n 1 | awk '{print $2}')
    
    if [ "$edition_value" = "\${edition.current:personal}" ] || [ -z "$edition_value" ]; then
        echo -e "  ${GREEN}✓ edition.current 配置存在${NC}"
    else
        echo -e "  ${RED}✗ edition.current 配置异常: ${edition_value}${NC}"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    
    # 验证版本专属配置文件是否存在
    echo "  检查版本配置文件..."
    local profile_file="application-${expected_edition}.yml"
    
    if unzip -l "$actual_jar" "BOOT-INF/classes/${profile_file}" >/dev/null 2>&1; then
        echo -e "  ${GREEN}✓ ${profile_file} 存在${NC}"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo -e "  ${RED}✗ ${profile_file} 不存在${NC}"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    
    # 验证功能开关配置
    echo "  检查功能开关配置..."
    local features_content=$(unzip -p "$actual_jar" "BOOT-INF/classes/${profile_file}" 2>/dev/null | grep -A 20 "vipclaw:" | grep -A 15 "features:")
    
    if [ -n "$features_content" ]; then
        echo -e "  ${GREEN}✓ 功能开关配置存在${NC}"
        
        # 检查特定功能开关
        for feature in $expected_features; do
            if echo "$features_content" | grep -q "${feature}:"; then
                echo -e "    ${GREEN}✓ ${feature} 配置存在${NC}"
                PASS_COUNT=$((PASS_COUNT + 1))
            else
                echo -e "    ${RED}✗ ${feature} 配置缺失${NC}"
                FAIL_COUNT=$((FAIL_COUNT + 1))
            fi
        done
    else
        echo -e "  ${RED}✗ 功能开关配置不存在${NC}"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    
    echo ""
}

# 验证个人版
echo "1/3: 验证个人版..."
check_edition "个人版" "personal" "user-management: false
token-monitor: false
billing: false
multi-tenant: false
agent-sharing: false
phone-login: false
email-login: false"

# 验证企业版
echo "2/3: 验证企业版..."
check_edition "企业版" "enterprise" "user-management: true
token-monitor: true
billing: false
multi-tenant: false
agent-sharing: true
phone-login: true
email-login: true"

# 验证公有云版
echo "3/3: 验证公有云版..."
check_edition "公有云版" "public" "user-management: true
token-monitor: true
billing: true
multi-tenant: true
agent-sharing: true
phone-login: true
email-login: true
model-market: true
skill-market: true"

# 输出验证结果
echo "========================================="
echo "  验证结果"
echo "========================================="
echo ""
echo -e "${GREEN}通过: ${PASS_COUNT}${NC}"
echo -e "${RED}失败: ${FAIL_COUNT}${NC}"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}========================================="
    echo "  ✓ 所有验证通过!"
    echo "=========================================${NC}"
    exit 0
else
    echo -e "${RED}========================================="
    echo "  ✗ 存在 ${FAIL_COUNT} 项验证失败"
    echo "=========================================${NC}"
    exit 1
fi
