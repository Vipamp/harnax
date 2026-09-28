# Harnax 渠道集成（Channel）

> 本文是 `harnax-channel` 的完整设计文档：支持哪些平台、消息怎么进怎么出、会话怎么算、连接怎么保活、
> 管理面下发什么、出问题去哪儿看。面向接入渠道的开发者与排障的运维。
>
> 每一条陈述都以当前代码为准，模块内注释与本文冲突时以代码为准。

## 1. 定位与模块划分

渠道层把外部 IM（飞书 / 微信 / 企微 / 钉钉）接成 Agent 的对话入口：平台侧的收发消息由渠道层承担，
Agent 的推理与会话由 `harnax-session-router` + `harnax-agent-service` 承担，两者之间只走 HTTP。

| 模块 | 职责 | 依赖 |
|------|------|------|
| `harnax-channel/harnax-channel-sdk` | 平台无关抽象：`ChannelAdaptor`、`AgentAdaptor`、`ChannelCommunicationMode`、`ChannelChatService`、`ReplyMarkers`、消息模型、去重、分块、退避、连接状态、`ChannelTurnExecutor` | 仅 Kotlin 协程 + `harnax-protocol`，不依赖 Spring |
| `harnax-channel/harnax-channel-service` | 可运行服务（容器内端口 8083，compose 发布为 `28083:8083`）：`ChannelBootstrapRunner` 对账循环、`ChannelListenerLockGuard` 监听互斥、`RouterClient`、`RouterCircuitBreaker`、健康指示、`/actuator/channels`、`ChannelCallbackController`、API Key 初始化 | Spring Boot + MyBatis（读 `harnax_admin` 库的 `channel` 表） |
| `harnax-channel/harnax-channel-feishu` | 飞书适配器 + `FeishuWebSocketMode`（官方 `oapi-sdk` 长连接）+ `FeishuWsTransport` 注入点 | 飞书开放平台 |
| `harnax-channel/harnax-channel-wecom` | 企微适配器 + `WecomWebSocketMode`（自研 OkHttp WebSocket 帧协议） | 企微智能机器人 |
| `harnax-channel/harnax-channel-wechat` | 微信适配器 + `WechatLongPollingMode`（iLink 长轮询）+ 唯一实现 `sendFile` 的适配器 | 微信 iLink |
| `harnax-channel/harnax-channel-dingtalk` | 钉钉适配器 + `DingtalkStreamMode`（官方 `app-stream-client`） | 钉钉开放平台 |

`ChannelType` 有五个 code：`wecom` / `wechat` / `feishu` / `dingtalk` / `http`。
`ChannelType.fromCode` 大小写敏感且不匹配时返回 `null`，由 `ChannelEntityConverter.toSpec` 把它变成
`IllegalArgumentException("Unsupported channel type: ...")`。分发只看 `ChannelType`：
`ChannelAdaptorRegistry` 把所有 `ChannelAdaptor` Bean 按 `getType()` 建索引，新增一个平台只需要注册
一个新 adaptor Bean。`http` 类型没有注册任何 adaptor，它出现在类型集合里：admin 的
`ChannelServiceImpl.MODES_BY_TYPE` 只允许它 `webhook`，所以 `ChannelBootstrapRunner.startChannel` 先落在
webhook 分支，启动失败记录的是
`webhook callback mode is not implemented for http; use this platform's long-connection mode`。同一个类里
长连接分支的 `no adaptor registered for channel type ...` 对 `http` 取不到。

`communicationMode` 描述传输方式，并决定「这个渠道需不需要本进程持有连接」，不参与选路。

## 2. 能力矩阵

| 平台 | 传输模式 | 入站 | 回发 | 幂等去重 | 文件下发 | 流式输出 | HTTP 回调契约 |
|------|----------|------|------|----------|----------|----------|---------------|
| 飞书 | `websocket`（官方 SDK 长连接） | 事件订阅 | `im/v1/messages` | 按 `msgId` | ❌ | ❌ | ✅ |
| 飞书 | `webhook` | `POST /api/channel/callback/{callbackKey}` | 平台 webhook 地址 | 按 messageId | ❌ | ❌ | ✅ |
| 钉钉 | `stream`（官方 SDK） | BOT_MESSAGE_TOPIC | `sessionWebhook` → 机器人 OpenAPI 兜底 | 按 `msgId` | ❌ | ❌ | ❌ |
| 企微 | `websocket`（自研帧协议） | `aibot_*` 帧 | `aibot_respond_msg` → `aibot_send_msg` 兜底 | 按 `msgid` | ❌ | ❌ | ❌ |
| 微信 | `long_polling`（iLink） | 长轮询 | iLink API | 按 `message_id` | ✅ | ❌ | ❌ |
| HTTP | `webhook` | — | — | — | ❌ | ❌ | ❌（无 adaptor） |

能力位由 `ChannelAdaptor` 声明，默认值即上表：

- `supportsStreamingOutput()` 默认 `false`，四个平台适配器都不覆盖它。于是
  `shouldUseStreaming(agentAdaptor) = supportsStreamingOutput() && agentAdaptor.supportsStreaming()`
  恒为 `false`——所有渠道都走批量输出（`ChannelChatService.batchSend()`），AI 处理期间只发一个
  「正在输入」指示。流式分支（`streamAndSend()`、`sendStreamingFragment()`）的协议与实现是通的，
  决策权在 Channel 实现手里，只是当前没有平台选中它。
- `supportsCallback()` 默认 `false`，只有飞书返回 `true`。`ChannelAdaptor.handleCallback()` 的默认实现
  抛 `UnsupportedOperationException`，文案是
  `${getType().code} channels have no HTTP callback contract; use the long-connection communication mode`。
- `supportsFileDelivery()` 默认 `false`，只有微信返回 `true`。这道门槛在**取文件字节之前**检查：
  为一个没人能收的上传去拉几十 MB 只会把 turn 拖到超时。

## 3. 端到端链路

