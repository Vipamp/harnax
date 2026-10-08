# Harnax 上下文压缩与占用比例方案

## 0. 口径与取证基线

版本事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文档的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | 上游检出 `~/code/opensource/agentscope-java/`，分支 `release/2.0.4`，HEAD `3c1c29c0`；其 `pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>` |
| 2.0.4 事实来源 | 上述检出的 `agentscope-harness/src/main/java/` 与 `agentscope-core/src/main/java/`，本篇全部上游锚点都取这里 | 与基线同一份代码，不再另解 sources jar |
| 2.0.4 构件来源 | 上游**仍未发版**（最新 tag `v2.0.3`，`release/2.0.4` 的 HEAD `3c1c29c0` 其 `pom.xml:30` 写 `2.0.4-SNAPSHOT`），本机构建用的那份是从上述源码检出 `mvn install` 进本地仓库的，不是从 Central 拉的：`~/.m2/repository/io/agentscope/agentscope-harness/2.0.4/_remote.repositories` 与 `agentscope-core/2.0.4/` 同名文件里逐行以 `>=` 结尾（本地安装标记），同目录有 `maven-metadata-local.xml`。**构建机前置**：换机器、上 CI 或给别人复现都要先做这一步，否则 `pom.xml:39` 钉的版本解析不到，编译在 harness-core 那步就红。部署不受影响：`harnax-deploy/Dockerfile.agent-service:24` 只 COPY `harnax-deploy/dist/agent-service/harnax-agent-service-*.jar` 这个成品 fat jar，容器里不编译 | 上述三个路径与 `git tag --list` 尾部 |
| harnax 依赖钉位 | **2.0.4**，本方案落地的第一步已完成 | 仓库根 `pom.xml:39`（`<agent-scope.version>2.0.4</agent-scope.version>`） |
| harnax 主数据源 | `harnax_admin` 库 | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 表在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15` |

锚点约定：harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，`TeamHistoryReplay.kt` 省略同文件的上一级 `.../agent/service/runner/`；`SessionConfig.kt` / `MysqlAgentStateStore.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/`，`TokenStatsMiddleware.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/`，`MessageLogConverter.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/`。上游侧省略 `io.agentscope.` 前缀。三个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本篇提到的**一律指 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt`**；iOS 的 `AgentRequest.swift` 在 `harnax-ios/.build/` 与 `harnax-ios/tmp/` 下有多份快照副本，本篇只认 `harnax-ios/Sources/HarnaxCore/Contract/AgentRequest.swift`；`harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ModelHelper.kt` 才是真正调上游 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()` 的地方（`:52,76`），它与 agent-service 下组装 harnax 自己的 `ChatModelConfig` 的 `ChatModelConfigAdaptorImpl.kt:60,97` 是模型构造链上的两处，本篇点到模型构造时按全路径区分。凡"现在跑成什么样"的断言以 2.0.4 源码为准。本篇的 harnax 行锚都对当前代码树逐条核过；`HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 这三个文件同时吃两个域的改动，行号会随合入往后推，改过任一处就按符号名重定位再回核，别只信号。

取证方式：源码静态阅读 + 配置比对，另在 harnax-deploy 栈上做过一轮真栈复验（2026-10-05：清库重建到 2.0.4 的镜像、MySQL 8、真模型 6 轮批模式 + 1 轮流式 + 一次 `/compact`）。第 10 节按这一轮记哪些条目已成为事实、哪些仍未验。

---

## 1. 目标与验收

三条，都可判定：

1. **`/compact` 是真压缩。** 会话在跑过若干轮之后发 `/compact`，模型侧上下文被摘要替换，`token_stats` 里下一轮的 `input_token` 相应下降；发一条 `/compact` 就多付一次摘要用的模型调用，除此之外不多付。
2. **页面永远看到全量原文气泡。** 无论压缩过一次还是十次，`GET /api/agent/chat/history/{sessionId}` 返回的气泡序列与压缩前逐字一致，且不出现任何"压缩摘要"气泡。这是本方案的主断言。
3. **占用比例可查。** `GET /api/agent/context/{sessionId}` 一次返回估算占用、真实占用、窗口值与其来源，调用方能据此判断"还要不要手动压"以及"自动压缩还差多少触发"。比例按真实占用（`token_stats.input_token` 那一路）算，估算那两位继续回答触发那一问 —— 实测两者不成比例，所以两个数都给、各管各的判据。这个数与一个按需压缩的入口都落在 webui 会话聊天页上（第 7 节），演示时不需要 curl；读不到数时标题栏整块不出现，而不是显示一个 `0%`。

---

## 2. 三条事实决定方案形状

**事实一：压缩本来就在跑，harnax 要做的只是给它受控的入口和读数。**
`HarnessAgent.Builder` 的字段初值是 `compactionConfig = CompactionConfig.builder().build()`、`disableCompaction = false`（`HarnessAgent.java:1227,1230`），`CompactionMiddleware` 在「未关压缩 ∧ `compactionConfig` 非空 ∧ 摘要模型（config 自带否则取 builder 的 model）非空」三条同时成立时装上，默认装配下三条全部成立（`HarnessAgent.java:2600-2609`）。上游那份初值是：`triggerMessages=50`、`triggerTokens=0`（动态档 = 模型窗口 − `reserved=20_000`，模型报不出窗口时回落 `FALLBACK_TRIGGER_TOKENS=160_000`）、`keepMessages=20`、`keepTokens=-1`（动态档 = `min(8_000, max(2_000, 可用量 × 0.25))`）、`flushBeforeCompact=true`、`offloadBeforeCompact=true`（`CompactionConfig.java:273-286`、`:67`）。这套数 harnax 在装配链逐字钉成自己的档位（5.1），钉住的唯一取值差别是 `offloadBeforeCompact = false`。窗口值优先取模型行填的 `context_window`（第 6 节），留空时由 core 按模型名前缀推断（`ModelContextWindows.java:151`）—— 窗口是每个模型不同的量，档位里钉的是触发/保留/裁剪那套旋钮。
所以"支持压缩功能"不是引入一个新机制，而是**给一个已在跑的机制补上受控的入口和可见度**。

**事实二：harnax 的用户可见历史就是模型上下文本身。**
`HarnessAgentLauncher.kt:1035-1048` 的 `loadSessionMessages()` 在改动前只有"直接返回 `agentState.context`"这一条读法（今天这句还成立，但它是该函数的第二级回退，第一级已经是第 4 节的归档）；读它的只有两处 —— `DefaultAgentRunner.kt:353-356` 的 `/chat/history` 和 `TeamHistoryReplay.kt:44` 的成员气泡合并。当时没有第二份存储。
后果：压缩覆写 `context` 等于把用户的聊天记录一起裁掉。**这条不改，第 1 节第 2 条就无法成立，所以历史分离不是可选优化，是压缩落地的前置。**

