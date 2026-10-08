# Harnax memory：跨会话长期记忆方案

## 0. 口径与取证基线

版本与存储事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文档的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | 上游检出 `~/code/opensource/agentscope-java/`，分支 `release/2.0.4`，HEAD `3c1c29c0`；其 `pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>` |
| 2.0.4 事实来源 | 上述检出的 `agentscope-harness/src/main/java/` 与 `agentscope-core/src/main/java/`，本篇全部上游锚点都取这里 | 与基线同一份代码，不另解 sources jar |
| harnax 当前依赖 | **2.0.4**，与本文档基线同版本 | 仓库根 `pom.xml:39`（`<agent-scope.version>2.0.4</agent-scope.version>`） |
| 记忆的字节落在哪 | MinIO，bucket `harnax-store`、全局前缀 `store/` | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:87,92`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/MinioConfig.kt:25,29`；对象键形状见同目录 `minio/MinioBaseStore.kt:21` 的类注释样例 `store/agents/myAgent/sessions/sess-123/MEMORY.md` |
| 这个 store 由谁装配 | 一个 `MinioBaseStore` 实例（`HarnessAgentLauncher.kt:649-651`）被两条分支共用 —— 沙箱分支 `:671-700`（记忆域开着时先过 `memoryStore()`，`:692`，它内含 `coordinationStore()` 与按桶的整理进度包装）、非沙箱分支 `:701-705` | 同文件；两档下的键形状必须一致，见第 7 节 |
| harnax 主数据源 | `harnax_admin` 库 | `.../application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 表在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15` |

