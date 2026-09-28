# Harnax 会话路由（Session Router）设计说明（中文）

本文覆盖 `harnax-session-router`（下文简称 Router）当前的完整实现：能力边界、认证分层、端点契约、会话绑定与实例生命周期、SSE 流式链路、租户隔离、调用日志、部署形态与可观测口径，以及当前限制清单。全部内容以 `kotlin-dev` 分支的代码为准。

## 1. 能力定位与边界

### 1.1 会话粘性这一约束的来源

一个会话（`sessionId`）在其生命周期内始终落到同一个 agent-service 实例上执行。原因是 Agent 的可变状态不在共享数据库里，而在那个进程的内存与本地磁盘上：

- Agent 实例对象与其 ReAct 循环上下文、缓存的 agent（`agentCache`）；
- workspace 沙箱容器与其中正在进行的计划；
- HITL 工具确认的挂起态（等待 `/confirm` 回到同一个实例才能续上）。

Router 为「会话 → 实例」这个约束提供放置决策与请求转发：监听 **8081**，Spring MVC + Kotlin 协程，进程自身无状态，可多副本水平扩展（副本共享同一份 Redis 路由状态）。

### 1.2 Router 承担与不承担的

| 事项 | 归属 |
|------|------|
| 会话到实例的绑定、放置、重路由、熔断 | Router |
| 请求转发与流式回传 | Router |
| 执行 Agent、调用模型 | `harnax-agent-service`（:8082） |
| 对话历史、计划、workspace 文件的存储 | agent-service（状态库 + snapshot） |
| API Key 是否有效、挂在哪个租户下 | `harnax-admin`（Router 远程查询 + 本地缓存） |
| 会话属于哪个租户 | admin 内部接口 `/api/admin/internal/sessions/{id}/info`，Router 只查询不裁决 |
| 渠道消息解析与回传 | `harnax-channel` |
| 定时任务调度 | `harnax-scheduler`（:8084） |

Router 自己持有的全部状态是：实例注册表、会话绑定、反向索引、熔断状态、幂等租约、调用日志。它不持有任何业务数据。

## 2. 拓扑与调用方

### 2.1 拓扑

```
                       ┌─ channel-service  （平台用户消息，内部 JWT）
                       ├─ webui            （管理台，登录态 JWT 或 API Key）
 外部调用方 ── nginx ──┼─ harnax-app       （H5 / 小程序，API Key）
    80 → 443           ├─ harnax-client SDK（外部系统集成，API Key）
                       └─ harnax-admin     （会话与 workspace 调用，API Key）
                                │
                                ↓  X-Api-Key 或 Bearer JWT
                       ┌────────────────────┐
        ┌──────────────│   Router :8081     │───────────┐
        │              └────────────────────┘           │
        │                        │                      ↓ 内部 JWT
   admin :8080                Redis :6379        agent-service :8082 (×N)
 （Key 校验、会话归属、       （实例注册表、         │
   原始密钥字符串比对）        会话绑定、熔断、       │ 注册 / 心跳（每 10s）
                              幂等租约）            ↓
                             MySQL :3306   ┌── POST /api/router/instance/*
                           （调用日志库）    │  agent-service 注册自己
                                            └── 调用日志落库（api_call_log）
```

### 2.2 调用方矩阵

| 调用方 | 使用的端点 | 凭证 | 说明 |
|--------|-----------|------|------|
| channel-service | `/agent/chat/stream`、`/agent/command`、`/agent/session/{id}` DELETE | 内部 JWT（`typ=internal`） | 代表已认证用户转发；不携带租户，故不进入租户比对 |
| webui | `/agent/**` 全量（含 workspace）、`/monitor/**` | Bearer JWT 或 `X-Api-Key` | 经 nginx `/api/router/` 代理 |
| harnax-app（H5/小程序） | 对话 + workspace 子集 | `X-Api-Key` | 可直连 28081，也可走 nginx |
| harnax-client SDK | `/agent/**` 对应方法 | `X-Api-Key` | — |
| agent-service | `/instance/register`、`/instance/heartbeat`、`/instance/unregister` | 内部 JWT | `@InternalOnly`，外部凭证一律 403 |
| admin / 运维 | `/instance/drain`、`/instance/list`、`/health`、`/metrics/cache`、`/monitor/**` | 内部 JWT（`/monitor/**` 亦可管理员 Key） | — |
| scheduler | `/agent/chat`、`/agent/command`、`/agent/session/{id}` DELETE | 由自身配置决定 | 走 `task-` 前缀规则，见「会话 id 前缀规则」 |

## 3. 认证与授权分层

### 3.1 入站：API Key 与内部 JWT

`UnifiedAuthFilter`（`harnax-auth`，`@Order(HIGHEST_PRECEDENCE + 10)`）在 `/api/router/**` 之前建立 `AuthContext`：

| 步骤 | 行为 |
|------|------|
| 免认证前缀 | `/health`、`/actuator` 加上 `harnax.auth.skip-paths`（仅静态资源） |
| `Authorization: Bearer …` | `InternalTokenProvider.verifyToken` 解签并分类，失败即 401 |
| `X-Api-Key: …` | `ExternalApiKeyValidator` 做 SHA-256 后查 `ApiKeyStore`，失败即 401 |
| 两者皆无 | 401，响应体为 `ResultVo` |

`verifyToken` 的分类规则决定了内部与外部的界线：

- 携带 `typ=internal` claim → `CallerType.INTERNAL_SERVICE`，可同时带 `userId` / `tenantId`；
- 无 `typ` 但带 `userId` → 按 `EXTERNAL_API` 处理（登录签出的 JWT 拿不到内部身份，即使运维把 `jwt.secret` 与 `harnax.auth.internal.shared-secret` 配成同一个值）；
- 既无 `typ` 也无 `userId` → 抛 `SecurityException`，不认这个 bearer。

API Key 侧：`RemoteApiKeyStore`（`@Primary`）调用 admin 的 `/api/admin/internal/api-keys/validate`，用 Caffeine 缓存 1000 条 / 写后 5 分钟，命中与未命中都缓存；`enabled=false` 或 `expiresAt` 已过 → 401。Key 的租户与 owner 身份完全来自 admin 的应答，Router 不解释 Key 本身。

### 3.2 API Key 的权限范围：chat / manager

`scopes` 是逗号分隔的字符串，admin 建 Key 时缺省写 `chat`，管理台提供 `chat`（对话）与 `manager`（管理）两种取值。Router 把 scopes 原样带进 `AuthContext`，但**它的任何准入判定都不读 scopes**：

- 内部专属端点由 `callerType` 决定（`InternalAuthorizationInterceptor` 只认 `INTERNAL_SERVICE`）；
- 会话作用域端点由租户归属决定（`SessionAccessGuard`）；
- 频率由 Key 上的 `rateLimit` 决定。

因此「一把 Key 能做什么」在 Router 侧等价于：它是内部身份还是外部身份、它挂在哪个租户、它每分钟能发多少次。`chat` 与 `manager` 的区分发生在 admin 侧的管理接口上。

### 3.3 出站：Router 到 agent-service 与 admin

| 目标 | 认证方式 | 实现 |
|------|----------|------|
| agent-service | `Authorization: Bearer <内部 JWT>` + `X-Caller-Id` | `RouterConfig.authFilter()` 每次请求向 `InternalTokenProvider.authHeaders()` 取头，令牌 TTL 300s、剩余不足 60s 时重新签发 |
| admin 内部接口 | `Authorization: Bearer <原始密钥字符串>` | `AdminClientService` 构造 WebClient 时挂 `defaultHeader`，值来自 `admin.internal-api.secret` |

