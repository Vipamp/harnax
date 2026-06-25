# harnax-harness-core 部署文档

## 服务概述

`harnax-harness-core` 是 Harnax 平台的分布式 Agent 运行时引擎，基于 `agentscope-harness` 构建，作为 `harnax-agent-service` 的核心模块运行。它不是一个独立部署的服务，而是嵌入 agent-service 进程中，提供以下核心能力：

- **Session 级沙箱隔离**：每个 session 绑定独立 Docker 容器，保证 workspace 一致性
- **分布式文件系统**：通过 MinIO（S3 兼容）实现跨节点文件共享和快照存储
- **Snapshot 自动恢复**：沙箱 workspace 自动打包为 tar 归档到 MinIO，跨节点请求时自动恢复
- **会话持久化**：支持 MySQL / JSON / 内存三种 AgentStateStore 后端

- **宿主服务端口**: 8082（agent-service）
- **依赖数据库**: harnax_admin（MySQL，业务数据 + agent_state 会话状态）
- **依赖外部服务**: Docker Engine（沙箱模式）、MinIO（分布式存储模式）

---

## 环境依赖

| 组件 | 本地模式 | 集群模式 | 说明 |
|------|---------|---------|------|
| JDK 21 | 必须 | 必须 | 运行时 |
| MySQL 8.0 | 必须 | 必须 | 业务数据 + 会话状态存储 |
| Docker Engine | 可选 | 必须 | Agent 沙箱执行环境 |
| MinIO | 不需要 | 必须 | 分布式文件存储 + 快照归档 |
| Redis | 不需要 | 不需要 | harness-core 不使用 Redis |
| harnax-admin | 必须 | 必须 | 模型配置、MCP 配置、Skill 加载等 |
| harnax-session-router | 必须 | 必须 | 会话路由 |

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

```yaml
harness:
  # === 全局开关 ===
  enableWorkspaceContext: true      # 是否注入 AGENTS.md / skills 上下文到 system prompt
  enableMemoryHooks: false          # 是否启用内置记忆 Hook（Harnax 使用自定义 Hook，通常关闭）
  enableSessionPersistence: true    # 是否启用 session 自动持久化

  # === 沙箱配置 ===
  sandbox:
    enabled: true                   # 是否启用 Docker 沙箱
    image: python:3.11-slim         # 沙箱容器镜像
    workspaceRoot: /workspace       # 容器内工作目录
    isolationScope: SESSION         # 隔离级别: SESSION / AGENT / GLOBAL
    keepAlive: true                 # 容器保活（请求结束后不销毁容器）

  # === MinIO 分布式存储 ===
  minio:
    enabled: true                   # 是否启用 MinIO
    endpoint: http://minio:9000     # MinIO 服务地址
    accessKey: minioadmin           # 访问密钥
    secretKey: minioadmin           # 安全密钥
    snapshotBucket: harnax-snapshots # 快照 Bucket
    storeBucket: harnax-store       # KV 存储 Bucket
    snapshotPrefix: snapshots/      # 快照对象前缀
    storePrefix: store/             # KV 对象前缀
```

### 会话持久化配置

会话持久化通过 `session` 配置块控制，独立于 harness 配置：

```yaml
session:
  # 方式一：MySQL 持久化（推荐用于生产环境）
  type: mysql
  jdbcUrl: jdbc:mysql://DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
  username: harnax
  password: harnax123
  databaseName: harnax_admin
  tableName: agent_state
  createIfNotExist: true

  # 方式二：JSON 文件持久化（适用于开发/测试）
  # type: json
  # path: /data/harnax/sessions

  # 方式三：不配置 session 块 — 使用内存存储（重启丢失）
```

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
                          MySQL (harnax_admin)
```

### 适用场景

- 开发和测试环境
- 个人版单机部署
- 不需要沙箱隔离的简单场景

### 环境变量

```bash
# === 数据库配置 ===
export SPRING_DATASOURCE_URL="jdbc:mysql://YOUR_DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SPRING_DATASOURCE_USERNAME="harnax"
export SPRING_DATASOURCE_PASSWORD="harnax123"

# === 会话数据库配置 ===
export SESSION_JDBC_URL="jdbc:mysql://YOUR_DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SESSION_DATABASE_NAME="harnax_admin"
export SESSION_USERNAME="harnax"
export SESSION_PASSWORD="harnax123"

# === 认证配置 ===
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"