锚点约定：上游文件一律省略前缀 `agentscope-harness/src/main/java/io/agentscope/harness/agent/`（`HarnessAgent.java`、`middleware/`、`memory/`、`memory/compaction/`、`filesystem/`、`coordination/`、`tool/`、`workspace/` 全在这一棵树下），core 侧三个文件省略前缀 `agentscope-core/src/main/java/io/agentscope/core/`。harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`，同目录的 `HarnessConfig.kt` 与 `SandboxConfig.kt` 在 `config/`、`MinioBaseStore.kt` 在 `minio/`、`HarnessAutoConfiguration.kt` 在 `spring/`，`SessionConfig.kt` 另属 `.../agnetix/harnax/agent/session/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，同模块的 `AgentController.kt` 省略 `.../agent/service/controller/`；admin 侧 `SysUserController.kt` 与 `TeamArtifactController.kt` 省略 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/`，`SysUserServiceImpl.kt` 省略 `.../admin/service/impl/`，`AdminMinioConfig.kt` 省略 `.../admin/config/`。两个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本篇提到的**一律指 agent-service 那一份**（`:105` 是它的 `GET /chat/history/{sessionId}`），只有第 7 节 REFRESH 那一行用的是 admin 那一份且把路径写全；`IsolationScope` 与 `MemoryConfig` 都是上游类型，不是 harnax 的配置类。本轮新增的引用里，`InternalApiController.kt` 同样省略 admin 那个 controller 前缀，webui 侧 `CreateForm.tsx`／`UpdateForm.tsx` 省略 `harnax-webui/src/pages/agent/components/`、两份 locale 省略 `harnax-webui/src/locales/`。凡"现在跑成什么样"的断言以 2.0.4 源码为准。

取证方式：方案定稿前是源码静态阅读 + 配置比对，未启动任何 harnax 服务；落地后第 8 节的断言跑在真 `MinioBaseStore`（Testcontainers 起 MinIO）与真装配出来的 agent 上。第 8 节第 11 条是唯一把服务起起来的一条：`HarnaxAdminApplication` 在随机端口上对着 Testcontainers 的 MySQL 与 MinIO 启动，判据取带 JWT 的 HTTP 响应；其余各条仍在同一 JVM 内的装配上跑，agent-service 本身始终没有启动。

阅读范围：本篇第 1 至第 10 节是已执行并合入 `kotlin-dev` 的方案。其中凡称"harnax 今天/当前"的句子，描述的是执行前的仓库形状；执行后的形状以第 6 节的改动清单与代码为准，第 9 节逐条标注哪些已测掉、哪些仍未验。**第 11 节是记忆分层，已按该节口径落码并合入 `kotlin-dev`**，落地形状见 11.11，逐条对账见 11.12——那一节里的"今天"指的是第 1 至第 10 节落完之后，且它不改变第 1 至第 10 节任何一条已落地的行为。

---

## 1. 目标与验收

六条，都可判定：

1. **新会话能想起旧会话。** 同一归属桶（见第 3 节）在会话 A 里说过一件耐久事实，之后新建的会话首轮提问时模型侧输入带 `<memory_context>`，回答能用上它。判据按写入顺序三段（抽取在轮次完成时异步派发、同会话的待跑任务会被后到的顶掉，`MemoryFlushMiddleware.java:159-212`，每段之前先等记忆后台静默，见第 9 节第 3 条）：会话 A 结束后该桶当天日报 `memory/<date>.md` 新增条目；整写器跑过一次后 `MEMORY.md` 非空（一轮抽取绝不直接写 `MEMORY.md`，见事实六）；同桶新建的会话 B 首轮推理输入含 `<memory_context>` 段。
2. **没有用户身份就没有记忆域。** 归属在装配期定死：一次投递若没带用户，这个 agent 就不挂桶、不建 `MemoryConfig`、不给那四个工具，装配日志点名原因，投递本身照常完成。匿名调用方共享一份记忆是数据串门，不是功能；把 `userId` 逐次塞进 `RuntimeContext` 也不是出路——那个值同时是 `agent_state` 的分桶键（事实四），为了记忆填它会把既有会话的历史整体换键。
3. **开关是一起的。** 任何一档下，"模型被教怎么用记忆"与"记忆真的被读写"两件事同时成立或同时不成立。半开（工具在清单里但没有任何钩子喂它们，或在读一个永远为空的 `MEMORY.md`）算没做完。
4. **每轮多付的模型调用次数能算死。** 上限是一轮至多 2 次抽取（按轮钩子那次，加上该轮若触发压缩则内嵌的那次），加每桶每 30 分钟至多 1 次整写。三次里只有钩子那次走记忆域自己的模型，压缩内嵌与溢出兜底跟当轮主模型（事实七）。判据：一轮结束后按调用的模型名分档计数，记忆档 ≤2、主档增量能对上压缩或兜底是否发生。
5. **记忆不进聊天。** `GET /api/agent/chat/history/{sessionId}` 的返回序列、iOS 与 webui 的契约都不因这套改动而变化；记忆文件里的内容永远不以气泡形式出现在任何会话里。
6. **清会话不动记忆，删用户才动记忆。** `clearSession` 之后该桶 `MEMORY.md` 原样在；删除用户之后该桶的 `MEMORY.md` 与日报全部取不到。

## 2. 七条事实决定方案形状

**事实一：跨会话记忆在上游只剩 harness 这一套。**
core 的长期记忆接口整族标了 `@Deprecated(forRemoval = true, since = "2.0.0")`（`Memory.java:30-34`、`LongTermMemory.java:66-71`、`StaticLongTermMemoryHook.java:77`），`LongTermMemory` 的类注释原文要求"任何跨会话持久化请到应用层去集成"。harness 这一套是文件形态的两层账：`memory/YYYY-MM-DD.md` 是只增的日报，`MEMORY.md` 是策展层，两层各由谁写在 `MemoryFlushManager.java:40-52` 的类注释里写明。方案只做这一套。

**事实二：默认档是"开着"的，harnax 是靠一个 disable 把它关掉的。**
`HarnessAgent.Builder` 的字段初值 `memoryConfig = MemoryConfig.defaults()`（`HarnessAgent.java:1228`），而 `MemoryConfig.defaults()` 的 `flushTrigger` 初值是 `FlushTrigger.always()`（`MemoryConfig.java:240`）—— 装上钩子就等于**每轮一次抽取、每 30 分钟每桶一次整写**。harnax 侧 `enableMemoryHooks: Boolean = false`（`HarnessConfig.kt:24`）经 `HarnessAgentLauncher.kt:689-695` 换成 `disableMemoryHooks()`，钩子才不存在。所以今天不是"没这个功能"，是"这功能默认开着而被显式关掉"。

**事实三：落点由 IsolationScope 与路由段共同决定，且 `agentId` 被框架写死在键里。**
`RemoteFilesystemSpec.storeNamespace(agentId)` 按 `isolationScope` 给出元组（`RemoteFilesystemSpec.java:333-352`）；每条路由再往元组尾部追加自己的段（`:314-323` 的 `remoteForRoute`）；`MEMORY.md` 走 `root` 段（`:256`），`memory/` 走 `memory` 段（`:258-259`）。`IsolationScope` **没有租户维度**，而 `agentId` 恒在键里 —— 今天能选的只有"按会话／按用户×agent／按 agent／全局"四档，没有"按租户"。

**事实四：匿名归一在三处给了三个不同的名字。**
`RemoteFilesystemSpec` 把空 uid 归成 `anonymousUserId`，字面量缺省 `_default`（`:79`、`:344`）；`IsolationScope.toNamespaceFactory()`（喂给 `WorkspaceManager` 做本地路径的，`HarnessAgent.java:2425`）在 USER 档且 uid 为空时**回落到 sessionId**（`IsolationScope.java:104-115`）；`agent_state` 的用户桶归一是 `__anon__`（`ReActAgent.java:394-399`）。而 harnax 送进 `RuntimeContext` 的 userId 本来就是空串（`HarnessAgentWrapper.kt:492`、`:1247` 两处装配 `RuntimeContext` 都是 `userId ?: ""`）。三套名字若被当成"同一个匿名桶"，会出现"写进 `_default`、本地兜底读 `<sessionId>`、状态读 `__anon__`"的三分叉。

**事实五：没有 CAS 的 store 撑不起分布式闸，整写与节流档抽取都会静默不跑。**
`StoreBackedPeriodicGate.tryClaim` 靠 `putIfVersion(ns, name, value, expectedVersion)` 抢槽（`StoreBackedPeriodicGate.java:48-72`）；首次抢槽时 `item == null`，于是 `expectedVersion = 0`，需要 store 支持 CAS-create-if-absent。任何没有覆写 `putIfVersion` 的 `BaseStore` 都走 `BaseStore.java:74` 的默认实现 —— **无条件返回 false**，且它的 `get` 若用 `StoreItem.java` 那个把 version 置 0 的两参兼容构造器，读到的版本永远比对不上。harnax 的 `minio/MinioBaseStore.kt` 已按这条补了条件写与带版本的 `get`（第 6 节），但部署侧换成别的 S3 兼容档时这条判据照样成立，所以装配时不再假定它，而是探一次。后果（探测不过且没有退路时）：`distributedStore != null` 时（沙箱分支，`HarnessAgentLauncher.kt:663-676`）闸永远抢不到，`MemoryMaintenanceMiddleware.java:165-169` 直接回 `Mono.empty()`，consolidation 与归档清理一次都不跑，且这条路径没有 warn。consolidator 拿到的正是这同一个 store（`HarnessAgent.java:2589` 的 `distributedStore.baseStore()`），所以 `MemoryConsolidator.java:359-384` 的 store 侧 watermark 同样写不进去（5 次 CAS 重试后放弃并记 warn），只是它还会同时写文件 watermark（`:350`）。**同一把闸还管着抽取本身**：`MemoryFlushMiddleware.shouldFlushNow` 在 `ALWAYS`/`NEVER` 档直接返回布尔（`MemoryFlushMiddleware.java:291-296`）不经闸，唯独 `THROTTLED` 档走 `periodicGate.tryClaim(...)`（`:297-298`），键前缀 `memory-flush:`（`:310-312`）与 maintenance 各自独立。所以 CAS 缺失时"整写不跑"与"节流档抽取不跑"是同一个故障，而缺省的 `always()` 反而是免疫的。**这条必须在打开任何记忆开关之前给出答案**：要么 store 真给得出 CAS，要么装配期探测到并退回本进程协调（第 6 节），第三种状态——静默——才是这条事实描述的故障本身。

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
| 其余文件的隔离档 | `IsolationScope.SESSION` 不动 | 非沙箱分支的 spec scope 写死在 `HarnessAgentLauncher.kt:679-680`，它一处管三样：远端键的元组（事实三）、`WorkspaceManager` 的本地命名空间（`HarnessAgent.java:2416-2425`）、flush 与 maintenance 的节流键（`:2564`）。沙箱容器复用是另一颗旋钮（`:657` 取自 `SandboxConfig.kt:37`）。翻前者会把 `sessions/` 等全部路由的命名空间一起搬走，是搬家不是开记忆 |
| 匿名调用 | 装配期就没有归属可绑：不挂桶、不建 `MemoryConfig`、不给四个工具，warn 一句，投递照常完成 | 归属取自装配入参而非逐次调用——`RuntimeContext.userId` 同时是 `agent_state` 的分桶键（事实四），为了记忆把它填进去会把既有会话的状态整体换键。而 harnax 一个 agent 实例只服务一个用户（`DefaultAgentRunner.cachedAgent` 撞到归属不符就丢缓存重建），所以装配期取到的就是这次投递的用户，调用侧再传谁都改不掉桶的键。**读侧仍挡不干净**：`readMemoryMd` 走 `readWithOverride`，路由回空后它会无条件再读宿主盘的 `workspace/MEMORY.md`（`WorkspaceManager.java:819-823`），所以桶装了而桶里还没有 `MEMORY.md` 时，"没注入"要靠宿主模板本身为空才成立，见第 9 节第 6 条 |
| 存储落点 | **A：沿用 MinIO 文件层**，记忆就是 `MEMORY.md` 与日报，harnax 不加表 | 上游三个写侧与两个读侧全都按文件读写；改 MySQL 投影要么自己重写 consolidator 的整篇改写语义，要么维护两份真值。B（加 `agent_memory` 投影表）只在"页面编辑记忆"或"按语义检索记忆"时才值得，本轮两者都不是目标 |
| 记忆不进聊天 | 记忆内容不进会话消息，`MEMORY.md` 不进 `agent_state.context` | 上游只把 `MEMORY.md` 当系统提示注入，从不作为历史消息（`WorkspaceContextMiddleware.java:512-521`）；而 harnax 的历史读路径 `DefaultAgentRunner.kt:338-341` 只渲染 `agentState.context`（`AgentController.kt:104` 的 `GET /chat/history/{sessionId}`）。这条不变量是白送的，但要在测试里钉住 |
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
| `agentSpec.memoryEnabled`（本轮新增） | 不存在，只有部署级开关 | `true` 为缺省，值取自 `agent` 行的 `memory_enabled`，只有显式 `0` 才关 | 「默认所有的 agent 都有记忆」的落点：装配判据是 `memory.enabled && !isLead && agentSpec.memoryEnabled`（`HarnessAgentLauncher.kt:646`），关掉单个 agent 不必关整域；部署侧既然整域默认打开，这一枚就是只把某个 agent 挡在记忆域外的唯一杠杆。`!= 0` 而非 `== 1` 的理由在第 6 节 agent-service 那条 |
| `distributedStore` | 沙箱分支才给（`HarnessAgentLauncher.kt:668`，非沙箱分支 `:677-681`） | 保持 | 但它一存在就选中 `StoreBackedPeriodicGate`（`HarnessAgent.java:2409-2412`），于是撞上事实五 |

## 5. `MemoryConfig` 取定

| 字段 | 取定 | 理由 |
|---|---|---|
| `model` | 记忆域专用小模型，按模型域的行 id 解析：`HARNAX_MEMORY_MODEL_ID`，`0` 沿用各 agent 自己的主模型 | `MemoryConfig.java:247-261` 支持独立 model；不配就用主模型（`HarnessAgent.java:2562`）。按事实七，压缩与兜底两条路仍走主模型，这一处要写在验收之外 |
| `flushTrigger` | `throttled(5m)`——两段式的第二段，`MinioBaseStore` 的 CAS 已落地 | 每轮一次在长会话里等于每轮多付一次抽取调用，但节流档走那把坏闸（事实五）。`throttled` 的语义是首次立即跑、最小间隔只作用于其后（`MemoryConfig.java:45,76-84`） |
| `consolidationMinGap` | `30m`（显式钉上缺省值，`MemoryConfig.java:58`） | 让"每桶每 30 分钟至多一次整写"成为可判定条款，而不是缺省的副作用 |
| `consolidationMaxTokens` | `4_000`（缺省，`MemoryConfig.java:55`） | 整篇 `MEMORY.md` 的预算，直接决定注入的 token 上限 |
| `dailyFileRetentionDays` / `sessionRetentionDays` | `90` / `180`（缺省，`MemoryConfig.java:61,64`） | 日报归档天数与会话 JSONL 保留天数；本轮不引入新的清理器 |
| `flushPrompt` | 默认 prompt 之后追加两条：禁写跨用户与跨租户信息；禁写凭据与密钥 | 抽取器写的是文件，PII 与凭据一旦入档就跟着 `<memory_context>` 每轮进模型 |
| `consolidationPrompt` | 不改 | 该字段要求恰好两个 `%d` 占位且在构造期校验（`MemoryConfig.java:282-294`），自定义的收益不抵踩错占位形的风险 |
| 四个工具 | `memory_search`、`memory_get`、`memory_save` 与记忆同批；`session_search` 同批关 | 前三个是记忆仅有的读回与手工入档入口。`session_search` 不是记忆入口：它搜的是 `.log.jsonl` 会话原文，那些文件只由压缩的 offload 一步产出（`ConversationCompactor.java:152-164` 调 `MemoryFlushManager.java:207-211`，开关是缺省为真的 `offloadBeforeCompact`，`CompactionConfig.java:283`），且它只扫宿主盘、看不见别的副本（`SessionSearchTool.java:226-239` 的注释原文 "Only scans the local disk"） |
| `maxContextTokens` | `8000`（builder 缺省，`HarnessAgent.java:1240`） | 注入预算闸门；`WorkspaceContextMiddleware.java:229-240` 先扣其余上下文再决定 `MEMORY.md` 的截断 |

## 6. 改动清单

按模块，文件级：

- **harnax-harness-core**：
  - `harness/minio/MinioBaseStore.kt` 补 `putIfVersion` 与带版本的 `get`（事实五是硬前置，且它同时决定节流档抽取会不会跑）。MinIO 侧可用条件写或"版本号对象 + `If-Match`"实现；确实做不到就在装配时显式改选 `LocalPeriodicGate`（代价是每副本各整写一次），二选一，不许沉默。
  - `harness/minio/StoreCasProbe.kt` 与 `harness/minio/ProcessLocalCoordinationStore.kt`：把上面那句"二选一，不许沉默"落成代码。装配第一次需要这把闸时，先在同一命名空间里建一个带 uuid 的一次性槽，走三步判据（版本不符必须拒、版本相同必须放、对象已存在时 `expectedVersion = 0` 的建槽必须拒），据此把"接口默认恒拒"与"网关收了 `If-Match` 却不生效"这两种相反的故障分开；结论按进程记忆，但**只记 store 真答过的**——连接没通时的"没答案"不是关于 store 的判定，记下来会让 MinIO 重启一次就把整副本永久钉在本进程协调上；记下来的那份十分钟到了重问一次（11.4 同一条理由：修好存储不该要求重启）。判据不过就把 `["coordination", "periodic"]` 这一个命名空间改由 `ProcessLocalCoordinationStore` 就地应答（`ConcurrentHashMap.compute` 内完成比较与写，槽键按进程共享，形状对齐上游 `LocalPeriodicGate` 的静态表），其余键一律透传，所以记忆桶本身仍在共享 store 里；同时打一条 WARN 说明本次部署拿到的是哪把闸。非沙箱分支根本没有 distributed store，上游那两个钩子自带 `LocalPeriodicGate`，这条路径上只有长期层的装配不探测；点了会话记忆的那台仍要探测，因为晋升这一步的节流槽用的是同一把 store 支持的闸（11.4）。装配日志里"这把闸按副本共享"的措辞跟着探测结果走，三种状态（无闸／真共享／本进程各算各的）各说各的，不许把退路报成前者。
  - `HarnessAgentBuilder.kt` 加三个透传：`memory(MemoryConfig)`、`disableMemoryTools()`、`filesystemRoute(String, AbstractFilesystem)`，对应上游 `HarnessAgent.java:1893,2232,1862`。
  - `HarnessAgentLauncher.kt` 的记忆装配段：按第 3 节与第 4 节挂记忆路由、装 `MemoryConfig` 与三个开关。路由的归属取自装配入参 `userIdentifier.userId`，取不到就整域关掉并 warn（见第 3 节「匿名调用」行）。`HarnessAgentWrapper.kt` 的 `userId` 因此保持不填——它进 `RuntimeContext` 就成了 `agent_state` 的分桶键，而历史读取按空用户寻址（事实四）。
  - `harness/config/HarnessConfig.kt` 与 `spring/HarnessAutoConfiguration.kt`：新增 `harness.memory.*`（`enabled`、`model-id`、`flush-trigger`、`flush-min-gap`、`tools-enabled`、`tenant-scoped` 桶形状），代码缺省 `enabled = false`（`HarnessConfig.kt:59-60`），绑定形状照现有 `enableMemoryHooks`（`HarnessConfig.kt:30`、`HarnessAutoConfiguration.kt:87,284-293`）；这套部署的 true 由 compose 注入，见下面 harnax-deploy 那条。装配分支上的 `MinioBaseStore` 现在只有一份（`HarnessAgentLauncher.kt:649-651`），两条分支共用，补了 CAS 的那一份因此天然覆盖两档。
  - `agent/AgentSpec.kt`：`memoryEnabled` 一个 `val`，缺省 `true`，配 builder 同名方法。装配判据读它（`memoryRequested`，`HarnessAgentLauncher.kt:1164`，装配处调用在 `:656`），所以它是运行侧唯一认识"某个 agent 不要记忆"这件事的地方。
- **harnax-entity**：`agent` 表加 `memory_enabled tinyint(1) NOT NULL DEFAULT '1'`，写进 V1 基线并同步 `harnax-entity/src/test/resources/schema-test.sql` 那份逐字副本；`Agent.kt` 加同名 `var`（缺省 1）、`AgentMapper.xml` 的 resultMap／insert 列与值／updateById 三处各加一行（insert 少了它行就落在 DDL 缺省上，向导的答复会丢）；线上契约 `AgentSpecInfoResponse` 加 `memoryEnabled: Int = 1`。
- **harnax-admin（智能体向导的开关）**：`AgentCreateRequest`／`AgentUpdateRequest`／`AgentResponse` 三处 `Int? = null`（可空才能把"没答"与"答了 0"分开），`AgentServiceImpl` 创建取 `?: 1`、更新用 `?.let`、两条读路径（`fromEntity` 与分页用的 `convertToResponse`）都要带上这个字段——少一处，向导就在一屏上看得见开关、另一屏看不见。`InternalApiController.buildAgentSpecResponse` 的 `memoryEnabled` 是**必填参数**（`:685`，会话记忆那一枚同样必填 `:688`）而不是缺省值：五个调用点里漏掉任何一个都会编译不过，而不是静默给每个 agent 下发"开"。四个读智能体行的调用点（`:443-444`、`:469-470`、`:508-509`、`:607-608`）把行上的值原样传下去，主管那一侧（`specForTeam`，`:645-646`）填 `1` 与 `0` 并写明拒绝主管的是运行侧 `!isLead`，不在这里重复一遍判断；builder 的写入在 `:947-948`。
- **harnax-agent-service**：`clearSession` 明确不清记忆（该动作只归会话数据）。`AgentSpecResolver` 把线上值映射进 `AgentSpec` 时判的是 `specInfo.memoryEnabled != 0`，与同一处 `skillSelfWrite == 1` 方向相反：授权型开关缺省为假、要显式给真，而这一域是这个 agent 的缺省状态、只有显式 0 才把它拿走，于是任何一个本运行时没预料到的取值都留在"有记忆"这一侧。报文里压根没有这个键时取的是线上契约的缺省 1（旧 admin 先发出去的那次滚动升级就属于这一类），两种拼写在这一格上等价，不等价的是越界取值。
- **harnax-admin**：记忆的读与删两个接口，作用域限于当前登录用户自己的桶，供页面核对与合规删除；删用户的记忆清理挂在既有删用户链上（`SysUserController.kt:119` 的 `DELETE /{id}`，实现 `SysUserServiceImpl.kt`），按桶前缀清 `root/MEMORY.md` 与 `memory/` 下全部对象，且**该用户所属的每一个租户各清一遍**（记忆桶按租户分键，只清当前租户会留下其余租户的对象）；admin 另读一颗与写入侧同名的开关 `harnax.memory.tenant-scoped`（`HARNAX_MEMORY_TENANT_SCOPED`，与 runtime 的 `harness.memory.tenant-scoped` 同值），关掉后桶键不含租户对，读侧必须跟着走，否则整桶记忆表现为空；store 拒绝一次读要答成故障（信封 503），不许伪装成"这个 owner 没有记忆"。admin 已有自己的 MinIO 客户端（`AdminMinioConfig.kt`）与按对象读写 MinIO 的先例（`TeamArtifactController.kt`），这两个接口直接照那一形状走，不经 agent-service；记忆小模型复用模型域。这两个接口的端到端覆盖是新增的 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/MemoryOwnerBucketMinioIT.kt`——本模块唯一同时起服务与接真 MinIO 的记忆用例，它灌进桶的对象正文用的是写入侧记录下来的那份字面量信封（第 8 节第 11 条、第 9 节第 9 条）。
- **harnax-session-router**：本轮不加新转发 —— 记忆不是实例本地状态，而是共享 store 里的对象，按第 3 节的桶键寻址；同一用户的两个会话即使绑在不同实例上，也读到同一份 `MEMORY.md`。抽取只在当次调用所在实例异步发生，不需要跨实例寻址。
- **harnax-deploy**：`docker-compose.yml` 给 agent-service 注入 `HARNESS_ENABLE_MEMORY_HOOKS` 与 `HARNAX_MEMORY_ENABLED` / `_MODEL_ID` / `_FLUSH_TRIGGER` / `_FLUSH_MIN_GAP` / `_TOOLS_ENABLED` / `_TENANT_SCOPED`，给 admin 注入 `HARNAX_MEMORY_TENANT_SCOPED`。两枚总开关在这一层取的是 `${VAR:-true}`（compose `:541`、`:542`），与 `application.yml` 里的 `false` 不一致——这是这一域唯一一处两侧故意不同值的缺省：compose 内的部署因此默认整域打开，而 `application.yml` 的 `false` 描述的是"在这份 compose 之外起的一次运行"，那里没有 MinIO，整域若也默认打开就让每个智能体都撞在装配期那道"没有 store"的拒绝上，连普通对话都起不来。其余各行仍与 `application.yml` 同值，所以不在 `.env` 里给值的部署，除这一域之外行为不变。这一层透传是这一域在集群部署里能不能打开的分界：compose 用的是逐服务的 `environment:` 而不是 `env_file`，没有对应行的变量在容器里根本不存在，`.env` 里单写 `HARNAX_MEMORY_ENABLED=true` 只会停在工作树。域的开法记在 `.env.example` 与 `docs/deploy-harnax-agent-service.md`、`docs/deploy-harnax-admin.md` 的环境表、`docs/deploy-harnax-harness-core.md` 的配置参考里：`HARNAX_MEMORY_ENABLED` 与 `HARNESS_ENABLE_MEMORY_HOOKS` 要一起给——现在两枚都由 compose 缺省成 `true`，要整域关掉得两个一起填 `false`，只给前者会在装配时拒掉整个智能体（连普通对话一起起不来）；单个智能体要不要记忆不在这里配，走向导那一枚「长期记忆」（第 4 节）。而 compose 钉成 `false` 的 `HARNESS_ENABLE_WORKSPACE_CONTEXT`（`:531`）不看这一域的请求——`enabled=true` 会强制打开它，那条 `SandboxConfigurationException` 噪声随记忆一起回来。
- **harnax-webui / harnax-ios**：向导的创建与编辑两屏各加一枚「长期记忆」——`CreateForm.tsx` 的 state 缺省 `true`，`UpdateForm.tsx` 按行上的值初始化为 `values?.memoryEnabled !== 0`，即"没答"与缺字段都算开、只有显式 `0` 才取消，与第 4 节那条反序判据一致；文案是新 key `pages.agent.memoryEnabled` 与 `pages.agent.memoryEnabledHint`（zh 588-589、en 589-590），hint 里点名"已经落盘的记忆文件不会删除，需要清理请到记忆管理页"，那条路径 `/agent/memory` 在本轮之前已存在；`typings.d.ts` 的 `AgentItem`／`AgentCreateRequest`／`AgentUpdateRequest` 三处各加 `memoryEnabled?: number`。记忆管理页与 iOS、聊天页本轮一律不动。