admin 侧的 `InternalApiAuthFilter` 对 `/api/admin/internal/**` 做的是**原始字符串相等比对**（`token == secret`），不解析 JWT。若调用方误发 JWT，401 的响应文本会点名这一点。两条出站链路的凭证体系不同，配置时要分别对齐 `HARNAX_AUTH_SECRET` 与 `ADMIN_INTERNAL_API_SECRET`。

Router 调 admin 的两类请求：Key 校验（`validateApiKey`）与会话归属查询（`lookupSession`）。会话 id 通过 `pathSegment()` 拼装而非字符串插值，避免一个带 `../` 的 id 把请求指向另一个内部端点并带走这把密钥。

### 3.4 频率与异步派发

- `RateLimitInterceptor` 只对 `EXTERNAL_API` 生效，按 `callerId` 在 60 秒滑动窗口内计数，超限直接 **HTTP 429** + `Retry-After: 60`，响应体 `ResultVo(429, …)`；`rateLimit` 为空的 Key 不限流。
- Router 的计数器是进程内的（`RateLimiter`），多副本部署时每个副本各算各的。
- `InternalAuthorizationInterceptor` 只在 `DispatcherType.REQUEST` 上判定，异步派发（SSE）不重复判定，因为 `AuthContextHolder` 是线程局部的。

## 4. 会话绑定与实例生命周期

### 4.1 Redis 键布局

`redis` 模式下全部共享状态落在 Redis，值经 JSON 序列化（字符串带引号），键名用明文序列化：

| 键 | 类型 | 内容 | 存活 |
|----|------|------|------|
| `router:session:<sessionId>` | String | 绑定的 `instanceId` | 24h，每次请求续期 |
| `router:instance_sessions:<instanceId>` | Set | 该实例上的 sessionId 集合（反向索引，负载统计与搬家依据） | 24h，随索引内绑定一起续期 |
| `router:lock:session:<sessionId>` | String | 放置互斥锁令牌 | 10s |
| `router:instance:<instanceId>` | Hash | `host` / `port` / `status` / `lastHeartbeat` / `active` | 24h，心跳续期 |
| `router:instances:healthy` | Set | 可接受新会话的实例集合 | — |
| `router:instances:all` | Set | 全部在册实例 | — |
| `router:circuit:<instanceId>` | Hash | 熔断状态与连续失败数（0 关闭 / 1 打开 / 2 半开） | 打开时长 30s |
| `router:idempotency:<requestId>` | String | 在途请求租约 | 60s，请求结束即删 |
| `router:lock:index_reconcile` | String | 反向索引对账的全局锁 | 4min |

一次状态变更至少涉及两个键（绑定 + 反向索引，或实例状态 + healthy set），因此每次变更都是**一个多键 Lua 脚本**：`BIND` / `CLAIM`（NX 建立）/ `MOVE_IF_FROM`（比较并交换搬家）/ `UNBIND_SESSION` / `REFRESH_TTL` / `REBIND_BATCH` / `UNBIND_BATCH` / `HEARTBEAT` / `MARK_DOWN` / `MARK_DRAINING` / 锁释放。在 Kotlin 里做读-改-写会让第二个副本覆盖第一个副本的绑定，并留下指向已不服务该会话的实例的索引项。

### 4.2 绑定生命周期

| 事件 | 行为 |
|------|------|
| 首次请求一个未绑定会话 | 放置决策 + `CLAIM` 原子写入 |
| 经过该会话的请求成功 | `refreshActiveTime` 同时续期绑定与反向索引 |
| 24h 无任何请求 | 绑定与索引一起过期，下次请求重新放置 |
| 绑定所在实例 DOWN | 由健康检查批量搬到其它实例 |
| 实例 `unregister` | `unbindInstanceSessions` 删除该实例全部绑定 |
| 清除会话（`DELETE /agent/session/{id}`） | 只转发给实例执行，绑定保留 |

TTL 在两种缓存模式下是同一个常量：`LocalSessionMappingService` 的 `bindingTtl` 默认取 `RedisSessionMappingService.SESSION_TTL`（24h）。24h 描述的是「一个会话离开 Router 视野一天之后允许换实例」，与会话的业务有效期无关，业务生命周期在 admin 的 session 表里。

### 4.3 放置决策

`selectLeastLoadedInstance` 对候选实例做加权随机，权重 `1 / (当前会话数 + 1)`，只有 1 个候选时直接返回。候选来自 `instanceRegistry.getHealthyInstances()`，再减去：

- 调用方已见过失败的实例（`excluded` 集合）；
- 熔断中的实例（`placementExclusions` → `circuitBreaker.trippedInstances`）；
- DRAINING 的实例（不在 healthy set 里，`MARK_DRAINING_SCRIPT` 会把它从 set 中摘掉）。

**放置排除只影响新会话**。已经绑定在某实例上的会话不会因为该实例熔断而搬家——按熔断读数搬家会一次动光该实例上的全部会话，把一个慢实例放大成集群级的重绑风暴，而且下一次请求绑定又回来了。会话搬家的唯一触发条件是实例 DOWN。

`rerouteSession` 的并发处理：先尝试取 10 秒的会话锁（取不到不失败，锁只是让放置更整齐），随后最多 3 轮读-选-比-换。若别的副本已把该会话放到一个可接受的实例上，直接沿用并续期；Redis 拒写时走 `placeUnpersisted`（见「Redis 不可达时的行为」）。没有任何健康候选时，若现有绑定仍在接受新会话就保留它，否则抛 `IllegalStateException("No healthy agent-service instances available for session …")`。

### 4.4 实例状态机

```
（新实例 / 重新注册） --register---------> UP        写入 status=UP，并加入 healthy 与 all 两个 set
UP                  --heartbeat---------> UP        刷新 lastHeartbeat 与 TTL
DOWN                --heartbeat---------> UP        DOWN 只由心跳超时产生，因此一次成功心跳即可收回
UP                  --/instance/drain---> DRAINING  从 healthy set 摘掉
DRAINING            --heartbeat---------> DRAINING  心跳保留 DRAINING，不回置 UP
UP / DRAINING       --心跳停 30s--------> DOWN      CAS 判定，多副本只有一个赢家；健康检查每 5s 扫描
任意状态            --unregister--------> （删除实例键，并从两个 set 摘掉）
```

| 状态 | 接受新会话 | 已有绑定继续服务 | 计入健康实例数 |
|------|-----------|------------------|----------------|
| `UP` | 是 | 是 | 是 |
| `DRAINING` | 否 | 是 | 否 |
| `DOWN` | 否 | 触发批量搬家 | 否 |

**心跳可以收回 DOWN**：`HEARTBEAT_SCRIPT` 在状态不是 DRAINING 时把状态写回 UP 并重新加入 healthy set，所以一次网络抖动造成的 DOWN 在该实例恢复心跳后即归队，无需重新注册——但已经搬走的会话不会搬回来。`MARK_DOWN_SCRIPT` 对已经是 DOWN 的实例返回 0，因此同一台实例的搬家不会被重复触发。

`AgentInstance.isHealthy` 把 DRAINING 视为「活着」——它表示「不接新会话」而不是「已死」；若视为不健康，健康检查会在一个扫描周期内把它判成 DOWN 并强制搬走全部会话，优雅下线就失去意义。

**drain 是单向门**：没有 `undrain` 端点，心跳脚本明确保留 DRAINING 不被重置回 UP。要恢复接新会话，只能让该实例重新 `register`。这意味着 drain 服务于「准备下线」，不服务于「临时摘流量看日志」。

