# harnax-agent-service 部署文档

## 服务概述

`harnax-agent-service` 是**分布式智能体运行时**：网页端 / IM 通道的对话请求经 router 转发过来，本进程负责把「一份 Agent 配置」装配成一个可运行的 HarnessAgent（模型 + 工具 + 技能 + MCP 服务 + 沙箱环境），并把会话状态、工作区快照、输出文件持久化到共享存储。

它与另外几个服务的关系是**配置只读、状态共享**：

| 方向 | 用途 |
|---|---|
| → admin | 通过内部 API 取 AgentSpec（含 MCP / 工具 / 技能的完整配置与已解密凭证）、换发 MCP 访问令牌、回写会话能力开关 |
| → router | 注册实例、心跳、由 router 完成跨实例转发与会话定位 |
| → MySQL | 两个库：`harnax_admin`（业务数据源）与 `agentscope`（会话 / 智能体状态） |
| → MinIO | 工作区快照、分布式 KV、输出文件 |
| → Docker daemon | 为每个会话创建沙箱容器 |

端口默认 `8082`（`AGENT_SERVICE_PORT` 可改），集群部署里对外发布为 `28082`。**它不直接面向浏览器**：对话流量应经 router，健康检查与运维端口也不建议暴露到公网。

---

## 系统部署总览

```
浏览器 / IM ──► nginx ──► router (×N) ──► agent-service (×N)
                              │                │
                              ├──► admin ──────┤  （取 AgentSpec、换 MCP 令牌）
                              │                ├──► MySQL  harnax_admin + agentscope
                              │                ├──► MinIO  snapshots / store / output
                              └────────────────┴──► Docker daemon（沙箱容器）
```

- router 与 agent-service 都是**多实例**服务，会话与实例的绑定关系存在 router 侧（集群模式用 MySQL + Redis）。
- agent-service 扩缩容不需要改 admin 或 router 的配置，只要 `ROUTER_SERVICE_URL` / `ADMIN_SERVICE_URL` 指向各自的入口。

---

## 环境依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | 构建产物按 Java 21 编译 |
| MySQL | 8.0 | 两个库：`harnax_admin`（表结构由 **admin** 的 Flyway 维护）与 `agentscope`（会话 / 智能体状态）。**`agentscope` 这个库要预先存在**——库内的 `agent_state` 表由本服务启动时 `CREATE TABLE IF NOT EXISTS` 建，但建库不归它 |
| Docker daemon | 必须可达 | 开启沙箱（`SANDBOX_ENABLED=true`）时要挂 `/var/run/docker.sock`，且容器内需有 `docker` CLI（镜像已装）：沙箱是通过 shell 调 `docker` 命令创建的，不是走 SDK。**裸 JVM 部署要自己保证 `docker` 在 PATH 上且当前用户可读 socket** |
| 沙箱镜像 | `harnax-sandbox:py-node`（`SANDBOX_IMAGE` 默认值） | 由 `build.sh` Step 7 调根目录的 `sandbox-plugins/build.sh` 构建（**必须在仓库根目录执行**），这一步**不需要 Go**——旧的 `harnax-sandbox:latest` 插件镜像已删除。按 CLI 组合派生的 `harnax-sandbox:cli-<hash>` 不归构建期：本服务在首个用到它的会话前现场 `docker build`（相关变量见下文 `SANDBOX_CLI_PACKAGE_CACHE_DIR` 起三行），构建失败只影响那个会话 |
| MinIO | 仅 `MINIO_ENABLED=true` 时需要 | 四个桶：`harnax-snapshots` / `harnax-store` / `harnax-output` / `harnax-cli-packages`（名字可改，见下文）。启动时会自动尝试建桶 |
| Redis | **不需要** | 路由与实例注册状态由 router 持有 |

---

## 配置边界：为什么 MCP 配置不从库里读

