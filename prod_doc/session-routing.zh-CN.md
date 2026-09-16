# Harnax 会话路由能力说明（中文）

> 英文版本见 [session-routing.en-US.md](./session-routing.en-US.md)
>
> 本文基于当前代码库调研整理，覆盖 `harnax-session-router`（下文简称 Router）对**调用方**的能力契约：端点清单、错误语义、租户隔离保证、部署形态、超时与容量预算、可观测口径，以及当前限制清单。
>
> **Router 相关文档的分工**（三套各自回答不同问题，避免混读）：
>
> | 文档 | 读者 | 回答的问题 |
> |------|------|------------|
> | 本文（`prod_doc/session-routing`） | 调用方（channel / webui / app / 小程序 / SDK）、产品与运维决策者 | 这个能力承诺什么、语义是什么、边界在哪里 |
> | [`harnax-session-router/README.md`](../harnax-session-router/README.md) | 该模块的开发与值班运维 | 内部机制如何实现、Redis 键结构、源码结构、告警规则 |
> | [`docs/deploy-harnax-session-router.md`](../docs/deploy-harnax-session-router.md) | 部署执行者 | 环境变量、启动步骤、SQLite / MySQL 路径 |
>
> 数值冲突时以代码为准：配置默认值看 `harnax-session-router/src/main/resources/application.yml`，行为看 `proxy/SessionRouterService.kt`。

## 1. 定位与能力边界

### 1.1 它解决的问题

一个会话（`sessionId`）在其生命周期内，**必须始终落到同一个 agent-service 实例**上执行。原因是 Agent 的可变状态不在数据库里，而在那个进程的内存与本地磁盘上：

- Agent 实例对象与其 ReAct 循环上下文；
- workspace 沙箱目录（工具产出的文件、正在进行的计划）；
- HITL 工具确认的挂起态（等待 `/confirm` 回到同一个实例才能续上）。

Router 就是为「会话 → 实例」这个粘性约束提供放置决策与请求转发的服务：端口 **8081**，Spring MVC + Kotlin 协程，无状态可多副本水平扩展。

### 1.2 明确不做的事

| Router 不做 | 由谁做 |
|-------------|--------|
| 执行 Agent、调用模型 | `harnax-agent-service`（:8082） |
| 存储对话历史 / 计划 / workspace 文件 | agent-service（本地 workspace + snapshot） |
| 判定 API Key 本身是否有效、属于哪个租户 | `harnax-admin`（Router 远程查询 + 本地缓存） |
| 判定会话归哪个租户（只查询不裁决） | admin（`SessionInfoClient` → admin 内部接口） |
| 渠道消息解析与回传 | `harnax-channel` |
| 定时任务调度 | `harnax-scheduler`（:8084） |

> 一句话概括：**Router 是「会话维度的七层反向代理 + 放置决策器」，不是业务服务。** 它自己不持有任何业务数据，唯一的状态就是路由表（实例注册表 + 会话绑定 + 熔断状态）。

## 2. 端到端流转

### 2.1 拓扑

```
                          ┌─ channel-service  （钉钉/飞书/微信 用户消息）
                          ├─ webui            （Ant Design Pro 管理台）
外部调用方 ── nginx ──────┼─ harnax-app       （uni-app H5 / 小程序）
   :80                    ├─ harnax-client SDK（外部系统集成）
                          └─ harnax-admin     （转发 workspace / 会话相关调用）
                                   │
                                   ↓  X-Api-Key 或 Bearer JWT
                          ┌────────────────────┐
                          │  Router :8081      │──JWT──> agent-service :8082 (×N 实例)
                          │  会话粘性路由       │            │
                          └────────────────────┘            │ 注册 / 心跳（每 10s）
                            │      │      │                 ↓
                            ↓      ↓      ↓        ┌── Router /api/router/instance/*
                     admin :8080  Redis   MySQL    │  （agent-service 主动注册自己）
                   （Key 校验、    （共享  （仅
                    会话归属）     状态）  调用日志）
```

### 2.2 一次流式对话（最完整的路径）

以 `POST /api/router/agent/chat/stream` 为例：

