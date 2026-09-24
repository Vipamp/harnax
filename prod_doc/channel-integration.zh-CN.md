# Harnax 渠道集成（Channel）

> 本文是 `harnax-channel` 的**现行行为说明**：支持哪些平台、消息怎么进怎么出、会话怎么算、
> 连接怎么保活、出问题去哪儿看。面向接入渠道的开发者与排障的运维。
>
> 相关的历史文档以本文为准：
> [docs/channel-to-agent-flow.md](../docs/channel-to-agent-flow.md)（时序细节，其中 `ReActAgentAdaptor`
> 等类名已过时）、[harnax-channel/arch.md](../harnax-channel/arch.md)（早期分层稿）、
> [channel-hot-reload-design.md](../harnax-channel/harnax-channel-service/docs/channel-hot-reload-design.md)
> （热更新方案稿，开头已列出与实现的四处差异）。
>
> 本文第 12 节记录了 2026-09 渠道层审查后的修复清单与仍然存在的限制，改动渠道层之前请先读那一节。

## 1. 定位与模块划分

渠道层负责把外部 IM（飞书 / 微信 / 企微 / 钉钉）接成 Agent 的对话入口：平台侧的收发消息由
渠道层承担，Agent 的推理与会话由 `harnax-session-router` + `harnax-agent-service` 承担，
两者之间只走 HTTP。

| 模块 | 职责 | 依赖 |
|------|------|------|
| `harnax-channel-sdk` | 平台无关抽象：`ChannelAdaptor`、`AgentAdaptor`、`ChannelChatService`、`ChannelCommunicationMode`、消息模型、去重、分块、退避、连接状态、`ChannelTurnExecutor` | 仅 Kotlin 协程 + `harnax-protocol`，不依赖 Spring |
| `harnax-channel-service` | 可运行服务（端口 8083）：`ChannelBootstrapRunner` 对账循环、监听互斥锁、`RouterClient`、熔断、健康指示、`/actuator/channels`、API Key 初始化 | Spring Boot + MyBatis（读 `harnax_admin` 库的 `channel` 表） |
| `harnax-channel-feishu` | 飞书适配器 + `FeishuWebSocketMode`（官方 `oapi-sdk` 长连接） | 飞书开放平台 |
| `harnax-channel-wecom` | 企微适配器 + `WecomWebSocketMode`（自研 OkHttp WebSocket 帧协议） | 企微智能机器人 |
| `harnax-channel-wechat` | 微信适配器 + `WechatLongPollingMode`（iLink 长轮询）+ 唯一实现了 `sendFile` 的适配器 | 微信 iLink |
| `harnax-channel-dingtalk` | 钉钉适配器 + `DingtalkStreamMode`（官方 `app-stream-client`） | 钉钉开放平台 |

分发只看 `ChannelType`：`ChannelAdaptorRegistry` 把所有 `ChannelAdaptor` Bean 按 `getType()` 建索引，
`communicationMode` 只用来描述传输方式并判断「webhook 模式免探活」，不参与选路。新增一个平台
只需要注册一个新 adaptor Bean。

## 2. 能力矩阵

| 平台 | 传输模式 | 入站 | 回发 | 消息分块 | 幂等去重 | 文件下发 | 流式输出 |
|------|----------|------|------|----------|----------|----------|----------|
| 飞书 | `websocket`（官方 SDK） | 事件订阅 | `im/v1/messages` | 20 000 UTF-8 字节 | ✅ `msgId` | ❌ | ❌ |
| 飞书 | `webhook` | `POST /api/channel/callback/{callbackKey}` | `im/v1/messages` | 同上 | ✅ `messageId` | ❌ | ❌ |
| 钉钉 | `stream`（官方 SDK） | BOT_MESSAGE_TOPIC | `sessionWebhook` → 机器人 OpenAPI 兜底 | 18 000 字符（两条路径都逐条发） | ✅ `msgId` | ❌ | ❌ |
| 企微 | `websocket`（自研帧协议） | `aibot_*` 帧 | `aibot_respond_msg`（一次性）→ `aibot_send_msg` 兜底 | 2 000 字符（兜底路径） | ✅ `msgid` | ❌ | ❌ |
| 微信 | `long-polling`（iLink） | 长轮询 | iLink API | 4 000 字符 | ✅ `message_id` | ✅ | ❌ |

四个长连接平台的 `supportsStreamingOutput()` 全部返回 `false`，所以
`shouldUseStreaming() = supportsStreamingOutput() && agentAdaptor.supportsStreaming()` 恒为
`false`——**当前所有渠道都走批量输出**（`batchSend()`），AI 处理期间只发一个「正在输入」指示。
流式分支的协议与实现是通的（`EndStreamEvent` 现在也带 `attachments`），只是没有平台选中它。

`webhook` 模式只有飞书有对应的 HTTP 契约，见第 11 节。钉钉（Stream）、企微（帧协议）、微信（长轮询）
的机器人形态本身没有等价的事件回调：`ChannelAdaptor.supportsCallback()` 对它们返回 `false`，
对账循环会把配成 webhook 的这类渠道直接标成启动失败并说明原因，而不是静静收不到消息。

文件下发由 `ChannelAdaptor.supportsFileDelivery()` 声明：只有微信返回 `true`。其余三个平台在
**取文件字节之前**就被拦下，一轮只发一条点名所有文件的降级提示——为一个没人能收的上传去拉几十 MB
只会把 turn 拖到超时。

## 3. 端到端链路

```
平台推送 / 轮询 / HTTP 回调
  └─ FeishuWebSocketMode.handle / DingtalkStreamMode.handleBotMessage
     / WecomWebSocketMode.handleRawFrame / WechatLongPollingMode
     / ChannelCallbackController → FeishuAdaptor.handleCallback（飞书 webhook）
        │ ①归属守卫：listeners[channelId] 是否还是当前 holder
        │ ②去重：MessageDeduplicator.tryBegin(msgId)
        │ ③解析：parseXxxMessage → ChannelMessage（平台会话 id 作 sessionId）
        │ ④缓存回发坐标（钉钉 sessionWebhook / 企微回调 req_id / 主动发送目标）
        ▼
     ChannelTurnExecutor.launchTurn(channelId, platformSessionId)
        │ 固定线程池 24 → per-session 串行锁 1 → per-channel Semaphore 4
        ▼
     Adaptor 内的 handler（AgentAdaptor + SessionManager + ChatService 三件套由 adaptor 自己接线）
        messageParser.parse(message).withSessionId(channel.sessionId)
        ▼
     ChannelChatService.chat(message, channelSpec, agentAdaptor, channelAdaptor, agentRequest)
        │ 读历史 → 存用户消息 → 组 AgentContext → 选输出策略 → /clear 时清渠道侧缓存
        ▼
     RouterAgentAdaptor.process(context)
        ▼
     RouterClient（RestClient / WebClient + RouterCircuitBreaker）
        │ POST /api/router/agent/chat      （批量，恒走这条）
        │ POST /api/router/agent/chat/stream（流式，当前不会被选中）
        │ POST /api/router/agent/command    （斜杠命令）
        ▼
     harnax-session-router → 按 sessionId 选实例 → harnax-agent-service
        ▼
     ChatResponse{content, attachments} / Flux<ChatEvent>（末端 EndEventChatEvent 带 attachments）
        │ 批量回发：channelAdaptor.sendMessage()（内部按平台上限分块）
        │ 文件投递：deliverFileAttachments()——批量与流式共用这一个入口
        └ 保存 assistant 消息到渠道侧会话
```