## 7. 边界、失败与限制

| 情形 | 行为 |
|---|---|
| `putIfVersion` 未实现就开钩子 | maintenance 一次都不跑且无 warn，`throttled` 档的抽取同样一次都不跑（事实五）；`always()` 档不经闸因此照常跑，consolidation 退回文件 watermark。修法是先补 store 再开节流；补不了的部署由装配期探测接住（第 6 节）——探测不过就把协调命名空间改成本进程应答并 warn，代价退回"每副本各整写一次"，而不是整条节流档静默不跑 |
| 沙箱分支与非沙箱分支键形状不同 | 单挂路由不在两条分支里各写一遍，而是在两条之外只挂一次（`HarnessAgentLauncher.kt:721-722`），所以同一 owner 换部署档读到的是同一个元组；分支之间不同的只有 filesystem spec 与上游那两个钩子所用的 base store——沙箱分支 `:671` 在要记忆时把它换成 `memoryStore(wantsMemory, mountedBucket, minioStore)`（`:692`，内含 `coordinationStore()` 与按桶的整理进度包装），非沙箱分支 `:701-705` 不带 distributed store，因此上游那两个钩子在本副本各算各的。第 11 节那一层的晋升节流不在这一格上：两条分支都直接把 `coordinationStore(...)` 包成 `StoreBackedPeriodicGate`（`:738`），见 11.4 |
| lead agent | 装配判据 `memory.enabled && !isLead && agentSpec.memoryEnabled`（`memoryRequested`，`:1164`）里这一项与两条 filesystem 分支的 `!isLead` 是同一条理由（`:671`、`:701`）：lead 因此没有 filesystem，`WorkspaceManager` 的读写退回宿主盘且**不带命名空间**（`appendLocalFile`/`writeLocalFile` 落在 `workspace/<相对路径>`，`:851`、`:883`；读侧回落在 `:823`）。整域开着而装配的是主管时另留一条 info 点名原因（`:1181`）。记忆域只属于成员与普通 agent，lead 显式排除 |
| team 成员 | 成员各按自己的 agentId 建桶；同一次委派里 lead 与成员的记忆不通；一次委派里成员用的会话键由根会话推导（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:56` 拼成 `team-<rootSessionId>-m<memberAgentId>`），换一个根会话就是换一个键 |
| 投递没有指名用户 | 装配期就把整个记忆域关掉：不挂桶路由、不装钩子、不交工具，并留一条 warn 说明原因。这类调用今天占多数，所以"记忆没生效"首先该查这次投递有没有带上 user id。不给它建一个匿名桶是刻意的 —— 匿名桶会把不同人的日报混进同一个对象键 |
| 单个智能体自己在向导上关掉 | 读写两头一起停：不挂桶路由、不装 `MemoryConfig`、不给那四枚工具，`<memory_context>` 也随之没有；`:787-799` 那三处开关（workspace context、memory tools、两个钩子）由同一个 `memoryEnabled` 一起翻。**已经落盘的对象一个都不动**——清空是记忆管理页与删用户那条链的活，翻这枚开关不产生删除。其余 agent 与整域照旧 |
| 这枚开关翻了何时生效 | 装配好的 agent 按会话缓存在 Caffeine 里 30 分钟（`DefaultAgentRunner.kt:78-80`），旧会话在这次过期之前仍按装配时的那份答复跑；要立刻换，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:105` 的 `POST /api/admin/agents/refresh-sessions` 推一次 REFRESH，运行时丢掉该会话的缓存条目（`DefaultAgentRunner.kt:304`），下一条消息才重建。这一段窗口本轮未实测（第 9 节） |
| 记忆的所有者只在装配期绑定 | 上游按 `RuntimeContext.userId` 给持久化 agent 状态定槽位（`ReActAgent` 的 `slotKey(userId, sessionId)`），而 harnax 读会话历史的几处都用空用户寻址同一个 store。两者是同一个值，所以 `HarnessAgentWrapper.userId` 保持不填；把它填上会让已经落库的状态行读不到。所有者因此来自投递身份，在装配时算好并固定到路由上 |
| 两副本同桶并发 append | append 是读全文加全量回写，跨副本可能互相覆盖（事实六）。不变量是"最多丢一次并发写"，不是"绝不丢"；第 4 节取定的 `throttled(5m)` 把这个窗口夹在 5 分钟里，退回 `always()` 的部署档下每轮都写、窗口最大 |
| `memory_save` 与 consolidation 撞车 | 整篇改写可能重排 `memory_save` 刚追加的那段；按桶串行才有保证，不串行就把它当可接受的最终一致 |
| `MEMORY.md` 越写越长 | 由 `consolidationMaxTokens` 与 `maxContextTokens` 两处夹住，超了是注入前截断而不是拒写 |
| 日报被归档 | 90 天后移入 `memory/archive/`（`MemoryMaintenanceMiddleware.java:234`），`memory_search` 因此少一档可搜的历史；本轮不做归档回读 |
| 记忆页请求里的 agent 名字带路径形状 | 实测是两层不同的拒：仍然拼得出一个合法路径段的（`a..b`、`...`）走到 `MemoryObjectKeys.isValidAgentId`，按本模块业务失败的形状回答——HTTP 200，信封里 `code` 400 且不带 data；要编码之后才成为路径分隔符的（`%2F`、`%5C`）与本身就是上跳段的（`..`、`%2E%2E`）在 `JwtAuthenticationFilter` 之前就被 Spring Security 的路径防火墙丢掉，GET 与 DELETE 都是 HTTP 401，请求根本到不了控制器。两种都在 store 之前被拒，判据是拒前后各取一次全桶键快照逐字相等（`MemoryOwnerBucketMinioIT`） |
| 提示注入 | `MEMORY.md` 每轮以 `<memory_context>` 进模型，等价于一段用户可控的长期系统提示。写侧只有 agent 自己（工具与抽取器），没有外部通道能直接改桶里的这个对象；风险记此，本轮不额外设防 |