| 步 | 动作 | 失败时的表现 |
|----|------|--------------|
| 1 | `UnifiedAuthFilter` 校验凭证（Bearer JWT 或 `X-Api-Key`），写入 `AuthContextHolder` | 401（HTTP 状态码即 401） |
| 2 | `SessionAccessGuard.requireAccessible(sessionId)` 做租户归属校验 | 403 / `SecurityException`（见 6.4） |
| 3 | 查会话绑定；命中则直接用该实例 | 未绑定 → 返回「not bound」类错误（见 6.3） |
| 4 | 未绑定则做放置：排除 DRAINING 与熔断中的实例，按反向索引做 `1/(count+1)` 加权随机 | 无可用实例 → 错误 |
| 5 | 原子写入绑定（Lua 脚本：会话键 + 实例反向索引一起改） | 绑定写不进（Redis 故障）→ 本节点影子绑定，降级但不拒服务 |
| 6 | WebClient 转发到 `http://{host}:{port}`，SSE 事件流回传 | 见第 7 步 |
| 7 | 连通性失败且**尚未向客户端投递任何事件**时，换实例重试，最多 `failover-max-retries=2` 次 | 首个事件之后不再重放（避免重复内容） |
| 8 | 可重试失败计入熔断器，连续 3 次即对该实例开熔断 30s | 开熔断后新会话不再放置到该实例 |

**关键契约**：第 7 步的「首事件后不重放」意味着——一旦客户端看到第一个 token，Router 就不会再偷偷把它切到别的实例；此时实例挂了，客户端看到的是一条终止的错误事件，而不是"续上的"输出。

### 2.3 调用方矩阵

| 调用方 | 走哪些端点 | 凭证 | 说明 |
|--------|-----------|------|------|
| channel-service | `/agent/chat/stream`、`/agent/command`、`/agent/session/{id}` DELETE | 内部服务 token（JWT） | 代表用户调用，Router 侧不做租户比对（见 7.2） |
| webui | `/agent/**` 全量（含 workspace） | Bearer JWT 或 `X-Api-Key` | 经 nginx `/api/router/` 代理 |
| harnax-app（H5/小程序） | 对话 + workspace 子集 | `X-Api-Key` | 小程序走独立代理路径 |
| harnax-client SDK | `/agent/**` 对应方法 | `X-Api-Key` | `SpringHarnaxClient` / `SingleClient` |
| agent-service | `/instance/register`、`/instance/heartbeat`、`/instance/unregister` | 内部服务 token | `@InternalOnly`，外部凭证一律拒绝 |
| admin / 运维 | `/instance/drain`、`/instance/list`、`/health`、`/metrics/cache`、`/monitor/**` | 内部 token 或管理 API Key | — |

## 3. 部署形态

### 3.1 两种缓存模式

Router 的全部共享状态（实例注册表、会话绑定、熔断、幂等去重）由 `router.cache.type` 决定落在哪里：

| 能力 | `local`（默认） | `redis` |
|------|-----------------|---------|
| 路由状态 | 进程内存（`ConcurrentHashMap`） | Redis，多副本共享 |
| 调用日志 | SQLite（嵌入式） | MySQL |
| 副本数 | 只能 1 | 任意多 |
| 熔断一致性 | 仅本节点 | 全节点共享 |
| 故障转移 | 本节点执行 | 任意节点执行（CAS 竞争，只有一个赢家） |
| 会话粘性 | 仅本节点可见 | 全节点可见 |
| 状态持久化 | 进程重启即丢 | Redis 重启不丢（AOF/RDB 取决于部署） |
| 适用 | 开发 / 测试 / 单机 | 生产 |

> **绑定存活时长在两种模式下是同一个值**（`RedisSessionMappingService.SESSION_TTL` = 24h），不是两个旋钮。这样开发环境测出的"会话多久换实例"和生产一致。

### 3.2 Redis 拓扑支持矩阵

| 拓扑 | 是否支持 | 说明 |
|------|----------|------|
| 单实例 standalone | ✅ | 默认。需 `maxmemory-policy=noeviction` |
| Sentinel（主从 + 自动故障转移） | ✅ | `REDIS_SENTINEL_MASTER` / `REDIS_SENTINEL_NODES` |
| **Redis Cluster** | ❌ **不支持，设置即启动失败** | 见下 |

不支持 Cluster 的原因是硬约束，不是"还没测"：Router 的状态变更大量依赖**跨键 Lua 脚本**——

- 写入一个 session 绑定，要同时更新该实例的反向索引（`router:session:*` 与 `router:instance_sessions:*` 两个键）；
- 实例状态流转，要和 healthy set 在同一个脚本里原子完成（`router:instance:*` 与 `router:instances:healthy`）。

这些键在 Cluster 下不会落在同一个 slot，Redis 在执行脚本**之前**就返回 `CROSSSLOT`。后果不是"部分降级"而是**整条链路立刻失效**：注册后的第一次心跳就 500，`checkInstanceHealth()` 每 5 秒抛异常，故障转移完全不工作。

因此 `RedisConfig` 检测到 `REDIS_CLUSTER_NODES` 非空时直接抛 `IllegalStateException` 拒绝启动，并给出替代方案提示，而不是让一个"进程活着、注册表已损坏"的实例上线。行为由 `RedisConfigTest` 锁住。