一次消息处理叫一个 **turn**。turn 内部任何异常都被 `ChannelTurnExecutor` 捕获并记日志，
不会冒泡回平台的接收线程。

## 4. 会话与身份模型

这是渠道层最容易读错的地方，单独说明。

- **一条 `channel` 记录 = 一个 Agent 会话。** `channel.session_id`（形如 `chn-<uuid>`）由 admin
  在创建渠道时生成，是 Agent 侧唯一的会话标识。适配器在把请求交给 `ChannelChatService` 之前，
  一律用 `withSessionId(channel.sessionId)` **覆盖**解析出来的 sessionId。
- **平台会话 id（`chat_id` / `conversationId` / `openChatId` / `from_user_id`）只用于两件事：**
  回发消息时定位收件人，以及 `ChannelTurnExecutor` 的 per-session 串行锁 key
  （`channelId:sessionId`）。它不代表 Agent 侧的会话。
- 因此：**同一个机器人下，所有用户、所有群共享同一段 Agent 记忆。** 需要按人隔离会话时，
  必须一个用户一条 channel 记录。
- `harnax-harness-core` 用 `sessionId.startsWith("chn-")` 区分渠道会话与 WebUI 会话，只剩一处行为
  分叉：渠道会话的文件附件只回 `filePath`（不上传 MinIO），由渠道层从沙箱工作区直取。权限模式
  不再按前缀分叉，`chn-` 会话按 admin 配置的 `permissionMode` 走（见 9.2）。

渠道侧另有一份内存会话缓存 `InMemoryChannelSessionManager`（key = `channelId` + 平台会话 id），
只用于拼 `AgentContext.history`。当前 `RouterAgentAdaptor` **不读** `history`——Agent 侧的记忆由
`chn-` 会话自己维护，渠道这份缓存写了没人用。

## 5. 监听器生命周期与对账

`ChannelBootstrapRunner` 是唯一入口，`@Scheduled(fixedDelayString = "${channel.sync.interval-ms}")`
驱动一轮 `reconcile()`（默认 10 秒，`fixedDelay` 保证不与上一轮重叠）：

| 分支 | 条件 | 动作 |
|------|------|------|
| 移除 | 内存里在跑，DB 里已删/禁用/不在自动启动集 | `stopChannel()`，取消跟踪并清理指标与退避 |
| 新增 | DB 里有，内存里没有（按 id 升序） | `startChannel()`，受每轮启动预算限制 |
| 配置漂移 | `configFingerprint()` 变化 | 立即重启（视为人为操作，不等退避） |
| 启动失败 | `startFailed` 且 `retryDue()` | 重启 |
| 半死 | `CONNECTED` 但心跳静默超过 `stale-heartbeat-ms` | 重启，走同一道退避门限；重启后不把这段「看似健康」的时长计作健康寿命 |
| 存活 | `isServing()` | 重置退避，什么都不做 |
| 探活失败 | 状态非 serving 且 `retryDue()` | 重启 |
| 回调模式 | `communicationMode=webhook` | 不建监听；平台无 HTTP 契约时（钉钉 / 企微 / 微信）直接记为启动失败 |

关键设计点：

- **`configFingerprint()`** 覆盖 `type / communicationMode / agentId / enabled / status / sessionId /
  callbackKey / configJson`。不用 `update_time`：MySQL `DATETIME` 只到秒，同秒内的两次编辑区分不了，
  而改名这类展示字段变更会白白踢掉一条健康的长连接。
- **启动预算** `channel.sync.max-starts-per-cycle`（默认 5，`<=0` 表示不限）：下游拥塞时避免整轮
  对账都在重建连接。按 id 升序消耗，保证多副本和日志收敛到同一个渠道。
- **指数退避** `ReconnectBackoff`：`restart-backoff-initial-ms`（2 秒）起步，上限
  `restart-backoff-max-ms`（300 秒）；`retryDue()` 用当前退避步长作门限，连接稳定持有超过退避
  上限后重置回快速重试。
- **单实例互斥** `ChannelListenerLockGuard` 用 MySQL `GET_LOCK`（`channel.lock.name`）保证只有一
  个副本持有长连接。丢锁的副本在 `reconcile()` 开头会 `stopAll()`。同一份凭证开两个监听的结果
  是消息被消费两次、会话历史分裂在两份内存里。失败策略是**刻意不对称**的：已经证得过所有权的
  连接遇到 DB 抖动时继续按 owner 服务（同一场抖动也让对账查询失败，循环自己就会静下来；判死会
  把整个部署的 WebSocket 全踢一遍）；从未证得过所有权的新连接一律返回 `false`，因为在这种位置
  假定「锁是我的」正是双监听的来源——何况 MySQL 不通时对账本来就取不到渠道清单。
- **优雅停机** `@PreDestroy`：等待 `reconcileLock` → 逐个停监听 → `adaptor.shutdown()` 释放传输层
  → 释放 MySQL 锁。`spring.lifecycle.timeout-per-shutdown-phase` 给 20 秒预算。不这么做的话，
  进程退出时平台侧套接字还开着，平台继续往死连接里推，重启后的进程要等那个陈旧会话被回收才能
  重新监听——用户看到的就是「部署完渠道掉了两分钟」。

### 5.1 各传输模式的保活手段

| 模式 | 谁持有连接 | 谁判活 |
|------|------------|--------|
| 飞书 `websocket` | 官方 `WsClient`（内部 `pingLoop` 线程） | 首条入站事件是唯一正向证据，`markConnected` 只在 `handle()` 里调；`start()` 干净返回**不**代表任何状态 |
| 钉钉 `stream` | 官方 SDK 的调度线程池 | 同上，`markConnected` 只在 `handleBotMessage()` 里调 |
| 企微 `websocket` | 自研 OkHttp WebSocket，带 `generation` 计数 | `aibot_subscribe` 成功应答即 `markConnected`；30 秒 ping + 错过 2 次 pong 判死，`isCurrent(gen)` 丢弃被淘汰套接字的回调 |
| 微信 `long-polling` | 自建守护线程轮询 | 轮询启动成功即 `CONNECTED`，异常由轮询循环自己重试 |