```
平台推送 / 轮询 / HTTP 回调
  └─ FeishuWebSocketMode.handle / DingtalkStreamMode.handleBotMessage
     / WecomWebSocketMode 帧处理 / WechatLongPollingMode
     / ChannelCallbackController → FeishuAdaptor.handleCallback（飞书 webhook）
        │ ①归属守卫：当前 holder 是否还是这个 channelId 的监听者
        │ ②去重：MessageDeduplicator.tryBegin(msgId)
        │ ③解析：平台 MessageParser → ChannelMessage
        │ ④缓存回发坐标（钉钉 sessionWebhook / 企微回调 req_id / 主动发送目标）
        ▼
     ChannelTurnExecutor.launchTurn(channelId, platformSessionId)
        │ per-session 串行锁 → per-channel Semaphore（固定线程池承载）
        ▼
     Adaptor 内的 handler（AgentAdaptor + ChannelSessionManager + ChannelChatService
     三件套由 startChannelWithAgent 接线）
        messageParser.parse(message).withSessionId(channel.sessionId)
        ▼
     ChannelChatService.chat(message, channelSpec, agentAdaptor, channelAdaptor, agentRequest)
        │ 读历史 → 存用户消息 → 组 AgentContext → 选输出策略 → /clear 时清渠道侧缓存
        ▼
     RouterAgentAdaptor.process(context)
        ▼
     RouterClient（RestClient / WebClient + RouterCircuitBreaker）
        │ POST /api/router/agent/chat       （批量，当前恒走这条）
        │ POST /api/router/agent/chat/stream（流式，当前不会被选中）
        │ POST /api/router/agent/command    （斜杠命令）
        ▼
     harnax-session-router → 按 sessionId 选实例 → harnax-agent-service
        ▼
     ChatResponse{content, attachments} / 流式事件（末端事件带 attachments）
        │ 批量回发：channelAdaptor.sendMessage()（内部按平台上限分块）
        │ 文件投递：deliverFileAttachments()——批量与流式共用这一个入口
        └ 保存 assistant 消息到渠道侧会话
```

一次消息处理叫一个 **turn**。`ChannelTurnExecutor.launchTurn` 里 turn 体的异常被捕获后交给
`ChannelMetricsSink.onTurnCompleted`，不冒泡回平台的接收线程；`CancellationException` 例外，照常向上抛。

## 4. 会话与身份模型

- **一条 `channel` 记录 = 一个 Agent 会话。** `channel.session_id` 形如 `chn-<uuid>`，由 admin 的
  `ChannelServiceImpl.generateSessionId()` 在创建渠道时生成，之后不可变，是 Agent 侧唯一的会话标识。
  适配器在把消息交给 `ChannelChatService` 之前，一律用 `withSessionId(channel.sessionId)` **覆盖**
  解析出来的 sessionId。
- **平台会话 id（`chat_id` / `conversationId` / `from_user_id` 等）只用于两件事：** 回发消息时定位
  收件人，以及 `ChannelTurnExecutor` 的 per-session 串行锁 key（`"<channelId>:<sessionId>"`）。
  它不代表 Agent 侧的会话。
- 因此：**同一个机器人下，所有用户、所有群共享同一段 Agent 记忆。** 需要按人隔离会话时，
  一个用户一条 channel 记录。
- **`chn-` 会话在 `session` 表里没有行。** 会话 API 只铸造 `web-` 与 `mp-` 两种 id，
  渠道会话的状态只存在运行侧（agent-service 的会话状态、计划、沙箱容器）。归属信息落在 `channel`
  行本身：`ChannelMapper.selectOwnerBySessionId` 按 `session_id` 返回 `sessionId` / `tenantId` /
  `agentId` 三列，并且**刻意不带 `active = 1` 过滤**——软删之后的行仍然要答得出归属，否则「删掉一个
  渠道」会变成「抹掉读取该会话的责任记录」。`harnax-session-router` 的 `SessionAccessGuard` 用这份
  答复做同租户判断：同租户放行，跨租户拒绝。
- **`chn-` 会话没有用户身份，因而不加载 OAuth 类 MCP。** `McpSessionOwnerResolver.resolve()` 对
  `web-` / `mp-` 查 `session` 行、对 `task-` 走 scheduler 的属主端点，对 `chn-` 直接返回 `null` 并记一条
  debug：渠道会话的 `creator` 是平台侧的发送者标识，不是平台账号。返回 `null` 不是错误，规则是
  「没有用户就没有 OAuth 工具」。

渠道侧另有一份内存会话缓存 `InMemoryChannelSessionManager`（key = `channelId` + 平台会话 id），
用于拼 `AgentContext.history`。`RouterAgentAdaptor` 不消费 `history`——Agent 侧的记忆由 `chn-` 会话自己
维护，这份缓存服务的是 `/clear` 的连带清理与本地裁剪。

## 5. 监听器生命周期与对账

`ChannelBootstrapRunner` 是唯一入口。`ApplicationReadyEvent` 触发第一轮 `reconcile()`，
此后由 `@Scheduled(fixedDelayString = "${channel.sync.interval-ms}")` 驱动（`fixedDelay` 保证一轮
不会与上一轮重叠，慢的平台握手可能让一轮超过间隔）。

一轮 `reconcile()` 按固定顺序做四件事，前一件是后一件的前提：

| 顺序 | 关注点 | 行为 |
|------|--------|------|
| 1 | 所有权 | `lockGuard.hold()` 为假时，若本机在跑就 `stopAll()`，然后直接返回 |
| 2 | 成员关系 | 内存里在跑、DB 里已不在自动启动集 → `stopChannel()`；DB 里有、内存里没有（按 id 升序）→ `startChannel()` |
| 3 | 配置漂移 | `configFingerprint()` 变化 → 立即重启（视为人为操作，不等退避），仍受每轮启动预算限制 |
| 4 | 存活 | 见下 |

`selectAutoStartChannels` 的条件是 `enabled = 1 AND status = 1 AND active = 1`，按 `id ASC` 排序。

「已启动」的分支里（`reconcileRunning`）判据依次为：

- `startFailed` 且 `retryDue()` → 重启；
- `listening == false`（webhook 型渠道）→ 清掉该渠道的退避状态，不探活；
- **半死**：`status == CONNECTED` 且 `lastHeartbeatAt > 0` 且静默超过 `stale-heartbeat-ms` → 走
  统一的 `retryDue()` 门限后重启，并以 `healthyLifetime = false` 记这次断开（该 socket 的存活时长
  不计作健康寿命，否则退避会被立刻重置，又回到每轮重启）；
- `state.isServing()` → 重置退避，什么都不做；
- 其余（状态非 serving）→ 过 `retryDue()` 门限后重启。

关键设计点：