### 4.5 故障转移与搬家

`HeartbeatHealthChecker.checkInstanceHealth`（`@Scheduled` 固定延迟 5s）：

1. 把 `!isHealthy(30s)` 的实例逐个处理，每个都重新取一次健康快照，避免把会话从一个刚死的实例搬到另一个同批死亡的实例；
2. 对同一实例的搬家有 10 秒冷却（`failoverCooldownMs`，硬编码），冷却记录每 60 秒清一次、条目 5 分钟后视为过期；
3. `markInstanceDown` 是 CAS 的：返回 0 行说明别的副本已经判定，直接跳过；
4. 目标实例排除熔断中的（全部熔断时不排除——会话卡死比搬一次更糟），按会话数最少选择；
5. 过载避让：目标会话数 > 集群均值 × 2 时，改选 ≤ 均值 × 1.5 中负载最低的；
6. `rebindAllSessions` 用 `REBIND_BATCH_SCRIPT` 每批 500 个搬，最多 200 批；没搬完的留给对账器；
7. 反向索引由 `SessionIndexReconciler` 兜底：每 5 分钟一轮，全局锁保证单节点执行，两趟（清理指向已不存在绑定的索引项、补回丢失的索引项），单趟最多扫 50000 个键，超出留给下一轮。

### 4.6 驱逐语义：STOP_SANDBOX 而不是 CLEAR

会话搬家后，`SessionEvictor` 通知被离开的实例停掉这个会话的沙箱：

- 发的是 `CommandType.STOP_SANDBOX`。`CLEAR` 会连同对话历史与计划一起删掉，而那些存在所有实例共享的状态库里——一个实例丢掉会话，不该删掉下一个实例还要用的东西；
- `STOP_SANDBOX` 在销毁容器前先落 workspace snapshot，会话若回到这个实例，文件仍然在；
- 发送前先向绑定存储确认「这个会话确实离开了」，Redis 不可达期间放置是本节点记账的，凭这个就发驱逐会停掉一个根本没搬家的会话的沙箱；
- 在独立线程池执行（1～2 线程，队列 `router.migration.max-pending=200`），溢出丢弃并计数告警，不占请求线程，失败不向调用方传播；
- 由 `router.migration.evict-old-instance`（默认 `true`）控制。

### 4.7 清除会话与释放失败

`DELETE /api/router/agent/session/{sessionId}` 的行为：

| 情形 | 结果 |
|------|------|
| 该会话从未被本 Router 放置过 | 直接返回 `code=200`，消息说明未绑定；不联系任何实例 |
| 已绑定 | 只向该实例转发一次 `DELETE /api/agent/session/{id}`，不重试到其它实例 |

agent 侧的执行链：`DefaultAgentRunner.clearSession` → 先 `interrupt`、清 `agentCache`、清状态库与计划笔记、并对团队子会话逐个清理，然后 `KeepAliveSandboxManager.destroy` 先持久化 workspace snapshot 再 `docker rm -f`。**snapshot 保留**，历史与计划被清除。

Router 不做的事：不清除绑定。清除记录后会话仍指向同一实例。

释放失败的处理在 admin 侧：`SessionRuntimeReleaser.release` 读 Router 返回的 `ResultVo.isSuccess()`，不成功就抛 `BizException` 并带上运行时自己的那句话。也就是说，删除会话（管理台与移动端）与删除频道（其 `chn-{uuid}` 会话没有独立 session 行）都建立在「运行时说不放行就不写这个删除」之上。未绑定被 Runtime 侧视为成功，因此一个从没跑起来的会话照样能删。

`callBound` 与 `executeWithRetry` 的区别值得写明：写类端点（chat / command）失败后换实例重试；读类与清除类端点**只打一次**绑定的那台实例——第二台机器没有第一台被要求提供的东西，把失败报告给调用方比编造一个空结果更有用。只有「这台实例不回话」类失败才计入熔断。

## 5. 端点契约

### 5.1 对话代理（14 个，前缀 `/api/router/agent`）

全部以 `sessionId` 为路由键，全部经过 `SessionAccessGuard.requireAccessible`。

| 方法与路径 | 请求体 | 返回 | 用途 |
|------------|--------|------|------|
| `POST /chat` | `ChatAgentRequest` | `ResultVo<ChatResponse>` | 非流式对话 |
| `POST /chat/stream` | `ChatAgentRequest` | `Flux<ChatEvent>`（SSE） | 流式对话（主路径） |
| `POST /command` | `CommandAgentRequest` | `ResultVo<CommandResponse>` | 命令式控制（clear / stop / compact / …） |
| `POST /confirm` | `ConfirmAgentRequest` | `Flux<ChatEvent>`（SSE） | HITL 工具确认，续接挂起的执行 |
| `DELETE /session/{sessionId}` | — | `ResultVo<String>` | 清除会话记录 |
| `GET /chat/history/{sessionId}` | — | 透传 | 对话历史 |
| `GET /session/{sessionId}/plans` | — | 透传 | 计划列表 |
| `GET /session/{sessionId}/current-plan` | — | 透传 | 当前计划 |
| `GET /workspace/{sessionId}/files` | `path`（默认 `/workspace`） | 透传 | 列目录 |
| `GET /workspace/{sessionId}/read` | `path` | 透传 | 读文件文本 |
| `GET /workspace/status` | `sessionIds`（逗号分隔，必填） | 透传 | 多会话 workspace 状态 |
| `GET /workspace/{sessionId}/status` | — | 单会话状态 | 无数据时返回 `{"active": false}` |
| `POST /workspace/{sessionId}/upload` | multipart `file` + `path` | 透传 | 上传到 workspace |
| `GET /workspace/{sessionId}/download` | `path` | 文件流 | 下载产出文件 |

请求体字段与身份：

```kotlin
ChatAgentRequest(sessionId, message, imageUrls = [], requestId = "", userId = null)
CommandAgentRequest(sessionId, command: CommandType, args = "", userId = null)
ConfirmAgentRequest(sessionId, isConfirmed, toolInfoList = [], toolResults = [], userId = null)
```

`resolveUserId` 的优先级：认证上下文里的终端用户身份高于请求体的 `userId`。可信身份存在而 body 报了别的值时，用可信身份并打 warn；只有拿不到身份（不带 `userId` 的内部令牌）时才采用 body 值并打 info——这是 body 身份唯一被信任的场合。

`/workspace/status` 把整串 id 发给一台实例回答，因此 `IdFormat.parseSessionIds` 拆出的每一个 id 都单独过 guard，只查第一个会让剩下的 id 成为跨租户读取会话内容的入口。

下载与上传的边界：

- 文件名是客户端给的，Router 会把它写进日志、转发给 agent 的 multipart、以及下载的 `Content-Disposition`。`safeFileName` 去掉目录部分、去掉引号与控制字符、截到 128 字符、空则 `unnamed`；
- 下载超过 `MAX_DOWNLOAD_SIZE = 50 MB` 时 Router 直接返回 **HTTP 413**；agent 返回空体时返回 **HTTP 404**（这两个走真实状态码，不是 `ResultVo`）。

### 5.2 只读端点的未绑定行为

未绑定（从未对话过、或绑定已过期、或所在实例已注销）时 Router 不联系任何实例，各端点的答复不同：