**容量视角**：Router 的全部状态是「5 万级会话绑定 + 数百实例记录」，量级在十几 MB。单节点 Redis 毫无压力，Cluster 能给的横向扩展对这个组件收益为零。

### 3.3 两个「cluster」不是一回事

| 名称 | 含义 |
|------|------|
| Spring profile `cluster`（`--spring.profiles.active=cluster`，即 `application-cluster.yml`） | **Router 多副本 + MySQL + Redis 的部署形态**，连的仍是 standalone / Sentinel Redis。这是受支持的生产模式 |
| Redis Cluster（`REDIS_CLUSTER_NODES`） | Redis 自身的分片集群。**不支持** |

docker-compose 里 Router 服务的环境变量是 `SPRING_PROFILES_ACTIVE: cluster`，指的是前者，与 Redis Cluster 无关。

### 3.4 Redis 不可达时的降级承诺

「Redis 故障 Router 会不会整个挂掉」是运维最常问的问题。答案分四层，调用方需要知道每层的语义：

| 状态 | Redis 不可达时的行为 | 代价 |
|------|---------------------|------|
| 已绑定会话继续请求 | 读本节点累积的影子绑定（`lastKnownBindings`，50k / 24h） | 换一台 Router 副本处理同一会话时粘性不保证 |
| 新会话放置 | 读最后一次 Redis 实例快照（`knownInstances`） | 快照按心跳超时自然老化；老化后宁可不路由，也不投向可能已全灭的集群 |
| 熔断判定 | 读失败时放行（fail-open） | 熔断窗口内的故障实例可能被再次选中 |
| 新绑定写入 | 走 `placeUnpersisted`，本节点生效 | 其他副本看不到这个绑定 |

**明确不承诺的**：Redis 不可达期间跨副本的会话粘性。Router 保证的是「不打挂请求线程、不静默丢服务」，不是「路由结果与 Redis 恢复后一致」。

## 4. 会话绑定生命周期

### 4.1 建立与过期

| 事件 | 行为 |
|------|------|
| 首次请求一个未绑定会话 | 放置决策 + 原子写入绑定 |
| 每次经过该会话的请求 | 刷新绑定 TTL（24h） |
| 24h 无任何请求 | 绑定自然过期，下次请求重新放置 |
| 会话被清除（`DELETE /agent/session/{sessionId}`） | 转发给实例执行 CLEAR，并解绑 |
| 绑定所在实例被判定 DOWN | 批量重路由到其它实例（见 4.3） |

> TTL 24h 的含义是「一个会话离开 Router 视野一天之后，允许换实例」，不是会话的业务有效期。业务侧的会话生命周期在 admin 的 session 表里。

### 4.2 实例状态机

```
  register          心跳超时 30s
UP ──────────── DOWN ◄──────────── 健康检查判定（每 5s 扫描）
│                ↑
│ /drain         │ 心跳恢复（非 DRAINING 时才重置为 UP）
↓                │
DRAINING ────────┘
   │
   └─ 存量会话照常服务，只拒绝新会话放置；不提供撤销接口
```

| 状态 | 接受新会话 | 存量会话 | 计入健康实例数 |
|------|-----------|----------|----------------|
| `UP` | ✅ | ✅ | ✅ |
| `DRAINING` | ❌ | ✅ | ✅（活着，只是不接新的） |
| `DOWN` | ❌ | 触发重路由 | ❌ |

**DRAINING 目前是单向门**：`/instance/drain` 之后没有 `undrain`，心跳脚本明确保留 DRAINING 不被重置回 UP。要恢复接新会话，只能让该实例重新 `register`。运维上意味着 **drain 是给"准备下线"用的，不是"临时摘流量排查问题"用的**。

### 4.3 故障转移

健康检查（`router.health.check-interval-ms=5000`）发现实例心跳超过 30s 未刷新时：

1. CAS 标记 DOWN（多副本里只有一个副本赢家，其余副本看到 `rows == 0` 直接跳过）；
2. 该实例上的会话**批量重路由**到其它实例，每批 500，最多 200 批；
3. 目标实例做**过载避让**：若目标当前会话数 > 集群均值 × 2，则改选 ≤ 均值 × 1.5 的实例；
4. 同一实例的重路由有 10 秒冷却（`failoverCooldownMs`，硬编码），防止抖动引发反复搬家；
5. 重路由后向旧实例发**驱逐通知**（见 4.4）。

> **放置排除 ≠ 搬走已有会话**：熔断中或 DRAINING 的实例只影响**新会话**放不放上去，不会因为熔断而把已绑定的会话搬走。已绑定的会话只有在实例 DOWN 时才搬家。

