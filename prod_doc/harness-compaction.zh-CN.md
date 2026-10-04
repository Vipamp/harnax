# Harnax 上下文压缩与占用比例方案

## 0. 口径与取证基线

版本事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文档的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | 上游检出 `~/code/opensource/agentscope-java/`，分支 `release/2.0.4`，HEAD `3c1c29c0`；其 `pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>` |
| 2.0.4 事实来源 | 上述检出的 `agentscope-harness/src/main/java/` 与 `agentscope-core/src/main/java/`，本篇全部上游锚点都取这里 | 与基线同一份代码，不再另解 sources jar |
| 2.0.4 构件可得性 | 尚未发版：上游最新 tag 是 `v2.0.3`，`release/2.0.4` 仍叫 `2.0.4-SNAPSHOT`。要用它就得先把上游检出 `mvn install` 进本地仓库（或等发版），否则本篇引用的 API 与行号都还没进 harnax 的 classpath | 上述检出的 tag 列表与同一 `pom.xml:30` |
| harnax 当前依赖 | 仍钉在 **2.0.2**；本方案落地前要先把它改到 2.0.4，这是前置动作不是背景 | 仓库根 `pom.xml:39`（`<agent-scope.version>2.0.2</agent-scope.version>`） |
| harnax 主数据源 | `harnax_admin` 库 | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 表在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15` |

锚点约定：harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，`TeamHistoryReplay.kt` 省略同文件的上一级 `.../agent/service/runner/`；`SessionConfig.kt` / `MysqlAgentStateStore.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/`，`TokenStatsMiddleware.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/`，`MessageLogConverter.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/`。上游侧省略 `io.agentscope.` 前缀。三个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本篇提到的**一律指 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt`**；iOS 的 `AgentRequest.swift` 在 `harnax-ios/.build/` 与 `harnax-ios/tmp/` 下有多份快照副本，本篇只认 `harnax-ios/Sources/HarnaxCore/Contract/AgentRequest.swift`；`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ModelHelper.kt` 才是真正调上游 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()` 的地方（`:52,75`），它与 agent-service 下组装 harnax 自己的 `ChatModelConfig` 的 `ChatModelConfigAdaptorImpl.kt:61,95` 是模型构造链上的两处，本篇点到模型构造时按全路径区分。凡"现在跑成什么样"的断言以 2.0.4 源码为准。

取证方式：源码静态阅读 + 配置比对，未启动任何 harnax 服务。标 **未验** 的条目需实跑或产物级核对后才能当事实用。

---

## 1. 目标与验收

三条，都可判定：

1. **`/compact` 是真压缩。** 会话在跑过若干轮之后发 `/compact`，模型侧上下文被摘要替换，`token_stats` 里下一轮的 `input_token` 相应下降；发一条 `/compact` 就多付一次摘要用的模型调用，除此之外不多付。
2. **页面永远看到全量原文气泡。** 无论压缩过一次还是十次，`GET /api/agent/chat/history/{sessionId}` 返回的气泡序列与压缩前逐字一致，且不出现任何"压缩摘要"气泡。这是本方案的主断言。
3. **占用比例可查。** `GET /api/agent/context/{sessionId}` 一次返回估算占用、真实占用、窗口值与其来源，调用方能据此判断"还要不要手动压"以及"自动压缩还差多少触发"。

---

## 2. 三条事实决定方案形状

**事实一：压缩本来就在跑，harnax 从没配过它。**
`HarnessAgent.Builder` 的字段初值是 `compactionConfig = CompactionConfig.builder().build()`、`disableCompaction = false`（`HarnessAgent.java:1227,1230`），`CompactionMiddleware` 在「未关压缩 ∧ `compactionConfig` 非空 ∧ 摘要模型（config 自带否则取 builder 的 model）非空」三条同时成立时装上，默认装配下三条全部成立（`HarnessAgent.java:2600-2609`）。默认档：`triggerMessages=50`、`triggerTokens=0`（动态档 = 模型窗口 − `reserved=20_000`，模型报不出窗口时回落 `FALLBACK_TRIGGER_TOKENS=160_000`）、`keepMessages=20`、`keepTokens=-1`（动态档 = `min(8_000, max(2_000, 可用量 × 0.25))`）、`flushBeforeCompact=true`、`offloadBeforeCompact=true`（`CompactionConfig.java:273-286`、`:67`）。窗口值由 core 按模型名前缀推断（`ModelContextWindows.java:151`），harnax 从未显式设置。
所以"支持压缩功能"不是引入一个新机制，而是**给一个已在跑的机制补上受控的入口和可见度**。

**事实二：harnax 的用户可见历史就是模型上下文本身。**
`HarnessAgentLauncher.kt:786-794` 的 `loadSessionMessages()` 直接返回 `agentState.context`；读它的只有两处 —— `DefaultAgentRunner.kt:338-341` 的 `/chat/history` 和 `TeamHistoryReplay.kt:44` 的成员气泡合并。没有第二份存储。
后果：压缩覆写 `context` 等于把用户的聊天记录一起裁掉。**这条不改，第 1 节第 2 条就无法成立，所以历史分离不是可选优化，是压缩落地的前置。**

**事实三：`/compact` 的命令链路已经全通，只差服务端实现。**
`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:67,90,199` 已把 `/compact 500` 解析为 `CommandType.COMPACT` + `args="500"`；`AgentController.kt:76-85` 的 `POST /api/agent/command` 已在收；webui `harnax-webui/src/pages/session/components/ChatWindow.tsx:950` 有关键词到命令的映射；iOS 侧只有契约枚举 `harnax-ios/Sources/HarnaxCore/Contract/AgentRequest.swift:9`，没有发它的入口。`CommandResponse`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13`，`result: Any?` 在 `:16`）带一个 `result` 字段可直接承载结构化返回。
`DefaultAgentRunner.kt:245-249` 是那个 stub，回 `Compact not yet implemented`。**命令形态不需要新造，客户端也不需要改。**