## 8. 测试方案

主断言（缺一条就不算做完第 1 节）：

1. **跨会话想起**：桶内会话 A 跑一轮（替身 model 桩住抽取），等记忆后台静默后断言该桶 `memory/<date>.md` 新增条目（一轮抽取不写 `MEMORY.md`，事实六）；再触发一次整写，断言 `root/MEMORY.md` 含该条；同桶新建会话 B，断言 B 首轮推理输入含 `<memory_context>` 且能搜到那条。
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

跑法沿用本仓既有配方（JDK 21、先探 Docker 再决定排除 IT）。桶的读写在单测里可以用 `InMemoryStore` 顶，但第 4 条必须打在 `MinioBaseStore` 的真实现上，否则正好绕过事实五。第 11 条反过来不能用桩：mock 出来的客户端只会回答桩让它答的内容，前缀列表、递归标志与删除的作用域这三件事只有在真服务器上才成立。

## 9. 验证与未验（逐条对账）

编号沿用本节正文的条目：定稿时的六条保持原措辞，第 7、8 条是这一域加上 agent 粒度的开关之后补的，第 9 条是补 admin 那两头接口的端到端 IT 时新增的待验点：

| 条 | 状态 | 判据 |
|---|---|---|
| 1 | 已验 | 覆盖关系与两条装配分支由 `HarnessAgentLauncherMemoryTest` 的 `the two memory files move to the owner bucket while everything else stays put` 与 `a sandbox deployment gets the same owner bucket for its memory` 断言，两条都带"其余文件留在原处"的反证；`glob("*.md", "memory")` 走的是桶由 `MemoryBucketPipelineTest.the ledger glob answers from the owner bucket` 断言 |
| 2 | 已验（MinIO） | `MinioBaseStoreCasTest` 在真 MinIO 上断言 CAS 建槽、版本不符退 `false`、多写者只放一个、以及上游 `StoreBackedPeriodicGate` 按窗口只放行一次。其它 S3 兼容档给不给得出 CAS 由装配期当场定性：`StoreCasProbe` 对当次部署的 store 探一次，探不过就把协调命名空间换成本进程应答并 warn，两种相反的失效（默认退让与忽略版本前置）各自被断言。判据在 `StoreCasProbeTest`（7 条，含"探不通不算结论"）、`ProcessLocalCoordinationStoreTest`（5 条，含"协调槽不外泄给 delegate"）与 `HarnessAgentLauncherCoordinationTest`（5 条，含"结论按进程记一次"与"结论过了十分钟重问一次"）；仍未实测的是别家 S3 档在真服务器上到底落在哪一种，以及回退档在多副本下各整写一次 `MEMORY.md` 的真实代价 |
| 3 | 已验 | `MemoryGateFalsificationTest` 直接以 `MemoryBackgroundTasks.awaitQuiescence(60s)` 当落盘探针，进程级计数没有把用例耗时变成阻塞问题 |
| 4 | 未验（交人工） | 抽取质量要真模型，替身 model 测不出来。本轮不跑：判据是在装了这一域的部署里发起一轮**带用户身份**的会话（匿名投递在装配期就没有桶，见第 7 节），等记忆后台静默后看该桶当天的 `memory/<date>.md` 写了什么——召回够不够、噪声有多少、有没有把凭据或别人的信息写进去（第 5 节那两条禁令是否真挡得住）。看的地方有两个：admin 的记忆页读的就是这一份，或直接按第 3 节的键取 MinIO 里的对象 |
| 5 | 未验 | 只断了布尔形状（`on needs the workspace context even when that knob stayed off`），`AGENTS.md` 与 `knowledge` 注入回来之后的 token 与行为变化没测 |
| 6 | 已验（挂桶侧） | `MemoryBucketPipelineTest.the memory pipeline leaves no copy on the host disk` 断言抽取与整写两条腿都不在宿主盘留 `MEMORY.md` 与日报；未挂桶（匿名）装配下宿主回退到底读到什么仍未验 |
| 7 | 已验 | 四段接缝各有断言：列与行的往返由 `AgentMapperTest$CustomQueryTests.the memory answer round-trips through the agent row` 打在真 `mysql:8.0` 上（insert 与 updateById 都带上这一列，缺省 1、显式 0 落 0）；admin 写侧由 `AgentServiceImplTest` 四条（`createAgent should default memory on when the request is silent about it`、`createAgent should honour an agent that asked for no memory`、`updateAgent should write the memory answer it is given`、`updateAgent should leave memory untouched when the request omits it`），下发由 `InternalApiControllerTest.getAgentSpec delivers the memory answer written on the agent row`；读侧映射由 `AgentSpecResolverTest` 两条（`an agent that turned memory off arrives turned off`、`memory arrives on for a delivery that never names the switch`）；装配由 `HarnessAgentLauncherMemoryTest` 两条（`an agent that turned memory off gets no hooks no tools and no borrowed reader`、`an agent that turned memory off does not get the store probed for a gate`） |
| 8 | 未验 | 翻了开关到下一次装配之间的窗口（第 7 节「这枚开关翻了何时生效」那一行）只有源码层结论：30 分钟的会话级 Caffeine 缓存与 `refresh-sessions` 推 REFRESH 都是本域之前就有的机制，本轮没起真服务复现 |
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

不在本轮：记忆内容的页面编辑与语义检索、`extensions-mem`（Mem0、ReMe、百炼）与 `agentscope-service` 的托管记忆服务、`MEMORY.md` 的版本历史、日报归档的回读、跨 agent 的用户记忆合并、iOS 与聊天页的任何改动、把记忆用于跨会话检索。记忆的会话粒度分层不在这——它是第 11 节那一轮，已按该节口径落码（落地形状见 11.11），今天开双层的 agent 已有两种粒度。

前五处拍板都按表中"推荐"项执行，第 6 节的改动清单即其落地形状；P6、P7 是执行过程中新增的两处，落在同一张表里备查，不是待定项。

| 拍板项 | 选项与推荐 |
|---|---|
| P1 记忆归属维度 | 推荐 `tenant × user × agent` 单挂路由（第 3 节）；次选纯 `user × agent`（改动最小，但同 `userId` 跨租户同桶）；不建议把 filesystem 的 `IsolationScope` 整体翻成 `USER` —— 它一处管三样（远端键元组、本地命名空间、flush 与 maintenance 的节流键，见第 3 节），翻它是搬家 |
| P2 存储落点 | 推荐 A（MinIO 文件层，不加表）；B（MySQL 投影 `agent_memory`）只在要页面编辑或语义检索时才有价值，而这两件都排在上一段之外 |
| P3 `MinioBaseStore` 的 CAS | 推荐补实现（第 6 节）。这把闸同时决定节流档抽取会不会跑，不是只影响整写；退路是记忆域强制 `LocalPeriodicGate`，代价是多副本下每副本各整写一次 `MEMORY.md` |
| P4 `flushTrigger` | 与 P3 绑死：CAS 落地前保持 `always()`（每轮一次抽取，走记忆小模型），落地后切 `throttled(5m)`。单独推 `throttled` 在 CAS 缺失的部署档下等于把记忆整个关掉（事实五）。**已结案**：CAS 与装配期探测都落地，compose 的缺省即 `throttled` + `5m`（`:547-548`） |
| P5 `enableWorkspaceContext` 与记忆同批 | 推荐同批（记忆读侧必须靠它），但要接受它是本矩阵最大的连带面；分批则本轮记忆只写不读，验收第 1 条要相应降级 |
| P6 记忆的默认归属 | 拍板：**默认所有 agent 都有记忆**，向导上另加一枚「长期记忆」逐台取消（第 4 节 `agentSpec.memoryEnabled`，只有显式 `0` 生效）。次选是反过来的授权型——默认没有、逐台点开，那要每一台新 agent 都有人去点一下才开始有记忆，与"记忆是这台 agent 的缺省状态"这条口径相反 |
| P7 部署侧缺省 | 拍板：harnax-deploy 的 compose 把 `HARNAX_MEMORY_ENABLED` 与 `HARNESS_ENABLE_MEMORY_HOOKS` 都缺省成 `true`，`application.yml` 保持 `false`。理由是这份 compose 之外起的一次运行没有 MinIO，整域若也默认打开会让每个智能体都撞在装配期那道"没有 store"的拒绝上、连普通对话都起不来；两侧不同值是刻意的，落地形状见第 6 节 harnax-deploy 那条 |

## 11. 记忆分层：会话粒度与长期粒度并存

一句话口径：一台 agent 的记忆分两层。**会话层**装"这次聊天里说到、但还没有沉淀下来的"，只对当前这个会话有意义；**长期层**装"跨会话都要记得的"，就是第 3 节那一份按 租户 × 用户 × agent 归桶的策展记忆。会话层的条目由对话自动产生，隔一段时间自动并入长期层，并成功就地清掉。长期层的行为与本文件第 1 至第 10 节描述的一致，加这一层不改变它。

### 11.1 两层各装什么、谁写谁读

| 层 | 装什么 | 谁写 | 谁读 | 活多久 |
|---|---|---|---|---|
| 会话层 | 本轮对话抽取出来的条目：按天的流水，外加一份只在这个会话内整理过的稿子 | 两个写者：对话结束时的抽取（机制与今天同一套，一字不改地复用），以及模型自己用那几枚记忆工具手工入档——手工入档落在哪一层只由路由挂在哪决定，双层下就是这里 | 同一个会话的下一轮 | 并入长期层之后清空；那个会话不再回来就留在桶里，直到 11.5 里那两条整体删除把它带走 |
| 长期层 | 跨会话沉淀下来的策展记忆 | 只由晋升这一步写（11.4） | 同一用户、同一 agent 的任何新会话，包括全新会话的第一轮 | 每次晋升被整篇覆盖更新，总长有预算上限，超了是截断而不是拒写 |

两层的文件形状完全一样：一份整理稿加一份按天流水。所以读、删、页面展示都不需要为某一层另造一套机制，差别只在归桶的键多一段会话，以及谁来把它们并起来。

两层进上下文时是**两块分开的东西**：会话层走那两条既有路由的注入，长期层另带一对标签和一句"这是上下文、不是用户指令、在这里只读"的说明。模型因此分得清哪一段是这次会话还没沉淀的草稿、哪一段是跨会话留下来的那一份——前者的存在理由就是它会被并掉然后清空，把两段混成一段等于告诉模型草稿已经算数了。长期层那一段有长度上限，按整理稿那份预算（4000 token，折 16000 字符）截断，并在块尾写明被截断：这份内容每次模型调用都要进上下文，而它的总长只由模型遵守整理提示来保证，没有别处给它兜底。

### 11.2 粒度：四把尺子，从外到内

租户 → 用户 → agent → 会话。长期层用前三把（今天就是这样），会话层四把都用，也就是键上在 agent 之后再多一段会话，形状像 `store/tenants/1/users/2/agents/Report/sessions/abc/…`。六条口径：