**事实三：`/compact` 的命令链路已经全通，只差服务端实现。**
`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:67,90,199` 已把 `/compact 500` 解析为 `CommandType.COMPACT` + `args="500"`；`AgentController.kt:77-86` 的 `POST /api/agent/command` 已在收；webui `harnax-webui/src/pages/session/components/ChatWindow.tsx:957` 有关键词到命令的映射；iOS 侧同样发得出去 —— 斜杠命令表 `harnax-ios/Sources/HarnaxFeatures/Chat/ChatSlashCommand.swift:33` 登记了 `compact`，`ChatViewModel.swift:1517` 的 `run(command:rawText:)` 把它装成 `CommandAgentRequest` 投递，契约枚举在 `harnax-ios/Sources/HarnaxCore/Contract/AgentRequest.swift:9`。`CommandResponse`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13`，`result: Any?` 在 `:16`）带一个 `result` 字段可直接承载结构化返回。
`DefaultAgentRunner.kt` 的 `executeCommand`（`:247`）当时在 COMPACT 那一支（`:264`）回 `Compact not yet implemented`。**命令形态不需要新造，客户端也不需要改 —— 缺的只有那一支的实现。**

按需压缩所需的上游 API 在 2.0.4 全是 public，装配层就能做完，不改上游：`ConversationCompactor.compactIfNeeded(...)`（签名 `ConversationCompactor.java:92-96`）、`CompactionConfig`、`TokenCounterUtil.calculateToken(List<Msg>)`（`TokenCounterUtil.java:73`）、`HarnessAgent.getDelegate()/getModel()/getWorkspaceManager()/getStateStore()`（`HarnessAgent.java:492,496,246,504`）、`ReActAgent.getAgentState(userId, sessionId)` 与 `ReActAgent.saveAgentState(userId, sessionId)`（`ReActAgent.java:4447,4691`）。上游自己的溢出兜底 `forceCompactAndRetry`（`HarnessAgent.java:1058-1101`）就是同一个配方，可以照着写。

---

## 3. 基线钉在 2.0.4：四条会改变结论的事实与一条前置动作

| # | 2.0.4 的事实（锚点） | 对本方案的影响 |
|---|---|---|
| 1 | **会话 transcript 中间件默认开启。** builder 初值 `disableTranscript = false`（`HarnessAgent.java:1253`），安装块 `:2543-2561`：`transcriptStore` 未注入时，有 filesystem 就走 `ObjectStoreTranscriptStore`（`:2547-2549`），否则走 `FilesystemTranscriptStore(<workspace>/.agentscope/transcripts)`（`:2550-2556`）。中间件在每次 agent 调用结束时把 `state.getContext()` 全量交给 `SessionTranscriptWriter.appendMessages`（`TranscriptMiddleware.java:86,101-104`） | 升到 2.0.4 会**凭空多出一条会话历史写通道**。harnax 三条装配分支里有两条带 filesystem：沙箱分支（`HarnessAgentLauncher.kt:684`）与非沙箱且配了 MinIO 的非 lead 分支（`:701-705`），这两条走 `ObjectStoreTranscriptStore` —— 它只是包住 `WorkspaceManager` 当前暴露的那把 `AbstractFilesystem`（`ObjectStoreTranscriptStore.java:47-51`、`WorkspaceManager.java:194`），所以前者写进沙箱文件系统、后者写进 MinIO 对象存储，键形如 `{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl`（`TranscriptStore.java:30-33`）；lead 分支与没配 MinIO 的分支没有 filesystem，落到宿主工作区的 `.agentscope/transcripts` 目录。它当不了第 1 节第 2 条要的历史源，两处硬伤：工具入参截到 500 字符、工具结果截到 1000 字符且这两个常量是 `public static final`、无配置口（`SessionTranscriptWriter.java:62,65`）；它每轮读的 live context 正是自动压缩就地覆写后的那份（`CompactionMiddleware.java:132-134`），压过的会话它记的也是压后的形状。**所以本篇要求在装配时显式 `disableTranscript()`（`HarnessAgent.java:2252`）**：留着一个既截断、又跟随压缩、又没有读回入口、清会话也不在删除清单里的副本只会误导运维 |
| 2 | token 估算开始计入思考内容：`TokenCounterUtil` 有 `ThinkingBlock` 分支（`:21` 引入、`:132` 判定），按与正文同一把字符尺折算（`:225`） | 第 6 节的 `estimatedTokens` 在带思考链的模型上比不计思考时更高，于是 `ratio` 更大、自动压缩更早触发。这条直接进第 10 节第 1 条的偏差实测范围 —— 中文 + 思考链的偏差方向要一起测 |
| 3 | 状态写入统一套了一层版本比对壳子 `persistAgentStateCas`（`ReActAgent.java:535-566`），第 5 节写回用的 `saveAgentState(userId, sessionId)` 走它（调用点 `:4699`） | 对 harnax **是空转**：`MysqlAgentStateStore` 只覆写了 `save`/`get`/`exists`/`delete`/`listSessionIds`（`MysqlAgentStateStore.kt:74,94,115,142,176,191,204,218`），没有覆写 `supportsVersioning()`，接口默认假（`AgentStateStore.java:87`），于是走 `ReActAgent.java:542` 分支直接 `stateStore.save(...)`（`:563`）—— 仍是无条件覆盖。结论：升到 2.0.4 既不需要动这个 store，也不带来并发保护，第 5 节的并发闸仍是唯一防线 |
| 4 | 新增 `HarnessAgent.getCompactionHook()`（`:264-266`），能取回装配好的中间件 | 本篇**不用它**：命令路径自己 `new ConversationCompactor(model, flushManager)`（构造器 `ConversationCompactor.java:70`），与自动路径实例无关。记此以免被误当作必需入口 |

**一条前置动作（已完成）**：`<agent-scope.version>` 从 2.0.2 改到 2.0.4。这一步不做，第 7 节任何一条改动都编不过 —— `disableTranscript()` 在 2.0.2 的 builder 里根本不存在。换机器或上 CI 要重做一遍它的前提，见第 0 节"2.0.4 构件来源"那行：那份构件在本地基线里是从上游源码检出 `mvn install` 进去的，Central 没有它。

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
    UNIQUE KEY uk_session_msg (user_id, session_id, msg_id),
    KEY idx_session_order (session_id, id)
)
```

| 设计点 | 取定 | 理由 |
|---|---|---|
| 唯一键 | `(user_id, session_id, msg_id)`，`msg_id` 取 `Msg.id` | core 的 `Msg` 自带 UUID 且随 JSON 序列化（`Msg.java:113,139`），同一条消息跨轮次、跨进程都是同一个 id。键的跨度必须等于读写的跨度：`load` 与 `delete` 都带 `user_id` 谓词，这条键是幂等写的落点，少一维就会让后写的桶撞进前一个桶的行 —— 实测形状是第二个桶 `load` 回空历史，页面在这个桶上什么都看不见。用例 `the same message id archived under two owner buckets keeps one row per bucket` 守这条。表由写入方 `CREATE TABLE IF NOT EXISTS` 自建，它不会给已存在的表重建键，所以跑过本分支旧版代码的开发机要 `DROP TABLE session_message` 让新键生效；这张表还没进任何部署环境，线上无此动作 |
| 冲突处理 | `ON DUPLICATE KEY UPDATE`，正文只在**新版本更长**时才换，`role`/`msg_name` 随同一版本走 | 上游会用同一个 id 重建消息对象，而且两个方向都有：`Msg.java:668,687,704` 的 `withGenerateReason`/`withContent`/`withMetadata` 各把 `this.id` 原样交回构造器（`:672,689,706`），内容变长；而 `ConversationCompactor.pruneToolResults`（`ConversationCompactor.java:510-588`）在压缩时把长工具结果换成头尾拼起的预览并**保留原 id**（`:578`），内容变短。prune 在 2.0.4 是默认开的（`CompactionConfig.java:285` 取 `PruneConfig.defaults()`，阈值 `protectTokens=40_000`/`minimumTokens=20_000`/`maxOutputChars=2_000`，`:576-578`）。所以"后到的"不等于"更全的"，这张表按最长正文保，页面才不会因为一次压缩把气泡裁短。三个 `SET` 子句的顺序是这条设计的承重墙：MySQL 8 按从左到右求值 `ON DUPLICATE KEY UPDATE` 的赋值，后面的子句读到的是**本条语句里已被更新过的列**，所以正文子句必须排在最后 —— 排在最前会让两次长度比较在两边已相等时进行，`role`/`msg_name` 因此钉在最旧那一版上而与正文不符。H2 的 `MODE=MySQL` 不这样求值，同一条 SQL 在它上面两种顺序都成立，所以这条判据只能由真库用例 `MysqlSessionMessageStoreMySQL8Test`（Testcontainers `mysql:8.0`）守，它断言正文、`role`、`msg_name` 三列一起跟随最长的那一版 |
| 写点 | 每轮结束时读 live context 全量，逐条幂等落档；装配时若该会话该桶还没有档，先把已持久化的 context 落一次；`/compact` 额外在压缩之前补写一次 | 只增不改 + 每轮全量补写，使**自动压缩不需要轮内挂钩子**：中间件顺序决定 harnax 拿不到"压缩覆写之前那一刻"的上下文（`CompactionMiddleware` 在 `onReasoning` 内部就地把裁掉的 `input` 交给下游，`CompactionMiddleware.java:132-147`），所以"压缩前抓一份"这条路在它身上走不通。轮末补写覆盖的是"上一轮已经把要裁的内容落过档"，但对一个档案还是零行的老会话，它的第一条新消息那一轮正好是这个不变量的例外 —— 自动压缩在这一轮里裁走的头部从未落过档。补法是把落档点提到轮前一次：装配完成时（`HarnessAgentLauncher.kt:919`）问一句 `store.hasArchive(userId, sessionId)`（`LIMIT 1` 的存在性查询，不是 count，也不回读正文），没有就 `backfillArchive()` 把已持久化的 context 先写进去。一次装配一问，不是每轮一问，之后每轮由全量补写接住，重启时档案已非空又跳过。手动那条是自己按下的，能先写：老会话在档案里还是零行（下一行的回退分支正是为它们留的），此时手压会把头部消息同时从 `context` 和回退读数里抹掉，所以命令把"档案写得进去"当前置条件，写不进去就拒（第 5 节第 2 步） |
| 排序 | 本地自增 `id` | 会话库连接参数是 `serverTimezone=UTC`（`SessionConfig.kt:44`），主库是 `Asia/Shanghai`，跨库时间列不可比。展示用的时间戳来自 `Msg.timestamp`，`MessageLogConverter.kt:44-66` 今天就在读它 |
| 序列化 | `JsonUtils.getJsonCodec().toJson(msg)`，读回 `Msg` | 与 `MysqlAgentStateStore.kt:77` 同一把 codec，工具调用/工具结果/图片块的原样形状因此保住，`MessageLogConverter` 不动，客户端契约不动 |
| **排除项** | 跳过 `msg.name == ConversationCompactor.SUMMARY_MSG_NAME`（常量值 `__compaction_summary__`，`ConversationCompactor.java:65`） | 摘要消息由 `buildSummaryMessage`（`:454`）以 `.role(MsgRole.USER)` 构造（`:471-472`），收进档就会在页面上多出一坨用户气泡装摘要全文 —— 直接违背第 1 节第 2 条。框架的合成提醒（`Msg.java:102` 的 `METADATA_SYNTHETIC`）不写入 context —— `TaskReminderMiddleware.java:45-48` 的类注释原文写明它"只临时追加进推理输入，从不写进 `AgentState.context`，因此从不被持久化、压缩或召回"，所以不需要再判 |

读路径：`loadSessionMessages()` 先查 `session_message`；该会话**零行**时回退现有 `agentState.context`，再回退 legacy `memory_messages`。回退分支保留是因为上线之前建的老会话在发新消息之前档案是空的，不回退就翻不到任何东西。

连带改动：

- 成员子会话自动覆盖 —— `TeamHistoryReplay.kt:44` 走的就是这个方法，成员的气泡也随之全量。
- 删会话要连带删档：`DefaultAgentRunner.kt:480-489` 的 `clearSession` 已对成员子会话递归，跟着删即可。
- 用户桶是这条链路的隐藏不变量：压缩写回、归档读写、历史读路径必须落在同一个 user 桶。三侧都把空值归一成 `__anon__`（`MysqlAgentStateStore.kt:70,247`、`ReActAgent.java:394-399`），而 harnax 唯一的 wrapper 构造点不传 userId（`HarnessAgentLauncher.kt:891-916`，字段默认 `null`，见 `HarnessAgentWrapper.kt:91`），历史读路径则写死 `""`（`HarnessAgentLauncher.kt:1036`）—— 今天两边同桶。任何一方将来单独开始传真实用户 id，写死的 `""` 就会与 live slot 分叉，表现成"压了但历史读回旧的"。
- 归档写失败不影响回答，但要重试一次并记 warn。理由：这条数据不可再生（与 `TokenStatsMiddleware.kt:68-78` 那种"少一行统计"不同）；而"每轮全量补写"的写法使下一轮自然把漏掉的补回来，只有在那之前被压缩裁走的消息才真丢。

**一个必须写明的既有限制**：自动压缩已经默认在跑（第 2 节事实一），本次上线之前被它裁掉的早期消息已经不在 `context` 里，也不在任何地方，**无法追回**。上线之后新产生的压缩不丢：轮末全量补写接住后续轮次，装配时的首次补档接住会话自己的第一轮。剩下的窗口只有一条——补档那一次写不进去（会话库连着却写失败），此时与第 8 节"归档写失败"一行同一处理：warn、下一轮补写自愈，只有在那之前被裁走的头部真丢。

---

## 5. `/compact` 命令压缩

命令入口是 `DefaultAgentRunner.kt:247` 的 `executeCommand` 中 `COMPACT` 那一支。runner 那一侧只做前置判定与结果映射，第 3~5 步（取 live `AgentState`、调压缩、成功才覆写并保存）落在 harnax-harness-core 的 `ContextCompactionService.compact(...)`，第 2 步落在 wrapper 自己的 `archiveContext()`，两步都经 `HarnessAgentWrapper.compactManually(keepTokens)` 暴露 —— agent、sessionId、user 桶三个键一律取自 wrapper 自身，这正是第 4 节末条不变量的守法：调用方传不进第二个 userId。流程六步：

1. 前置判定（见下表）。
2. `archiveContext()` 补写归档，返回 false（没有归档表 / 读不到 live context / 重试两次仍失败）就回 failure —— 这条命令不许裁掉页面没有副本的内容。
3. `val delegate = harnessAgent.delegate`；取不到 live `AgentState` 就拒。
4. `ConversationCompactor(model, MemoryFlushManager(workspaceManager, model)).compactIfNeeded(rc, context, forceConfig, agentId, sessionId)`（构造器 `ConversationCompactor.java:70`、`MemoryFlushManager.java:95`），`rc` 照 `HarnessAgentWrapper.kt:492` 的形状用 `RuntimeContext.builder().sessionId(sessionId).userId(userId ?: "")` 构。
5. 结果判空/判失败串 → 成功才覆写 `state.contextMutable()`（`AgentState.java:176`）并 `delegate.saveAgentState(userId, sessionId)`（`ReActAgent.java:4691-4700`，走 `agent_state` 键回到 `MysqlAgentStateStore`）。它只在 `stateCache` 命中该 slot 时才写库（`:4696-4699`），而第 3 步的 `getAgentState(userId, sessionId)` 自己就用 `computeIfAbsent` 把 slot 建出来（`:4449-4462`），所以按本流程"先取 live state 再保存"两步连着走一定能落库。2.0.4 把这一写包进了 `persistAgentStateCas`（调用点 `:4699`），但对 harnax 仍是无条件覆盖，理由见第 3 节第 3 行。
6. 返回 `CommandResponse.success(sessionId, message, result = {beforeTokens, afterTokens, beforeMessages, afterMessages, window, windowSource})`。

| 判定点 | 取定 |
|---|---|
| 压缩档位 | `AutoCompactionTier.command(keepTokens)`，就是 5.1 那套数改掉三项（取值见 5.1 的表）。`triggerMessages(1)` 与上游溢出兜底逐字同构（`HarnessAgent.java:1071`），含义是"用户既然发了命令就别拿阈值挡我"；真正的"值不值得压"由 cutoff 判定把关 —— 消息数不足以留出 `keepMessages=20` 的尾部时上游直接返回 `Optional.empty()`（`ConversationCompactor.java:112,118`），我们据此回"当前会话还短，没有可压缩的内容" |
| `args` 语义 | `/compact <N>` → `keepTokens = N`（保留尾部约 N token）。缺省走动态档。非数字或 ≤0 → 忽略并照默认档，`message` 里说明被忽略 |
| flush / offload | **两条都关**。代码上确认可关：两步各由 `config.isFlushBeforeCompact()`（`ConversationCompactor.java:136`）/ `isOffloadBeforeCompact()`（`:156`）把守，关了就是一句空 `Mono`，不付 LLM 调用也不落文件。取舍：开着能留下 `sessions/<id>.jsonl` 原文副本，但那份文件既不在 harnax 的产物可见范围也不在清会话的删除清单里，lead 侧还落在宿主 `user.dir` 子树，用户和运维都取不到；同时 flush 会多付一次模型调用、写出的日报没有任何读回入口。所以手动压缩只出摘要那一次调用。第 3 节第 1 行的 `disableTranscript()` 与这条同源：默认路径留下的文件产物一律按"取不到就不算收益"处理。自动路径同一对旋钮取 `flush = true` / `offload = false`，判据见 5.1 |
| 并发闸 | 该会话有在跑的流或阻塞调用即拒（`activeStreams` 在 `DefaultAgentRunner.kt:87`、`activeCalls` 在 `:96`，同一对判据的现有用法见 `:110`）。mid-turn 覆写 `context` 会和轮次结束时的 `saveStateToSession`（`ReActAgent.java:475`）抢同一个对象。压缩自己也在这一次写入的整个跨度里 `registerCall`/`unregisterCall`（与阻塞轮同款，`DefaultAgentRunner.kt:134-141`），于是同一会话的第二次压缩被同一条闸挡在外面，压缩期间该会话的 agent 也不会被驱逐后释放；仍未闭合的是反方向——压缩在跑时新起一轮对话不会因此被拒，见第 11 节 |
| 会话范围 | 只压 root 会话。`task-` 前缀直接拒（同 `:1078` 能力开关那条的先例）；team 成员各自的子会话不在本轮 —— 成员的上下文由成员自己跑完时的自动压缩负责 |
| 摘要失败 | **不落库，直接回 failure。** 上游把摘要调用的异常吞成字符串 `"(Summarization failed: …)"` / `"(Summary unavailable)"` 并照常返回一个"压缩结果"（`ConversationCompactor.java:375,382`）。自动路径下这是可接受的降级，手动路径下等于用一坨错误文本把模型上下文换掉、且因为第 4 节的分离用户看不见、下一次轮就照着它答 —— 判据是结果首条 `name == __compaction_summary__` 且正文**包含** `(Summarization failed` 或 `(Summary unavailable)`。取"包含"而非"开头"是因为标记并不写在开头：`buildSummaryMessage` 先拼固定引导语 `"Here is a summary of the conversation to date:\n\n"`（`ConversationCompactor.java:467`），标记只会落在它后面；而"包含"带来的唯一代价是把一句真提到该字样的成功摘要也判为失败，这个方向上误判便宜（命令重试一次、上下文原样保留），反向漏判才贵 |
| 无缓存 agent | 照 `getOrCreateAgent` 正常重建（`:774-787` 的 `cachedAgent` 判空后由 `:794` 建），压缩不必是"活跃会话"专属 |
| 阻塞与超时 | 命令这条链全程同步返回 `ResultVo<CommandResponse>`（router `AgentProxyController.kt:105-113`、agent-service `AgentController.kt:77-86`），而 `compactIfNeeded` 回的是 `Mono`，所以实现就地 `block()` 等那一次摘要调用。够用：router 对 JSON 代理的读超时 600s（`harnax-session-router/src/main/resources/application.yml:103`）、webflux `request-timeout` 1800s（同文件 `:22`），一次摘要远在其内，与 `HarnessConfig.turnTimeoutSeconds = 300`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt:33`）同一量级。不为它另开异步通道 |
| 走 store 兜底副本 | 禁止。`HarnessAgentWrapper.kt:293-314` 的 `getLiveAgentState()` 在 delegate 缺席时会返回从库里反序列化出来的副本，改它再 `saveAgentState` 会静默 no-op（只保存 cache 里已有的 slot）。压缩这条路径只认 delegate |

### 5.1 自动压缩档位

两条路共用一份档位，落在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/AutoCompactionTier.kt`：装配链 `HarnessAgentLauncher.kt` 在 `disableTranscript()` 那一段之后调 `agentBuilder.compaction(AutoCompactionTier.auto())`，命令路径调 `AutoCompactionTier.command(keepTokens)`。取值的判据是"这就是本运行时会走的那套数"，不是"上游现在是多少"——`AutoCompactionTierTest` 里有一条漂移哨兵把 `auto()` 与 `CompactionConfig.builder().build()` 逐字段比，上游哪天动任何一个默认值它就转红，那是要人裁决的信号而不是要修的 bug。

| 常量 | 值 | 含义 |
|---|---|---|
| `TRIGGER_MESSAGES` | 50 | 消息数那条线 |
| `TRIGGER_TOKENS` | 0 | 0＝按模型窗口动态。窗口是每模型不同的量（`model.contextWindowSize`，操作者在模型行填的 `context_window` 经 `ModelHelper.kt:64/82/104` 进到模型件），钉绝对值会让小窗口模型永远够不着线 |
| `RESERVED_TOKENS` | 20 000 | 动态触发线让出的余量，也就是第 6 节那行阈值的减数 |
| `KEEP_MESSAGES` | 20 | 没有窗口可推时的保留条数 |
| `KEEP_TOKENS` | -1 | -1＝动态尾档 |
| `KEEP_TOKENS_MIN` / `_MAX` / `_RATIO` | 2 000 / 8 000 / 0.25 | 动态尾档 `min(MAX, max(MIN, usable × RATIO))` 的三个参数 |
| `PRUNE_PROTECT_TOKENS` | 40 000 | 最近这么多 token 的工具结果不参与裁剪 |
| `PRUNE_MINIMUM_TOKENS` | 20 000 | 可裁总量越过这条才真裁 |
| `PRUNE_MAX_OUTPUT_CHARS` | 2 000 | 裁完只留头尾预览时的每份字符数 |
| `PRUNE_EXCLUDED_TOOLS` | `read_file, memory_search, memory_get, session_search` | 后三件在这里要么只读要么已摘除，裁它们换不到余量 |

`auto()` 与 `command()` 的差**恰好三项**：`triggerMessages`（1）、`flushBeforeCompact`（false）、`offloadBeforeCompact`（false），另加 `keepTokens` 那一个可选入参。`AutoCompactionTierTest` 把这三项之外逐字段比平，所以两条路不会再各长出一个旋钮。

三项显式写出但不钉值，各带理由：`summaryPrompt` **引用** `CompactionConfig.DEFAULT_SUMMARY_PROMPT`（摘要措辞的改进该跟，这一跟是写在代码里的跟）；`model` 留 null（摘要用 agent 自己的模型）；`truncateArgs` 留 null（工具入参截断没开，上游开它是 500/1000 字符常量、不可配，那正是第 3 节否掉 transcript 通道的理由之一）。

自动路径 `offloadBeforeCompact = false` 的三条判据：上游这一步走的是 `MemoryFlushManager.offloadMessages`（`MemoryFlushManager.java:207-216`）里 `new SessionTranscriptWriter(...).appendMessages(...)`，与 `disableTranscript()` 关掉的每轮 transcript 是**同一个写入器**、同一个键布局（`agents/<agentId>/sessions/<sessionId>`，`SessionTranscriptWriter.java:86`）；它在 harnax 里唯一的消费方 `session_search` 已被显式摘除（`HarnessAgentLauncher.kt` 的 `removeTool("session_search")`）；这份副本也不在 `clearSession` 的删除范围内。`flushBeforeCompact` 保持 true：压缩前把即将被裁掉的前缀抽进当日记忆账，memory 域吃这一路。关掉 offload 不引入新的失败面——上游本来就把它的异常静默吞掉继续走（`ConversationCompactor.java:153`）。

---

## 6. 上下文占用比例

分子两个口径同时给，因为它们回答的不是一个问题：

| 字段 | 来源 | 口径说明 |
|---|---|---|
| `estimatedTokens` | `TokenCounterUtil.calculateToken(context)` | 上游那把尺：字符数 / 2.5 + 每条消息与每个工具块的结构开销（`TokenCounterUtil.java:49-58` 的常量与 `:73` 的方法），思考内容按正文同一把尺折算（`:132`）。**与自动压缩的触发判据完全同源**，所以它回答"压缩会不会触发" |
| `lastCallInputTokens` | `token_stats.input_token` 该会话最近一行 | 账单真值，由 `TokenStatsMiddleware.kt:41-67` 每次模型调用写一行。它含系统提示与工具清单而 `context` 不含，且它反映上一轮，压缩之后要到下一轮才降。**它与估算不成比例**：真栈同一会话同一轮并排取到的六组数是 669/4245、2330/4604、4974/4961、3770/4934、7573/4868、3807/5157，另一会话 3618/6278 与 447/4468 —— 估算最低只有账单的一成、最高略超账单，因为估算计入的思考内容并不会回放进后续轮次、而账单带着估算永远看不到的系统提示与工具清单。所以这两个数不能互相换算，只能各答各的问题 |
| `contextWindow` | 三级回退：模型域新列 `model.context_window` → 上游 `getContextWindowSize()`（`ChatModelBase.java:38-40`；builder 没给值时由 `ModelContextWindows.lookup` 按模型名做最长前缀匹配，未命中返回 0，`ModelContextWindows.java:151`）→ `160_000` | `windowSource` 取 `MODEL_FIELD` / `UPSTREAM_TABLE` / `FALLBACK`，让调用方知道这个分母是配的还是猜的 |
| `ratio` | 有账单行时 `lastCallInputTokens / contextWindow`，该会话还没有账单行时回退 `estimatedTokens / contextWindow` | 展示用，也是"还要不要手动压"的判据 —— 它回答的是"真实请求把窗口占了多满"，所以分子必须用账单那个真数而不是自算的估算。回退只覆盖"装配完但一次模型都没调过"那一格，此时估算就是唯一可读的量。用例 `the ratio divides the billed number while the estimate keeps answering for the trigger` 守这一条；`estimatedTokens` 本身不动，继续与触发判据同数 |
| `triggerTokens` | 用**模型自己报的窗口** `model.getContextWindowSize()` 走 `CompactionMiddleware.java:164-190` 那段算法：>0 时 `窗口 - RESERVED_TOKENS`，该值 ≤0 时上游钳成 `max(1, 窗口/2)`；窗口报不出（≤0）时取 `160_000`。减数与 `triggerMessages` 都取 5.1 那份常量，`HarnessAgentWrapper` 直接读常量而不是另建一个配置件，所以读数与运行时 middleware consult 的那份 config 同源 | "自动压缩还差多少兜底"。这里刻意不用上一行的三级回退值当被减数：中间件只看得到模型自己报的数，两者一旦分叉，三级都拿不到时就会报出一个 `160_000 - 20_000 = 140_000` 而实际兜底是 `160_000` —— 报错的阈值比报不了更糟 |
| `messageCount` | `context.size` | 与 `triggerMessages` 同判据 |

新增读接口 `GET /api/agent/context/{sessionId}`，挂在 `AgentController.kt`（同类已有 `:105` 的 `/chat/history/{sessionId}`），鉴权形状照它：`@InternalOnly` + sessionId 作用域、不带租户谓词 —— 这是既有先例，照用并在此标明。注意 `@InternalOnly` 打在类上（`:33`，同处 `@RequestMapping("/api/agent")` 在 `:32`），新方法挂在同一个 controller 里就自动继承，不需要逐个方法标注。真实 token 那条查询因此只按 `session_id` 过滤。响应体沿用 `ResultVo`。

配套改动：

- `TokenStatsMapper.xml` 原本只有 insert（`:22`）与租户维聚合读，本轮加了一条按 sessionId 取最近一行 `input_token` 的 select（`:36`）。agent-service 的主数据源就是 `harnax_admin`（`application.yml:10`），就地能读，不需要经 admin。
- session-router 的绑定转发已加。会话的上下文在哪个实例上，只有 router 知道；新调用照 `SessionRouterService.kt:361` 的 `loadHistory` 同款（`proxyLoadContext` 在 `:397`）。这条转发不是优化而是前提：占用读数只由持有该会话 agent 的实例给（第 8 节"该实例没有这个会话的 agent"一行）。

`model.context_window` 的落法：

| 位置 | 改动 |
|---|---|
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:351`（`model` 表） | 加一列 `context_window int DEFAULT NULL COMMENT 'Model context window in tokens'` |
| `harnax-entity/src/test/resources/schema-test.sql` | 同一列逐字跟上（它是 admin 基线的逐字副本） |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Model.kt:16-84`、同目录 `dto/ModelConfigDto.kt:45` | 各加一个可空 `contextWindow` 字段。窗口值进运行时只有两条通道，都从这两处读：`ChatModelConfigAdaptorImpl.kt:72,82,89` 取 `cfg.contextWindow`（`ModelConfigDto`），`:109,119,126` 取 `model.contextWindow`（实体）。`AgentSpecInfoResponse` 不在任一条通道上，也没有读窗口的调用方，因此**不加**这个字段 —— 加了就是一列死数据 |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ChatModelConfigAdaptorImpl.kt:60,97` | 两条通道各一个方法，把窗口值透传进 `ChatModelConfig`；模型对象最终由 `harnax-agent/harnax-agent-utils/src/main/kotlin/com/agnetix/harnax/agent/adaptor/model/ModelHelper.kt:52,76` 走上游 `DashScopeChatModel.builder()` / `OpenAIChatModel.builder()` 构造，窗口值就进那个 builder（三个 provider 各一处：`:64,82,104`）—— 不再另开一条"直接读列"的通道，读列的那两处已经在上一行的适配器里 |
| admin 模型表单 | 一个可选数字输入框（单位：token） |
| 已部署环境 | 基线折进 V1 意味着**清库重建**；不想清库的话就改出前向增量 `V2__model_context_window.sql`。两条都写得出来，默认走清库重建 |

**自动压缩的档位是钉出来的**：装配链把 5.1 那份 `AutoCompactionTier.auto()` 经 `HarnessAgentBuilder.compaction(...)` 交给上游，命令路径取同源的 `command(keepTokens)`，本节这个阈值的减数读的也是同一份常量。`disableMemoryTools()` / `disableToolsConfig()` 的收口排在第 11 节。

---

## 7. 改动清单

按模块，文件级：

- **仓库根**：`pom.xml:39` 的 `<agent-scope.version>` 改 2.0.4（第 3 节的前置动作，已完成；换机器要先按第 0 节"2.0.4 构件来源"把那两份构件装进本地仓库）。
- **harnax-entity**：`Model.kt` 加列映射；`ModelConfigDto` 加 `contextWindow`；`TokenStatsMapper.kt` + `.xml` 加一条按 sessionId 的最新 `input_token` select；`schema-test.sql` 跟基线。
- **harnax-admin**：`V1__init_schema.sql` 加列；模型 CRUD 的校验与表单加一个可选字段。
- **harnax-harness-core**：`agent/session/` 新增 `MysqlSessionMessageStore`（自建表、幂等写、按会话读、按会话删、按桶问一句有没有档，纯 JDBC，照 `MysqlAgentStateStore.kt` 的形状）；`HarnessAgentLauncher.kt:1035` 的 `loadSessionMessages` 改读归档并保留两级回退；`HarnessAgentWrapper.kt` 新增四个能力 —— 归档当前 context、装配时给还没有档的桶补一次（`backfillArchive`，`:331` 起，判据是 `store.hasArchive(userId, sessionId)` 这条 `LIMIT 1` 存在性查询）、按命令压缩（`compactManually`：agent / sessionId / user 桶三个值一律取自 wrapper 自身，就是为守住第 4 节末条那条隐藏不变量）、算占用比例；`HarnessAgentLauncher` 把归档表与 `context_window` 原值带到 wrapper，并在唯一的构造点（`:891-916`）建好 wrapper 之后调一次 `wrapper.backfillArchive()`（`:919`）；`HarnessAgentBuilder.kt` 加 `disableTranscript()` 透传（disable 一族在 `:224-242`），并在 `HarnessAgentLauncher.kt:809` 三条装配分支合流之后无条件调用它一次（第 3 节第 1 行）—— 一处调用覆盖全部分支，没有能绕过它的分支。
- **harnax-harness-core（档位）**：新增 `harness/compaction/AutoCompactionTier.kt` 作两条路唯一的档位来源 —— `auto()` 给装配链、`command(keepTokens)` 给 `/compact`，两者只差 5.1 那三项；`HarnessAgentBuilder.kt` 加 `compaction(CompactionConfig)` 透传（上游 `HarnessAgent.java:1873`，传 null 即等价于 `disableCompaction`）；`HarnessAgentLauncher.kt` 在 `disableTranscript()` 之后一处调用把档位钉上，与 transcript 那条同样没有能绕过它的装配分支；`HarnessAgentWrapper.contextUsage` 的 `triggerTokens`/`triggerMessages` 与 `ContextCompactionService.commandConfig` 都改读这份常量，两处不再各自 `CompactionConfig.builder().build()`。
- **harnax-harness-core（team 侧）**：`team/TeamOrchestrator.kt` 的成员轮次以 `collectTurn` 的轮末 `finally` 归档该成员子会话 —— 成员会话没有别的收尾点，且它必须与委派成功与否无关：到达过 context 的就是页面已经给用户看过的内容。`team/TeamRuntimeSpec.kt` 的 `TeamSessions` 加成员子会话谓词（键的拼法只有这一个所有者，判定不能由调用方自己拼字符串），由 `HarnessAgentLauncher.isMemberChildSession` 转发给 runner 做 `/compact` 的前置拒绝。
- **harnax-agent-service**：`DefaultAgentRunner.kt:264` 的 COMPACT 分支接 `handleCompact`（`:917`），替掉原来那句 `Compact not yet implemented`；归档挂在三条轮次收尾处 —— 阻塞轮的 `finally`（`:163`）、流式轮的 `doFinally`（`:195`）、HITL 确认续跑那轮的 `doFinally`（`:425`，它是 `confirm` 那条独立流，`:376-446`）。三处都排在 `drainPendingRelease`／`unregisterCall` 之前：deferred 的 `release()` 会清掉归档要读的那份 state cache，一前一后决定归档有没有内容可读。`AgentController.kt` 加 `GET /api/agent/context/{sessionId}`，其账单口径的分子由 `DefaultAgentRunner` 现读 `token_stats` 该会话最近一行 —— 这张表就在 agent-service 的主库里，不必经 admin；wrapper 只接受这个数，不自己碰库。`clearSession`（`:480-489`）连带删档。
- **harnax-session-router**：新接口按会话绑定转发一条 —— 转发调用落在 `src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt`（照 `:361` 的 `proxyLoadHistory` 同款，新增的 `proxyLoadContext` 在 `:397`），对外端点落在 `src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt`（照 `:105-113` 的 `/command` 代理形状，新端点 `GET /context/{sessionId}` 在 `:183`）。该端点同样是 `suspend fun`，所以它的路径 `/api/router/agent/context` 要登记进 `router/config/ApiCallLogFilter.kt` 的 `SUSPEND_ENDPOINTS`：漏了这一条，`ContentCachingResponseWrapper` 会在协程写响应体之前就把空 body 刷出去，调用方拿到一个成功但没有内容的读取。
- **harnax-webui**：admin 侧模型表单那一处，加会话聊天页的两处。标题栏是一枚只读 Tag（`pages/session/index.tsx` 的 `ContextUsageTag`），走 `services/ant-design-pro/chat.ts` 新增的 `getContextUsage` —— 与聊天历史同一条 router 通道、同一份 `X-Api-Key`，因此不需要新的网关配置；输入区的压缩入口复用聊天页既有的命令通道（与 `sendSilentCommand` 同款 fetch 与 `getRouterHeaders`），团队子会话与 `task-` 会话被拒时把后端那句 `message` 原样显示，信封级失败（`data: null`）取信封自身的 `message`。三个真正需要判定的地方抽成纯函数模块 `pages/session/components/contextUsage.ts`，好让它们在渲染之外可断言：`isContextUsageReadable` 把第 8 节那两种报不出形状一律判成"没有读数"，于是标题栏整块不渲染而不是显示 0%；`contextUsageBasis` 按 `lastCallInputTokens` 是否为空决定读数后缀是"账单"还是"估算"，与第 6 节的分子同源；`isAtAutoTrigger` 用同一个分子比 `triggerTokens`，到线转橙。`formatContextPercent` 只管一位宽槽里的取位。四个 token 行（账单／本地估算／窗口／触发线）不打裸数字，按仓内既有的 M/K 口径缩写（`src/utils/tokenFormat.ts`，与首页总览同一份规则：≥1e6 记 `x.xxM`、≥1e3 记 `x.xxK`、不足 1000 原样），一个 200000 的窗口在提示层里是 `200.00K` 而不是六位不带分隔的数字；缩写只改展示，`ratio` 与触发判据读的还是原始整数。缺账单那一支仍走「尚未记录」的文案而不是被缩写成 `0`，消息条数是条数、不缩写。`compactionOutcome` 把"成功但一条没裁"判成 no-op 而不是完成。刷新时机只有两个：一轮回答结束、一次压缩返回 —— 分子跟的是最近一次已计费的调用，轮内不会动。文案全部走 i18n，中英两份 key 同批加。
- **harnax-ios**：不新增任何接口，读数与命令都走会话聊天页既有的那一条 router 通道与同一份凭据，落点是三层各一处加屏幕两处。契约层新增 `harnax-ios/Sources/HarnaxCore/Contract/ContextUsage.swift`，四条判据与 webui 那个纯函数模块同源同序：`isReadable` 把第 8 节那两种报不出形状一律判成"没有读数"，`basis` 按 `lastCallInputTokens` 是否为空定后缀，`isAtAutoTrigger` 用同一个分子比 `triggerTokens`，`percentText` 只管窄槽里的取位，并在比值越出 `Int` 范围时钳到 `Int.max`（`Int(Double)` 越界是致命错误，而 `isFinite` 挡不住乘上一百之后才溢出的一支）；`windowSource` 保留服务端送来的原字符串，于是上游将来加第四档时它按自己的拼法出现，不会被读成 `FALLBACK`，空串这一支例外——按 webui 的 `usage.windowSource || 'FALLBACK'` 读成兜底。传输层 `Sources/HarnaxAPI/ContextUsageClient.swift:24` 把 `AdminClient` 接成 `ContextUsageReading`，`:34` 钉住 base 路径 `/api/router/agent/context`，会话键带斜杠时先按段编码（与 plan 那几条同一判据）。视图模型 `Sources/HarnaxFeatures/Chat/ChatViewModel.swift:145` 持一个可空的 `contextUsage`，`refreshContextUsage()`（`:1471`）**每次读都覆盖**它，取不到就置空——与 webui `loadContextUsage` 同形，因为"这次没读到"本身就是新信息；覆盖点只有这一处，外加 `bind` 里切会话时清空（旧读数的分母属于上一个模型）。既然覆盖是设计，落地的只能是**最后一次询问**的答案：`:149` 一个自增代际计数，读回来时只认与自己同代的那次（`:1484`），于是一次慢的压缩前读数盖不掉压缩之后的新读数；类是 `@MainActor`，这个计数不需要锁。刷新时机两条，与 webui 一致：一轮回答结束（`followUpContextUsage()`，`:1493`，正常收尾与失败收尾都算，失败那一轮已经发生的调用照样计费）、一次压缩返回（`run(command:rawText:)` 的 COMPACT 分支，含用户按停止那一支——命令已经在服务端跑掉，停止只放弃回答，读数照样重取，对应 webui `finally` 里的那一次）。压缩入口 `requestCompact()`（`:1401`）复用既有命令通道，回包三态先由契约层的 `CompactionOutcome.compact(_:)`（`Sources/HarnaxCore/Contract/AgentStreaming.swift:64,81`）判出：成败只认 `success` 旗标，与 webui 的 `success === true` 同判据——线上 `CommandResponse.success` 是非空 `Boolean`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13-17`，`AgentProxyController.kt:109` 与 `AgentController.kt:79` 返回的都是这一个类型），命令体在场旗标必在，所以旗标缺失等价于没有命令体；旗标为真的那一支里计数不同才是压成、相等是没得压，旗标不为真一律失败，于是横幅不会把一次失败报成成功。措辞按 `compactionSentence(for:)`（`:1613`）分四支：完成报 `before → after` 条数、成功而一条没裁说"还太短"、被后端拒时把服务端那句 `message` 顶上来、信封级失败取失败自身的正文；点按走这四条本地文案，手敲 `/compact` 保留服务端原句进气泡——与 webui 那两支的分工同形。屏幕 `Sources/HarnaxFeatures/Chat/ChatView.swift`：标题栏在 `if let usage = vm.contextUsage`（`:89`）里挂一枚只读胶囊（`ToolbarItem(placement: .primaryAction)`，`:90`，实现在 `contextUsageTag(_:)`，`:154`），有没有读数只在这一处解开一次，屏幕里没有第二个判断点；没有读数整块不出现；它展开的五行就是 webui 那枚 Tooltip 的内容，四个 token 行由 `TokenFigures.token` 按 token 页同一档 M/K 缩写（两端因此对同一个数说同一个样），形状换成 `Menu`，因为手机没有 hover；到线时胶囊转警告色，与那枚 `orange` 同判据。App target 的替身目录（`harnax-ios/App/HarnaxDebugScreens.swift:327`）把这条读答复出来而不是拒掉，并且三个数自相洽（比值就是账单分子除以窗口），否则截图里要么没有这块读数、要么标题与明细互相打脸。五行里"哪个数回答哪个问题"和标题串都抽在 `Sources/HarnaxFeatures/Chat/ContextUsageReadout.swift`，屏幕里不重判 `isReadable`（同一个判据只由一处读）。输入区压缩胶囊按 console 的次序排在权限档之后、停沙箱之前（`ChatView.swift:508`），在那次请求跑完之前哑掉；它那句"聊天页仍然显示全部原始消息"随控件的可读说明走，因为胶囊没有 hover。中英两份 `chat.context.*` 与 `chat.composer.compact` 同批加，两份 key 集与占位符由 `Tests/HarnaxKitTests/LocalizationKeyTests.swift` 的闸守；`Tests/HarnaxFeaturesTests/ContextUsageReadoutTests.swift` 守那五行配对与无账单那一支，`ContextUsageViewGateTests.swift` 守挂载与胶囊在行里的位置。第 2 节那条硬要求——压缩后页面仍是全部原文气泡——在 iOS 侧钉在屏幕能证的最高一层：`Tests/HarnaxFeaturesTests/ChatHistoryLoadTests.swift` 的 `testACompactionLeavesEveryStoredBubbleOnScreen` 让四条存档气泡过一次成功压缩（`40 → 12`），逐条比文本与角色都不动，且屏幕上不出现摘要气泡。

---

## 8. 边界、失败与限制

| 情形 | 行为 |
|---|---|
| 会话太短，cutoff 留不出尾部 | 回成功但 `result.afterTokens == beforeTokens`，`message` 说明"没有可压缩的内容"；不覆写、不落库 |
| `/command` 请求体缺多态判别属性 `type` | 在 session-router 的入站反序列化就被拒（`AgentProxyController.kt:105` 的形参类型是 `CommandAgentRequest`，router 自己的 `GlobalExceptionHandler` 回 `code: 500` 与 `missing type id property 'type'`），请求根本不落到 agent-service。webui 发的命令体一直带 `type`（`harnax-webui/src/pages/session/components/ChatWindow.tsx:1020`），所以这条只打在直连 router 的调用方身上 |
| 摘要模型调用失败 | 回 failure，`context` 与库都不动，用户可原样重试 |
| 调用方在服务端完成前断开 | 已 `block()` 的那次覆写不回滚（服务端不知道连接断了）。用户重发 `/compact` 时，cutoff 判定会把已压过的会话判成"没有可压缩的内容"，因此不会二次摘要 —— 这条命令因此是幂等安全的 |
| delegate 取不到 live state | 第 5 节第 2 步先拒：读不到 live context 就是归档写不进去，`message` 说明会话未被记录。服务内部那道 delegate 判定（第 3 步）因此是纵深防御，命令路径上不会先到它 |
| 该会话正有流/阻塞调用在跑 | 回 failure |
| `task-` 会话 / 成员子会话收到命令 | 回 failure，说明只支持主管/普通会话 |
| 流式轮里客户端提前挂断 | 那一轮已经进 `context` 的用户消息照常落档，没产出完的回答在两侧都不存在 —— live context 与档案同时只多那一条用户消息。下一次正常轮次由全量补写接着接住，压缩因此碰不到"页面没有副本"的内容 |
| 归档写失败 | warn + 重试一次；不回滚回答。下一轮全量补写会自愈，只有在此之前被裁走的消息才真丢 |
| 归档写不进去时收到 `/compact` | 回 failure，`context` 与两处库都不动（第 5 节第 2 步把它当前置条件）。这条命令不能裁掉页面没有副本的内容 |
| 压缩里有超长工具结果 | 上游 prune 把 live context 里的工具结果换成头尾拼起的预览并保留原 id（第 4 节冲突处理行）；模型看到的是预览，页面读的那张表按最长正文保，气泡不缩短 |
| 同一会话第二次 `/compact` | 前一次还在跑就回 failure（压缩自己占着 `activeCalls`），不会两次覆写抢同一份 context |
| 窗口三级都拿不到 | `windowSource = FALLBACK`、`contextWindow = 160_000`，接口照常返回但 `ratio` 是估计值 |
| 该实例没有这个会话的 agent（重启后、或会话绑在别的实例） | 读数报不出，不为此建 agent：`DefaultAgentRunner.loadContextUsage` 只查 `agentCache`，查不到就回 `ResultVo.error`，说明这个实例不持有该会话。窗口分母属于那个会话的模型，而只有装配过程知道是哪个模型 —— 为一个读数付一次 admin spec 往返加一整套工具装配不值，且这个数下一轮本来就会重算。上一行讲"装配了但模型报不出窗口"，这一行讲"根本没装配" |
| 占用读数的两种报不出形状 | 会话**绑定过但本实例不持有** agent：`code: 500` + `No context held for session <id> on this instance - usage needs the live agent`（重启后拿旧会话读到的就是这个）。会话**从未绑定过**（router 查不到绑定实例）：`code: 200`、`data: null`，因为 `proxyLoadContext` 在 `boundInstance(sessionId)` 缺失那一步直接回 `ResultVo.success(null)`（`SessionRouterService.kt:400`）。两种都不是"占用为 0"，调用方要按 `data` 是否为空与 `code` 分支，不能把 `null` 读成空会话 |
| `model.context_window` 填了个比真实窗口大的数 | 服务端不校验（无法校验），后果是自动压缩推迟、`ratio` 偏小 —— 分母被填大了。表单里按"留空则由运行时按模型名推断"提示 |
| `model.context_window` 填了个比真实窗口小的数 | 同一把尺的另一侧：自动压缩提前，而且**每一轮都触发** —— 每轮多付一次摘要调用，`ratio` 长期大于 1。实测形状：填 4000 时上游把 `triggerTokens` 钳成窗口的一半即 2000（`triggerMessages` 仍是 50，所以消息数那道闸不会先拦），跑六轮批模式后从第四轮起每轮都在压，live context 稳定只剩 3 条而页面一路涨到 14 条。这一侧不校验的理由与上一行同，但它咬的是钱包而不是记忆 |
| 老会话（上线前建的） | 档案为空 → 读路径回退 `agent_state.context`，翻页行为与今天一致；发过新消息后开始建档案 |
| 升到 2.0.4 但没调 `disableTranscript()` | 每轮往对象存储/宿主盘多写一批截断的 transcript 段，页面不受影响但清会话删不掉它们（第 3 节第 1 行）。这条属于装配缺陷而非运行时降级，测试第 9 条守 |
| 分母被写坏到 `ratio` 越出可表示范围 | 两个客户端都不能崩：webui 的 `formatContextPercent` 取位后照常输出，iOS 的 `percentText` 钳到 `Int.max`（`Int(Double)` 越界在 Swift 是致命错误，`ratio.isFinite` 挡不住乘上一百之后才溢出的一支）。读数本身仍按 `isReadable` 判，钳位只管"怎么把画不下的数画出来" |
| 压缩请求在返回前被用户取消 | 命令已经在服务端跑掉，取消只放弃回答：两端都不把这次当"没发生过"——iOS 仍补取一次读数（对应 webui `finally` 里的那次重取），但横幅与气泡都不出现，于是屏幕不会宣称一次它没看到的压缩 |

---

## 9. 测试方案

主断言（缺一条就不算做完第 1 节）：

1. **压缩不改历史**：构造一个 context 消息数 > `keepMessages` 的会话，先取 `loadHistory` 快照 → 调 `COMPACT` 命令 → 再取快照，断言两次的逐条正文与条数完全一致，且新列表里不出现任何含 `__compaction_summary__` 或其摘要正文的气泡。用替身 model 桩摘要调用，避免测试真打模型。同一条判据再走一遍**档案为空**的会话（用例里不许自己先调 `archiveContext()`，否则正好绕过第 5 节第 2 步要守的那条路径）：命令自己先把要裁的头部写进档案，页面才仍然全量。
2. **压缩确实降下了模型侧上下文**：同一用例里断言 `agent_state.context` 的消息数在压缩后变小、且首条是 `name = __compaction_summary__`（`AgentState.context` 与历史读的是两份数据，这条正是分离的证据）。
3. **归档幂等与补写**：连续两轮写入同一 `msg_id` 的更大版本，断言只有一行且内容是后者；再写入一个**更短**的版本（上游 prune 那种带原 id 的预览），断言正文仍是最长那份 —— 页面缩短就是第 1 节破防；断言跨轮不会重复插行。最后断言同一 `msg_id` 落在两个用户桶时各留一行、各读各的正文（唯一键跨度与 `load`/`delete` 的谓词跨度一致，第 4 节第一行）。再断言装配时的首次补档：档案为空时把已持久化的 context 落进去，档案已有行时连 live state 都不去读（那一次存在性查询是它唯一的价格）。
4. **失败不覆写**：把摘要桩成抛异常，断言 `context` 未变、`agent_state` 未重写、命令回 failure。
5. **并发闸**：`activeCalls` 里放了该会话时发压缩命令，断言 failure 且 `context` 未变；反向断言压缩在跑的整个跨度里该会话确实在 `activeCalls` 中、命令返回后又被摘掉（第二次压缩因此被同一条闸拒）。
6. **占用比例**：窗口三级各一条用例（配了列 / 列为空但模型名命中上游表 / 两者都没有），断言 `windowSource` 与 `ratio` 的算法；再一条断言 `estimatedTokens` 与 `TokenCounterUtil.calculateToken(同一份 context)` 逐字相等（防止自己另写一套估算）；最后一条断言该实例没有这个会话的 agent 时读数直接报不出且 `createSingleAgent` 零调用（第 8 节那行的判据）。
7. **`args` 语义**：`/compact 500` 落 `keepTokens=500`、`/compact abc` 被忽略并走默认档。
8. **删会话连带删档**：`clearSession` 之后 `session_message` 该会话零行，成员子会话同样。
9. **transcript 没被装上**：跑一轮对话后断言工作区与 store 里没有任何 `events/` 段对象（键布局 `TranscriptStore.java:30-33`），即第 3 节第 1 行那条默认开启的通道确实被 `disableTranscript()` 关掉。

跑法沿用本仓既有配方（JDK 21、先探 Docker 再决定排除 IT）。`session_message` 由写入方自建，H2 与真库两条路都能测，不依赖清库。

---

## 10. 真栈复验记录与仍未验条目

复验环境：harnax-deploy 全栈（MySQL 8 + Redis + minio + 六个业务容器），后端镜像是清库重建后按 2.0.4 编出来的那一份，模型走真实 qwen 端点，`model.context_window` 按探针需要改写（4000 / 200000 两档），取完读数再改回空值，会话数据留在 `agentscope` 库与 `harnax_admin.token_stats` 里可回查。下面"已成立"的每条都注明判据取自哪一层：真栈读数、容器级测试、产物级核对，还是单测与源码级闸。

### 10.1 已成立

1. **估算与账单不成比例，比例因此按账单算。** 同一会话同一轮并排取到 `estimatedTokens`/`lastCallInputTokens` 六组：669/4245、2330/4604、4974/4961、3770/4934、7573/4868、3807/5157；另一会话两组：3618/6278、447/4468。估算最低是账单的一成、最高略超账单，方向不固定，所以第 6 节把 `ratio` 的分子定成账单那个真数，估算只留作触发判据的同源读数。改完重新部署后线上重验：同一会话连续两轮读到 `lastCallInputTokens` 5322 与 5598，`ratio × contextWindow` 分别回 5322 与 5598（`windowSource = MODEL_FIELD`、窗口 200000），同两轮估算 5057 与 5504 各自差 265 与 94 —— 分子走的确实是账单那一路。
2. **主断言：压过之后页面仍是全量原文气泡。** 三个形状各验一次，判据都是"逐条正文长度对齐探针当轮记下的回答长度"，不是总量相等。自动压缩：窗口填 4000，六轮批模式 + 一流式，`agent_state` 里 `$.context` 最后剩 3 条且首条 `name = __compaction_summary__`，同一会话页面回 14 条，逐条正文 549/536/567/456/585/498/537 与探针当轮记录的 `answerChars` 逐字相同。命令压缩：窗口填 200000 让自动那道闸够不着，`/compact 1500` 把 context 从 8 条 5534 估算 token 压到 5 条 1476，页面仍 8 条，逐条 703/536/629/610。激进压缩：`/compact 1` 把 12 条压到 2 条（估算 3618→293），页面 12 条全在，逐条 616/650/634/666/665/49。三例的 `summaryHits` 都是 0，页面里没有任何摘要气泡。判据：真栈读数 + `agentscope.session_message` 与 `agentscope.agent_state` 直查。
3. **压缩确实降下了账单，也就是第 1 节第 1 条的那一跳。** 对照形状：同一个会话先用未压缩状态问一句（`token_stats.input_token = 6278`，context 12 条），紧接着 `/compact 1` 压到 2 条，再用同长度问法问一句（`input_token = 4468`）—— 降 1810 token，约 29%。反向的形状也取到了：`/compact 1500` 那次 8 条压到 5 条，下一轮账单从 5467 涨到 5631，因为保留尾部 1500 token 加上摘要本身已经抵掉了裁掉的那三条 —— 压得少就省得少，这条命令的效果是按 `keepTokens` 连续变化的，不是"一压就降"。
4. **`args` 语义与"留不出尾部"那条边界。** `/compact 1500` 落 `keepTokens=1500`（第 2、3 条那两个数就是它的结果）；`args` 传空串走默认档，8 条 3096 估算 token 的会话返回 `success=true`、`beforeTokens == afterTokens`、`message` 说明留不出尾部，`context` 与两处库都不动 —— 批模式之后和流式轮之后各撞一次，形状相同。判据：真栈读数。
5. **归档在异常收尾路径上的覆盖面。** 三条轮次收尾（阻塞轮的 `finally`、流式轮的 `doFinally`、HITL 确认续跑那轮的 `doFinally`）的调用与顺序由单测覆盖；真栈上打到了流式轮客户端提前挂断那一形：挂断之后档案只多出那一条用户消息，未产出完的回答在档案与 live context 两侧都不存在，下一次正常轮次的全量补写接着接住 —— 就是第 8 节"流式轮里客户端提前挂断"那行讲的形状。上游取消（模型侧断流）不是同一个触发源，见 10.2 第 3 条。判据：真栈读数 + `agentscope.session_message` 直查。
6. **归属桶与两张表的落点。** 探针跑过的会话（批模式、流式、命令压缩、窗口填小让自动压缩连发）在 `agent_state.user_id` 与 `session_message.user_id` 上的取值分布各自只有 `__anon__` 一个值，没有哪个入口带进非空 userId。两张表都落在 `agentscope` 库、同一条数据源；账单那行的 `token_stats` 在 `harnax_admin`，所以第 6 节那个分子是一次同库查询而不是跨库 join。渠道固定 UUID 会话与团队子会话的桶分布没在这轮取到，见 10.2 第 7 条。判据：真栈读数。
7. **MySQL 8 上 `ON DUPLICATE KEY UPDATE` 的子句顺序。** 第 4 节冲突处理那行的承重墙在 `mysql:8.0` 容器上跑过三条用例（`MysqlSessionMessageStoreMySQL8Test`）：短预览覆盖不掉长正文、同一 `msg_id` 只留一行、同一 `msg_id` 落两个用户桶时各留一行。顺序错在哪一层测得出来：MySQL 8 按从左到右求值赋值列表，正文子句排在前时它先把新值写进 `json_value`，随后那条比对旧值的表达式读到的已是新值，两边相等于是判定不更新，"保最长"退化成"后写入者胜"。H2 的 `MODE=MySQL` 不求值成这样，所以这条不变量只能由真库用例守。线上那一份另在产物级核过：从容器 `/app/app.jar` 取出内层 `harnax-harness-core` jar，`javap -v` 的常量池里这条 SQL 的 `SET` 顺序是 `role`、`msg_name`、`json_value`。判据：容器级测试 + 产物级核对。真栈端到端那一形见 10.2 第 4 条。
8. **2.0.4 与 harnax 依赖树（Jackson 3、Kotlin 2.2.20、Spring Boot BOM）在真服务上共存。** 后端镜像清库重建后：六个业务容器 `compose ps` 全部 `(healthy)`，每容器日志各一条 `Tomcat started on port`，`harnax-deploy/dist` 里的 jar 与容器内 `/app/app.jar` 的字节数与 mtime 逐一对上。在这个前提下，第 2、3 条那些读数才是 2.0.4 真跑出来的 —— 自动压缩在真实模型端点上触发过，命令压缩也触发过。判据：部署级闸 + 真栈读数。
9. **transcript 通道运行时没有写过对象。** 这几段会话跑完，对象存储侧与宿主 `user.dir` 子树都没有 `events/` 段对象；工作区里唯一的 `.jsonl` 属于 memory flush 那条路径（日报与记忆文件），键布局与 transcript 不同，不是这条通道的产物。第 9 节第 9 条要的正是这个运行时判据，装配链上的两层间接证据（`TranscriptMiddleware` 在装配后的中间件链上缺席、`disableTranscript()` 只有一个构造点）不能替代它。判据：产物级核对。
10. **占用读数的两种报不出形状。** 同一个端点换两种会话各取到一次：绑定过但本实例不持有 agent 时 `code: 500` 加 `No context held for session ... - usage needs the live agent`；会话从未绑定过任何实例时 `code: 200` 且 `data: null`，因为 `proxyLoadContext` 在 `boundInstance(sessionId)` 缺失那一步直接回空信封。两种都不该读成"占用为 0"，第 8 节那两行就是对这两次读数的记录。判据：真栈读数。
11. **第 7 节 webui 那两处在真浏览器里成立，且主断言在页面路径上重取到一次。** 浏览器打开的是本地 dev server（`:8000`，代理指向部署栈的 28xxx 端口），页面加载的就是工作树这一份代码。登录进既有会话后：标题栏读到 `11% · 账单`，同一份响应里 `ratio 0.1058807373046875 × contextWindow 131072 = 13878` 与 `lastCallInputTokens 13878` 逐字相等（`windowSource = UPSTREAM_TABLE`、`triggerTokens 111072`），分子走的确实是账单那一路；换到第 10 条那两种报不出的会话，标题栏整块不渲染，页面上没有出现 `0%`。输入点"压缩上下文"后落下"已压缩上下文：28 条 → 21 条"，随后按页面读路径重取历史仍是 28 条（USER 8 / ASSISTANT 14 / TOOL 6）、摘要气泡命中 0，而同一时刻 `/context` 报 `messageCount: 21` —— 第 1 节的主断言在 UI 这条链上又对上一次。压缩被后端拒掉的形状取到的是**信封**：在页面上下文里实发一次 `task-` 前缀会话的 COMPACT，回 `code: 500`、`data: null`、顶层 `message` 为 `Privileged session prefix requires an internal caller` —— 拒绝原因带在信封自己身上，而前端那一支取文案的次序是 `data.message` → 信封 `message` → 本地兜底，所以这句原因顶替得掉泛化的"压缩失败"。这一支的 DOM 呈现本轮没单独取到：服务端库里当时只有一个会话，页面上点不出被拒的那一屏。前端容器随后按同一份工作树重建，`compose ps frontend` 回 `(healthy)`，`harnax-deploy/dist/frontend` 与容器内 `/usr/share/nginx/html` 两处都 grep 到新增的 `pages.session.context.*` key（落在 `p__session__index.*.async.js` 与 `umi.*.js`）。判据：浏览器 DOM + 真栈读数 + 产物级核对。
12. **第 7 节 iOS 那两处成立在单测与源码级闸上，没到真机现场。** 契约层（`ContextUsage`：账单分子优先、无账单才回落估算、窗口三级、只有拿到真窗口才算"有读数"）22 条用例——含显式 `null` 与缺键同解码一支（agent-service 与 session-router 没开 `default-property-inclusion: non_null`，两种形状线上都有），和比值越出 `Int` 范围时钳位而不是崩一支（钳位之前 `Int(percent)` 直接把整个测试进程带崩：`Fatal error: Double value cannot be converted to Int because the result would be greater than Int.max`，signal 5；`isFinite` 挡不住乘上一百之后才溢出）。`COMPACT` 回包的三态读数另 7 条（成败只认 `success` 旗标，旗标为真时两边计数不同才算压成、相等是"还太短没得压"，另钉"没带旗标的回包"读成失败而不是成功、"单边计数"不读成没得压）；传输层 6 条把线上三种形状各钉一次——`ResultVo.success(null)` 落成"报不出"而不是失败、HTTP 200 里的业务码 500 落成带着服务端那句原文的失败、缺字段的半截 body 落成解码失败（落成 `0%` 就是第 8 节那条破防），另断言请求落在 router base 的 `/api/router/agent/context/{sessionId}` 且含斜杠的会话键先编码再进路径；视图模型层 14 条钉住读取的落地规则：任何一次读取都覆盖上一次的读数（与 webui 的 `loadContextUsage` 同判据，那里也是直接覆写，"这次没读到"本身就是新信息，画 `0%` 就是第 8 节那条破防），而两次询问重叠时落地的只能是最后一次的答案（压缩前那次读得慢，盖不掉压缩之后的新数）；读数在进页、一轮回答之后、一次压缩之后各重取一次，其中压缩那一支含"用户在请求中途按了停止"——命令已在服务端跑掉，停止只放弃回答，读数照样补取，而横幅与气泡都不出现；再加四支文案分支、没带旗标那一支转警告、点按与手敲两条落点；归档不失真另 1 条：四条存档气泡过一次 `40 → 12` 的成功压缩，逐条文本与角色都不动、屏幕不出现摘要气泡（这条把第 2 节的硬要求钉在屏幕能证的最高一层）；展示层 7 条把菜单里五行的标签与取值逐条配死（另两条钉四个 token 行走 token 页同档 M/K、消息条数保持裸条数），其中"账单那一行读 `lastCallInputTokens` 而不是 `estimatedTokens`"做过变异核验——把账单行换成估算值后恰好两条用例转红；另外窗口档位出现线上没见过的**非空**取值时保留其拼写，不回落成"兜底"字样（空串不在这一支，它按 webui 的 `usage.windowSource || 'FALLBACK'` 读成兜底）。视图本身由 2 条源码级闸守：读数挂在 `ToolbarItem(placement: .primaryAction)`、解开只发生在 `if let usage = vm.contextUsage` 这一处（闸另断言胶囊那一段里不再出现 `vm.contextUsage`，即屏幕确实没有第二个判断点），达到自动压缩阈值时 chip 转 warning；压缩入口在 composer 那行的位置钉在权限之后、两个销毁类入口之前。`harnax-ios` 全量 `swift test` 2051 条 0 失败，本域 59 条（契约 22 + 回包读数 7 + 传输 6 + 视图模型 14 + 归档不失真 1 + 展示 7 + 源码级闸 2）。本域断言里做过变异核验的一共四组：把账单行换成估算值后恰好两条用例转红；去掉定序守卫、去掉停止后的补读、把"压缩后仍留全量存档"改成清空，这三条各自转红且没有连带——它们钉的是需求而不是代理指标。App target 另按 `generic/platform=iOS Simulator` 构建过并回 `** BUILD SUCCEEDED **`：`swift test` 只编四个包 target，App 层那份替身目录不在其内，"新依赖没接线"这一形只有这一跳测得出来（漏接时报的是 `missing argument for parameter 'contextUsage' in call`）。判据：单测 + 源码级闸 + 全量计数 + App target 模拟器构建；缺的那一屏见 10.2 第 8 条。
13. **自动压缩档位钉住后的三条。** 单测层：档位相关四类共 32 条全绿（`AutoCompactionTierTest` 7 条 —— 自动档逐旋钮 4 条、命令档 2 条、外加盯上游默认值漂移的哨兵 1 条；`HarnessAgentBuilderCompactionTest` 3 条 —— 不钉时上游吃自己的默认件、钉了时装配后的档位逐字段等于 `auto()`、钉档位不会把自动压缩关掉；`HarnessAgentLauncherCompactionTest` 1 条 —— 装配链每一路都把档位递进去；`HarnessAgentContextArchiveAndUsageTest` 21 条 —— 归档 8 / 读数 10 / 压缩 3）；harness-core 全量 662 条 0 失败 1 跳过（跳过的是既有 `MemoryPromotionRealModelTest`，缺 `HARNAX_REAL_MODEL_API_KEY`），agent-service 全量 257 条 0 失败。
    读数那一跳按 5.1 的算式给出，两支都取到：同一把模型（`harnax_admin.model` id 2）先把 `context_window` 填 200000 建会话问一句话，`/context` 回 `triggerTokens` 180000（= 200000 − `RESERVED_TOKENS` 20000）、`triggerMessages` 50、`windowSource` `MODEL_FIELD`；再填 4000 另建一个会话，回 `triggerTokens` 2000 —— 20000 的余量把这个窗口吃穿了，落的是 `max(1, 窗口/2)` 那道钳，与运行时 middleware consult 的那份 config 同一个算法。两个会话的 `$.context` 都还是 2 条、首条 `name = "user"`，即这一档下没触发压缩。
    offload 的取舍在同一通道前后各取到一次：钉之前那次自动压缩在会话沙箱里落下 `/workspace/agents/测试/sessions/` 三份文件 —— `<sid>.jsonl` 88216 字节 136 行、`<sid>.log.jsonl` 38955 字节 71 行、`sessions.json` 记 "transcript updated (71 entries)"，写盘时刻 12:00:22 UTC，与 `agent_state` 被覆写的 12:00:54 是同一次压缩的两条腿；钉之后那条确实压缩过的会话（`web-219f7e7b-9f51-426f-983f-57fc2c31d0c5`）的沙箱 `/workspace` 只有 `output/`（空）与 `skills/harnax/SKILL.md`，`agents/` 整段不出现。这一对照只隔离 offload 一项：memory flush 那一路在两份镜像上都不写这个目录（改动前那棵树里同样没有 `MEMORY.md` 与 `memory/`），而 `flushBeforeCompact` 钉的仍是 true。
    页面全量那一条在同一条形上重取：窗口填 4000 的会话跑五轮，账单 `token_stats.input_token` 依次 7765 / 8073 / 8593 / 8210 / 8848；`agentscope.agent_state` 的 `$.context` 剩 5 条且首条 `name = __compaction_summary__`；页面读路径回 10 条，逐条正文长度 USER 32/41/37/31/36、ASSISTANT 784/916/845/1069/827，`summaryHits` 0；`agentscope.session_message` 10 行、最长正文 78000 字节；同一时刻 `/context` 报 `messageCount` 5、`estimatedTokens` 3165、`lastCallInputTokens` 8848、`ratio` 2.212。顺带量到一条地板：压到 5 条之后账单仍从 7765 涨到 8848，压不穿的那部分是系统提示与工具模式（估算 3165 对账单 8848），所以档位裁的是尾部对话而不是这一轮账单的全部 —— 读数报的仍是真数，与第 6 节「比例按账单算」不冲突。判据：单测 + 全量计数 + 产物级核对 + 真栈读数 + `agentscope.agent_state` 与 `agentscope.session_message` 直查。

### 10.2 仍未验

1. **被裁掉的内容有没有可追回路径。** 档案是页面读路径上原文的唯一副本，但没有任何入口把它回灌进 `context`；`session_search` 这类工具在当前装配下能读到什么，仍未实测 —— 关 offload 的取舍（第 5 节 flush / offload 那行）因此是按"取不到就不算收益"定的，不是按"追不回"定的。
2. **team 成员子会话在长时间 delegation 后自身被自动压缩，其成员气泡是否变短。** 归档写点覆盖成员轮次（`collectTurn` 的轮末 `finally`），按第 4 节的读路径设计不会变短，但要真栈上一段多轮 delegation 之后的页面才算。
3. **上游取消时的收尾。** 模型侧断流（不是客户端挂断）时那条 `doFinally` 是否仍跑到并留下内容 —— 10.1 第 5 条打到的是挂断那一形。
4. **长工具结果被 prune 成头尾预览后页面气泡不缩短的端到端形状。** 语义已由 10.1 第 7 条的容器级用例守住，缺的是一条真跑过的观察。
5. **老会话（档案为空 → 读路径回退 `agent_state.context`）在真实既有会话上的翻页。** 清库重建后没有"上线前建的"会话可取，这条路径只有单测证据。
6. **`clearSession` 连带删档**（含成员子会话递归）在真栈上的落点，目前只有单测与源码级判据。
7. **渠道固定 UUID 会话与团队子会话的 `user_id` 分布**是否也只有 `__anon__`（10.1 第 6 条只覆盖到探针跑过的入口）。
8. **iOS 真机现场那一屏。** 10.1 第 12 条的判据停在单测与源码级闸，模拟器/真机上没有取到三张图：标题栏那颗 chip 的真实绘制宽度（`25% · 账单` 在窄屏上会不会被截断）、点开它之后菜单里五行标签与取值的两列对齐、以及压缩被后端拒掉时通知条上落的那句服务端原文。`simctl` 不能注入输入，这一屏要么手点要么由 XCUITest 取。

对外演示时的口径缺口：占用比例与压缩入口在两个页面上都有——webui 会话聊天页（第 7 节，实机复核见 10.1 第 11 条）与 iOS 会话页（第 7 节 iOS 那条，判据层级见 10.1 第 12 条），页面上看不到的是两处口径本身——一是那个分子跟的是最近一次**已计费**的调用，所以一次压缩不会当场把比例降下来，要等下一轮结束才动；二是读不到数时标题栏整块不出现，不是显示一个 `0%`（第 8 节那两种形状，两端同判据）。iOS 缺的只有真机现场那一屏，见下面第 8 条。

---

## 11. 明确不在本轮

- `disableMemoryTools()` / `disableToolsConfig()` / `disableAtPathExpansion()` 三项收口。
- 把 `session_message` 用于跨会话检索（`session_search` 那类能力）。
- 用上游 transcript 或 `TranscriptStore` 承载用户可见历史 —— 第 3 节第 1 行已给出否决理由（截断常量不可配、且它记的是压缩后的 live context）。
- mp 模块的 `mp_chat_message`（该模块已定废弃），不复用、不迁移。
- 并发闸的反方向：压缩在跑时用户发一条聊天**不拒**（`activeCalls` 只在压缩侧被读）。撞车时两侧都在写同一个 live `AgentState`：页面侧不受影响（档案只增不改、且保最长，两端各自轮末补写都不丢内容），受损的是模型侧 —— 要么压缩被轮末的 `saveStateToSession` 覆回去（命令等于没生效，重试即可），要么刚答完那一轮从 `context` 里丢（模型下一页不再记得，但页面上还在）。要把三方互斥得把忙判据铺到聊天的三个入口，而窗口只有一次摘要调用那么长，本轮只做"压缩不与压缩/轮次抢同一个 context"这一半。
- 本节未点名的其它 TODO 与既有缺陷。
