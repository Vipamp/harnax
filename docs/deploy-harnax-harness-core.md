# harnax-harness-core 部署文档

## 服务概述

`harnax-harness-core` 是 Harnax 平台的分布式 Agent 运行时引擎，基于 `agentscope-harness` 构建，作为 `harnax-agent-service` 的核心模块运行。它不是一个独立部署的服务，而是嵌入 agent-service 进程中，提供以下核心能力：

- **Session 级沙箱隔离**：每个 session 绑定独立 Docker 容器，保证 workspace 一致性
- **分布式文件系统**：通过 MinIO（S3 兼容）实现跨节点文件共享和快照存储
- **Snapshot 自动恢复**：沙箱 workspace 自动打包为 tar 归档到 MinIO，跨节点请求时自动恢复
- **会话持久化**：支持 MySQL / JSON / 内存三种 AgentStateStore 后端

- **宿主服务端口**: 8082（agent-service）
- **依赖数据库**: harnax_admin（MySQL，业务数据）+ agentscope（MySQL，会话状态，独立数据库）
- **依赖外部服务**: Docker Engine（沙箱模式）、MinIO（分布式存储模式）

---

## 环境依赖

| 组件 | 本地模式 | 集群模式 | 说明 |
|------|---------|---------|------|
| JDK 21 | 必须 | 必须 | 运行时 |
| MySQL 8.0 | 必须 | 必须 | 业务数据（harnax_admin）+ 会话状态（agentscope），两个独立数据库 |
| Docker Engine | 可选 | 必须 | Agent 沙箱执行环境 |
| MinIO | 不需要 | 必须 | 分布式文件存储 + 快照归档 |
| Redis | 不需要 | 不需要 | harness-core 不使用 Redis |
| harnax-admin | 必须 | 必须 | 模型配置、MCP 配置、Skill 加载等 |
| harnax-session-router | 必须 | 必须 | 会话路由（本地模式使用 SQLite，集群模式使用 MySQL + Redis） |

---

## 部署模式对比

| 能力 | 本地模式 | 集群模式 |
|------|---------|---------|
| 沙箱隔离 | 无（宿主机直接执行） | Docker 容器（SESSION 级隔离） |
| 文件系统 | 宿主机本地目录 | MinIO 远程文件系统 或 Docker 容器内 |
| Shell 执行 | 宿主机本地执行 | Docker 容器内执行 |
| 快照存储 | 不适用 | MinIO（tar 归档） |
| Memory/KV 存储 | 不适用 | MinIO BaseStore |
| 会话持久化 | MySQL / JSON / 内存 | MySQL（推荐） |
| 水平扩展 | 不支持（状态绑定本机） | 支持（通过 MinIO 共享状态） |
| 适用场景 | 开发 / 测试 / 个人版 | 生产 / 多节点集群 |

---

## 配置系统

harness-core 通过 Spring Boot 自动配置（`HarnessAutoConfiguration`）注入，所有配置项均以 `harness` 为前缀。

### 完整配置参考

> **注意**：以下注释中标注的"默认值"是代码中的默认值。当前 `application.yml` 中的实际配置值可能不同（如 `keepAlive: true`、`enableWorkspaceContext: true`），且 agent-service 的数据库、MinIO、Router 地址目前为硬编码，需要直接修改 `application.yml` 文件。

```yaml
harness:
  # === 全局开关 ===
  enableWorkspaceContext: false     # 代码默认值。是否注入 AGENTS.md / skills 上下文到 system prompt
  enableMemoryHooks: false          # 是否启用内置记忆 Hook（Harnax 使用自定义 Hook，通常关闭）
  enableSessionPersistence: true    # 是否启用 session 自动持久化

  # === 沙箱配置 ===
  sandbox:
    enabled: false                  # 代码默认值。是否启用 Docker 沙箱
    image: python:3.11-slim         # 沙箱容器镜像
    workspaceRoot: /workspace       # 容器内工作目录
    isolationScope: SESSION         # 隔离级别: SESSION / AGENT / GLOBAL（无效值会静默回退为 SESSION）
    keepAlive: false                # 代码默认值。容器保活（请求结束后不销毁容器）

  # === MinIO 分布式存储 ===
  minio:
    enabled: false                  # 代码默认值。是否启用 MinIO
    endpoint: http://minio:9000     # MinIO 服务地址
    accessKey: minioadmin           # 访问密钥
    secretKey: minioadmin           # 安全密钥
    snapshotBucket: harnax-snapshots # 快照 Bucket
    storeBucket: harnax-store       # KV 存储 Bucket
    snapshotPrefix: snapshots/      # 快照对象前缀
    storePrefix: store/             # KV 对象前缀
```

