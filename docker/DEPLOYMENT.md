# VIPClaw 打包部署手册

## 目录

- [1. 概述](#1-概述)
- [2. 环境要求](#2-环境要求)
- [3. 项目结构](#3-项目结构)
- [4. 构建流程](#4-构建流程)
- [5. Docker 部署](#5-docker-部署)
- [6. 配置说明](#6-配置说明)
- [7. 数据库管理](#7-数据库管理)
- [8. 运维管理](#8-运维管理)
- [9. 故障排查](#9-故障排查)
- [10. 常见问题](#10-常见问题)

---

## 1. 概述

VIPClaw 是一个基于 Spring Boot 3 + React 的多版本 AI 管理平台，支持 personal（个人版）、enterprise（企业版）和 public（公有云版）三种部署模式。

本手册详细介绍项目的构建、打包和部署流程，采用本地编译 + Docker 容器化的部署方案。

### 1.1 技术栈

**后端**：
- Spring Boot 3.5.8
- Kotlin 2.2.20
- MyBatis 3.0.4
- MySQL 8.0
- JDK 21

**前端**：
- React 18
- UmiJS 4
- Ant Design Pro
- Node.js 20+

**部署**：
- Docker 20+
- Docker Compose 2+
- Nginx（静态资源服务）

### 1.2 部署架构

```
┌─────────────────────────────────────────┐
│           Docker Compose                 │
│                                          │
│  ┌─────────────┐    ┌──────────────┐   │
│  │   Frontend  │───▶│   Backend    │   │
│  │   (Nginx)   │    │ (Spring Boot)│   │
│  │   Port 80   │    │  Port 8080   │   │
│  └─────────────┘    └──────┬───────┘   │
│                            │            │
│                     ┌──────▼───────┐   │
│                     │    MySQL     │   │
│                     │  Port 3306   │   │
│                     └──────────────┘   │
│                                        │
└─────────────────────────────────────────┘
```

---

## 2. 环境要求

### 2.1 开发环境

| 软件 | 版本要求 | 用途 |
|------|---------|------|
| JDK | 21+ | Java/Kotlin 开发 |
| Maven | 3.8+ | 项目构建 |
| Node.js | 20+ | 前端构建 |
| npm | 9+ | 依赖管理 |
| Git | 2.30+ | 版本控制 |

### 2.2 部署环境

| 软件 | 版本要求 | 用途 |
|------|---------|------|
| Docker | 20.10+ | 容器运行时 |
| Docker Compose | 2.0+ | 容器编排 |
| Linux/macOS | - | 操作系统 |

### 2.3 硬件要求

| 环境 | CPU | 内存 | 磁盘 |
|------|-----|------|------|
| 开发环境 | 2 核 | 4GB | 20GB |
| 生产环境 | 4 核 | 8GB | 50GB |

---

## 3. 项目结构

```
vipclaw/
├── vipclaw-admin/              # 后端主服务
│   ├── src/main/kotlin/        # Kotlin 源代码
│   ├── src/main/resources/     # 配置文件
│   └── pom.xml                 # Maven 配置
├── vipclaw-webui/              # 前端项目
│   ├── src/                    # React 源代码
│   ├── package.json            # npm 配置
│   └── config/                 # 构建配置
├── docker/                     # Docker 部署文件
│   ├── Dockerfile.backend      # 后端镜像
│   ├── Dockerfile.frontend     # 前端镜像
│   ├── docker-compose.personal.yml     # 个人版配置
│   ├── docker-compose.prod.yml         # 生产环境配置
│   ├── build.sh                # 一键构建脚本
│   ├── nginx.conf              # Nginx 配置
│   ├── .env.example            # 环境变量示例
│   ├── data/                   # MySQL 数据目录
│   └── dist/                   # 编译产物
└── pom.xml                     # 父 POM
```

---

## 4. 构建流程

### 4.1 一键构建（推荐）

项目提供了自动化构建脚本，可以完成从编译到打包的全部流程：

```bash
# 赋予执行权限
chmod +x docker/build.sh

# 构建个人版
./docker/build.sh personal

# 构建企业版
./docker/build.sh enterprise

# 构建公有云版
./docker/build.sh public
```

**构建流程说明**：
1. 使用 Maven 编译后端项目，生成 JAR 包
2. 使用 npm 构建前端项目，生成静态文件
3. 将编译产物复制到 `docker/dist/` 目录
4. 构建 Docker 镜像

### 4.2 手动构建

#### 4.2.1 构建后端

```bash
# 编译打包（跳过测试）
mvn clean package -pl vipclaw-admin -am -Ppersonal -DskipTests

# 复制 JAR 到 Docker 目录
mkdir -p docker/dist/backend
cp vipclaw-admin/target/vipclaw-admin-*.jar docker/dist/backend/
```

#### 4.2.2 构建前端

```bash
# 进入前端目录
cd vipclaw-webui

# 安装依赖（首次构建）
npm install

# 构建个人版
npm run build:personal

# 返回项目根目录
cd ..

# 复制前端文件到 Docker 目录
mkdir -p docker/dist/frontend
cp -r vipclaw-webui/dist/* docker/dist/frontend/
```

#### 4.2.3 构建 Docker 镜像

```bash
# 构建后端镜像
docker build -f docker/Dockerfile.backend -t vipclaw-backend:personal .

# 构建前端镜像
docker build -f docker/Dockerfile.frontend -t vipclaw-frontend:personal .
```

### 4.3 构建产物说明

构建完成后，编译产物统一存储在 `docker/dist/` 目录：

```
docker/dist/
├── backend/
│   └── vipclaw-admin-1.0.0-SNAPSHOT.jar  # 后端 JAR 包
└── frontend/
    ├── index.html                         # 前端入口文件
    ├── assets/                            # 静态资源
    └── ...
```

**注意**：`docker/dist/` 目录不提交到 Git，每次构建会自动更新。

---

## 5. Docker 部署

### 5.1 开发环境部署

#### 5.1.1 快速启动

```bash
# 1. 构建项目
./docker/build.sh personal

# 2. 启动服务
docker-compose -f docker/docker-compose.personal.yml up -d

# 3. 查看服务状态
docker-compose -f docker/docker-compose.personal.yml ps

# 4. 查看日志
docker-compose -f docker/docker-compose.personal.yml logs -f
```

#### 5.1.2 服务说明

| 服务 | 容器名称 | 端口 | 说明 |
|------|---------|------|------|
| MySQL | vipclaw-mysql | 3306 | 数据库服务 |
| Backend | vipclaw-backend-personal | 8080 | 后端 API 服务 |
| Frontend | vipclaw-frontend-personal | 80 | 前端 Web 界面 |

#### 5.1.3 访问应用

- **前端界面**：http://localhost
- **后端 API**：http://localhost:8080
- **健康检查**：http://localhost:8080/api/health
- **API 文档**：http://localhost:8080/swagger-ui.html

### 5.2 生产环境部署

#### 5.2.1 准备外部数据库

生产环境需要使用外部数据库，请提前准备：

```sql
-- 创建数据库
CREATE DATABASE vipclaw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建用户
CREATE USER 'vipclaw'@'%' IDENTIFIED BY 'your_password';

-- 授权
GRANT ALL PRIVILEGES ON vipclaw.* TO 'vipclaw'@'%';
FLUSH PRIVILEGES;
```

#### 5.2.2 配置环境变量

创建 `.env` 文件：

```bash
cd docker
cp .env.example .env
```

编辑 `.env` 文件，配置生产环境参数：

```bash
# 数据库配置
SPRING_DATASOURCE_URL=jdbc:mysql://your-db-host:3306/vipclaw?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
SPRING_DATASOURCE_USERNAME=vipclaw
SPRING_DATASOURCE_PASSWORD=your_password

# Session 数据库配置（可选，默认使用主数据库）
SESSION_JDBC_URL=jdbc:mysql://your-db-host:3306/vipclaw?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
SESSION_USERNAME=vipclaw
SESSION_PASSWORD=your_password

# 日志级别
LOG_LEVEL=INFO
```

#### 5.2.3 启动服务

```bash
# 构建镜像
./docker/build.sh personal

# 启动生产环境服务
docker-compose -f docker/docker-compose.prod.yml up -d

# 查看日志
docker-compose -f docker/docker-compose.prod.yml logs -f
```

### 5.3 多版本部署

项目支持三个版本同时部署，互不干扰：

```bash
# 部署个人版
./docker/build.sh personal
docker-compose -f docker/docker-compose.personal.yml up -d

# 部署企业版
./docker/build.sh enterprise
docker-compose -f docker/docker-compose.enterprise.yml up -d

# 部署公有云版
./docker/build.sh public
docker-compose -f docker/docker-compose.public.yml up -d
```

每个版本使用不同的端口，避免冲突。

---

## 6. 配置说明

### 6.1 MySQL 配置

#### 6.1.1 数据目录挂载

MySQL 数据默认存储在本地目录 `docker/data/mysql`，可通过环境变量配置：

```bash
# 使用自定义数据目录
MYSQL_DATA_DIR=/path/to/your/data docker-compose -f docker/docker-compose.personal.yml up -d
```

**注意**：数据库表结构由 Flyway 自动管理，应用启动时会自动执行 `schema.sql` 创建表结构。

### 6.2 后端配置

#### 6.2.1 环境变量

| 变量名 | 说明 | 默认值 | 必填 |
|--------|------|--------|------|
| SPRING_PROFILES_ACTIVE | Spring 配置文件 | personal | 是 |
| SPRING_DATASOURCE_URL | 数据库连接 URL | jdbc:mysql://localhost:3306/vipclaw | 是 |
| SPRING_DATASOURCE_USERNAME | 数据库用户名 | root | 是 |
| SPRING_DATASOURCE_PASSWORD | 数据库密码 | 123456 | 是 |
| SESSION_JDBC_URL | Session 数据库 URL | (同主数据库) | 否 |
| SESSION_DATABASE_NAME | Session 数据库名称 | vipclaw | 否 |
| SESSION_USERNAME | Session 数据库用户名 | (同主数据库) | 否 |
| SESSION_PASSWORD | Session 数据库密码 | (同主数据库) | 否 |
| LOG_LEVEL | 日志级别 | DEBUG（开发）/ INFO（生产） | 否 |

#### 6.2.2 JVM 参数

生产环境建议配置 JVM 参数：

```bash
JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

### 6.3 前端配置

前端通过 Nginx 提供静态资源服务，配置文件为 `docker/nginx.conf`：

- 支持 Gzip 压缩
- 静态资源缓存 1 年
- API 反向代理到后端
- SPA 路由支持
- WebSocket/SSE 支持

---

## 7. 数据库管理

### 7.1 数据持久化

MySQL 数据存储在本地目录，容器重启后数据不会丢失。

**目录结构**：
```
docker/data/mysql/
├── ibdata1              # InnoDB 系统表空间
├── ib_logfile0          # InnoDB 日志文件
├── ib_logfile1
├── auto.cnf
└── vipclaw/             # 数据库文件
    ├── *.ibd            # 表数据文件
    └── *.frm            # 表结构文件
```

### 7.2 数据备份

#### 7.2.1 完整备份（推荐）

```bash
# 备份数据目录
tar czf mysql-backup-$(date +%Y%m%d_%H%M%S).tar.gz docker/data/mysql
```

#### 7.2.2 逻辑备份

```bash
# 使用 mysqldump 导出
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysqldump -u vipclaw -pvipclaw123 \
  --single-transaction \
  --routines \
  --triggers \
  vipclaw > backup_$(date +%Y%m%d).sql
```

### 7.3 数据恢复

#### 7.3.1 从目录备份恢复

```bash
# 停止服务
docker-compose -f docker/docker-compose.personal.yml down

# 恢复数据
tar xzf mysql-backup-20260514.tar.gz -C docker/data/

# 启动服务
docker-compose -f docker/docker-compose.personal.yml up -d
```

#### 7.3.2 从 SQL 文件恢复

```bash
# 导入 SQL 文件
docker-compose -f docker/docker-compose.personal.yml exec -T mysql \
  mysql -u vipclaw -pvipclaw123 vipclaw < backup_20260514.sql
```

### 7.4 数据库连接测试

```bash
# 连接 MySQL
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysql -u vipclaw -pvipclaw123

# 执行 SQL 查询
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysql -u vipclaw -pvipclaw123 -e "SHOW DATABASES;"

# 查看表结构
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysql -u vipclaw -pvipclaw123 -e "USE vipclaw; SHOW TABLES;"
```

---

## 8. 运维管理

### 8.1 服务管理

#### 8.1.1 启动服务

```bash
# 启动所有服务
docker-compose -f docker/docker-compose.personal.yml up -d

# 启动指定服务
docker-compose -f docker/docker-compose.personal.yml up -d backend
```

#### 8.1.2 停止服务

```bash
# 停止所有服务
docker-compose -f docker/docker-compose.personal.yml down

# 停止服务并删除数据卷（危险操作！）
docker-compose -f docker/docker-compose.personal.yml down -v
```

#### 8.1.3 重启服务

```bash
# 重启所有服务
docker-compose -f docker/docker-compose.personal.yml restart

# 重启指定服务
docker-compose -f docker/docker-compose.personal.yml restart backend
```

### 8.2 日志管理

#### 8.2.1 查看日志

```bash
# 查看所有服务日志
docker-compose -f docker/docker-compose.personal.yml logs -f

# 查看指定服务日志
docker-compose -f docker/docker-compose.personal.yml logs -f backend

# 查看最近 100 行日志
docker-compose -f docker/docker-compose.personal.yml logs --tail=100 backend
```

#### 8.2.2 日志文件

后端日志同时输出到容器日志和文件：

```
docker/data/logs/
└── vipclaw-admin.log    # 应用日志
```

### 8.3 健康检查

#### 8.3.1 检查服务状态

```bash
# 查看所有服务状态
docker-compose -f docker/docker-compose.personal.yml ps

# 查看服务详细信息
docker inspect vipclaw-backend-personal
```

#### 8.3.2 健康检查接口

```bash
# 检查后端健康状态
curl http://localhost:8080/api/health

# 检查前端健康状态
curl http://localhost:80/health

# 检查 MySQL 状态
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysqladmin ping -h localhost
```

### 8.4 容器管理

#### 8.4.1 进入容器

```bash
# 进入后端容器
docker-compose -f docker/docker-compose.personal.yml exec backend bash

# 进入 MySQL 容器
docker-compose -f docker/docker-compose.personal.yml exec mysql bash

# 进入前端容器
docker-compose -f docker/docker-compose.personal.yml exec frontend sh
```

#### 8.4.2 查看容器资源占用

```bash
# 查看容器资源使用情况
docker stats

# 查看指定容器
docker stats vipclaw-backend-personal
```

### 8.5 镜像管理

#### 8.5.1 查看镜像

```bash
# 查看所有镜像
docker images

# 查看 VIPClaw 相关镜像
docker images | grep vipclaw
```

#### 8.5.2 清理镜像

```bash
# 删除指定镜像
docker rmi vipclaw-backend:personal

# 删除所有未使用的镜像
docker image prune -a
```

---

## 9. 故障排查

### 9.1 常见问题

#### 9.1.1 后端启动失败

**症状**：后端容器不断重启

**排查步骤**：

1. 查看日志
```bash
docker-compose -f docker/docker-compose.personal.yml logs backend
```

2. 检查数据库连接
```bash
# 测试数据库连通性
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysql -u vipclaw -pvipclaw123 -e "SELECT 1"
```

3. 检查环境变量
```bash
docker-compose -f docker/docker-compose.personal.yml exec backend env | grep SPRING
```

**常见原因**：
- 数据库未启动或连接失败
- 环境变量配置错误
- 端口被占用

#### 9.1.2 前端无法访问后端 API

**症状**：前端页面正常，但 API 请求失败

**排查步骤**：

1. 检查后端服务是否正常
```bash
curl http://localhost:8080/api/health
```

2. 检查 Nginx 配置
```bash
docker-compose -f docker/docker-compose.personal.yml exec frontend \
  cat /etc/nginx/conf.d/default.conf
```

3. 检查网络连通性
```bash
docker-compose -f docker/docker-compose.personal.yml exec frontend \
  wget -qO- http://backend:8080/api/health
```

**解决方案**：
- 确保后端服务正常运行
- 检查 Nginx 反向代理配置
- 重启前端服务

#### 9.1.3 MySQL 连接失败

**症状**：后端日志显示数据库连接错误

**排查步骤**：

1. 检查 MySQL 容器状态
```bash
docker-compose -f docker/docker-compose.personal.yml ps mysql
```

2. 检查 MySQL 日志
```bash
docker-compose -f docker/docker-compose.personal.yml logs mysql
```

3. 检查数据目录权限
```bash
ls -la docker/data/mysql
```

**解决方案**：
- 确保数据目录存在且有读写权限
- 删除损坏的数据文件，重新初始化
- 检查数据库连接配置

### 9.2 性能问题

#### 9.2.1 内存不足

**症状**：服务频繁重启或 OOM

**解决方案**：

1. 增加 JVM 内存
```bash
JAVA_OPTS=-Xms1g -Xmx2g
```

2. 调整 Docker 资源限制
```yaml
deploy:
  resources:
    limits:
      memory: 2G
```

#### 9.2.2 数据库连接池耗尽

**症状**：请求超时，日志显示连接池错误

**解决方案**：

调整 HikariCP 配置：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
```

### 9.3 日志分析

#### 9.3.1 查看错误日志

```bash
# 查看后端错误日志
docker-compose -f docker/docker-compose.personal.yml logs backend | grep ERROR

# 查看最近 10 个错误
docker-compose -f docker/docker-compose.personal.yml logs backend | grep ERROR | tail -10
```

#### 9.3.2 慢查询日志

MySQL 慢查询日志位于：
```
docker/data/mysql/slow-query.log
```

---

## 10. 常见问题

### Q1: 如何修改默认端口？

**A**: 修改 `docker-compose.personal.yml` 中的端口映射：

```yaml
services:
  backend:
    ports:
      - "9090:8080"  # 将 8080 改为 9090
  
  frontend:
    ports:
      - "8080:80"    # 将 80 改为 8080
```

### Q2: 如何升级版本？

**A**: 
```bash
# 1. 拉取最新代码
git pull

# 2. 停止服务
docker-compose -f docker/docker-compose.personal.yml down

# 3. 重新构建
./docker/build.sh personal

# 4. 启动服务
docker-compose -f docker/docker-compose.personal.yml up -d
```

### Q3: 如何查看数据库表结构？

**A**:
```bash
docker-compose -f docker/docker-compose.personal.yml exec mysql \
  mysql -u vipclaw -pvipclaw123 vipclaw -e "SHOW TABLES;"
```

### Q4: 如何重置数据库？

**A**:
```bash
# 警告：这将删除所有数据！
docker-compose -f docker/docker-compose.personal.yml down
rm -rf docker/data/mysql/*
docker-compose -f docker/docker-compose.personal.yml up -d
```

### Q5: 生产环境如何配置 SSL？

**A**: 
1. 将 SSL 证书放在 `docker/ssl/` 目录
2. 修改 `docker/nginx.conf` 配置 HTTPS
3. 使用 `docker-compose.prod.yml` 启动（已包含 SSL 卷挂载）

### Q6: 如何监控服务状态？

**A**:
```bash
# 查看容器资源使用
docker stats

# 查看服务健康状态
curl http://localhost:8080/api/health

# 查看日志
docker-compose -f docker/docker-compose.personal.yml logs -f
```

### Q7: 多个版本可以同时运行吗？

**A**: 可以。每个版本使用不同的端口和容器名称，互不干扰：

```bash
# 个人版：80, 8080
docker-compose -f docker/docker-compose.personal.yml up -d

# 企业版：81, 8081
docker-compose -f docker/docker-compose.enterprise.yml up -d
```

### Q8: 如何迁移数据到新服务器？

**A**:
```bash
# 1. 备份数据
tar czf mysql-backup.tar.gz docker/data/mysql

# 2. 传输到新服务器
scp mysql-backup.tar.gz user@new-server:/path/

# 3. 在新服务器恢复
tar xzf mysql-backup.tar.gz -C docker/data/

# 4. 启动服务
docker-compose -f docker/docker-compose.personal.yml up -d
```

---

## 附录

### A. 环境变量完整列表

详见 `docker/.env.example` 文件。

### B. 数据库迁移

数据库迁移由 Flyway 自动管理，相关文件位于：

```
vipclaw-admin/src/main/resources/db/
├── schema.sql                 # 初始数据库结构
└── migration/                 # 增量迁移脚本
    ├── V1__xxx.sql
    ├── V2__xxx.sql
    └── README.md
```

详见：`vipclaw-admin/src/main/resources/db/migration/README.md`

### C. 联系支持

如有问题，请联系技术支持团队或查看项目文档。

---

**文档版本**：v1.0.0  
**更新日期**：2026-05-14  
**维护团队**：VIPClaw 开发团队
