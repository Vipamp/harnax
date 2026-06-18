## Context

当前项目需要完整的 Docker 打包和部署方案。项目结构：
- **前端**：harnax-webui（React + UmiJS），需要使用 Nginx 打包运行
- **后端**：harnax-admin（Spring Boot + Kotlin），需要本地构建 JAR 后打包到 Docker
- **数据库**：MySQL 8.0，需要在 docker-compose 中提供服务

所有 Docker 相关文件将统一保存在 `docker/` 目录下，保持项目根目录整洁。

## Goals / Non-Goals

**Goals:**
- 创建前端 Dockerfile，使用 Nginx 提供静态文件服务
- 创建后端 Dockerfile，接收本地构建的 JAR 包（不在 Docker 中构建）
- 创建 docker-compose.yml，编排 MySQL、后端、前端三个服务
- 所有 Docker 相关文件保存在 `docker/` 目录
- 提供开发环境和生产环境两套配置
- 确保一键启动命令可以正常工作

**Non-Goals:**
- 不在 Docker 中进行 Maven 或 Node.js 构建
- 不修改业务代码逻辑
- 不涉及数据库结构变更
- 不重构 CI/CD 流水线

## Decisions

### 决策 1：后端 Dockerfile 设计
**选择**：使用单阶段构建，从 `docker/dist/backend/` 目录读取已构建的 JAR 包

**构建流程**：
```bash
# 1. 本地构建 JAR
cd /Users/heqingsong/code/my_project/harnax
mvn clean package -pl harnax-admin -am -Ppersonal -DskipTests

# 2. 复制 JAR 到 docker/dist/backend/
cp harnax-admin/target/harnax-admin-*.jar docker/dist/backend/

# 3. 构建 Docker 镜像（使用 dist 目录的 JAR）
docker build -f docker/Dockerfile.backend -t harnax-backend:personal .
```

**理由**：
- 避免在 Docker 中安装 Maven 和 JDK，减小镜像体积
- 利用本地构建缓存，提高构建速度
- 便于本地调试和快速迭代
- 最终镜像只包含 JRE，更加轻量
- `dist/` 目录统一存储编译产物，便于管理

**备选方案**：多阶段构建（在 Docker 中构建）-  rejected，因为会增加构建时间和镜像大小

### 决策 2：前端 Dockerfile 设计
**选择**：使用单阶段构建，从 `docker/dist/frontend/` 目录读取已构建的静态文件

**构建流程**：
```bash
# 1. 本地构建前端
cd /Users/heqingsong/code/my_project/harnax/harnax-webui
npm run build:personal

# 2. 复制构建产物到 docker/dist/frontend/
cp -r dist/* ../docker/dist/frontend/

# 3. 构建 Docker 镜像（使用 dist 目录的静态文件）
cd ..
docker build -f docker/Dockerfile.frontend -t harnax-frontend:personal .
```

**理由**：
- 避免在 Docker 中安装 Node.js 和依赖，减小镜像体积
- 利用本地构建缓存，提高构建速度
- 最终镜像只包含 Nginx，更加轻量
- 便于本地调试前端
- `dist/` 目录统一存储编译产物，便于管理

### 决策 3：文件组织结构
**选择**：所有 Docker 相关文件统一保存在 `docker/` 目录

**目录结构**：
```
docker/
├── Dockerfile.frontend      # 前端镜像（Nginx）
├── Dockerfile.backend       # 后端镜像（JRE + JAR）
├── docker-compose.personal.yml      # 个人版配置
├── docker-compose.enterprise.yml    # 企业版配置
├── docker-compose.public.yml        # 公开版配置
├── build.sh                 # 一键构建脚本
├── init-db.sh               # 数据库初始化脚本
├── nginx.conf               # Nginx 配置（已存在）
├── dist/                    # 编译产物目录
│   ├── backend/             # 后端 JAR 包
│   │   └── harnax-admin-*.jar
│   └── frontend/            # 前端静态文件
│       └── ... (build 产物)
└── sql/                     # 数据库初始化脚本
    └── V1__init.sql         # Flyway 迁移脚本
```

**理由**：
- 保持项目根目录整洁
- 集中管理所有容器化配置
- `dist/` 目录存储编译产物，Dockerfile 直接引用
- `sql/` 目录存储 Flyway 迁移脚本，后端启动时自动执行
- 便于维护和更新

### 决策 4：docker-compose 服务编排
**选择**：三个服务（MySQL + Backend + Frontend），每个版本有独立的配置文件

**服务配置**：
- **mysql**：MySQL 8.0，持久化数据卷，Flyway 自动迁移
- **backend**：Spring Boot 应用，依赖 MySQL，暴露 8080 端口，集成 Flyway
- **frontend**：Nginx 静态服务，依赖 backend，暴露 80 端口

