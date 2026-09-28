# Harnax Channel Integration

> This is the complete design document for `harnax-channel`: which platforms are supported, how messages
> arrive and leave, how a conversation is identified, how a connection is kept alive, what the management
> plane writes, and where to look when something breaks. It is written for developers integrating a
> channel and for operators troubleshooting one.
>
> Every statement is grounded in the current code. Where a module comment disagrees, the code wins.

## 1. Positioning and module layout

The channel layer wires external IM platforms (Feishu / WeChat / WeCom / DingTalk) up as conversational
entry points for an Agent. Sending and receiving on the platform side belongs to this layer; Agent
reasoning and conversation state belong to `harnax-session-router` + `harnax-agent-service`, and the two
talk over HTTP only.

| Module | Responsibility | Depends on |
|------|------|------|
| `harnax-channel/harnax-channel-sdk` | Platform-neutral abstractions: `ChannelAdaptor`, `AgentAdaptor`, `ChannelCommunicationMode`, `ChannelChatService`, `ReplyMarkers`, the message model, deduplication, chunking, backoff, connection state, `ChannelTurnExecutor` | Kotlin coroutines + `harnax-protocol` only; no Spring |
| `harnax-channel/harnax-channel-service` | The runnable service (container port 8083, published by compose as `28083:8083`): the `ChannelBootstrapRunner` reconcile loop, `ChannelListenerLockGuard` listener mutex, `RouterClient`, `RouterCircuitBreaker`, the health indicator, `/actuator/channels`, `ChannelCallbackController`, API key initialisation | Spring Boot + MyBatis (reads the `channel` table in the `harnax_admin` database) |
| `harnax-channel/harnax-channel-feishu` | Feishu adaptor + `FeishuWebSocketMode` (official `oapi-sdk` long connection) + the `FeishuWsTransport` injection point | Feishu open platform |
| `harnax-channel/harnax-channel-wecom` | WeCom adaptor + `WecomWebSocketMode` (in-house OkHttp WebSocket frame protocol) | WeCom intelligent bot |
| `harnax-channel/harnax-channel-wechat` | WeChat adaptor + `WechatLongPollingMode` (iLink long polling) + the only adaptor implementing `sendFile` | WeChat iLink |
| `harnax-channel/harnax-channel-dingtalk` | DingTalk adaptor + `DingtalkStreamMode` (official `app-stream-client`) | DingTalk open platform |

`ChannelType` carries five codes: `wecom` / `wechat` / `feishu` / `dingtalk` / `http`.
`ChannelType.fromCode` is case-sensitive and returns `null` on no match; `ChannelEntityConverter.toSpec`
turns that into `IllegalArgumentException("Unsupported channel type: ...")`. Dispatch looks only at
`ChannelType`: `ChannelAdaptorRegistry` indexes every `ChannelAdaptor` bean by `getType()`, so adding a
platform means registering one more adaptor bean. No adaptor is registered for `http`, and the type stays
in the type set: admin's `ChannelServiceImpl.MODES_BY_TYPE` allows `webhook` as its only mode, so
`ChannelBootstrapRunner.startChannel` lands in the webhook branch first and the recorded startup failure is
`webhook callback mode is not implemented for http; use this platform's long-connection mode`. The
`no adaptor registered for channel type ...` text in that same class sits on the long-connection branch, and
`http` does not reach it.

`communicationMode` describes the transport and decides "does this process have to hold a connection for
this channel". It plays no part in routing.

## 2. Capability matrix

| Platform | Transport | Inbound | Outbound reply | Idempotent dedup | File delivery | Streaming output | HTTP callback contract |
|------|----------|------|------|----------|----------|----------|---------------|
| Feishu | `websocket` (official SDK long connection) | event subscription | `im/v1/messages` | by `msgId` | ❌ | ❌ | ✅ |
| Feishu | `webhook` | `POST /api/channel/callback/{callbackKey}` | the platform webhook address | by messageId | ❌ | ❌ | ✅ |
| DingTalk | `stream` (official SDK) | BOT_MESSAGE_TOPIC | `sessionWebhook` → bot OpenAPI fallback | by `msgId` | ❌ | ❌ | ❌ |
| WeCom | `websocket` (in-house frame protocol) | `aibot_*` frames | `aibot_respond_msg` → `aibot_send_msg` fallback | by `msgid` | ❌ | ❌ | ❌ |
| WeChat | `long_polling` (iLink) | long polling | iLink API | by `message_id` | ✅ | ❌ | ❌ |
| HTTP | `webhook` | — | — | — | ❌ | ❌ | ❌ (no adaptor) |

The capability bits are declared by `ChannelAdaptor`, and the defaults are the table above:

- `supportsStreamingOutput()` defaults to `false` and none of the four platform adaptors overrides it. So
  `shouldUseStreaming(agentAdaptor) = supportsStreamingOutput() && agentAdaptor.supportsStreaming()`
  is always `false`: every channel uses batched output (`ChannelChatService.batchSend()`) and only a
  "typing" indicator goes out while the AI is working. The streaming branch
  (`streamAndSend()`, `sendStreamingFragment()`) is wired through in protocol and implementation; the
  decision belongs to the Channel implementation, and no platform currently takes it.
- `supportsCallback()` defaults to `false` and only Feishu returns `true`. The default
  `ChannelAdaptor.handleCallback()` throws `UnsupportedOperationException` carrying
  `${getType().code} channels have no HTTP callback contract; use the long-connection communication mode`.
- `supportsFileDelivery()` defaults to `false` and only WeChat returns `true`. This gate is checked
  **before any file bytes are fetched**: pulling tens of megabytes for an upload nobody can receive would
  only drag the turn into its timeout.

## 3. End-to-end path