四个官方/自研 SDK 的 `start()` 都**不阻塞**：飞书走 `newWebSocket(...)` 异步握手后把 `pingLoop`
丢进线程池，钉钉是 `scheduleAtFixedRate(new ConnectionTask(), 0, ...)`，企微的 OkHttp
`newWebSocket` 立即返回。由此定下两条不变式：

1. holder 只在 `stop()` 里退休。在 `runConnection()` 返回时摘除 holder 会让归属守卫从此恒真，
   而套接字还活着——这正是曾经的 P0-1（见第 12 节）。
2. 判活不靠「`start()` 返回得快不快」这类推断，只靠正向信号（首条消息 / 订阅应答 / 心跳）。

飞书与钉钉的 `stop()` 会打一行 `served N event(s) over Mms`：一条被平台掐掉、靠 SDK 自己重连撑了几
小时没收到任何消息的连接，在这行日志里是 `served 0 event(s)`，而监控上它是 `CONNECTED`。

## 6. 连接状态机与可观测

`ChannelConnectionStatus`：`UNKNOWN → CONNECTING → CONNECTED`，异常进 `RECONNECTING` / `FAILED`，
主动停进 `STOPPED`。`isServing()` 把 `CONNECTED`、`CONNECTING`、`RECONNECTING` 都算作在服务。

每个适配器持有一个 `ChannelConnectionTracker`（绑定平台 code），是状态的唯一写入方；
`ChannelRuntimeMonitor` 汇总成运行视图。三个暴露口：

| 出口 | 内容 |
|------|------|
| `/actuator/channels` | 监听锁是否持有、汇总计数、按状态分布、router 熔断快照、每条渠道的 `status` / `serving` / `missingListener` / `statusAgeMs` / `lastConnectedAt` / `lastActivityAt` / `reconnectCount` / `receivedCount` / `lastError` |
| `/actuator/health` | `channelConnections` 指示器：全部在服务 → `UP`；有非 serving 但未硬失败 → `OUT_OF_SERVICE`；有 `FAILED` 持续超过 `failure-grace-ms`（默认 60 秒）→ `DOWN` |
| Prometheus / Metrics | `MicrometerChannelMetricsSink`：连接 up/down、发送耗时与错误、重复消息计数等 |

`/actuator/health/liveness` **故意不含**渠道健康：一条飞书套接字被平台掐掉是服务降级，不是 JVM
坏了，重启进程会带着其他渠道一起下去。

`ChannelRuntimeEndpoint` 挂在 `/actuator` 而不是 `/api/channel` 下，是因为本服务的
`UnifiedAuthFilter` 会拦 `/api/**`，而 actuator 前缀是豁免的——排障时可以直接 `curl`，不用先搞一个
服务令牌。

## 7. 可靠性边界

| 关注点 | 实现 | 参数 |
|--------|------|------|
| at-least-once 容忍 | `MessageDeduplicator` 三段式：`tryBegin`（在途）→ `commit`（已处理）/ `rollback`（处理失败，允许重投再来一次） | — |
| 并发上限 | `ChannelTurnExecutor`：固定池 → per-session 串行锁（1 个许可）→ per-channel `Semaphore`。**先拿会话锁再拿配额**：否则同会话的排队消息会一边等自己前一条、一边占着渠道配额，几条慢会话就能把整个渠道的其他会话堵住 | `channel.turn.pool-size`=24、`per-channel-concurrency`=4 |
| 会话锁回收 | 每 512 次获取或每 60 秒扫一次，空闲超过 10 分钟且许可全空的条目被移除 | — |
| 长文本 | `TextChunker.splitByChars` / `splitByUtf8Bytes`：优先在换行处切，其次空格，都没有才硬切 | 各平台上限见第 2 节 |
| Router 不可用 | `RouterCircuitBreaker`（自研 CLOSED/OPEN/HALF_OPEN）：连续失败达到阈值开闸，冷期间快速失败并回一条友好文案；半开只放一个探针，探针丢失会重新武装 | `channel.router.breaker.*` |
| 超时 | 批量走 `RestClient`：连接 5 秒、响应 600 秒（10 分钟，覆盖长任务）；流式走 `WebClient` + `Flux.timeout(stream-idle-timeout-ms)`（180 秒无事件即取消，0 关闭） | `channel.proxy.*` |
| 出站文件 URL 兜底 | 只允许 `http` / `https`，拒跟重定向（跟一次就绕过了 scheme 与地址检查），解析后拒绝 loopback / any-local / link-local 地址（元数据端点、本机管理端口），内网对象存储仍然放行；连接 5 秒、读取 30 秒、上限 50 MB 且**超限即整条丢弃**而不是截断 | `ChannelChatService` 常量 |
| 入站图片 | 飞书图片按魔数嗅探类型（旧实现一律标 `image/jpeg`，PNG 到模型侧就是坏图），单图上限 5 MB 再 base64 | `FeishuWebSocketMode` 常量 |
| 错误透出 | `ErrorStreamEvent` / `onError()` → `ReplyMarkers.FAILED_PREFIX` + code + `requestId`（`req-xxxxxxxx`，与 `ApiCallLog` 对应） | — |
| 空回复 | 批量路径拿到空白内容时回兜底文案而不是静默吞掉；但若本轮有文件要投递，就只发文件提示，不再发「没有内容」 | `ReplyMarkers.EMPTY_REPLY_PREFIX` |
| 回调入口 | 请求体上限 1 MB（超限直接拒），验签/解密交给平台官方 SDK，未知 key 与不可验证的事件分别 404 / 4xx | `ChannelCallbackController` |

## 8. 文件交付

Agent 产出的文件有两条通路，取决于会话类型。渠道会话（`chn-` 前缀）的 `FileAttachment` 只带
`filePath`（`/workspace/output/...`）、`url` 与 `objectKey` 为空——不上传 MinIO，因为渠道服务不再持有
对象存储凭证（`a039a95` 移除内网直读的代价就是必须经 router 取）。也因此
`HarnessAgentWrapper` **不给渠道会话拼下载链接**：那个链接指向渠道侧无法公开的工作区路径，
写出来必然是死链。WebUI 会话则照常持久化并附链接。

投递由 `ChannelChatService.deliverFileAttachments()` 统一负责——`batchSend()` 传
`ChatResponse.attachments`，`streamAndSend()` 传 `EndStreamEvent.attachments`，两条输出策略共用
同一个入口（`attachments` 为空时直接返回，所以两处都可以无条件调用）。取字节的两级策略：

