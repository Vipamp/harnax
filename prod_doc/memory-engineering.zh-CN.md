# Harnax memory：跨会话长期记忆方案

## 0. 口径与取证基线

版本与存储事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文档的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | 上游检出 `~/code/opensource/agentscope-java/`，分支 `release/2.0.4`，HEAD `3c1c29c0`；其 `pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>` |
| 2.0.4 事实来源 | 上述检出的 `agentscope-harness/src/main/java/` 与 `agentscope-core/src/main/java/`，本篇全部上游锚点都取这里 | 与基线同一份代码，不另解 sources jar |
| harnax 当前依赖 | **2.0.4**，与本文档基线同版本 | 仓库根 `pom.xml:39`（`<agent-scope.version>2.0.4</agent-scope.version>`） |
| 记忆的字节落在哪 | MinIO，bucket `harnax-store`、全局前缀 `store/` | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:87,92`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/MinioConfig.kt:25,29`；对象键形状见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt:21` 的类注释样例 `store/agents/myAgent/sessions/sess-123/MEMORY.md` |
| 这个 store 由谁装配 | 一个 `MinioBaseStore` 实例（`HarnessAgentLauncher.kt:759-760`）被两条分支共用 —— 沙箱分支 `:778-807`（记忆域开着时先过 `memoryStore()`，`:799`，它内含 `coordinationStore()` 与按桶的整理进度包装）、非沙箱分支 `:808-812` | 同文件；两档下的键形状必须一致，见第 7 节 |
| harnax 主数据源 | `harnax_admin` 库 | `harnax-admin/src/main/resources/application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 表在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15` |

锚点约定：上游文件一律省略前缀 `agentscope-harness/src/main/java/io/agentscope/harness/agent/`（`HarnessAgent.java`、`middleware/`、`memory/`、`memory/compaction/`、`filesystem/`、`coordination/`、`tool/`、`workspace/` 全在这一棵树下），core 侧三个文件省略前缀 `agentscope-core/src/main/java/io/agentscope/core/`。harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`，同目录的 `HarnessConfig.kt` 与 `SandboxConfig.kt` 在 `config/`、`MinioBaseStore.kt` 在 `minio/`、`HarnessAutoConfiguration.kt` 在 `spring/`，`SessionConfig.kt` 另属 `.../agnetix/harnax/agent/session/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，同模块的 `AgentController.kt` 省略 `.../agent/service/controller/`；admin 侧 `SysUserController.kt` 与 `TeamArtifactController.kt` 省略 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/`，`SysUserServiceImpl.kt` 省略 `.../admin/service/impl/`，`AdminMinioConfig.kt` 省略 `.../admin/config/`。两个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本篇提到的**一律指 agent-service 那一份**（`:105` 是它的 `GET /chat/history/{sessionId}`），只有第 7 节 REFRESH 那一行用的是 admin 那一份且把路径写全；`IsolationScope` 与 `MemoryConfig` 都是上游类型，不是 harnax 的配置类。本轮新增的引用里，`InternalApiController.kt` 同样省略 admin 那个 controller 前缀，webui 侧 `CreateForm.tsx`／`UpdateForm.tsx` 省略 `harnax-webui/src/pages/agent/components/`、两份 locale 省略 `harnax-webui/src/locales/`。凡"现在跑成什么样"的断言以 2.0.4 源码为准。

取证方式：方案定稿前是源码静态阅读 + 配置比对，未启动任何 harnax 服务；落地后第 8 节的断言跑在真 `MinioBaseStore`（Testcontainers 起 MinIO）与真装配出来的 agent 上。这一域把服务起起来的端到端用例有两条，都在 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/` 下、都从 `BaseAdminIT` 那一份随机端口上的 `HarnaxAdminApplication` 与真 `mysql:8.0` 起步：`MemoryOwnerBucketMinioIT` 另接真 MinIO，判据取带 JWT 的 HTTP 响应（第 8 节第 11 条）；`MemoryDraftFlowIT` 也接真 MinIO，候选入账与审批落库两步都从网线上进（入账走内部密钥、审批走 JWT，11.12）。其余各条仍在同一 JVM 内的装配上跑，agent-service 本身始终没有启动。

阅读范围：本篇第 1 至第 10 节是已执行并合入 `kotlin-dev` 的方案。其中凡称"harnax 今天/当前"的句子，描述的是执行前的仓库形状；执行后的形状以第 6 节的改动清单与代码为准，第 9 节逐条标注哪些已测掉、哪些仍未验。**第 11 节是记忆分层那一轮，已按该节口径落码并合入 `kotlin-dev`**，落地形状见 11.11，逐条对账见 11.12——那一节里的"今天"指的是第 1 至第 10 节落完之后。

---

## 1. 目标与验收

六条，都可判定：

1. **新会话能想起旧会话。** 同一归属桶（见第 3 节）在会话 A 里说过一件耐久事实，之后新建的会话首轮提问时模型侧输入带 `<memory_context>`，回答能用上它。判据按写入顺序四段（抽取在轮次完成时异步派发、同会话的待跑任务会被后到的顶掉，`MemoryFlushMiddleware.java:159-212`，每段之前先等记忆后台静默，见第 9 节第 3 条）：会话 A 结束后该桶当天日报 `memory/<date>.md` 新增条目；整写器跑过一次后**会话层**的 `MEMORY.md` 非空（一轮抽取绝不直接写整理稿，见事实六）；这一段被并成一条候选、候选经人工批准之后，**长期层**的 `MEMORY.md` 含该条（11.1、11.4——运行时一侧一次都不写长期层）；同桶新建的会话 B 首轮推理输入含 `<memory_context>` 段。
2. **没有用户身份就没有记忆域。** 归属在装配期定死：一次投递若没带用户，这个 agent 就不挂桶、不建 `MemoryConfig`、不给那四个工具，装配日志点名原因，投递本身照常完成。匿名调用方共享一份记忆是数据串门，不是功能；把 `userId` 逐次塞进 `RuntimeContext` 也不是出路——那个值同时是 `agent_state` 的分桶键（事实四），为了记忆填它会把既有会话的历史整体换键。
3. **开关是一起的。** 任何一档下，"模型被教怎么用记忆"与"记忆真的被读写"两件事同时成立或同时不成立。半开（工具在清单里但没有任何钩子喂它们，或在读一个永远为空的 `MEMORY.md`）算没做完。
4. **每轮多付的模型调用次数能算死。** 上限是一轮至多 2 次抽取（按轮钩子那次，加上该轮若触发压缩则内嵌的那次），加每桶每 30 分钟至多 1 次整写；再往上是把会话层并成候选那一次合并调用，它走的是同一个节流窗口（缺省 30 分钟，与整写同源、可配，11.4）。这几次里只有钩子那次与合并那次走记忆域自己的模型，压缩内嵌与溢出兜底跟当轮主模型（事实七）。判据：一轮结束后按调用的模型名分档计数，记忆档 ≤2、主档增量能对上压缩或兜底是否发生。
5. **记忆不进聊天。** `GET /api/agent/chat/history/{sessionId}` 的返回序列、iOS 与 webui 的契约都不因这套改动而变化；记忆文件里的内容永远不以气泡形式出现在任何会话里。
6. **清会话不动记忆，删用户才动记忆。** `clearSession` 之后该桶 `MEMORY.md` 原样在；删除用户之后该桶的 `MEMORY.md` 与日报全部取不到。

## 2. 七条事实决定方案形状

**事实一：跨会话记忆在上游只剩 harness 这一套。**
core 的长期记忆接口整族标了 `@Deprecated(forRemoval = true, since = "2.0.0")`（`Memory.java:30-34`、`LongTermMemory.java:66-71`、`StaticLongTermMemoryHook.java:77`），`LongTermMemory` 的类注释原文要求"任何跨会话持久化请到应用层去集成"。harness 这一套是文件形态的两层账：`memory/YYYY-MM-DD.md` 是只增的日报，`MEMORY.md` 是策展层，两层各由谁写在 `MemoryFlushManager.java:40-52` 的类注释里写明。方案只做这一套。

**事实二：默认档是"开着"的，harnax 是靠一个 disable 把它关掉的。**
`HarnessAgent.Builder` 的字段初值 `memoryConfig = MemoryConfig.defaults()`（`HarnessAgent.java:1228`），而 `MemoryConfig.defaults()` 的 `flushTrigger` 初值是 `FlushTrigger.always()`（`MemoryConfig.java:240`）—— 装上钩子就等于**每轮一次抽取、每 30 分钟每桶一次整写**。harnax 侧 `enableMemoryHooks: Boolean = false`（`HarnessConfig.kt:30`）经 `HarnessAgentLauncher.kt:898-904` 换成 `disableMemoryHooks()`，钩子才不存在。所以今天不是"没这个功能"，是"这功能默认开着而被显式关掉"。

**事实三：落点由 IsolationScope 与路由段共同决定，且 `agentId` 被框架写死在键里。**
`RemoteFilesystemSpec.storeNamespace(agentId)` 按 `isolationScope` 给出元组（`RemoteFilesystemSpec.java:333-352`）；每条路由再往元组尾部追加自己的段（`:314-323` 的 `remoteForRoute`）；`MEMORY.md` 走 `root` 段（`:256`），`memory/` 走 `memory` 段（`:258-259`）。`IsolationScope` **没有租户维度**，而 `agentId` 恒在键里 —— 今天能选的只有"按会话／按用户×agent／按 agent／全局"四档，没有"按租户"。

**事实四：匿名归一在三处给了三个不同的名字。**
`RemoteFilesystemSpec` 把空 uid 归成 `anonymousUserId`，字面量缺省 `_default`（`:79`、`:344`）；`IsolationScope.toNamespaceFactory()`（喂给 `WorkspaceManager` 做本地路径的，`HarnessAgent.java:2425`）在 USER 档且 uid 为空时**回落到 sessionId**（`IsolationScope.java:104-115`）；`agent_state` 的用户桶归一是 `__anon__`（`ReActAgent.java:394-399`）。而 harnax 送进 `RuntimeContext` 的 userId 本来就是空串（`HarnessAgentWrapper.kt:489`、`:1244` 两处装配 `RuntimeContext` 都是 `userId ?: ""`）。三套名字若被当成"同一个匿名桶"，会出现"写进 `_default`、本地兜底读 `<sessionId>`、状态读 `__anon__`"的三分叉。

**事实五：没有 CAS 的 store 撑不起分布式闸，整写与节流档抽取都会静默不跑。**
`StoreBackedPeriodicGate.tryClaim` 靠 `putIfVersion(ns, name, value, expectedVersion)` 抢槽（`StoreBackedPeriodicGate.java:48-72`）；首次抢槽时 `item == null`，于是 `expectedVersion = 0`，需要 store 支持 CAS-create-if-absent。任何没有覆写 `putIfVersion` 的 `BaseStore` 都走 `BaseStore.java:74` 的默认实现 —— **无条件返回 false**，且它的 `get` 若用 `StoreItem.java` 那个把 version 置 0 的两参兼容构造器，读到的版本永远比对不上。harnax 的 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt` 已按这条补了条件写与带版本的 `get`（第 6 节），但部署侧换成别的 S3 兼容档时这条判据照样成立，所以装配时不假定它，而是探一次。后果（探测不过且没有退路时）：`distributedStore != null` 时（沙箱分支，`HarnessAgentLauncher.kt:778-807`）闸永远抢不到，`MemoryMaintenanceMiddleware.java:165-169` 直接回 `Mono.empty()`，consolidation 与归档清理一次都不跑，且这条路径没有 warn。consolidator 拿到的正是这同一个 store（`HarnessAgent.java:2589` 的 `distributedStore.baseStore()`），所以 `MemoryConsolidator.java:359-384` 的 store 侧 watermark 同样写不进去（5 次 CAS 重试后放弃并记 warn），只是它还会同时写文件 watermark（`:350`）。**同一把闸还管着抽取本身**：`MemoryFlushMiddleware.shouldFlushNow` 在 `ALWAYS`/`NEVER` 档直接返回布尔（`MemoryFlushMiddleware.java:291-296`）不经闸，唯独 `THROTTLED` 档走 `periodicGate.tryClaim(...)`（`:297-298`），键前缀 `memory-flush:`（`:310-312`）与 maintenance 各自独立。所以 CAS 缺失时"整写不跑"与"节流档抽取不跑"是同一个故障，而缺省的 `always()` 反而是免疫的。**这条必须在打开任何记忆开关之前给出答案**：要么 store 真给得出 CAS，要么装配期探测到并退回本进程协调（第 6 节），第三种状态——静默——才是这条事实描述的故障本身。

**事实六：三个写侧互不知情，读侧只有一个入口。**
`memory_save` 同时追加 `MEMORY.md` 与当天日报（`MemorySaveTool.java:73,76-80`）；flush 只追加日报、绝不碰 `MEMORY.md`（`MemoryFlushManager.java:218-235` 的注释写明 `MEMORY.md` 归 consolidator）；consolidation **整体覆写** `MEMORY.md`（`MemoryConsolidator.java:301-303`）。三者都落在 `WorkspaceManager` 的 append/write 上，而 append 是"读全文 + 拼接 + 全量回写"（`WorkspaceManager.java:370-397`），那把锁是进程内 `ReentrantLock`，同文件 `:366-368` 的 javadoc 原文写着跨副本 "the append is last-writer-wins; this method does not perform CAS"，`uploadFiles` 在 `RemoteFilesystem.java:521-527` 的注释里同样自称 last-write-wins。读侧：`<memory_context>` 只在 `WorkspaceContextMiddleware` 装上且 `includeMemoryContext()` 为真时注入（`WorkspaceContextMiddleware.java:254-255,512-516`），另加 `memory_search` 与 `memory_get` 两个工具（`MemorySearchTool.java:67-87`，搜索面由 `WorkspaceManager.java:934-947` 定为 `MEMORY.md` 加 `memory/*.md`，无命中条数上限）。

**事实七：三处 flush 入口的定制面不均匀，所以"记忆用小模型"只在一处成立。**
按轮钩子用 `memoryConfig.flushPrompt()` 与 `memoryConfig.model()`（`HarnessAgent.java:2562-2571`）；压缩内嵌的那次硬编码默认 prompt 且用压缩模型（`CompactionMiddleware.java:106-107` 直接 `new MemoryFlushManager(workspaceManager, model)`，压根不接 `memoryConfig`）；溢出兜底那次用 `flushPrompt` 但跟主模型（`HarnessAgent.java:1073-1077`）。即 `flushPrompt` 三处里有两处生效，而 `memoryConfig.model` 只有钩子那一处认。后两条在 harnax 都活着：压缩不是可选项而是缺省 —— `compactionConfig = CompactionConfig.builder().build()` 与 `disableCompaction = false` 是 builder 字段初值（`HarnessAgent.java:1227,1230`），只要摘要模型非空 `CompactionMiddleware` 就装上（`:2600-2608`）；兜底那次由上下文溢出触发。

半开现状一并记在这里：`disableMemoryTools()` 在 harnax 从未被调用，`HarnessAgentBuilder.kt:148-158` 的 disable 一族里连这个透传方法都没有，所以四个记忆工具今天就在工具清单上对模型广告着（`HarnessAgent.java:2688-2692`）；喂它们的钩子被关（事实二），而 `enableWorkspaceContext = false` 又让读侧中间件整个不装（`HarnessAgent.java:2527`）。`includeMemoryContext()` 要求 `disableMemoryTools` **且** `disableMemoryHooks` 同时为真才为假（`WorkspaceContextMiddleware.java:254-255`），所以"只关一个"必然留下半开。

## 3. 归属与键布局先于取舍

先定桶，再谈开关。桶决定后面每一条能不能验。