### 4.4 驱逐语义：STOP_SANDBOX 而不是 CLEAR

会话搬家后，Router 会通知旧实例停掉这个会话的沙箱，但发的是 `STOP_SANDBOX`：

- **保留** workspace snapshot、对话历史、计划；
- **只停止**沙箱进程与占用的资源。

这是刻意的：会话可能只是被换到一个更好的实例，用户回来时历史和文件都还在。这条语义由 `router.migration.evict-old-instance`（默认 `true`）控制，且驱逐在独立线程池异步执行（最多排队 200 个，超出即丢弃并告警），不占请求线程。

### 4.5 清除会话

`DELETE /api/router/agent/session/{sessionId}` 转发给绑定实例执行。注意它与"解绑"的语义差别：清除聊天记录不会把会话换实例，会话仍绑定在原实例上。

## 5. 端点契约

### 5.1 对话代理（14 个，需凭证 + 会话归属校验）

前缀 `/api/router/agent`，全部以 `sessionId` 为路由键。

| 方法与路径 | 请求体 | 返回 | 用途 |
|------------|--------|------|------|
| `POST /chat` | `ChatAgentRequest` | `ResultVo<ChatResponse>` | 非流式对话 |
| `POST /chat/stream` | `ChatAgentRequest` | `Flux<ChatEvent>`（SSE） | 流式对话（主路径） |
| `POST /command` | `CommandAgentRequest` | `ResultVo<CommandResponse>` | 命令式控制（`/clear`、`/stop`、`/compact`、`/enable`…） |
| `POST /confirm` | `ConfirmAgentRequest` | `Flux<ChatEvent>`（SSE） | HITL 工具确认，续接挂起的执行 |
| `DELETE /session/{sessionId}` | — | `ResultVo<String>` | 清除会话记录 |
| `GET /chat/history/{sessionId}` | — | `ResultVo`（透传） | 拉对话历史 |
| `GET /session/{sessionId}/plans` | — | `ResultVo`（透传） | 计划列表 |
| `GET /session/{sessionId}/current-plan` | — | `ResultVo`（透传） | 当前计划 |
| `GET /workspace/{sessionId}/files` | `path` 等透传 | `ResultVo`（透传） | 列目录 |
| `GET /workspace/{sessionId}/read` | `path` 透传 | `ResultVo`（透传） | 读文件文本 |
| `POST /workspace/{sessionId}/upload` | multipart | `ResultVo`（透传） | 上传到 workspace |
| `GET /workspace/{sessionId}/download` | `path` 透传 | 文件流 | 下载产出文件 |
| `GET /workspace/status` | — | `ResultVo` | 全部会话的 workspace 状态（面板用） |
| `GET /workspace/{sessionId}/status` | — | `ResultVo` | 单会话 workspace 状态（含沙箱是否活跃） |

请求体关键字段：

```kotlin
ChatAgentRequest(sessionId, message, imageUrls = [], requestId = "", userId = null)
CommandAgentRequest(sessionId, command: CommandType, args = "", userId = null)
ConfirmAgentRequest(sessionId, isConfirmed, toolInfoList = [], toolResults = [], userId = null)
```

`userId` 的取值优先级：认证上下文里的终端用户身份 **优先于** 请求体的 `userId`。带得出可信身份时，body 里伪造的 `userId` 不生效；只有拿不到身份（如内部服务 token 且不携带用户）时才回落到 body 值，并打一条 info 日志。

### 5.2 实例管理（`@InternalOnly`，前缀 `/api/router`）

| 方法与路径 | 调用方 | 语义 |
|------------|--------|------|
| `POST /instance/register` | agent-service | 注册自己（`host` + `port`，端口须在 8000–9999） |
| `POST /instance/heartbeat` | agent-service | 刷新心跳（默认每 10s，`AGENT_HEARTBEAT_INTERVAL`）；**实例未注册时响应体 `code=410`**（HTTP 仍为 200），agent 据此重新注册 |
| `POST /instance/unregister` | agent-service | 下线并解绑该实例的全部会话 |
| `POST /instance/drain` | 运维 / admin | 优雅下线：停接新会话、保留存量（单向门，见 4.2） |
| `GET /instance/list` | 运维 | 实例与状态 |
| `GET /health` | 探针 | Router 视角健康 |
| `GET /metrics/cache` | 运维 | 缓存模式与状态规模 |

外部 API Key 调用这些端点一律 403——`@InternalOnly` 要求内部服务身份。

### 5.3 监控与 UI