- **会话层嵌在 agent 段之下，不与 agent 并列。** 这样"删这台 agent 的记忆"与"删这个用户的记忆"两条既有清理链路都只按那台 agent 的前缀列一遍，会话层自然在清单里被一起带走；合规删除永远不需要先问"删哪一层"。这一条不依赖任何键解析：两条整体删除枚举的是桶里落在该前缀之下的原始对象名，逐个删，不经解码器。解码器只服务页面读侧，它认得多出来的会话段并据此把会话层从清单与详情里减掉——于是两者各自成立：一个连 agent 段都解不出来的键（比如 agent 自己带斜杠）仍会跟着主人被删掉，只是永不出现在页面上。
- **会话层跟着租户那枚开关一起动。** 部署侧把记忆键上的租户段关掉时，两层同时从用户段起键、各自少一截，不会出现一层按租户写、另一层不按租户读，因此不需要为会话层新增第二枚开关。
- **会话层不跨 agent。** 团队里主管把活委派给成员时，成员有自己的两层，读不到主管那个会话的会话层。这与第 7 节"成员各按自己的 agent 建桶、同一次委派里主管与成员的记忆不通"是同一条口径。
- **匿名投递两层都没有。** 会话层同样需要用户那一段，否则不同人的流水会混进同一个键。第 7 节"投递没有指名用户就整域关掉"那条判据不变，只是关掉的范围现在覆盖两层。
- **换粒度不等于搬家。** 只有整理稿与按天流水这两条路由换键，会话状态、沙箱工作区、其余文件的隔离档一个都不动，理由与第 3 节"单挂路由那两条、其余不碰"是同一条。
- **会话那一段只能让桶更深，不能让它挪窝。** 会话 id 原样拼进键，所以一个带斜杠的 id 只多出层级——`sessions/a/b/root/MEMORY.md` 仍在那台 agent 的前缀之下，跟着 11.5 那两条整体删除一起走。想把桶往上挪的 id（`../`、挪到够得着别人的用户段那种）在写入那一刻就被存储按对象名规则拒掉：`.` 或 `..` 那一段不允许出现在对象名里。这一拒落在"存储读不出来"那一档，与 11.10 第三条说的是同一种故障，因此它不会把这一层读成空层。`MemorySessionKeyShapeTest` 在真 MinIO 上把两种形状各钉一次，钉的是「落在 agent 前缀之外的对象数为 0」而不是「它抛了异常」——换一个会自己归一化路径的存储档时这条断言会红，而不是悄悄放过。

### 11.3 开关：逐台 agent 选，缺省就是今天

与第 4 节那枚「长期记忆」并列，再加一枚「会话记忆」。两枚合起来决定四种装配：

| 长期记忆 | 会话记忆 | 结果 |
|---|---|---|
| 关 | 任意 | 整台 agent 没有记忆域：不挂桶、不装抽取、不给那四枚工具，两层一起没有。会话记忆那一枚此时不参与判断 |
| 开 | 关（缺省） | 今天的行为，逐字不变：只有长期层 |
| 开 | 开 | 双层：抽取先进会话层，按 11.4 晋升进长期层，两层都注入模型 |

缺省组合是"开加关"，所以这一层落地不改变任何一台现有 agent 的表现；要双层得在向导上逐台点开。

翻这一枚只改变一件事——路由挂在哪（`HarnessAgentLauncher.kt:721`）——它不搬家、不删除、也不改写任何已经落盘的对象。两个方向各有一次冻结，都得知道：

- **开→关：那个会话层从此三样都没有。** 没有读者（挂载的路由回到长期桶，`<long_term_memory>` 那一块也随之不装，它只在双层时挂上，`:728`）、没有晋升者（晋升中间件与它那道探测一起在 `:723` 那一段里，双层才装配）、也没有归档者（90 天归档走的是挂载路由，如今指向长期桶）。会话层里还没并走的东西就留在桶里，直到删这台 agent 或删除用户把它们带走（11.5 第二、三条）。这不是泄漏：那一段前缀仍在这个 owner 名下，仍在这两条链路的作用域里。
- **关→开：长期桶的流水段停止增长，而它此前的内容不再可检索。** 抽取从此写进会话桶，长期桶不会再长新的按天流水；旧流水仍随 11.6 的清单列着日期，却不再被 `memory_search` / `memory_get` 命中——那四枚工具寻的是挂载路由，此刻指向会话桶。长期层那份整理稿仍然被注入、仍然被每次晋升整篇覆盖。也就是说这一枚翻上去之后，"页面上这台 agent 的记忆"与"模型此刻能检索到的记忆"第一次分成两处看。

部署侧另配了一枚，但它的作用比"把整份部署翻成双层"窄：**admin 建智能体时的初值**（`harnax.agent.session-memory-default`，缺省 `false`）。它只在插入那一刻生效，而且只作用于**没带这一项的请求**——向导每次都显式给 `0`/`1`，所以它从来不听这一枚；存量 agent 保持自己已有的答案，把它打开也不会被改回去。也就是说这枚实际服务的是「直接打 admin 这个接口建 agent 且不填这一项」的用法（curl / Swagger / 经 nginx `/api/admin/` 的 API Key 调用）——本仓唯一会建 agent 的调用方是向导，而它每次都显式给值。落得这么窄是拍板的结果，见 11.9 的 P16。本节不改第 4 节那张已落地的矩阵，那张表描述的是已经跑起来的这一批。

### 11.4 晋升：什么时候并、并的时候做什么