### 会话持久化配置

会话持久化通过 `session` 配置块控制，独立于 harness 配置。会话数据存储在**独立的数据库**（默认 `agentscope`），与业务数据库（`harnax_admin`）分开。

```yaml
# application.yml 中的实际配置格式（Spring kebab-case）
session:
  jdbc-url: jdbc:mysql://DB_HOST:3306/agentscope?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
  username: root
  password: 123456
  database-name: agentscope
```

> **注意**：
> - 会话数据库默认为 `agentscope`，不是 `harnax_admin`。需要预先创建该数据库。
> - `MysqlSessionConfig.toDataSource()` 内部会强制设置 `serverTimezone=UTC`，覆盖 JDBC URL 中的时区设置。这意味着会话表中的时间戳始终以 UTC 存储。
> - 会话连接池独立于业务数据库连接池，参数硬编码为：`maximumPoolSize=10, minimumIdle=5, connectionTimeout=30000, idleTimeout=600000, maxLifetime=1800000`。
> - 匿名用户（userId 为空）在数据库中使用 `__anon__` 作为 user_id 值。
> - `tableName` 和 `createIfNotExist` 字段在 `LauncherBean` 中硬编码为 `"session_record"` 和 `true`，但 `MysqlAgentStateStore` 实际使用的表名为 `agent_state`。

### 配置项详解

#### 沙箱隔离级别（isolationScope）

| 级别 | 说明 | 适用场景 |
|------|------|---------|
| `SESSION` | 每个 session 独立容器，互不干扰 | 生产环境（默认推荐） |
| `AGENT` | 同一 agent 的所有 session 共享容器 | 同一 agent 的多个会话需要共享文件 |
| `GLOBAL` | 全局共享单个容器 | 开发测试 |

#### 容器保活（keepAlive）

- `true`：容器在请求结束后保持运行，后续请求复用同一容器。workspace 变更通过 `persistWorkspace()` 持久化到 MinIO
- `false`：每次请求创建新容器，从 MinIO 恢复 snapshot，请求结束后销毁。开销较大但隔离性更强

---

## 本地模式部署

### 架构

```
客户端 → Session Router → agent-service (×1)
                                │
                          harness-core
                          ├── 无沙箱（宿主机执行）
                          ├── 无 MinIO
                          └── MySQL (agent_state 表)
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
          MySQL (harnax_admin)    MySQL (agentscope)
           业务数据                  会话状态
```

### 适用场景

- 开发和测试环境
- 个人版单机部署
- 不需要沙箱隔离的简单场景

### 环境变量

> **重要**：当前 agent-service 的 `application.yml` 中，数据库、MinIO、Router 等地址为硬编码（如 `172.20.10.5`），不支持通过环境变量覆盖。部署时必须直接修改 `application.yml` 文件中的对应地址。仅 `HARNAX_AUTH_SECRET` 和 `SERVICE_ID` 支持环境变量。

```bash
# === 认证配置（支持环境变量） ===
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export SERVICE_ID="agent-0"           # 本实例在 UnifiedAuth 中的标识

# === 以下配置需要直接修改 application.yml ===
# spring.datasource.url              → 业务数据库 (harnax_admin)
# session.jdbc-url                   → 会话数据库 (agentscope)
# session.database-name              → 会话数据库名 (agentscope)
# harness.minio.endpoint             → MinIO 地址
# router.service.url                 → Session Router 地址
# agent.service.instance-id          → 本实例标识（集群模式每个实例不同）
# jwt.secret                         → JWT 签名密钥（需与 admin 一致）
```

