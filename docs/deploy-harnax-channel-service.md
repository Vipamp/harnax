# harnax-channel-service 部署文档

## 服务概述

`harnax-channel-service` 是**聊天软件接入层**：把飞书 / 钉钉 / 企业微信 / 个人微信 / HTTP 自定义通道的消息接进来，转成一次与智能体的对话，再把回答送回原平台。

两类接入方式在运维上完全不同，先看这一句：

| 通道类型 | 连接方向 | 多实例行为 |
|---|---|---|
| 飞书 / 钉钉 / 企业微信 / 微信（长连接） | 本服务**主动外连**平台 | 只能有一个实例真正持有连接（见「监听器所有权」），其余实例是热备 |
| 飞书 HTTP 事件回调 | 平台**回调进来** | 只有飞书支持回调（`supportsCallback()` 仅飞书为 `true`）；钉钉 / 企微 / 微信没有可验证的等价 HTTP 事件契约，给它们配 webhook 会在启动时直接记失败并提示改用长连接 |

端口固定 `8083`（`server.port` 未做环境变量占位），优雅停机已开启。它**不对外提供业务 API**：除平台回调外，其余接口都要服务间认证。

---

## 系统部署总览

```
平台（飞书 / 钉钉 / 企业微信 / 微信）
   │  长连接（本服务外连）        ▲ HTTP 回调（公网入口）
   ▼                            │
channel-service (×N，见下文) ────┘
   │
   ├──► router   —— 转发对话、消费流式事件
   ├──► admin    —— 取系统 API Key、读通道配置
   └──► MySQL    —— 共享 harnax_admin（通道表；**本服务不建表**）
```

---

## 环境依赖

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | 21 | |
| MySQL | `harnax_admin` 库 | 与 admin **共用同一个库**；`spring.flyway.enabled=false`，表结构由 admin 迁移维护 |
| router | 必须可达 | 对话流量全部经 router 到 agent-service |
| admin | 必须可达（未显式配 API Key 时） | 启动时会向 admin 申请一把系统 Key，见 `CHANNEL_API_KEY` |
| Redis / MinIO | **不需要** | 会话上下文是进程内存缓存，不走外部存储 |

> `application.yml` 里数据源与 router 地址的默认值写的是某台开发机的局域网 IP（`172.20.10.5`），这是历史遗留：任何真实部署都必须显式给 `SPRING_DATASOURCE_URL` 与 `ROUTER_URL`，否则会连到一个不存在的地址上，症状是启动正常但对话全失败。

---

## 核心机制：监听器所有权

长连接通道**同一时刻只能由一个实例驱动**，否则同一条入站消息会被消费多次（用户在群里 @ 一次，AI 回三遍）。

- 实现是一把 MySQL `GET_LOCK` 命名锁（`CHANNEL_LOCK_NAME`，默认 `harnax-channel-listeners`）：抢到锁的实例启动监听器并持续续心跳，抢不到的实例空转待命。
- 因此 `CHANNEL_LOCK_ENABLED=false` 不是「关掉一个功能」，而是**放弃这条保证**：只有在确实做了分片（每个实例只驱动一部分通道）时才可以关，否则等价于允许多重消费。
- 锁的持有者是数据库连接级的：实例被杀、网络分区导致连接断开，锁释放，另一实例在下一轮 reconcile 接管。切换窗口内的消息由平台侧重投或丢弃，取决于各平台策略。

## 通道对账（reconcile）

一个后台循环按 `CHANNEL_SYNC_INTERVAL_MS` 轮询通道表，把「库里存在且启用、但本地没在跑」的监听器拉起来，把「本地在跑但库里已删/已停」的关掉。关键旋钮：

| 变量 | 默认 | 作用 |
|---|---|---|
| `CHANNEL_SYNC_INTERVAL_MS` | `10000` | 轮询周期 |
| `CHANNEL_SYNC_MAX_STARTS` | `5` | 一轮最多启动几个监听器（防止刚连上就被限流）；`<=0` 表示不限 |
| `CHANNEL_RESTART_BACKOFF_INITIAL_MS` | `2000` | 死掉的监听器首次重连等待 |
| `CHANNEL_RESTART_BACKOFF_MAX_MS` | `300000` | 退避上限，**同时是「曾经健康足够久」的重置阈值** |
| `CHANNEL_STALE_HEARTBEAT_MS` | `180000` | 状态是 CONNECTED 但这么久没心跳就重启；`0` 关闭该检测 |

## 入站消息处理与预算