```
platform push / polling / HTTP callback
  └─ FeishuWebSocketMode.handle / DingtalkStreamMode.handleBotMessage
     / WecomWebSocketMode frame handling / WechatLongPollingMode
     / ChannelCallbackController → FeishuAdaptor.handleCallback (Feishu webhook)
        │ 1 ownership guard: is the current holder still this channelId's listener
        │ 2 dedup: MessageDeduplicator.tryBegin(msgId)
        │ 3 parse: platform MessageParser → ChannelMessage
        │ 4 cache the reply coordinates (DingTalk sessionWebhook / WeCom callback req_id / proactive-send target)
        ▼
     ChannelTurnExecutor.launchTurn(channelId, platformSessionId)
        │ per-session serial lock → per-channel Semaphore (served by a fixed pool)
        ▼
     the handler inside the adaptor (the AgentAdaptor + ChannelSessionManager + ChannelChatService
     triple is wired by startChannelWithAgent)
        messageParser.parse(message).withSessionId(channel.sessionId)
        ▼
     ChannelChatService.chat(message, channelSpec, agentAdaptor, channelAdaptor, agentRequest)
        │ read history → store the user message → build AgentContext → pick the output strategy → on /clear drop the channel-side cache
        ▼
     RouterAgentAdaptor.process(context)
        ▼
     RouterClient (RestClient / WebClient + RouterCircuitBreaker)
        │ POST /api/router/agent/chat       (batched; currently always this one)
        │ POST /api/router/agent/chat/stream (streaming; never selected today)
        │ POST /api/router/agent/command    (slash commands)
        ▼
     harnax-session-router → pick an instance by sessionId → harnax-agent-service
        ▼
     ChatResponse{content, attachments} / stream events (the terminal event carries attachments)
        │ batched reply: channelAdaptor.sendMessage() (chunked internally to the platform limit)
        │ file delivery: deliverFileAttachments() — one entry point shared by batched and streaming
        └ store the assistant message in the channel-side conversation
```

One message handling is a **turn**. Inside `ChannelTurnExecutor.launchTurn` an exception thrown by the
turn body is caught and handed to `ChannelMetricsSink.onTurnCompleted`; it never bubbles back to the
platform's receiving thread. `CancellationException` is the exception to that rule and propagates.

## 4. Session and identity model

- **One `channel` row = one Agent conversation.** `channel.session_id` looks like `chn-<uuid>`, is minted
  by admin's `ChannelServiceImpl.generateSessionId()` when the channel is created, never changes
  afterwards, and is the only conversation identifier on the Agent side. Before handing a message to
  `ChannelChatService`, an adaptor always **overwrites** the parsed sessionId with
  `withSessionId(channel.sessionId)`.
- **The platform session id (`chat_id` / `conversationId` / `from_user_id` …) serves exactly two things:**
  addressing the recipient when replying, and the per-session serial-lock key in `ChannelTurnExecutor`
  (`"<channelId>:<sessionId>"`). It never denotes an Agent-side conversation.
- Therefore: **under one bot, every user and every group shares one stretch of Agent memory.** To
  isolate conversations per person, create one channel row per person.
- **A `chn-` conversation has no row in the `session` table.** The session API mints `web-` and `mp-` ids
  only, and channel conversation state lives exclusively on the runtime side (agent-service session state,
  plans, sandbox container). Ownership lives on the `channel` row itself:
  `ChannelMapper.selectOwnerBySessionId` answers `sessionId` / `tenantId` / `agentId` for a `session_id`
  and **deliberately omits the `active = 1` filter** — a soft-deleted row still has to be able to say
  whose session it was, otherwise "delete a channel" would become "erase the record of who is
  responsible for reading that session". `SessionAccessGuard` in `harnax-session-router` makes its
  same-tenant decision from that answer: same tenant passes, cross tenant is refused.
- **A `chn-` conversation has no user identity, so OAuth-class MCP is not loaded.**
  `McpSessionOwnerResolver.resolve()` looks up the `session` row for `web-` / `mp-`, calls the scheduler's
  owner endpoint for `task-`, and returns `null` directly for `chn-` with a debug log line: the `creator`
  of a channel conversation is a platform-side sender identifier, not a platform account. `null` is not
  an error; the rule is "no user, no OAuth tools".

There is a separate in-memory conversation cache on the channel side,
`InMemoryChannelSessionManager` (key = `channelId` + platform session id), used to assemble
`AgentContext.history`. `RouterAgentAdaptor` does not consume `history` — the Agent-side memory is
maintained by the `chn-` conversation itself — so this cache serves the cleanup side of `/clear` and local
trimming.

## 5. Listener lifecycle and reconciliation

`ChannelBootstrapRunner` is the single entry point. `ApplicationReadyEvent` triggers the first
`reconcile()`, after which `@Scheduled(fixedDelayString = "${channel.sync.interval-ms}")` drives it
(`fixedDelay` guarantees a pass cannot overlap the previous one; a slow platform handshake can make a
pass outlast the interval).

One `reconcile()` pass does four things in a fixed order, each a precondition for the next:

| Step | Concern | Behaviour |
|------|--------|------|
| 1 | Ownership | when `lockGuard.hold()` is false, `stopAll()` if anything runs locally, then return immediately |
| 2 | Membership | running in memory but absent from the auto-start set in DB → `stopChannel()`; present in DB and absent in memory (ascending by id) → `startChannel()` |
| 3 | Config drift | `configFingerprint()` changed → restart at once (treated as a human action, no backoff wait), still bounded by the per-pass start budget |
| 4 | Liveness | see below |

`selectAutoStartChannels` selects `enabled = 1 AND status = 1 AND active = 1`, ordered by `id ASC`.

In the already-started branch (`reconcileRunning`) the checks run in this order:

- `startFailed` and `retryDue()` → restart;
- `listening == false` (a webhook-type channel) → clear that channel's backoff state, no liveness probe;
- **half-dead**: `status == CONNECTED` and `lastHeartbeatAt > 0` and silent for longer than
  `stale-heartbeat-ms` → restart behind the common `retryDue()` gate, recording the disconnect with
  `healthyLifetime = false` (this socket's lifetime does not count as healthy lifetime, otherwise the
  backoff resets immediately and the channel is restarted on every pass again);
- `state.isServing()` → reset the backoff and do nothing;
- anything else (state not serving) → restart behind the `retryDue()` gate.

Key design points:

- **`configFingerprint(entity)`** joins these eight fields with `|`: `type`, `communicationMode`,
  `agentId`, `enabled`, `status`, `sessionId`, `callbackKey`, `configJson` (empty counts as `""`).
  `update_time` is not used: MySQL `DATETIME` only reaches seconds, so two edits inside the same second are
  indistinguishable, and a change to a display-only field would kick a healthy long connection for nothing.