1. **workspace 直下**：`workspaceFileDownloader` →
   `RouterClient.downloadWorkspaceFile(sessionId, filePath)` →
   `GET /api/router/agent/workspace/{sessionId}/download?path=...`（router 侧上限 50 MB）。
2. **HTTP URL 兜底**：`attachment.url` 非空时按第 7 节的约束下载。

能力门槛在取字节**之前**：`channelAdaptor.supportsFileDelivery()` 为 `false` 时，一轮只发一条
`ReplyMarkers.fileUndeliverable(...)`，把文件名逐个列出来并指向 Web UI；为 `true`（只有微信）时
才逐个上传，单个文件失败退化成 `ReplyMarkers.fileSendFailed(...)`，不影响其余文件。

> 这是产品口径而非技术妥协：飞书 / 钉钉 / 企微的文件上传接口本服务没有接，用户要拿文件就走 Web UI。
> 平台侧要补的话，`supportsFileDelivery()` 与 `sendFile()` 一起改。

`ReplyMarkers` 里的那几个前缀同时是「管道自己生成的文案」标记，`isSyntheticReply()` 会把这些提示
排除在会话历史之外——否则下一轮会把报错文本当成助手真说过的话喂回模型。

## 9. 命令与 HITL

### 9.1 斜杠命令

消息文本以 `/` 开头时，`CommandAgentRequest.parse()` 解析为命令，走
`POST /api/router/agent/command`（批量，非流式）。关键字（大小写不敏感，`args` 用空格或冒号分隔）：

| 命令 | 别名 | 渠道侧效果 |
|------|------|-----------|
| `INTERRUPT` | `/interrupt`、`/stop` | 中断进行中的流式响应 |
| `CLEAR` | `/clear` | 清 Agent 侧会话历史与缓存实例，**并同步清空渠道侧该会话的内存缓存** |
| `COMPACT` | `/compact` | 未实现，回「not yet implemented」 |
| `APPROVE` | `/approve` | 见 9.2 |
| `DENY` | `/deny`、`/reject` | 见 9.2 |
| `STOP_SANDBOX` | `/stop-sandbox` | 中断 + 失效缓存 + 销毁沙箱容器 |
| `ENABLE` / `DISABLE` | `/enable`、`/disable` | 开关 `search` / `thinking` / `plan` 能力 |
| `PERMISSION` | `/permission` | 设置权限模式 |
| `REFRESH` | `/refresh` | 重建 Agent 实例 |

四个平台的 `MessageParser` 都走同一套解析，命令能力在平台间一致。`/clear` 之所以要在渠道侧补一刀：
`ChannelChatService` 每个 turn 都会把这份缓存拼进 `AgentContext.history`，只清 Agent 侧的话下一条
消息就把「已清空」的对话原样喂回去。

### 9.2 工具确认（HITL）

渠道会话按 admin 配置的 `permissionMode` 走，与其它会话无异。两条闭环：

- **流式**：`RequireUserConfirmEvent` → `ToolConfirmChatEvent` →
  `AgentStreamEvent.ToolConfirmStreamEvent` → `ChannelChatService.buildConfirmText()` 发一段纯文本
  清单，末尾提示「Reply /approve to confirm, or /deny to reject.」
- **批量**：`HarnessAgentWrapper.call()` 捕获到暂停异常后由
  `buildConfirmPromptIfPaused()` 生成同一段清单（优先从持久化状态里的 `ASKING` 工具块还原完整
  `ToolUseBlock`），随 `ChatResponse.content` 回给用户；用户回 `/approve` 或 `/deny` →
  `CommandType.APPROVE|DENY` → `DefaultAgentRunner.handleApproveOrDeny()` →
  组 `ConfirmAgentRequest` 调 `confirm()`。

批量路径的确认提示早就在 `harnax-harness-core` 里实现了，渠道侧此前拿不到它只有一个原因：
`HarnessAgentWrapper` 在 `call()` 与 `callStreamInternal()` 两处把 `chn-` 会话的权限模式无条件提升到
`BYPASS`，`RequireUserConfirmEvent` 根本不会发出。注释里的理由（渠道没有 `/approve` `/deny` UI）
在渠道侧做完这套文案之后已经不成立，而后果比「确认不了」更糟——admin 上配置的 ASK 规则在渠道下
静默失效，危险工具直接执行。两处提升已删除。

`ChannelChatService` 的 `onPendingConfirm` 回调（用于跨进程持久化待确认状态）目前没有接入：
批量路径的确认清单是从 Agent 侧状态还原的，不依赖渠道侧记账。

## 10. 配置项

全部在 `harnax-channel-service/src/main/resources/application.yml`，前缀 `channel`。

| 键 | 默认值 | 作用 |
|----|--------|------|
| `router-api-key` / `CHANNEL_API_KEY` | 空 | 渠道 → Router 的 API Key；为空时按下一项自动获取 |
| `auto-fetch-system-key` | `true` | 启动时向 admin 内部接口换取 SYSTEM Key |
| `sync.interval-ms` | 10000 | `channel` 表轮询与对账周期 |
| `sync.max-starts-per-cycle` | 5 | 单轮最多启动多少个监听器，`<=0` 不限 |
| `sync.restart-backoff-initial-ms` | 2000 | 死监听首次重试延迟 |
| `sync.restart-backoff-max-ms` | 300000 | 退避上限，同时也是「曾经健康」的重置阈值 |
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
| `proxy.response-timeout-ms` | 600000 | 批量响应超时（10 分钟） |
| `proxy.stream-idle-timeout-ms` | 180000 | SSE 空闲超时，0 关闭 |
| `router.breaker.enabled` | `true` | 是否启用熔断 |
| `router.breaker.failure-threshold` | 5 | 连续失败多少个请求后开闸 |
| `router.breaker.open-duration-ms` | 30000 | 开闸多久后转半开探测 |

回调入口相关的两项不在 `channel` 前缀下：

| 键 | 默认值 | 作用 |
|----|--------|------|
| `harnax.auth.skip-paths` | 含 `/api/channel/callback` | 平台无法出示服务令牌，回调前缀必须豁免统一认证；认证就落在平台签名上 |
| `harnax.auth.enabled` | `true` | 关掉它等于把 `/api/**` 全部公开，不只是回调 |

服务自身：端口 8083，`server.shutdown: graceful`，`spring.lifecycle.timeout-per-shutdown-phase: 20s`。
数据源直接指向 `harnax_admin` 库（Flyway 关闭，迁移由 admin 管）。鉴权走
`harnax.auth.enabled=true` 的统一内部认证，`service-id` 为 `channel-<n>`。