**网络配置**：
- 使用自定义 bridge 网络 `harnax-network`
- 服务间通过服务名通信（如 `jdbc:mysql://mysql:3306/...`）

**多版本配置**：
- `docker-compose.personal.yml` - 个人版
- `docker-compose.enterprise.yml` - 企业版
- `docker-compose.public.yml` - 公开版

**Flyway 集成**：
- 后端启动时自动执行 `docker/sql/` 目录下的迁移脚本
- 脚本命名规范：`V1__init.sql`, `V2__add_user_table.sql` 等
- 通过环境变量配置 Flyway：
  ```yaml
  environment:
    SPRING_FLYWAY_LOCATIONS: classpath:db/migration,file:/app/sql
  volumes:
    - ./sql:/app/sql
  ```

### 决策 5：健康检查方案
**选择**：
- **后端**：使用 `wget` 检查 HTTP 端点（eclipse-temurin 镜像包含 wget）
- **前端**：使用 `wget` 检查 Nginx 服务
- **MySQL**：使用 `mysqladmin ping`

**理由**：
- wget 在 Alpine 和 Debian 基础镜像中通常已包含
- 无需额外安装工具，减小镜像体积

## Risks / Trade-offs

### 风险 1：本地构建环境依赖
**风险**：需要本地安装 Maven 和 Node.js

**缓解措施**：
- 在文档中明确说明前置依赖
- 提供构建脚本自动化流程
- 可选：提供 CI/CD 集成示例

### 风险 2：JAR 包路径变化
**风险**：Maven 构建产物路径可能因配置变化

**缓解措施**：
- 在 Dockerfile 中使用通配符匹配 JAR 文件
- 提供明确的构建命令示例
- 添加错误提示信息

### 风险 3：数据持久化
**风险**：容器重启后 MySQL 数据丢失

**缓解措施**：
- 使用 Docker Volume 持久化 MySQL 数据
- 提供数据备份和恢复指南

### 权衡 1：构建灵活性 vs 镜像大小
- 本地构建：需要本地环境，但镜像更小更快
- Docker 内构建：环境独立，但镜像大且慢
- **选择**：本地构建（符合用户需求）

### 权衡 2：开发便利性 vs 生产安全性
- 开发环境：开放更多端口，启用 DEBUG 日志
- 生产环境：最小化暴露，使用 INFO/WARN 日志

## Migration Plan

### 部署步骤
1. 在 `docker/` 目录创建 `dist/` 和 `sql/` 子目录
2. 在 `docker/` 目录创建 Dockerfile.frontend
3. 在 `docker/` 目录创建 Dockerfile.backend
4. 在 `docker/` 目录创建 `docker-compose.personal.yml`、`docker-compose.enterprise.yml`、`docker-compose.public.yml`
5. 在 `docker/` 目录创建 `build.sh` 和 `init-db.sh`
6. 在 `docker/sql/` 目录添加 Flyway 迁移脚本
7. 配置后端 Flyway 集成
8. 测试完整构建和启动流程
9. 更新文档说明

### 构建和启动流程
```bash
# 1. 一键构建（自动完成 Maven + npm + Docker 构建）
./docker/build.sh personal

# 2. 启动服务
docker-compose -f docker/docker-compose.personal.yml up -d

# 3. 查看服务状态
docker-compose -f docker/docker-compose.personal.yml ps

# 4. 查看日志
docker-compose -f docker/docker-compose.personal.yml logs -f
```

### 回滚策略
- 保留 Docker 文件的 Git 历史
- 如有问题，可通过 `git revert` 回滚
- 提供手动部署指南作为备选方案

### 验证步骤
```bash
# 检查 MySQL 连接
docker-compose -f docker/docker-compose.personal.yml exec mysql mysql -u harnax -pharnax123 -e "SHOW DATABASES;"

# 检查 Flyway 迁移状态
docker-compose -f docker/docker-compose.personal.yml logs backend | grep -i flyway

# 检查后端健康
docker-compose -f docker/docker-compose.personal.yml exec backend wget -qO- http://localhost:8080/api/health

# 检查前端服务
docker-compose -f docker/docker-compose.personal.yml exec frontend wget -qO- http://localhost:80/

# 访问应用
open http://localhost
```

## Open Questions

1. **构建脚本**：是否需要提供一键构建脚本（build.sh）？
   - 当前方案：手动执行构建命令
   - 可选方案：提供 `docker/build.sh` 自动化构建流程

2. **数据库初始化**：是否需要自动化执行 SQL 迁移脚本？
   - 当前方案：挂载 `sql/` 目录到 MySQL 容器的 `/docker-entrypoint-initdb.d`
   - 可选方案：集成 Flyway 或 Liquibase

3. **多版本支持**：如何支持 personal、public、enterprise 三个版本？
   - 当前方案：通过环境变量 `EDITION` 控制
   - 需要在构建时指定对应的 Maven Profile 和 npm script