- **触发靠节流定时，不靠"会话结束"。** harnax 里没有可靠的会话结束信号：绝大多数会话只是不再有请求，而"清空会话""删除会话"只在用户主动操作时才走。把晋升挂在那两个动作上，表现就是一部分会话的记忆永远停在会话层。取定的做法是这个会话每有新对话就顺带看一眼到没到点，到点就并；间隔是一枚配置项，缺省 30 分钟，与长期层整写的节流同源；负值在装配那一步就被拒，`0` 是合法值，意思是每一轮都赶一次晋升（除这一轮自己的模型调用外再多一次）。**判"到没到点"排在"这一层有没有东西"之后**：占槽不可退回（这把闸只有一个方法，赢了就把窗口关掉的时间戳写进去），而往这一层填内容的抽取是在回答之后才派发的、还要走一次模型往返，所以第一轮通常读到空。先占槽再读，就会把整个窗口花在空层上——比一个窗口还短的会话（占了大多数）从此一次都不晋升。读不出来的那一档按空层同样处理：不占槽，窗口留给先填上内容的那一次。
- **并的动作**：取会话层的整理稿与还没并进去的流水，连同长期层现有内容一起交给模型，产出一份**合并后的完整长期记忆**，再写回长期层。写回带版本比对：这期间若已有人（包括同一用户的另一个会话）改过长期层，这一次直接放弃、不重读重试，因为材料还在这个会话层里，下一轮对话会拿那时更新的长期层再并一次；长期层还不存在时用的是"必须还不存在"这一档前置。绝不盲覆盖。
- **清理逐对象进行，每个对象各带一次正文复核。** 写回成功之后清会话层，是先重读那一个对象、确认它从这次读起到现在**字节没变**，才删它；这期间被写过就不删它，留着下一次再并。比的是正文而不是存储给的那个号：无保护的写可以把任意字节落在任意版本上，而这一步紧接着要删的正是那些字节的唯一一份副本。删完还要回读确认真的走了——这一层的存储对象在一次失败的删除上只是打一条日志然后正常返回，不确认就会把没带走的条目当成已沉淀，下一个窗口再把它并一遍。会话层的按天流水是追加式的，同一次对话在模型往返之间就能给它添新条目，所以"读到的那份已经不是现在的那份"是真实会发生的状态，不是理论上的。这道复核把危险窗口从"一整趟模型往返"压到"一次读与一次删之间"，并且落在最小的动作上：清理不许比它更宽。桶里那份整理进度不参与清理，它数的是流水写于何时，留着才不会让下一次整写重读这层已经没有了的条目。存储那一头的删除动作"失败也不抛、只留一条日志"保持原样：它的签名是不返回东西的那一种，改成抛会让到期归档那一轮 maintenance 整体失败。要确认的地方各自确认——这一层的清理按上面那半条回读，管理面两条整体删除各自抛错并计数；剩下的暴露面只有上游归档与整写清理那两处，表现是"对象还在、一条 warn"，不是静默丢失。
- **晋升只在存储真认版本比对的部署上装配。** 装配那一步先探一次存储：它对任何版本号都答"写进去了"（也就是等于没比对）时，这一台 agent 只有会话层、不装晋升，并留一条日志说明怎么把它取回来——修好存储的版本前置条件。这里选择拒绝而不是降级，因为并的动作是"覆盖长期层，再删会话层里的那一份"：两个会话同时被允许写，必然有一个把另一个刚沉淀的内容盖掉，而盖掉的那一份已经按成功清理了自己的原件。第 6 节那枚节流槽可以降级成各进程自己计时，因为它的失败后果只是重复整理；这里的失败后果是丢记忆。探针没能得到结论（存储直接抛错）不算"存储不行"，晋升照装——同一份存储的故障会在并的那条路上自己撞到，而那里失败并不掉任何东西。被撤走的只有晋升这一步：长期层那块只读注入照旧挂上，所以这台的表现是"会话层永远不被并走"，不是"这台没有记忆"。探测结论按进程记十分钟，过期后重问一次——理由与第 6 节那条同源且在这里更要紧：这一条说的是"修好存储就能拿回晋升"，答案不许比修好本身更长寿。晋升自己的节流槽在两条装配分支上都由同一个存储支撑（`StoreBackedPeriodicGate` 包的是第 6 节那把探过的闸），退路与那两个钩子同源：同一份探测结论、同一个包装类，只是槽键另起一名（`memory-promotion:SESSION:<会话>`），各进程自己计时那一档因此互不牵制。
- **合并结果缩水就按模型没做完处理。** 合并后的文本明显比长期层现有的短（不足一半），这一次算失败：不写回、不清理、留一条日志。提示里那句"不许丢条目"并不能保证模型真把两份都写全，而失败的方向不能是"长期层被这次会话那几条换掉"——那是这一层唯一一份每次调用都进上下文的内容。**这一道不设"已经超预算"的例外**：飘到预算之上的那一份最容易被模型答成四分之一，而这一步写完就删掉会话层里那份副本，塌掉与认真整理从外面看一模一样。超预算的层照样会被整理：一次窗口收进一半，几个窗口收敛到预算内，代价只是慢，不是丢。
- **一次合并有时间上限（缺省 5 分钟）。** 它跑在会话共用的那个阻塞调度器上并占住其中一个 worker，也在优雅停机要等的在途计数里；没有上界的等待等于让一个不回答的模型一直占着这一份并发能力。上限取得比一轮对话自己的超时宽松，因为一次合并要在一个补全里重写整份策展层。超时按失败处理，与上面两条同一条口径：会话层原样留着。
- **失败照样把整个窗口花掉，这一条按接受处理。** 那把节流闸只有"抢"这一个动作、没有归还：版本冲突、模型出错、超时、缩水、读写被拒任一发生之后，这个会话要等满一个窗口才有下一次机会。接受的理由是失败的方向已经安全——材料逐字留在会话层，代价只有延迟。两种改法都不换这个代价：把槽上的时间戳回写需要一次带版本比的归还，而同一台 agent 的两个会话此刻正抢同一份长期层，写回的正是上面"并的动作"那条里"绝不盲覆盖"要防的那一类互踩；给失败单独缩短窗口要在槽上多存一份"上一次是失败"的状态，那是这一层唯一一处会随失败增长的东西。换到的只是早几分钟重试。
- **三条硬口径。** 一、失败绝不清理：版本冲突、模型出错、写回失败、超时、缩水、读或写被存储拒绝任一发生，会话层对象数量与正文逐字未变，宁可下一轮重复并一遍（合并时带去重），也不能把唯一一份还没沉淀的记忆删掉。每一次尝试都有一个说得清的结果档位（没有内容／并成功／版本冲突／模型失败／存储拒绝），存储故障不许被算成"这一层本来就没东西"。二、晋升不在对话链路上：它出错只留一条日志，当轮回答照常。三、各会话独立计时：同一台 agent 的两个会话各自晋升，互不阻塞。
- **用的模型沿用记忆域那一枚**（第 5 节 model 那一行），没配就跟着这台 agent 的主模型，与今天抽取的取值口径一致，不新增第二份配置。
- **团队会员的会话层按根会话分档，晋升槽跟着键一起换。** 一次委派里成员那一档用的会话键由根会话推导（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:56` 拼成 `team-<rootSessionId>-m<memberAgentId>`），而会话层的桶键与它的晋升槽键都取自这个会话键（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/memory/MemoryPromotionMiddleware.kt:70`）。于是一台被多个会话委派过的成员，每个根会话各留一份自己的会话层，只有同一个根会话再次开口并且到点时那一份才被并走；没有第二次开口的根会话，那几份留在桶里。回收不受这一格影响：那些键都在同一个主人前缀之内，删这台 agent 与删用户两条途径按前缀一起带走（11.5 第二、三条）。这台成员要不要写会话层，取决于它自己那一行上的开关（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:728`），两种装配都成立。

### 11.5 清理：四道回收，加一道明确的"不清"

| 途径 | 覆盖哪层 | 说明 |
|---|---|---|
| 晋升即清 | 会话层 | 只有并成功才清，见 11.4 第一条硬口径 |
| 记忆管理页删这台 agent | 两层 | 作用域是这台 agent 前缀下列回来的**全部原始对象名**，逐个删，不经键解析；会话层嵌在它下面（11.2 第一条），因此自然一起带走 |
| 删除用户 | 两层 | 该用户所属的每一个租户各走一遍（沿用第 6 节那条既有口径，不为新层加代码），每一遍同样是按前缀列原始对象名，解不出来的键也照样带走 |
| 按天流水到期归档 | 两层的流水 | 90 天归档那条既有机制对两层同样成立 |
| 清空会话、删除会话 | **都不清** | 维持第 1 节第 6 条：那是会话数据的动作，不动任何一层记忆 |

"删会话不清"的代价写在明处：被删掉的会话若还有没并完的流水，那些条目此后**没有任何读者**（会话层的注入只认同一个会话），也不会再有机会被晋升（不会再有那个会话的装配），只是留在桶里，直到上面第二条或第三条把它们带走。归档那一档还有一层去向：一份会话层流水一旦满 90 天被移进 `memory/archive/`，它就落在流水段的下一层，而晋升取的是流水段的直接子对象——归档件因此不会被并走，它是这个会话留在桶里的一段历史，不再是待沉淀的草稿。这一份欠账在页面上是**看得见数量、没有手动了账的入口**：有多少个会话还没并走由 11.6 第三条那一枚计数说清，而"立即沉淀"那一个动作不提供。理由是它与这一节的回收口径冲突：一次晋升要的节流槽、存储版本比对的探测结论与它取的材料都挂在会话装配那一步（11.4 第一条），要让一个页面动作在没有对话的情况下并起来，等于新造一条清理链路，正是 11.8 拒掉的那件事。回收因此只有两条既有走法：这台 agent 的下一轮对话把窗口走到，或上面第二、三条那两道整体删除把它带走。这一枚的裁定记在 11.9 的 P15。

### 11.6 页面上看得见的

- 记忆管理页仍然只列**长期层**：一行一台 agent，正文是那份跨会话的整理稿，日期是它的流水日期，与单层时一致。会话层与长期层共用一个前缀，所以这句不是"列出来再看运气"：读的那一侧按解析出的会话段做减法，清单与详情都只保留不带会话段的对象，没晋升的草稿和它那一天的流水不会因为躺在同一个前缀下就出现在页面上；同一处减法还顺手把 11.7 那个进度对象挡在外面，它的名字读不成日期。删的那一侧连这道减法都不做——它按前缀列原始对象名，见 11.5 第二、三条。
- 双层那一台在页面上有一处看得见的落差，而这一处是说破的：它的长期层只剩整理稿。抽取与整写都落在会话桶里，长期桶的流水段不再长新文件（11.7 那份进度对象跟的是本次装配挂上的那个桶，双层时它落在会话桶，单层时落在主人的长期桶，两种都不被页面列出），于是这一行的**日期列常年为空**，正文却照常随每次晋升被覆盖。这一枚不是丢记忆：开关之前落下的流水仍留在长期桶里、仍在页面上列着日期，新条目则正躺在会话层等并。行上因此带着这台 agent 有没有开会话层，开了就把这句话写在「每日记录」那一格的悬停里——记录停在哪个日期，或长期层还没有并入任何记录。这一枚读的是 agent 行上的 `session_memory_enabled`：记忆桶里只有名字，开关在行上，所以按名字与**本次列表所用的同一个租户**去取（名字只在租户内唯一，不带租户会拿另一台工作空间的开关解释这一份记忆）。取不到行（改名或删除之后留在桶里的那份记忆）答案是"不知道"，不是"没开"：接口把那一整个键丢掉，页面于是什么都不说，因为"这台从来没有第二层"是这一页没有依据的一句断言。这一枚只在长期清单上多做几次按名字的点查，与"每一行读一次整理稿"同量级，不新增一次存储读。
- 会话层不单列，但它在页面上有一个数字：那一行另带一个「待并入」计数，数的是这台 agent 底下**还有内容没被并走的会话有几个**，不是对象个数——一次会话的草稿连同它当天的流水是一次晋升，不是两次，按对象数会把同一次对话显示成两次落后。哪些对象算"还有内容"沿用晋升自己那条判据：会话的 `root/MEMORY.md` 草稿，与流水段的**直接子**日期件。因此 11.7 那枚进度对象与 11.5 末段那个归档件都不计数——并成功之后它们照样留在桶里，把它们算进去会让一台每一层都已并走的 agent 永远显示待并入，而那是这一枚唯一不能给的答案。这一枚从列表那一次列举的同一批对象名里数出来，不新增接口也不新增存储读，给数量不给正文，所以第一条那句"页面只列长期层"照旧成立。清单的分组落在减法之前：一台**只写过会话层**的 agent 也占一行（正文空、日期空、待并入 N），否则它在页面上看起来像"这台没有记忆"。
- 删除那一行与页顶的警告把这台的**两层**都点出名字（整理稿、全部每日记录、尚未并入的会话记忆），因为带走它们的正是 11.5 第二条那条按前缀的清扫：它按原始对象名列举，本来就够不着"只清长期层"这一档，文案不能再少报它带走了什么。
- 客户端这一侧只有网页一个消费者：聊天页与 iOS 都没有记忆界面，记忆永远不以气泡形式出现，第 1 节第 5 条那条不变量在分层之后对两层同时成立。
- 会话层的正文对任何客户端都取不到，这一格由读侧的形状决定而不是由某一屏的形状决定：三条路由里没有任何一条按会话寻址，详情那一条先做减法（本节第一条）。要给会话层正文，先得在服务端加两条按会话的读路由（按会话列出、读某一个会话），键布局已在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:162` 给出；那样的屏上显示的是**待并的进度**而不是存档，因为会话层的正文随每次晋升被清掉（11.4 第三条），而团队会员的会话层按根会话分档（11.4 末条），一旦按会话列出就会成排出现。这两条正是 P14 里"更重的形态"所指的内容。

### 11.7 整理进度：跟着本次挂载的桶走

- 机制里有一个"上一次整理到什么时候"的进度标记，上游把它存在共享存储的**一个固定位置**——`["memory","consolidation"]` 这个命名空间下的 `watermark`，不带租户、不带用户、不带 agent。
- 为什么这一格必须搬走：整个部署上所有用户、所有 agent 读写同一个进度，甲整理一次就把进度推到"现在"，乙此前没并进去的流水因此永远低于它、被静默跳过。表现是"乙的记忆隔一阵就不长了，而日志里一个错都没有"。这条与分层无关，单层部署同样成立（第 2 节事实五记过同一个标记写不进去的故障，记的是它的可用性，这里说的是它的归属）。
- 分层之后更需要它：会话层和长期层各要一份自己的进度，共用一个会让一次会话层的整写把主人的跨会话流水标成已整理。
- 落地形状：第 6 节协调槽那一条已经立过"只把某一个位置改成本进程应答、其余一律透传"的先例，这里照同一个形状再用一次——`harness/memory/BucketScopedWatermarkStore.kt` 只改写上游那一个命名空间，其余键一律透传，管线、提示与文件布局都不动。上游那两个常量是私有的，本仓重述了一份，并由一条用例把上游字段读回来逐字对账：改了上游那一头而本仓没跟上，会立刻红，而不是静默把所有桶退回同一个地址。
- 落点定在**本次挂载那个桶的按天流水段里面**，而不是给它另开一个只属于进度的小空间。理由是回收：删这台 agent 与删除用户两条既有途径都是按前缀列举再逐个删，进度放在那条前缀之内就会被一起带走；放在自己那一段里，没有任何一条链路会去清它，于是它比自己要数的流水活得更久，下一次拿到同一个桶键的用户会被它静默压住整理。双层时"本次挂载的桶"是这个会话的桶，单层时是主人的长期桶——`HarnessAgentLauncher.kt:670` 那一处是唯一的决定点，路由、进度与晋升读写的因此始终是同一个桶。
- 这个落点让记忆页多看见一个对象，代价是页面必须不认它：页面只列名字能读成日期的条目，进度那条读不成日期，因此清单与详情都不出现它；它与两层一样，落在删除的作用域里。
- 上游那个固定地址没有任何读者：这一层往它写的是零，两条整体删除也够不着它——`memory/consolidation` 那一段前缀不在任何主人前缀之内，而 11.5 那四条回收全都按主人前缀列举。一份桶里若真落着一枚这样的对象，把它取走是部署侧的一次手工动作（按对象名删那一个），不是这一层要补的机制。
- 晋升不参与这道清理：清会话层时这个进度留着（11.4 清理那条末句），否则下一次整写会重读这一层已经没有的条目。
- 验收的两头：地址改写本身打在存储包装上（`BucketScopedWatermarkStoreTest` 7 条：上游地址对账、写落进哪个桶、读回同一个桶、两个 owner 各一份、两层不共用一份、CAS 只在本桶内争、其余命名空间原样透传），装配侧由 `HarnessAgentLauncherMemoryTest` 的 `the consolidation progress follows whichever bucket the routes point at` 等三条盯"进度跟的是挂上去的那个桶"；真 MinIO 上另有一条断言它确实坐在两个响应所依据的那段前缀之下、且两个响应里都搜不到它的名字与时间戳。两个 owner 先后整理这一条没有另外在真存储上跑一次：它断言的是地址改写，与服务器无关，桩不会放过它。第 9 节第 2 条那一栏仍未验的是别家 S3 兼容档，不是这一格。

### 11.8 这一层不做什么