| 档 | 元组（`RemoteFilesystemSpec.java:333-352`） | MinIO 对象键（bucket `harnax-store`、prefix `store/`） | 记忆含义 |
|---|---|---|---|
| `SESSION`（今天） | agents, agentId, sessions, sid 或 `default`, 段名 | `store/agents/<agentId>/sessions/<sid>/root/MEMORY.md`、`.../memory/<date>.md` | 每个会话一份私有记忆，跨会话不通 —— 与"跨会话记忆"这个目标直接矛盾 |
| `USER` | agents, agentId, users, uid 或 `_default`, 段名 | `store/agents/<agentId>/users/<uid>/root/MEMORY.md`、`.../memory/<date>.md` | 同一 agent 内该用户通；换 agent 不通；匿名调用全落 `_default` 一个桶 |
| `AGENT` | agents, agentId, shared, 段名 | `store/agents/<agentId>/shared/root/MEMORY.md` | 该 agent 的所有用户共享一份记忆 —— 跨用户串门 |
| `GLOBAL` | global, 段名 | `store/global/root/MEMORY.md` | 全局一份，同上且更糟 |

四档里都没有租户。要给记忆一个租户维度，唯一不改上游的做法是：**用 `filesystemRoute(prefix, AbstractFilesystem)`（`HarnessAgent.java:1862`）把 `MEMORY.md` 与 `memory/` 单独挂到一个自造命名空间的 `RemoteFilesystem` 上**。可行性由三处源码定死：外层 Composite 把调用方的 routes 包在 spec 内置路由之外（`:2482-2483`），匹配按最长前缀先赢（`CompositeFilesystem.java:86-93` 的排序与 `:108-136` 的匹配），命中后按后缀转交（`CompositeFilesystem.java:129-133`）。于是这一挂能覆盖 spec 自己的同名两条路由，而**其余文件与隔离档一律不动**。

| 设计点 | 取定 | 理由 |
|---|---|---|
| 记忆归属维度 | `tenant × user × agent`，元组 `[tenants, tenantId, users, userId, agents, agentId]`，由上面的单挂路由给出 | agent 维度是框架写死在默认路由里的，保留它等价于保留既有形状；租户必须进键，否则同一个 `userId` 在两个租户下的记忆同桶 |
| 其余文件的隔离档 | `IsolationScope.SESSION` 不动 | 非沙箱分支的 spec scope 写死在 `HarnessAgentLauncher.kt:811`，它一处管三样：远端键的元组（事实三）、`WorkspaceManager` 的本地命名空间（`HarnessAgent.java:2416-2425`）、flush 与 maintenance 的节流键（`:2564`）。沙箱容器复用是另一颗旋钮（`:749` 取自 `SandboxConfig.kt:37`）。翻前者会把 `sessions/` 等全部路由的命名空间一起搬走，是搬家不是开记忆 |
| 匿名调用 | 装配期就没有归属可绑：不挂桶、不建 `MemoryConfig`、不给四个工具，warn 一句，投递照常完成 | 归属取自装配入参而非逐次调用——`RuntimeContext.userId` 同时是 `agent_state` 的分桶键（事实四），为了记忆把它填进去会把既有会话的状态整体换键。而 harnax 一个 agent 实例只服务一个用户（`DefaultAgentRunner.cachedAgent` 撞到归属不符就丢缓存重建），所以装配期取到的就是这次投递的用户，调用侧再传谁都改不掉桶的键。**读侧仍挡不干净**：`readMemoryMd` 走 `readWithOverride`，路由回空后它会无条件再读宿主盘的 `workspace/MEMORY.md`（`WorkspaceManager.java:819-823`），所以桶装了而桶里还没有 `MEMORY.md` 时，"没注入"要靠宿主模板本身为空才成立，见第 9 节第 6 条 |
| 存储落点 | **A：记忆的正文沿用 MinIO 文件层**，就是整理稿与按天流水两份文件，harnax 不加记忆内容表 | 上游三个写侧与两个读侧全都按文件读写；改 MySQL 投影要么自己重写 consolidator 的整篇改写语义，要么维护两份真值。B（加 `agent_memory` 投影表）只在"页面编辑记忆"或"按语义检索记忆"时才值得，本轮两者都不是目标。本轮确实新增了一张表，但它装的不是记忆：`memory_draft` 装的是**一条待人工决定的候选**——待并的文本、它并身对的那一版、来源清单与摘要（11.4），批准之后正文就落回文件层，表里留下的是一次决定的记录 |
| 记忆不进聊天 | 记忆内容不进会话消息，`MEMORY.md` 不进 `agent_state.context` | 上游只把 `MEMORY.md` 当系统提示注入，从不作为历史消息（`WorkspaceContextMiddleware.java:512-521`）；而 harnax 的历史读路径 `DefaultAgentRunner.kt:338-341` 只渲染 `agentState.context`（`AgentController.kt:105` 的 `GET /chat/history/{sessionId}`）。这条不变量是白送的，但要在测试里钉住 |
| 删除范围 | 删用户：该桶 `root/MEMORY.md` 加 `memory/` 前缀下全部对象；清会话：不动记忆 | 记忆按用户归；清会话是会话级动作，混在一起会让"清空当前会话"变成丢长期上下文 |
| 写序 | 同桶单写者：flush 与 maintenance 各自按桶键串行，harnax 不加第三条自动写路径 | 事实六的 last-write-wins 只在同桶串行时成立。`memory_save` 是模型驱动的第二条入口，是否保留由第 5 节决定 |

## 4. 开关矩阵

| 开关 | 今天 | 取定 | 说明 |
|---|---|---|---|
| `enableWorkspaceContext` | `false` | `true` | `<memory_context>` 只在这个中间件里注入（事实六）。代价是 `AGENTS.md`、`knowledge`、`plans` 的注入一并回来，这是本矩阵最大的连带面，必须与记忆同批验 |
| `enableMemoryHooks` | `false` | `true` | 装 flush 与 maintenance 两条钩子，两者在同一个 `!disableMemoryHooks` 块里（`HarnessAgent.java:2563-2599`） |
| `disableMemoryTools()` | 从未调用，builder 也没有透传 | 新增 `harness.memory.toolsEnabled`，假则调 `disableMemoryTools()` | 今天关不掉。四个工具在 `HarnessAgent.java:2688-2692`，注册条件是 `!disableMemoryTools` |
| `IsolationScope`（filesystem） | `SESSION` | `SESSION` 不动，记忆走单挂路由 | 见第 3 节 |
| `flushTrigger` | 缺省 `always()` | **两段式已走到第二段**：`throttled(5m)`，由 `HARNAX_MEMORY_FLUSH_TRIGGER` 切换 | `MemoryConfig.java:240` 的初值是 always，不改等于每轮多付一次模型调用；但事实五证明 `throttled` 在 CAS 缺失的 store 上直接静默，而 `always` 不经闸。所以这一档与 P3 绑死，配置项要能只改配置就切换 |
| `agentSpec.memoryEnabled`（本轮新增） | 不存在，只有部署级开关 | `true` 为缺省，值取自 `agent` 行的 `memory_enabled`，只有显式 `0` 才关 | 「默认所有的 agent 都有记忆」的落点：装配判据是 `memory.enabled && !isLead && agentSpec.memoryEnabled`（`HarnessAgentLauncher.kt:1259`），关掉单个 agent 不必关整域；部署侧既然整域默认打开，这一枚就是只把某个 agent 挡在记忆域外的唯一杠杆。会话层没有第二枚开关，这一枚一翻两层与晋升一起翻（见 11.3）。`!= 0` 而非 `== 1` 的理由在第 6 节 agent-service 那条 |
| `distributedStore` | 沙箱分支才给（`HarnessAgentLauncher.kt:807`，非沙箱分支 `:808-812`） | 保持 | 但它一存在就选中 `StoreBackedPeriodicGate`（`HarnessAgent.java:2409-2412`），于是撞上事实五 |

## 5. `MemoryConfig` 取定

| 字段 | 取定 | 理由 |
|---|---|---|
| `model` | 记忆域专用小模型，按模型域的行 id 解析：`HARNAX_MEMORY_MODEL_ID`，`0` 沿用各 agent 自己的主模型 | `MemoryConfig.java:247-261` 支持独立 model；不配就用主模型（`HarnessAgent.java:2562`）。按事实七，压缩与兜底两条路仍走主模型，这一处要写在验收之外 |
| `flushTrigger` | `throttled(5m)`——两段式的第二段，`MinioBaseStore` 的 CAS 已落地 | 每轮一次在长会话里等于每轮多付一次抽取调用，但节流档走那把坏闸（事实五）。`throttled` 的语义是首次立即跑、最小间隔只作用于其后（`MemoryConfig.java:45,76-84`） |
| `consolidationMinGap` | `30m`，从 `harness.memory.consolidation-min-gap` 取（`HarnessConfig.kt:70`）而不是硬编码上游缺省 | 让"每桶每 30 分钟至多一次整写"成为可判定条款，而不是缺省的副作用；这同一个窗口也是把会话层并成候选的节流间隔（11.4），一次改动同时挪两头 |
| `consolidationMaxTokens` | `4_000`（缺省，`MemoryConfig.java:55`） | 整篇整理稿的预算，直接决定注入的 token 上限；长期层那一段每次调用都要进上下文，注入侧就按这份预算折算字符上限并写明截断（11.1 末段） |
| `dailyFileRetentionDays` / `sessionRetentionDays` | `90` / `180`（缺省，`MemoryConfig.java:61,64`） | 日报归档天数与会话 JSONL 保留天数；新增的清理动作只有批准那一步按版本与字节复核清会话层（11.4、11.5），按天到期归档这一档两层共用同一条既有机制，不为新层另造清理器 |
| `flushPrompt` | 默认 prompt 之后追加两条：禁写跨用户与跨租户信息；禁写凭据与密钥 | 抽取器写的是文件，PII 与凭据一旦入档就跟着 `<memory_context>` 每轮进模型 |
| `consolidationPrompt` | 不改 | 该字段要求恰好两个 `%d` 占位且在构造期校验（`MemoryConfig.java:282-294`），自定义的收益不抵踩错占位形的风险 |
| 四个工具 | `memory_search`、`memory_get`、`memory_save` 与记忆同批；`session_search` 同批关 | 前三个是记忆仅有的读回与手工入档入口。`session_search` 不是记忆入口：它搜的是 `.log.jsonl` 会话原文，那些文件只由压缩的 offload 一步产出（`ConversationCompactor.java:152-164` 调 `MemoryFlushManager.java:207-211`，开关是缺省为真的 `offloadBeforeCompact`，`CompactionConfig.java:283`），且它只扫宿主盘、看不见别的副本（`SessionSearchTool.java:226-239` 的注释原文 "Only scans the local disk"） |
| `maxContextTokens` | `8000`（builder 缺省，`HarnessAgent.java:1240`） | 注入预算闸门；`WorkspaceContextMiddleware.java:229-240` 先扣其余上下文再决定 `MEMORY.md` 的截断 |

## 6. 改动清单

按模块，文件级：

- **harnax-harness-core**：
  - `harness/minio/MinioBaseStore.kt` 补 `putIfVersion` 与带版本的 `get`（事实五是硬前置，且它同时决定节流档抽取会不会跑）。MinIO 侧可用条件写或"版本号对象 + `If-Match`"实现；确实做不到就在装配时显式改选 `LocalPeriodicGate`（代价是每副本各整写一次），二选一，不许沉默。
  - `harness/minio/StoreCasProbe.kt` 与 `harness/minio/ProcessLocalCoordinationStore.kt`：把上面那句"二选一，不许沉默"落成代码。装配第一次需要这把闸时，先在同一命名空间里建一个带 uuid 的一次性槽，走三步判据（版本不符必须拒、版本相同必须放、对象已存在时 `expectedVersion = 0` 的建槽必须拒），据此把"接口默认恒拒"与"网关收了 `If-Match` 却不生效"这两种相反的故障分开；结论按进程记忆，但**只记 store 真答过的**——连接没通时的"没答案"不是关于 store 的判定，记下来会让 MinIO 重启一次就把整副本永久钉在本进程协调上；记下来的那份十分钟到了重问一次（11.4 同一条理由：修好存储不该要求重启）。判据不过就把 `["coordination", "periodic"]` 这一个命名空间改由 `ProcessLocalCoordinationStore` 就地应答（`ConcurrentHashMap.compute` 内完成比较与写，槽键按进程共享，形状对齐上游 `LocalPeriodicGate` 的静态表），其余键一律透传，所以记忆桶本身仍在共享 store 里；同时打一条 WARN 说明本次部署拿到的是哪把闸。非沙箱分支根本没有 distributed store，上游那两个钩子自带 `LocalPeriodicGate`，这条路径上只有长期层的装配不探测；凡是装了记忆的这台仍要探测，因为晋升这一步的节流槽用的是同一把 store 支持的闸（11.4）。装配日志里"这把闸按副本共享"的措辞跟着探测结果走，三种状态（无闸／真共享／本进程各算各的）各说各的，不许把退路报成前者。
  - `HarnessAgentBuilder.kt` 加三个透传：`memory(MemoryConfig)`、`disableMemoryTools()`、`filesystemRoute(String, AbstractFilesystem)`，对应上游 `HarnessAgent.java:1893,2232,1862`。
  - `HarnessAgentLauncher.kt` 的记忆装配段：按第 3 节与第 4 节挂记忆路由、装 `MemoryConfig` 与三个开关。路由的归属取自装配入参 `userIdentifier.userId`，取不到就整域关掉并 warn（见第 3 节「匿名调用」行）。`HarnessAgentWrapper.kt` 的 `userId` 因此保持不填——它进 `RuntimeContext` 就成了 `agent_state` 的分桶键，而历史读取按空用户寻址（事实四）。
  - `harness/config/HarnessConfig.kt` 与 `spring/HarnessAutoConfiguration.kt`：新增 `harness.memory.*`（`enabled`、`model-id`、`flush-trigger`、`flush-min-gap`、`tools-enabled`、`tenant-scoped` 桶形状），代码缺省 `enabled = false`（`HarnessConfig.kt:66`），绑定形状照现有 `enableMemoryHooks`（`HarnessConfig.kt:30`、`HarnessAutoConfiguration.kt:87,284-293`）；这套部署的 true 由 compose 注入，见下面 harnax-deploy 那条。装配分支上的 `MinioBaseStore` 现在只有一份（`HarnessAgentLauncher.kt:759-760`），两条分支共用，补了 CAS 的那一份因此天然覆盖两档。
  - `agent/AgentSpec.kt`：`memoryEnabled` 一个 `val`，缺省 `true`，配 builder 同名方法。装配判据读它（`memoryRequested`，`HarnessAgentLauncher.kt:1259`，装配处调用在 `:766`），所以它是运行侧唯一认识"某个 agent 不要记忆"这件事的地方。