本服务确实持有一个指向 `harnax_admin` 的数据源，但 **MCP / 工具 / 技能的配置一律来自 admin 下发的 AgentSpec**，不走查库兜底。原因是密钥边界：MCP 的 `headers` / `envParams` 里标了 secret 的条目在库里是 AES-GCM 密文，而 **AES 密钥只存在于 admin**（`harnax.aes.secret-key`，见 `docs/deploy-harnax-admin.md`）。所以 admin 在下发前就把它解成明文扁平对象，本服务的 `PlaintextMcpConfigDecryptor` 只负责解析 JSON。

部署上的三条直接后果：

1. **admin 不可达 = 智能体配置拿不到**。会话会失败，本服务自身仍能启动。
2. **不要试图在这里配 AES 密钥**去“直连库读配置”：那条路已被删掉，留下它只会让某条代码路径把密文当明文交给上游。
3. **两边版本要对齐**：新增的下发字段（例如 MCP 的 `authType`）如果 admin 版本旧到不带它，本服务会按“无认证”处理并打 WARN（日志里能看到 `arrived with no auth type`）——这不是 agent 的 bug，是版本不匹配。

---

## 环境变量说明

### 服务与端口

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_SERVICE_PORT` | `8082` | HTTP 端口 |
| `AGENT_INSTANCE_ID` | `agent-service-1` | 实例 ID，**多实例必须互不相同**，否则 router 的实例表会互相顶掉 |
| `AGENT_HEARTBEAT_INTERVAL` | `10000` | 向 router 心跳的毫秒间隔 |
| `LOCAL_TMP_DIR` | `/tmp/harnax-agent` | 本地临时目录（工作区、下载中转） |
| `SERVICE_ID` | `agent-0` | 服务间认证里本服务的标识 |

### 数据库

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/harnax_admin?...` | 业务库连接串 |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `root` / `123456` | 业务库账号 |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `5` | Hikari 连接池 |
| `SESSION_JDBC_URL` | `jdbc:mysql://localhost:3306/agentscope?...` | **会话 / 智能体状态库**，与业务库分开 |
| `SESSION_USERNAME` / `SESSION_PASSWORD` | `root` / `123456` | 会话库账号 |
| `SESSION_DATABASE_NAME` | `agentscope` | 会话库名 |

> 生产环境 `SPRING_DATASOURCE_PASSWORD` / `SESSION_PASSWORD` 必须改；两个库可以用同一套账号，只要它对两者都有权限。

### MinIO（分布式状态与文件）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MINIO_ENABLED` | `false` | 关闭时工作区快照与分布式 KV 落在**本实例**，跨实例不可见；开启后输出文件检测才生效（见下一节） |
| `MINIO_ENDPOINT` | `http://localhost:9000` | 服务地址 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | `minioadmin` | 与 MinIO 一致 |
| `MINIO_SNAPSHOT_BUCKET` | `harnax-snapshots` | 沙箱工作区快照 |
| `MINIO_STORE_BUCKET` | `harnax-store` | 跨节点 KV（记忆等） |
| `MINIO_OUTPUT_BUCKET` | `harnax-output` | 输出文件 |
| `MINIO_SNAPSHOT_PREFIX` / `MINIO_STORE_PREFIX` | `snapshots/` / `store/` | 前缀 |