| 端点 | 未绑定时 |
|------|----------|
| `DELETE /session/{id}` | `code=200`，`data` 为说明文本 |
| `GET /chat/history/{id}`、`/plans`、`/current-plan` | `code=200`，`data` 为空列表 / null |
| `GET /workspace/{id}/files`、`/workspace/status` | `code=200`，空列表 / 空映射 |
| `GET /workspace/{id}/read` | `code=404`，说明未绑定所以没有 workspace |
| `POST /workspace/{id}/upload` | `code=409`，提示先发消息再上传 |
| `GET /workspace/{id}/download` | HTTP 404 |
| `POST /chat`、`/command`、`/chat/stream`、`/confirm` | 立即放置到新实例 |

含义上，未绑定等价于「这个会话还没开始」。这些答复刻意不伪装成"内容真的是空的"，也不通过把会话放到别的实例来制造一个空目录。

### 5.3 实例管理（前缀 `/api/router`，整个 controller 标 `@InternalOnly`）

| 方法与路径 | 调用方 | 语义 |
|------------|--------|------|
| `POST /instance/register` | agent-service | 参数 `instanceId` / `host` / `port`；host 必须是 IPv4 字面量、不在黑名单、端口在 8000–9999 |
| `POST /instance/heartbeat` | agent-service | 刷新心跳；未注册时响应体 `code=410`（HTTP 仍 200），agent 据此重新注册 |
| `POST /instance/unregister` | agent-service | 下线并解绑该实例全部会话 |
| `POST /instance/drain` | 运维 / admin | 优雅下线；实例不存在时响应体 `code=404` |
| `GET /instance/list` | 运维 | 实例与状态 |
| `GET /health` | 运维 | 容量报告：`healthyInstances > 0` 即 `UP`。这不是本节点健康，容器与负载均衡检查用 `/actuator/health/*` |
| `GET /metrics/cache` | 运维 | 当前生效的注册表 / 绑定 / 幂等实现类名 |

因为类上标了 `@InternalOnly`，外部 API Key 调用其中任何端点（含 `GET /health`、`/metrics/cache`）都得到 403。

注册校验有两道：`InstanceRegistrationValidationFilter`（`@Order(HIGHEST_PRECEDENCE + 20)`，先于鉴权，读的是 URL 参数而不是 body，并按 `;` 截断矩阵参数后再匹配路径）以真实 HTTP 状态码拒绝脏参数——`instanceId`/`host` 格式不合法 400、被拦地址 400、端口越界 400、特权端口 403；控制器再独立复查 IPv4 与端口范围，答复在 `ResultVo` 里。这道校验放在注册入口，是为了让脏地址根本不进注册表——注册口是 SSRF 的主要风险面。

### 5.4 监控与静态资源

| 路径 | 认证 | 内容 |
|------|------|------|
| `GET /api/router/monitor/instances` | 需要凭证 | 实例地址、端口、状态、心跳年龄、会话数。这是集群拓扑，不属于任何租户，因此不下钻到会话内容 |
| `GET /api/router/monitor/call-logs` | 需要凭证 | 调用记录分页查询，按调用方租户收口（见「调用日志」） |
| `/ui`、`/index.html`、`/static/`、`/style.css`、`/app.js`、`/favicon.ico` | 免认证 | 仅是渲染监控页的静态资源；页面上的数据请求要带凭证 |

监控端点不标 `@InternalOnly`：浏览器不持有服务间凭证，运维用的是自己的 JWT 或管理员 Key。免认证路径只有静态资源——把 `/api/router/` 加进 `harnax.auth.skip-paths` 会让 `heartbeat` / `register` 失去 `AuthContext`，`InternalAuthorizationInterceptor` 便无从判定。

### 5.5 幂等

| 端点 | 去重 | 条件 |
|------|------|------|
| `POST /chat` | 是，仅当 `requestId` 非空 | 租约键 = `requestId` |
| `POST /chat/stream`、`/confirm`、`/command` | 否 | — |

`proxyChatRequest` 只在客户端自己给出 `requestId` 时去重（Router 生成的 id 按定义唯一，守它要多一次往返且什么都拒不掉）。语义是**在途租约**而不是重放窗口：`tryAcquire` 用 `SET NX` 占位，请求结束在 `finally` 里 `release` 删掉，60 秒 TTL 只覆盖进程中途死掉的情况。占不到租约时返回 `code=429`、消息 `Duplicate request: {requestId}`。Redis 不可达时 `tryAcquire` 放行（宁可放过一次重复，也不停止服务）。

## 6. 响应与错误语义

### 6.1 非流式：HTTP 200 + 业务码

`GlobalExceptionHandler` 把未捕获异常写成 `ResultVo`，HTTP 状态码保持 200：

```json
{ "code": 500, "message": "No healthy agent-service instances available for session web-…", "data": null }
```

`HarnaxException` 会带出自己的数字码，其余异常统一 `code=500`。这是 webui 的请求封装要求的形状：它从 2xx 响应的 `code` 字段判断成败，非 2xx 会被当成网络故障吞掉。**判断成败看 `body.code`，不看 HTTP 状态码。**

Router 自己在控制流里给出的非 500 业务码：429（重复请求）、404（workspace 读未绑定、drain 未知实例）、409（workspace 上传未绑定）、410（心跳未知实例）。

### 6.2 流式：错误就是事件

`/chat/stream` 与 `/confirm` 声明 `text/event-stream`。所有失败路径——归属拒绝、id 格式非法、无实例、转发失败、搬家失败——都以事件流终止：

```kotlin
ErrorChatEvent(code = HarnaxErrorCode.code, message = …)   // code 是字符串码
EndEventChatEvent()
```

`code` 取的是 `HarnaxErrorCode` 的字符串码：`2002` FORBIDDEN（归属拒绝）、`1003` INVALID_PARAM（id 非法）、`6011` ROUTER_NO_INSTANCE（放置失败）、`6010` ROUTER_PROXY_ERROR（转发/搬家失败）。**不要用 HTTP 状态码语义去比较这个数字。** 正常内容事件为 `StreamThinkingChatEvent`、`StreamTextChatEvent`、`CallToolChatEvent`、`ToolResultChatEvent`、`ToolConfirmChatEvent`（HITL 挂起，需回 `/confirm`），收尾为 `EndEventChatEvent`（带 `tokenUsage`）。

调用方处理规约：读到 `ErrorChatEvent` 即业务失败并结束，不要重试整个流。

### 6.3 使用真实 HTTP 状态码的场合

| 场景 | HTTP | 出处 |
|------|------|------|
| 凭证缺失 / 无效 / Key 停用或过期 | 401 | `UnifiedAuthFilter` |
| Origin 不在 CORS 白名单 | 403（正文 `Invalid CORS request`） | `CorsFilter`，发生在鉴权之前 |
| 外部凭证调用 `@InternalOnly` | 403 | `InternalAuthorizationInterceptor` |
| 标了 `@InternalOnly` 但拿不到 `AuthContext` | 401 | 同上（通常是 `skip-paths` 配错） |
| 外部 Key 超过每分钟限额 | 429 + `Retry-After: 60` | `RateLimitInterceptor` |
| 注册参数不合法 / 地址被拦 / 端口越界 | 400 | `InstanceRegistrationValidationFilter` |
| 注册特权端口 | 403 | 同上 |
| 下载超限 / 下载无内容 | 413 / 404 | `AgentProxyController` |

## 7. SSE 流式链路

### 7.1 端到端超时预算

