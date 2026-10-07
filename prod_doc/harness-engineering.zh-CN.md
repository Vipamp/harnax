# Harnax Harness 工程方案

本文钉 harness 域的版本基线、数据源与锚点约定，并把上下文压缩与占用比例那一部分的交付形状收成可核对的口径。逐条模块级改动清单、边界矩阵、九条主断言与真栈复验记录落在同目录 `prod_doc/harness-compaction.zh-CN.md`，两份共用下面第 0 节的锚点约定。

## 0. 口径与取证基线

版本事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | harnax 钉位见仓库根 `pom.xml:39`（`<agent-scope.version>2.0.4</agent-scope.version>`） |
| 2.0.4 事实来源 | `~/.m2/repository/io/agentscope/agentscope-harness/2.0.4/agentscope-harness-2.0.4-sources.jar` 与 `agentscope-core` 同名构件，本文与压缩方案篇的上游锚点都取这里 | `unzip -p <sources.jar> <类的全限定路径>` 逐类取；上游 jar 的 `.java` 不入库 |
| 2.0.4 构件来源 | 上游**未发版**（Central 没有 `2.0.4`），本机构建用的那两份是从 `~/code/opensource/agentscope-java/` 的 `release/2.0.4` 检出 `mvn install` 进本地仓库的：`~/.m2/repository/io/agentscope/agentscope-harness/2.0.4/_remote.repositories` 逐行以 `>=` 结尾（本地安装标记），同目录另有 `agentscope-core` 两份 | 上述路径；换机器、上 CI 或给别人复现都要先做这一步，否则 `pom.xml:39` 钉的版本解析不到，编译在 harness-core 那步就红。部署不受影响：`harnax-deploy/Dockerfile.agent-service:24` 只 COPY `harnax-deploy/dist/agent-service/harnax-agent-service-*.jar` 这个成品 fat jar，容器里不编译 |
| harnax 主数据源 | `harnax_admin` 库 | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 与 `session_message` 在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15`；两张表的落点见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/MysqlAgentStateStore.kt:51-68` 与 `.../MysqlSessionMessageStore.kt:63` |