- **`configFingerprint(entity)`** 是这八个字段用 `|` 拼接：`type`、`communicationMode`、`agentId`、
  `enabled`、`status`、`sessionId`、`callbackKey`、`configJson`（空视为 `""`）。不用 `update_time`：
  MySQL `DATETIME` 只到秒，同秒内的两次编辑区分不了，而纯展示字段的变更会白白踢掉一条健康的长连接。
- **启动预算** `channel.sync.max-starts-per-cycle`（默认 5，`<=0` 表示不限）：一轮内新增、漂移重启、
  存活重启共用同一份预算，按 id 升序消耗，保证多副本和日志收敛到同一个渠道。
- **指数退避** `ReconnectBackoff`：`restart-backoff-initial-ms`（2 秒）起步、上限
  `restart-backoff-max-ms`（300 秒），`resetThreshold` 同取上限值（连接稳定持有到退避上限即视为健康，
  下次断开回到快速重试）。`retryDue()` 的门限是**当前**退避步长，所以反复失败的渠道越试越稀。
- **单实例互斥** `ChannelListenerLockGuard` 用 MySQL `GET_LOCK`（锁名 `channel.lock.name`）保证只有一个
  副本持有长连接——同一份凭证开两个监听的结果是消息被消费两次、会话历史分裂在两份内存里。
  失败策略刻意不对称：已经证得过所有权的连接遇到 DB 抖动时继续按 owner 服务；从未证得过所有权的新
  连接一律返回 `false`。
- **优雅停机** `@PreDestroy shutdown()`：置 `shuttingDown` → 拿 `reconcileLock` 后 `stopAll()` →
  逐个 `adaptor.shutdown()` 释放共享传输层 → `lockGuard.release()`。
  `spring.lifecycle.timeout-per-shutdown-phase: 20s` 是这段的预算。不做这件事的后果是进程退出时平台侧
  套接字还开着，平台继续往死连接里推，重启后的进程要等平台把那个会话回收掉才能重新监听。

### 5.1 各传输模式的保活手段

| 模式 | 谁持有连接 | 判活依据 |
|------|------------|----------|
| 飞书 `websocket` | 官方 `WsClient`（SDK 内部保活线程） | 首条入站事件是唯一正向证据；`start()` 干净返回不代表任何状态 |
| 钉钉 `stream` | 官方 SDK 的调度线程池 | 同上 |
| 企微 `websocket` | 自研 OkHttp WebSocket，带 `generation` 计数 | 订阅应答即视为连上；心跳 ping/pong 判死，被淘汰套接字的回调按 `generation` 丢弃 |
| 微信 `long_polling` | 自建守护线程轮询 | 轮询循环自己重试 |

三个官方/自研 SDK 的建立连接调用都是**非阻塞**的。由此定下两条不变式：

1. 监听器的 holder 只在 `stop()` 里退休。在连接调用返回时摘除 holder 会让归属守卫从此恒真，而套接字
   还活着，结果是每一条入站消息都被当成「过期监听器的残留」丢弃。
2. 判活不靠「连接调用返回得快不快」这类推断，只靠正向信号（首条入站消息 / 订阅应答 / 心跳）。

飞书与钉钉在 `stop()` 时打一行带「服务过多少事件、服务了多久」的日志：一条被平台掐掉、靠 SDK 自己
重连撑了几小时没收到任何消息的连接在这行日志里事件数为 0，而监控状态是 `CONNECTED`。

## 6. 连接状态机与可观测

`ChannelConnectionStatus`：`UNKNOWN → CONNECTING → CONNECTED`，异常进 `RECONNECTING` / `FAILED`，
主动停进 `STOPPED`。`isServing()` 把 `CONNECTED`、`CONNECTING`、`RECONNECTING` 算作在服务。

`ChannelConnectionState` 是不可变快照，携带 `sinceMillis`、`lastConnectedAt`、`lastActivityAt`、
`lastHeartbeatAt`、`reconnectCount`、`receivedCount`、`lastError`；时间戳为 0 表示「从未发生」。
`lastHeartbeatAt` 对保活在厂商 SDK 内部的传输保持为 0，这同时让它退出半死判定（空闲但健康的渠道
不会被反复重启）。

每个传输模式持有一个 `ChannelConnectionTracker`，是状态的唯一写入方；`ChannelRuntimeMonitor`
汇总成运行视图。三个暴露口：

| 出口 | 内容 |
|------|------|
| `/actuator/channels`（`ChannelRuntimeEndpoint`） | 监听锁是否持有、汇总计数、按状态分布、router 熔断快照、`missingListener` 集合、每条渠道的 `status` / `serving` / `statusAgeMs` / `lastConnectedAt` / `lastActivityAt` / `reconnectCount` / `receivedCount` / `lastError` |
| `/actuator/health`（`ChannelConnectionHealthIndicator`） | 全部在服务 → `UP`；有非 serving 但未硬失败 → `OUT_OF_SERVICE`；有 `FAILED` 持续超过 `monitor.health.failure-grace-ms`（默认 60 秒）→ `DOWN` |
| Prometheus / Metrics | `MicrometerChannelMetricsSink`：连接 up/down、发送耗时与错误、重复消息计数、turn 耗时与异常 |

`/actuator/health/liveness` **故意不含**渠道健康指示器：一条套接字被平台掐掉是服务降级，不是 JVM
坏了，重启进程会带着其他渠道一起下去。

`ChannelRuntimeEndpoint` 挂在 `/actuator` 而不是 `/api/channel` 下：本服务的 `UnifiedAuthFilter` 拦
`/api/**`，actuator 前缀豁免，排障时可以直接 `curl`，不需要先搞一个服务令牌。

## 7. 可靠性边界