| 路径 | 认证 | 内容 |
|------|------|------|
| `GET /api/router/monitor/instances` | **需要凭证** | 集群内网地址映射，敏感 |
| `GET /api/router/monitor/call-logs` | **需要凭证** | 调用记录（含 sessionId 与错误文本），**按调用方租户收口**：带租户的凭证只看自己租户的行；只有无租户的调用方（内部服务令牌 / SYSTEM key）看全量，`tenant_id` 为 NULL 的行也只在它眼里——那些行本就是这类调用方写的 |
| `/ui`、`/index.html`、`/static/`、`/style.css`、`/app.js`、`/favicon.ico` | 免认证 | 仅是渲染页面的静态资源 |

> 免认证路径（`harnax.auth.skip-paths`）**只包含静态资源**。绝不能把 `/api/router/` 加进去——`/instance/heartbeat`、`/instance/register` 依赖 `UnifiedAuthFilter` 建立 `AuthContext`，`InternalAuthorizationInterceptor` 才有判据。

### 5.4 幂等

| 端点 | 是否去重 | 依据 |
|------|----------|------|
| `POST /chat` | ✅ 仅当 `requestId` 非空 | 去重键 = `requestId`，窗口 60s |
| `POST /chat/stream`、`/confirm`、`/command` | ❌ 无 | 见限制清单 #4 |

重复的 `/chat` 返回 `code=429`、消息 `Duplicate request: {requestId}`。**调用方若需要去重能力，必须自己生成并传 `requestId`**——当前 channel 侧不传，所以这条链路实际未生效。

## 6. 错误语义（对调用方最重要的契约）

### 6.1 非流式：HTTP 200 + 业务码

**Router 的绝大多数业务错误返回 HTTP 200，错误信息在响应体里**：

```json
{ "code": 500, "message": "Session xxx is not bound to any instance", "data": null }
```

这是刻意的约定：webui 的 request 封装只从 2xx 响应中读取 `code`/`message`，非 2xx 会被当成网络故障吞掉。所以**判断成功与否要看 `body.code`，不能只看 HTTP 状态码**。

只有少数发生在过滤器/拦截器层（尚未进入 Controller）的错误才使用真实 HTTP 状态码：

| 场景 | HTTP | 响应 |
|------|------|------|
| 凭证缺失 / 无效 | 401 | 过滤器直接写状态码 |
| Origin 不在 CORS 白名单 | 403 | `Invalid CORS request`（发生在鉴权之前，容易误读成"没权限"） |
| 外部 Key 调 `@InternalOnly` | 403 | 拦截器拒绝 |
| 会话归属其他租户 | 见 6.4 | `SecurityException` |

> **已知缺陷**：进入异常处理器之后，除个别显式分支（如幂等 429）外，业务码基本被压成 `500`。调用方目前**无法**据此区分"会话未绑定"“实例不可用”“参数非法”，只能匹配 `message` 文本。见限制清单 #3。

### 6.2 流式：错误走事件流

`/chat/stream` 与 `/confirm` 的 `produces = text/event-stream`。正常失败路径下，错误以事件形式下发后关闭流：

| 事件类型 | 载荷 | 语义 |
|----------|------|------|
| `ErrorChatEvent` | `code: String`、`message: String` | **`code` 是 `HarnaxErrorCode` 的字符串码，不是 HTTP 状态码**，不要用数字比较 |
| `EndEventChatEvent` | `tokenUsage?` | 正常结束 |

其余为内容类事件：`StreamThinkingChatEvent`、`StreamTextChatEvent`、`CallToolChatEvent`、`ToolResultChatEvent`、`ToolConfirmChatEvent`（HITL 挂起，需回 `/confirm`）。

**调用方处理规约**：读到 `ErrorChatEvent` 就当业务失败并结束，不要重试整个流——若错误前已投递过内容，重试会造成重复输出（Router 侧同样遵守这条"首事件后不重放"约定）。

### 6.3 会话未绑定

会话从未走过对话、或绑定已过期时，Router **不会** 联系任何 agent-service，直接返回未绑定错误。含义上等价于"这个会话还没开始"。对 workspace 类端点同样成立——没有绑定就没有文件可列。

### 6.4 归属校验失败在 SSE 端点的当前行为（务必注意）

| 端点类别 | 当前行为 | 调用方会看到 |
|----------|----------|--------------|
| 非流式（`/chat`、`/command`、历史、workspace） | 异常进全局处理器 → JSON `ResultVo` | 正常可读的错误体（HTTP 200） |
| **流式（`/chat/stream`、`/confirm`）** | guard 在 `try` 之外，异常同样逃向全局处理器去写 JSON，但端点声明的是 `text/event-stream` | 可能 406 协商失败；即便写出 JSON，channel 的 `bodyToFlux(ChatEvent)` 解码也会失败，并被兜底成"连不上 Router"的网络错误 |