- **harnax-entity**：`agent` 表加 `memory_enabled tinyint(1) NOT NULL DEFAULT '1'`，写进 V1 基线并同步 `harnax-entity/src/test/resources/schema-test.sql` 那份逐字副本；`Agent.kt` 加同名 `var`（缺省 1）、`AgentMapper.xml` 的 resultMap／insert 列与值／updateById 三处各加一行（insert 少了它行就落在 DDL 缺省上，向导的答复会丢）；线上契约 `AgentSpecInfoResponse` 加 `memoryEnabled: Int = 1`。
- **harnax-admin（智能体向导的开关）**：`AgentCreateRequest`／`AgentUpdateRequest`／`AgentResponse` 三处 `Int? = null`（可空才能把"没答"与"答了 0"分开），`AgentServiceImpl` 创建取 `?: 1`、更新用 `?.let`、两条读路径（`fromEntity` 与分页用的 `convertToResponse`）都要带上这个字段——少一处，向导就在一屏上看得见开关、另一屏看不见。`InternalApiController.buildAgentSpecResponse` 的 `memoryEnabled` 是**必填参数**（`:705`，报文里没有会话层那一枚）而不是缺省值：五个调用点里漏掉任何一个都会编译不过，而不是静默给每个 agent 下发"开"。四个读智能体行的调用点（`:470`、`:495`、`:533`、`:631`）把行上的值原样传下去，主管那一侧（`specForTeam`，`:666`）填 `1` 并写明拒绝主管的是运行侧 `!isLead`，不在这里重复一遍判断；builder 的写入在 `:964`。
- **harnax-agent-service**：`clearSession` 明确不清记忆（该动作只归会话数据）。`AgentSpecResolver` 把线上值映射进 `AgentSpec` 时判的是 `specInfo.memoryEnabled != 0`，与同一处 `skillSelfWrite == 1` 方向相反：授权型开关缺省为假、要显式给真，而这一域是这个 agent 的缺省状态、只有显式 0 才把它拿走，于是任何一个本运行时没预料到的取值都留在"有记忆"这一侧。报文里压根没有这个键时取的是线上契约的缺省 1（旧 admin 先发出去的那次滚动升级就属于这一类），两种拼写在这一格上等价，不等价的是越界取值。
- **harnax-admin**：记忆的读与删两个接口，作用域限于当前登录用户自己的桶，供页面核对与合规删除；删用户的记忆清理挂在既有删用户链上（`SysUserController.kt:119` 的 `DELETE /{id}`，实现 `SysUserServiceImpl.kt`），按桶前缀清 `root/MEMORY.md` 与 `memory/` 下全部对象，且**该用户所属的每一个租户各清一遍**（记忆桶按租户分键，只清当前租户会留下其余租户的对象）；admin 另读一颗与写入侧同名的开关 `harnax.memory.tenant-scoped`（`HARNAX_MEMORY_TENANT_SCOPED`，与 runtime 的 `harness.memory.tenant-scoped` 同值），关掉后桶键不含租户对，读侧必须跟着走，否则整桶记忆表现为空；store 拒绝一次读要答成故障（信封 503），不许伪装成"这个 owner 没有记忆"。admin 已有自己的 MinIO 客户端（`AdminMinioConfig.kt`）与按对象读写 MinIO 的先例（`TeamArtifactController.kt`），这两个接口直接照那一形状走，不经 agent-service；记忆小模型复用模型域。这两个接口的端到端覆盖是新增的 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/MemoryOwnerBucketMinioIT.kt`——本模块唯一同时起服务与接真 MinIO 的记忆用例，它灌进桶的对象正文用的是写入侧记录下来的那份字面量信封（第 8 节第 11 条、第 9 节第 9 条）。
- **harnax-session-router**：本轮不加新转发 —— 记忆不是实例本地状态，而是共享 store 里的对象，按第 3 节的桶键寻址；同一用户的两个会话即使绑在不同实例上，也读到同一份 `MEMORY.md`。抽取只在当次调用所在实例异步发生，不需要跨实例寻址。
- **harnax-deploy**：`docker-compose.yml` 给 agent-service 注入 `HARNESS_ENABLE_MEMORY_HOOKS` 与 `HARNAX_MEMORY_ENABLED` / `_MODEL_ID` / `_FLUSH_TRIGGER` / `_FLUSH_MIN_GAP` / `_TOOLS_ENABLED` / `_TENANT_SCOPED`，给 admin 注入 `HARNAX_MEMORY_TENANT_SCOPED`。两枚总开关在这一层取的是 `${VAR:-true}`（compose `:552`、`:553`），与 `application.yml` 里的 `false` 不一致——这是这一域唯一一处两侧故意不同值的缺省：compose 内的部署因此默认整域打开，而 `application.yml` 的 `false` 描述的是"在这份 compose 之外起的一次运行"，那里没有 MinIO，整域若也默认打开就让每个智能体都撞在装配期那道"没有 store"的拒绝上，连普通对话都起不来。其余各行仍与 `application.yml` 同值，所以不在 `.env` 里给值的部署，除这一域之外行为不变。这一层透传是这一域在集群部署里能不能打开的分界：compose 用的是逐服务的 `environment:` 而不是 `env_file`，没有对应行的变量在容器里根本不存在，`.env` 里单写 `HARNAX_MEMORY_ENABLED=true` 只会停在工作树。域的开法记在 `.env.example` 与 `docs/deploy-harnax-agent-service.md`、`docs/deploy-harnax-admin.md` 的环境表、`docs/deploy-harnax-harness-core.md` 的配置参考里：`HARNAX_MEMORY_ENABLED` 与 `HARNESS_ENABLE_MEMORY_HOOKS` 要一起给——现在两枚都由 compose 缺省成 `true`，要整域关掉得两个一起填 `false`，只给前者会在装配时拒掉整个智能体（连普通对话一起起不来）；单个智能体要不要记忆不在这里配，走向导那一枚「长期记忆」（第 4 节）。而 compose 钉成 `false` 的 `HARNESS_ENABLE_WORKSPACE_CONTEXT`（`:542`）不看这一域的请求——`enabled=true` 会强制打开它，那条 `SandboxConfigurationException` 噪声随记忆一起回来。
- **harnax-webui / harnax-ios**：向导的创建与编辑两屏各加一枚「长期记忆」——`CreateForm.tsx` 的 state 缺省 `true`，`UpdateForm.tsx` 按行上的值初始化为 `values?.memoryEnabled !== 0`，即"没答"与缺字段都算开、只有显式 `0` 才取消，与第 4 节那条反序判据一致；文案是新 key `pages.agent.memoryEnabled` 与 `pages.agent.memoryEnabledHint`（zh 643-644、en 644-645），hint 里点名"已经落盘的记忆文件不会删除，需要清理请到记忆管理页"，`/optimization/memory` 那一条是记忆清单页自己的路由；`typings.d.ts` 的 `AgentItem`／`AgentCreateRequest`／`AgentUpdateRequest` 三处各加 `memoryEnabled?: number`。记忆审批两屏是这一层新增的入口：`harnax-webui/src/pages/memory/drafts.tsx`（清单，缺省只列未决、可按智能体名搜）与 `harnax-webui/src/pages/memory/draftDetail.tsx`（左原文右并后、这一台还没有长期层时按「首份记忆」渲染，批准与拒绝各一枚，五种 `outcome` 都有回话），服务层 `harnax-webui/src/services/ant-design-pro/memoryDraft.ts` 与既有 `memory.ts` 并列，路由在 `harnax-webui/config/routes.ts:126-138` 登记 `/optimization/memory/drafts` 与 `/optimization/memory/draft/detail/:id`，入口挂在记忆清单那屏的页顶（`harnax-webui/src/pages/memory/index.tsx:343-348`），文案是 `pages.memory.draft.*` 那一族（zh 从 1421 起、en 从 1425 起，两份各 101 枚 `pages.memory` 键）。iOS 与聊天页不带这一层的界面。

## 7. 边界、失败与限制

| 情形 | 行为 |
|---|---|
| `putIfVersion` 未实现就开钩子 | maintenance 一次都不跑且无 warn，`throttled` 档的抽取同样一次都不跑（事实五）；`always()` 档不经闸因此照常跑，consolidation 退回文件 watermark。修法是先补 store 再开节流；补不了的部署由装配期探测接住（第 6 节）——探测不过就把协调命名空间改成本进程应答并 warn，代价退回"每副本各整写一次"，而不是整条节流档静默不跑 |
| 沙箱分支与非沙箱分支键形状不同 | 单挂路由不在两条分支里各写一遍，而是在两条之外只挂一次（`HarnessAgentLauncher.kt:832-833`），所以同一 owner 换部署档读到的是同一个元组；分支之间不同的只有 filesystem spec 与上游那两个钩子所用的 base store——沙箱分支（`:778` 起）在要记忆时把它换成 `memoryStore(wantsMemory, mountedBucket, minioStore)`（`:799`，内含 `coordinationStore()` 与按桶的整理进度包装），非沙箱分支 `:808-812` 不带 distributed store，因此上游那两个钩子在本副本各算各的。第 11 节那一层的晋升节流不在这一格上：两条分支都直接把 `coordinationStore(...)` 包成 `StoreBackedPeriodicGate`（`:846`），见 11.4 |
| lead agent | 装配判据 `memory.enabled && !isLead && agentSpec.memoryEnabled`（`memoryRequested`，`:1259`）里这一项与两条 filesystem 分支的 `!isLead` 是同一条理由（`:778`、`:808`）：lead 因此没有 filesystem，`WorkspaceManager` 的读写退回宿主盘且**不带命名空间**（`appendLocalFile`/`writeLocalFile` 落在 `workspace/<相对路径>`，`:851`、`:883`；读侧回落在 `:823`）。整域开着而装配的是主管时另留一条 info 点名原因（`:1276`）。记忆域只属于成员与普通 agent，lead 显式排除 |
| team 成员 | 成员各按自己的 agentId 建桶；同一次委派里 lead 与成员的记忆不通；一次委派里成员用的会话键由根会话推导（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:59` 拼成 `team-<rootSessionId>-m<memberAgentId>`），换一个根会话就是换一个键 |
| 投递没有指名用户 | 装配期就把整个记忆域关掉：不挂桶路由、不装钩子、不交工具，并留一条 warn 说明原因。这类调用今天占多数，所以"记忆没生效"首先该查这次投递有没有带上 user id。不给它建一个匿名桶是刻意的 —— 匿名桶会把不同人的日报混进同一个对象键 |
| 单个智能体自己在向导上关掉 | 读写与晋升三头一起停：不挂桶路由、不装 `MemoryConfig`、不给那四枚工具、不挂 `<memory_context>` 的注入、也不装提议晋升的那枚中间件（于是这一台的会话层再不会进审批队列）；`:852-866` 那三处开关（workspace context、memory tools、两个钩子）由同一个 `memoryEnabled` 一起翻。**已经落盘的对象一个都不动**——清空是记忆管理页与删用户那条链的活，翻这枚开关不产生删除；已经在队列里等的那一条候选也不动，它归审批台管。其余 agent 与整域照旧 |
| 这枚开关翻了何时生效 | 装配好的 agent 按会话缓存在 Caffeine 里 30 分钟（`DefaultAgentRunner.kt:78-80`），旧会话在这次过期之前仍按装配时的那份答复跑；要立刻换，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105` 的 `POST /api/admin/agents/refresh-sessions` 推一次 REFRESH，运行时丢掉该会话的缓存条目（`DefaultAgentRunner.kt:304`），下一条消息才重建。这一段窗口本轮未实测（第 9 节） |
| 记忆的所有者只在装配期绑定 | 上游按 `RuntimeContext.userId` 给持久化 agent 状态定槽位（`ReActAgent` 的 `slotKey(userId, sessionId)`），而 harnax 读会话历史的几处都用空用户寻址同一个 store。两者是同一个值，所以 `HarnessAgentWrapper.userId` 保持不填；把它填上会让已经落库的状态行读不到。所有者因此来自投递身份，在装配时算好并固定到路由上 |
| 两副本同桶并发 append | append 是读全文加全量回写，跨副本可能互相覆盖（事实六）。不变量是"最多丢一次并发写"，不是"绝不丢"；第 4 节取定的 `throttled(5m)` 把这个窗口夹在 5 分钟里，退回 `always()` 的部署档下每轮都写、窗口最大 |
| `memory_save` 与 consolidation 撞车 | 整篇改写可能重排 `memory_save` 刚追加的那段；按桶串行才有保证，不串行就把它当可接受的最终一致 |
| `MEMORY.md` 越写越长 | 由 `consolidationMaxTokens` 与 `maxContextTokens` 两处夹住，超了是注入前截断而不是拒写 |
| 日报被归档 | 90 天后移入 `memory/archive/`（`MemoryMaintenanceMiddleware.java:234`），`memory_search` 因此少一档可搜的历史；本轮不做归档回读 |
| 记忆页请求里的 agent 名字带路径形状 | 实测是两层不同的拒：仍然拼得出一个合法路径段的（`a..b`、`...`）走到 `MemoryObjectKeys.isValidAgentId`，按本模块业务失败的形状回答——HTTP 200，信封里 `code` 400 且不带 data；要编码之后才成为路径分隔符的（`%2F`、`%5C`）与本身就是上跳段的（`..`、`%2E%2E`）在 `JwtAuthenticationFilter` 之前就被 Spring Security 的路径防火墙丢掉，GET 与 DELETE 都是 HTTP 401，请求根本到不了控制器。两种都在 store 之前被拒，判据是拒前后各取一次全桶键快照逐字相等（`MemoryOwnerBucketMinioIT`） |
| 提示注入 | `MEMORY.md` 每轮以 `<memory_context>` 进模型，等价于一段用户可控的长期系统提示。会话层这一头的写侧只有 agent 自己（那几枚工具与抽取器），没有外部通道能直接改这一格。长期层现在多了一次外部写入：唯一的写者是人工批准落库（11.4），而它写进去的是模型合并出来的文本，也就是说对话内容有第三条路进系统提示——这条路比单层时多的一道闸是"有人读过并签了"：审批那一屏把待并正文、它并身对的那一份与全部来源文件原文摆在同一屏上，读不到原文就签不了。风险记此，这一层不再额外设防 |

## 8. 测试方案

主断言（缺一条就不算做完第 1 节）：

1. **跨会话想起**：桶内会话 A 跑一轮（替身 model 桩住抽取），等记忆后台静默后断言该桶 `memory/<date>.md` 新增条目（一轮抽取不写 `MEMORY.md`，事实六）；断言这一条只走到会话层与一条未决候选，`root/MEMORY.md` 逐字未动；再走一次人工批准，断言 `root/MEMORY.md` 含该条；同桶新建会话 B，断言 B 首轮推理输入含 `<memory_context>` 且能搜到那条。
2. **投递没有用户就整块没有记忆**：以空 `userId` 装配一个 agent，断言工具清单为空、日志里恰好一条说明原因的 warn，并且这个 agent 仍然建得起来（关掉记忆域不等于这一轮请求失败）。
3. **租户隔离**：同一 `userId` 在两个租户下各跑一轮，断言两个桶的对象键不同且互不命中 —— 这条直接测第 3 节的元组。
4. **闸真的抢得到**：`MinioBaseStore.putIfVersion` 两条用例（对象不存在时创建成功、版本不符时返回 false）；再加两条反证 —— 把 `putIfVersion` 桩成恒 false 时，`throttled` 档的抽取与 maintenance 都不落盘，而 `always()` 档的抽取照常落盘（事实五的三段判据）。
5. **开关不成半开**：三档用例（全关、全开、只开钩子），断言"工具在清单里"与"钩子已装"这两个布尔的组合与第 4 节矩阵逐行一致。
6. **记忆不进聊天**：跑完带记忆的会话，断言 `GET /api/agent/chat/history/{sessionId}` 的返回里没有 `MEMORY.md` 与日报的任何片段。
7. **清会话不动记忆、删用户动记忆**：两条分别断言对象在与不在。
8. **抽取失败不影响回答**：把记忆 model 桩成抛异常，断言当轮回答照常返回、等静默后该桶日报与 `MEMORY.md` 逐字未变（失败只记 warn，`MemoryFlushMiddleware.java:273-277`）。
9. **每轮调用次数封顶**：一轮对话断言记忆档 model 调用为 1 次；同一轮若触发压缩则为 2 次且第二次落在压缩模型上（第 1 节第 4 条的上界）。
10. **单个智能体的答复优先于整域**：整域开着时 `memoryEnabled = 0` 的那台没有钩子、没有工具、没有桶路由，也不触发 store 的闸探测；同批装配的 `memoryEnabled = 1` 那台三样都有，两者互不影响。解析侧另断言两档：报文给 `0` 映射成关、报文压根没这个键映射成开。这是第 1 节第 3 条在 agent 粒度上的重复——半开同样算没做完。
11. **admin 的读与删打在真桶上**：这是拍板时点名要的一条端到端 IT（`MemoryOwnerBucketMinioIT`，harnax-admin），起整个 admin 服务对着 Testcontainers 的 MinIO，按写入方的键布局灌对象，再用带 JWT 的 HTTP 断言三件事——列表与详情给出的正文是信封里的 `value.content` 而不是整段 JSON，删除只清空被点名那个 agent 的前缀且两类邻居（同租户的另一个用户、同一用户在另一个租户下的一份）逐字节留在原处，空桶的调用方拿到的是成功的空列表而不是错误。无 JWT 的调用与"编码之后才会成为路径段"的 agent id（`%2F`、`%5C`、`..`）都在到达 store 之前被拒，断言方式是前后取一次全桶键快照逐字相等。这条同时是跨模块信封契约的读者侧：信封的字面量在写入侧与读者侧各存一份、两个测试类互相点名，写入方改了形状两处一起红（见第 9 节第 9 条）。

跑法沿用本仓既有配方（JDK 21、先探 Docker 再决定排除 IT）。桶的读写在单测里可以用 `InMemoryStore` 顶，但第 4 条必须打在 `MinioBaseStore` 的真实现上，否则正好绕过事实五。第 11 条反过来不能用桩：mock 出来的客户端只会回答桩让它答的内容，前缀列表、递归标志与删除的作用域这三件事只有在真服务器上才成立。上面这一张清单只覆盖第 1 节那一轮的形状；会话层、候选入账与人工批准那三段的断言逐条列在 11.12 的对账表里，不在此重复。

## 9. 验证与未验（逐条对账）

表里的「条」就是本节末尾那张编号清单的条目号：第 1~6 条对第 1 节那六条主断言，第 7、8 条对 agent 粒度那枚开关的四段接缝与生效窗口，第 9 条对 admin 读写两头的跨模块信封契约：

| 条 | 状态 | 判据 |
|---|---|---|
| 1 | 已验 | 覆盖关系与两条装配分支由 `HarnessAgentLauncherMemoryTest` 的 `the two memory files move to the owner bucket while everything else stays put` 与 `a sandbox deployment gets the same owner bucket for its memory` 断言，两条都带"其余文件留在原处"的反证；`glob("*.md", "memory")` 走的是桶由 `MemoryBucketPipelineTest.the ledger glob answers from the owner bucket` 断言 |
| 2 | 已验（MinIO） | `MinioBaseStoreCasTest` 在真 MinIO 上断言 CAS 建槽、版本不符退 `false`、多写者只放一个、以及上游 `StoreBackedPeriodicGate` 按窗口只放行一次。其它 S3 兼容档给不给得出 CAS 由装配期当场定性：`StoreCasProbe` 对当次部署的 store 探一次，探不过就把协调命名空间换成本进程应答并 warn，两种相反的失效（默认退让与忽略版本前置）各自被断言。判据在 `StoreCasProbeTest`（7 条，含"探不通不算结论"）、`ProcessLocalCoordinationStoreTest`（5 条，含"协调槽不外泄给 delegate"）与 `HarnessAgentLauncherCoordinationTest`（5 条，含"结论按进程记一次"与"结论过了十分钟重问一次"）；仍未实测的是别家 S3 档在真服务器上到底落在哪一种，以及回退档在多副本下各整写一次 `MEMORY.md` 的真实代价 |
| 3 | 已验 | `MemoryGateFalsificationTest` 直接以 `MemoryBackgroundTasks.awaitQuiescence(60s)` 当落盘探针，进程级计数没有把用例耗时变成阻塞问题 |
| 4 | 未验（交人工） | 抽取质量要真模型，替身 model 测不出来。本轮不跑：判据是在装了这一域的部署里发起一轮**带用户身份**的会话（匿名投递在装配期就没有桶，见第 7 节），等记忆后台静默后看该桶当天的 `memory/<date>.md` 写了什么——召回够不够、噪声有多少、有没有把凭据或别人的信息写进去（第 5 节那两条禁令是否真挡得住）。这一份正文不在记忆页上：页面那一屏只列长期层（11.6 第一条），而抽取落在会话层，页面上能看见的只有那一行的「待并入」计数。看原文有两个去处：直接按第 3 节的键取 MinIO 里的会话桶对象，或者在审批台打开这条候选，读它点名的每一个来源文件的原文 |
| 5 | 未验 | 只断了布尔形状（`on needs the workspace context even when that knob stayed off`），`AGENTS.md` 与 `knowledge` 注入回来之后的 token 与行为变化没测 |
| 6 | 已验（挂桶侧） | `MemoryBucketPipelineTest.the memory pipeline leaves no copy on the host disk` 断言抽取与整写两条腿都不在宿主盘留 `MEMORY.md` 与日报；未挂桶（匿名）装配下宿主回退到底读到什么仍未验 |
| 7 | 已验 | 四段接缝各有断言：列与行的往返由 `AgentMapperTest$CustomQueryTests.the memory answer round-trips through the agent row` 打在真 `mysql:8.0` 上（insert 与 updateById 都带上这一列，缺省 1、显式 0 落 0）；admin 写侧由 `AgentServiceImplTest` 四条（`createAgent should default memory on when the request is silent about it`、`createAgent should honour an agent that asked for no memory`、`updateAgent should write the memory answer it is given`、`updateAgent should leave memory untouched when the request omits it`），下发由 `InternalApiControllerTest.getAgentSpec delivers the memory answer written on the agent row`；读侧映射由 `AgentSpecResolverTest` 两条（`an agent that turned memory off arrives turned off`、`memory arrives on for a delivery that never names the switch`）；装配由 `HarnessAgentLauncherMemoryTest` 两条（`an agent that turned memory off gets no hooks no tools and no borrowed reader`、`an agent that turned memory off does not get the store probed for a gate`） |
| 8 | 未验 | 翻了开关到下一次装配之间的窗口（第 7 节「这枚开关翻了何时生效」那一行）只有源码层结论：30 分钟的会话级 Caffeine 缓存与 `refresh-sessions` 推 REFRESH 都是本域既有的机制，尚未起真服务复现 |
| 9 | 已验（两侧，真 MinIO） | 跨模块的信封契约两头各钉一次。写入侧 `MemoryObjectKeyCrossCheckTest.the wrapped body is the envelope admin's reader decodes` 从真 MinIO 把刚写出去的对象正文整段读回来，抹掉两个时间戳后与一份记录下来的字面量逐字相等；读者侧 `MemoryOwnerBucketMinioIT` 用同一份字面量灌桶，再走 HTTP 断言列表与详情给出的是 `value.content` 的文本。两侧的测试类互相点名，改一处必红两处。这一条纠正了一个推断：`value` 里的字段顺序不是 `StoreWrapper` 的声明顺序，而是框架把文件数据装成 `HashMap` 时的顺序（上游 `RemoteFilesystem.fileDataToStoreValue`），`content` 排在 `created_at`、`encoding`、`modified_at` 之后——按类声明写下来的字面量是错的，只能从真写回来的字节上读 |