### 沙箱

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SANDBOX_ENABLED` | `false` | 开启后智能体的文件与命令操作在容器里执行 |
| `SANDBOX_IMAGE` | `harnax-sandbox:py-node` | 基础沙箱镜像，**必须预先构建** |
| `SANDBOX_WORKSPACE_ROOT` | `/workspace` | 容器内工作目录 |
| `SANDBOX_ISOLATION_SCOPE` | `SESSION` | 隔离粒度：`SESSION`（每会话一容器，推荐）/ `AGENT` / `USER` / `GLOBAL`。**填错不报错**——无法识别的值静默退回 `SESSION` |
| `SANDBOX_KEEP_ALIVE` | `true` | 会话空闲后保活容器，下次对话免冷启动。空闲容器由后台定时扫描回收（见下两个变量），不需要「下一次新会话」才顺带触发；重启后被重新接管的容器空闲时钟从接管时刻起算，所以崩溃遗留的容器会在一个空闲预算内自行回收。紧急清理仍可在宿主执行 `docker rm -f $(docker ps -aq --filter name=agentscope-sandbox-)`。单实例上限 `maxSize=100` 是硬编码的 |
| `SANDBOX_KEEP_ALIVE_MAX_IDLE_MS` | `1800000`（30 分钟） | 保活容器空闲多久后回收。**必须大于最长单轮对话**：时钟只在一轮开始挂载沙箱时刷新，轮中不刷新 |
| `SANDBOX_KEEP_ALIVE_SWEEP_INTERVAL_MS` | `300000`（5 分钟） | 回收扫描周期，首次扫描同样延迟一个周期。仅 `SANDBOX_ENABLED=true` 时该定时任务才装配 |
| `SANDBOX_NETWORK` | 空（`bridge`） | 需要容器按域名访问其他服务时填自定义网络名，例如 `docker-new_harnax-network` |
| `SANDBOX_CLI_PACKAGE_CACHE_DIR` | `/tmp/harnax-agent/cli-packages` | CLI 包 payload 的解包与缓存目录，按 `packageDigest` 分片，是 `docker build` 的上下文。**容器内路径**，构建上下文由客户端打包成 tar 流给宿主 daemon，所以不必与宿主共享文件系统；容器重建后缓存丢失只会重新从 MinIO 拉一次 |
| `SANDBOX_PLATFORM_ADMIN_URL` | 空（compose 里 `http://admin:8080`） | 包内 `${platform.adminUrl}` 的解析值，即沙箱内 CLI 回调 admin 的地址。**必须是容器可达的地址**。为空时该锚点解析不出值：受管 CLI 起得来但调不通 admin |
| `SANDBOX_PLATFORM_INTERNAL_TOKEN` | 回退 `ADMIN_INTERNAL_API_SECRET` | 包内 `${platform.internalToken}` 的解析值。compose 不单独注入，靠的就是与 admin 同值的那个密钥变量；留过占位默认值会同时踩中「admin 拒收占位密钥」那条 401（见部署文档的密钥一致性） |
| `MINIO_CLI_PACKAGE_BUCKET` | `harnax-cli-packages` | 取包用的桶，**必须与 admin 的 `minio.cli-package-bucket` 同值**（admin 登记时写进这个桶）。两侧读的是同一个环境变量名。**compose 目前不转发这个变量**，两服务都落在 yml 默认值上，因此在 `.env` 里设它不生效——真要换名得给 admin 与 agent-service 两个 `environment` 块同时加上 |