启动时若 `router-api-key` 为空且开启 `auto-fetch-system-key`，会向 admin 内部接口换取 SYSTEM Key：
连接 3 秒、读取 5 秒、最多 3 次（间隔 1 秒与 2 秒）。admin 与 channel-service 在 compose 里是一起
起来的，admin 短暂不可达是常态；换不到 Key 时抛出的异常会带上尝试次数。

## 11. 部署与网络

- `docker-compose.yml` 中的服务名是 `channel-service`，对外 8083。
- `docker-new/nginx.conf` 把 `/api/channel/` 转发到 `channel-service:8083`。admin 创建渠道时下发的
  `callbackUrl = $baseUrl/api/channel/callback/{callbackKey}` 现在由
  `ChannelCallbackController` 承接，**但只有飞书有对应的 HTTP 契约**：
  - 飞书：URL 验证回显、Encrypt Key 验签与 AES 解密都由官方 `EventDispatcher` 完成，事件解析与
    长连接走同一个 `parseFeishuEvent`，两条入口不会漂移。**必须配 `encodingAesKey`**——飞书在没有
    Encrypt Key 时压根不发签名头，那就没有任何东西可验，这个端点又是豁免认证的，所以
    `FeishuAdaptor.handleCallback()` 直接 403 拒绝，而不是收下一条匿名消息。
  - 钉钉 / 企微 / 微信：`supportsCallback()` 为 `false`，回调一律 501，对账循环同时把该渠道记为
    启动失败并写明「请改用长连接模式」。这三个平台的机器人形态没有等价的 HTTP 事件回调，
    补一个会接收未验签请求的空端点比不补更糟。
- 回调是异步的：验签通过后立刻回 ack，agent turn 交给 `ChannelTurnExecutor`。平台的超时是几秒，
  一个 turn 是几分钟，同步等必然超时重投。
- 配置入口与运行时约束现在是对齐的：
  - WebUI 的通讯模式下拉按类型过滤（`harnax-webui/src/pages/channel/components/channelModes.ts`），
    飞书在 webhook 下会显出 Encrypt Key（必填）与 Verification Token 两个输入框；
  - admin 在 `ChannelServiceImpl.resolveCommunicationMode()` 同样拒绝 `dingtalk` / `wecom` 的
    webhook 请求，并在未指定模式时按类型选推荐值，不再一律 `webhook`；
  - 三处各有一份「类型→可用模式」的表（前端、admin、运行时），没有共享模块，注释里互相点了名，
    改一处要另两处跟上。
- 长连接模式只需要**出方向**网络可达，不需要公网入口和回调地址，仍是推荐的接入方式；
  只有在无法放开出方向网络时才用飞书 webhook。

## 12. 修复记录与仍然存在的限制

### 12.1 已修复

编号沿用 2026-09 渠道层审查的结论（P0 = 功能事实上不可用且监控看不出来）。

| 编号 | 问题 | 修法 | 落点 |
|------|------|------|------|
| P0-1 | 飞书与钉钉在 `runConnection()` 的 `finally` 里无条件摘除 holder，而两个官方 SDK 的 `start()` 都立即返回——归属守卫从此恒真，**每一条入站消息都被当「过期监听器的残留」丢弃**，同时状态卡在 `CONNECTING`/`CONNECTED` 使 reconcile 永不重启、健康检查报 `UP` | holder 只在 `stop()` 里退休；删掉「`start()` 返回快 = 连接死了」的推断（`FAST_RETURN_MS`）；`markConnected` 只由首条入站消息触发；`stop()` 日志带上服务过的事件数 | `FeishuWebSocketMode`、`DingtalkStreamMode` |
| P0-1 回归防护 | 归属守卫这段核心逻辑零单测，回归时无人拦截 | 各 3 条测试：`start()` 返回后消息必须抵达处理器、`stop()` 后旧监听器回调必须丢弃、重启后只有新监听器服务。SDK 客户端用注入点替身（钉钉 `OpenDingTalkClient` 本身是接口；飞书的 `Client` 构造器私有，故抽出 `FeishuWsTransport`）| `*ModeOwnershipTest` |
| P1-1 | webhook 模式无回调入口，admin 仍下发 `callbackUrl`、nginx 仍有路由，配成 webhook 的渠道全量 404 且无人报错 | 新增 `ChannelCallbackController`（`POST /api/channel/callback/{callbackKey}`）+ `ChannelAdaptor.supportsCallback()/handleCallback()`；飞书委托官方 `EventDispatcher` 做验签/解密/挑战回显；无 HTTP 契约的三个平台显式 501 并在对账时记启动失败 | `ChannelCallbackController.kt`、`FeishuAdaptor.handleCallback` |
| P1-2 | 流式路径不投递附件，`EndStreamEvent` 也没有承载附件的字段 | 协议 `EndEventChatEvent`、SDK `EndStreamEvent` 补 `attachments`；harness-core 流式收尾时检测产出文件（挪到 `boundedElastic` 上跑，避免在发射线程做阻塞的 `ls`）；两条输出策略共用 `deliverFileAttachments()` | `ChatEvent.kt`、`HarnessAgentWrapper`、`ChannelChatService` |
| P1-3 | `chn-` 会话被无条件提升到 `BYPASS`，`RequireUserConfirmEvent` 不发；admin 配的 ASK 规则在渠道下静默失效，危险工具直接执行 | 删除 `call()` 与 `callStreamInternal()` 两处前缀判断，按配置走。批量路径的确认清单本就由 `buildConfirmPromptIfPaused()` 生成，无需改协议 | `HarnessAgentWrapper.kt` |
| P1-4 | 只有微信实现了 `sendFile`，其余平台的附件先被拉下几十 MB 再降级成一句提示 | 新增能力位 `supportsFileDelivery()`（只有微信 `true`），门槛放在取字节之前；一轮只发一条点名所有文件的提示；按「渠道只发文本、文件走 Web UI」的产品口径定稿 | `ChannelAdaptor`、四个 adaptor |
| P1-5 | 心跳过期的重启分支在 `retryDue()` 之前短路，绕过了退避门限 | `retryDue()` 提为所有重启路径的统一门限；半死重启不把这段时长计作健康寿命（否则退避立刻被重置，又回到每轮重启） | `ChannelBootstrapRunner` |
| P1-6 | 锁的获取抛异常时一律 `return true`，从未证得过所有权的实例也自认 owner | 改为刻意不对称：只有「刚在持有的连接上丢了上下文」才继续按 owner 服务，新连接一律 `false` | `ChannelListenerLockGuard` |
| P2-1 | 微信长轮询既不去重也不分块 | 按 `message_id` 走 `MessageDeduplicator` 三段式；`TextChunker` 按 4 000 字符切，只有首块带「正在输入」 | `WechatLongPollingMode` |
| P2-2 | 钉钉主动发送兜底把 `chunks` 用 `joinToString("\n")` 合回一条，分块白做 | 每个分块一次 OpenAPI 调用 | `DingtalkStreamMode.sendProactiveText` |
| P2-3 | `sendRichMessage` 在响应回来之前就上报 `onSendCompleted(..., null)`，失败也记成功 | 移到 `finally`，带上真实异常 | `FeishuWebSocketMode` |
| P2-4 | 读不懂的消息类型（钉钉的图片/文件/语音、企微的空文本、飞书的未知类型）被静默丢弃，用户发了东西毫无反应，与连接掉了无法区分；飞书未知类型还会把裸内容 JSON 当作用户原话喂给模型 | 三条长连接传输与飞书回调都回一条 `ReplyMarkers.unsupportedMessageType(msgtype)`，并 `commit` 而非 `rollback`，平台重投不会重复骚扰。钉钉在回提示前先缓存回发坐标；企微把 `req_id` 缓存提到可读性判断之前，让提示确实回在这条消息的流上；飞书未知类型改为拒绝而不是透传 | `DingtalkStreamMode`、`WecomWebSocketMode`、`FeishuWebSocketMode`、`FeishuAdaptor` |
| P2-5 | `resolveFileBytes` 的 URL 兜底 `url.readBytes()` 无超时、无大小上限，只校验 scheme | 见第 7 节「出站文件 URL 兜底」 | `ChannelChatService.downloadFromUrl` |
| P2-6 | 用户可见文案散落在实现里 | 集中到 `ReplyMarkers`（含 `isSyntheticReply()` 覆盖新前缀，避免提示语回灌进历史）| `ReplyMarkers` |
| P2-7 | 飞书图片 MIME 写死 `image/jpeg`、不限大小 | 魔数嗅探 + 5 MB 上限 | `FeishuWebSocketMode` |
| P2-8 | 微信扫码登录那行日志是中文，违反本模块「注释与日志用英文」的规范 | 改英文并带上 channelId（主源码里唯一一处中文日志，其余为注释，见 12.2） | `WechatLongPollingMode` |
| P2-9 | `/clear` 只清 Agent 侧，渠道侧缓存仍会把旧对话拼进下一条 `history` | `chat()` 在命令为 `CLEAR` 后调 `sessionManager.clearHistory()` | `ChannelChatService` |
| P2-10 | 企微回调 `req_id` 从不清除，第二条消息复用已关闭的流：写成功、ack 被平台拒、用户永远收不到；ack 与发送各记一次指标 | `req_id` 改为取用即消费，后续自然落到主动发送路径；ack 只在失败时补记一次 | `WecomWebSocketMode` |
| P2-11 | `ChannelApiKeyInitializer` 有无用的 `val url`、Bean 方法内发无超时 HTTP、admin 未就绪即整个上下文启动失败 | 删死代码、加 3 秒/5 秒超时与 3 次重试 | `ChannelApiKeyInitializer` |
| P2-12 | `per-channel` 许可先于会话锁获取，同会话排队时白占配额；排队本身还完全不可见 | 顺序换成会话锁 → 渠道许可，一个许可只代表「真正在跑的活」；等待超过 30 秒打一行 WARN 点出 channel/session 与时长。**没有加等待超时**——那等于为了保住一个本该吸收流量的队列而丢掉用户的消息 | `ChannelTurnExecutor` |