锚点约定：harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，`TeamHistoryReplay.kt` 省略同文件的上一级 `.../agent/service/runner/`；`SessionConfig.kt` / `MysqlAgentStateStore.kt` / `MysqlSessionMessageStore.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/`，`TokenStatsMiddleware.kt` 省略 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/`，`MessageLogConverter.kt` 省略 `.../agent/chat/`。上游侧省略 `io.agentscope.` 前缀。三个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本文提到的**一律指 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/controller/AgentController.kt`**；iOS 的 `AgentRequest.swift` 在 `harnax-ios/.build/` 与 `harnax-ios/tmp/` 下有多份快照副本，本文只认 `harnax-ios/Sources/HarnaxCore/Contract/AgentRequest.swift`；`HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 同时吃多个域的改动，行号会随合入往后推，改过任一处就按符号名重定位再回核，别只信号。凡"现在跑成什么样"的断言以 2.0.4 源码与当前代码树为准。

取证方式：源码静态阅读 + 配置比对，另在 harnax-deploy 栈上做过一轮真栈复验（2026-10-05：清库重建到 2.0.4 的镜像、MySQL 8、真模型批模式与流式各若干轮 + 多次 `/compact`）。复验取到的数与仍未验的条目记在 `prod_doc/harness-compaction.zh-CN.md` 第 10 节。

---

## 1. 上下文压缩与占用比例

### 1.1 三条验收

1. **`/compact` 是真压缩。** 会话跑过若干轮之后发 `/compact`，模型侧上下文被摘要替换，`token_stats` 里下一轮的 `input_token` 相应下降；发一条命令就多付一次摘要用的模型调用，除此之外不多付。
2. **页面永远看到全量原文气泡。** 无论压缩过一次还是十次，`GET /api/agent/chat/history/{sessionId}`（`AgentController.kt:105`）返回的气泡序列与压缩前逐字一致，且不出现任何"压缩摘要"气泡。这是本域的主断言。
3. **占用比例可查。** `GET /api/agent/context/{sessionId}`（`AgentController.kt:123`）一次返回估算占用、真实占用、窗口值与其来源；比例按真实占用算，估算那两位继续回答"自动压缩会不会触发"。

### 1.2 形状由三条事实决定

- **压缩本来就在跑。** `HarnessAgent.Builder` 的初值是 `compactionConfig = CompactionConfig.builder().build()`、`disableCompaction = false`（`HarnessAgent.java:1227,1230`），默认装配下 `CompactionMiddleware` 一定装上（`HarnessAgent.java:2600-2609`），默认档 `triggerMessages=50`、`keepMessages=20`、动态 token 档与 flush/offload 全开（`CompactionConfig.java:273-286`、`:67`）。所以这一域不是引入新机制，而是给一个已在跑的机制补上受控入口与可见度。
- **用户可见历史原本就是模型上下文本身。** `loadSessionMessages()`（`HarnessAgentLauncher.kt:1035-1048`）读的是 `AgentState.context`，读它的只有 `DefaultAgentRunner.kt:353` 的聊天历史与 `TeamHistoryReplay.kt:44` 的成员气泡合并。压缩覆写 `context` 等于把聊天记录一起裁掉，所以**历史与模型上下文分离是压缩落地的前置**，见 1.3。
- **`/compact` 的命令链路一直是通的。** `/compact 500` 解析为 `CommandType.COMPACT` + `args="500"`（`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:67,90,199`），`POST /api/agent/command` 在收（`AgentController.kt:77`），webui 与 iOS 的斜杠命令表各登记一处（`harnax-webui/src/pages/session/components/ChatWindow.tsx:957`、`harnax-ios/Sources/HarnaxFeatures/Chat/ChatSlashCommand.swift:33`）。缺的只有服务端那一支。

### 1.3 全量历史与模型上下文分离

逐条归档会话消息到一张只增不改的表 `session_message`，与 `agent_state` 同库，由写入方在初始化时 `CREATE TABLE IF NOT EXISTS` 自建（`MysqlSessionMessageStore.kt:63`），不进 admin 的 Flyway 基线，因此这张表不需要清库重建。唯一键 `(user_id, session_id, msg_id)`，`msg_id` 取 core `Msg` 自带的 UUID；`ON DUPLICATE KEY UPDATE` 按**最长正文**保，因为上游既会用同一 id 交付内容更全的消息，也会在 prune 时用同一 id 交付更短的预览。模型上下文仍由 `AgentState.context` 承担，压缩随便它怎么覆写。

写点三处：三条轮次收尾（`DefaultAgentRunner.kt:163` 阻塞轮、`:195` 流式轮、`:425` HITL 确认续跑轮）各一次全量补写；装配时该桶还没有档就先落一次（`HarnessAgentLauncher.kt:919` → `HarnessAgentWrapper.kt:331`）；`/compact` 额外在压缩之前补写一次（写不进去就拒这条命令）。读路径先查归档，该会话零行时回退 `agentState.context`，再回退 legacy `memory_messages`，并滤掉 `name == __compaction_summary__` 的摘要消息 —— 摘要以 USER 角色构造，收进档就会在页面上多出一坨装摘要全文的用户气泡。

一条隐藏不变量：压缩写回、归档读写、历史读路径必须落在同一个 user 桶。三侧都把空值归一成 `__anon__`（`MysqlAgentStateStore.kt:70`、`ReActAgent.java:394-399`），harnax 唯一的 wrapper 构造点不传 userId（`HarnessAgentLauncher.kt:891-916`，字段默认 `null`，见 `HarnessAgentWrapper.kt:91`），历史读路径写死 `""`（`:1036`）—— 两侧同桶。任何一方单独开始传真实用户 id，写死的 `""` 就会与 live slot 分叉，表现成"压了但历史读回旧的"。命令路径因此把三个键一律取自 wrapper 自身（`HarnessAgentWrapper.compactManually`），调用方传不进第二个 userId。

### 1.4 命令压缩落在哪

`DefaultAgentRunner.kt:247` 的 `executeCommand` 在 `COMPACT` 那一支（`:264`）接 `handleCompact`（`:917`）。runner 侧只做前置判定与结果映射；取 live `AgentState`、调 `ConversationCompactor.compactIfNeeded(...)`（`ConversationCompactor.java:92-96`）、成功才覆写并保存这三步落在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt`。档位是 `triggerMessages(1)` 加 flush/offload 双关，含义是"用户既然发了命令就别拿阈值挡我"，"值不值得压"由上游 cutoff 判定把关（`ConversationCompactor.java:112,118`）。摘要失败不落库直接回 failure；`task-` 前缀与 team 成员子会话拒；该会话有在跑的流或阻塞调用拒；命令全程同步返回，`compactIfNeeded` 那一次 `Mono` 就地 `block()`。