| 层 | 参数 | 值 |
|----|------|-----|
| 客户端 → nginx（SSE 正则 location） | `proxy_read_timeout` | 900s |
| 客户端 → nginx（`/api/router/`） | `proxy_read_timeout` | 60s |
| Router 容器异步请求 | `spring.mvc.async.request-timeout` | 1800000ms（30min） |
| Router → agent（非流式 JSON） | `router.proxy.read-timeout-ms` | 600000ms（10min），同时作为 `responseTimeout` 与 `ReadTimeoutHandler` |
| Router → agent（写） | `router.proxy.write-timeout-seconds` | 30s，**代码内默认**：这个键只作为 `RouterConfig` 构造参数上的 `@Value` 默认值存在，`harnax-session-router/src/main/resources/application.yml` 与 compose 里都没有它，因此不经配置调整 |
| Router → agent（连接） | `router.proxy.connect-timeout-ms` | 5s |
| Router 流式空闲 | `router.proxy.stream-idle-timeout-seconds` | 120s |
| Router 流式墙钟 | `router.proxy.stream-max-duration-minutes` | 30min |
| Router → Redis | 命令超时 / 连接超时 | 3s / 2s |
| Router → admin | 响应超时 / 连接超时 | 3s（外加 1s 总预算余量）/ 2s |
| 幂等租约 | `router.idempotency.ttl-seconds` | 60s |
| agent 单轮预算 | `HARNAX_TURN_TIMEOUT_SECONDS` | 300s |

流式的两条界由 `AgentServiceClient.withinStreamLimits` 施加在订阅上：`timeout(120s)` 量的是事件之间的沉默，`takeUntilOther(Mono.delay(30min))` 量的是整条流的墙钟。两者都以异常结束流，调用方因此看到的是失败而不是"无声停止"。`streamingWebClient` 刻意不挂 `responseTimeout`——agent 思考一分钟不回字节是这条端点存在的理由，按读超时切掉的正是它该服务的那类请求。

容器异步超时（30min）与流式墙钟（30min）对齐，使 Router 自己结束流而不是被容器切断；Redis 的 3s 命令超时刻意设短，只有快速失败才轮得到降级路径。

### 7.2 nginx 侧要求

`harnax-deploy/nginx.conf` 中 `location ~ ^/api/router/agent/(chat/stream|confirm)` 做了这些事，缺一即表现为"流不动"：

| 指令 | 值 | 原因 |
|------|-----|------|
| `proxy_http_version` | 1.1 | — |
| `proxy_set_header Connection` | `""` | 长连接复用，且不带上 `Upgrade`：SSE 不是协议升级，路由这条路径的 location 不转发 `Upgrade` 头 |
| `proxy_buffering` / `proxy_request_buffering` | off | 响应与请求都不缓冲 |
| `proxy_cache` | off | 跳过任何响应缓存 |
| `gzip` | off，且 `Accept-Encoding` 置空 | 压缩 SSE 会攒块 |
| `X-Accel-Buffering` | `no` | 上游再套一层代理时同样禁缓冲 |
| `chunked_transfer_encoding` | on | — |
| `proxy_next_upstream_tries` | 1 | 不把一次 POST 重投到另一台上游 |
| `proxy_read_timeout` | 900s | 只在流沉默时触发，Router 侧的 120s 先到 |

匹配顺序上，正则 location 优先于 `/api/router/` 前缀 location，所以只有这两个端点走无缓冲路径。

### 7.3 首事件前可换实例，首事件后不重放

`buildStreamFlux` 用 `AtomicBoolean delivered` 在 `doOnNext` 里记录"是否已向客户端投递过事件"，`onErrorResume` 的换实例条件是三者同时成立：`isConnectivityError(e)` && `attempt < failover-max-retries(2)` && `!delivered`。

`isConnectivityError` 只认这几类异常（含 cause 链）：`ConnectException`、`SocketTimeoutException`、`NoRouteToHostException`、`UnknownHostException`、`ConnectTimeoutException`。因此换实例只发生在"连不上"上；一旦投递过事件，Router 不重放——重放一个已经产出文本的提示词会在同一段会话里追加第二个答案，并且把 agent 已经执行过的工具再跑一遍。

搬家时 `excluded` 集合会累加已试过的实例，并通过 `placementExclusions` 排除熔断中的实例；换实例前请求 `sessionEvictor.requestEviction` 通知被离开的实例停沙箱。

### 7.4 连接池与缓冲

`RouterConfig` 建一个 `ConnectionProvider`（`router-pool`）供两台 WebClient 共用，Reactor Netty 按 `host:port` 分池，因此下列上限是**单个 agent 实例**可占用的量，而不是全局量——一个卡死的实例占不满整个 Router：

| 参数 | 值 | 语义 |
|------|-----|------|
| `max-connections-per-instance` | 50 | 单实例并发连接上限 |
| `pending-acquire-timeout-ms` | 10000 | 池满时排队等槽的时间 |
| `pending-acquire-max-count` | 100 | 等待者超过此数直接快速拒绝，而不是大家一起超时 |
| `pool-max-idle-seconds` | 60 | 空闲连接回收 |
| `pool-max-lifetime-minutes` | 5 | 按年龄强制回收，会话搬家后原实例上的连接寿终、得不到复用 |
| `evictInBackground` | 30s | — |
| `max-in-memory-size-mb` | 16 | 非流式响应缓冲上限；下载与流式不走这条路径 |

## 8. 租户隔离

### 8.1 校验汇聚点

所有 14 个会话作用域端点在找实例之前都要过 `SessionAccessGuard.requireAccessible(sessionId)`：写类路径显式调用，只读路径统一在 `boundInstance()` 里调用（新端点只要复用 `boundInstance` 就继承这道 guard）。比对的两边：调用方的 `tenantId`（JWT 的 `tenantId` claim 或 Key 上挂的租户）与 admin 报告的会话归属租户，不一致即 `SecurityException`。

这道 guard 存在的理由：Router 转发时打的是自己的服务令牌，agent-service 看到的是"一个对等服务"，无法据此挡跨租户；而入站鉴权回答的是"能不能用 Router"，从不回答"能不能读这个会话"。

### 8.2 放行条件

| 放行 | 依据 |
|------|------|
| 调用方无 `tenantId`（不带租户的内部服务令牌 / 无租户 Key） | 没有可比对的一侧。channel 代表它已经认证过的用户来路由，硬造一个拒绝只会把运维推向关掉鉴权 |
| admin 明确回答"没有这个会话"（`Unknown`） | 什么都没绑定，代理端点会答"未绑定"，不接触任何实例；归属也无法证实或证伪 |
| admin 不可达 / 拒绝（`Unreachable`） | 不因 admin 故障阻断对话链路，打 warn 放行 |

`Unknown` 与 `Unreachable` 在 `AdminClientService.lookupSession` 里是两种结果（404 属于"admin 回答了"），`SessionInfoClient` 只缓存前者、拿到后者立即失效——否则一次 admin 抖动会让受影响会话在恢复后的 5 分钟里一律读成"不存在"。

### 8.3 会话 id 前缀规则

`PrivilegedSessionPrefixes` 在归属查询之前先挡掉 `task-`：调用方带终端用户身份时，`task-` 前缀直接 `SecurityException`。这是前缀规则而不是查询，因为 admin 是从 id 自身解析 `task-` 的（`agent_task` 已在 scheduler 自己的库里），归属查询答不出它，一律 `Unknown` 即放行——而 `task-{taskId}` 里的 taskId 是递增整数，一把有效凭证可以枚举。`Scheduler` 这类不带终端用户的内部调用方仍然可用。