| 关注点 | 实现 | 参数 |
|--------|------|------|
| at-least-once 容忍 | `MessageDeduplicator` 三段式：`tryBegin`（在途）→ `commit`（已处理）/ `rollback`（处理失败，允许重投再来一次）；在途集合有条数上限，超限时选择「可能重复处理」而不是「泄漏并永久屏蔽该 msgId」 | — |
| 并发上限 | `ChannelTurnExecutor`：固定线程池 → per-session 串行锁（1 个许可）→ per-channel `Semaphore`。**先拿会话锁再拿配额**：否则同会话的排队消息会一边等自己前一条、一边占着渠道配额，几条慢会话就能堵住该渠道的其他会话。一个许可只代表「真正在跑的活」 | `channel.turn.pool-size`=24、`channel.turn.per-channel-concurrency`=4 |
| 排队可见性 | 等待超过阈值的 turn 打一行 WARN，点名 channel / session 与实际等待时长。没有等待超时——那等于为了保住一个本该吸收流量的队列而丢掉用户的消息 | 阈值见 `ChannelTurnExecutor` 常量 |
| 会话锁回收 | 达到获取次数或时间间隔时扫描，空闲足够久且许可全空的条目被移除 | `ChannelTurnExecutor` 私有常量 |
| 长文本 | `TextChunker.splitByChars` / `splitByUtf8Bytes`：优先在换行处切，其次空格，都没有才硬切；空输入返回空列表 | 上限由各平台传入 |
| Router 不可用 | `RouterCircuitBreaker`（自研 CLOSED/OPEN/HALF_OPEN）：连续失败达阈值开闸，冷期间快速失败并回一条友好文案；半开状态只放行一次探测请求，探测请求丢失会重新武装 | `channel.router.breaker.*` |
| 超时 | 批量走 `RestClient`：连接 5 秒、响应取 `channel.proxy.response-timeout-ms`——本服务 `application.yml` 给的是 600000（600 秒），`ChannelConfig` 的 `@Value` 兜底是 120000（120 秒），该 yml 不在位时生效的是后者；流式走 `WebClient` + `Flux.timeout(stream-idle-timeout-ms)`（180 秒无事件即取消，0 关闭） | `channel.proxy.*` |
| 出站文件 URL 兜底 | 只允许 `http` / `https`，拒跟重定向（跟一次就绕过了 scheme 与地址检查）；解析后拒绝 loopback / any-local / link-local 地址，内网对象存储仍放行；连接 5 秒、读取 30 秒、上限 50 MB 且超限整条丢弃而不是截断 | `ChannelChatService` 私有常量 |
| 错误透出 | 流式 `ErrorStreamEvent` / 批量异常 → `ReplyMarkers.FAILED_PREFIX` + code + `requestId`（`req-xxxxxxxx`，与 `ApiCallLog` 对应） | — |
| 空回复 | 批量路径拿到空白内容时回 `ReplyMarkers.EMPTY_REPLY_PREFIX` 兜底文案；同一次 turn 有文件要投递时只发文件提示 | — |
| 回发本身失败 | `handleChatError()` 里的错误提示是尽力而为：再发一次失败只记一条带 requestId 的 ERROR，不把「送达失败」升级成「turn 失败」 | — |
| 不可读消息类型 | 传输层把平台消息类型交给 `ReplyMarkers.unsupportedMessageType(...)` 回一条提示并 `commit`（平台重投不会重复骚扰），而不是静默丢弃 | — |
| 回调入口 | 请求体上限 1 MB，超限 413（拒收而非截断——半个签名事件只会给出误导性的验签失败）；验签/解密交给平台官方 SDK；≥500 一律降级成 400 | `ChannelCallbackController` |

`ReplyMarkers` 是「管道自己生成的文案」的集中地，`isSyntheticReply()` 按前缀识别它们并排除在会话历史
之外——否则下一轮会把报错文本当成助手真说过的话喂回模型。当前前缀：`ROUTER_ERROR_PREFIX`、
`EMPTY_REPLY_PREFIX`、`FAILED_PREFIX`、`FILE_UNDELIVERABLE_PREFIX`、`FILE_SEND_FAILED_PREFIX`。

## 8. 文件投递

文件投递只有一条产品路径：**用户要拿文件走 WebUI**。渠道侧的投递只对声明了能力的平台生效。

Agent 产出的文件按会话类型分两种形态。渠道会话（`chn-` 前缀）的 `FileAttachment` 只带 `filePath`
（沙箱工作区路径）、`fileSize`、`mimeType`，`url` 与 `objectKey` 为空，也不上传对象存储——
`HarnessAgentWrapper.detectAndPersistOutputFiles()` 对 `chn-` 走这个分支，并且**不给渠道会话拼下载
链接**（`call()` 里 `attachments.isNotEmpty() && !sessionId.startsWith("chn-")` 才 append）：那个链接
指向渠道侧无法公开的工作区路径，写出来必然是死链。WebUI 会话照常持久化并附链接。

投递由 `ChannelChatService.deliverFileAttachments()` 统一负责——`batchSend()` 传
`ChatResponse.attachments`，`streamAndSend()` 传流式末端事件的 `attachments`，两条输出策略共用同一个
入口（`attachments` 为空时直接返回，所以两处都可以无条件调用）。取字节的两级策略：

1. **workspace 直下**：`workspaceFileDownloader` →
   `RouterClient.downloadWorkspaceFile(sessionId, filePath)` →
   `GET /api/router/agent/workspace/{sessionId}/download?path=...`。
2. **HTTP URL 兜底**：`attachment.url` 非空时按 `ChannelChatService` 的出站下载约束取字节——scheme 只允许
   `http` / `https`、拒跟重定向、解析后拒绝 loopback / any-local / link-local、连接 5 秒读取 30 秒、
   上限 50 MB。

能力门槛在取字节**之前**：`channelAdaptor.supportsFileDelivery()` 为 `false` 时，一轮只发一条
`ReplyMarkers.fileUndeliverable(fileNames)`，把文件名逐个列出来并指向 Web UI（一次动作对所有文件相同，
所以一条而不是每条）；为 `true`（只有微信）时逐个上传，单个文件失败退化成
`ReplyMarkers.fileSendFailed(fileName)`，不影响其余文件。`ChannelAdaptor.sendFile()` 的默认实现同样退化成
`fileUndeliverable` 文本，留给直接调用者兜底。

## 9. 命令与 HITL

### 9.1 斜杠命令

消息文本以 `/` 开头时由 `CommandAgentRequest.parse()` 解析为命令，走
`POST /api/router/agent/command`（批量，非流式）。关键字大小写不敏感，`args` 用空格或冒号分隔。
支持的命令类型：中断、清历史、`/approve`、`/deny`、停止沙箱、开关 `search` / `thinking` / `plan`、
设置权限模式、重建实例；`COMPACT` 回「未实现」。四个平台的 `MessageParser` 共用这套解析，
命令能力在平台间一致。

`/clear` 除了清 Agent 侧会话，`ChannelChatService.chat()` 还会调 `sessionManager.clearHistory()` 清掉
渠道侧该会话的内存缓存：这个缓存每个 turn 都会拼进 `AgentContext.history`，只清 Agent 侧的话下一条
消息就把「已清空」的对话原样喂回去。

### 9.2 工具确认（HITL）