按需压缩所需的上游 API 在 2.0.4 全是 public，装配层就能做完，不改上游：`ConversationCompactor.compactIfNeeded(...)`（签名 `ConversationCompactor.java:92-96`）、`CompactionConfig`、`TokenCounterUtil.calculateToken(List<Msg>)`（`TokenCounterUtil.java:73`）、`HarnessAgent.getDelegate()/getModel()/getWorkspaceManager()/getStateStore()`（`HarnessAgent.java:492,496,246,504`）、`ReActAgent.getAgentState(userId, sessionId)` 与 `ReActAgent.saveAgentState(userId, sessionId)`（`ReActAgent.java:4447,4691`）。上游自己的溢出兜底 `forceCompactAndRetry`（`HarnessAgent.java:1058-1101`）就是同一个配方，可以照着写。

---

## 3. 基线钉在 2.0.4：四条会改变结论的事实与一条前置动作

| # | 2.0.4 的事实（锚点） | 对本方案的影响 |
|---|---|---|
| 1 | **会话 transcript 中间件默认开启。** builder 初值 `disableTranscript = false`（`HarnessAgent.java:1253`），安装块 `:2543-2561`：`transcriptStore` 未注入时，有 filesystem 就走 `ObjectStoreTranscriptStore`（`:2547-2549`），否则走 `FilesystemTranscriptStore(<workspace>/.agentscope/transcripts)`（`:2550-2556`）。中间件在每次 agent 调用结束时把 `state.getContext()` 全量交给 `SessionTranscriptWriter.appendMessages`（`TranscriptMiddleware.java:86,101-104`） | 升到 2.0.4 会**凭空多出一条会话历史写通道**。harnax 三条装配分支里有两条带 filesystem：沙箱分支（`HarnessAgentLauncher.kt:562`）与非沙箱且配了 MinIO 的非 lead 分支（`:583-592`），这两条走 `ObjectStoreTranscriptStore` —— 它只是包住 `WorkspaceManager` 当前暴露的那把 `AbstractFilesystem`（`ObjectStoreTranscriptStore.java:47-51`、`WorkspaceManager.java:194`），所以前者写进沙箱文件系统、后者写进 MinIO 对象存储，键形如 `{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl`（`TranscriptStore.java:30-33`）；lead 分支与没配 MinIO 的分支没有 filesystem，落到宿主工作区的 `.agentscope/transcripts` 目录。它当不了第 1 节第 2 条要的历史源，两处硬伤：工具入参截到 500 字符、工具结果截到 1000 字符且这两个常量是 `public static final`、无配置口（`SessionTranscriptWriter.java:62,65`）；它每轮读的 live context 正是自动压缩就地覆写后的那份（`CompactionMiddleware.java:132-134`），压过的会话它记的也是压后的形状。**所以本篇要求在装配时显式 `disableTranscript()`（`HarnessAgent.java:2252`）**：留着一个既截断、又跟随压缩、又没有读回入口、清会话也不在删除清单里的副本只会误导运维 |
| 2 | token 估算开始计入思考内容：`TokenCounterUtil` 有 `ThinkingBlock` 分支（`:21` 引入、`:132` 判定），按与正文同一把字符尺折算（`:225`） | 第 6 节的 `estimatedTokens` 在带思考链的模型上比不计思考时更高，于是 `ratio` 更大、自动压缩更早触发。这条直接进第 10 节第 1 条的偏差实测范围 —— 中文 + 思考链的偏差方向要一起测 |
| 3 | 状态写入统一套了一层版本比对壳子 `persistAgentStateCas`（`ReActAgent.java:535-566`），第 5 节写回用的 `saveAgentState(userId, sessionId)` 走它（调用点 `:4699`） | 对 harnax **是空转**：`MysqlAgentStateStore` 只覆写了 `save`/`get`/`exists`/`delete`/`listSessionIds`（`MysqlAgentStateStore.kt:74,94,115,142,176,191,204,218`），没有覆写 `supportsVersioning()`，接口默认假（`AgentStateStore.java:87`），于是走 `ReActAgent.java:542` 分支直接 `stateStore.save(...)`（`:563`）—— 仍是无条件覆盖。结论：升到 2.0.4 既不需要动这个 store，也不带来并发保护，第 5 节的并发闸仍是唯一防线 |
| 4 | 新增 `HarnessAgent.getCompactionHook()`（`:264-266`），能取回装配好的中间件 | 本篇**不用它**：命令路径自己 `new ConversationCompactor(model, flushManager)`（构造器 `ConversationCompactor.java:70`），与自动路径实例无关。记此以免被误当作必需入口 |