`chn-` 不在这份名单上：它的 id 是 UUID，指向它已经要求先知道它；admin 的 `/sessions/{id}/info` 对 `chn-` 从 `channel` 行取租户，并且**不看 `active` 标记**——软删除的频道仍归属它原来的租户（删除既不清 session 也不清 sandbox，能让一次读取变得无主可归的动作不是删除）。因此跨租户的 `chn-` 读取由上面的租户比对拒绝，同租户的合法页面读取继续通过。`web-` 与 `mp-` 一律由租户比对决定。

### 8.4 粒度与精度

**隔离单位是租户，不是用户。** 同一租户内、持有该租户有效凭证的不同登录用户之间，可以读到彼此的会话。需要用户级隔离时由上层（admin 的会话授权、或渠道侧的用户—会话映射）保证。

| 项 | 值 / 行为 |
|----|-----------|
| 归属查询缓存 | 5000 条、写后 5 分钟过期；`Unknown` 也在缓存内 |
| 查询发生线程 | 请求线程上的 `runBlocking`，最坏约 4s（3s 响应超时 + 1s 余量） |
| 缓存的窗口 | 一个 `chn-` id 若在 admin 开始回答之前被问过，其 `Unknown` 会让它在之后 5 分钟内继续放行 |

### 8.5 输入格式与 SSRF 防护

| 防护点 | 规则 |
|--------|------|
| `sessionId` | `^[A-Za-z0-9._:-]{1,128}$` 且不含 `..`；违规抛 `IllegalArgumentException`，不回显被拒的值 |
| `instanceId` | `^[A-Za-z0-9._-]{1,64}$` |
| `sessionIds` 列表 | 逗号分隔后逐个校验，空列表直接拒绝 |
| 注册地址 | 仅 IPv4 字面量（含字母即拒），拒绝 `0.*` / `127.*` / `169.254.*` 与 `localhost`、`metadata*` 等主机名；私网段放行。端口 8000–9999 |
| 转发路径 | `sessionId` 一律经 `UriUtils.encodePathSegment` 成为路径段；查询参数经 `encodeQueryParam`；URI 由 `java.net.URI` 构造，不做字符串拼接 |
| 文件名 | `safeFileName` 去目录、去引号与控制字符、限长 128 |
| Redis 反序列化 | `GenericJackson2JsonRedisSerializer` 配 `BasicPolymorphicTypeValidator`，只允许 `com.agnetix.harnax.` / `java.util.` / `java.lang.` |

格式校验的意义在于 `sessionId` 会同时出现在 Redis 键名、Router 自己发起的 URL、以及日志里：含引号或反斜杠的 id 会让反向索引推导出一个永不匹配的键名（会话在对话中途悄悄失去实例），含 `../` 的 id 会把一次会话查询变成对另一个内部端点的请求。

## 9. 存储与调用日志

### 9.1 两种数据源形态

| 模式 | 触发条件 | 路由状态 | 调用日志 |
|------|----------|----------|----------|
| `local`（`router.cache.type=local`，默认） | 单机 / 开发 | 进程内存 | SQLite，JDBC URL 缺省 `jdbc:sqlite:tmp/harnax-router/call-log.db`；建表由 `SqliteInitConfig` 执行 `db/sqlite-init.sql`，目录由 `SqliteDirectoryInitializer` 建 |
| `redis`（cluster profile） | `CACHE_TYPE=redis` | Redis | MySQL 库 `harnax_router`，表由 Flyway `V1__create_session_router_tables.sql` 建 |

两份 DDL 字段一致（SQLite 少了 `instance_id` 索引之外的约束差异，且额外建了 `idx_instance_id`）。SQLite 分支的生效条件是数据源 URL 含 `sqlite` 且 Flyway 未启用，因此 cluster profile 不会走到它。

调用日志库不参与就绪判定：写日志失败不该让路由下线。

### 9.2 `api_call_log` 字段

| 字段组 | 字段 |
|--------|------|
| 调用方 | `caller_id`、`caller_type`（`INTERNAL_SERVICE` / `EXTERNAL_API`）、`tenant_id`（可为 NULL） |
| 会话与目标 | `session_id`、`agent_id`、`agent_name`、`model_id`、`model_name`、`instance_id` |
| 请求 | `endpoint`、`method`、`request_type`（`CHAT` / `COMMAND` / `CONFIRM`）、`request_id` |
| 结果 | `status_code`、`success`、`error_message`、`start_time`、`end_time`、`duration_ms`、`create_time` |
| 索引 | `idx_caller_id`、`idx_session_id`、`idx_start_time`、`idx_tenant_id` |

### 9.3 采集时机与缓冲

`ApiCallLogFilter`（`@Order(HIGHEST_PRECEDENCE + 15)`）只处理 `/api/router/agent/` 前缀，白名单之外的请求不记录：

- 行在响应真正结束时写。判定依据是响应状态而不是过滤器链是否返回——流与协程端点的链在异步处理开始时即返回，那时记录会得到一个 200 与几毫秒；
- SSE 端点（路径以 `/stream` 结尾，或 `/api/router/agent/confirm`）与协程批量端点（`/chat`、`/command`、`/session`、`/chat/history`、`/workspace` 及其子路径）不套 `ContentCachingResponseWrapper`：缓冲会掐断事件流、或者刷出一个空 body。这些请求改为注册 `AsyncListener`，在 `onComplete` / `onTimeout` / `onError` 时写一行（只写一次），`duration_ms` 覆盖整条流的生命周期，超时行标记失败并写明"响应写出之前超时"；
- 落点实例取自 `SessionRouterService.trackPlacement` 写入的请求属性 `router.routedInstanceId`，取不到再退到 MDC。MDC 属于放置发生的那条线程、并随协程挂起而失效，只有绑定了 HTTP 请求的线程能写下这个属性；
- agent / model 四列由 `SessionInfoClient` 补，admin 不可达时这四列为空，其余照记；
- `ApiCallLogService` 内存缓冲，攒满 50 条或每 5 秒刷一次批量插入，缓冲区上限 10000 行、超出丢弃并计数；批量插入失败退化为逐条重试，坏行计入 `poisonedCount`；进程退出前刷一次。各字符串列按 DDL 宽度截断。
- 没有任何按时间删除的任务，表只增不减。

### 9.4 按调用方租户收口

`GET /monitor/call-logs` 的 `tenantId` 不来自请求参数，而是 `RouterMonitorController` 从已验证的凭证里取：

| 调用方 | 看到 |
|--------|------|
| 带租户的凭证 | 仅 `tenant_id = 自己的租户` 的行 |
| 无租户（不带租户的内部令牌 / 无租户 Key） | 全量，含 `tenant_id IS NULL` 的行 |

`tenant_id IS NULL` 的行本来就是无租户调用方写下的，因此不放宽成 `IS NULL OR = 自己`——一个无法归属的行不该变成所有人的行。`sessionId`、`instanceId`、`agentName`、`statusCode`、`success`、`minDurationMs`、`limit`、`offset` 都是调用方可选的过滤条件，与"谁的行"这个边界无关；分页与 count 复用同一个 WHERE 片段。

## 10. Redis 不可达时的行为

### 10.1 逐环节降级