渠道会话按 admin 配置的 `permissionMode` 走，与其它会话无异。两条闭环：

- **流式**：Agent 侧的确认事件 → `AgentStreamEvent.ToolConfirmStreamEvent` →
  `ChannelChatService.buildConfirmText()` 发一段纯文本清单，末尾是
  `ReplyMarkers.CONFIRM_FOOTER`（提示回 `/approve` 或 `/deny`）。
- **批量**：`HarnessAgentWrapper` 捕获到暂停后由 `buildConfirmPromptIfPaused()` 生成同一段清单
  （优先从持久化状态里的 `ASKING` 工具块还原完整 `ToolUseBlock`），随 `ChatResponse.content` 回给
  用户；用户回 `/approve` 或 `/deny` → `CommandType.APPROVE|DENY` → 组 `ConfirmAgentRequest` 调
  `confirm()`。

权限模式由 `channel.permission_mode` 决定（admin 的 `ChannelCreateRequest.permissionMode`，默认
`DEFAULT`），运行侧对 `chn-` 前缀不做任何提升或降级。`ChannelChatService` 的 `onPendingConfirm`
回调（用于跨进程持久化待确认状态）没有接入：批量路径的确认清单从 Agent 侧状态还原，不依赖渠道侧记账。

## 10. 管理面：CRUD、租户、凭据掩码与删除

管理端与运行时读同一张 `channel` 表，没有中间同步协议：admin 写行，运行侧在下一个对账周期看见。

### 10.1 读路径

`ChannelController` 的列表、详情、创建、更新、开关、删除都先过 `ChannelServiceImpl`。

- **租户判据。** `selectChannelList` 的 `tenant_id = #{tenantId}` 是服务端推出的谓词，不是调用方可选的
  过滤器；`getChannel(id)` 取回行后用 `it.tenantId == currentTenantId()` 二次判定，不匹配当「不存在」处理，
  因此别人的行与不存在的行在 HTTP 上不可区分。变更、开关、删除都经 `getChannel` 读，所以无凭据的
  id 既改不动也删不掉。租户解析链在 `TenantResolver`：验签过的 `X-Tenant-ID` 优先，其次调用方自己的
  租户，最后落到默认值——与 `createChannel` 写入的是同一个表达式，所以一行总是由有权写它的人读得到。
- **`channel.tenant_id` 的归属。** 一条渠道行的租户就是它 `agent_id` 所指 Agent 的租户：渠道存在的意义
  就是暴露一个 Agent，运行侧以那个 Agent 身份跑。基线 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 给
  这一列写的是 `bigint NOT NULL DEFAULT '1'`——每一行都带着一个租户，库里不存在无归属的渠道行。写入侧用的是
  `currentTenantId()`，没有 workspace 头的请求会落到 DDL 默认租户 1，而 `selectChannelList` 按这一列过滤，于是这类行会从
  自己创建者的列表里消失、停在租户 1 的列表中可见。`tenant_id` 与 `agent_id` 之间没有任何数据库约束，「租户跟着 Agent 走」
  因此是写入侧的判据而不是库内的保证：把一行的租户判成它所属 Agent 的租户，只能由解析链答出正确租户来达成，而带
  workspace 头的请求写下的租户本就是刻意取值，不该被改写。`creator` 列在这条判据里不可用（`createChannel` 从不写它，
  基线给这一列的缺省是字面量 `system`，于是每行都是这个值）。
- **`callbackKey` 不下发。** `ChannelResponse.callbackKey` 字段根本不存在。它是回调端点唯一校验的凭据，
  对外只给服务端派生的 `callbackUrl`，且只在 `communicationMode == "webhook"` 时给——`websocket` /
  `stream` / `long_polling` 的渠道主动拉消息，没有回调地址可言。
- **`configJson` 掩码。** `convertToResponse(channel)` 把 blob 交给 `maskSecrets()` 后再下发。
  掩码只作用于 `SECRET_CONFIG_KEYS = {appSecret, token, encodingAesKey, botToken, webhookUrl}` 里的字符串
  值，形态按长度分三档：`******`（≤4）、`首1****尾1`（≤8）、`首3****尾2`（其余），与
  `EnvVariableServiceImpl` 的掩码同形。列表接口与详情接口走同一个 `convertToResponse`，所以
  `/page` 也拿不到明文。

### 10.2 写路径

`keepStoredSecrets(incoming, stored)` 让掩码可回写：判定**不靠模式匹配**，而是拿这次提交的值与库里
现值逐一比对——把现值套上同一个 `maskSecret()` 后与提交值相等，即视为「未改动」，写入库里保持原值；
否则按用户输入写入。因此一个本身含星号的凭证仍然可编辑。创建路径没有可比对的库里现值，所以创建时送来的掩码
按字面入库（渠道随后明显跑不起来，好过悄悄留下一个没人输入过的值）。
`rewriteSecrets` 在没有任何键被改写时原样返回入参，所以一次没碰凭证的编辑不会重排 blob。

其余写侧不变量：

| 校验 | 规则 |
|------|------|
| `type` | 必须在 `MODES_BY_TYPE` 的键集合内；大小写敏感（运行侧 `ChannelType.fromCode` 精确匹配，`"DingTalk"` 会落库并在运行时报 unsupported） |
| `communicationMode` | 必须落在该类型的可运行集合内：`feishu → {websocket, webhook}`、`dingtalk → {stream}`、`wecom → {websocket}`、`wechat → {long_polling}`、`http → {webhook}`。未指定时取集合首项（各类型的推荐默认）。创建与「显式给了模式或改了类型」的更新都跑这道校验，所以把 feishu/webhook 改成 dingtalk 不会留下一个永远收不到消息的组合 |
| `wechat` 强制 | 个人微信只支持长轮询，更新时无论请求给什么模式都改写为 `long_polling` |
| `configJson` | 非空时必须是 JSON object，且不超过 20 000 字符（远低于 `TEXT` 列的 65 535 字节，多字节名不会把「字符合法」的值推成「字节非法」）。异常信息不回显载荷，因为它含密钥 |
| `status` | 只接受 0/1；别的值会被 `selectAutoStartChannels` 的 `status = 1` 永久过滤掉，而列表页也筛不出来 |

### 10.3 删除渠道：先释放运行侧，再软删行

`deleteChannel(id)` 的顺序是这套设计的重点：