### 需要修改的 application.yml 配置（本地模式）

```yaml
spring:
  datasource:
    url: jdbc:mysql://YOUR_DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: harnax
    password: harnax123

session:
  jdbc-url: jdbc:mysql://YOUR_DB_HOST:3306/agentscope?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
  username: root
  password: 123456
  database-name: agentscope

harness:
  enable-workspace-context: true
  enable-memory-hooks: false
  enable-session-persistence: true
  sandbox:
    enabled: false              # 关闭沙箱
  minio:
    enabled: false              # 关闭 MinIO

router:
  service:
    url: http://YOUR_ROUTER_HOST:8081

agent:
  service:
    instance-id: agent-service-1

jwt:
  secret: harnax-secret-key-2026-harnax-admin-backend-jwt-token-authentication  # 需与 admin 一致
```

### 启动命令

```bash
# 构建
cd /path/to/harnax
mvn clean package -DskipTests -pl harnax-agent/harnax-agent-service -am

# 启动
java -Xms512m -Xmx1024m \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar
```

### 本地模式行为说明

| 行为 | 说明 |
|------|------|
| Shell 执行 | 在宿主机本地执行，注意安全风险 |
| 文件操作 | 在宿主机 `local.tmp-dir` 目录下操作 |
| 会话状态 | 存储在 MySQL `agent_state` 表 |
| 快照 | 不启用，无 snapshot 上传/下载 |
| Memory/KV | 不启用分布式存储 |

---

## 集群模式部署

### 架构

```
客户端 → Session Router (redis 模式)
              │
    ┌─────────┼─────────┐
    ▼         ▼         ▼
agent-1   agent-2   agent-3     ← 多实例，各自内嵌 harness-core
    │         │         │
    │    ┌────┴────┐    │
    ▼    ▼         ▼    ▼
  Docker Engine    Docker Engine
  (沙箱容器池)      (沙箱容器池)
              │
         ┌────┴────┐
         ▼         ▼
      MinIO     MySQL
   (快照+KV)  (agent_state)
```

### 适用场景

- 生产环境
- 需要沙箱隔离的安全执行
- 多节点水平扩展
- 跨节点 session 迁移

### 前置条件

#### 1. 安装 Docker Engine

每个 agent-service 节点都需要安装 Docker：

```bash
# CentOS / RHEL
sudo yum install -y docker-ce docker-ce-cli containerd.io
sudo systemctl enable docker
sudo systemctl start docker

# 确保运行 agent-service 的用户有 docker 权限
sudo usermod -aG docker $(whoami)
```

#### 2. 部署 MinIO

```bash
# 单机 MinIO（测试/小规模）
docker run -d \
  --name harnax-minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  -v /data/minio:/data \
  minio/minio server /data --console-address ":9001"

# 验证
curl http://MINIO_HOST:9000/minio/health/live
```

harness-core 启动时会自动创建以下 Bucket：

| Bucket | 用途 |
|--------|------|
| `harnax-snapshots` | 沙箱 workspace tar 快照归档 |
| `harnax-store` | 分布式 KV 存储（Memory 等跨节点状态） |

Bucket 结构：

```
harnax-snapshots/
  └── snapshots/
      ├── <sessionId-1>.tar
      ├── <sessionId-2>.tar
      └── ...

harnax-store/
  └── store/
      ├── agents/<agentId>/sessions/<sessionId>/MEMORY.md
      └── ...
```

#### 3. 准备数据库

需要创建两个数据库：