| 环节 | 行为 | 影响 |
|------|------|------|
| 已绑定会话的请求 | `getInstanceId` 读失败时回落到本节点影子缓存 `lastKnownBindings`（Caffeine 50000 条 / 24h，读到即回灌以熬过比 TTL 更长的故障） | 换一台副本处理同一会话时粘性不保证 |
| 新会话放置 | 实例列表来自 `RedisInstanceRegistry` 的 `knownInstances` 快照，按心跳超时自然老化后全部丢弃 | 老化后停止放置，而不是投向一个可能已经全灭的集群 |
| 负载统计 | `degradedCounts` 用本节点影子绑定估算 | 只是本节点视图，spread 仍优于选不出来 |
| 熔断判定 | 读失败时放行 | 熔断窗口内的故障实例可能被再次选中 |
| 新绑定写入 | `placeUnpersisted` 在本节点生效，Redis 恢复后的下一次放置才写入 | 其它副本看不到这个绑定 |
| 幂等租约 | `tryAcquire` 放行 | 故障期间可能放过重复请求 |

Lettuce 配 `DisconnectedBehavior.REJECT_COMMANDS`：连接断开时命令立即在调用线程抛异常，而不是排队——排队会把 servlet 线程池耗尽，而抛异常才走得到上面这些回落路径。

**不承诺的部分**：Redis 不可达期间跨副本的会话粘性。承诺的是不打挂请求线程、不静默丢服务，不是"路由结果与 Redis 恢复后一致"。集群模式下就绪检查（`/actuator/health/readiness` 含 `redis` 成员）会让该节点退出负载均衡。

### 10.2 不支持 Redis Cluster：启动期 fail-fast

| 拓扑 | 支持 |
|------|------|
| 单实例 standalone | 支持，默认；要求 `maxmemory-policy=noeviction` |
| Sentinel（主从 + 自动故障转移） | 支持，`REDIS_SENTINEL_MASTER` / `REDIS_SENTINEL_NODES` / `REDIS_SENTINEL_PASSWORD` |
| Redis Cluster | 不支持；`REDIS_CLUSTER_NODES` 非空即启动失败 |

原因在键布局：`BIND` / `MOVE_IF_FROM` / `REBIND_BATCH` 这类脚本同时操作 `router:session:*` 与 `router:instance_sessions:*`，`HEARTBEAT` / `MARK_DOWN` / `MARK_DRAINING` 同时操作 `router:instance:*` 与 `router:instances:healthy`。这些键在 Cluster 下不落同一个 slot，Redis 在执行脚本之前就返回 `CROSSSLOT`——注册后的第一次心跳就失败、健康检查每 5 秒抛异常、故障转移完全不工作。

`RedisConfig.redisConnectionFactory` 因此把 Cluster 检查放在最前面，抛 `IllegalStateException` 并在消息里给出替代方案（standalone 或 Sentinel），而不是让一个"进程活着、注册表已损坏"的节点上线。这个行为由 `harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/config/RedisConfigTest.kt` 锁定。

Router 的全部状态是「数万会话绑定 + 数百实例记录 + 少量索引集合」，量级在十几 MB；docker-compose 里给 Redis 的配额是 `maxmemory 512mb` + `noeviction`。

### 10.3 profile `cluster` 与 Redis Cluster 是两件事

| 名称 | 含义 |
|------|------|
| Spring profile `cluster`（`SPRING_PROFILES_ACTIVE=cluster`，即 `application-cluster.yml`） | Router 多副本 + MySQL + Redis 的部署形态，连的仍是 standalone / Sentinel Redis；这是生产形态 |
| `REDIS_CLUSTER_NODES` | Redis 自身的分片集群，不支持，设置即启动失败 |

`application-cluster.yml` 覆盖的是数据源（MySQL + Flyway 开启）、`router.cache.type=redis`、反向索引对账参数、以及把 `redis` 纳入就绪组。

## 11. 部署形态（harnax-deploy）

`harnax-deploy/docker-compose.yml` 是唯一部署入口，`router` 服务的相关行：

| 项 | 值 |
|----|-----|
| 镜像 / 容器名 | `harnax-router:latest`（`harnax-deploy/Dockerfile.router`） / `harnax-router` |
| profile | `SPRING_PROFILES_ACTIVE: cluster` |
| 存储 | `DB_URL: jdbc:mysql://mysql:3306/harnax_router`、`DB_USERNAME` / `DB_PASSWORD`；库由 `harnax-deploy/sql/init-databases.sql` 建出 |
| 缓存 | `CACHE_TYPE: redis`、`REDIS_HOST: redis`、`REDIS_PORT: 6379`、`REDIS_PASSWORD` |
| 身份 | `SERVICE_ID`（`ROUTER_SERVICE_ID`，缺省 `router-0`）、`HARNAX_AUTH_SECRET`、`ADMIN_INTERNAL_API_SECRET` |
| 上游 | `ADMIN_SERVICE_URL: http://admin:8080` |
| CORS | `ROUTER_CORS_ALLOWED_ORIGINS`，必须含无端口的 `http(s)://localhost` 与 `127.0.0.1` 变体 |
| 端口 | `28081:8081` 发布到宿主（标准约定：20000 + 服务端口） |
| 依赖 | `admin` started、`redis` healthy |
| 健康检查 | `wget /actuator/health/liveness`，每 30s，`start_period` 30s；这是存活检查，不看 Redis |
| 资源 | CPU 上限 2.0 / 内存 1024M，JVM `-Xms128m -Xmx512m` |
| 卷 | `router-logs:/app/logs`、宿主 `/etc/localtime` 只读 |

代理侧的要求见「nginx 侧要求」：SSE 正则 location 必须保持禁缓冲与 900s 读超时；`/api/router/` 前缀 location 的 60s 覆盖非流式与实例管理调用；`= /ui` 单独转发到 `router:8081`。

### 11.1 启动期 fail-fast

| 检查 | 条件 | 结果 |
|------|------|------|
| `RedisConfig` | `router.cache.type=redis` 且 `REDIS_CLUSTER_NODES` 非空 | 抛 `IllegalStateException`，进程不启动 |
| `RedisConfig` | `router.cache.type=redis` 且既无 host 也无 sentinel master | `check(...)` 失败并提示配 `REDIS_HOST` |
| `PlaceholderSecretCheck` | `redis` 模式且 `harnax.auth.enabled=true`，而 `HARNAX_AUTH_SECRET` 或 `ADMIN_INTERNAL_API_SECRET` 仍是仓库里的占位值 | 抛异常拒绝启动 |
| `SqliteInitConfig` / `SqliteDirectoryInitializer` | 数据源是 SQLite 且 Flyway 未启用 | 建目录与建表，失败即启动失败 |

`PlaceholderSecretCheck` 只管 `redis` 模式：占位密钥在单机内存模式下换不来任何损失，为一个用不上的强度要求让开发环境起不来，只会把人推向关掉鉴权。

### 11.2 存活检查、就绪检查与调度线程

| 端点 | 内容 |
|------|------|
| `GET /actuator/health/liveness` | 进程在不在。容器重启策略只该看这个 |
| `GET /actuator/health/readiness` | 能不能路由。cluster profile 下含 `redis` 成员，不含 MySQL |
| `GET /metrics/cache` | 当前生效的实现类（内部身份） |

`spring.task.scheduling.pool.size` 为 4（`ROUTER_SCHEDULER_POOL_SIZE`）：健康检查、调用日志刷写、限流与绑定清理都是 `@Scheduled`，共用单线程时一个等 Redis 的健康检查会停掉包括日志刷写在内的所有其它任务。

## 12. 可观测性

### 12.1 指标

`/actuator/prometheus`（暴露 `health,info,prometheus,metrics`，全部带 `application` 标签）：