### 输出文件检测

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OUTPUT_DETECTION_ENABLED` | `true` | 检测 `/workspace/output/` 下新产物并提供下载 |
| `OUTPUT_MAX_FILE_SIZE` | `52428800` | 单文件上限（字节） |
| `OUTPUT_MAX_FILES` | `5` | 单次回答最多附带几个文件 |
| `OUTPUT_ADMIN_BASE_URL` | 空（compose 未设置） | 下载链接里 admin 的 origin；多网卡 / 反向代理场景需要显式填成前端能访问到的地址 |

### 其他开关

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HARNESS_ENABLE_WORKSPACE_CONTEXT` | `true`（compose 里置 `false`） | 是否把工作区 `AGENTS.md` 注入上下文。开着但沙箱仅在对话阶段激活时会刷出配置告警，故集群 compose 默认关掉 |
| `HARNESS_ENABLE_MEMORY_HOOKS` | `false` | 记忆钩子 |
| `HARNESS_ENABLE_SESSION_PERSISTENCE` | `true` | 会话持久化 |
| `HARNAX_MCP_STDIO_ENABLED` | `false` | **stdio 类型 MCP 服务的开关**，同一个变量同时决定 admin 的 `harnax.mcp.stdio-enabled`。详见下一节 |
| `ROUTER_SERVICE_URL` | `http://localhost:8081` | router 地址 |
| `ADMIN_SERVICE_URL` | `http://localhost:8080` | admin 地址 |
| `LOG_LEVEL` | `INFO` | 本服务包级别日志 |
| `MYBATIS_LOG_IMPL` | 代码默认 `StdOutImpl`；**compose 已改传 `Slf4jImpl`** | 默认会把每条 SQL 打到 stdout。打包部署现在默认安静，要恢复逐条输出就在 `.env` 里填回 `...stdout.StdOutImpl`，级别由 `MYBATIS_LOG_LEVEL` 控制 |
| `JWT_EXPIRATION` | `7200000` | 本服务签发的用户 token 有效期（毫秒），与 admin 的同名项不必相同但要保持合理 |
| `AGENT_CACHE_MAX_SIZE` | `500` | 内存中缓存的 agent 实例数上限（键为 sessionId）。这个 key 不在 `application.yml` 里，只能靠 relaxed binding 注入 |
| `DB_URL` / `SESSION_JDBC_URL` | compose 里的 mysql 地址 | 连接串已改成可在 `.env` 覆盖（原先是写死的），换外部数据库不必再编辑 compose |
| `HARNAX_AUTH_ENABLED` | `true` | 入向统一鉴权开关。**不建议关**：关掉等于 8082 上所有接口裸奔 |
| `HARNAX_AUTH_SKIP_PATHS` | `[]` | 免鉴权路径前缀列表。默认只跳过 `/health` 与 `/actuator`，因此 `curl /api/agent/health` 不带凭据会拿到 **401** |
| `HARNAX_AUTH_TOKEN_TTL_SECONDS` | `300` | 出向服务间 token 有效期 |
| `JAVA_OPTS` | compose 里 `-Xms256m -Xmx1g …` | 只作用于容器（Dockerfile 的 ENTRYPOINT 引用它）；注意 compose 同时给该容器 2048M 内存上限，堆外还要留余量 |

---

## 密钥一致性（最容易配错的一段）

| 变量 | 必须与谁一致 | 不一致的症状 |
|---|---|---|
| `JWT_SECRET` | **admin** | 用户已登录但调本服务被拒；网页端表现为对话起不来 |
| `ADMIN_INTERNAL_API_SECRET` | **admin / router / channel / scheduler** | 取不到 AgentSpec（内部 API 401），日志出现 `[InternalApiAuth] Invalid credentials` |
| `HARNAX_AUTH_SECRET` | router / channel / scheduler | 服务间调用 401 |
| `SANDBOX_PLATFORM_INTERNAL_TOKEN` | 默认**回退** `ADMIN_INTERNAL_API_SECRET`（compose 就这么落：不单独注入） | 沙箱内 CLI（如 `harnax`）调 admin 失败；显式设成别的值等于给 `${platform.internalToken}` 换了凭据，两件事必须同时改 |

`ADMIN_INTERNAL_API_SECRET` 有**两个方向**的用途，两处都要通：

- 出向：本服务拿它作为 Bearer 调 admin 的 `/api/admin/internal/**`。
- 回程：沙箱里的 `harnax-cli` 拿同一个值访问 admin 的**业务接口**（`/api/admin/**`，不是 internal）。

第十八轮起，admin 侧不再接受**仍是占位默认值**的这串密钥走业务接口（那串字符在仓库里是公开的）。因此留着默认值的部署会看到：会话与内部调用正常，但沙箱内 CLI 的操作一律 401，且 admin 启动时有一条 WARN 点名这件事。修法：在 `docker-new/.env` 里给它一个真值（≥32 字符），重启两侧容器——所有服务读同一个 `.env`，改一次即可。

> 与之相关但**不要顺手改**的是 `HARNAX_AES_SECRET_KEY`：那是 admin 侧的凭据加密密钥，只影响 admin，本服务没有也不需要它；换掉会让 admin 库里已存的 MCP / 工具凭据全部解不开（详见 `docs/deploy-harnax-admin.md`）。