**一条前置动作**：`<agent-scope.version>` 从 2.0.2 改到 2.0.4，并按第 0 节"构件可得性"那行先把上游检出装进本地仓库。这一步没做完，第 7 节任何一条改动都编不过 —— `disableTranscript()` 在 2.0.2 的 builder 里根本不存在。

---

## 4. 全量历史与模型上下文分离

做法：**逐条归档会话消息到一张只增不改的表，历史读路径改用它；模型上下文仍由 `AgentState.context` 承担，压缩随便它怎么覆写。**

新增表 `session_message`，与 `agent_state` 同库（`agentscope` 会话库），由写入方在初始化时 `CREATE TABLE IF NOT EXISTS` 自建 —— 沿用 `MysqlAgentStateStore.kt:51-68` 的既有做法，不进 admin 的 Flyway 基线，因此这张表不需要清库重建。

```sql
CREATE TABLE IF NOT EXISTS session_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    msg_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    msg_name VARCHAR(128) NULL,
    json_value LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_msg (session_id, msg_id),
    KEY idx_session_order (session_id, id)
)
```

| 设计点 | 取定 | 理由 |
|---|---|---|
| 唯一键 | `(session_id, msg_id)`，`msg_id` 取 `Msg.id` | core 的 `Msg` 自带 UUID 且随 JSON 序列化（`Msg.java:113,139`），同一条消息跨轮次、跨进程都是同一个 id |
| 冲突处理 | `ON DUPLICATE KEY UPDATE json_value = VALUES(json_value)` | 上游会用同一个 id 重建消息对象 —— `Msg.java:668,687,704` 的 `withGenerateReason`/`withContent`/`withMetadata` 各把 `this.id` 原样交回构造器（`:672,689,706`），后到的版本内容更全，取后者 |
| 写点 | 每轮结束时读 live context 全量，逐条幂等落档 | 只增不改 + 每轮全量补写，使自动压缩与手动压缩**都不需要挂钩子**。中间件顺序决定 harnax 拿不到"压缩覆写之前那一刻"的上下文（`CompactionMiddleware` 在 `onReasoning` 内部就地把裁掉的 `input` 交给下游，`CompactionMiddleware.java:132-147`），所以"压缩前抓一份"这条路在自动压缩上走不通 |
| 排序 | 本地自增 `id` | 会话库连接参数是 `serverTimezone=UTC`（`SessionConfig.kt:44`），主库是 `Asia/Shanghai`，跨库时间列不可比。展示用的时间戳来自 `Msg.timestamp`，`MessageLogConverter.kt:44-66` 今天就在读它 |
| 序列化 | `JsonUtils.getJsonCodec().toJson(msg)`，读回 `Msg` | 与 `MysqlAgentStateStore.kt:77` 同一把 codec，工具调用/工具结果/图片块的原样形状因此保住，`MessageLogConverter` 不动，客户端契约不动 |
| **排除项** | 跳过 `msg.name == ConversationCompactor.SUMMARY_MSG_NAME`（常量值 `__compaction_summary__`，`ConversationCompactor.java:65`） | 摘要消息由 `buildSummaryMessage`（`:454`）以 `.role(MsgRole.USER)` 构造（`:471-472`），收进档就会在页面上多出一坨用户气泡装摘要全文 —— 直接违背第 1 节第 2 条。框架的合成提醒（`Msg.java:102` 的 `METADATA_SYNTHETIC`）不写入 context —— `TaskReminderMiddleware.java:45-48` 的类注释原文写明它"只临时追加进推理输入，从不写进 `AgentState.context`，因此从不被持久化、压缩或召回"，所以不需要再判 |