不提供只按会话、彻底不留长期记忆的那一档（与第 1 节第 1 条直接矛盾）；不提供会话层的手工编辑入口；不给记忆管理页配"立即沉淀"那一个手动赶晋升的动作（11.9 的 P15，代价与替代写在 11.5 末段）；不做跨 agent 的会话层合并；不给会话层配独立模型或独立容量预算；不在 iOS 与聊天页做任何事；不新造清理链路（除 11.4 的晋升即清之外，回收只复用删这台 agent、删除用户、到期归档这三条既有途径）；不换掉日报归档机制。

### 11.9 拍板结果

| 拍板项 | 取值 |
|---|---|
| P8 两层关系 | 拍板：并存，且会话层会晋升进长期层。次选"二选一、每台只走一种粒度"被否，它让选了会话粒度的那台从此没有跨会话记忆 |
| P9 晋升触发 | 拍板：节流定时。理由是 harnax 没有可靠的会话结束信号，见 11.4 第一条 |
| P10 适用范围 | 拍板：向导里逐台 agent 选，缺省仍是"只有长期层"，现行为不变 |
| P11 读侧 | 拍板：两层都注入模型，新会话刚开口那几句不必等节流窗口 |
| P12 删会话 | 拍板：不连带清，维持第 1 节第 6 条那一行；代价与缓解写在 11.5 末段 |
| P13 晋升后的回收 | 拍板：晋升即清会话层，且只在并成功之后清 |
| P14 页面呈现 | 控制方按推荐项定，未点验：长期层单列，会话层不单列而只在 agent 那一行给一个「待并入」计数——数的是还有内容没被并走的**会话**个数，判据沿用晋升自己那条读取规则（11.6 第三条）；同一行还带着这台有没有开会话层，用来解释日期列为什么停在旧日期（11.6 第二条）。要更重的形态（按会话列出、按会话删除）就得加一层视图与一条按会话删除的接口 |
| P15 手动"立即沉淀" | 控制方按推荐项定，未点验：**不提供这一枚动作**。P14 那个计数已经回答"有多少没沉淀"，而"立刻了账"要的是在没有对话的情况下装配起一次晋升——晋升用的节流槽、存储版本比对的探测结论与它要问的模型都在运行侧，管理面手里一份都没有。三个候选里：管理面自建一套晋升等于把模型配置、凭据解密、桶装配三处各复制一份进管理面；走"admin 调 agent-service 新内部接口按会话触发"要先回答"页面上选哪个会话"，那正是 P14 否掉的形态。代价与既有回收途径写在 11.5 末段 |
| P16 部署侧缺省的形状 | 拍板：**admin 建默认值**——一枚只决定插入那一刻初值的配置项，存量行不动、向导那一枚仍是最终判断、运行时不参与OR。两个次选都被否：「运行时强制开」要让这枚与列做 OR，而列是 NOT NULL DEFAULT 0，库里"没答过"与"明确答了关"是同一个值，于是它会盖掉某台 agent 故意关掉的判断；「列改可空三态」最贴合"整份部署翻成双层"那句话，代价最大——DDL 变更＋wire 上 `Int?` 的三态语义＋向导控件要加"跟随部署"这一档＋iOS/客户端契约跟着改。这一枚的落点与限制写在 11.3 末段 |

### 11.10 判定条款

第 1 节那六条对长期层继续原样成立，这一层另加八条，每条都可判定；每条由谁断言、断到哪一档，逐条对账见 11.12。

1. **没点开双层的 agent 行为逐字不变。** 同一套用例在"开加关"那一档跑，断言挂载的桶仍停在主人的那一段（agent 之后直接是 `root` 与 `memory`，中间没有 `sessions`）、模型输入里不多出一块、也不装晋升这一步。工具清单不在这一条里：那四枚由「长期记忆」那一枚决定（第 4 节），这一枚从不参与那个判断，所以矩阵第一行才写"会话记忆那一枚此时不参与判断"。这是这一层的回归锚。
2. **双层下的可见范围。** 全新生成的同用户同 agent 会话，首轮就拿到长期层；同一个会话的下一轮，同时拿到长期层与自己那份会话层，且两段在输入里分得开——分得开靠的就是长期层那一对标签。长期层那一段过了预算时，进来的是它的头部加一句写明被截断，不是整篇。
3. **晋升失败一个对象都不少。** 版本冲突、模型抛错、模型不回答直到超时、合并结果缩水、写回被拒、读被拒六档下分别断言会话层的对象数量与正文逐字未变，只有并成功那一档才变空；每一次尝试都落在一个说得清的结果档位上，存储故障不许被读成"这一层本来就没东西"。
4. **两条整体删除都覆盖两层。** 删这台 agent、删这个用户之后，前缀下的键快照逐字为空，包括会话段下面那些还没晋升的。作用域按 11.5 那一条取前缀下列回的原始对象名，所以连键解析认不出来的那类（agent 自己带斜杠）也在断言范围内。
5. **两个 owner 各整理各的。** 真存储上两个用户先后整理，断言第二位的流水不会因为第一位的进度而被跳过，这一条同时把 11.7 那个既有缺陷钉死；同一条链路上还断言进度跟的是本次挂载的那个桶（双层落会话桶、单层落长期桶），且两个响应里都搜不到它的名字与时间戳。
6. **清理不许比"读—删"这一对动作更宽。** 在这次读到的那份稿子与它被删掉之间往会话层再写一条（追加流水或整篇重写那份稿子），断言新来的那条还在原位，而这次并掉的其余部分照常清空。删除本身要回读确认：存储报一次失败的删除而不抛错，确认不了的算没删、留给下一个窗口。
7. **存储不认版本比对时这一台没有晋升。** 装一台对任何版本号都答"写进去了"的存储，断言它不装晋升这一步并留下怎么说得通的日志；被撤走的只有晋升那一步，长期层的只读注入照常挂上。探针自己抛错的那一档不算，断言晋升照装——同一份存储的故障由并的那条路自己接。这一条的结论按进程记十分钟，过期后重问，断言修好存储不需要重启进程。
8. **空层不占窗口。** 一个比节流窗口还短的会话，第一次读到会话层是空的（抽取还在模型那一头），断言它没有把窗口花掉；读不出来的那一档同样不占。先占槽再读的话，这类会话会从此一次都不晋升。

### 11.11 落地形状：按模块的文件清单

| 模块 | 文件 | 这一处负责什么 |
|---|---|---|
| harnax-harness-core | `harness/memory/MemoryDomain.kt`（新） | 一台 agent 的桶身份只算一次：两层的各自路由、整理稿与流水的命名空间、长期层整理稿的只读读口。路由、进度与晋升读写同一个桶由这里保证（11.7） |
| | `harness/memory/MemoryPromoter.kt`（新） | 一次晋升尝试的全部判定：读会话层、取长期层、问模型、缩水判据、带版本写回、逐对象清理与回读确认、五个结果档位（11.4） |
| | `harness/memory/MemoryPromotionMiddleware.kt`（新） | 触发：每轮回答之后，在阻塞调度器上先看这一层有没有内容、再抢节流槽（11.4 第一条与 11.10 第八条） |
| | `harness/memory/LongTermMemoryContextMiddleware.kt`（新） | 长期层的只读注入：`<long_term_memory>` 那对标签、按整理稿预算折出的 16000 字符上限、块尾写明被截断、读失败时原样返回 prompt（11.1） |
| | `harness/memory/BucketScopedWatermarkStore.kt`（新） | 只改写上游那一个进度地址，其余键逐字节透传；重述上游两个私有常量并留一条用例把它们读回来对账（11.7） |
| | `harness/memory/MemoryFilesystemRoutes.kt` | 会话桶键：`sessions/<sid>` 嵌在 agent 段之内，新增 `sessionRoutes`/`sessionNamespace`/`bucketNamespace`；`SESSIONS_SEGMENT` 与 `MEMORY_SEGMENT` 改为公开，给 admin 的解码器与进度包装用 |
| | `harness/memory/MemoryConfigFactory.kt` | 那两条禁止条款从私有改为抽取与晋升共用；整理稿预算公开，注入侧按它算字符上限；`consolidationMinGap` 改为从配置取而不是硬编码 |
| | `harness/HarnessAgentLauncher.kt` | 装配：`memoryDomainOf(...)` `:662` → `sessionLayer` `:666` → `mountedBucket` `:670` 三段判定，路由挂载 `:721-722`，双层时另挂注入与晋升 `:723-756`（拒绝晋升时那条 warn 在 `:747-754`），`memoryStore()` `:1229-1233`、`casSupport()` `:1108-1119` 与 `promoterIsSafe()` `:1135`、结论 TTL `:1246` |
| | `agent/AgentSpec.kt` | `sessionMemoryEnabled`，缺省 `false`；`memoryEnabled` 为假时它不参与任何判断 |
| | `harness/config/HarnessConfig.kt`＋`harness/spring/HarnessAutoConfiguration.kt` | `harness.memory.consolidation-min-gap`（缺省 30 分钟）：整写节流与晋升共用同一个窗口 |
| harnax-agent-service | `agent/service/runner/AgentSpecResolver.kt` | 下发时这一枚按 `== 1` 判：一个这个运行时没见过的值留在单层那一侧（与 `memoryEnabled != 0` 相反） |
| | `src/main/resources/application.yml` | 上面那枚窗口的环境变量与注释 |
| harnax-entity | `entity/Agent.kt`＋`resources/mapper/AgentMapper.xml`＋`src/test/resources/schema-test.sql` | `session_memory_enabled tinyint(1) NOT NULL DEFAULT 0`，进 resultMap、insert、update 与测试基线那份逐字副本 |
| | `entity/dto/AgentSpecInfoResponse.kt` | 内部接口下发这一枚的字段，写明"没答过就是今天的一层" |
| harnax-admin | `resources/db/migration/V1__init_schema.sql` | 同一列进 Flyway 基线：本仓的口径是变更折进基线、清库重建，不写前向增量 |
| | `dto/AgentCreateRequest.kt`＋`AgentUpdateRequest.kt`＋`AgentResponse.kt` | 可空 `Int?`：创建时没带这一项才落到部署初值，更新时没带就不动存下的答案 |
| | `service/impl/AgentServiceImpl.kt` | `harnax.agent.session-memory-default` 的唯一读取点，只在插入那一刻听它（11.3 末段） |
| | `controller/InternalApiController.kt` | 四张 agent 行的出口各带这一枚（`:444`、`:470`、`:509`、`:608`）、主管那一份显式给 `0`（`:646`）、`buildAgentSpecResponse` 的必填参数与写入（`:688`、`:948`） |
| | `util/MemoryObjectKeys.kt` | 键解码认得会话段（`Location.sessionId`），并给出会话层整理稿的精确键；仍解不出 agent 段的照旧返回 null。另给出「这一枚对象算不算还没并走的内容」那枚谓词：长期段的整理稿，加流水段的**直接子**日期件——进度件与归档件读不成日期因此不计数，判据与晋升自己取材料时同一条（11.6 第三条） |
| | `service/impl/MemoryStoreGateway.kt` | 页面读侧先按 agent 分组、再在每一组内部按会话段做减法，因此只写过会话层的那台也占一行，分组那一次顺手数出这一行的「待并入」会话个数（同一次列举的同一批对象名，不新增存储读）；详情只保留长期层；两条整体删除按前缀列回的原始对象名逐个删，不经解码器（11.5、11.6） |
| | `dto/MemoryAgentResponse.kt` | 一行上的两枚新字段：`pendingSessionLayers`（还有几个会话没并走）与 `sessionMemory`（这台有没有开会话层，可空——取不到就是整个键不出现，页面于是不解释） |
| | `service/impl/MemoryServiceImpl.kt` | 长期清单之后按名字与**本次列表所用的同一个租户**点查 agent 行，取 `session_memory_enabled` 填上面那一枚；取不到行是"不知道"而不是"没开"，点查失败只让这一枚变成"不知道"、记忆照常列出 |
| harnax-webui | `pages/agent/components/CreateForm.tsx`＋`UpdateForm.tsx`＋`typings.d.ts`＋`locales/zh-CN/pages.ts`＋`locales/en-US/pages.ts` | 第二枚开关的控件、两处初值与提示文案，键 `pages.agent.sessionMemoryEnabled` / `…Hint` |
| | `pages/memory/index.tsx`＋`typings.d.ts`＋`locales/zh-CN/pages.ts`＋`locales/en-US/pages.ts` | 「待并入」那一列（`pendingSessionLayers`，零就按次要文本显示）与它的悬停、日期列上解释记录停在哪儿的悬停（只在 `sessionMemory` 为真时出现，缺键就当没说）、删除确认与页顶警告点名两层；键 `pages.memory.pendingCount` / `…pendingTooltip` / `…sessionLayerStopped` / `…sessionLayerNoNotes` |
| harnax-deploy | `.env.example`＋`docker-compose.yml` | `HARNAX_MEMORY_CONSOLIDATION_MIN_GAP`（agent-service 侧）与 `HARNAX_AGENT_SESSION_MEMORY_DEFAULT`（admin 侧）两枚环境变量与注释 |
| docs | `docs/deploy-harnax-admin.md`＋`docs/deploy-harnax-agent-service.md`＋`docs/deploy-harnax-harness-core.md` | 两枚新增配置项在部署文档里的落点 |