### 12.2 CRUD 链路（管理端 → 运行时）

第二轮 review 专查「前端建渠道 → admin 落库 → 渠道服务收得到」这条链，修掉的都是会让链条断掉
而不是让人难看的点。

| 编号 | 问题 | 修法 | 落点 |
|------|------|------|------|
| C-1 | 飞书 webhook 的 Encrypt Key 在 UI 里**没有任何输入框**，而运行时缺它就 403 拒收回调；`token` / `encodingAesKey` 两个 locale key 也长期是孤儿 | 飞书选 webhook 模式时渲染 Encrypt Key（必填）与 Verification Token 两个字段，并复用那组孤儿 key、把措辞从「企业微信」订正为飞书 | `CreateForm.tsx`、`UpdateForm.tsx`、`locales/{zh-CN,en-US}/pages.ts` |
| C-2 | 编辑任意字段都会**静默抹掉** `configJson` 里没有渲染成输入框的键：`validateFields()` 只返回已挂载字段（`rc-field-form@2.7.1` useForm.js:838 → `getFieldsValue(namePathList)`），而提交时整个 blob 被这 5 个键重建。于是改个描述就会清掉飞书 Encrypt Key；微信的 `botToken` 只是碰巧因为该类型没有任何配置字段才活下来 | 改成在存量 config 上合并：托管键从 form store 读（未渲染时保留加载值，清空则删键），非托管键原样保留 | `UpdateForm.tsx` |
| C-3 | 通讯模式下拉对所有类型敞开四种模式，`initialValue="webhook"`、admin 的默认值也是 `webhook`——按新契约有 3 个类型会建出一个永远收不到消息的渠道，保存还返回 200 | 前端按类型过滤可选模式并保留各类型推荐默认；admin 拒绝 `dingtalk` / `wecom` 的 webhook，未指定模式时按类型取推荐值；编辑时存量死值会被纠正为可运行模式 | `channelModes.ts`、两个表单、`ChannelServiceImpl.resolveCommunicationMode` |
| C-4 | 渠道 `type` 不做校验，`"DingTalk"` 这种大小写能落库，而运行时 `ChannelType.fromCode` 是**大小写敏感**的，于是只在运行时报「Unsupported channel type」 | admin 按已知类型集合校验（`SUPPORTED_TYPES`），错误在创建时就抛出；同时把断言 `"DingTalk"` 的既有测试改成真实 code | `ChannelServiceImpl`、`ChannelServiceImplTest` |

另外修掉两个挡住本次验证的既有编译问题（不属于渠道层，但都是一行 typo）：

- `harnax-entity/.../dto/McpAccessTokenResponse.kt` 的 KDoc 里写了 `` `/api/admin/mcp/**` ``，
  Kotlin 块注释可嵌套，那个 `/*` 把注释一路吃到文件末尾，整个模块编译失败。
- `InMemoryChannelSessionManagerTest.liveSessions()` 不是 `suspend`，却调用了 `suspend` 的
  `getHistory()`，导致 `harnax-channel-service` 的测试源集编译失败。

### 12.3 边界条件复查（第二轮）

针对「流程里的边界条件处理是否充分」的专项复查，修的都是能构造出具体输入的：