读路径：`loadSessionMessages()` 先查 `session_message`；该会话**零行**时回退现有 `agentState.context`，再回退 legacy `memory_messages`。回退分支保留是因为上线之前建的老会话在发新消息之前档案是空的，不回退就翻不到任何东西。

连带改动：

- 成员子会话自动覆盖 —— `TeamHistoryReplay.kt:44` 走的就是这个方法，成员的气泡也随之全量。
- 删会话要连带删档：`DefaultAgentRunner.kt:444-455` 的 `clearSession` 已对成员子会话递归，跟着删即可。
- 用户桶是这条链路的隐藏不变量：压缩写回、归档读写、历史读路径必须落在同一个 user 桶。三侧都把空值归一成 `__anon__`（`MysqlAgentStateStore.kt:70,247`、`ReActAgent.java:394-399`），而 harnax 唯一的 wrapper 构造点不传 userId（`HarnessAgentLauncher.kt:675-690`，字段默认 `null`，见 `HarnessAgentWrapper.kt:77`），历史读路径则写死 `""`（`:788`）—— 今天两边同桶。任何一方将来单独开始传真实用户 id，写死的 `""` 就会与 live slot 分叉，表现成"压了但历史读回旧的"。
- 归档写失败不影响回答，但要重试一次并记 warn。理由：这条数据不可再生（与 `TokenStatsMiddleware.kt:68-78` 那种"少一行统计"不同）；而"每轮全量补写"的写法使下一轮自然把漏掉的补回来，只有在那之前被压缩裁走的消息才真丢。

**一个必须写明的既有限制**：自动压缩已经默认在跑（第 2 节事实一），本次上线之前被它裁掉的早期消息已经不在 `context` 里，也不在任何地方，**无法追回**。上线后新产生的压缩才保证不丢。

---

## 5. `/compact` 命令压缩

`DefaultAgentRunner.kt:245-249` 的 stub 换成真实现。runner 那一侧只做前置判定与结果映射，第 2~4 步（取 live `AgentState`、调压缩、成功才覆写并保存）落在 harnax-harness-core 的 `ContextCompactionService.compact(...)`，经 `HarnessAgentWrapper.compactManually(keepTokens)` 暴露 —— agent、sessionId、user 桶三个键一律取自 wrapper 自身，这正是第 4 节末条不变量的守法：调用方传不进第二个 userId。流程五步：

1. 前置判定（见下表）。
2. `val delegate = harnessAgent.delegate`；取不到 live `AgentState` 就拒。
3. `ConversationCompactor(model, MemoryFlushManager(workspaceManager, model)).compactIfNeeded(rc, context, forceConfig, agentId, sessionId)`（构造器 `ConversationCompactor.java:70`、`MemoryFlushManager.java:95`），`rc` 照 `HarnessAgentWrapper.kt:301` 的形状用 `RuntimeContext.builder().sessionId(sessionId).userId(userId ?: "")` 构。
4. 结果判空/判失败串 → 成功才覆写 `state.contextMutable()`（`AgentState.java:176`）并 `delegate.saveAgentState(userId, sessionId)`（`ReActAgent.java:4691-4700`，走 `agent_state` 键回到 `MysqlAgentStateStore`）。它只在 `stateCache` 命中该 slot 时才写库（`:4696-4699`），而第 2 步的 `getAgentState(userId, sessionId)` 自己就用 `computeIfAbsent` 把 slot 建出来（`:4449-4462`），所以按本流程"先取 live state 再保存"两步连着走一定能落库。2.0.4 把这一写包进了 `persistAgentStateCas`（调用点 `:4699`），但对 harnax 仍是无条件覆盖，理由见第 3 节第 3 行。
5. 返回 `CommandResponse.success(sessionId, message, result = {beforeTokens, afterTokens, beforeMessages, afterMessages, window, windowSource})`。