```
deleteChannel(id)
  ├─ getChannel(id)            （租户不匹配 = 404）
  ├─ 若 sessionId 非空：SessionRuntimeReleaser.release(sessionId)
  │     └─ AgentRuntimeClient.clearSession(sessionId)
  │        失败 → 抛 BizException（i18n error.session.runtime.release + 运行侧原文），删除中止
  └─ channelMapper.deleteById(id)   → UPDATE channel SET active = 0
```

`chn-{uuid}` 会话在 `session` 表里没有行，运行侧是唯一存放处，而 admin 够不着它存的状态、计划与沙箱
容器——`clearSession` 是那扇门。运行侧答「释放不掉」就意味着状态会在一个再没有页面指向它的名字底下
继续活着，比一个可以稍后重试的失败更糟，所以这里把拒绝转成异常而不是返回一个可能被忘记的标志。
拒绝时什么都不写，运行时恢复应答后重试即可。这同一个不变量被三处删除共用：admin 页面删会话、
小程序删会话、删渠道。`release` 需要的参数是运行侧认得的 sessionId，绝不是行 id。

行本身是软删（`active = 0`），`selectAutoStartChannels` 的 `active = 1` 从此把它挡在自动启动集之外，
`reconcile()` 的第一分支就把监听器停掉——这是文档化行为。

## 11. 回调入口（webhook 模式，当前只有飞书）

`ChannelCallbackController` 挂在 `POST /api/channel/callback/{callbackKey}`，是 webhook 模式的入站半边，
也是本服务唯一注册了请求映射的控制器——渠道的增删改查全在 admin 那一侧，运行时只暴露这一个入口加
`/actuator` 下的观测端点。它刻意做薄：验签、解码与应答正文属于适配器，因为各平台契约的差异超过一个
控制器能吸收的范围（`ChannelAdaptor.handleCallback` 的签名就是这个约定）。

判定顺序与答案：

| 情况 | HTTP |
|------|------|
| `selectByCallbackKey` 查不到行 | 404 `unknown callback key` |
| 查询本身抛异常 | 500（不落容器页，回一句定型的 `channel lookup failed`） |
| 渠道 `enabled != 1` 或 `status != 1` | 403 `channel is disabled` |
| `configJson` 解析不出可用配置 | 400 `channel configuration is invalid` |
| 模式不是 `webhook`（忽略大小写比 `CallbackModes.WEBHOOK`） | 404 `channel does not use callback mode`——监听器拥有这条渠道的入站流量，回调会双份处理 |
| `adaptorRegistry` 取不到该类型的 adaptor（`get` 抛 `IllegalArgumentException`） | 404 `unsupported channel type`，并记 WARN `No adaptor registered for channel type {}`；`http` 渠道的回调落这一行 |
| 取到 adaptor 但 `supportsCallback()` 为 `false` | 501 `this channel type has no HTTP callback; use long-connection mode`，同一分支的 WARN 点名该类型没有回调契约、要 `switch it to the long-connection mode` |
| body 超过 1 MB | 413 |
| 适配器抛任何异常 | 400 |
| 适配器返回 ≥500 | 一律降级为 400 |

降级成 4xx 的理由：平台把 5xx 读成「没送到」并无限重投，而飞书 SDK 对一个解不开或签名不对的事件
正是回 500。探测者也不该能区分「这里没有渠道」和「有渠道但拒了我」。

**飞书 webhook 必须配 `encodingAesKey`。** 飞书在没有 Encrypt Key 时压根不发签名头，那就没有任何
东西可验证；而这个端点在 `harnax.auth.skip-paths` 的豁免清单里，所以 `FeishuAdaptor.handleCallback()` 直接 403
拒绝，而不是收下一条匿名事件。URL 验证挑战的回显与 AES 解密都由官方 `EventDispatcher` 完成，事件
解析与长连接模式共用同一个解析入口，两条入口不漂移。

回调是异步的：验签通过后立刻回 ack，agent turn 交给 `ChannelTurnExecutor`。平台的超时是几秒，
一个 turn 是几分钟。

## 12. 配置项

全部在 `harnax-channel/harnax-channel-service/src/main/resources/application.yml`，前缀 `channel`。

| 键 | 默认值 | 作用 |
|----|--------|------|
| `router-api-key` / `CHANNEL_API_KEY` | 空 | 渠道 → Router 的 API Key；为空时按下一项自动获取 |
| `auto-fetch-system-key` | `true` | 启动时向 admin 内部接口换取 SYSTEM Key |
| `sync.interval-ms` | 10000 | `channel` 表轮询与对账周期 |
| `sync.max-starts-per-cycle` | 5 | 单轮最多启动/重启多少个监听器，`<=0` 不限 |
| `sync.restart-backoff-initial-ms` | 2000 | 死监听首次重试延迟 |
| `sync.restart-backoff-max-ms` | 300000 | 退避上限，同时也是「曾算健康」的重置阈值 |
| `sync.stale-heartbeat-ms` | 180000 | `CONNECTED` 但心跳静默超过此时长即重启，0 关闭 |
| `lock.enabled` | `true` | 是否启用 MySQL `GET_LOCK` 单实例互斥 |
| `lock.name` | `harnax-channel-listeners` | 锁名 |
| `turn.pool-size` | 24 | 入站消息处理线程数 |
| `turn.per-channel-concurrency` | 4 | 单渠道最大并发会话数 |
| `session.max-sessions` | 10000 | 内存中保留的会话数（LRU 淘汰） |
| `session.max-messages` | 500 | 单会话保留的消息条数 |
| `session.idle-ttl-minutes` | 120 | 空闲会话丢弃周期，0 关闭 |
| `monitor.health.enabled` | `true` | 是否注册连接健康指示器 |
| `monitor.health.failure-grace-ms` | 60000 | `FAILED` 持续多久后健康转 `DOWN` |
| `proxy.connect-timeout-ms` | 5000 | Router 连接超时 |
| `proxy.response-timeout-ms` | `application.yml` 给 600000；`ChannelConfig` 的 `@Value` 兜底 120000 | 批量响应超时：随本服务 yml 运行是 10 分钟，该 yml 不在位时按兜底值 2 分钟 |
| `proxy.stream-idle-timeout-ms` | 180000 | SSE 空闲超时，0 关闭 |
| `router.breaker.enabled` | `true` | 是否启用熔断 |
| `router.breaker.failure-threshold` | 5 | 连续失败多少个请求后开闸 |
| `router.breaker.open-duration-ms` | 30000 | 开闸多久后转半开探测 |
| `admin.url` / `ADMIN_SERVICE_URL` | `http://localhost:8080` | 换取 SYSTEM Key 的对端 |