```sql
-- 1. 业务数据库（harnax_admin，如果已部署 admin 服务则已存在）
CREATE DATABASE IF NOT EXISTS harnax_admin
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

-- 2. 会话数据库（agentscope，独立数据库）
CREATE DATABASE IF NOT EXISTS agentscope
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

`agent_state` 表由 `MysqlAgentStateStore` 在首次使用时自动创建（`createIfNotExist=true`）：

```sql
-- 自动创建的表结构（仅供参考，无需手动执行）
CREATE TABLE agent_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    state_type VARCHAR(32) NOT NULL,       -- 'SINGLE' 或 'LIST'
    json_value LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_slot (user_id, session_id, state_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 环境变量与配置

> **重要**：与本地模式相同，agent-service 的大部分配置为硬编码，需要直接修改 `application.yml`。集群模式下每个实例需要修改 `agent.service.instance-id` 为不同的值。

```bash
# === 认证配置（支持环境变量） ===
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
export SERVICE_ID="agent-0"           # 每个实例可以使用相同 ID
```

### 需要修改的 application.yml 配置（集群模式）

```yaml
spring:
  datasource:
    url: jdbc:mysql://DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: harnax
    password: harnax123

session:
  jdbc-url: jdbc:mysql://DB_HOST:3306/agentscope?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
  username: root
  password: 123456
  database-name: agentscope

harness:
  enable-workspace-context: true
  enable-memory-hooks: false
  enable-session-persistence: true
  sandbox:
    enabled: true               # 启用 Docker 沙箱
    image: python:3.11-slim     # 沙箱镜像（需提前拉取）
    workspace-root: /workspace
    isolation-scope: SESSION    # Session 级隔离
    keep-alive: true            # 容器保活
  minio:
    enabled: true               # 启用 MinIO
    endpoint: http://MINIO_HOST:9000
    access-key: minioadmin
    secret-key: minioadmin
    snapshot-bucket: harnax-snapshots
    store-bucket: harnax-store
    snapshot-prefix: snapshots/
    store-prefix: store/

router:
  service:
    url: http://ROUTER_HOST:8081

agent:
  service:
    instance-id: agent-service-1   # 每个实例必须不同

jwt:
  secret: harnax-secret-key-2026-harnax-admin-backend-jwt-token-authentication  # 需与 admin 一致
```

### 预拉取沙箱镜像

首次部署前在每个节点预拉取沙箱镜像，避免首次请求时等待下载：

```bash
docker pull python:3.11-slim
```

如果使用自定义镜像，修改 `harness.sandbox.image` 配置并提前拉取。

### 启动命令

```bash
# 构建
cd /path/to/harnax
mvn clean package -DskipTests -pl harnax-agent/harnax-agent-service -am

# 启动（节点 1 — 确保 application.yml 中 agent.service.instance-id=agent-service-1）
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
java -Xms1g -Xmx2g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar

# 启动（节点 2 — 修改 application.yml 中 agent.service.instance-id=agent-service-2）
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"
java -Xms1g -Xmx2g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar
```

### 集群模式沙箱生命周期

```
首次请求 (sessionId=abc)
  │
  ├── 检查本地是否有 sessionId=abc 的容器
  │     ├── 有 → 直接复用
  │     └── 无 → 从 MinIO 下载 snapshots/abc.tar
  │               ├── 下载成功 → 解压恢复 workspace → 创建容器
  │               └── 不存在 → 创建空 workspace → 创建容器
  │
  ├── 执行 Agent 任务（Shell/File 操作在容器内）
  │
  └── 请求结束
        ├── keepAlive=true → 容器保持运行，workspace tar 上传到 MinIO
        └── keepAlive=false → 容器销毁，workspace tar 上传到 MinIO

后续请求 (sessionId=abc, 可能命中不同节点)
  │
  ├── 检查本地是否有 sessionId=abc 的容器
  │     ├── 有 → 直接复用
  │     └── 无 → 从 MinIO 下载 snapshots/abc.tar → 恢复 workspace → 创建容器
  │
  └── 继续执行...
```

---

## 沙箱执行边界

理解哪些代码在哪里执行，对安全排查和功能调试至关重要：

| 组件 | 执行位置 | 说明 |
|------|---------|------|
| 内置 Shell Tool | Docker 容器内 | `docker exec -w /workspace <container> sh -c <cmd>` |
| 内置 Filesystem Tools | Docker 容器内 | 通过 `tar` 进行文件读写 |
| 自定义 Java Tools (ToolBox) | JVM 宿主机 | Java 方法直接执行 |
| MCP Tools | 远程 MCP Server | 通过 MCP 协议调用 |
| Skill 代码 | Docker 容器内 | Agent 将 skill 指令转化为 shell/file 操作 |

---

## 健康检查

```bash
# agent-service 健康检查
curl http://localhost:8082/api/health

# MinIO 健康检查
curl http://MINIO_HOST:9000/minio/health/live

# Docker Engine 检查
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker NOT available"
```

---

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|------|------|---------|
| 8082 | agent-service HTTP API + SSE | 集群模式下仅 Router 可访问 |

agent-service 不直接对外暴露，所有外部请求通过 Session Router 代理转发。

---

## 与其他服务的关联

| 调用方 | 关系 | 配置项 |
|--------|------|--------|
| harnax-admin | Agent 从 admin 获取模型配置、MCP 配置、Skill 定义 | 通过适配器接口（ChatModelConfigAdaptor 等） |
| harnax-session-router | agent-service 启动时注册到 Router，定时发心跳 | `router.service.url` |
| Docker Engine | 沙箱容器的创建、执行、销毁 | 本地 Docker Socket |
| MinIO | 快照上传/下载、KV 存储 | `harness.minio.*` |
| MySQL | 会话状态持久化（agent_state 表） | `session.*` |

---

## 运维管理

### 沙箱容器清理

> **注意**：harness-core 创建的沙箱容器没有设置特定的 Docker label，无法通过 label 过滤。需要通过容器名或镜像名来识别。

```bash
# 查看所有 python:3.11-slim 容器（默认沙箱镜像）
docker ps -a --filter "ancestor=python:3.11-slim" --format "table {{.ID}}\t{{.Names}}\t{{.Status}}"

# 清理所有已停止的沙箱容器
docker container prune --filter "ancestor=python:3.11-slim"

# 清理所有沙箱容器（包括运行中的）— 谨慎操作
docker ps -a --filter "ancestor=python:3.11-slim" -q | xargs docker rm -f
```

### 沙箱容器硬编码限制

`KeepAliveSandboxManager` 有以下不可配置的硬编码限制：

| 参数 | 值 | 说明 |
|------|------|------|
| `maxSize` | 100 | 单个 JVM 实例最多保活 100 个沙箱容器。超出时自动驱逐最久未使用的容器 |
| `maxIdleTimeMs` | 30 分钟 | 空闲超过 30 分钟的容器自动销毁 |

> **运维提示**：如果并发 session 数超过 100，旧的沙箱容器会被驱逐，下次请求时需要从 MinIO 恢复 snapshot，会增加延迟。

### MinIO 快照管理

```bash
# 使用 MinIO Client (mc) 管理快照
mc alias set harnax http://MINIO_HOST:9000 minioadmin minioadmin

# 列出所有快照
mc ls harnax/harnax-snapshots/snapshots/

# 查看快照大小
mc du harnax/harnax-snapshots/

# 删除指定 session 的快照
mc rm harnax/harnax-snapshots/snapshots/<sessionId>.tar

# 清理过期快照（保留最近 7 天）
mc rm --older-than 7d harnax/harnax-snapshots/snapshots/
```

### 日志排查

```bash
# 查看 agent-service 日志
tail -f /var/log/harnax-agent/agent-service.log

# 查看沙箱容器日志
docker logs <container-name>

# 查看 MinIO 相关错误
grep -i "minio\|snapshot" /var/log/harnax-agent/agent-service.log | tail -50
```

---

## 故障排查

### 沙箱相关

#### 容器创建失败

**症状**：日志报 `Cannot connect to Docker daemon`

**排查**：
```bash
# 检查 Docker 是否运行
docker info

# 检查 Docker Socket 权限
ls -la /var/run/docker.sock
```

**解决**：确保 Docker Engine 已启动，且运行 agent-service 的用户有 docker 组权限。

#### 镜像拉取失败

**症状**：日志报 `pull access denied` 或 `image not found`

**排查**：
```bash
# 手动拉取镜像
docker pull python:3.11-slim

# 如果使用私有仓库，检查认证
docker login <registry-url>
```

### MinIO 相关

#### Bucket 创建失败

**症状**：启动日志报 `MinIO bucket creation failed`

**排查**：
```bash
# 检查 MinIO 连通性
curl http://MINIO_HOST:9000/minio/health/live

# 检查认证
mc alias set test http://MINIO_HOST:9000 minioadmin minioadmin
mc ls test/
```

#### 快照上传/下载失败

**症状**：日志报 `Snapshot upload failed` 或 `Snapshot download failed`

**排查**：
```bash
# 检查 Bucket 是否存在
mc ls harnax/harnax-snapshots/

# 检查磁盘空间
mc du harnax/harnax-snapshots/
df -h /data/minio
```

### 会话相关

#### MySQL 沙箱状态 Key 兼容性

**已知问题**：agentscope-harness 默认的 `SessionSandboxStateStore` 生成的 session key 含路径分隔符 `/`（如 `sandbox/session/<sessionId>`），而 MySQL 会话存储可能对包含 `/` 的 key 有限制。

**设计文档说明**：`docs/harnax-harness-core.md` 中描述了一个 `MysqlCompatibleSandboxStateStore` 组件来将 `/` 替换为 `-`，但在当前代码中该组件尚未实现。如果遇到沙箱状态 key 相关的数据库错误，需要关注此问题。

| 原始 key | 期望修复后 key |
|----------|-----------|
| `sandbox/session/<value>` | `sandbox-session-<value>` |
| `sandbox/user/<agentId>/<value>` | `sandbox-user-<agentId>-<value>` |
| `sandbox/agent/<agentId>` | `sandbox-agent-<agentId>` |
| `sandbox/global` | `sandbox-global` |

#### 非流式调用超时

`HarnessAgentWrapper.call()` 非流式调用有硬编码的 **5 分钟超时**（`Duration.ofMinutes(5)`）。长时间运行的 Agent 任务可能因此失败，目前无法通过配置调整。

---

## 补充部署模式：无沙箱 + MinIO

除了本地模式和集群模式外，harness-core 还支持一种混合模式：**关闭沙箱但启用 MinIO**。

```yaml
harness:
  sandbox:
    enabled: false        # 关闭 Docker 沙箱
  minio:
    enabled: true         # 启用 MinIO
```

此模式下的行为：

| 行为 | 说明 |
|------|------|
| Shell 执行 | 宿主机本地执行 |
| 文件系统 | MinIO `RemoteFilesystemSpec`，按 SESSION 隔离 |
| Memory/KV | MinIO `BaseStore`，跨节点共享 |
| 快照 | 不适用（无沙箱则无 workspace tar） |

适用场景：需要跨节点共享 Memory 状态，但不需要沙箱安全隔离的环境。

---

## 跨服务密钥一致性

agent-service 与其他服务共享多个密钥，部署时必须保证一致：

| 密钥 | 涉及服务 | 配置项 | 说明 |
|------|---------|--------|------|
| JWT Secret | admin + agent-service | `jwt.secret` | 当前为硬编码值，两个服务必须相同。用户登录后请求 agent-service 时依赖此密钥验证 |
| Auth Secret | router + agent-service | `HARNAX_AUTH_SECRET` | 内部服务间 JWT 认证密钥，所有使用 UnifiedAuth 的服务必须一致 |
| Admin API Secret | admin + router | `ADMIN_INTERNAL_API_SECRET` | Router 调用 admin 内部 API 的凭证 |

> **当前状态**：`jwt.secret` 在 admin 和 agent-service 的 `application.yml` 中均为硬编码的相同值。如果修改了其中一个，必须同步修改另一个。

---

## JVM 参数建议

### 本地模式

```bash
java -Xms512m -Xmx1024m \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar harnax-agent-service-*.jar
```

### 集群模式

```bash
java -Xms1g -Xmx2g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/harnax-agent/heapdump.hprof \
  -jar harnax-agent-service-*.jar
```

集群模式需要更多内存，因为需要维护 Docker 容器状态、MinIO 连接池和沙箱 workspace 缓存。

---

## 文档版本

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-06-25 | 初始版本，覆盖本地模式和集群模式部署 |