---

## MCP 的两条部署开关

本服务装载 MCP 服务时逐条判断，两种情况会被**跳过而不是失败**：

- **stdio**：`HARNAX_MCP_STDIO_ENABLED=false`（默认）时，即使某条 stdio 配置到达本服务也不会起进程。这是第二道闸——admin 那道会先把这类行扣住不下发。两边都要开，stdio 才真正运行；只开一边是配置漂移，日志里会有明确一行。
- **OAuth 2.1（按用户）**：这类服务需要一个能代表用户的身份。渠道会话（`chn-`）背后没有平台账号，因此**不会加载 OAuth 类型的 MCP 工具**；网页 / 小程序会话与定时任务（以任务创建人身份运行）可以。用户没授权时，工具调用会失败并提示「请重新授权该 MCP 服务」，同一个原因在 15 秒内不会重复去问 admin。

装配阶段每跳过一条都会单独 WARN，并在结束时补一条汇总（日志原文 `was built with {} of {} bound MCP servers`，占位符填成实际数字）——用户报「智能体没有 MCP 工具」时，按 `bound MCP servers` 搜这一行。

---

## 扩缩容与宿主资源

- **`--scale` 现在起不来**：compose 里 `container_name: harnax-agent-service` 是固定名，且 `AGENT_INSTANCE_ID` 有默认值，两个副本会撞容器名并共用同一实例 ID。要多实例就复制服务块并各自改名、改 ID，而不是 `--scale`。
- **实例挂掉时正在跑的会话会断**：SSE 直连 agent-service 时断的是这一条连接；会话与实例的绑定在 router 侧，重连由 router 决定落到哪台。缓存 agent 数上限（`AGENT_CACHE_MAX_SIZE`，默认 500）按实例计，容量规划据此摊。
- **宿主磁盘会长**，四处：`harnax-snapshots` 桶里的 workspace 快照（回收取决于 MinIO 生命周期规则，代码不管）、`harnax-sandbox:cli-<hash>` 这类按所选 CLI 的 `payloadDigest` 组合哈希产出的镜像（**只在 `checkCommand` 验收失败时被 `docker rmi -f`，成功落地的没有任何回收**，所以每换一次二进制就多一个 tag）、`SANDBOX_CLI_PACKAGE_CACHE_DIR` 下按 `packageDigest` 分片的 payload 缓存、以及 `LOCAL_TMP_DIR` 下的临时工作区。部署说明里应写明定期回收策略，否则表现为宿主的 `docker system df` 一路涨。
- **构建顺序**：`build.sh` 必须在**仓库根目录**执行；镜像构建依赖 `docker-new/dist/agent-service/*.jar`，那是 Step 3 的产物，跳过 Step 3 会在 Step 8 报「文件不存在」。

---

## 单机部署（开发 / 试用）

```bash
# 1. 构建
cd /path/to/harnax
mvn clean package -pl harnax-agent/harnax-agent-service -am -Dmaven.test.skip=true

# 2. 启动
export SPRING_DATASOURCE_URL="jdbc:mysql://DB_HOST:3306/harnax_admin?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SPRING_DATASOURCE_PASSWORD="your_db_password"
export SESSION_JDBC_URL="jdbc:mysql://DB_HOST:3306/agentscope?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
export SESSION_PASSWORD="your_db_password"
export JWT_SECRET="与 admin 完全一致"
export ADMIN_INTERNAL_API_SECRET="与 admin 完全一致"
export ROUTER_SERVICE_URL="http://localhost:8081"
export ADMIN_SERVICE_URL="http://localhost:8080"
export SANDBOX_ENABLED=true            # 本机有 Docker 才开
export MINIO_ENABLED=true              # 本机有 MinIO 才开

java -Xms256m -Xmx1g -jar harnax-agent/harnax-agent-service/target/harnax-agent-service-*.jar
```

不需要沙箱与 MinIO 时把两个 `*_ENABLED` 留 `false`，其余照常——但此时**没有跨节点共享状态**，只适合单实例。