1. 单挂路由与 spec 内置路由的**覆盖**关系：源码层成立（外层 Composite 先匹配），但 `MEMORY.md` 与 `memory/` 两条前缀同时被覆盖时，`glob("*.md", "memory")` 走哪一层要实测。沙箱分支的 routes 走的是 `RoutedSandboxFilesystem`（`HarnessAgent.java:2451-2455`），记忆读写不落进容器这条同样需要实测。
2. MinIO 之外的兼容档位能否给出 CAS 语义（条件写、版本号对象或 `If-Match`），由装配期那一次探测当场定性并留 warn；仍未实测的是某一家具体档位在真服务器上会给出哪个答案，以及回退档在多副本下各整写一次 `MEMORY.md` 的真实代价。
3. `MemoryBackgroundTasks.awaitQuiescence`（`MemoryBackgroundTasks.java:69-87`）能否当验收探针：它目前只在 `HarnessAgent.close()` 里被调用过（`HarnessAgent.java:455-463`），测试里直接调它等异步落盘，上界够不够要实跑。另要注意它的 in-flight 计数是**进程级**而非按桶（`MemoryBackgroundTasks.java:26-31` 的类注释与 `:38` 的静态字段），所以它会等上别的桶的任务，用例耗时因此不可预期。
4. 抽取质量：默认 flush prompt 对中文对话的召回与噪声水平，决定第 5 节里 prompt 追加规则的措辞与 `consolidationMaxTokens` 要不要下调。
5. `enableWorkspaceContext = true` 的连带影响面：本轮只在测试第 5 条断言布尔形状，`AGENTS.md` 与 `knowledge` 注入回来之后的 token 与行为变化没有测。
6. 宿主盘的两个根不一致：`readWithOverride` 的回退读的是不带命名空间的 `workspace/MEMORY.md`（`WorkspaceManager.java:823`），而 `listMemoryFilePaths` 扫的是 `resolveRuntimeDataPath(rc, MEMORY_MD)`（`:960`）。两者不同根，`memory_search` 能列到而 `<memory_context>` 读到的可能不是同一份，反之亦然 —— 需要实测确认在挂了所有者桶与没挂桶（第 7 节「投递没有指名用户」那一行）两种装配下各读到的到底是什么。
7. 向导上那枚「长期记忆」的四段接缝：DB 列 → admin 写侧与下发报文 → 运行侧解析 → 装配判据（第 6 节那四条模块各占一段）。漏任何一段的表现都不一样——mapper 的 insert 或 updateById 少这一行，值就落在 DDL 缺省 1 上，向导那次"关"根本存不进行，页面重新打开又显示"开"；两条读路径少一条，是一屏看得见开关、另一屏看不见；下发少一个调用点，是行上关了而运行时照样给这台装记忆；解析若写成授权型方向（`== 1`），由于线上契约的缺省本来就是 1，老 admin 的报文仍然判成有记忆（那一档没事），真正被反过来的是行上落下非 1 取值时——这一列是缺省状态而不是授权，越界取值该留在"有记忆"那一侧。
8. 这枚开关翻了之后的生效窗口：结论（30 分钟会话级缓存、`refresh-sessions` 可提前推 REFRESH）来自读现有代码，没有起真服务复现，因此第 7 节那一行是机制说明而不是实测结果。
9. admin 读回的字节与运行时写出的字节之间没有共享类型：`MinioBaseStore` 在 harness-core，`MemoryRecordParser` 在 harnax-admin，前者给每个文件套的那层信封当时只按源码推断成 `{"key":…,"value":{"content":…},…}`。两头没有一处编译期牵连，所以契约要么靠一份记在测试里的字面量钉住、要么每次改动都靠人记着同步，需要一次真桶上的两端对账。

## 10. 明确不在本轮 + 拍板结果

不在本轮：记忆内容的页面编辑与语义检索、`extensions-mem`（Mem0、ReMe、百炼）与 `agentscope-service` 的托管记忆服务、`MEMORY.md` 的版本历史、日报归档的回读、跨 agent 的用户记忆合并、iOS 与聊天页的任何改动、把记忆用于跨会话检索。记忆的会话粒度分层不在这——它是第 11 节那一轮，已按该节口径落码（落地形状见 11.11）：凡装了记忆的 agent 两层常在，会话层没有开关（11.3），从会话层出去的那一步要人工批准（11.4）。

前五处拍板都按表中"推荐"项执行，第 6 节的改动清单即其落地形状；P6、P7 是执行过程中新增的两处，落在同一张表里备查，不是待定项。

| 拍板项 | 选项与推荐 |
|---|---|
| P1 记忆归属维度 | 推荐 `tenant × user × agent` 单挂路由（第 3 节）；次选纯 `user × agent`（改动最小，但同 `userId` 跨租户同桶）；不建议把 filesystem 的 `IsolationScope` 整体翻成 `USER` —— 它一处管三样（远端键元组、本地命名空间、flush 与 maintenance 的节流键，见第 3 节），翻它是搬家 |
| P2 存储落点 | 推荐 A（MinIO 文件层，不加表）；B（MySQL 投影 `agent_memory`）只在要页面编辑或语义检索时才有价值，而这两件都排在上一段之外 |
| P3 `MinioBaseStore` 的 CAS | 推荐补实现（第 6 节）。这把闸同时决定节流档抽取会不会跑，不是只影响整写；退路是记忆域强制 `LocalPeriodicGate`，代价是多副本下每副本各整写一次 `MEMORY.md` |
| P4 `flushTrigger` | 与 P3 绑死：CAS 落地前保持 `always()`（每轮一次抽取，走记忆小模型），落地后切 `throttled(5m)`。单独推 `throttled` 在 CAS 缺失的部署档下等于把记忆整个关掉（事实五）。**已结案**：CAS 与装配期探测都落地，compose 的缺省即 `throttled` + `5m`（`harnax-deploy/docker-compose.yml:558-559`） |
| P5 `enableWorkspaceContext` 与记忆同批 | 推荐同批（记忆读侧必须靠它），但要接受它是本矩阵最大的连带面；分批则本轮记忆只写不读，验收第 1 条要相应降级 |
| P6 记忆的默认归属 | 拍板：**默认所有 agent 都有记忆**，向导上另加一枚「长期记忆」逐台取消（第 4 节 `agentSpec.memoryEnabled`，只有显式 `0` 生效）。次选是反过来的授权型——默认没有、逐台点开，那要每一台新 agent 都有人去点一下才开始有记忆，与"记忆是这台 agent 的缺省状态"这条口径相反 |
| P7 部署侧缺省 | 拍板：harnax-deploy 的 compose 把 `HARNAX_MEMORY_ENABLED` 与 `HARNESS_ENABLE_MEMORY_HOOKS` 都缺省成 `true`，`application.yml` 保持 `false`。理由是这份 compose 之外起的一次运行没有 MinIO，整域若也默认打开会让每个智能体都撞在装配期那道"没有 store"的拒绝上、连普通对话都起不来；两侧不同值是刻意的，落地形状见第 6 节 harnax-deploy 那条 |

## 11. 记忆分层：会话粒度与长期粒度并存

一句话口径：一台 agent 的记忆分两层，而两层之间没有开关。**会话层**装"这次聊天里说到、但还没有沉淀下来的"，只对当前这个会话有意义，凡是拿到了记忆的每一段对话都有自己的这一层；**长期层**装"跨会话都要记得的"，就是第 3 节那一份按 租户 × 用户 × agent 归桶的策展记忆，也是每一台新会话开局就读到的那一份。会话层的内容由对话自动产生，隔一段时间自动**并成一份候选**并送去给人看；候选被批准，长期层才被改写、会话层才被清掉，而批准改写的长期层那一头有两样东西——那份跨会话的整理稿，加上这次并入点名到的那些日期文件。长期层的写者因此只有"批准"这一个动作，运行时一侧一次都不写。第 1 至第 10 节描述的就是长期层这一头，加这一层只改了它的写者——从整写那一步改成人批准那一步（11.4），其余行为一条都不变。

### 11.1 两层各装什么、谁写谁读