# === 本地临时目录 ===
export LOCAL_TMP_DIR="/tmp/harnax-agent"
```

### application.yml 关键配置（本地模式）

```yaml
harness:
  enableWorkspaceContext: true
  enableMemoryHooks: false
  enableSessionPersistence: true
  sandbox:
    enabled: false              # 关闭沙箱
  minio:
    enabled: false              # 关闭 MinIO

session:
  type: mysql
  jdbcUrl: ${SESSION_JDBC_URL}
  username: ${SESSION_USERNAME}
  password: ${SESSION_PASSWORD}
  databaseName: ${SESSION_DATABASE_NAME}
  tableName: agent_state
  createIfNotExist: true
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

```sql
-- agent_state 表（如果 createIfNotExist=true 则自动创建）
CREATE TABLE IF NOT EXISTS agent_state (
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

### 环境变量

```bash
# === 数据库配置 ===
export SPRING_DATASOURCE_URL="jdbc:mysql://DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SPRING_DATASOURCE_USERNAME="harnax"
export SPRING_DATASOURCE_PASSWORD="harnax123"

# === 会话数据库配置 ===
export SESSION_JDBC_URL="jdbc:mysql://DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SESSION_DATABASE_NAME="harnax_admin"
export SESSION_USERNAME="harnax"
export SESSION_PASSWORD="harnax123"

# === 认证配置 ===
export HARNAX_AUTH_SECRET="your-secret-at-least-32-chars-long"

# === 本地临时目录 ===
export LOCAL_TMP_DIR="/tmp/harnax-agent"

# === Router 配置 ===
export ROUTER_SERVICE_URL="http://ROUTER_HOST:8081"

# === Agent 实例标识（每个实例不同） ===
export AGENT_INSTANCE_ID="agent-service-1"
```

### application.yml 关键配置（集群模式）

```yaml
harness:
  enableWorkspaceContext: true
  enableMemoryHooks: false
  enableSessionPersistence: true
  sandbox:
    enabled: true               # 启用 Docker 沙箱
    image: python:3.11-slim     # 沙箱镜像（需提前拉取）
    workspaceRoot: /workspace
    isolationScope: SESSION     # Session 级隔离
    keepAlive: true             # 容器保活
  minio:
    enabled: true               # 启用 MinIO
    endpoint: http://MINIO_HOST:9000
    accessKey: minioadmin
    secretKey: minioadmin
    snapshotBucket: harnax-snapshots
    storeBucket: harnax-store
    snapshotPrefix: snapshots/
    storePrefix: store/

session:
  type: mysql
  jdbcUrl: ${SESSION_JDBC_URL}
  username: ${SESSION_USERNAME}
  password: ${SESSION_PASSWORD}
  databaseName: ${SESSION_DATABASE_NAME}
  tableName: agent_state
  createIfNotExist: true
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

# 启动（节点 1）
export AGENT_INSTANCE_ID="agent-service-1"
java -Xms1g -Xmx2g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -jar harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar

# 启动（节点 2）— 配置相同，仅 AGENT_INSTANCE_ID 不同
export AGENT_INSTANCE_ID="agent-service-2"
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

```bash
# 查看所有 harnax 沙箱容器
docker ps -a --filter "label=com.agnetix.harnax" --format "table {{.ID}}\t{{.Names}}\t{{.Status}}"

# 清理所有已停止的沙箱容器
docker container prune --filter "label=com.agnetix.harnax"

# 清理所有沙箱容器（包括运行中的）
docker ps -a --filter "label=com.agnetix.harnax" -q | xargs docker rm -f
```

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

#### MySQL 兼容性问题

**已知问题**：agentscope-harness 默认的 `SessionSandboxStateStore` 生成的 session key 含路径分隔符 `/`（如 `sandbox/session/<sessionId>`），而 `MysqlSession.validateSessionId()` 拒绝包含 `/` 的 ID。

**解决方案**：harness-core 内置了 `MysqlCompatibleSandboxStateStore`，自动将 `/` 替换为 `-`。确保使用的是 harness-core 的实现而非框架默认实现。

| 原始 key | 修复后 key |
|----------|-----------|
| `sandbox/session/<value>` | `sandbox-session-<value>` |
| `sandbox/user/<agentId>/<value>` | `sandbox-user-<agentId>-<value>` |
| `sandbox/agent/<agentId>` | `sandbox-agent-<agentId>` |
| `sandbox/global` | `sandbox-global` |

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