| 变量 | 默认 | 作用与调整提示 |
|---|---|---|
| `CHANNEL_TURN_POOL_SIZE` | `24` | 处理入站回合的线程池上限（取代原先共享的 `Dispatchers.IO`）。抬它要给 agent 侧留余量：每个在处理的回合都对应一条到 router 的长连接 |
| `CHANNEL_TURN_PER_CHANNEL` | `4` | 单个通道内最多并发多少个会话，防一个热门群吃满整池 |
| `CHANNEL_SESSION_MAX_SESSIONS` | `10000` | 内存里保多少个对话（LRU 淘汰） |
| `CHANNEL_SESSION_MAX_MESSAGES` | `500` | 每个对话保多少条消息 |
| `CHANNEL_SESSION_IDLE_TTL_MIN` | `120` | 空闲多久丢弃；`0` 关闭 |

三者是**内存上限**：进程重启即清空。也就是说重启后所有通道的上下文都从头开始（消息历史本身在别处持久化，但这一层的紧凑上下文会丢）。

## 调 router 的保护

| 变量 | 默认 | 作用 |
|---|---|---|
| `ROUTER_BREAKER_ENABLED` | `true` | 是否启用熔断 |
| `ROUTER_BREAKER_FAILURE_THRESHOLD` | `5` | 连续失败几次后打开 |
| `ROUTER_BREAKER_OPEN_DURATION_MS` | `30000` | 打开多久后放一次探测请求 |
| `CHANNEL_STREAM_IDLE_TIMEOUT_MS` | `180000` | SSE 流多久不出事件就取消；`0` 关闭 |

固定值（无环境变量）：TCP 连接超时 `5s`、HTTP 响应超时 `600s`（AI 处理上限）。

## 健康指示

| 变量 | 默认 | 作用 |
|---|---|---|
| `CHANNEL_HEALTH_ENABLED` | `true` | 是否注册「平台连接状态」健康指示 |
| `CHANNEL_HEALTH_FAILURE_GRACE_MS` | `60000` | 一个通道持续 FAILED 多久后整体转 DOWN |

这两个只影响 `/actuator/health`，**与容器存活探针无关**：平台连接断开只是服务降级，不该因此重启容器。探针路径 `/actuator/health/liveness`。

---

## 环境变量总览

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://172.20.10.5:3306/harnax_admin?...` | **必须显式覆盖** |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `root` / `123456` | 与 admin 同库同账号 |
| `ROUTER_URL` | `http://172.20.10.5:8081` | router 地址，**必须显式覆盖** |
| `ADMIN_SERVICE_URL` | `http://localhost:8080` | admin 地址 |
| `CHANNEL_API_KEY` | 空 | 留空则启动时向 admin 申请系统 Key（去掉这个启动依赖就显式填一把）；配置后不再申请 |
| `SERVICE_ID` | `channel-0` | 服务间认证标识，多实例建议各不相同 |
| `HARNAX_AUTH_SECRET` | 占位串 | 与其他服务一致 |
| `ADMIN_INTERNAL_API_SECRET` | —（compose 注入） | 用于向 admin 取系统 Key |
| `CHANNEL_LOCK_ENABLED` / `CHANNEL_LOCK_NAME` | `true` / `harnax-channel-listeners` | 见「监听器所有权」 |
| `CHANNEL_SYNC_INTERVAL_MS` / `CHANNEL_SYNC_MAX_STARTS` | `10000` / `5` | 对账周期与每轮启动上限 |
| `CHANNEL_RESTART_BACKOFF_INITIAL_MS` / `..._MAX_MS` | `2000` / `300000` | 重连退避 |
| `CHANNEL_STALE_HEARTBEAT_MS` | `180000` | 假死判定 |
| `CHANNEL_TURN_POOL_SIZE` / `CHANNEL_TURN_PER_CHANNEL` | `24` / `4` | 回合并发 |
| `CHANNEL_SESSION_MAX_SESSIONS` / `..._MAX_MESSAGES` / `..._IDLE_TTL_MIN` | `10000` / `500` / `120` | 上下文缓存 |
| `ROUTER_BREAKER_ENABLED` / `..._FAILURE_THRESHOLD` / `..._OPEN_DURATION_MS` | `true` / `5` / `30000` | 熔断 |
| `CHANNEL_STREAM_IDLE_TIMEOUT_MS` | `180000` | SSE 空转取消 |
| `CHANNEL_HEALTH_ENABLED` / `CHANNEL_HEALTH_FAILURE_GRACE_MS` | `true` / `60000` | 健康指示 |

---

## 公网入口与回调安全

只有 `/api/channel/callback` 免服务间认证（`harnax.auth.skip-paths`）：平台回调带不了我们的内部 token，**认证靠平台自己的签名校验**。这条路径目前**只服务飞书**：