后果：**跨租户访问被正确拒绝，但调用方看到的是一条误导性的网络故障消息**，排查方向会被带偏。这是当前已知缺陷（限制清单 #2），在修好之前，channel / webui 侧遇到流式秒失败且报"网络故障"，应当先确认是不是 403 归属拒绝。

## 7. 租户隔离承诺

### 7.1 校验点

全部 14 个会话作用域代理端点在**查找实例之前**都要过 `SessionAccessGuard.requireAccessible(sessionId)`。汇聚点是 `boundInstance()`，因此不存在"某个端点忘了加"的旁路。

比对的两侧：调用方的 `tenantId`（来自 JWT 的 `tenantId` claim，或 API Key 上挂的租户）↔ admin 告知的会话所属 `tenantId`。不一致即拒绝（`SecurityException`）。

`chn-` 会话的归属自发布 3 起是**可判定**的：admin 对 `chn-` 前缀不再回"查不到"，而是从 `channel` 行取 `tenant_id`，且**不看 `active`**——软删除的频道照样归属它原来的租户（删除动作既不清 session 也不清 sandbox，能让一次读取变得无主可归的守卫不是删除）。所以"用别的租户的登录态读某个频道会话的历史与工作区"今天是拒绝，不是放行。`task-` 不走这条路：它仍由前缀规则挡在 lookup 之前，只有无终端用户的内部调用方可用（其归属查询属 scheduler 域，见定时任务文档）。

这条 guard 存在的原因值得记录：Router 转发时打的是**自己的**服务令牌，agent-service 看到的是"一个对等服务"，无法据此挡跨租户；而 inbound 鉴权只回答"能不能用 Router"，从不回答"能不能读这个会话"。

### 7.2 三条显式放行（不是漏洞，但决定了承诺的边界）

| 放行条件 | 原因 |
|----------|------|
| 调用方没有 `tenantId`（内部服务 token、`SYSTEM` 级 API Key） | channel 是代表它已经认证过的用户来路由的，两侧无可比对的值；硬造一个拒绝只会把运维逼向关闭鉴权 |
| admin 说这个会话不存在（`Unknown`） | 什么都没绑定，代理端点会答"未绑定"，不接触任何实例；归属也无法证实或证伪 |
| admin 不可达（`Unreachable`） | 不因 admin 故障阻断对话链路，打 warn 放行 |

### 7.3 粒度假设与精度边界

**必须明确的粒度**：隔离单位是**租户（tenant）**，不是用户（user）。

- 同一租户内的不同登录用户，**彼此可以访问对方的会话**（拿得到该租户的有效凭证即可）。
- 需要用户级隔离时，必须由上层（admin 的会话授权、或渠道侧的用户—会话映射）保证，Router 不提供。

**精度边界**：

| 项 | 值 / 行为 |
|----|-----------|
| 归属查询缓存 | 5000 条、写后 5 分钟过期 → 归属信息在窗口内以缓存值判定 |
| 查询超时 | `admin.internal-api.timeout-response-ms=3000`，最坏再加 1s 兜底 |
| 查询发生在请求线程 | 用 `runBlocking`，见限制清单 #8 |

### 7.4 输入与 SSRF 防护

| 防护 | 规则 |
|------|------|
| `sessionId` 格式 | `[A-Za-z0-9._:-]{1,128}`，且拒绝 `..`；不合规直接 `IllegalArgumentException` |
| 实例注册地址 | `isValidIpAddress` + 内网/回环黑名单（`isBlockedHost`），端口限 **8000–9999** |
| workspace `path` | 先 `substringBefore(';')` 去掉 matrix parameter，再用 `UriUtils.encodePathSegment` / `pathSegment()` 构造 URI，不做字符串拼接 |

> 实例注册口是 SSRF 的主要风险面（注册一个 `169.254.169.254:80` 就能让 Router 替攻击者发请求），因此校验放在**注册时的入口过滤器**而非使用时——脏数据根本不进注册表。

## 8. 超时与容量预算

### 8.1 分层超时表（当前实际值）

| 层 | 参数 | 值 | 备注 |
|----|------|-----|------|
| 客户端 → nginx | SSE location `proxy_read_timeout` | **900s** | 覆盖流式端点 |
| 客户端 → nginx | 普通 `/api/router/` `proxy_read_timeout` | **60s** ⚠️ | 与下行不齐 |
| nginx → Router | 同上跟随 | 60s ⚠️ | 长非流式请求会被网关先切 |
| Router → agent-service（非流式） | `router.proxy.read-timeout-ms` | **600s** | AI 处理耗时预算 |
| Router → agent-service（连接） | `router.proxy.connect-timeout-ms` | 5s | |
| Router 流式自身 | `stream-idle-timeout-seconds` | 120s | 沉默这么久即判定流结束 |
| Router 流式自身 | `stream-max-duration-minutes` | 30min | 无论多活跃都有墙钟上限 |
| Router → Redis | `spring.data.redis.timeout` / `connect-timeout` | 3s / 2s | **故意设短**：快速失败才能走降级 |
| Router → admin | `admin.internal-api.timeout-response-ms` | 3s | 另有 1s 兜底 |
| 幂等窗口 | `router.idempotency.ttl-seconds` | 60s | |