| 边界 | 原来会怎样 | 现在 | 落点 |
|------|-----------|------|------|
| 空文本 `TextChunker.splitByChars("", n)` | 返回 `[""]`，而每个传输都是 `chunks.forEach { send(it) }` → 给用户发一个空气泡（`WechatLongPollingMode` 里正好有条注释把这种空泡当 bug 记着） | 空输入返回空列表；同时改掉那条把 `[""]` 当契约钉住的旧测试 | `TextChunker.kt`、`TextChunkerTest.kt` |
| `stop()` 与 `start()` 抢在同一步之间 | `stop()` 关的是一个还没开的客户端，紧接着 `start()` 把套接字开起来了，而 holder 已被摘走 → **僵尸连接**，`autoReconnect` 让它永远重连；飞书是集群模式，同一 app 的事件只投给一个客户端，于是一部分真实消息永远进不到服务中的那条连接 | `start()` 返回后发现 `closing` 就立刻关掉自己这条，并把 holder 摘净 | `FeishuWebSocketMode`、`DingtalkStreamMode` |
| turn 里抛出 `Error`（非 `Exception`） | 传输层的 `catch (Exception)` 接不住 → 既不 `commit` 也不 `rollback`，`MessageDeduplicator.inFlight` 里留下一条永久记录，且没有任何日志（`ChannelTurnExecutor` 只把它交给 metrics） | 5 处 turn 体改成 `catch (Throwable)`；`inFlight` 另加 `MAX_IN_FLIGHT` 上限，超限时选择「重复处理」而不是「泄漏 + 永久屏蔽该 msgId」 | 四个传输 + `FeishuAdaptor`、`MessageDeduplicator` |
| 飞书图片是 HEIC/AVIF/TIFF（iPhone 默认格式） | 嗅探不到魔数就标成 `application/octet-stream` 发给模型 → 不支持的媒体类型让**整个 turn** 失败 | 未知类型直接跳过这张图并记日志，文本照答；另外给富文本多图加了单条消息 8 MB 的聚合上限（原来只有每图 5 MB，一张图封顶、一叠图不设限） | `FeishuWebSocketMode` |
| 回调端点把 SDK 的 500 原样回给平台 | 飞书把 5xx 当「没送到」无限重投同一个坏事件 | 任何 ≥500 一律降级成 400；超大 body 从「截断成空串再验签失败」改成明确 413；DB 查询异常也不再裸抛容器页 | `ChannelCallbackController` |
| `configJson` 存成非法 JSON | 运行期 `ChannelEntityConverter` 解析失败时把**整段配置打进 WARN 日志**——里面有 appSecret/Encrypt Key；而回调每次都走这条路径 | 只记字符数与错误；admin 侧写入时直接拒绝非 JSON object 与超长值 | `ChannelEntityConverter`、`ChannelServiceImpl.validateConfigJson` |
| `type` 改了但没带 `communicationMode` | feishu/webhook 改成 dingtalk 后模式原样留着 → 落进刚加的 `supportsCallback()` 拒绝分支，成为「保存成功但永远收不到」的渠道 | 类型变更强制重新校验模式；模式必须落在该类型可运行集合内；`status` 只接受 0/1 | `ChannelServiceImpl.resolveCommunicationMode` |
| 编辑表单换了平台类型 | 旧平台的 `appId`/`appSecret`/`encodingAesKey` 字段名跨类型相同，被原样写进新类型的 config blob | 改类型即清空这五个托管字段（非托管的微信登录态不动）；同时「清空所有凭证」现在真能落库（原来 configJson 为空会整个不发） | `UpdateForm.tsx` |
| 飞书 webhook 只配 Encrypt Key、不配 Verification Token | UI 把 token 标成「可选」，但 URL 验证比的就是它 → 平台侧验证永远失败且看不到原因 | webhook 下两个字段都必填，措辞与 locale 一起订正 | 两个表单、`locales/*` |
| 回发本身失败（钉钉 webhook 过期、企微流已关） | `handleChatError()` 里再发一次错误提示，而它**没有保护**：同一句 `sendMessage` 再次抛穿 `chat()`，日志指向这个第二次调用，用户既没拿到答案也没拿到错误提示 | 错误提示改成尽力而为（失败只记一条带 requestId 的 ERROR），不再把「送达失败」升级成「turn 失败」 | `ChannelChatService.handleChatError` |

**刻意没改的一处**：`WecomWebSocketMode` 的 `respondMsg`（主回发路径）整块发送、不分块，而兜底的
`sendMsg` 路径按 2000 字符分块。看着像同类问题，但 `aibot_respond_msg` 用的是 `stream` 全量替换语义
（见 `WecomFrames` 注释），2000 这个上限是否适用于它无法从代码判定；分块发送需要连续多个 stream
帧 + 末帧 `finish`，属于协议行为，没验证过就改会把主回发路径换成一种可能根本不被接受的说法。
现状是超限时 ack 会回 errcode 并记一条 WARN + 失败指标，能被发现，因此记在下面的限制里。

**被测试挡回去的一处改动**：给 `InMemoryChannelSessionManager` 的淘汰批量加 `coerceAtLeast(1)`
（想让小容量也少走几次全量排序），跑测试即失败——`reading history keeps a conversation alive` 证明
「只淘汰超额的那一条」才是本意，加下限会超额驱逐、把刚被读取续活的会话踢掉。已回退。

### 12.4 仍然存在的限制

