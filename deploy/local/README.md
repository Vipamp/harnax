# Harnax Local Development Deployment

本地一键部署，**不依赖 Docker**。

## 前置要求

| 依赖 | 版本 | 必须 | 安装方式 |
|------|------|------|---------|
| Java | JDK 21+ | ✅ | `brew install openjdk@21` / `apt install openjdk-21-jdk` |
| Maven | 3.9+ | ✅ | `brew install maven` / `apt install maven` |
| MySQL | 8.0+ | ✅ | `brew install mysql` / `apt install mysql-server` |
| Node.js | 18+ | ❌ | `brew install node`（仅前端需要） |

> **不需要** Docker、Redis、MinIO。Router 使用 SQLite + 本地缓存，Agent 使用本地文件系统。

## 快速开始

```bash
cd deploy/local

# 1. 确保 MySQL 已启动，修改 env.conf 中的数据库密码
vim env.conf

# 2. 初始化数据库
./infra.sh init

# 3. 一键部署
./deploy-all.sh

# 或跳过测试快速部署
./deploy-all.sh fast

# 停止
./deploy-all.sh stop
```

## 本地模式架构

```
┌─────────────────────────────────────────────────┐
│  Frontend (Node.js)                             │
│  harnax-webui :3000                             │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│  Admin API :8080        Scheduler :8084         │
│  ┌─────────────┐        ┌─────────────┐         │
│  │  MySQL      │◄──────►│  MySQL      │         │
│  │harnax_admin │        │harnax_admin │         │
│  └─────────────┘        └─────────────┘         │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│  Router :8081                                   │
│  ┌──────────┐  ┌──────────────┐                 │
│  │ SQLite   │  │ Caffeine     │ ← 替代 Redis    │
│  │ (本地)   │  │ (本地缓存)   │                 │
│  └──────────┘  └──────────────┘                 │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│  Agent Service :8082    Channel Service :8083   │
│  ┌─────────────┐        ┌─────────────┐         │
│  │  MySQL      │        │  MySQL      │         │
│  │ harnax      │        │harnax_admin │         │
│  │ agentscope  │        └─────────────┘         │
│  └─────────────┘                                │
│  ┌──────────────┐                               │
│  │ Local FS     │ ← 替代 MinIO                  │
│  │ (本地文件)   │                               │
│  └──────────────┘                               │
└─────────────────────────────────────────────────┘
```

## 脚本说明

```
deploy/local/
├── deploy-all.sh    # 一键部署：检查环境 → 编译 → 启服务 → 启前端
├── infra.sh         # MySQL 检查 & 数据库初始化
├── build.sh         # Maven 编译 + npm 构建
├── services.sh      # 5 个后端 Java 服务管理
├── webui.sh         # 前端 dev server
├── env.conf         # 环境配置（端口/密码/JVM参数）
├── init-db.sql      # 数据库建库脚本
└── README.md        # 本文件
```

### deploy-all.sh

```bash
./deploy-all.sh          # 全量部署
./deploy-all.sh fast     # 跳过测试
./deploy-all.sh check    # 仅检查环境
./deploy-all.sh stop     # 停止全部
./deploy-all.sh restart  # 重启（不重新编译）
```

### infra.sh

```bash
./infra.sh check    # 检查 MySQL 连接 + 数据库是否存在
./infra.sh init     # 创建数据库（首次使用执行一次）
./infra.sh status   # 查看状态
```

### build.sh

```bash
./build.sh              # 编译全部
./build.sh backend      # 仅后端
./build.sh frontend     # 仅前端
./build.sh fast         # 跳过测试
```

### services.sh

```bash
./services.sh start          # 启动全部（按依赖顺序）
./services.sh stop           # 停止全部
./services.sh restart        # 重启
./services.sh status         # 查看状态
./services.sh logs           # 全量日志
./services.sh logs admin     # 指定服务日志
./services.sh start-one admin  # 启动单个
./services.sh stop-one agent   # 停止单个
```

### webui.sh

```bash
./webui.sh start    # 启动前端 dev server
./webui.sh stop
./webui.sh status
```

## 配置 (env.conf)

```bash
# 数据库（按本地 MySQL 实际情况修改）
DB_USERNAME=root
DB_PASSWORD=root123456
MYSQL_PORT=3306

# 服务端口（默认即可）
ADMIN_PORT=8080
ROUTER_PORT=8081
AGENT_SERVICE_PORT=8082

# JVM
JVM_OPTS=-Xms128m -Xmx512m
```

## 常见开发场景

```bash
# 首次部署
./infra.sh init && ./deploy-all.sh

# 改了后端代码，只重启后端
./build.sh backend fast && ./services.sh restart

# 只改了 admin 代码
./build.sh backend fast
./services.sh stop-one admin
./services.sh start-one admin

# 改了前端代码（热更新，无需重启）
# 直接保存文件，Vite 自动刷新

# 重置数据库
./infra.sh init

# 看某个服务的日志
./services.sh logs agent

# 查看所有状态
./services.sh status
./infra.sh status
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Frontend | 3000 | Vite dev server |
| Admin API | 8080 | 管理后台 |
| Router | 8081 | 会话路由 + SSE 代理 |
| Agent Service | 8082 | Agent 推理 |
| Channel Service | 8083 | 渠道接入（飞书/微信） |
| Scheduler | 8084 | 定时任务 |

## 故障排查

```bash
# MySQL 连不上
./infra.sh check
# → 检查 MySQL 是否启动：brew services list / systemctl status mysql
# → 检查密码是否正确：vim env.conf

# 端口被占用
lsof -i :8080
# → kill 占用进程或修改 env.conf 中的端口

# 服务启动失败
./services.sh logs admin    # 查看日志
cat logs/admin.log          # 查看完整日志

# Maven 编译失败
mvn clean package -DskipTests  # 手动编译看错误
```