⚠️ **两处不齐是已知问题**：非流式 `/chat` 在 Router 侧允许跑 600s，但 nginx 60s 就会切断，调用方收到 504 而 Router 可能仍在成功收尾（重复模型调用与计费风险）。见限制清单 #5。

### 8.2 连接池（防止一个坏实例拖垮整个 Router）

| 参数 | 值 | 语义 |
|------|-----|------|
| `max-connections-per-instance` | 50 | Reactor Netty 按 `host:port` 分池，所以这是**单个 agent 实例**能占用的上限 |
| `pending-acquire-timeout-ms` | 10s | 池满时调用方最多排队等待 |
| `pending-acquire-max-count` | 100 | 等待者超过此数直接快速拒绝，而不是让大家一起超时 |
| `pool-max-idle-seconds` | 60s | 空闲连接回收 |
| `pool-max-lifetime-minutes` | 5min | 按年龄强制回收，避免会话搬家后仍复用旧实例连接 |
| `max-in-memory-size-mb` | 16 | 非流式响应缓冲上限；**大文件下载不走这条路径** |

### 8.3 熔断

| 参数 | 值 | 语义 |
|------|-----|------|
| `failure-threshold` | 3 | 连续 3 次可重试失败即开熔断 |
| `open-duration-ms` | 30s | 开熔断期间不再把**新会话**放上去 |
| `probe-lease-ms` | 30s | 半开状态只留给**一个**探测请求（租约式单槽），避免半开瞬间被并发打爆 |

多副本下熔断状态经 Redis 共享：A 副本熔断的实例，B/C 副本同样不再放置。`local` 模式仅本节点有效。

## 9. 可观测性

### 9.1 指标（`/actuator/prometheus`）

| 指标 | 类型 | 标签 | 用途 |
|------|------|------|------|
| `router.proxy.duration` | Timer | `endpoint` | 代理耗时分布 |
| `router.proxy.requests` | Counter | `endpoint`、`status`（`ok`/`error`） | 成功率 |
| `router.failover.count` | Counter | `endpoint`、`attempt` | 故障转移发生频率与第几跳 |
| `router.healthy.instances` | Gauge | — | 本副本此刻可放置的实例数（排除 DRAINING） |

> 告警建议：`router.healthy.instances` 要用 **`min()` 而不是 `avg()`**——副本之间视图不一致时，平均值会掩盖"某个副本完全看不到实例"。规则样例见 README 的「告警建议」。

### 9.2 调用日志（`api_call_log`）

只有 `redis` 模式（MySQL）落这一份；`local` 模式落 SQLite，字段一致。

| 字段组 | 字段 |
|--------|------|
| 调用方 | `caller_id`、`caller_type`（`INTERNAL_SERVICE`/`EXTERNAL_API`）、`tenant_id` |
| 会话与目标 | `session_id`、`agent_id`、`agent_name`、`model_id`、`model_name`、`instance_id` |
| 请求 | `endpoint`、`method`、`request_type`（`CHAT`/`COMMAND`/`CONFIRM`）、`request_id` |
| 结果 | `status_code`、`success`、`error_message`、`start_time`、`end_time`、`duration_ms` |

采集方式：`ApiCallLogFilter` 白名单策略（只对需要记录的路径缓存响应体），异步批量落库（批 50、每 5s），缓冲区上限 10000 行，溢出丢弃并告警。SSE 请求通过 `AsyncListener` 在流关闭后才落这一行，因此 `duration_ms` 覆盖整个流的生命周期。

**保留期：无自动清理**。当前没有任何按时间 DELETE 的任务，表只增不减。运维需自行安排归档/清理，见限制清单 #7。

### 9.3 用 sessionId 定位问题

| 手段 | 可用性 |
|------|--------|
| `api_call_log` 按 `session_id` 查 | ✅ 最可靠，含 `instance_id`，可直接定位是哪个实例 |
| `GET /api/router/monitor/instances` | ✅ 看实例侧会话分布 |
| 应用日志中的 MDC | ⚠️ 不可靠：协程挂起后 MDC 不跟随，且可能在 Tomcat 线程上残留到下一个请求（限制清单 #11）。**不要用日志里的字段确认落点实例，用 `api_call_log.instance_id`** |