### 1.5 占用口径

分子两个读数同时给，因为它们回答的不是一个问题：`estimatedTokens` 与自动压缩的触发判据同源（`TokenCounterUtil.calculateToken`，`TokenCounterUtil.java:73`，含思考块）；`lastCallInputTokens` 是账单真值（`TokenStatsMiddleware.kt:41-67` 每次模型调用写一行，读侧 `harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:36`）。两者**不成比例**：估算看不见系统提示与工具清单，却会计入不回放进后续轮的思考内容，所以 `ratio` 的分子取账单那个真数、没有账单行时才回退估算（`HarnessAgentWrapper.kt:441`），估算继续只回答触发那一问。分母三级回退：模型域列 `model.context_window`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:351`，`harnax-entity/src/test/resources/schema-test.sql:335` 逐字跟上）→ 上游按模型名推断的窗口 → `160_000`，`windowSource` 取 `MODEL_FIELD` / `UPSTREAM_TABLE` / `FALLBACK` 让调用方知道这个分母是配的还是猜的。`triggerTokens` 用模型自己报的窗口走 `CompactionMiddleware.java:164-190` 那段算法，与自动路径同一个数。

占用读数只由持有该会话 agent 的实例给（窗口分母属于那个会话的模型，而只有装配过程知道是哪个模型），所以 router 必须按会话绑定转发：`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:183` → `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:397`。两种报不出的形状都不是"占用为 0"：绑定过但本实例不持有 agent 时 `code: 500` 加一句"该实例不持有此会话"，从未绑定过时 `code: 200` 且 `data: null`（`:400`）。

### 1.6 两端入口

webui 会话聊天页（`harnax-webui/src/pages/session/index.tsx` 的 `ContextUsageTag` + `pages/session/components/contextUsage.ts` 三个判据 + `services/ant-design-pro/chat.ts` 的 `getContextUsage`）与 iOS 会话页（契约 `harnax-ios/Sources/HarnaxCore/Contract/ContextUsage.swift`、传输 `Sources/HarnaxAPI/ContextUsageClient.swift:24`、视图模型 `Sources/HarnaxFeatures/Chat/ChatViewModel.swift:1471`、屏幕 `Sources/HarnaxFeatures/Chat/ChatView.swift:89`）同一套判据、同一套刷新时机（一轮回答结束、一次压缩返回）。两端都不新增接口：读走聊天历史那条 router 通道，压缩走既有命令通道。没有读数时标题栏整块不出现，而不是画一个 `0%`；到自动压缩阈值时读数转警告色。压缩结果的措辞分四支：压成报条数、成功而一条没裁报"还太短"、被后端拒时顶替成服务端那句原因、信封级失败取失败自身的正文。

## 2. 这个域仍然开着的收口项

| 项 | 现状 | 判据落点 |
|---|---|---|
| 自动压缩档位显式化 | 装配层不钉 `triggerTokens` / `keepTokens` / `prune`，全走上游默认档 | `CompactionConfig.java:273-286`；动它会把"命令压缩上线"的回归面变得没法判定 |
| 中间件开关收口 | `disableTranscript()` 已在三条装配分支合流后无条件调用一次（`HarnessAgentLauncher.kt:809`，透传在 `HarnessAgentBuilder.kt:242`）；`disableMemoryTools()` / `disableToolsConfig()` / `disableAtPathExpansion()` 三项未收口 | disable 一族在 `HarnessAgentBuilder.kt:224-242` |
| 并发闸的反方向 | 压缩在跑时用户发一条聊天不拒（忙判据只在压缩侧被读，`DefaultAgentRunner.kt:87`/`:96`）。受损的是模型侧，页面侧因档案只增不改且保最长而不丢 | 要三方互斥得把忙判据铺到聊天的三个入口 |
| 归档表的检索面 | `session_message` 只服务聊天历史，不用于跨会话检索（`session_search` 那类能力） | 键布局与读点见 `MysqlSessionMessageStore.kt:63` |
| 被裁内容的追回 | 关 offload 之后没有任何入口把档案回灌进 `context`，可追回路径未实测 | 按"取不到就不算收益"处理，与 flush/offload 同源 |