## 集群部署（docker-compose）

`docker-new/docker-compose.yml` 的 `agent-service` 服务已把上面这些值连好，要点：

- 构建镜像前先跑 `docker-new/build.sh`（Step 3 打 JAR，Step 7 建沙箱镜像，缺 Step 7 会导致会话期沙箱创建失败）。
- 容器挂载 `/var/run/docker.sock`：这是**宿主 Docker 的控制接口**，能访问它就等于能在宿主上起容器。这也是 stdio MCP 默认关闭的直接原因——沙箱内跑什么由 admin 侧的部署决定，而不是由某个能填表单的人决定。
- `SANDBOX_NETWORK` 默认指向 compose 自己的网络（`docker-new_harnax-network`），否则沙箱容器按域名访问不到其他服务。
- 多实例时每个实例必须有独立的 `AGENT_INSTANCE_ID`。

---

## 健康检查

```bash
# 带凭据访问（默认鉴权开着）
curl -H "Authorization: Bearer <token>" http://localhost:8082/api/agent/health
```

- 直接 `curl http://localhost:8082/api/agent/health` 得到的是 **401**，不是故障：`UnifiedAuthFilter` 只放行 `/health` 与 `/actuator` 前缀，而 `harnax.auth.skip-paths` 默认是空的。要把健康检查挂进编排，就给 `/api/agent/health` 开一个 skip-path，或者改用 `/actuator/health`（**本模块没有引 actuator 依赖，这条并不存在**）。
- 因此 `Dockerfile.agent-service` 里那条 `HEALTHCHECK /actuator/health` 在**不经 compose 覆盖**时永远不健康——compose 自己定义了 `test`，用的是 `28082` 那条带 401 容忍的探针。用 `docker run` 单起这个镜像时要一并覆盖健康检查。
- compose 的探针接受 HTTP 200 与 401（401 说明进程活着、只是要鉴权），不会因为加了认证而误判成不健康。

## 端口与防火墙

| 端口 | 用途 | 对外暴露 |
|---|---|---|
| 8082 | 智能体运行时 HTTP / SSE | 只允许 router 与运维网段访问，不直接对外 |

## 常见问题

| 现象 | 先看什么 |
|---|---|
| 会话起不来，日志 `Failed to get agent spec from admin` | admin 是否可达、`ADMIN_INTERNAL_API_SECRET` 是否两边同值、是否已不再是占位值 |
| 智能体没有 MCP 工具 | 汇总行 `bound MCP servers`（`was built with k of n bound MCP servers`），再看它前面每条单独 WARN（停用 / stdio 关闭 / 无用户身份 / 连不上） |
| OAuth 类 MCP 一调用就提示「请重新授权」 | 该用户是否真在 MCP 详情页授权过；渠道会话本来就不支持 |
| 沙箱创建失败 | `harnax-sandbox:py-node` 是否存在；Docker socket 是否挂上；`SANDBOX_NETWORK` 名字是否与实际网络一致 |
| 快照 / 输出文件下载不到 | `MINIO_ENABLED`、桶是否存在、`OUTPUT_ADMIN_BASE_URL` 是否指向前端能访问的 origin |
| 会话状态跨实例丢失 | 两个实例是否指向**同一个** `agentscope` 库与同一套 MinIO |

---

## 相关文档

- `prod_doc/mcp-management.zh-CN.md` §7.17：MCP 换发与运行侧注入的实现口径
- `prod_doc/mcp-authorization-design.zh-CN.md`：OAuth 2.1 方案与分期
- `docs/deploy-harnax-admin.md`：`HARNAX_AES_SECRET_KEY`、`ADMIN_INTERNAL_API_SECRET`、`HARNAX_MCP_STDIO_ENABLED`
- `docs/deploy-harnax-harness-core.md`：运行时（沙箱、快照、隔离粒度）的机制说明
- `docs/功能说明/平台功能说明.md` §三.3：面向使用者的 MCP 说明