## 10. 当前限制清单（真相源）

按对调用方的影响排序。「规避方式」是在修复之前可以先用的手段。

| # | 限制 | 影响 | 规避方式 |
|---|------|------|----------|
| 1 | **不支持 Redis Cluster**，`REDIS_CLUSTER_NODES` 非空即拒绝启动 | 只能用 standalone / Sentinel 做 Redis HA | 用 Sentinel；若必须 Cluster，需先做键布局改造（单 hash tag 路线，见 README） |
| 2 | **SSE 端点的归属拒绝返回 JSON 而非事件流** | 跨租户访问表现为"连不上 Router"的网络故障，误导排查 | 流式秒失败且报网络故障时，先查是不是 403 归属拒绝（看 Router 日志 `Rejected a cross-tenant session access`） |
| 3 | 非流式业务错误码全部压成 `500` | 调用方无法程序化区分未绑定/无实例/参数非法 | 只能匹配 `message` 文本；或先查会话状态 |
| 4 | **流式端点无幂等**；`/chat` 去重需调用方传 `requestId`（channel 当前不传） | 客户端重连可能触发两次模型调用 | 客户端侧自行去重；需要 Router 去重就生成并传 `requestId` |
| 5 | nginx `60s` 与 Router `600s` 非流式读超时不齐 | 长非流式请求被网关切断，Router 侧可能仍成功 → 重复调用/计费 | 长任务改走 `/chat/stream`（nginx 侧 900s）；或对齐两处配置 |
| 6 | 挂起类故障（`ReadTimeoutException`、连接池获取超时）不计熔断、不触发 failover | 实例"连得上但不回数据"时不会被自动摘掉，只报超时 | 依赖心跳超时（30s）兜底；必要时手工 `/instance/drain` |
| 7 | `api_call_log` 无保留期与清理任务 | 表只增不减，长期运行膨胀 | 运维侧定时按 `start_time` 归档/删除 |
| 8 | 会话归属校验是**租户粒度**，且查询在请求线程上 `runBlocking` | 同租户内用户之间不隔离；admin 慢时会占用请求线程（最坏约 4s） | 用户级隔离由上层保证；admin 保持健康 |
| 9 | `drain` 不可逆，无 `undrain` | 误操作只能重启实例恢复 | drain 前确认该实例确实要下线 |
| 10 | `local` 模式不共享任何状态 | 多副本部署时各副本独立放置，会话粘性随副本漂移 | `local` 只部署单副本（部署约束） |
| 11 | MDC 跨协程挂起点不清理 | 同一 Tomcat 线程的后续请求日志可能带上前一个请求的 session | 用 `api_call_log` 而非应用日志做归因 |

## 11. 相关代码与文档索引

| 主题 | 位置 |
|------|------|
| 代理与放置主流程 | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt` |
| 端点定义 | `.../router/controller/AgentProxyController.kt`、`InstanceRegistryController.kt`、`RouterMonitorController.kt` |
| 会话绑定（Redis / 本地双实现） | `.../router/service/impl/RedisSessionMappingService.kt`、`LocalSessionMappingService.kt` |
| 实例注册与健康检查 | `.../router/service/impl/RedisInstanceRegistry.kt`、`.../router/health/HeartbeatHealthChecker.kt` |
| 熔断 | `.../router/service/InstanceCircuitBreaker.kt` + `impl/RedisCircuitBreaker.kt`、`LocalInstanceCircuitBreaker.kt` |
| 反向索引对账 | `.../router/service/impl/SessionIndexReconciler.kt` |
| 租户隔离 | `.../router/service/SessionAccessGuard.kt`、`SessionInfoClient.kt` |
| 输入格式与 SSRF 防护 | `.../router/support/IdFormat.kt`、`.../router/entity/AgentInstance.kt` |
| 调用日志 | `.../router/config/ApiCallLogFilter.kt`、`.../router/service/ApiCallLogService.kt` |
| 拓扑决策（Redis Cluster 拒绝点） | `.../router/config/RedisConfig.kt`，测试 `src/test/.../config/RedisConfigTest.kt` |
| 事件与请求协议 | `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/`（`AgentRequest.kt`、`ChatEvent.kt`） |
| 模块实现细节与告警规则 | [`harnax-session-router/README.md`](../harnax-session-router/README.md) |
| 部署步骤 | [`docs/deploy-harnax-session-router.md`](../docs/deploy-harnax-session-router.md) |
| 渠道侧接入 | [`prod_doc/channel-integration.zh-CN.md`](./channel-integration.zh-CN.md) |
| 跨服务调用全景 | [`docs/http-call-network.md`](../docs/http-call-network.md) |