1. 飞书签名是 `sha256Hex(timestamp + nonce + encryptKey + body)`（不是 HMAC，也不用 appSecret），验签与 AES 解密都委托官方 `EventDispatcher` 完成——不要在这里另写一套 crypto，飞书对无法验证的事件本身就会拒收。
2. 钉钉 / 企微 / 微信**没有可验证的等价 HTTP 事件契约**，`ChannelAdaptor.supportsCallback()` 只有飞书为 `true`；给这些通道配 webhook 会在启动时被 `ChannelBootstrapRunner` 记为失败并提示改用长连接。适配器里 `verifySignature` 返回 `true`、`parseMessage` 是空实现，那是 SDK 接口兼容，不是漏实现——补一个通用回调端点等于开一个接收未验签请求的公开入口，比不补更糟。
3. 暴露到公网时只放行这一条前缀，其余 `/api/channel/**` 不开放；平台后台登记的回调地址必须与对外 origin 完全一致，改外部地址（`APP_BASE_URL` 一类）时平台侧要同步改，否则表现为「消息不再进来」。
4. 换通道的回调密钥（encryptKey / verification token）要在平台侧同步改，否则同样是静默失联而不是报错。

逐平台细节与仍然存在的限制见 `prod_doc/channel-integration.zh-CN.md` 第 12 节。

## 渠道只发文本

**文件不经通道下发**：飞书 / 钉钉 / 企微都不接上传接口（能力由 `ChannelAdaptor.supportsFileDelivery()` 声明），生成的产物一律在 **Web UI 下载**（只有微信有真实上传能力）。所以 agent 侧的输出文件配置（`OUTPUT_*`）与通道无关：通道用户收到的是文本，外加一句「文件请在 Web UI 获取」，且一轮只提示一次并列出全部文件名。门槛刻意放在**取文件字节之前**——为一个没人能收的上传去拉几十 MB 会把整个回合拖到超时。

## 停机顺序

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`（留给 `@PreDestroy` 里监听器拆除的预算），compose 里对应 `stop_grace_period: 35s`。**这个 35 必须大于内部的 20**：否则容器在监听器还没拆完时就被 SIGKILL，平台侧连接残留到对端超时，切换窗口内可能重复投递。

## 多副本注意

- 长连接通道：可以放心多副本，锁保证只有一个实例驱动，其余是热备（故障时自动接管）。
- 飞书 HTTP 回调：可以水平扩展，但**上下文缓存在各实例内存里**（`CHANNEL_SESSION_*`），同一会话的两次回调落到不同实例会各说各话。要么给负载均衡配会话粘滞，要么这条通道只指向一个实例——默认长连接接入不存在这个问题。
- 无论哪种，实例数上升不会让单条通道的并发变高：每通道的并发上限由 `CHANNEL_TURN_PER_CHANNEL` 决定。

## 与 MCP / 智能体能力的边界

渠道消息**没有平台账号身份**（发送者是 IM 侧的 open id）。因此挂在本通道智能体上的 **OAuth 2.1 类型 MCP 服务不会被加载**——运行时无法确定该用谁的授权。用户级 OAuth 工具要生效，请使用网页端 / 小程序会话（详见 `docs/deploy-harnax-agent-service.md` 的「MCP 的两条部署开关」）。同一条会话在网页端能看到那些工具、在 IM 里看不到，是这个原因，不是同步故障。

---

## 运维观测

| 端点 | 内容 |
|---|---|
| `/actuator/health` | 含各平台连接状态（`show-details: always`） |
| `/actuator/health/liveness` | 容器存活探针（与平台连接无关，不会因掉线被重拉） |
| `/actuator/channels` | 逐通道状态：是否持有锁、连接状态、最后心跳、重启计数 |
| `/actuator/metrics`、`/actuator/prometheus` | 回合处理、熔断与缓存指标，tag `application=harnax-channel-service` |

## 常见问题

| 现象 | 先看什么 |
|---|---|
| 群里 @ 一次回多遍 | 是否有实例把 `CHANNEL_LOCK_ENABLED` 关掉了；比对各实例 `/actuator/channels` 里「是否持有锁」 |
| 完全没有消息进来 | 启动日志里数据源 / router 地址是否还是默认的局域网 IP；`/actuator/channels` 里连接状态与最后心跳 |
| 消息进得来但无回答 | router 是否可达、熔断是否打开（连续 5 次失败即开，30s 后探测） |
| 上下文总是"忘记" | 是否走的飞书 HTTP 回调 + 多副本无粘滞；或实例刚重启过（缓存是内存的） |
| 长对话中途没响应 | `CHANNEL_STREAM_IDLE_TIMEOUT_MS`（默认 3 分钟无事件即取消）；再看 agent 侧是否卡住 |
| 发布时短暂重复投递 | 停机窗口是否被 `stop_grace_period` 卡住（须 > 20s 的拆除预算） |

---

## 相关文档

- `docs/channel-to-agent-flow.md`：从平台回调到智能体的完整链路与状态机
- `prod_doc/channel-integration.zh-CN.md`：各平台接入步骤与凭据申请
- `docs/deploy-harnax-session-router.md`：router 的部署与集群模式
- `docs/deploy-harnax-agent-service.md`：运行时侧的 MCP 开关与密钥一致性