- **Start budget** `channel.sync.max-starts-per-cycle` (default 5, `<=0` means unlimited): new starts,
  drift restarts and liveness restarts share one budget within a pass, consumed in ascending id order, so
  replicas and logs converge on the same channel.
- **Exponential backoff** `ReconnectBackoff`: starts at `restart-backoff-initial-ms` (2 s), capped at
  `restart-backoff-max-ms` (300 s), and `resetThreshold` takes the same cap (a connection held steadily
  until the backoff cap counts as healthy, so the next disconnect returns to fast retries). The
  `retryDue()` gate uses the **current** backoff step, so a repeatedly failing channel is retried more and
  more sparsely.
- **Single-instance mutex**: `ChannelListenerLockGuard` uses MySQL `GET_LOCK` (lock name
  `channel.lock.name`) so that exactly one replica holds the long connections — two listeners on the same
  credentials consume every message twice and split the conversation history across two heaps. The failure
  policy is deliberately asymmetric: a connection that has proven ownership keeps serving as owner through
  DB flakiness; a new connection that never proved ownership returns `false`.
- **Graceful shutdown** `@PreDestroy shutdown()`: set `shuttingDown` → take `reconcileLock` then
  `stopAll()` → `adaptor.shutdown()` one by one to release shared transports → `lockGuard.release()`.
  `spring.lifecycle.timeout-per-shutdown-phase: 20s` is the budget for this. Without it the platform-side
  socket is still open when the process exits, the platform keeps pushing into a dead connection, and the
  restarted process must wait for the platform to reclaim that session before it can listen again.

### 5.1 How each transport keeps itself alive