`router.service.url` / `ROUTER_URL` 不在 `channel` 前缀下，是渠道 → router 的基址。

服务自身：端口 8083，`server.shutdown: graceful`，`spring.lifecycle.timeout-per-shutdown-phase: 20s`。
数据源指向 `harnax_admin` 库，`spring.flyway.enabled: false`（迁移由 admin 管）；MyBatis
`classpath*:mapper/*.xml` + 下划线转驼峰，读的是 `harnax-entity` 的 `ChannelMapper`。
`management.endpoints.web.exposure.include: health,info,prometheus,metrics,channels`，
`probes.enabled: true`。

认证相关两项在 `harnax.auth` 前缀下：

| 键 | 值 | 作用 |
|----|----|------|
| `harnax.auth.enabled` | `true` | 统一内部认证打开；关掉它等于把 `/api/**` 全部公开，不只是回调 |
| `harnax.auth.service-id` | `channel-<n>`（`SERVICE_ID`，默认 `channel-0`） | 本服务签发出的内部令牌上的标签 |
| `harnax.auth.skip-paths` | `/api/channel/callback` | 平台无法出示服务令牌，回调前缀必须豁免；认证就落在平台签名上 |
| `harnax.auth.internal.shared-secret` | `HARNAX_AUTH_SECRET` | 内部令牌 HMAC 密钥，须与 admin 同值 |

启动时 `ChannelApiKeyInitializer` 在 `router-api-key` 为空且 `auto-fetch-system-key` 为真时向 admin
内部接口换取 SYSTEM Key：连接 3 秒、读取 5 秒、最多 3 次（间隔 1 秒与 2 秒）。admin 与
channel-service 在 compose 里一起起来，admin 短暂不可达是常态；换不到 Key 时抛出的异常带上尝试次数。

## 13. 部署与网络

- 镜像构建：`harnax-deploy/Dockerfile.channel-service`；compose 服务名 `channel-service`，容器内监听 8083，
  `harnax-deploy/docker-compose.yml` 的发布映射是 `28083:8083`——宿主机上访问 28083，容器之间与 nginx
  上游仍用 8083。
- `harnax-deploy/nginx.conf` 的 `location /api/channel/` 转发到 `channel-service:8083`，公网回调地址因此
  可用；回调路径命中的是这一条。同一份配置里还有一条更长的 `location /api/channel/webhook/`（长超时 +
  关缓冲），而服务侧没有任何映射落在 `/api/channel/webhook/` 下，它接不到流量。
- admin 创建渠道时下发的 `callbackUrl = $baseUrl/api/channel/callback/{callbackKey}` 由
  `ChannelCallbackController` 承接，**只有飞书有对应的 HTTP 契约**（`supportsCallback()` 只对飞书返回
  `true`）。钉钉、企微、微信的机器人形态没有等价的事件回调，给它们补一个会接收未验签请求的空端点
  比不补更糟。
- 长连接模式只需要**出方向**网络可达，不需要公网入口和回调地址，仍是推荐的接入方式；只有在无法
  放开出方向网络时才用飞书 webhook。
- 「类型 → 可用模式」这张表在三处各有一份：前端 `harnax-webui/src/pages/channel/components/channelModes.ts`
  （下拉按类型过滤）、admin `ChannelServiceImpl.MODES_BY_TYPE`（写入校验）、
  运行侧各 adaptor 的 `supportsCallback()` 与实际传输（`ChannelBootstrapRunner.startChannel` 在
  webhook 但无契约时记启动失败）。三者语言不同、没有共享模块，改一处要另两处跟上；只有运行侧能在
  读的时候真正检查。
- 渠道服务不持有对象存储凭证：文件一律经 router 的工作区下载接口取。

## 14. 明确不做与边界

| 边界 | 现状 |
|------|------|
| 渠道侧文件上传接口 | 不接。飞书 / 钉钉 / 企微的 `supportsFileDelivery()` 为 `false`，用户在界面拿文件走 WebUI。要接的话 `supportsFileDelivery()` 与 `sendFile()` 一起改 |
| 按用户拆分渠道会话 | 不做。一条 channel 记录一段记忆，需要隔离就多建记录 |
| 流式输出 | 协议与实现都在，四个平台都把 `supportsStreamingOutput()` 保持 `false`；要用起来得先确定由哪个平台的哪个 API 承载增量 |
| 企微主回发路径分块 | `aibot_respond_msg` 用的是 stream 全量替换语义，2000 这个上限是否适用于它无法从代码判定；分块发送需要连续多帧 + 末帧 finish，属协议行为。现状是超限时平台回 errcode，并记 WARN 与失败指标 |
| `COMPACT` 命令 | 回「未实现」 |
| 回调模式的探活 | webhook 渠道没有连接可探，`reconcileRunning` 直接清掉退避状态；「没有监听器」不算 `missingListener` |
| 渠道运行状态在 WebUI 的可见性 | admin 不代理 `/actuator/channels`，也不轮询。列表里的状态列是 DB 的 `status`（配置意图），不是连接事实；连接事实在 actuator 与健康指示器上 |
| 微信登录态 | 由 admin 的微信扫码登录写入 `configJson`，渠道侧只读；类型改成别的平台时那些键原样留着，不属于托管凭证键 |
| `http` 渠道类型 | 保留在类型集合里，没有 adaptor。admin 的 `MODES_BY_TYPE` 只给它 `webhook`，模式校验因此放行、创建响应还带 `callbackUrl`；启动落 `ChannelBootstrapRunner` 的 webhook 分支，`lastError` 记 `webhook callback mode is not implemented for http; use this platform's long-connection mode`，而这句提示给出的长连接模式对 `http` 没有合法取值可切。回调入口按类型取不到 adaptor，回 404 `unsupported channel type` |

## 15. 排障速查