| 层 | 装什么 | 谁写 | 谁读 | 活多久 |
|---|---|---|---|---|
| 会话层 | 本轮对话抽取出来的条目：按天的流水，外加一份只在这个会话内整理过的稿子 | 两个写者：对话结束时的抽取（机制与今天同一套，一字不改地复用），以及模型自己用那几枚记忆工具手工入档——手工入档落在哪一层只由路由挂在哪决定，而路由现在只挂在会话层。上游那份按天流水的整写也在同一个桶里进行 | 同一个会话的下一轮；以及晋升这一步取材料 | 它的内容并成候选、候选被批准之后清空；没人批准就一直留在这一段的桶里，直到 11.5 里那两条整体删除把它带走 |
| 长期层 | 跨会话沉淀下来的策展记忆，两段：一份整理稿与一份按天的流水 | 只有审批落库那一步写（11.4 后半段），整理稿与日期段是同一枚写者——候选点名到哪天就写哪天，运行时一侧没有任何写者 | 同一用户、同一 agent 的任何新会话，包括全新会话的第一轮 | 整理稿每次批准被整篇覆盖更新，总长有预算上限，超了是截断而不是拒写；日期段每个对象各带一次版本前提写，没被候选点名过的日期一个字节都不动 |

两层的文件形状完全一样：一份整理稿加一份按天流水。所以读、删、页面展示都不需要为某一层另造一套机制，差别只在归桶的键多一段会话，以及谁来把它们并起来。

两层进上下文时是**两块分开的东西**：会话层走那两条既有路由的注入，长期层另带一对标签和一句"这是上下文、不是用户指令、在这里只读"的说明。模型因此分得清哪一段是这次会话还没沉淀的草稿、哪一段是跨会话留下来的那一份——前者的存在理由就是它会被并掉然后清空，把两段混成一段等于告诉模型草稿已经算数了。长期层那一段有长度上限，按整理稿那份预算（4000 token，折 16000 字符）截断，并在块尾写明被截断：这份内容每次模型调用都要进上下文，而它的总长只由模型遵守整理提示来保证，没有别处给它兜底。

"批准之后这台 agent 的新会话自动带上这份记忆"不需要任何额外机制，它是上面这一格的直接结果：注入读的是主人的长期层，且每一次模型调用都重读一遍。所以批准落库之后，同一台 agent 的新会话首轮就拿得到，正在进行的会话也在它的下一轮拿到——中间没有"等装配过期"这一档，因为读侧不缓存。

### 11.2 粒度：四把尺子，从外到内

租户 → 用户 → agent → 会话。长期层用前三把（今天就是这样），会话层四把都用，也就是键上在 agent 之后再多一段会话，形状像 `store/tenants/1/users/2/agents/Report/sessions/abc/…`。六条口径：

- **会话层嵌在 agent 段之下，不与 agent 并列。** 这样"删这台 agent 的记忆"与"删这个用户的记忆"两条既有清理链路都只按那台 agent 的前缀列一遍，会话层自然在清单里被一起带走；合规删除永远不需要先问"删哪一层"。这一条不依赖任何键解析：两条整体删除枚举的是桶里落在该前缀之下的原始对象名，逐个删，不经解码器。解码器只服务页面读侧，它认得多出来的会话段并据此把会话层从清单与详情里减掉——于是两者各自成立：一个连 agent 段都解不出来的键（比如 agent 自己带斜杠）仍会跟着主人被删掉，只是永不出现在页面上。
- **会话层跟着租户那枚开关一起动。** 部署侧把记忆键上的租户段关掉时，两层同时从用户段起键、各自少一截，不会出现一层按租户写、另一层不按租户读，因此不需要为会话层新增第二枚开关。
- **会话层不跨 agent。** 团队里主管把活委派给成员时，成员有自己的两层，读不到主管那个会话的会话层。这与第 7 节"成员各按自己的 agent 建桶、同一次委派里主管与成员的记忆不通"是同一条口径。
- **匿名投递两层都没有。** 会话层同样需要用户那一段，否则不同人的流水会混进同一个键。第 7 节"投递没有指名用户就整域关掉"那条判据不变，只是关掉的范围现在覆盖两层。
- **换粒度不等于搬家。** 只有整理稿与按天流水这两条路由换键，会话状态、沙箱工作区、其余文件的隔离档一个都不动，理由与第 3 节"单挂路由那两条、其余不碰"是同一条。
- **会话那一段只能让桶更深，不能让它挪窝。** 会话 id 原样拼进键，所以一个带斜杠的 id 只多出层级——`sessions/a/b/root/MEMORY.md` 仍在那台 agent 的前缀之下，跟着 11.5 那两条整体删除一起走。想把桶往上挪的 id（`../`、挪到够得着别人的用户段那种）在写入那一刻就被存储按对象名规则拒掉：`.` 或 `..` 那一段不允许出现在对象名里。这一拒落在"存储读不出来"那一档，与 11.10 第三条说的是同一种故障，因此它不会把这一层读成空层。`MemorySessionKeyShapeTest` 在真 MinIO 上把两种形状各钉一次，钉的是「落在 agent 前缀之外的对象数为 0」而不是「它抛了异常」——换一个会自己归一化路径的存储档时这条断言会红，而不是悄悄放过。

### 11.3 没有开关：凡有记忆的 agent 两层常在

装配判据只有第 4 节那枚「长期记忆」：`memory.enabled && !isLead && agentSpec.memoryEnabled`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:1259`）。它为真时两层一起有——抽取先进会话层、路由挂在会话层、长期层以只读块注入、晋升这一步把这一段的层并成候选入队；它为假时整域没有：不挂桶、不装抽取、不给那四枚工具，注入与晋升也都不装。

| 长期记忆 | 结果 |
|---|---|
| 关 | 整台 agent 没有记忆域，两层一起没有 |
| 开 | 双层：抽取先进会话层，按 11.4 并成候选待人审批，两层都进模型上下文 |

也就是说这一层不逐台选，也没有"整份部署翻成双层"的那种部署侧缺省：`agent` 行上没有与会话层有关的列，下发报文里也没有，向导上只有一枚「长期记忆」。库里的形状因此是两条前向增量对着一列的往返——`harnax-admin/src/main/resources/db/migration/V2__agent_session_memory.sql` 加过 `session_memory_enabled`，`harnax-admin/src/main/resources/db/migration/V6__drop_agent_session_memory.sql` 把它删掉，而 `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` 从未含它（这一列走的是增量而不是基线，理由见 11.11 的迁移那一行）。`harnax-entity/src/test/resources/schema-test.sql` 那份逐字副本跟着同一个终点。

会话层与长期层之间没有可翻的档：想让一台 agent 只留一层，唯一的办法是不给它记忆。

最后一枚相关的形状是运行侧唯一一处"看起来有记忆但长期层不再变"的部署：候选队列的适配器（`agent/adaptor/MemoryDraftAdaptor.kt`）由 Spring 装配透传（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt:373`），取不到 bean 时会话层照常填、长期层照常注入，只有"出得去会话层"的那一步不装，并留一条 warn 点名原因与修法（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:851-861`）。这一档写在这里是因为它从页面上看和"桶坏了"一模一样：记忆页的正文一格不动，而每一段对话都确实在写自己的层。

### 11.4 候选入账与人工审批

**运行侧：什么时候并、并出来的东西交给谁。**

- **触发靠节流定时，不靠"会话结束"。** harnax 里没有可靠的会话结束信号：绝大多数会话只是不再有请求，而"清空会话""删除会话"只在用户主动操作时才走。把晋升挂在那两个动作上，表现就是一部分会话的记忆永远停在会话层。取定的做法是这个会话每有新对话就顺带看一眼到没到点，到点就并；间隔是一枚配置项，缺省 30 分钟，与长期层整写的节流同源；负值在装配那一步就被拒，`0` 是合法值，意思是每一轮都赶一次晋升（除这一轮自己的模型调用外再多一次）。**判"到没到点"排在"这一层有没有东西"之后**：占槽不可退回（这把闸只有一个方法，赢了就把窗口关掉的时间戳写进去），而往这一层填内容的抽取是在回答之后才派发的、还要走一次模型往返，所以第一轮通常读到空。先占槽再读，就会把整个窗口花在空层上——比一个窗口还短的会话（占了大多数）从此一次都不晋升。读不出来的那一档按空层同样处理：不占槽，窗口留给先填上内容的那一次。
- **并的动作止于一份候选，而这一份候选覆盖 `1 + K` 个对象。** 取会话层的整理稿与还没并进去的流水（只取流水段的直接子对象，名字读不成日期的不算，按日期升序最多取 7 天，超出上限的那几日是延后而不是丢），连同长期层现有内容一起交给模型，产出一份**合并后的完整长期记忆**；然后每个入选日期各并一次，把这一天的会话流水并入这台 agent 自己那一天的长期流水，每笔各得一份文本与它读到的版本。最后把结论那份文本、它读过的长期层正文与那一刻的版本号、这 K 条日目标（路径、并入基准版本、该日原文、并入后文本），以及它取材料的每一个会话层对象与当时读到的字节，一起作为一条候选入队。**长期层一个字节都不写，会话层一个对象都不删**：在有人读过它之前，会话层是这些条目唯一的一份副本，而一条没人看过的候选不构成扔掉它的理由。取材料的那些对象连同字节一起进候选，正是批准那一步能够只清"字节没变的"的依据。
- **按天那几笔各自有前提，任何一笔给不出可用答案就整条不提。** 这台 agent 自己那一天先读一次：存储答不出的那一档是故障不是空缺，整条候选就此不提；这一天并完的答案若不足它读到的那一份的一半，同样按"模型把这一天丢了"处理，整条也不提；一次提名里所有模型调用共用一份总预算（缺省 25 分钟），单笔仍受 5 分钟上限，预算见底时剩下那些天不并、也不提。三条里任何一条命中，入账接口一次都不会被调用，会话层与长期层照旧一个字节都不动。没入选的日期既不进材料清单，也不进结论层那一次合并的输入。
- **结论层的合并结果缩水就按模型没做完处理。** 结论那份文本明显比长期层现有的短（不足一半），这一次算失败：不入队、不清理、留一条日志。提示里那句"不许丢条目"并不能保证模型真把两份都写全，而失败的方向不能是"一份把长期层换掉了的文本被送去等人批准"——那是这一层唯一一份每次调用都进上下文的内容，而塌掉的稿子与认真整理出来的稿子从队列页上看一模一样。**这一道不设"已经超预算"的例外**：飘到预算之上的那一份最容易被模型答成四分之一。超预算的层照样会被整理：一次窗口收进一半，几个窗口收敛到预算内，代价只是慢，不是丢。
- **一次合并有时间上限（缺省 5 分钟）。** 它跑在会话共用的那个阻塞调度器上并占住其中一个 worker，也在优雅停机要等的在途计数里；没有上界的等待等于让一个不回答的模型一直占着这一份并发能力。上限取得比一轮对话自己的超时宽松，因为一次合并要在一个补全里重写整份策展层。超时按失败处理，与上面两条同一条口径：会话层原样留着。
- **失败照样把整个窗口花掉，这一条按接受处理。** 那把节流闸只有"抢"这一个动作、没有归还：模型出错、超时、缩水、读被拒、入队被拒任一发生之后，这个会话要等满一个窗口才有下一次机会。接受的理由是失败的方向已经安全——材料逐字留在会话层，代价只有延迟。换到的只是早几分钟重试。
- **入队这一步是一次 HTTP 调用，它的判据全在管理面。** 报文里只有会话 id、agent 名、两份文本、一个版本号、来源文件清单与按天日目标（每条给路径、并入基准版本、该日原文与并入后文本），**没有租户也没有用户**：主人、租户、这一份记忆该进哪个桶，全部由 admin 从会话 id 反解（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:478`）。反解认三种形状——网页与小程序的会话读 `session` 行的创建者与 agent；定时任务的会话这一库里没有行，主人从 scheduler 按 HTTP 取、agent 取自任务契约；团队成员的会话是本系统自己造的名（`team-<root>-m<agentId>`），主人是根会话的、agent 是尾部点名的那一位。`chn-` 反解不出主人（那是聊天平台的发送者 id，不是本系统的用户），因此这一档直接拒。反解之后还要核四样：报文点名的 agent 与这行 agent 名字一致（否则批准会把这段对话的文字并进别人的记忆）、行与主人同租户、来源清单里每一条路径都解析得出一个对象键（否则批准清不掉它，这条候选就是个死账）；每一条日目标都解析得出这台 agent 长期流水里真实存在的那一天，也就是 `memory/<YYYY-MM-DD>.md`，同一天不许出现两次、正文不许为空，条数与字节各有上限（64 条、单条 200000 字节、合计 2000000 字节）。
- **一段会话同时只留一条未决候选。** 新的合并覆盖旧的那一条（同一份层加上新写的几轮，新的一份包含旧的），而被决定过的那一条不再被改写——它是一次决定的记录，覆盖它等于把决定抹掉。覆盖发生在这两条候选之间无人读过的时候，所以被换掉的摘要不是错误；如果覆盖恰好发生在有人已经打开之后，那次批准会被摘要比对挡住（下面第 2 条）。
- **三类答案必须分得开。** 已入队（拿到候选号）／被拒（重试还是同一个答案）／够不着（admin 或网络没答，什么都没写）。后两档的表现都是会话层原样留着，但只有第三档意味着下一窗口的重试还有意义——运行侧对第二档重复入队是白烧一次模型。这一分法落在 `MemoryDraftIntake` 三个分支与 `AgentApiClient` 的映射上（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt` 的 `submitMemoryDraft`：`code` 200 且给出正的候选号算已入队，400 与 404 算被拒，其余算够不着）。

**管理侧：批准是长期层的唯一写者。**

- **三道拒绝全在写之前**，且都不改任何对象。一、`expectedDigest` 与这一行现在的摘要不符 → `DRAFT_CHANGED`，并把当前摘要一起还给页面（批准的动作只覆盖"我读过的那一份"，而摘要是覆盖决定所依据的每一列算出来的：agent 名、会话号、待并正文、它所并身对的长期层正文与版本号、来源清单与按天日目标）。二、这一行已不是未决 → `ALREADY_REVIEWED`，带上前一位是谁、什么时候、拒的理由。三、结论层或候选点名的某一个日期对象，其版本不等于候选为它记下的那一个，并且它也不等于"这个对象已经就是这份文本" → `STALE_BASE`，带上结论层此刻的版本并点名是那一个对象动了。第三条是这一域与技能草稿审批唯一实质的差别：技能批准只需要对草稿本身负责，这一份批准要回答"我读的那份长期层还在不在原位"，因为它覆盖的是每次调用都进上下文的那一段。这 `1 + K` 个前提逐一在任何抢占之前问完：一条候选覆盖的每一半都要成立，只并其中两天不是那份决定。
- **抢的是行上的条件更新**，抢不到就把已经赢的那个决定回读给页面。同一个主人的两个页签因此只会有一次落库。
- **落库带两道条件，而 `1 + K` 个对象各带一次**：先比这一个对象现在的版本与候选为它记下的版本号（这一步在这层代码里做，版本是信封里那个字段），再把对象自己的 ETag 作为 `If-Match` 交给存储——对象还不存在时是 `If-None-Match: *`。存储答 412 是一次拒答而不是故障：条件不满足时返回 false，事务把刚才那次抢行回滚掉，页面上仍是未决。没有 ETag 可读的对象不做无条件替换（返回 false 并留一条日志），因为一次"读没看见、写却盖上去"正是这一格要防的动作。写序是先写完 K 个日期、最后写结论层：结论层是每一次模型调用都要进上下文的那一份，也是页面当成"这台记得什么"的那一份，而日期段此刻还没有读者；半途停下留下的是"日期已齐、结论层仍是大家读过的那一版"，下一次点击接着写完。
- **清理排在落库之后，且逐对象各带一次正文复核。** 只有路径解析得出、且此刻仍持有候选记下那一份字节的对象才删；这期间被写过就留下它（它属于一条没人读过的候选）、已经不在了就单独计一档。比的是正文而不是存储给的那个号：无保护的写可以把任意字节落在任意版本上，而这一步紧接着要删的正是那些字节的唯一一份副本。每一次删除都回读确认，对象还在就抛错——整个批准随之回滚，下一次批准重新走一遍，而不是让队列显示"这一层已经并走了"而对话仍持有它。
- **批准是幂等的。** 若这一层已经正持有这份文本（上一次批准写成了却没清干净），这一次不再写，只把清理补完，答的还是 `APPROVED`、给出层自己的当前版本。这条不是兜底而是必经：写与清是两次存储动作，中间任何一次失败都会把状态留在这里。日期段按同一条判据各算各的已落地：该对象现存正文逐字等于候选给的那一份就不再重写，而一次批准落下的日期数——含此前已经落掉的那几笔——由 `dailyTargetsApplied` 报给页面。
- **三条硬口径。** 一、运行侧既不写长期层也不删会话层，只有批准这一步两者都动。二、入账失败与审批失败都不动任何对象：入队没成功时材料逐字在原位，审批被拒或回滚时长期层与来源对象都照旧。三、各会话独立计时、各会话至多一条未决候选；决定权在记忆的主人本人而不是工作空间管理员——行按 `sys_user.id` 精确归属，别人的账号连这一条存在都读不到。
- **用的模型沿用记忆域那一枚**（第 5 节 model 那一行），没配就跟着这台 agent 的主模型，与今天抽取的取值口径一致，不新增第二份配置。管理面这一侧不碰模型：它只落库人读过的那一份文本。
- **团队会员的会话层按根会话分档，晋升槽跟着键一起换。** 一次委派里成员那一档用的会话键由根会话推导（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:59` 拼成 `team-<rootSessionId>-m<memberAgentId>`），而会话层的桶键与它的晋升槽键都取自这个会话键（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromotionMiddleware.kt:75`）。于是一台被多个会话委派过的成员，每个根会话各留一份自己的会话层，只有同一个根会话再次开口并且到点时那一份才被并成一条候选；没有第二次开口的根会话，那几份留在桶里。回收不受这一格影响：那些键都在同一个主人前缀之内，删这台 agent 与删用户两条途径按前缀一起带走（11.5 第二、三条）。这台成员有没有会话层，沿用第 4 节那枚「长期记忆」判据（`HarnessAgentLauncher.kt:1278`），没有按会话的开关。

### 11.5 清理：四道回收，加一道明确的"不清"

| 途径 | 覆盖哪层 | 说明 |
|---|---|---|
| 批准即清 | 会话层 | 只有批准落库才清，且只清字节未变的那几份，见 11.4 管理侧倒数第二条 |
| 记忆管理页删这台 agent | 两层 | 作用域是这台 agent 前缀下列回的**全部原始对象名**，逐个删，不经键解析；会话层嵌在它下面（11.2 第一条），因此自然一起带走 |
| 删除用户 | 两层 | 该用户所属的每一个租户各走一遍（沿用第 6 节那条既有口径，不为新层加代码），每一遍同样是按前缀列原始对象名，解不出来的键也照样带走 |
| 按天流水到期归档 | 只有会话层的流水 | 90 天归档那条既有机制挂在本次装配挂上的那个桶上，也就是会话桶；长期层的日期件由批准写进 `memory/` 段，那条机制够不着它们，于是这一层的日期只由上面第二、三条那两道整体删除带走 |
| 清空会话、删除会话 | **都不清** | 维持第 1 节第 6 条：那是会话数据的动作，不动任何一层记忆 |

"删会话不清"的代价写在明处：被删掉的会话若还有没并完的流水，那些条目此后**没有任何读者**（会话层的注入只认同一个会话），也不会再有机会被并成候选（不会再有那个会话的装配），只是留在桶里，直到上面第二条或第三条把它们带走。它并非完全没有去向——如果这个会话在被删之前已经入过一条候选而人还没决定，批准那条候选会照着清单去清它们（对象还在、字节没变就被带走）；拒绝那条则什么都不动。归档那一档还有一层去向：一份会话层流水一旦满 90 天被移进 `memory/archive/`，它就落在流水段的下一层，而晋升取的是流水段的直接子对象——归档件因此不会被并走，也不会被任何候选点名清理，它是这个会话留在桶里的一段历史，不是待沉淀的草稿。

这一份欠账在页面上有两处看得见的地方，而**没有"立即沉淀"那一个动作**：有多少个会话的层里还带着内容由 11.6 第三条那一枚计数说清，其中哪些已经成了候选由审批台那一屏列清（11.6 第四条）。"立刻并一次"要的是在没有对话的情况下装配起一次合并——合并要的节流槽与它要问的模型都挂在会话装配那一步（11.4 第一条），管理面手里一份都没有，为它新造一条清理链路正是 11.8 拒掉的那件事。页面上给的手动了账是**拒绝**：它把一条候选关掉而不动任何对象，材料因此回到这个会话的下一次合并。回收本身仍只有三条既有走法：这台 agent 的下一轮对话把窗口走到、上面第二、三条那两道整体删除把它带走，或者有人批准那条已经存在的候选。

### 11.6 页面上看得见的

- 记忆管理页仍然只列**长期层**：一行一台 agent，正文是那份跨会话的整理稿，日期是它的流水日期。会话层与长期层共用一个前缀，所以这句不是"列出来再看运气"：读的那一侧按解析出的会话段做减法，清单与详情都只保留不带会话段的对象，没批准的草稿和它那一天的流水不会因为躺在同一个前缀下就出现在页面上；同一处减法还顺手把 11.7 那个进度对象挡在外面，它的名字读不成日期。删的那一侧连这道减法都不做——它按前缀列原始对象名，见 11.5 第二、三条。
- 日期列跟着批准长：一次批准除结论层以外还写入这条候选点名的那些日期文件（11.4 管理侧），所以这一行的日期是这台 agent 已经沉淀下来的天，不再一律为空。抽取、整写与手工入档仍只落在会话桶里（11.1 第一条），于是会话桶里那些日期要等到有人批准了那条候选才出现在这一列上，而 11.7 那份进度对象跟的是本次装配挂上的那个桶、也就是会话桶，页面本来就列不到它。这一格空着的时候说的是"还没有批准过任何一天"，新条目正躺在会话层等一条候选被批准；解释它的是下面那枚计数与审批台，不是行上的某枚开关（没有开关可解释）。
- 会话层不单列，但它在页面上有一个数字：那一行另带一个「待并入」计数，数的是这台 agent 底下**层里还带着内容的会话有几个**，不是对象个数——一次会话的草稿连同它当天的流水是一次合并，不是两次，按对象数会把同一次对话显示成两次落后。哪些对象算"还带着内容"沿用晋升自己那条判据：会话的 `root/MEMORY.md` 草稿，与流水段的**直接子**日期件。因此 11.7 那枚进度对象与 11.5 末段那个归档件都不计数——批准后它们照样留在桶里，把它们算进去会让一台每一层都已并走的 agent 永远显示待并入，而那是这一枚唯一不能给的答案。这一枚从列表那一次列举的同一批对象名里数出来，不新增接口也不新增存储读，给数量不给正文，所以第一条那句"页面只列长期层"照旧成立。这一枚只说"会话层还带着东西"，不区分它是否已经入了队列——入了队列而人还没批准的东西照样带着，那正是批准之前不该少的一份状态。清单的分组落在减法之前：一台**只写过会话层**的 agent 也占一行（正文空、日期空、待并入 N），否则它在页面上看起来像"这台没有记忆"。
- 审批两屏是这一层新增的入口，路径 `/optimization/memory/drafts` 与 `/optimization/memory/draft/detail/:id`（菜单在「优化治理」组里，记忆页页顶也有一枚按钮直接过去）。队列一屏只列**当前登录用户自己**的候选，默认只看未决，可按状态、按 agent 名（模糊）与会话号（精确）筛；每行给 agent 名、会话号、并身对的那一版（版本 0 显示成"这一台还没有长期层"）、来源文件个数、待并正文的字符数、提出与改写时间（改写时间晚于提出时间时点明这份被并过第二次）、状态、决定人与下钻。详情一屏把决定所需的三样都摆在同一屏里：待并的完整文本、它并身对的那份长期层原文、以及取材料的每一个会话层文件各自的路径、字数与原文（不预折叠，展开才给正文）——批准按的是"我读过的那一份"，所以读不到原文就不能签。同一屏还给出这份候选的摘要，批准时原样带回。
- 五种结果各有一个下一步，页面按 `outcome` 分支而不是按错误码：批准成功给出层落到的版本与清理三档计数（清掉几个、因字节挪了而留下几个、已经不在了几个）；拒绝成功说明会话层不动、这些材料会回到下一次合并；`DRAFT_CHANGED` 提示重新读一遍再签并把新摘要显示出来；`ALREADY_REVIEWED` 给出谁在什么时候怎么决定的；`STALE_BASE` 给出长期层此刻的版本，并说明这条候选并身对的那一版已经不在了。这一域与其余 admin 接口一样有意的破例：可以再动作的拒绝装在 200 信封的 `data` 里而不是错误码上，因为错误码只带一句话，而页面要拿这些字段决定下一步，靠重新拉一次来恢复"它刚拒了什么"就是一次竞态。
- 删除那一行与页顶的警告把这台的**两层**都点出名字（整理稿、全部每日记录、尚未并入的会话记忆），因为带走它们的正是 11.5 第二条那条按前缀的清扫：它按原始对象名列举，本来就够不着"只清长期层"这一档，文案不能再少报它带走了什么。
- 客户端这一侧只有网页一个消费者：聊天页与 iOS 都没有记忆界面，记忆永远不以气泡形式出现，第 1 节第 5 条那条不变量在分层之后对两层同时成立。审批也不出现在聊天页——它是对着一份文本做的决定，属于记忆管理这一侧。
- 会话层的正文没有按会话读这条路：记忆管理页那三条路由（清单、详情、删除）里没有任何一条按会话寻址，详情那一条先做减法（本节第一条）。人能看到的是某一条候选在入账那一刻取走的逐字快照（上面第四条的详情一屏），而它随批准被清掉（11.4 管理侧第四条、11.5 第一条），所以那份快照不是这个会话层此刻的样子。要给「这个会话现在有什么」，先得在服务端加两条按会话的读路由（按会话列出、读某一个会话），键布局已在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:199` 给出；那样的屏显示的是**待并的进度**而不是存档，而团队会员的会话层按根会话分档（11.4 末条），一旦按会话列出就会成排出现。