| Mode | Who holds the connection | Proof of life |
|------|------------|----------|
| Feishu `websocket` | the official `WsClient` (the SDK's own keep-alive thread) | the first inbound event is the only positive evidence; `start()` returning cleanly says nothing |
| DingTalk `stream` | the official SDK's scheduled thread pool | same |
| WeCom `websocket` | an in-house OkHttp WebSocket with a `generation` counter | the subscription acknowledgement counts as connected; heartbeat ping/pong decides death; callbacks from an out-of-date socket generation are dropped by `generation` |
| WeChat `long_polling` | a self-managed daemon polling thread | the polling loop retries on its own |

Every connect call in the three official/in-house SDKs is **non-blocking**. Two invariants follow:

1. A listener's holder retires only inside `stop()`. Clearing the holder when the connect call returns
   makes the ownership guard permanently true while the socket is still alive, and the result is that every
   inbound message is discarded as "leftover from a stale listener".
2. Liveness is never inferred from "did the connect call return quickly", only from positive signals
   (first inbound message / subscription acknowledgement / heartbeat).

Feishu and DingTalk log one line at `stop()` stating how many events were served and for how long: a
connection that the platform cut, and that the SDK's own reconnects then held open for hours without
receiving anything, shows an event count of 0 in that line while the monitored state says `CONNECTED`.

## 6. Connection state machine and observability

`ChannelConnectionStatus`: `UNKNOWN → CONNECTING → CONNECTED`; errors go to `RECONNECTING` / `FAILED`;
an intentional stop goes to `STOPPED`. `isServing()` counts `CONNECTED`, `CONNECTING` and `RECONNECTING`
as serving.

`ChannelConnectionState` is an immutable snapshot carrying `sinceMillis`, `lastConnectedAt`,
`lastActivityAt`, `lastHeartbeatAt`, `reconnectCount`, `receivedCount`, `lastError`; a timestamp of 0
means "never happened". `lastHeartbeatAt` stays 0 for transports whose keep-alive lives inside the vendor
SDK, which also takes them out of the half-dead rule (an idle but healthy channel is not restarted over and
over).

Each transport owns one `ChannelConnectionTracker`, the single writer of that state;
`ChannelRuntimeMonitor` aggregates it into the runtime view. Three surfaces expose it:

| Surface | Contents |
|------|------|
| `/actuator/channels` (`ChannelRuntimeEndpoint`) | whether the listener lock is held, summary counts, distribution by state, the router circuit-breaker snapshot, the `missingListener` set, and per channel `status` / `serving` / `statusAgeMs` / `lastConnectedAt` / `lastActivityAt` / `reconnectCount` / `receivedCount` / `lastError` |
| `/actuator/health` (`ChannelConnectionHealthIndicator`) | everything serving → `UP`; something not serving but not hard-failed → `OUT_OF_SERVICE`; something `FAILED` for longer than `monitor.health.failure-grace-ms` (default 60 s) → `DOWN` |
| Prometheus / Metrics | `MicrometerChannelMetricsSink`: connection up/down, send duration and errors, duplicate message counter, turn duration and exceptions |

`/actuator/health/liveness` **deliberately excludes** the channel health indicator: a socket the platform
cut is a degraded service, not a broken JVM, and restarting the process would take the other channels down
with it.

`ChannelRuntimeEndpoint` sits under `/actuator` rather than `/api/channel`: this service's
`UnifiedAuthFilter` intercepts `/api/**`, the actuator prefix is exempt, so troubleshooting is a plain
`curl` without first obtaining a service token.

## 7. Reliability boundaries

| Concern | Implementation | Parameters |
|--------|------|------|
| at-least-once tolerance | `MessageDeduplicator` in three phases: `tryBegin` (in flight) → `commit` (handled) / `rollback` (handling failed, a redelivery may try again); the in-flight set is bounded, and on overflow the choice is "possibly handled twice" rather than "leak and permanently mask that msgId" | — |
| Concurrency ceiling | `ChannelTurnExecutor`: fixed pool → per-session serial lock (1 permit) → per-channel `Semaphore`. **Session lock first, quota second**: otherwise queued messages of one session would each hold a channel permit while waiting for their own predecessor, and a few slow conversations could block that channel's other sessions. One permit means "work actually running" | `channel.turn.pool-size`=24, `channel.turn.per-channel-concurrency`=4 |
| Queue visibility | a turn waiting past the threshold logs one WARN naming channel / session and the actual wait. There is no queue timeout — that would mean dropping the user's message to protect a queue whose job is to absorb it | threshold in `ChannelTurnExecutor` constants |
| Session lock reclamation | a sweep runs once a count or time interval of acquisitions is reached; entries idle long enough with all permits free are removed | `ChannelTurnExecutor` private constants |
| Long text | `TextChunker.splitByChars` / `splitByUtf8Bytes`: split at a newline first, then a space, and hard-cut only when neither exists; empty input yields an empty list | limits passed in by each platform |
| Router unavailable | `RouterCircuitBreaker` (in-house CLOSED/OPEN/HALF_OPEN): opens after N consecutive failures, fails fast during the cold window with one friendly line; half-open admits exactly one probe request, and a lost probe re-arms the breaker | `channel.router.breaker.*` |
| Timeouts | batched uses `RestClient`: connect 5 s, response from `channel.proxy.response-timeout-ms` — this service's `application.yml` sets 600000 (600 s) while `ChannelConfig`'s `@Value` fallback is 120000 (120 s), and the fallback is what applies when that yml is not in place; streaming uses `WebClient` + `Flux.timeout(stream-idle-timeout-ms)` (cancel after 180 s without an event; 0 disables) | `channel.proxy.*` |
| Outbound file URL fallback | `http` / `https` only, redirects are not followed (following one bypasses both the scheme and the address check); after resolution loopback / any-local / link-local addresses are refused while an internal object store still passes; connect 5 s, read 30 s, 50 MB cap and an oversized body is discarded whole rather than truncated | `ChannelChatService` private constants |
| Error surfacing | streaming `ErrorStreamEvent` / batched exception → `ReplyMarkers.FAILED_PREFIX` + code + `requestId` (`req-xxxxxxxx`, matching `ApiCallLog`) | — |
| Empty reply | when the batched path receives blank content it answers with the `ReplyMarkers.EMPTY_REPLY_PREFIX` fallback text; when the same turn has files to deliver, only the file notice is sent | — |
| The reply itself failing | the error text inside `handleChatError()` is best effort: a second send failing logs one ERROR carrying the requestId and does not escalate "delivery failed" into "turn failed" | — |
| Unreadable message types | the transport hands the platform message type to `ReplyMarkers.unsupportedMessageType(...)`, replies with one notice and `commit`s (a platform redelivery will not nag again), rather than dropping silently | — |
| Callback entry | request body capped at 1 MB, above that 413 (refuse rather than truncate — half a signed event only produces a misleading signature failure); verification and decryption belong to the platform's official SDK; anything ≥500 is downgraded to 400 | `ChannelCallbackController` |

`ReplyMarkers` is where the text produced by the pipeline itself lives; `isSyntheticReply()` recognises it
by prefix and keeps it out of the conversation history — otherwise the next turn would feed an error string
back to the model as something the assistant actually said. Current prefixes: `ROUTER_ERROR_PREFIX`,
`EMPTY_REPLY_PREFIX`, `FAILED_PREFIX`, `FILE_UNDELIVERABLE_PREFIX`, `FILE_SEND_FAILED_PREFIX`.

## 8. File delivery

There is exactly one product path for files: **a user who wants a file uses the WebUI**. Channel-side
delivery applies only to platforms that declared the capability.

Files produced by the Agent come in two shapes depending on the conversation type. For a channel
conversation (`chn-` prefix) a `FileAttachment` carries only `filePath` (the sandbox workspace path),
`fileSize` and `mimeType`; `url` and `objectKey` are empty and nothing is uploaded to object storage —
`HarnessAgentWrapper.detectAndPersistOutputFiles()` takes that branch for `chn-`, and **no download link is
appended for a channel conversation** (in `call()` the append requires
`attachments.isNotEmpty() && !sessionId.startsWith("chn-")`): that link would point at a workspace path the
channel side cannot publish, so writing it down would guarantee a dead link. WebUI conversations are
persisted as usual and carry the link.

Delivery is owned by `ChannelChatService.deliverFileAttachments()`: `batchSend()` passes
`ChatResponse.attachments` and `streamAndSend()` passes the terminal stream event's `attachments`, so both
output strategies share one entry point (it returns immediately when `attachments` is empty, which is why
both call sites may call it unconditionally). Fetching bytes follows a two-level policy:

1. **Workspace directly**: `workspaceFileDownloader` →
   `RouterClient.downloadWorkspaceFile(sessionId, filePath)` →
   `GET /api/router/agent/workspace/{sessionId}/download?path=...`.
2. **HTTP URL fallback**: when `attachment.url` is non-empty, the bytes come through `ChannelChatService`'s
   outbound download constraints — scheme restricted to `http` / `https`, redirects not followed,
   loopback / any-local / link-local refused after resolution, connect 5 s and read 30 s, 50 MB cap.

The capability gate sits **before any bytes are fetched**: when `channelAdaptor.supportsFileDelivery()` is
`false`, one turn emits a single `ReplyMarkers.fileUndeliverable(fileNames)` listing each file name and
pointing at the Web UI (one action covers all files, so one message rather than one per file); when it is
`true` (WeChat only), files are uploaded one by one and a single failure degrades to
`ReplyMarkers.fileSendFailed(fileName)` without affecting the rest. The default `ChannelAdaptor.sendFile()`
implementation degrades to the same `fileUndeliverable` text, so a direct caller is covered too.

## 9. Commands and HITL

### 9.1 Slash commands

When the message text starts with `/`, `CommandAgentRequest.parse()` turns it into a command and it goes to
`POST /api/router/agent/command` (batched, not streamed). Keywords are case-insensitive and `args` are
separated by a space or a colon. Supported command types: interrupt, clear history, `/approve`, `/deny`,
stop the sandbox, toggle `search` / `thinking` / `plan`, set the permission mode, rebuild the instance;
`COMPACT` answers "not implemented". All four platforms' `MessageParser` share this parser, so command
capability is identical across platforms.

`/clear` clears the Agent-side conversation, and `ChannelChatService.chat()` additionally calls
`sessionManager.clearHistory()` to drop the channel-side in-memory cache for that conversation: this cache
is folded into `AgentContext.history` on every turn, so clearing only the Agent side would feed the
"cleared" dialogue straight back on the next message.

### 9.2 Tool confirmation (HITL)

A channel conversation follows the `permissionMode` configured by admin, exactly like any other
conversation. Two closed loops:

- **Streaming**: the Agent-side confirmation event → `AgentStreamEvent.ToolConfirmStreamEvent` →
  `ChannelChatService.buildConfirmText()` sends a plain-text list ending in
  `ReplyMarkers.CONFIRM_FOOTER`, which tells the user to answer `/approve` or `/deny`.
- **Batched**: after `HarnessAgentWrapper` catches a pause, `buildConfirmPromptIfPaused()` produces the same
  list (preferring to rebuild the complete `ToolUseBlock` from the `ASKING` tool blocks in persisted
  state) and returns it as `ChatResponse.content`; the user answers `/approve` or `/deny` →
  `CommandType.APPROVE` / `CommandType.DENY` → a `ConfirmAgentRequest` is assembled and `confirm()` is called.

The permission mode comes from `channel.permission_mode` (admin's `ChannelCreateRequest.permissionMode`,
default `DEFAULT`), and the runtime neither raises nor lowers it for the `chn-` prefix.
`ChannelChatService`'s `onPendingConfirm` callback — meant for persisting pending confirmations across
processes — is not wired: the batched path's confirmation list is reconstructed from Agent-side state and
does not depend on channel-side bookkeeping.

## 10. Management plane: CRUD, tenancy, credential masking, deletion

The management side and the runtime read the same `channel` table; there is no synchronisation protocol in
between: admin writes the row and the runtime sees it on the next reconcile pass.

### 10.1 Read path

List, detail, create, update, toggle and delete on `ChannelController` all go through `ChannelServiceImpl`
first.

- **The tenancy predicate.** `tenant_id = #{tenantId}` in `selectChannelList` is a server-derived
  predicate, not a filter the caller may choose; `getChannel(id)` re-checks the fetched row with
  `it.tenantId == currentTenantId()` and treats a mismatch as "does not exist", so someone else's row and a
  nonexistent row are indistinguishable over HTTP. Update, toggle and delete all read through `getChannel`,
  so an id you have no claim on can neither be changed nor deleted. The resolution chain lives in
  `TenantResolver`: a verified `X-Tenant-ID` first, then the caller's own tenant, and only as a last
  resort the default — the same expression `createChannel` writes, so a row is always readable by whoever
  was allowed to write it.
- **Who `channel.tenant_id` belongs to.** A channel row's tenant is the tenant of the Agent named by its
  `agent_id`: a channel exists to expose one Agent and the runtime runs as that Agent. The baseline
  `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` declares that column as
  `bigint NOT NULL DEFAULT '1'`, so every row names a tenant and no channel row in the database is
  unattributed. The write side uses `currentTenantId()`, a request without a workspace header lands on the
  DDL default tenant 1, and `selectChannelList` filters on that column, so such a row disappears from its
  own creator's list and stays visible in tenant 1's list. No database constraint links `tenant_id` to
  `agent_id`, which is why "the tenant follows the Agent" is a rule the write side keeps rather than one the
  schema enforces: a row is attributed to its Agent's tenant only when the resolution chain answers with the
  right tenant, and a tenant written by a request that did carry a workspace header is deliberate and stays
  as it is. The `creator` column cannot serve this rule (`createChannel` never writes it, the baseline
  defaults the column to the literal `system`, so every row carries that value).
- **`callbackKey` is never delivered.** There is no `ChannelResponse.callbackKey` field at all. It is the
  only credential the callback endpoint checks, so what leaves the server is the derived `callbackUrl`, and
  only when `communicationMode == "webhook"` — a `websocket` / `stream` / `long_polling` channel pulls its
  messages and has no callback address to speak of.
- **`configJson` masking.** `convertToResponse(channel)` passes the blob through `maskSecrets()` before it
  leaves. Masking applies only to string values under `SECRET_CONFIG_KEYS = {appSecret, token,
  encodingAesKey, botToken, webhookUrl}`, in three shapes by length: `******` (≤4), `first1****last1` (≤8),
  `first3****last2` (the rest) — the same shape as `EnvVariableServiceImpl`'s mask. List and detail go
  through the same `convertToResponse`, so `/page` cannot yield plaintext either.

### 10.2 Write path

`keepStoredSecrets(incoming, stored)` makes the mask re-writable: the decision **does not pattern-match**,
it compares each submitted value against the value currently stored — reapplying the same `maskSecret()` to
the stored value and finding it equal to the submitted one counts as "unchanged" and the original is
written back; anything else is written as typed. A credential that itself contains asterisks therefore
remains editable. The create path has no stored value to compare against, so a mask submitted at create
time is stored literally (the channel then visibly fails to start, which beats silently keeping a value
nobody typed). `rewriteSecrets` returns its input untouched when no key was rewritten, so an edit that
never touched a credential does not reorder the blob.

The remaining write-side invariants:

| Check | Rule |
|------|------|
| `type` | must be a key of `MODES_BY_TYPE`; case-sensitive (the runtime's `ChannelType.fromCode` matches exactly, so `"DingTalk"` lands in the database and reports unsupported at runtime) |
| `communicationMode` | must be inside that type's runnable set: `feishu → {websocket, webhook}`, `dingtalk → {stream}`, `wecom → {websocket}`, `wechat → {long_polling}`, `http → {webhook}`. When unspecified the first element is taken (each type's recommended default). The check runs on create and on any update that either names a mode or changes the type, so editing feishu/webhook into dingtalk cannot leave a combination that never receives anything |
| `wechat` forced | personal WeChat supports long polling only, so an update rewrites the mode to `long_polling` whatever the request said |
| `configJson` | when non-empty it must be a JSON object and at most 20 000 characters (far below the 65 535 bytes of a `TEXT` column, so multi-byte names cannot push a character-legal value into a byte-illegal one). The exception message never echoes the payload, because it contains secrets |
| `status` | only 0/1 accepted; any other value would be filtered out permanently by `selectAutoStartChannels`' `status = 1` and could not be selected in the list page either |

### 10.3 Deleting a channel: release the runtime first, then soft-delete the row

The order inside `deleteChannel(id)` is the point of the design:

```
deleteChannel(id)
  ├─ getChannel(id)            (tenant mismatch = 404)
  ├─ if sessionId is not blank: SessionRuntimeReleaser.release(sessionId)
  │     └─ AgentRuntimeClient.clearSession(sessionId)
  │        failure → BizException (i18n error.session.runtime.release + the runtime's own text), delete aborts
  └─ channelMapper.deleteById(id)   → UPDATE channel SET active = 0
```

A `chn-{uuid}` conversation has no row in `session`; the runtime is the only place it exists and admin
cannot reach the state, plans and sandbox container it holds — `clearSession` is that door. A runtime that
answers "cannot release" means state keeps living under a name no page points at any more, which is worse
than a failure that can be retried later, so the refusal becomes an exception here instead of a flag that
may be ignored. Nothing is written on a refusal; retry once the runtime answers. The same invariant is
shared by three deletions: deleting a conversation in the admin page, in the mini-program, and deleting a
channel. The argument `release` needs is the sessionId the runtime recognises, never the row id.

The row itself is soft-deleted (`active = 0`), `selectAutoStartChannels`' `active = 1` keeps it out of the
auto-start set from then on, and the first branch of `reconcile()` stops the listener — documented
behaviour.

## 11. The callback entry point (webhook mode; Feishu only today)

`ChannelCallbackController` is mounted at `POST /api/channel/callback/{callbackKey}` and is the inbound
half of webhook mode. It is also the only class in this service that registers request mappings — all
channel CRUD lives on the admin side, and the runtime exposes this one entry plus the observation
endpoints under `/actuator`. It is deliberately thin: signature verification, decoding and the response body
belong to the adaptor, because the differences between platform contracts exceed what a controller can
absorb (`ChannelAdaptor.handleCallback`'s signature is that agreement).

Decision order and answers:

| Situation | HTTP |
|------|------|
| `selectByCallbackKey` finds no row | 404 `unknown callback key` |
| the lookup itself throws | 500 (no container page; one canned `channel lookup failed`) |
| channel `enabled != 1` or `status != 1` | 403 `channel is disabled` |
| `configJson` does not parse into a usable config | 400 `channel configuration is invalid` |
| the mode is not `webhook` (compared case-insensitively against `CallbackModes.WEBHOOK`) | 404 `channel does not use callback mode` — the listener owns this channel's inbound traffic and a callback would be double-handled |
| `adaptorRegistry` has no adaptor for that type (`get` throws `IllegalArgumentException`) | 404 `unsupported channel type`, plus the WARN `No adaptor registered for channel type {}`; the callback of an `http` channel lands here |
| an adaptor exists but `supportsCallback()` is `false` | 501 `this channel type has no HTTP callback; use long-connection mode`, and the WARN on that branch says the type `has no callback contract` and to `switch it to the long-connection mode` |
| body larger than 1 MB | 413 |
| the adaptor throws any exception | 400 |
| the adaptor returns ≥500 | always downgraded to 400 |

Why downgrade to 4xx: platforms read 5xx as "not delivered" and retry without limit, and the Feishu SDK
answers exactly 500 for an event it cannot decode or whose signature is wrong. A prober should also not be
able to tell "there is no channel here" from "there is a channel and it refused me".

**A Feishu webhook must configure `encodingAesKey`.** Feishu sends no signature header at all when there is
no Encrypt Key, so there is nothing to verify; and since this endpoint sits in the
`harnax.auth.skip-paths` exemption list, `FeishuAdaptor.handleCallback()` refuses with 403 rather than
accepting an anonymous event. Echoing the URL-verification challenge and the AES decryption are both done
by the official `EventDispatcher`, and event parsing shares one entry point with the long-connection mode,
so the two entries cannot drift apart.

The callback is asynchronous: the ack goes out immediately after verification and the agent turn is handed
to `ChannelTurnExecutor`. The platform's timeout is seconds; a turn is minutes.

## 12. Configuration

Everything lives in
`harnax-channel/harnax-channel-service/src/main/resources/application.yml` under the `channel` prefix.

| Key | Default | Purpose |
|----|--------|------|
| `router-api-key` / `CHANNEL_API_KEY` | empty | the channel → router API key; when empty it is obtained automatically per the next row |
| `auto-fetch-system-key` | `true` | exchange a SYSTEM key from admin's internal endpoint at startup |
| `sync.interval-ms` | 10000 | `channel` table polling and reconcile period |
| `sync.max-starts-per-cycle` | 5 | how many listeners one pass may start or restart; `<=0` is unlimited |
| `sync.restart-backoff-initial-ms` | 2000 | first retry delay for a dead listener |
| `sync.restart-backoff-max-ms` | 300000 | backoff cap, which is also the "counted as healthy" reset threshold |
| `sync.stale-heartbeat-ms` | 180000 | restart when `CONNECTED` but silent for this long; 0 disables |
| `lock.enabled` | `true` | whether the MySQL `GET_LOCK` single-instance mutex is used |
| `lock.name` | `harnax-channel-listeners` | lock name |
| `turn.pool-size` | 24 | inbound message handling threads |
| `turn.per-channel-concurrency` | 4 | maximum concurrent conversations per channel |
| `session.max-sessions` | 10000 | conversations kept in memory (LRU eviction) |
| `session.max-messages` | 500 | messages kept per conversation |
| `session.idle-ttl-minutes` | 120 | idle conversation eviction period; 0 disables |
| `monitor.health.enabled` | `true` | whether the connection health indicator is registered |
| `monitor.health.failure-grace-ms` | 60000 | how long `FAILED` persists before health turns `DOWN` |
| `proxy.connect-timeout-ms` | 5000 | router connect timeout |
| `proxy.response-timeout-ms` | 600000 in `application.yml`; `ChannelConfig`'s `@Value` fallback 120000 | batched response timeout: 10 minutes with this service's yml, 2 minutes on the fallback when the key is absent |
| `proxy.stream-idle-timeout-ms` | 180000 | SSE idle timeout; 0 disables |
| `router.breaker.enabled` | `true` | whether the circuit breaker is enabled |
| `router.breaker.failure-threshold` | 5 | consecutive failures before the breaker opens |
| `router.breaker.open-duration-ms` | 30000 | how long open before a half-open probe |
| `admin.url` / `ADMIN_SERVICE_URL` | `http://localhost:8080` | peer used to obtain the SYSTEM key |

`router.service.url` / `ROUTER_URL` is not under the `channel` prefix: it is the channel → router base
address.

The service itself: port 8083, `server.shutdown: graceful`,
`spring.lifecycle.timeout-per-shutdown-phase: 20s`. The datasource points at the `harnax_admin` database
with `spring.flyway.enabled: false` (migrations are admin's responsibility); MyBatis uses
`classpath*:mapper/*.xml` plus underscore-to-camel mapping and reads `ChannelMapper` from `harnax-entity`.
`management.endpoints.web.exposure.include: health,info,prometheus,metrics,channels` and
`probes.enabled: true`.

Two authentication keys sit under the `harnax.auth` prefix:

| Key | Value | Purpose |
|----|----|------|
| `harnax.auth.enabled` | `true` | unified internal authentication on; switching it off publishes all of `/api/**`, not just the callback |
| `harnax.auth.service-id` | `channel-<n>` (`SERVICE_ID`, default `channel-0`) | the label on internal tokens this service signs |
| `harnax.auth.skip-paths` | `/api/channel/callback` | platforms cannot present a service token, so the callback prefix must be exempt; authentication then rests on the platform signature |
| `harnax.auth.internal.shared-secret` | `HARNAX_AUTH_SECRET` | HMAC key for internal tokens; must equal admin's value |

At startup, `ChannelApiKeyInitializer` exchanges a SYSTEM key from admin's internal endpoint when
`router-api-key` is empty and `auto-fetch-system-key` is true: connect 3 s, read 5 s, at most 3 attempts
(1 s and 2 s apart). admin and channel-service come up together under compose, so admin being briefly
unreachable is normal; the exception thrown when no key can be obtained carries the attempt count.

## 13. Deployment and networking

- Image build: `harnax-deploy/Dockerfile.channel-service`; the compose service is `channel-service`, listening on
  8083 inside the container and published by `harnax-deploy/docker-compose.yml` as `28083:8083` — from the host
  the port is 28083, while container-to-container traffic and the nginx upstream keep 8083.
- The `location /api/channel/` block in `harnax-deploy/nginx.conf` forwards to `channel-service:8083`, which is
  what makes a public callback address usable; the callback path hits this block. The same file carries a
  longer `location /api/channel/webhook/` block (long timeouts, buffering off) while no mapping in the
  service answers under `/api/channel/webhook/`, so that block receives no traffic.
- The `callbackUrl = $baseUrl/api/channel/callback/{callbackKey}` that admin hands out when a channel is
  created is served by `ChannelCallbackController`, and **only Feishu has a matching HTTP contract**
  (`supportsCallback()` returns `true` for Feishu alone). The bot shapes of DingTalk, WeCom and WeChat have
  no equivalent event callback, and giving them an empty endpoint that accepts unsigned requests would be
  worse than not having one.
- Long-connection modes need **outbound** reachability only, with no public entry point and no callback
  address, and remain the recommended way to attach a channel; Feishu webhook is for deployments that
  cannot open outbound access.
- The "type → usable modes" table exists in three places: the front-end
  `harnax-webui/src/pages/channel/components/channelModes.ts` (the dropdown filters by type), admin's
  `ChannelServiceImpl.MODES_BY_TYPE` (write validation), and the runtime's per-adaptor
  `supportsCallback()` plus its actual transport (`ChannelBootstrapRunner.startChannel` records a start
  failure for webhook without a contract). The three are written in different languages with no shared
  module, so changing one requires the other two to follow; only the runtime can actually check at read
  time.
- The channel service holds no object-storage credentials: files are always fetched through router's
  workspace download endpoint.

## 14. Explicitly out of scope and boundaries

| Boundary | Current state |
|------|------|
| Channel-side file upload endpoint | Not accepted. `supportsFileDelivery()` is `false` for Feishu / DingTalk / WeCom and users collect files in the WebUI. Adopting it means changing `supportsFileDelivery()` and `sendFile()` together |
| Splitting a channel conversation per user | Not done. One channel row is one stretch of memory; create more rows to isolate |
| Streaming output | Protocol and implementation are both present; all four platforms keep `supportsStreamingOutput()` at `false`. Enabling it first requires deciding which platform's which API carries the deltas |
| Chunking on WeCom's primary reply path | `aibot_respond_msg` uses whole-stream replacement semantics, and whether the 2000 limit applies to it cannot be decided from the code; chunked sending needs successive frames plus a final finish, which is protocol behaviour. Today an oversized payload returns a platform errcode and records a WARN plus a failure metric |
| The `COMPACT` command | Answers "not implemented" |
| Liveness probing in callback mode | A webhook channel has no connection to probe, so `reconcileRunning` clears the backoff state directly; "no listener" does not count as `missingListener` |
| Channel runtime state in the WebUI | admin neither proxies `/actuator/channels` nor polls it. The status column in the list is the DB's `status` (configured intent), not a connection fact; connection facts live on the actuator endpoint and the health indicator |
| WeChat login state | Written into `configJson` by admin's WeChat QR login and read-only for the channel side; changing the type to another platform leaves those keys in place — they are not managed credential keys |
| The `http` channel type | Kept in the type set with no adaptor. admin's `MODES_BY_TYPE` gives it `webhook` alone, so mode validation accepts it and the response still carries a `callbackUrl`; startup lands in `ChannelBootstrapRunner`'s webhook branch and `lastError` records `webhook callback mode is not implemented for http; use this platform's long-connection mode` — and the long-connection mode that sentence points at has no legal value for `http`. The callback endpoint answers 404 `unsupported channel type` because the type resolves to no adaptor |

## 15. Troubleshooting quick reference

| Symptom | Look at first | How to read the answer |
|------|------|-----------|
| The bot does not react at all | `receivedCount` and `lastActivityAt` on `/actuator/channels` | still 0 with state `CONNECTED` → nothing reached the handler; check that channel's `lastError`. Growing but no reply → keep going down to the router side |
| State `CONNECTED` but silent | the "expired listener" text in debug logs, then the event counter logged at `stop()` | a zero count means the connection is received but messages are not — an ownership-guard class of problem |
| A channel stays `FAILED` and `lastError` reads `webhook callback mode is not implemented for <type>; use this platform's long-connection mode` | that channel's `communicationMode` and the legal modes of its type | for DingTalk / WeCom / WeChat follow the hint and change the mode (`stream` / `websocket` / `long_polling`); `http` has `webhook` as its only entry in `MODES_BY_TYPE`, so there is no long-connection mode to switch to and that hint offers it no way out |
| Feishu webhook returns 403 | whether that channel has an `encodingAesKey` | no Encrypt Key means no verifiable signature, so the endpoint refuses instead of accepting an anonymous event |
| Feishu webhook configured but no conversation | whether the platform-side URL verification passed, whether `/api/channel/callback/{callbackKey}` is reachable | verification usually fails because `UnifiedAuthFilter` intercepted it (`skip-paths` missing the prefix) or nginx does not route here |
| Callback answers 404 although the channel exists | that channel's `communicationMode`, then its type | a non-webhook channel is served by its listener and answers `channel does not use callback mode`; a webhook channel whose type resolves to no adaptor (`http`) answers `unsupported channel type` |
| Every channel errors at once | the router breaker snapshot on `/actuator/channels` | the breaker is open; check whether router restarted or the network partitioned |
| Only one replica receives messages, the other idles | `listenerLock.held` | expected: `GET_LOCK` is mutually exclusive and non-holding replicas idle |
| Only some channels run and the log says the lock was released | whether the two replicas point at different MySQL instances / whether the pool reuses one connection | the lock lives on a session; changing the connection means losing it |
| A channel restarts every so often | `sync.stale-heartbeat-ms` and that channel's `statusAgeMs` | the half-dead rule is firing. If the restart interval equals the reconcile interval, the backoff never grew — check whether `lastError` is new on every pass |
| Replies are cut short | message length vs the platform chunk limit | every transport path chunks; if it is still truncated the platform imposes a smaller per-message limit |
| Generated files never arrive | the `[Files]` prefix in the log | for Feishu / DingTalk / WeCom this is expected (capability bit `false`, and the notice says to use the Web UI); for WeChat look for file-content resolution failures |
| Channels drop for a few minutes after a deploy | whether `@PreDestroy` graceful shutdown ran | look for the "Stopping N channel listener(s)" line |
| A dangerous tool ran without confirmation | the permission mode configured for that channel in admin | the runtime does not change the permission mode based on the `chn-` prefix; if it still happened, check `permissionMode` and whether an ASK rule exists at all |
| A channel edit had no effect | whether the eight fields covered by `configFingerprint` actually changed | changing `description` alone does not trigger a restart, deliberately |
| Deleting a channel reports "the runtime cannot release" | whether admin's `clearSession` call reaches agent-service | a refusal aborts the delete, the row is untouched, retry |

## 16. Key file index

| Concern | File |
|--------|------|
| Capability bits and callback contract | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelAdaptor.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelCallbackResult.kt` |
| Transport abstraction | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/adaptor/ChannelCommunicationMode.kt` |
| Type and configuration model | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/config/ChannelType.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/config/ChannelSpec.kt` |
| Reconciliation and lifecycle | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelBootstrapRunner.kt` |
| Single-instance mutex | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/bootstrap/ChannelListenerLockGuard.kt` |
| Platform callback entry | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/endpoint/ChannelCallbackController.kt` |
| Router calls and breaker | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterClient.kt`, `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterCircuitBreaker.kt` |
| Platform → Agent orchestration | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ChannelChatService.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/service/ReplyMarkers.kt` |
| Agent-side adaptor and wiring | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/adaptor/RouterAgentAdaptor.kt`, `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/config/ChannelConfig.kt` |
| Turn throttling | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/dispatch/ChannelTurnExecutor.kt` |
| Dedup / chunking / backoff | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/MessageDeduplicator.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/TextChunker.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/util/ReconnectBackoff.kt` |
| State machine and metrics | `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/ChannelConnectionState.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/ChannelConnectionTracker.kt`, `harnax-channel/harnax-channel-sdk/src/main/kotlin/com/agnetix/harnax/channel/sdk/monitor/MicrometerChannelMetricsSink.kt` |
| Runtime view and health | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/monitor/ChannelRuntimeEndpoint.kt`, `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/monitor/ChannelRuntimeMonitor.kt`, `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/health/ChannelConnectionHealthIndicator.kt` |
| Feishu transport and callback | `harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWebSocketMode.kt`, `harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuWsTransport.kt`, `harnax-channel/harnax-channel-feishu/src/main/kotlin/com/agnetix/harnax/channel/feishu/FeishuAdaptor.kt` |
| DingTalk transport | `harnax-channel/harnax-channel-dingtalk/src/main/kotlin/com/agnetix/harnax/channel/dingtalk/DingtalkStreamMode.kt` |
| WeCom transport and frame protocol | `harnax-channel/harnax-channel-wecom/src/main/kotlin/com/agnetix/harnax/channel/wecom/WecomWebSocketMode.kt`, `harnax-channel/harnax-channel-wecom/src/main/kotlin/com/agnetix/harnax/channel/wecom/WecomFrames.kt` |
| WeChat transport | `harnax-channel/harnax-channel-wechat/src/main/kotlin/com/agnetix/harnax/channel/wechat/WechatLongPollingMode.kt`, `harnax-channel/harnax-channel-wechat/src/main/kotlin/com/agnetix/harnax/channel/wechat/WechatAdaptor.kt` |
| Entity → Spec | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/mapper/ChannelEntityConverter.kt` |
| Channel conversation cache | `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/session/InMemoryChannelSessionManager.kt` |
| Management-plane rules | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ChannelServiceImpl.kt`, `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ChannelResponse.kt` |
| `channel` table statements | `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` (the `channel` create statement and its `tenant_id` column are both written in this one baseline), `harnax-entity/src/main/resources/mapper/ChannelMapper.xml`, `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Channel.kt` |
| Runtime release on deletion | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionRuntimeReleaser.kt` |
| MCP identity for channel conversations | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/McpSessionOwnerResolver.kt` |
| Attachment shape for channel conversations | `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt` |
| Front-end mode filtering | `harnax-webui/src/pages/channel/components/channelModes.ts` |
| Ownership decidability (router side) | `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt`, `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/SessionAccessGuard.kt` |
| Deployment | `harnax-deploy/Dockerfile.channel-service`, `harnax-deploy/docker-compose.yml`, `harnax-deploy/nginx.conf` |