| 级别 | 问题 | 说明 |
|------|------|------|
| 高 | 渠道详情与列表把 `configJson` **原样返回**，即 `appSecret` / 企微 Bot Secret / `encodingAesKey` / 微信 `botToken` 全部明文下发；`/page` 一次泄露整页 | `ChannelResponse.fromEntity` 直接拷 blob，而同仓的 `McpServerResponse`、`ModelProviderResponse`、`EnvVariableResponse` 都做了掩码。脱敏要连前端回填逻辑一起改（否则掩码会被当新值写回），属独立决策 |
| 高 | 渠道 CRUD 无权限注解、查询无租户条件 | admin 全局 `anyRequest().authenticated()`，`ChannelMapper.xml` 各语句没有 `tenant_id` 谓词（仓内不设 MyBatis 租户拦截器），与 `AgentMapper.xml` 的写法不一致 |
| 中 | 删除渠道只软删 `active=0`，`chn-<uuid>` 会话、沙箱与工作区不清理 | 监听器会在下一个对账周期停掉（这是文档化行为），但 Agent 侧资源留着 |
| 中 | `http` 类型没有任何 adaptor，四种模式都跑不起来，仍出现在类型下拉里 | 运行时会明确报 `no adaptor registered for channel type http`，admin 侧刻意没把它算进 webhook 校验（否则等于宣称问题出在 webhook） |
| 中 | 「渠道现在到底连上不」在 UI 上完全看不见 | 列表状态只有 DB 的 `status`；admin 没有代理 `/actuator/channels`，也没有轮询。`enabled` 与 `status` 两个开关语义相近但作用不同，界面上没解释 |
| 中 | 「不可读消息类型回提示」这条新分支只有编译与既有测试护航 | 它跨四个传输实现，各自的发送路径都要真凭据才能跑，没有为它单独搭 HTTP 替身。行为是 best-effort 包裹的：提示发不出去只落一行 WARN，不会影响后续消息 |
| 中 | 四个平台的 `supportsStreamingOutput()` 全为 `false` | 流式分支（`streamAndSend`、`sendStreamingFragment`、流式 HITL）实现与协议都通了，但没有平台会选中它。要真正用起来需要先确定用哪个平台的哪个 API 承载增量 |
| 低 | 飞书 webhook **未做真实平台联调** | 签名算法对齐了官方 `EventDispatcher`（并用独立算出的摘要做了单测），控制器路由也有单测，但没有凭据与公网入口跑端到端。首次接入按第 11 节核对 `encodingAesKey` 与 URL 验证 |
| 低 | 渠道侧 `history` 缓存仍然只写不读 | `RouterAgentAdaptor` 不消费 `AgentContext.history`，Agent 记忆由 `chn-` 会话自己维护；缓存只服务 `/clear` 与将来的本地上下文裁剪 |
| 低 | `channel-sdk` 的默认 `sendFile()` 仍会发一条文本提示 | 有 `supportsFileDelivery()` 之后这条路径在实践中不会被管道触发，留着是给直接调用者的兜底；两处文案已统一走 `ReplyMarkers` |
| 低 | 渠道模块约 80 处中文注释（不含日志），违反本模块的英文规范 | 集中在 `ChannelBootstrapRunner`、`RouterClient`、`InMemoryChannelSessionManager` 与若干测试类，多为历次提交里刻意用中文写的 rationale。本次新增/改动的代码一律英文；把这批存量整体翻译是一次独立的纯注释改动，混进行为修复里只会让两边都难 review |

## 13. 排障速查

| 症状 | 先看 | 结论怎么读 |
|------|------|-----------|
| 发消息给机器人完全没反应 | `/actuator/channels` 的 `receivedCount` 与 `lastActivityAt` | 一直是 0 而状态 `CONNECTED` → 消息没进处理器，查该渠道 `lastError`；有增长但没回复 → 往下看 Router 侧 |
| 状态 `CONNECTED` 但不回话 | 打开 debug 看有没有「superseded listener」，再看 `stop()` 时的 `served N event(s)` | `served 0 event(s)` 就是收得到连接、收不到消息；这是 P0-1 那一类归属问题 |
| 渠道一直 `FAILED` 且日志说 webhook | `channel.lastError` | 该类型没有 HTTP 契约（钉钉 / 企微 / 微信），改成长连接模式 |
| 飞书 webhook 403 | 该渠道有没有配 `encodingAesKey` | 没有 Encrypt Key 就没有可验证的签名，端点会拒绝而不是收匿名事件 |
| 飞书 webhook 配好了但不进对话 | 平台侧 URL 验证是否通过，`/api/channel/callback/{callbackKey}` 是否可达 | 验证不通过通常是被 `UnifiedAuthFilter` 拦了（`skip-paths` 没带上这个前缀）或 nginx 路由没指过来 |
| 所有渠道同时报错 | `/actuator/channels` 的 `routerCircuit` | 熔断开闸，看 router 是否重启或网络分区 |
| 只有某个副本在收消息，另一个空转 | `listenerLock.held` | 正常：`GET_LOCK` 互斥，非持有副本故意空转 |
| 只有部分渠道在跑，且报「lock was released while we held it」 | 两个副本是否连了不同 MySQL 实例 / 连接池是否复用了同一连接 | 锁活在会话上，换连接等于丢锁 |
| 回复被截断 | 消息长度与第 2 节分块上限 | 三条路径都已分块；若仍截断，多半是平台侧对单条消息另有更小的限制 |
| 生成的文件收不到 | 日志 `[Files]` 前缀 | 飞书 / 钉钉 / 企微是预期行为（能力位为 `false`，提示里写了去 Web UI 取）；微信看 `Unable to resolve file content` |
| 部署后渠道掉几分钟 | 是否执行了 `@PreDestroy` 优雅停机 | 看 `Stopping N channel listener(s)` 日志有没有出现 |
| 危险工具没确认就执行了 | 该渠道在 admin 上配的权限模式 | `chn-` 不再强制 `BYPASS`；若仍如此，查 `permissionMode` 与规则是否本身就没配成 ASK |

## 14. 关键文件索引

| 关注点 | 文件 |
|--------|------|
| 对账与生命周期 | `harnax-channel-service/.../bootstrap/ChannelBootstrapRunner.kt` |
| 单实例互斥 | `harnax-channel-service/.../bootstrap/ChannelListenerLockGuard.kt` |
| 平台回调入口 | `harnax-channel-service/.../endpoint/ChannelCallbackController.kt` |
| 回调契约与能力位 | `harnax-channel-sdk/.../adaptor/ChannelAdaptor.kt`（`supportsCallback` / `supportsFileDelivery` / `handleCallback`）、`ChannelCallbackResult.kt` |
| Router 调用与超时 | `harnax-channel-service/.../client/RouterClient.kt`、`RouterCircuitBreaker.kt` |
| 平台 → SDK 编排 | `harnax-channel-sdk/.../service/ChannelChatService.kt`、`ReplyMarkers.kt` |
| Agent 侧适配 | `harnax-channel-service/.../adaptor/RouterAgentAdaptor.kt`、`config/ChannelConfig.kt` |
| turn 限流 | `harnax-channel-sdk/.../dispatch/ChannelTurnExecutor.kt` |
| 幂等与分块 | `harnax-channel-sdk/.../util/MessageDeduplicator.kt`、`TextChunker.kt` |
| 状态机与指标 | `harnax-channel-sdk/.../monitor/ChannelConnectionState.kt`、`ChannelConnectionTracker.kt` |
| 运行视图 | `harnax-channel-service/.../monitor/ChannelRuntimeEndpoint.kt`、`ChannelRuntimeMonitor.kt`、`health/ChannelConnectionHealthIndicator.kt` |
| 四平台传输模式 | `FeishuWebSocketMode.kt`、`FeishuWsTransport.kt`、`DingtalkStreamMode.kt`、`WecomWebSocketMode.kt`、`WechatLongPollingMode.kt` |
| 归属守卫回归测试 | `FeishuWebSocketModeOwnershipTest.kt`、`DingtalkStreamModeOwnershipTest.kt` |
| 渠道 → Agent 的权限与会话分叉 | `harnax-harness-core/.../HarnessAgentWrapper.kt`（`chn-` 只影响附件持久化策略）|