| 症状 | 先看 | 结论怎么读 |
|------|------|-----------|
| 发消息给机器人完全没反应 | `/actuator/channels` 的 `receivedCount` 与 `lastActivityAt` | 一直是 0 而状态 `CONNECTED` → 消息没进处理器，查该渠道 `lastError`；有增长但没回复 → 往下看 router 侧 |
| 状态 `CONNECTED` 但不回话 | debug 日志里「过期监听器」字样，再看 `stop()` 时的事件计数 | 计数为 0 就是收得到连接、收不到消息，属归属守卫一类问题 |
| 渠道一直 `FAILED` 且 `lastError` 写着 `webhook callback mode is not implemented for <type>; use this platform's long-connection mode` | 该渠道的 `communicationMode` 与该类型的合法模式 | 钉钉 / 企微 / 微信按提示换模式（分别是 `stream` / `websocket` / `long_polling`）；`http` 的 `MODES_BY_TYPE` 只有 `webhook` 一项，没有可切的长连接模式，这句提示对它给不出出路 |
| 飞书 webhook 403 | 该渠道有没有配 `encodingAesKey` | 没有 Encrypt Key 就没有可验证的签名，端点拒绝而不是收匿名事件 |
| 飞书 webhook 配好了但不进对话 | 平台侧 URL 验证是否通过，`/api/channel/callback/{callbackKey}` 是否可达 | 验证不通过通常是被 `UnifiedAuthFilter` 拦了（`skip-paths` 没带上这个前缀）或 nginx 路由没指过来 |
| 回调返回 404 但渠道确实存在 | 该渠道的 `communicationMode`，再看类型 | 非 webhook 的渠道走的是监听器，回 `channel does not use callback mode`；webhook 渠道但该类型没有注册 adaptor（`http`）回 `unsupported channel type` |
| 所有渠道同时报错 | `/actuator/channels` 的 router 熔断快照 | 熔断开闸，看 router 是否重启或网络分区 |
| 只有某个副本在收消息，另一个空转 | `listenerLock.held` | 正常：`GET_LOCK` 互斥，非持有副本空转 |
| 只有部分渠道在跑，日志说锁被释放 | 两个副本是否连了不同 MySQL 实例 / 连接池是否复用同一连接 | 锁活在会话上，换连接等于丢锁 |
| 渠道每隔一阵重启一次 | `sync.stale-heartbeat-ms` 与该渠道的 `statusAgeMs` | 半死判定生效。若重启间隔等于对账间隔，说明退避没有长起来——查 `lastError` 是否每轮都新 |
| 回复被截断 | 消息长度与平台分块上限 | 各传输路径都分块；仍截断说明平台侧对单条消息另有更小的限制 |
| 生成的文件收不到 | 日志 `[Files]` 前缀 | 飞书 / 钉钉 / 企微是预期行为（能力位 `false`，提示里写了去 Web UI 取）；微信看文件内容解析失败的日志 |
| 部署后渠道掉几分钟 | 是否执行了 `@PreDestroy` 优雅停机 | 看「Stopping N channel listener(s)」这行有没有出现 |
| 危险工具没确认就执行了 | 该渠道在 admin 上配的权限模式 | 运行侧不按 `chn-` 前缀改变权限模式；若仍如此，查 `permissionMode` 与 ASK 规则本身是否配了 |
| 改了渠道没生效 | `configFingerprint` 覆盖的八个字段是否真的变了 | 只改 `description` 不会触发重启，这是刻意的 |
| 删渠道报「运行侧无法释放」 | admin 到 agent-service 的 `clearSession` 调用是否可达 | 拒绝即中止删除，库里那一行没动，可重试 |

## 16. 关键文件索引

| 关注点 | 文件 |
|--------|------|
| 能力位与回调契约 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelAdaptor.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelCallbackResult.kt` |
| 传输模式抽象 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelCommunicationMode.kt` |
| 类型与配置模型 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/config/ChannelType.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/config/ChannelSpec.kt` |
| 对账与生命周期 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelBootstrapRunner.kt` |
| 单实例互斥 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelListenerLockGuard.kt` |
| 平台回调入口 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/endpoint/ChannelCallbackController.kt` |
| Router 调用与熔断 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterClient.kt`、`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterCircuitBreaker.kt` |
| 平台 → Agent 编排 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ChannelChatService.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ReplyMarkers.kt` |
| Agent 侧适配与接线 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/adaptor/RouterAgentAdaptor.kt`、`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelConfig.kt` |
| turn 限流 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/dispatch/ChannelTurnExecutor.kt` |
| 幂等 / 分块 / 退避 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/MessageDeduplicator.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/TextChunker.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/ReconnectBackoff.kt` |
| 状态机与指标 | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/ChannelConnectionState.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/ChannelConnectionTracker.kt`、`harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/MicrometerChannelMetricsSink.kt` |
| 运行视图与健康 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/monitor/ChannelRuntimeEndpoint.kt`、`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/monitor/ChannelRuntimeMonitor.kt`、`harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/health/ChannelConnectionHealthIndicator.kt` |
| 飞书传输与回调 | `harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWebSocketMode.kt`、`harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWsTransport.kt`、`harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuAdaptor.kt` |
| 钉钉传输 | `harnax-channel/harnax-channel-dingtalk/src/main/kotlin/com/agnetix/harnax/channel/dingtalk/DingtalkStreamMode.kt` |
| 企微传输与帧协议 | `harnax-channel/harnax-channel-wecom/src/main/kotlin/com/agnetix/harnax/channel/wecom/WecomWebSocketMode.kt`、`harnax-channel/harnax-channel-wecom/src/main/kotlin/com/agnetix/harnax/channel/wecom/WecomFrames.kt` |
| 微信传输 | `harnax-channel/harnax-channel-wechat/src/main/kotlin/com/agnetix/harnax/channel/wechat/WechatLongPollingMode.kt`、`harnax-channel/harnax-channel-wechat/src/main/kotlin/com/agnetix/harnax/channel/wechat/WechatAdaptor.kt` |
| 实体 → Spec | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/mapper/ChannelEntityConverter.kt` |
| 渠道会话缓存 | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/session/InMemoryChannelSessionManager.kt` |
| 管理面规则 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt` |
| `channel` 表语句 | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`（`channel` 的建表语句与 `tenant_id` 列都写在这一个基线里）、`harnax-entity/src/main/resources/mapper/ChannelMapper.xml`、`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Channel.kt` |
| 删除时的运行侧释放 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionRuntimeReleaser.kt` |
| 渠道会话的 MCP 身份 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| 渠道会话的附件形态 | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt` |
| 前端模式过滤 | `harnax-webui/src/pages/channel/components/channelModes.ts` |
| 归属可判定性（router 侧） | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt`、`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionAccessGuard.kt` |
| 部署 | `harnax-deploy/Dockerfile.channel-service`、`harnax-deploy/docker-compose.yml`、`harnax-deploy/nginx.conf` |