列与键的名字、缺省值与注释在以上文件里各只有一处定义：`session_memory_enabled` 的缺省在 DDL，`sessionMemoryEnabled` 的缺省在 `AgentSpec`，页面文案的缺省在那两枚 locale 键。运行时读的是下发的那一列，不再读任何环境变量——`HARNAX_AGENT_SESSION_MEMORY_DEFAULT` 只管插入那一刻。

### 11.12 逐条对账：判定条款由谁断言

用例名逐字取自测试源码，条数是本轮实跑的数：`harnax-harness-core` 整模块 601 项（其中一项要真模型 key 才跑，没给就记一次跳过）、`harnax-admin` 整模块 2422 项、真 MinIO 上的端到端 `MemoryOwnerBucketMinioIT` 12 项，三处都是零失败。表里那一格"打在真 `mysql:8.0` 上"的 `AgentMapperTest` 随 admin 这一次 reactor 一起跑（`-am` 连带 harnax-entity 进 reactor）。

| 条 | 断言落在哪 | 状态 |
|---|---|---|
| 1 单层逐字不变 | `HarnessAgentLauncherMemoryTest`（23 条）的 `an agent that did not ask for two layers keeps today's keys and today's injected blocks` 断言挂载的桶仍停在主人的那一段（agent 之后直接是 `root` 与 `memory`，中间没有 `sessions`）、模型输入里不多出一块、也不装晋升这一步。工具清单不在这一条里：那四枚由「长期记忆」那一枚决定（第 4 节），这一枚从不参与那个判断。`the session switch does not open memory on its own` 断言矩阵第一行——长期层关掉时会话那一枚不参与判断 | 已验 |
| 2 双层可见范围 | `LongTermMemoryContextMiddlewareTest`（9 条）：`a brand-new conversation still reads its owner's long-term layer`、`the injected block is delimited and names which layer it is`、`one conversation reads its owner's layer beside its own draft instead of as one text`、`a long-term layer over its budget arrives cut rather than whole`，另四条管"没有整理稿就不加块"（`an owner with nothing curated gets the prompt untouched` 与 `a blank curated layer is no block either`）、"存储拒答就原样返回 prompt"、"每次调用重读"，`the tenant segment off reads the bucket the routes write` 管租户段关掉那一档读的是同一桶 | 已验 |
| 3 失败一个对象不少 | `MemoryPromoterTest`（19 条）里六档各有断言：`a sibling merge that lands first costs this conversation nothing`（版本冲突）、`a model that fails leaves the only copy of the draft alone`、`a model that answers with nothing never overwrites the owner's memory`、`a model that never answers costs the merge and releases the thread`（超时）、`a store that refuses the versioned write leaves the layer intact` 与 `a store that cannot be read is not a conversation with no memory`（写被拒／读被拒，后者同时钉住"存储故障不许读成空层"）。缩水那一档两条：`a merge that shrinks a curated layer already inside its budget is refused`、`a curated layer that overran its budget is still not open to a collapse`，并有一条正向 `a curated layer that overran its budget is brought back by a halving` 证明超预算的层仍会被整理 | 已验 |
| 4 两条整体删除覆盖两层 | `MemoryStoreGatewayTest`（36 条）：`a conversation's own layer goes with the agent although the page hides it`、`a key no decoder can place still goes with its owner`、`every agent of the user goes and the prefix never leaves that user`、`a delete asks for the caller's prefix and every key it removes stays inside it`。真 MinIO 上由 `MemoryOwnerBucketMinioIT` 的 `a delete reclaims both the long-term layer and the conversations under it` 走一遍 HTTP。键形状两头各钉一次：运行侧 `MemorySessionBucketKeyTest`（7 条）与 admin 侧 `MemorySessionLayerDecodeTest`（10 条），跨模块对账 `MemoryObjectKeyCrossCheckTest`（10 条）；会话那一段的形状钉在真 MinIO 上，`MemorySessionKeyShapeTest`（2 条）的 `a session id that nests deeper still writes inside its own agent` 断言带斜杠的 id 只在那台 agent 的前缀之下多出一层、两层各落一个对象，`a session id that walks up never leaves memory outside its own agent` 断言五种往上挪的 id 一个对象也不落在前缀之外，且同一条里先写一枚正常会话作对照——空清单不算通过 | 已验 |
| 5 两个 owner 各整理各的 | `BucketScopedWatermarkStoreTest`（7 条）逐项：上游地址对账、写落进哪个桶、读回同一个桶、两个 owner 各一份、两层不共用一份、CAS 只在本桶内争、其余命名空间透传。装配侧 `HarnessAgentLauncherMemoryTest` 的 `the consolidation progress follows whichever bucket the routes point at` 等三条盯"进度跟的是挂上去的那个桶"。页面不认它由 `MemoryOwnerBucketMinioIT` 的 `the bookkeeping object a bucket keeps beside its ledgers is never shown as memory` 断言 | 已验（桩上）。真存储上跑的是"进度落在哪一段前缀、页面两个响应里搜不到它"，两个 owner 先后整理没有另在真 MinIO 上重跑——那条断言的是地址改写，与服务器无关（11.7 末条） |
| 6 清理不许更宽 | `MemoryPromoterTest` 四条：`an entry the flush wrote while this merge ran is not cleared with the rest`、`a draft that was rewritten while this merge ran is not cleared with the rest`、`an object holding other bytes is not cleared because its number did not move`（比正文不比号）、`a delete the store swallows is not counted as cleared`（删后回读确认） | 已验 |
| 7 无 CAS 不晋升 | `HarnessAgentLauncherMemoryTest` 的 `a store that ignores its version precondition is refused the merge` 与 `a store that compares versions keeps the merge and one that could not be asked still tries it`。探测本身 `StoreCasProbeTest`（7 条）、退路 `ProcessLocalCoordinationStoreTest`（5 条）、结论按进程与十分钟 TTL `HarnessAgentLauncherCoordinationTest`（5 条，含 `a verdict past its own life is asked again`） | 已验 |
| 8 空层不占窗口 | `MemoryPromotionMiddlewareTest`（7 条）：`a first turn does not spend the window on a layer the extraction has not filled yet`、`a conversation with nothing to promote does not ask the gate`、`a layer that cannot be read leaves the window for whoever can`，另四条管"回答不等合并""窗口内不并""两个会话各走各的钟""闸答不出也不欠这一轮" | 已验 |
| 四段接缝 | 列与行的往返由 `AgentMapperTest` 打在真 `mysql:8.0` 上（insert 与 updateById 都带上这一列，两个方向都写）；admin 写侧 `AgentServiceImplTest` 三条（`createAgent should seed the session layer from the deployment default`、`createAgent should let the request overrule the deployment default`、`createAgent should honour an agent that asked for a session layer`）；下发 `InternalApiControllerTest` 的 `getAgentSpec delivers the session layer answer written on the agent row`；解析 `AgentSpecResolverTest` 三条（`the session layer arrives on only for a delivery that says so`、`a delivery that never names the session layer leaves the agent on one layer`、`a session value this runtime never expected stays on the single-layer side`）；装配 11.10 第一条那一格 | 已验 |
| 页面只列长期层 | `MemoryOwnerBucketMinioIT` 的 `the listing keeps a conversation's unpublished memory off the page` 与 `the detail answers the long-term layer alone even though a session bucket sits under it`；单元测试侧 `MemoryStoreGatewayTest` 的 `an agent is reported with its curated text its storage time and its ledger dates` 等 | 已验 |
| 页面读侧两枚（11.6 第二、三条） | 「待并入」计数按会话去重由 `MemoryStoreGatewayTest` 的 `the pending count is the conversations whose own layer still holds memory`（四枚对象落在三个会话，答 3：一次对话的草稿连同它当天的流水是一次晋升不是两次）与 `each agent's pending count covers only its own conversations`（只有会话对象的那台也占一行，且计数不串到邻台）钉住；进度件与归档件不计数由同类的 `the passes state object and an archived day are not counted as unmerged memory` 钉住，判据本身在解码器那头由 `MemorySessionLayerDecodeTest` 的 `a conversation draft and its dated ledgers are unmerged memory` 与 `the passes state object and an archived day are not unmerged memory` 各钉一档。开关那一枚由 `MemoryServiceImplTest`（16 条）四条钉：`an agent with the conversation layer on is reported as such`、`a single-layer agent says off and an agent with no row says nothing`（"明确答了关"与"取不到行"不是同一个答案）、`an agent lookup that fails still lists the memory`（这一枚失灵只损失那句解释，记忆照常列出）、`the layer lookup is names by the tenant the memory was read from`（租户取自本次列表所用那一个，不拿别人工作空间的开关解释这一份记忆）。真 MinIO 上 `MemoryOwnerBucketMinioIT` 的 `the listing keeps a conversation's unpublished memory off the page` 一条同时断言这一行的 `pendingSessionLayers` 是 1、且 `sessionMemory` 这个键整个不出现（这一枚种子写的是记忆不是 agent 行） | 已验 |

未验的三格与第 9 节同源，不重复展开：一、真模型下的晋升质量——并出来的那一份 `MEMORY.md` 召回够不够、有没有把这一轮才成立的东西当成长期事实留下，判据同第 9 节第 4 条，需要 provider key；二、别家 S3 兼容档在这台存储上会给出哪个答案（第 9 节第 2 条同一格）；三、翻开关到下一次装配之间的窗口仍是机制说明而非实测（第 9 节第 8 条）。另有一格本轮只做到构建：智能体向导那两屏与记忆页的「待并入」那一列／两处悬停都没有走浏览器验收（前端只过了 `max build` 与 lint），见第 9 节末尾那条口径。