| 指标 | 类型 | 标签 | 口径 |
|------|------|------|------|
| `router.proxy.duration` | Timer | `endpoint=chat` | 非流式对话耗时，异常路径也停表 |
| `router.proxy.requests` | Counter | `endpoint=chat\|stream`、`status=ok\|error` | 成功率分子分母 |
| `router.failover.count` | Counter | `endpoint`、`attempt` | 搬家发生频率与第几跳 |
| `router.healthy.instances` | Gauge | — | 抓取时刻本副本可放置的实例数（DRAINING 不在 healthy set 里，因此不计） |

`router.healthy.instances` 是抓取时刻读注册表而不是计数器累加：注册、drain、别的副本判定 DOWN 这些路径都在本节点之外改变舰队成员，计数会与视图漂移。多副本下这个指标要用 `min()` 而不是 `avg()` 聚合——副本视图不一致时，平均值会掩盖"某个副本完全看不到实例"。

### 12.2 用 sessionId 定位落点

| 手段 | 可用性 |
|------|--------|
| `api_call_log` 按 `session_id` 查，读 `instance_id` | 首选，含落点实例 |
| `GET /api/router/monitor/instances` | 实例侧的会话分布与心跳年龄 |
| 应用日志中的 MDC | 只对同步路径可靠。流式请求不写 MDC（事件在 agent 的事件循环上到达），流在 agent 事件循环上完成的那一次搬家不进调用日志的 `instance_id` |

## 13. 当前限制清单（真相源）

| # | 事实 | 影响 | 现有处置 |
|---|------|------|----------|
| 1 | 不支持 Redis Cluster；跨键 Lua 是设计前提，`REDIS_CLUSTER_NODES` 非空即拒绝启动 | Redis 的高可用只能由 standalone 或 Sentinel 提供 | Sentinel；状态量级十几 MB，横向分片对这个组件无收益 |
| 2 | 非流式业务错误除显式分支外统一压成 `code=500` | 调用方无法程序化区分"未绑定 / 无实例 / 参数非法"，只能匹配 `message` | 需要区分时先查会话与实例状态 |
| 3 | 流式端点无幂等；`/chat` 的去重要求调用方自带 `requestId` | 客户端重连可能触发两次模型调用 | 客户端侧去重；需要 Router 去重就生成并传 `requestId` |
| 4 | `/api/router/` 前缀的 nginx `proxy_read_timeout` 是 60s，Router 侧非流式读超时是 600s | 超过 60s 的非流式 `/chat` 由网关先切断，Router 侧仍可能跑到成功 | 长任务走 `/chat/stream`（900s 那条 location），或把两处对齐 |
| 5 | 读空闲超时与等槽超时都不触发搬家、也不计入熔断：`isConnectivityError` 只认连接类异常，`isRetryableError` 只认连接类与 5xx/429 | 一台"接得上但不回数据"的实例不会被自动摘掉，调用方只得到超时 | 靠 30s 心跳超时兜底；运维 `/instance/drain` |
| 6 | `api_call_log` 没有保留期与清理任务 | 表只增不减 | 运维按 `start_time` 归档或删除 |
| 7 | 会话归属校验是租户粒度；同一租户内的不同用户彼此可读对方的会话 | 用户级隔离不由 Router 提供 | 由 admin 的会话授权或渠道侧映射保证 |
| 8 | 归属查询在请求线程上 `runBlocking`（预算 3s + 1s） | admin 变慢时占用请求线程 | admin 保持健康；缓存命中即无网络调用 |
| 9 | `Unknown` 结果按 5 分钟缓存，`Unreachable` 不缓存 | 归属信息在这个窗口内以缓存值判定 | 需要立即生效时等窗口过期或重启节点 |
| 10 | `drain` 不可逆，没有 `undrain`；心跳保留 DRAINING | 误 drain 只能靠重新 `register` 恢复 | drain 前先确认该实例要下线 |
| 11 | `local` 模式不共享任何状态 | 多副本时各副本独立放置，粘性随副本漂移 | `local` 只跑单副本（部署约束） |
| 12 | 限流计数器是每副本进程内的 | 外部 Key 的实际配额随副本数放大 | 精确配额需要共享计数 |
| 13 | 流式在 agent 事件循环上完成的搬家不写调用日志的 `instance_id` | 该次落点只在实例日志里可追 | 落点在同步路径上由 `trackPlacement` 记录 |
| 14 | `GET /workspace/status` 把所有 id 交给第一个 id 所在实例回答 | 跨实例的会话只被部分回答；未绑定的那些返回空 | 按实例分组查询，或改用单会话端点 |

## 14. 关键文件索引

| 主题 | 位置 |
|------|------|
| 应用入口 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/SessionRouterApplication.kt` |
| 代理与放置主流程 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt` |
| 对话代理端点 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt` |
| 实例管理端点 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/InstanceRegistryController.kt` |
| 监控端点 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RouterMonitorController.kt` |
| 监控页面入口 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RootController.kt` |
| 会话绑定（Redis） | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisSessionMappingService.kt` |
| 会话绑定（单机） | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalSessionMappingService.kt` |
| 实例注册表 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisInstanceRegistry.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalInstanceRegistry.kt` |
| 反向索引对账 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/SessionIndexReconciler.kt` |
| 熔断 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/InstanceCircuitBreaker.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisCircuitBreaker.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/LocalInstanceCircuitBreaker.kt` |
| 幂等 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/RedisIdempotencyService.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/impl/CaffeineIdempotencyService.kt` |
| 健康检查与搬家 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/health/HeartbeatHealthChecker.kt` |
| 沙箱驱逐 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionEvictor.kt` |
| 出站客户端与流式界 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt` |
| 连接池与 WebClient | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RouterConfig.kt` |
| Redis 接线与拓扑判定 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/RedisConfig.kt` |
| 租户归属 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionAccessGuard.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionInfoClient.kt` |
| admin 客户端 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AdminClientService.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RemoteApiKeyStore.kt` |
| 输入格式与前缀规则 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/IdFormat.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt` |
| 实例实体与地址校验 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/entity/AgentInstance.kt` |
| 注册参数前置校验 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/InstanceRegistrationValidationFilter.kt` |
| 调用日志采集 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/ApiCallLogFilter.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/ApiCallLogService.kt`、`harnax-session-router/src/main/resources/mapper/ApiCallLogMapper.xml` |
| 限流 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/RateLimiter.kt`、`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/RateLimitInterceptor.kt` |
| 启动期密钥检查 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/PlaceholderSecretCheck.kt` |
| 异常到响应 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/config/GlobalExceptionHandler.kt` |
| 配置 | `harnax-session-router/src/main/resources/application.yml`、`harnax-session-router/src/main/resources/application-cluster.yml` |
| 建表脚本 | `harnax-session-router/src/main/resources/db/migration/V1__create_session_router_tables.sql`、`harnax-session-router/src/main/resources/db/sqlite-init.sql` |
| 入站鉴权 | `harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt`、`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/ExternalApiKeyValidator.kt`、`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalTokenProvider.kt`、`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalAuthorizationInterceptor.kt` |
| admin 内部接口的密钥比对 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/InternalApiAuthFilter.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` |
| 释放失败即拒删 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionRuntimeReleaser.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentRuntimeClientImpl.kt` |
| agent 侧清除与沙箱释放 | `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/sandbox/KeepAliveSandboxManager.kt` |
| 事件与请求协议 | `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt`、`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt` |
| 错误码 | `harnax-common/src/main/kotlin/com/agnetix/harnax/common/error/HarnaxErrorCode.kt` |
| 部署与代理 | `harnax-deploy/docker-compose.yml`、`harnax-deploy/nginx.conf`、`harnax-deploy/Dockerfile.router`、`harnax-deploy/sql/init-databases.sql` |