| 判定点 | 取定 |
|---|---|
| 压缩档位 | `CompactionConfig.builder().triggerMessages(1).flushBeforeCompact(false).offloadBeforeCompact(false).build()`。`triggerMessages(1)` 与上游溢出兜底逐字同构（`HarnessAgent.java:1071`），含义是"用户既然发了命令就别拿阈值挡我"；真正的"值不值得压"由 cutoff 判定把关 —— 消息数不足以留出 `keepMessages=20` 的尾部时上游直接返回 `Optional.empty()`（`ConversationCompactor.java:112,118`），我们据此回"当前会话还短，没有可压缩的内容" |
| `args` 语义 | `/compact <N>` → `keepTokens = N`（保留尾部约 N token）。缺省走动态档。非数字或 ≤0 → 忽略并照默认档，`message` 里说明被忽略 |
| flush / offload | **两条都关**。代码上确认可关：两步各由 `config.isFlushBeforeCompact()`（`ConversationCompactor.java:136`）/ `isOffloadBeforeCompact()`（`:156`）把守，关了就是一句空 `Mono`，不付 LLM 调用也不落文件。取舍：开着能留下 `sessions/<id>.jsonl` 原文副本，但那份文件既不在 harnax 的产物可见范围也不在清会话的删除清单里，lead 侧还落在宿主 `user.dir` 子树，用户和运维都取不到；同时 flush 会多付一次模型调用、写出的日报没有任何读回入口。所以手动压缩只出摘要那一次调用。第 3 节第 1 行的 `disableTranscript()` 与这条同源：默认路径留下的文件产物一律按"取不到就不算收益"处理 |
| 并发闸 | 该会话有在跑的流或阻塞调用即拒（`activeStreams` 在 `DefaultAgentRunner.kt:76`、`activeCalls` 在 `:85`，同一对判据的现有用法见 `:99`）。mid-turn 覆写 `context` 会和轮次结束时的 `saveStateToSession`（`ReActAgent.java:475`）抢同一个对象 |
| 会话范围 | 只压 root 会话。`task-` 前缀直接拒（同 `:952` 的先例）；team 成员各自的子会话不在本轮 —— 成员的上下文由成员自己跑完时的自动压缩负责 |
| 摘要失败 | **不落库，直接回 failure。** 上游把摘要调用的异常吞成字符串 `"(Summarization failed: …)"` / `"(Summary unavailable)"` 并照常返回一个"压缩结果"（`ConversationCompactor.java:375,382`）。自动路径下这是可接受的降级，手动路径下等于用一坨错误文本把模型上下文换掉、且因为第 4 节的分离用户看不见、下一次轮就照着它答 —— 判据是结果首条 `name == __compaction_summary__` 且正文**包含** `(Summarization failed` 或 `(Summary unavailable)`。取"包含"而非"开头"是因为标记并不写在开头：`buildSummaryMessage` 先拼固定引导语 `"Here is a summary of the conversation to date:\n\n"`（`ConversationCompactor.java:467`），标记只会落在它后面；而"包含"带来的唯一代价是把一句真提到该字样的成功摘要也判为失败，这个方向上误判便宜（命令重试一次、上下文原样保留），反向漏判才贵 |
| 无缓存 agent | 照 `getOrCreateAgent` 正常重建（`:757-762`），压缩不必是"活跃会话"专属 |
| 阻塞与超时 | 命令这条链全程同步返回 `ResultVo<CommandResponse>`（router `AgentProxyController.kt:105-113`、agent-service `AgentController.kt:76-85`），而 `compactIfNeeded` 回的是 `Mono`，所以实现就地 `block()` 等那一次摘要调用。够用：router 对 JSON 代理的读超时 600s（`harnax-session-router/src/main/resources/application.yml:103`）、webflux `request-timeout` 1800s（同文件 `:22`），一次摘要远在其内，与 `HarnessConfig.turnTimeoutSeconds = 300`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt:26`）同一量级。不为它另开异步通道 |
| 走 store 兜底副本 | 禁止。`HarnessAgentWrapper.kt:268-289` 的 `getLiveAgentState()` 在 delegate 缺席时会返回从库里反序列化出来的副本，改它再 `saveAgentState` 会静默 no-op（只保存 cache 里已有的 slot）。压缩这条路径只认 delegate |

---

## 6. 上下文占用比例

分子两个口径同时给，因为它们回答的不是一个问题：

| 字段 | 来源 | 口径说明 |
|---|---|---|
| `estimatedTokens` | `TokenCounterUtil.calculateToken(context)` | 上游那把尺：字符数 / 2.5 + 每条消息与每个工具块的结构开销（`TokenCounterUtil.java:49-58` 的常量与 `:73` 的方法），思考内容按正文同一把尺折算（`:132`）。**与自动压缩的触发判据完全同源**，所以它回答"压缩会不会触发" |
| `lastCallInputTokens` | `token_stats.input_token` 该会话最近一行 | 账单真值，由 `TokenStatsMiddleware.kt:41-67` 每次模型调用写一行。**它含系统提示与工具清单而 `context` 不含，所以必然比估算大 —— 这是口径差不是 bug**；且它反映上一轮，压缩之后要到下一轮才降 |
| `contextWindow` | 三级回退：模型域新列 `model.context_window` → 上游 `getContextWindowSize()`（`ChatModelBase.java:38-40`；builder 没给值时由 `ModelContextWindows.lookup` 按模型名做最长前缀匹配，未命中返回 0，`ModelContextWindows.java:151`）→ `160_000` | `windowSource` 取 `MODEL_FIELD` / `UPSTREAM_TABLE` / `FALLBACK`，让调用方知道这个分母是配的还是猜的 |
| `ratio` | `estimatedTokens / contextWindow` | 展示用 |
| `triggerTokens` | 用**模型自己报的窗口** `model.getContextWindowSize()` 走 `CompactionMiddleware.java:164-190` 那段算法：>0 时 `窗口 - reserved(20_000)`，该值 ≤0 时上游钳成 `max(1, 窗口/2)`；窗口报不出（≤0）时取 `160_000`。另带 `triggerMessages = 50` | "自动压缩还差多少兜底"。这里刻意不用上一行的三级回退值当被减数：中间件只看得到模型自己报的数，两者一旦分叉，三级都拿不到时就会报出一个 `160_000 - 20_000 = 140_000` 而实际兜底是 `160_000` —— 报错的阈值比报不了更糟 |
| `messageCount` | `context.size` | 与 `triggerMessages` 同判据 |

新增读接口 `GET /api/agent/context/{sessionId}`，挂在 `AgentController.kt`（同类已有 `:104` 的 `/chat/history/{sessionId}`），鉴权形状照它：`@InternalOnly` + sessionId 作用域、不带租户谓词 —— 这是既有先例，照用并在此标明。注意 `@InternalOnly` 打在类上（`:32`，同处 `@RequestMapping("/api/agent")` 在 `:31`），新方法挂在同一个 controller 里就自动继承，不需要逐个方法标注。真实 token 那条查询因此只按 `session_id` 过滤。响应体沿用 `ResultVo`。

配套改动：

- `TokenStatsMapper` 今天只有 insert 与租户维聚合读（`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:21-27`），要加一条"按 sessionId 取最近一行 `input_token`"的 select。agent-service 的主数据源就是 `harnax_admin`（`application.yml:10`），就地能读，不需要经 admin。
- session-router 要加一条绑定转发。会话的上下文在哪个实例上，只有 router 知道；`SessionRouterService.kt:365` 已有 `loadHistory` 的同款调用，照它加。

`model.context_window` 的落法：

| 位置 | 改动 |
|---|---|
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:335`（`model` 表） | 加一列 `context_window int DEFAULT NULL COMMENT 'Model context window in tokens'` |
| `harnax-entity/src/test/resources/schema-test.sql` | 同一列逐字跟上（它是 admin 基线的逐字副本） |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Model.kt:16-81`、同目录 `dto/ModelConfigDto.kt:10-43`、同目录 `dto/AgentSpecInfoResponse.kt:25-92` | 各加一个可空 `contextWindow` 字段 |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt:61,95` | 把窗口值透传进 `ChatModelConfig`；模型对象最终由 `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ModelHelper.kt:52,75` 走上游 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()` 构造，窗口值要么进那个 builder，要么走上方字段表里 `contextWindow` 的三级回退直接读列 |
| admin 模型表单 | 一个可选数字输入框（单位：token） |
| 已部署环境 | 基线折进 V1 意味着**清库重建**；不想清库的话就改出前向增量 `V2__model_context_window.sql`。两条都写得出来，默认走清库重建 |

**本轮不改自动压缩的默认档**（不显式钉 `triggerTokens`、不动 `flushBeforeCompact` 在自动路径上的取值），也不动 `disableMemoryTools()` / `disableToolsConfig()`。它们与压缩共用配置对象，混在一批里改会让"命令压缩上线"这件事的回归面变得没法判定。

---

## 7. 改动清单

按模块，文件级：

- **仓库根**：`pom.xml:39` 的 `<agent-scope.version>` 改 2.0.4（第 3 节的前置动作；上游检出需先 `mvn install`）。
- **harnax-entity**：`Model.kt` 加列映射；`ModelConfigDto`/`AgentSpecInfoResponse` 加 `contextWindow`；`TokenStatsMapper.kt` + `.xml` 加一条按 sessionId 的最新 `input_token` select；`schema-test.sql` 跟基线。
- **harnax-admin**：`V1__init_schema.sql` 加列；模型 CRUD 的校验与表单加一个可选字段。
- **harnax-harness-core**：`agent/session/` 新增 `MysqlSessionMessageStore`（自建表、幂等写、按会话读、按会话删，纯 JDBC，照 `MysqlAgentStateStore.kt` 的形状）；`HarnessAgentLauncher.kt:786` 的 `loadSessionMessages` 改读归档并保留两级回退；`HarnessAgentWrapper.kt` 新增三个能力 —— 归档当前 context、按命令压缩（`compactManually`：agent / sessionId / user 桶三个值一律取自 wrapper 自身，就是为守住第 4 节末条那条隐藏不变量）、算占用比例；`HarnessAgentLauncher` 把归档表与 `context_window` 原值带到 wrapper；`HarnessAgentBuilder.kt` 加 `disableTranscript()` 透传（现有 disable 一族在 `:148-158`），并在 `HarnessAgentLauncher.kt` 的两条装配分支上都调用它（第 3 节第 1 行）。
- **harnax-harness-core（team 侧）**：`team/TeamOrchestrator.kt` 的成员轮次以 `collectTurn` 的轮末 `finally` 归档该成员子会话 —— 成员会话没有别的收尾点，且它必须与委派成功与否无关：到达过 context 的就是页面已经给用户看过的内容。`team/TeamRuntimeSpec.kt` 的 `TeamSessions` 加成员子会话谓词（键的拼法只有这一个所有者，判定不能由调用方自己拼字符串），由 `HarnessAgentLauncher.isMemberChildSession` 转发给 runner 做 `/compact` 的前置拒绝。
- **harnax-agent-service**：`DefaultAgentRunner.kt:245` 的 COMPACT 分支换真实现；归档挂在三条轮次收尾处 —— 阻塞轮的 `finally`、流式轮的 `doFinally`、HITL 确认续跑那轮的 `doFinally`（它是另一条流，与 `:343-398` 同源）。三处都排在 `drainPendingRelease`／`unregisterCall` 之前：deferred 的 `release()` 会清掉归档要读的那份 state cache，一前一后决定归档有没有内容可读。`AgentController.kt` 加 `GET /api/agent/context/{sessionId}`，其账单口径的分子由 `DefaultAgentRunner` 现读 `token_stats` 该会话最近一行 —— 这张表就在 agent-service 的主库里，不必经 admin；wrapper 只接受这个数，不自己碰库。`clearSession` 连带删档。
- **harnax-session-router**：新接口按会话绑定转发一条 —— 转发调用落在 `src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt`（照 `:365` 的 `loadHistory` 同款），对外端点落在 `src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt`（照 `:105-113` 的 `/command` 代理形状）。该端点同样是 `suspend fun`，所以它的路径 `/api/router/agent/context` 要登记进 `router/config/ApiCallLogFilter.kt` 的 `SUSPEND_ENDPOINTS`：漏了这一条，`ContentCachingResponseWrapper` 会在协程写响应体之前就把空 body 刷出去，调用方拿到一个成功但没有内容的读取。
- **harnax-webui**：只有 admin 侧模型表单那一处。聊天页不改（它能发 `/compact` 这件事本来就是通的）。
- **harnax-ios**：不改。

---

## 8. 边界、失败与限制

| 情形 | 行为 |
|---|---|
| 会话太短，cutoff 留不出尾部 | 回成功但 `result.afterTokens == beforeTokens`，`message` 说明"没有可压缩的内容"；不覆写、不落库 |
| 摘要模型调用失败 | 回 failure，`context` 与库都不动，用户可原样重试 |
| 调用方在服务端完成前断开 | 已 `block()` 的那次覆写不回滚（服务端不知道连接断了）。用户重发 `/compact` 时，cutoff 判定会把已压过的会话判成"没有可压缩的内容"，因此不会二次摘要 —— 这条命令因此是幂等安全的 |
| delegate 取不到 live state | 回 failure，并在 `message` 里指明"该会话当前不可压缩"（不做副本兜底，见第 5 节末行） |
| 该会话正有流/阻塞调用在跑 | 回 failure |
| `task-` 会话 / 成员子会话收到命令 | 回 failure，说明只支持主管/普通会话 |
| 归档写失败 | warn + 重试一次；不回滚回答。下一轮全量补写会自愈，只有在此之前被裁走的消息才真丢 |
| 窗口三级都拿不到 | `windowSource = FALLBACK`、`contextWindow = 160_000`，接口照常返回但 `ratio` 是估计值 |
| `model.context_window` 填了个比真实窗口大的数 | 服务端不校验（无法校验），后果是自动压缩推迟、`ratio` 偏小。表单里按"留空则由运行时按模型名推断"提示 |
| 老会话（上线前建的） | 档案为空 → 读路径回退 `agent_state.context`，翻页行为与今天一致；发过新消息后开始建档案 |
| 升到 2.0.4 但没调 `disableTranscript()` | 每轮往对象存储/宿主盘多写一批截断的 transcript 段，页面不受影响但清会话删不掉它们（第 3 节第 1 行）。这条属于装配缺陷而非运行时降级，测试第 9 条守 |

---

## 9. 测试方案

主断言（缺一条就不算做完第 1 节）：

1. **压缩不改历史**：构造一个 context 消息数 > `keepMessages` 的会话，先取 `loadHistory` 快照 → 调 `COMPACT` 命令 → 再取快照，断言两次的逐条正文与条数完全一致，且新列表里不出现任何含 `__compaction_summary__` 或其摘要正文的气泡。用替身 model 桩摘要调用，避免测试真打模型。
2. **压缩确实降下了模型侧上下文**：同一用例里断言 `agent_state.context` 的消息数在压缩后变小、且首条是 `name = __compaction_summary__`（`AgentState.context` 与历史读的是两份数据，这条正是分离的证据）。
3. **归档幂等与补写**：连续两轮写入同一 `msg_id` 的更大版本，断言只有一行且内容是后者；断言跨轮不会重复插行。
4. **失败不覆写**：把摘要桩成抛异常，断言 `context` 未变、`agent_state` 未重写、命令回 failure。
5. **并发闸**：`activeCalls` 里放了该会话时发压缩命令，断言 failure 且 `context` 未变。
6. **占用比例**：窗口三级各一条用例（配了列 / 列为空但模型名命中上游表 / 两者都没有），断言 `windowSource` 与 `ratio` 的算法；再一条断言 `estimatedTokens` 与 `TokenCounterUtil.calculateToken(同一份 context)` 逐字相等（防止自己另写一套估算）。
7. **`args` 语义**：`/compact 500` 落 `keepTokens=500`、`/compact abc` 被忽略并走默认档。
8. **删会话连带删档**：`clearSession` 之后 `session_message` 该会话零行，成员子会话同样。
9. **transcript 没被装上**：跑一轮对话后断言工作区与 store 里没有任何 `events/` 段对象（键布局 `TranscriptStore.java:30-33`），即第 3 节第 1 行那条默认开启的通道确实被 `disableTranscript()` 关掉。

跑法沿用本仓既有配方（JDK 21、先探 Docker 再决定排除 IT）。`session_message` 由写入方自建，H2 与真库两条路都能测，不依赖清库。

---

## 10. 未验（需要实跑或产物级核对）

1. `estimatedTokens` 与真实 `input_token` 的偏差幅度 —— 上游估算按字符数 / 2.5，中文与代码混合的偏差方向未知，2.0.4 起思考内容也计入（第 3 节第 2 行），需要在真栈上跑一段会话对两个数。这直接决定 `ratio` 该给用户看到几位有效数字。
2. 关掉 offload 之后，压缩掉的早期内容是否有任何可追回路径 —— 按源码读是没有（`session_search` 这类工具在 harnax 当前装配下读不到东西），但没实测。
3. 运行时是否真的全程用匿名桶 —— 第 4 节已在源码层证明空值两侧都归一成 `__anon__`（`ReActAgent.java:394-399`、`MysqlAgentStateStore.kt:70`），静态也只见到一个不传 userId 的 wrapper 构造点；但渠道固定 UUID 会话、团队子会话这些入口有没有带进非空 userId，要看实跑后 `agent_state.user_id` 的取值分布才能定。
4. 归档在异常收尾路径上的覆盖面：三条轮次收尾都挂上了钩子、确认续跑那条也单独挂了（单测覆盖到调用与顺序），但客户端断连／上游取消时 `doFinally` 是否仍跑到并留下内容，要真栈验证。
5. team 成员子会话在长时间 delegation 后自身被自动压缩，其成员气泡是否会因此变短 —— 归档写点已覆盖成员轮次（`collectTurn` 的轮末 `finally`），按第 4 节的读路径设计不会变短，但要看真栈上一段多轮 delegation 后的页面。
6. 2.0.4 与 harnax 依赖树（Jackson 3、Kotlin 2.2.20、Spring Boot BOM）的共存只验到全量编译与单测；服务真起来跑一轮对话、并让上游的自动压缩在真实模型上触发一次，还没做过。
7. `disableTranscript()` 之外的另一种选择（注入 harnax 自己的 `TranscriptStore`）有没有将来要用的场景 —— 本轮结论是不留，若后续要做"会话原文检索"要重开这条。

---

## 11. 明确不在本轮

- 自动压缩档位的显式化（给装配层钉 `triggerTokens`/`keepTokens`/`prune`），以及 `flushBeforeCompact` 在自动路径上的取舍。
- `disableMemoryTools()` / `disableToolsConfig()` / `disableAtPathExpansion()` 三项收口。
- 前端显示占用比例与压缩入口（webui 聊天页、iOS）。
- 把 `session_message` 用于跨会话检索（`session_search` 那类能力）。
- 用上游 transcript 或 `TranscriptStore` 承载用户可见历史 —— 第 3 节第 1 行已给出否决理由（截断常量不可配、且它记的是压缩后的 live context）。
- mp 模块的 `mp_chat_message`（该模块已定废弃），不复用、不迁移。
- `DefaultAgentRunner.kt:246` 那条 TODO 之外的一切 TODO。