### 11.7 整理进度：跟着本次挂载的桶走

- 机制里有一个"上一次整理到什么时候"的进度标记，上游把它存在共享存储的**一个固定位置**——`["memory","consolidation"]` 这个命名空间下的 `watermark`，不带租户、不带用户、不带 agent。
- 为什么这一格必须搬走：整个部署上所有用户、所有 agent 读写同一个进度，甲整理一次就把进度推到"现在"，乙此前没并进去的流水因此永远低于它、被静默跳过。表现是"乙的记忆隔一阵就不长了，而日志里一个错都没有"。这条与分层无关（第 2 节事实五记过同一个标记写不进去的故障，记的是它的可用性，这里说的是它的归属）。
- 分层之后更需要它：会话层和长期层各要一份自己的进度，共用一个会让一次会话层的整写把主人的跨会话流水标成已整理。
- 落地形状：第 6 节协调槽那一条已经立过"只把某一个位置改成本进程应答、其余一律透传"的先例，这里照同一个形状再用一次——`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/BucketScopedWatermarkStore.kt` 只改写上游那一个命名空间，其余键一律透传，管线、提示与文件布局都不动。上游那两个常量是私有的，本仓重述了一份，并由一条用例把上游字段读回来逐字对账：改了上游那一头而本仓没跟上，会立刻红，而不是静默把所有桶退回同一个地址。
- 落点定在**本次挂载那个桶的按天流水段里面**，而不是给它另开一个只属于进度的小空间。理由是回收：删这台 agent 与删除用户两条既有途径都是按前缀列举再逐个删，进度放在那条前缀之内就会被一起带走；放在自己那一段里，没有任何一条链路会去清它，于是它比自己要数的流水活得更久，下一次拿到同一个桶键的用户会被它静默压住整理。两层常在之后"本次挂载的桶"就是这一个会话的桶（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:777` 那一处是唯一的决定点），路由、进度与晋升读写的因此始终是同一个桶，而同一主人的两个会话各有一份进度——这里挡住的串档比单层时更细一层。
- 这个落点让记忆页多看见一个对象，代价是页面必须不认它：页面只列名字能读成日期的条目，进度那条读不成日期，因此清单与详情都不出现它；它与两层一样，落在删除的作用域里。
- 上游那个固定地址没有任何读者：这一层往它写的是零，两条整体删除也够不着它——`memory/consolidation` 那一段前缀不在任何主人前缀之内，而 11.5 那四条回收全都按主人前缀列举。一份桶里若真落着一枚这样的对象，把它取走是部署侧的一次手工动作（按对象名删那一个），不是这一层要补的机制。
- 批准不参与这道清理：会话层的对象被候选点名才清（11.5 第一条），而进度件永远不会被任何候选点名（它的名字读不成日期），于是它跟着桶活到那两条整体删除为止。

### 11.8 这一层不做什么

不提供只按会话、彻底不留长期记忆的那一档（与第 1 节第 1 条直接矛盾）；不提供会话层的手工编辑入口；不自动批准，也不"先并进去再由人回退"——长期层的写者只有批准那一步；不给记忆管理页配"立即沉淀"那一个手动赶合并的动作（11.9 的 P15，代价与替代写在 11.5 末段）；不做跨 agent 的会话层合并；不给会话层配独立模型或独立容量预算；不在 iOS 与聊天页做任何事；不新造清理链路（除 11.4 的批准即清之外，回收只复用删这台 agent、删除用户、到期归档这三条既有途径）；不换掉日报归档机制；管理面不起模型、不重新合并——它只落库人读过的那一份文本。

### 11.9 拍板结果

| 拍板项 | 取值 |
|---|---|
| P8 两层关系 | 拍板：并存，且会话层经人批准进长期层。次选"二选一、每台只走一种粒度"被否，它让选了会话粒度的那台从此没有跨会话记忆 |
| P9 合并触发 | 拍板：节流定时。理由是 harnax 没有可靠的会话结束信号，见 11.4 第一条 |
| P10 适用范围 | 拍板：**没有开关**，凡有记忆的 agent 每一段对话都有会话层。次选"仍逐台选"被否：那一枚决定的是这台有没有能力积累跨会话记忆，而"该不该并进长期层"已经改由审批那一步逐条回答，两枚判断同一件事。撤除它同时撤掉了"整份部署缺省"那一枚（P16） |
| P11 读侧 | 拍板：两层都注入模型，新会话刚开口那几句不必等节流窗口 |
| P12 删会话 | 拍板：不连带清，维持第 1 节第 6 条那一行；代价与去处写在 11.5 末段 |
| P13 回收 | 拍板：批准即清，且只清字节仍与候选记下一致的那几份；删后要回读确认 |
| P14 页面呈现 | 长期层单列，会话层不单列而只在 agent 那一行给一个「待并入」计数（判据沿用晋升自己那条读取规则，按会话去重，11.6 第三条）；候选另开两屏（队列与详情），形状与技能草稿审批一致。日期列只在这条候选被批准之后才长出那一天，还空着的那一段由计数与审批台解释，没有"这台有没有开会话层"那一枚 |
| P15 手动"立即沉淀" | **不提供这一枚动作**。P14 的计数回答"有几个会话还带着东西"，审批台回答"其中哪些已经成了候选"，而"立刻并一次"要的是在没有对话的情况下装配起一次合并——合并用的节流槽与它要问的模型都在运行侧，管理面手里一份都没有；自建一套晋升等于把模型配置、凭据解密、桶装配三处各复制一份进管理面。代价与既有回收途径写在 11.5 末段 |
| P16 部署侧缺省的形状 | 随 P10 一并撤销：会话层既然逐台都不选，就不存在"新建 agent 的初值"这一枚配置项，`harnax.agent.session-memory-default` 与它的环境变量都不在这份部署里。原先的三个候选（admin 建默认值／运行时强制开／列改可空三态）都是在为一枚已经不存在的开关定缺省 |
| P17 长期层的写者 | 拍板：**只有批准落库那一步**。次选"自动并进去、人事后纠正"被否：那会让人在页面上读到的那份文本与模型每轮实际读到的那份分叉，而纠正一次已经生效的覆盖要靠再覆盖一次 |
| P18 候选粒度 | 拍板：一段会话至多一条未决候选，后来的合并覆盖它；被决定过的那一条不再被改写，新的合并另起一行。次选"每次合并都新起一行"被否——同一段对话连着几轮会攒出五条只差最后两轮的候选，审批人要的是"这段对话现在想并进长期层的是什么"，不是它的合并历史 |
| P19 批准的前置 | 拍板：候选按它并身对的那一版落库，版本挪了就拒（`STALE_BASE`），不重读重并。次选"以最新长期层为底自动重并一次再落"被否：那要在管理面装一条模型链路，且重并出来的文本没人读过，与 P17 直接冲突 |
| P20 摘要覆盖的范围 | 拍板：摘要覆盖决定所依据的每一列（agent 名、会话号、待并正文、并身对的正文与版本号、来源清单），因此换底与换来源都算改过这份候选，而不只是换正文。长度前缀逐字段喂进散列，防止文本在字段边界之间被重新切分而凑出同一个摘要 |
| P21 审批归属 | 拍板：记忆的主人本人，行按 `sys_user.id` 精确归属。次选"同租户管理员可代审"被否：长期层是每个人自己的回忆，别人替你签名等于替你决定以后每次对话模型都读到什么；内部服务身份在这一屏直接被拒（401），而不是"看得见但点不动" |

### 11.10 判定条款

第 1 节那六条对长期层继续原样成立，这一层另加九条，每条都可判定；每条由谁断言、断到哪一档，逐条对账见 11.12。

1. **装配判据只有一枚，且它管两层。** `memoryEnabled` 为假时整域没有：不挂路由、不装钩子、不给那四枚工具、不装注入与晋升。为真时两层一起有：挂载的路由键里 agent 段之后就是 `sessions/<会话>` 再到 `root` 与 `memory`（不是停在主人的那一段），模型输入里两块分得开，且晋升这一步只在有队列适配器时装。这是这一层的回归锚。
2. **双层下的可见范围。** 全新生成的同用户同 agent 会话，首轮就拿到长期层；同一个会话的下一轮，同时拿到长期层与自己那份会话层，且两段在输入里分得开——分得开靠的就是长期层那一对标签。长期层那一段过了预算时，进来的是它的头部加一句写明被截断，不是整篇。一次批准之后，同一台 agent 的下一次模型调用就读到这份文本，不需要新会话也不需要重新装配。
3. **入账与审批失败都一个对象都不少。** 运行侧的模型抛错、模型不回答直到超时、合并结果缩水、读被拒、队列够不着、队列明确拒收六档下，分别断言会话层的对象数量与正文逐字未变，且长期层一个字节都没写；只有入队成功那一档改变状态，而它改的是库里的候选行，不是任何一个对象。管理侧的摘要不符、已决、版本挪了、抢行失败四档同样断言两层照旧；落库被存储拒（412）时那一次抢行随事务回滚，候选仍是未决。存储故障不许被读成"这一层本来就没东西"。
4. **两条整体删除都覆盖两层。** 删这台 agent、删这个用户之后，前缀下的键快照逐字为空，包括会话段下面那些还没批准的那些。作用域按 11.5 那一条取前缀下列回的原始对象名，所以连键解析认不出来的那类（agent 自己带斜杠）也在断言范围内。
5. **进度跟着桶，谁也不压谁。** 真存储上两个用户先后整理，断言第二位的流水不会因为第一位的进度而被跳过（11.7 第二条那个既有缺陷）；同一次链路上还断言同一主人的两个会话各有一份进度，以及进度确实坐在本次挂载那个桶的流水段里、两个页面响应里都搜不到它的名字与时间戳。
6. **清理不许比"读—删"这一对动作更宽。** 在候选记下的那份正文与它被删掉之间往会话层再写一条（追加流水或整篇重写那份稿子），断言新来的那条还在原位，而这次并掉的其余部分照常清空；删除本身要回读确认——存储报一次失败的删除而不抛错时，确认不了的算没删并让整个批准失败，而不是让队列把没带走的条目报成已并入。
7. **一次批准对同一版长期层只落一次库。** 两个针对同一个底版的批准同时到达，断言落库的那一次改了层，落空的那一次既没改层也没被记成已决定，且它对页面给出的仍是一条未决候选。这一条不依赖存储认不认条件写：版本比对先看信封里那个字段，`If-Match` 只是第二道；存储把 `If-Match` 收了却不生效这一档由探测那一条（第 6 节）负责，与批准无关。
8. **空层不占窗口。** 一个比节流窗口还短的会话，第一次读到会话层是空的（抽取还在模型那一头），断言它没有把窗口花掉；读不出来的那一档同样不占。先占槽再读的话，这类会话会从此一次都不合并。
9. **一条候选覆盖 `1 + K` 个对象，且要么全成要么全无。** 预检阶段某一个对象既不在候选为它记下的版本上、也不已逐字持有候选给的那份文本时，断言整条候选被 `STALE_BASE` 拒掉、`staleTarget` 点名是那一个对象动了、长期层一个字节都没写、会话层一个对象都没清；已持有那份文本的对象算已落地而不算不符，其余目标照常写；提名侧某一个日期并不出可用答案时入账接口一次都不被调用，未入选的日期不进结论层那一次合并的输入。

### 11.11 落地形状：按模块的文件清单

| 模块 | 文件 | 这一处负责什么 |
|---|---|---|
| harnax-harness-core | `harness/memory/MemoryDomain.kt`（新） | 一台 agent 的桶身份只算一次：两层的各自路由、整理稿与流水的命名空间、长期层整理稿的只读读口。路由、进度与晋升读写同一个桶由这里保证（11.7） |
| | `harness/memory/MemoryPromoter.kt`（新） | 一次入账尝试的全部判定：读会话层与长期层、结论层与每个入选日期各问一次模型、两道缩水判据、单笔与总笔的模型预算、把候选（含 `targets`）与它读过的字节一起入队、六个结果档位（11.4） |
| | `harness/memory/MemoryPromotionMiddleware.kt`（新） | 触发：每轮回答之后，在阻塞调度器上先看这一层有没有内容、再抢节流槽（11.4 第一条与 11.10 第八条） |
| | `harness/memory/LongTermMemoryContextMiddleware.kt`（新） | 长期层的只读注入：`<long_term_memory>` 那对标签、按整理稿预算折出的 16000 字符上限、块尾写明被截断、读失败时原样返回 prompt（11.1） |
| | `harness/memory/BucketScopedWatermarkStore.kt`（新） | 只改写上游那一个进度地址，其余键逐字节透传；重述上游两个私有常量并留一条用例把它们读回来对账（11.7） |
| | `harness/memory/MemoryFilesystemRoutes.kt` | 会话桶键：`sessions/<sid>` 嵌在 agent 段之内，`sessionRoutes`/`sessionNamespace`/`bucketNamespace`；`SESSIONS_SEGMENT` 与 `MEMORY_SEGMENT` 公开，给 admin 的解码器与进度包装用 |
| | `harness/memory/MemoryConfigFactory.kt` | 那两条禁止条款从私有改为抽取与合并共用；整理稿预算公开，注入侧按它算字符上限；`consolidationMinGap` 改为从配置取而不是硬编码 |
| | `agent/adaptor/MemoryDraftAdaptor.kt`（新） | 入账契约：一份候选的形状（会话、agent、待并文本、并身对的正文与版本、来源清单、按天日目标）与三档答案（已入队／被拒／够不着），并写明这一层"永不抛"的边界 |
| | `harness/HarnessAgentLauncher.kt` | 装配：`memoryRequested` `:1259` 一枚判据 → `memoryDomainOf(...)` `:772` → `mountedBucket` `:777` → 路由挂载 `:832-833` → 注入 `:834` → 晋升 `:839-850`（没有队列适配器时的 warn `:851-861`）；`memoryStore()` `:1324`、`casSupport()` `:1219`、`coordinationStore()` `:1240` |
| | `agent/AgentSpec.kt` | 只剩 `memoryEnabled`，缺省 `true`；这一枚现在覆盖两层的有无，spec 上没有与会话层有关的字段 |
| | `harness/config/HarnessConfig.kt`＋`harness/spring/HarnessAutoConfiguration.kt` | `harness.memory.consolidation-min-gap`（缺省 30 分钟，整写节流与合并共用同一个窗口）；`memoryDraftAdaptor` 的透传 `:373` |
| harnax-agent-service | `agent/service/adaptor/MemoryDraftAdaptorImpl.kt`（新） | 契约里"永不抛"那一半的落点：任何故障都折成"够不着"，让运行侧能区分"队列拒了这份"与"队列没接上" |
| | `agent/service/client/AdminApiClient.kt` | `submitMemoryDraft`：内部 API 的一次 POST（报文带 `targets`）与三档映射（200 且候选号为正＝已入队；400/404＝被拒；其余＝够不着） |
| | `agent/service/runner/AgentSpecResolver.kt` | 下发时 `memoryEnabled != 0` 一条判据覆盖两层：一个这个运行时没见过的值留在"有记忆"那一侧 |
| | `src/main/resources/application.yml` | 上面那枚窗口的环境变量与注释 |
| harnax-entity | `entity/MemoryDraft.kt`＋`mapper/MemoryDraftMapper.kt`＋`resources/mapper/MemoryDraftMapper.xml`（新） | 候选行的读写：按会话取未决、只改正文的条件更新、按状态转档的条件更新（抢行）、`targets` 一列的读写，以及页面清单的动态筛选（全部按 `tenant_id` 与 `user_id` 谓词） |
| | `entity/Agent.kt`＋`resources/mapper/AgentMapper.xml`＋`src/test/resources/schema-test.sql` | 记忆只剩 `memory_enabled` 一列；`schema-test.sql` 是基线加全部前向增量的逐字副本，因此它带着 `memory_draft` 而不带 `session_memory_enabled`，重放到的最新一版是 V8 的 `targets` 列 |
| | `entity/dto/AgentSpecInfoResponse.kt` | 下发报文里记忆只有 `memoryEnabled` 一枚 |
| harnax-admin | `resources/db/migration/V2__agent_session_memory.sql`＋`V6__drop_agent_session_memory.sql`＋`V7__memory_draft.sql`＋`V8__memory_draft_targets.sql`＋`README.md` | 四条前向增量：V2 加过那一列、V6 把它删掉、V7 建 `memory_draft`、V8 给它加 `targets` 一列（只加列，表数不变）。V1 基线里这一列从未出现，`memory_draft` 也不在基线里——这个库持有一枚只有人能重填的模型 provider key，不能随 schema 变更清库重建，所以变更走增量而不是就地改基线（README 的"Changing the Schema"那一节写的就是这条例外，V6 是"撤掉一枚开关"在不能重建的库上长成的样子） |
| | `dto/MemoryDraftSubmitRequest.kt`＋`MemoryDraftSource.kt`＋`MemoryDraftApproveRequest.kt`＋`MemoryDraftRejectRequest.kt`＋`MemoryDraftResponse.kt`＋`MemoryDraftDecisionResponse.kt` | 入队报文（不含租户与用户，含 `targets`）、清单行与详情（清单行给 `targetCount`，详情另给 `targets`，`MemoryDraftDetailResponse` 与清单行同住这一格）、决策回执（`dailyTargetsApplied` 与 `staleTarget`） |
| | `service/MemoryDraftService.kt`＋`service/impl/MemoryDraftServiceImpl.kt` | 两半：入账那半从会话 id 反解主人／agent／租户（三种会话形状，`chn-` 拒），核 agent 名与行一致、同租户、每条来源路径解析得出键、每条日目标解析得出真实那一天，并保证一段会话只有一条未决候选；决定那半按 11.4 管理侧的顺序走——三道拒绝与 `1 + K` 个目标的全量预检都在写之前、抢行、日期先写结论层最后写、逐对象正文复核清理 |
| | `util/MemoryDraftCodec.kt`（新） | `sources` 与 `targets` 的规范形（各按路径排序）与覆盖整份决定的摘要（逐字段长度前缀，日目标也在其中），入队、页面显示与批准校验三处共用同一份实现 |
| | `util/MemoryObjectKeys.kt` | 键解码认得会话段（`Location.sessionId`）；`sessionSourceKey` 把候选里的路径解回对象键（沿用写入侧那一条流水过滤，解不出即不碰）；`longTermSourceKey` 把候选里的日目标路径解回长期层那一天的对象键，判据是 `memory/` 段加一个读得出真实日期的文件名，读不成日期即解不出；`isValidSessionId` 与 `isValidAgentId` 同形；`hasUnmergedContent` 给出「这一枚对象算不算还没并走的内容」，判据与晋升取材料时同一条 |
| | `service/impl/MemoryStoreGateway.kt` | 审批落库与清理的执行处：`readCuratedLayer`、`writeCuratedIfVersion`、`readDailyLayer` 与 `writeDailyIfVersion`（同一把判据按对象各走一次：先比信封版本、再带 `If-Match`，无 ETag 不做无条件替换；日期件自己的信封 `key` 写的是那一个日期而不是 `MEMORY.md`）、`clearSessionSources`（逐对象正文比对＋删后回读，清掉／留下／已不在三档）、`sessionSourceKey`（与入队同一处解析，两处读同一枚 `harnax.memory.tenant-scoped`）、`isAvailable`。页面读侧仍是先按 agent 分组、再在每组内按会话段做减法，并顺手数出「待并入」；两条整体删除仍按前缀列回的原始对象名逐个删 |
| | `controller/MemoryDraftController.kt`（新） | `/api/admin/memory-drafts` 四张出口：清单（状态／agent 名模糊／会话号精确，缺省只看未决）、详情、批准、拒绝 |
| | `controller/InternalApiController.kt` | 入账出口 `POST /api/admin/internal/memory/drafts` `:322-343`：拒绝一律装在信封 `code` 里回给运行时，因为"这一份没进队列"必须让会话层留着；四张 agent 行的出口与 `buildAgentSpecResponse` 只带 `memoryEnabled` 一枚记忆开关（必填而不是缺省） |
| | `dto/MemoryAgentResponse.kt`＋`service/impl/MemoryServiceImpl.kt` | 行上带 `pendingSessionLayers`；清单与详情全部取自记忆桶，一次行表点查都不做（会话层是常态，没有需要按台解释的开关） |
| | `service/impl/AgentServiceImpl.kt`＋`dto/AgentCreateRequest.kt`／`AgentUpdateRequest.kt`／`AgentResponse.kt` | 记忆这一域进出只有一枚「长期记忆」；没有 `harnax.agent.session-memory-default` 这一项 |
| harnax-webui | `pages/memory/drafts.tsx`＋`pages/memory/draftDetail.tsx`＋`services/ant-design-pro/memoryDraft.ts`＋`config/routes.ts`＋`typings.d.ts`＋两份 `menu.ts`＋两份 `pages.ts` | 审批两屏（队列与详情，详情一屏给待并文本／并身对的那份／按日并入里每一天的「该日当前」与「并入后」／每个来源文件的原文）、四个服务函数、路由与菜单项、`API.MemoryDraft*` 那一组类型，键 `pages.memory.draft.*` |
| | `pages/memory/index.tsx` | 页顶那枚去审批台的入口；「待并入」那一列与它的悬停；日期列的悬停（说明记录停在会话层，等人批准）；删除确认与页顶警告点名两层 |
| harnax-deploy | `.env.example`＋`docker-compose.yml` | `HARNAX_MEMORY_CONSOLIDATION_MIN_GAP` 一枚环境变量与注释（agent-service 侧）。这一层不新增任何环境变量，也没有与会话层开关同名的部署侧缺省 |
| docs | `docs/deploy-harnax-admin.md`＋`docs/deploy-harnax-agent-service.md`＋`docs/deploy-harnax-harness-core.md` | 配置项在部署文档里的落点：admin 侧删掉那一枚开关的初值行，agent-service 与 harness-core 两处的窗口注释改为"每一段对话都有自己的会话层" |

列与键的名字、缺省值与注释在以上文件里各只有一处定义：`memory_enabled` 的缺省在 DDL，`memoryEnabled` 的缺省在 `AgentSpec`，候选状态的三个取值在 `MemoryDraft` 的两个常量与 DDL 注释里，页面文案的缺省在那两枚 locale 键。会话层没有任何配置项可读：它随记忆一起有。

### 11.12 逐条对账：判定条款由谁断言

用例名逐字取自测试源码，条数是本轮实跑的数：`harnax-harness-core` 本模块 702 项（真模型晋升那一档没有 provider key，按跳过记一次）、`harnax-admin` 2921 项（单元 2501 加真 `mysql:8.0` 与真 MinIO 上的集成档 420），两处都是零失败。

| 条 | 断言落在哪 | 状态 |
|---|---|---|
| 1 一枚判据管两层 | `HarnessAgentLauncherMemoryTest`（20 条）：`a conversation mounts its own bucket and reads its owner's curated layer` 断言挂载的桶键在 agent 之后就是 `sessions/<会话>` 再到 `root` 与 `memory`，而同一次装配读长期层仍走 owner 那一段；`an agent that turned memory off gets no memory domain at all` 断言那一枚「长期记忆」为假时两层都不出现；`a deployment with no memory queue proposes nothing and says so` 断言适配器缺席时注入照挂、晋升不装且留一条 warn。工具清单不在这一条里：那四枚由同一枚「长期记忆」决定（第 4 节） | 已验 |
| 2 双层可见范围 | `LongTermMemoryContextMiddlewareTest`（9 条）：`a brand-new conversation still reads its owner's long-term layer`、`the injected block is delimited and names which layer it is`、`one conversation reads its owner's layer beside its own draft instead of as one text`、`a long-term layer over its budget arrives cut rather than whole`，另四条管"没有整理稿就不加块"（`an owner with nothing curated gets the prompt untouched` 与 `a blank curated layer is no block either`）、"存储拒答就原样返回 prompt"、"每次调用重读"，`the tenant segment off reads the bucket the routes write` 管租户段关掉那一档读的是同一桶。批准之后读得到那一份文本由真 MinIO 上的 `MemoryApprovalStoreIT` 的 `an approval writes the layer where the runtime reads it` 走一遍 | 已验 |
| 3 入账失败一个对象不少 | `MemoryPromoterTest`（28 条）里各档都有断言：`a model that fails leaves the only copy of the draft alone`、`a model that answers with nothing files nothing`、`a model that never answers costs the merge and releases the thread`（超时）、`a store that cannot be read at all is not a conversation with no memory` 与 `a long-term layer that cannot be read is not proposed as an empty one`（读被拒，后者同时钉住"存储故障不许读成空层"）、`a queue that cannot be reached keeps everything for the next window`、`a queue that refuses a candidate still does not clear the layer`。缩水那一档两条：`a merge that shrinks a curated layer already inside its budget is not filed`、`a curated layer that overran its budget is still not open to a collapse`，并有一条正向 `a curated layer that overran its budget is brought back by a halving` 证明超预算的层仍会被整理。"运行侧一个字节都不写"由 `a merge is filed with the queue and neither layer moves` 直接断两层对象数与正文逐字未变 | 已验 |
| 3 审批失败一个对象不少 | `MemoryDraftServiceImplTest`（39 条）里 `an approval without a digest is refused`、`a changed candidate is refused`、`a decided candidate is refused`、`a stale base is refused`、`a lost claim answers with the decision that won`、`an unreachable store refuses the approval`、`a rejection writes nothing to the store`；落库那一条 `an approval lands the text and clears its sources` 钉成功档。真 MinIO 上 `MemoryApprovalStoreIT`（8 条）逐档：`the store refuses a conditional write whose precondition no longer holds`、`a refused approval costs its own connection and not the next write`（412 之后同一个客户端的下一次写仍落得下去）、`a candidate merged against an older version never reaches the store`。审批被拒之后候选仍是未决由 `a lost race rolls the claim back` 钉回滚 | 已验 |
| 4 两条整体删除覆盖两层 | `MemoryStoreGatewayTest`（55 条）：`a conversation's own layer goes with the agent although the page hides it`、`a key no decoder can place still goes with its owner`、`every agent of the user goes and the prefix never leaves that user`、`a delete asks for the caller's prefix and every key it removes stays inside it`。真 MinIO 上由 `MemoryOwnerBucketMinioIT` 的 `a delete reclaims both the long-term layer and the conversations under it` 走一遍 HTTP。键形状两头各钉一次：运行侧 `MemorySessionBucketKeyTest`（7 条）与 admin 侧 `MemorySessionLayerDecodeTest`（10 条），跨模块对账 `MemoryObjectKeyCrossCheckTest`（10 条）；会话那一段的形状钉在真 MinIO 上，`MemorySessionKeyShapeTest`（2 条）的 `a session id that nests deeper still writes inside its own agent` 断言带斜杠的 id 只在那台 agent 的前缀之下多出一层、两层各落一个对象，`a session id that walks up never leaves memory outside its own agent` 断言五种往上挪的 id 一个对象也不落在前缀之外，且同一条里先写一枚正常会话作对照——空清单不算通过 | 已验 |
| 5 进度跟着桶 | `BucketScopedWatermarkStoreTest`（7 条）逐项：上游地址对账、写落进哪个桶、读回同一个桶、两个 owner 各一份、`the session layer and the long-term layer do not consolidate against one progress`、CAS 只在本桶内争、其余命名空间透传。装配侧 `HarnessAgentLauncherMemoryTest` 的 `the consolidation progress follows whichever bucket the routes point at` 盯"进度跟的是挂上去的那个桶"。页面不认它由 `MemoryOwnerBucketMinioIT` 的 `the bookkeeping object a bucket keeps beside its ledgers is never shown as memory` 断言 | 已验（桩上）。真存储上跑的是"进度落在哪一段前缀、页面两个响应里搜不到它"；两个 owner 先后整理没有另在真 MinIO 上重跑——那条断言的是地址改写，与服务器无关（11.7 末条） |
| 6 清理不许更宽 | `MemoryStoreGatewayTest` 三条：`only a source that still holds the merged bytes is cleared`、`a source that is already gone is counted apart from one that was cleared`、`an object that survives its own deletion is a fault, not a clear`；真 MinIO 上由 `MemoryApprovalStoreIT` 的 `a clear takes only the file that still holds the merged bytes out of the bucket` 与 `a file that is not in the bucket is answered absent rather than as a store fault` 各钉一档。入队那一条来源清单逐字带走由 `MemoryPromoterTest` 的 `the candidate carries every object it merged with the bytes it read there` 与 `what the flush wrote while this merge ran is not in the candidate` 钉 | 已验 |
| 7 同一版只落一次库 | `MemoryApprovalStoreIT` 的 `the second approval of one base leaves the layer as the first approval wrote it` 与 `an approval that fits the bytes it read replaces them and keeps the layer's own creation stamp`；行上的抢与回滚由 `MemoryDraftServiceImplTest` 的 `a lost race rolls the claim back`、`the second conditional transition changes nothing`（`MemoryDraftMapperTest`）；幂等补完那一档由 `an already applied candidate finishes` 钉 | 已验 |
| 8 空层不占窗口 | `MemoryPromotionMiddlewareTest`（7 条）：`a first turn does not spend the window on a layer the extraction has not filled yet`、`a conversation with nothing to promote does not ask the gate`、`a layer that cannot be read leaves the window for whoever can`，另四条管"回答不等合并""窗口内不并""两个会话各走各的钟""闸答不出也不欠这一轮" | 已验 |
| 入账的判据与形状 | `MemoryDraftServiceImplTest` 的 `an unattributable session is refused`、`a mismatched agent is refused`、`a cross-tenant agent is refused`、`a merge is filed against the resolved owner`、`a team child session is unwrapped to its member agent`、`sources are stored canonically`、`an empty source list is refused`、`an unresolvable source is refused`、`a blank merge is refused`；摘要覆盖范围由 `the digest covers the whole decision` 与 `the digest ignores only what the status gate already refuses` 两条钉住正反两面 | 已验 |
| 归属与可见范围 | `MemoryDraftServiceImplTest` 的 `page scopes by user`、`page refuses an unknown status`、`a foreign candidate is invisible`、`a service principal is refused`；mapper 侧 `selectDraftList is owner scoped and dynamically filtered` 与 `selectDraftList filters by session` 把 SQL 谓词钉在真 `mysql:8.0` 上；真栈两屏由 `MemoryDraftFlowIT`（7 条）走 HTTP，其中 `the bearer a proposal arrives on and an account that is not the owner get nothing from the review half` 同时钉"报文带来的身份不决定归属"与"别人的账号读不到这一条" | 已验 |
| 一谈话一条未决候选 | `MemoryDraftServiceImplTest` 的 `an open candidate is rewritten` 与 `a decided candidate is not reopened`；mapper 侧 `a repeat proposal finds the candidate of its own conversation`、`updateContent lands on nothing once the candidate is decided`、`the most recently patched duplicate wins` 打在真 `mysql:8.0` 上；HTTP 端到端由 `MemoryDraftFlowIT` 的 `a second merge from the same conversation rewrites the open candidate and invalidates the first digest` 与 `a merge that arrives after the decision files its own row and a rejection closes it with a reason` 各钉一档 | 已验 |
| 页面只列长期层 | `MemoryOwnerBucketMinioIT` 的 `the listing keeps a conversation's unpublished memory off the page` 与 `the detail answers the long-term layer alone even though a session bucket sits under it`；单元测试侧 `MemoryStoreGatewayTest` 的 `an agent is reported with its curated text its storage time and its ledger dates` 等 | 已验 |
| 页面的那一枚计数（11.6 第三条） | 「待并入」按会话去重由 `MemoryStoreGatewayTest` 的 `the pending count is the conversations whose own layer still holds memory`（四枚对象落在三个会话，答 3：一次对话的草稿连同它当天的流水是一次合并不是两次）与 `each agent's pending count covers only its own conversations`（只有会话对象的那台也占一行，且计数不串到邻台）钉住；进度件与归档件不计数由同类的 `the passes state object and an archived day are not counted as unmerged memory` 钉住，判据本身在解码器那头由 `MemorySessionLayerDecodeTest` 的 `a conversation draft and its dated ledgers are unmerged memory` 与 `the passes state object and an archived day are not unmerged memory` 各钉一档 | 已验 |
| 来源路径的解析两头 | 入队侧与清理侧共用一处解析这一条由 `MemoryObjectKeysTest` 的 `with the tenant segment off a source key starts at users`、`a path the merge never writes resolves to nothing`、`any markdown file of the ledger route resolves`、`an unaddressable owner or conversation resolves to nothing`、`the curated draft path is the conversation's own root object`、`a ledger path is the conversation's own memory object` 钉；清理侧另在 `MemoryStoreGatewayTest` 加 `a path the merge never reads is not addressed at all`，会话段本身由同类的 `a conversation id that could name a path clears nothing` 管；`MemoryObjectKeysTest` 这个类共 29 条把键串逐字钉住 | 已验 |
| 日目标的前提与写序 | 提名侧 `MemoryPromoterTest` 各档：三个日期⇒一次结论加三次按天合并、`targets` 逐条对上桩响应给的路径与版本；日期多于上限时入选的是最旧 7 天，而材料清单恰好是那 7 天加结论草稿；某日合并抛错、答回缩水过半、总预算见底⇒`propose` 零调用；长期那一天读不出⇒`STORE_FAILED` 且 `propose` 零调用；结论层那一次合并的输入不含未入选的日期。入账与批准侧 `MemoryDraftServiceImplTest`：重复 path、负版本、空白正文、`MEMORY.md` 作 path 各拒一档，`targets` 缺省照样通过；第 2 个目标版本不符⇒`STALE_BASE` 带 `staleTarget`、零次写、抢占不生效；某目标现存正文已逐字等于候选⇒不重写而其余照写、计数含它；第 2 个日期条件写返回 false⇒抛 409 且 `clearSessionSources` 零调用。键形状由 `MemoryObjectKeysTest` 把 `memory/<YYYY-MM-DD>.md` 之外的一切（`MEMORY.md`、`memory/`、两段路径、不存在的日期、`../` 形）判成解析不出；摘要由 `the digest covers the whole decision` 钉 `targets` 一变摘要即变。真 MySQL 与真 MinIO 上由 `MemoryDailyMergeIT`（4 条）端到端各钉一遍：日对象的信封 `key` 是那一天而不是 `MEMORY.md`、同日第二会话被点名拒掉且不覆盖第一会话写成的那一天、重提之后两份材料并成同一文件且 `created_at` 保住、三种非法日目标一条候选都不入队 | 已验 |
| 列的撤除与候选表 | 候选表由 `MemoryDraftMapperTest`（11 条）打在真 `mysql:8.0` 上，`a stored candidate reads back whole` 与 `a create-shaped candidate keeps an absent base layer` 覆盖两个方向；`agent` 行没有会话那一枚开关由 schema 两侧逐字一致钉住——`SchemaBaselineDriftIT`（7 条）把迁移链撤列之后的形状与 `harnax-entity/src/test/resources/schema-test.sql` 双向对账，那份副本里这一列 0 处；剩下的那一枚长期开关由 `AgentMapperTest` 的 `the memory answer round-trips through the agent row` 钉住 0 与 1 两个方向（`-am` 连带 harnax-entity 进 reactor） | 已验 |

未验的四格：一、真模型下的合并质量——并出来的那一份 `MEMORY.md` 召回够不够、有没有把这一轮才成立的东西当成长期事实留下，判据同第 9 节第 4 条，需要 provider key（`MemoryPromotionRealModelTest` 就是这一档，没给 key 就记一次跳过）；二、别家 S3 兼容档在这台存储上会给出哪个答案（第 9 节第 2 条同一格）；三、翻「长期记忆」到下一次装配之间的窗口仍是机制说明而非实测（第 9 节第 8 条）；四、`If-Match` 在真实网关（而不是 MinIO 本体）上会不会被收了却不生效——第 6 节那次探测回答的是节流槽那一路，审批这一路只断言了 412 与信封版本这两道，网关侧的档本轮没跑。另有一格本轮只做到构建：智能体向导那两屏、审批队列与详情两屏、记忆页那枚入口按钮都没有走浏览器验收（前端只过了 `max build` 与 lint），见第 9 节末尾那条口径。
