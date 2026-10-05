# Harnax memory：跨会话长期记忆方案

## 0. 口径与取证基线

版本与存储事实（决定哪些结论成立）：

| 项 | 取值 | 依据 |
|---|---|---|
| 本文档的版本基线 | `agentscope-harness` / `agentscope-core` **2.0.4** | 上游检出 `~/code/opensource/agentscope-java/`，分支 `release/2.0.4`，HEAD `3c1c29c0`；其 `pom.xml:30` `<revision>2.0.4-SNAPSHOT</revision>` |
| 2.0.4 事实来源 | 上述检出的 `agentscope-harness/src/main/java/` 与 `agentscope-core/src/main/java/`，本篇全部上游锚点都取这里 | 与基线同一份代码，不另解 sources jar |
| harnax 当前依赖 | **2.0.4**，与本文档基线同版本 | 仓库根 `pom.xml:39`（`<agent-scope.version>2.0.4</agent-scope.version>`） |
| 记忆的字节落在哪 | MinIO，bucket `harnax-store`、全局前缀 `store/` | `harnax-agent/harnax-agent-service/src/main/resources/application.yml:87,92`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/MinioConfig.kt:25,29`；对象键形状见同目录 `minio/MinioBaseStore.kt:21` 的类注释样例 `store/agents/myAgent/sessions/sess-123/MEMORY.md` |
| 这个 store 由谁装配 | 一个 `MinioBaseStore` 实例（`HarnessAgentLauncher.kt:639`）被两条分支共用 —— 沙箱分支 `:668`（记忆域开着时先过 `coordinationStore()`）、非沙箱分支 `:677-681` | 同文件；两档下的键形状必须一致，见第 7 节 |
| harnax 主数据源 | `harnax_admin` 库 | `.../application.yml:10` |
| harnax 会话数据源 | `agentscope` 库（`SESSION_JDBC_URL`），`agent_state` 表在此 | 同文件 `:32`、`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/session/SessionConfig.kt:15` |

锚点约定：上游文件一律省略前缀 `agentscope-harness/src/main/java/io/agentscope/harness/agent/`（`HarnessAgent.java`、`middleware/`、`memory/`、`memory/compaction/`、`filesystem/`、`coordination/`、`tool/`、`workspace/` 全在这一棵树下），core 侧三个文件省略前缀 `agentscope-core/src/main/java/io/agentscope/core/`。harnax 侧 `HarnessAgentLauncher.kt` / `HarnessAgentWrapper.kt` / `HarnessAgentBuilder.kt` 省略前缀 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/`，同目录的 `HarnessConfig.kt` 与 `SandboxConfig.kt` 在 `config/`、`MinioBaseStore.kt` 在 `minio/`、`HarnessAutoConfiguration.kt` 在 `spring/`，`SessionConfig.kt` 另属 `.../agnetix/harnax/agent/session/`；`DefaultAgentRunner.kt` 省略 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/`，同模块的 `AgentController.kt` 省略 `.../agent/service/controller/`；admin 侧 `SysUserController.kt` 与 `TeamArtifactController.kt` 省略 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/`，`SysUserServiceImpl.kt` 省略 `.../admin/service/impl/`，`AdminMinioConfig.kt` 省略 `.../admin/config/`。两个易踩点：`AgentController.kt` 在 agent-service 与 admin 各有一份，本篇提到的**一律指 agent-service 那一份**（`:104` 是它的 `GET /chat/history/{sessionId}`），只有第 7 节 REFRESH 那一行用的是 admin 那一份且把路径写全；`IsolationScope` 与 `MemoryConfig` 都是上游类型，不是 harnax 的配置类。本轮新增的引用里，`InternalApiController.kt` 同样省略 admin 那个 controller 前缀，webui 侧 `CreateForm.tsx`／`UpdateForm.tsx` 省略 `harnax-webui/src/pages/agent/components/`、两份 locale 省略 `harnax-webui/src/locales/`。凡"现在跑成什么样"的断言以 2.0.4 源码为准。

取证方式：方案定稿前是源码静态阅读 + 配置比对，未启动任何 harnax 服务；落地后第 8 节的断言跑在真 `MinioBaseStore`（Testcontainers 起 MinIO）与真装配出来的 agent 上。第 8 节第 11 条是唯一把服务起起来的一条：`HarnaxAdminApplication` 在随机端口上对着 Testcontainers 的 MySQL 与 MinIO 启动，判据取带 JWT 的 HTTP 响应；其余各条仍在同一 JVM 内的装配上跑，agent-service 本身始终没有启动。

阅读范围：本篇是已执行并合入 `kotlin-dev` 的方案。第 2 节与第 4 节里凡称"harnax 今天/当前"的句子，描述的是执行前的仓库形状；执行后的形状以第 6 节的改动清单与代码为准，第 9 节逐条标注哪些已测掉、哪些仍未验。

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
`RemoteFilesystemSpec` 把空 uid 归成 `anonymousUserId`，字面量缺省 `_default`（`:79`、`:344`）；`IsolationScope.toNamespaceFactory()`（喂给 `WorkspaceManager` 做本地路径的，`HarnessAgent.java:2425`）在 USER 档且 uid 为空时**回落到 sessionId**（`IsolationScope.java:104-115`）；`agent_state` 的用户桶归一是 `__anon__`（`ReActAgent.java:394-399`）。而 harnax 送进 `RuntimeContext` 的 userId 本来就是空串（`HarnessAgentWrapper.kt:301` 的 `userId ?: ""`）。三套名字若被当成"同一个匿名桶"，会出现"写进 `_default`、本地兜底读 `<sessionId>`、状态读 `__anon__`"的三分叉。

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
  - `harness/minio/StoreCasProbe.kt` 与 `harness/minio/ProcessLocalCoordinationStore.kt`：把上面那句"二选一，不许沉默"落成代码。装配第一次需要这把闸时，先在同一命名空间里建一个带 uuid 的一次性槽，走三步判据（版本不符必须拒、版本相同必须放、对象已存在时 `expectedVersion = 0` 的建槽必须拒），据此把"接口默认恒拒"与"网关收了 `If-Match` 却不生效"这两种相反的故障分开；结论按进程记忆，但**只记 store 真答过的**——连接没通时的"没答案"不是关于 store 的判定，记下来会让 MinIO 重启一次就把整副本永久钉在本进程协调上。判据不过就把 `["coordination", "periodic"]` 这一个命名空间改由 `ProcessLocalCoordinationStore` 就地应答（`ConcurrentHashMap.compute` 内完成比较与写，槽键按进程共享，形状对齐上游 `LocalPeriodicGate` 的静态表），其余键一律透传，所以记忆桶本身仍在共享 store 里；同时打一条 WARN 说明本次部署拿到的是哪把闸。非沙箱分支根本没有 distributed store，上游自带 `LocalPeriodicGate`，这条路径不探测。装配日志里"这把闸按副本共享"的措辞跟着探测结果走，三种状态（无闸／真共享／本进程各算各的）各说各的，不许把退路报成前者。
  - `HarnessAgentBuilder.kt` 加三个透传：`memory(MemoryConfig)`、`disableMemoryTools()`、`filesystemRoute(String, AbstractFilesystem)`，对应上游 `HarnessAgent.java:1893,2232,1862`。
  - `HarnessAgentLauncher.kt` 的记忆装配段：按第 3 节与第 4 节挂记忆路由、装 `MemoryConfig` 与三个开关。路由的归属取自装配入参 `userIdentifier.userId`，取不到就整域关掉并 warn（见第 3 节「匿名调用」行）。`HarnessAgentWrapper.kt` 的 `userId` 因此保持不填——它进 `RuntimeContext` 就成了 `agent_state` 的分桶键，而历史读取按空用户寻址（事实四）。
  - `harness/config/HarnessConfig.kt` 与 `spring/HarnessAutoConfiguration.kt`：新增 `harness.memory.*`（`enabled`、`model-id`、`flush-trigger`、`flush-min-gap`、`tools-enabled`、`tenant-scoped` 桶形状），代码缺省 `enabled = false`（`HarnessConfig.kt:59-60`），绑定形状照现有 `enableMemoryHooks`（`HarnessConfig.kt:30`、`HarnessAutoConfiguration.kt:87,284-293`）；这套部署的 true 由 compose 注入，见下面 harnax-deploy 那条。装配分支上的 `MinioBaseStore` 现在只有一份（`HarnessAgentLauncher.kt:639`），两条分支共用，补了 CAS 的那一份因此天然覆盖两档。
  - `agent/AgentSpec.kt`：`memoryEnabled` 一个 `val`，缺省 `true`，配 builder 同名方法。装配判据读它（`:646`），所以它是运行侧唯一认识"某个 agent 不要记忆"这件事的地方。
- **harnax-entity**：`agent` 表加 `memory_enabled tinyint(1) NOT NULL DEFAULT '1'`，写进 V1 基线并同步 `harnax-entity/src/test/resources/schema-test.sql` 那份逐字副本；`Agent.kt` 加同名 `var`（缺省 1）、`AgentMapper.xml` 的 resultMap／insert 列与值／updateById 三处各加一行（insert 少了它行就落在 DDL 缺省上，向导的答复会丢）；线上契约 `AgentSpecInfoResponse` 加 `memoryEnabled: Int = 1`。
- **harnax-admin（智能体向导的开关）**：`AgentCreateRequest`／`AgentUpdateRequest`／`AgentResponse` 三处 `Int? = null`（可空才能把"没答"与"答了 0"分开），`AgentServiceImpl` 创建取 `?: 1`、更新用 `?.let`、两条读路径（`fromEntity` 与分页用的 `convertToResponse`）都要带上这个字段——少一处，向导就在一屏上看得见开关、另一屏看不见。`InternalApiController.buildAgentSpecResponse` 的 `memoryEnabled` 是**必填参数**（`:678`）而不是缺省值：五个调用点里漏掉任何一个都会编译不过，而不是静默给每个 agent 下发"开"。四个读智能体行的调用点（`:443`、`:468`、`:506`、`:604`）把行上的值原样传下去，主管那一侧（`specForTeam`，`:639`）填 `1` 并写明拒绝主管的是运行侧 `!isLead`，不在这里重复一遍判断；builder 的写入在 `:937`。
- **harnax-agent-service**：`clearSession` 明确不清记忆（该动作只归会话数据）。`AgentSpecResolver` 把线上值映射进 `AgentSpec` 时判的是 `specInfo.memoryEnabled != 0`，与同一处 `skillSelfWrite == 1` 方向相反：授权型开关缺省为假、要显式给真，而这一域是这个 agent 的缺省状态、只有显式 0 才把它拿走，于是任何一个本运行时没预料到的取值都留在"有记忆"这一侧。报文里压根没有这个键时取的是线上契约的缺省 1（旧 admin 先发出去的那次滚动升级就属于这一类），两种拼写在这一格上等价，不等价的是越界取值。
- **harnax-admin**：记忆的读与删两个接口，作用域限于当前登录用户自己的桶，供页面核对与合规删除；删用户的记忆清理挂在既有删用户链上（`SysUserController.kt:119` 的 `DELETE /{id}`，实现 `SysUserServiceImpl.kt`），按桶前缀清 `root/MEMORY.md` 与 `memory/` 下全部对象，且**该用户所属的每一个租户各清一遍**（记忆桶按租户分键，只清当前租户会留下其余租户的对象）；admin 另读一颗与写入侧同名的开关 `harnax.memory.tenant-scoped`（`HARNAX_MEMORY_TENANT_SCOPED`，与 runtime 的 `harness.memory.tenant-scoped` 同值），关掉后桶键不含租户对，读侧必须跟着走，否则整桶记忆表现为空；store 拒绝一次读要答成故障（信封 503），不许伪装成"这个 owner 没有记忆"。admin 已有自己的 MinIO 客户端（`AdminMinioConfig.kt`）与按对象读写 MinIO 的先例（`TeamArtifactController.kt`），这两个接口直接照那一形状走，不经 agent-service；记忆小模型复用模型域。这两个接口的端到端覆盖是新增的 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/MemoryOwnerBucketMinioIT.kt`——本模块唯一同时起服务与接真 MinIO 的记忆用例，它灌进桶的对象正文用的是写入侧记录下来的那份字面量信封（第 8 节第 11 条、第 9 节第 9 条）。
- **harnax-session-router**：本轮不加新转发 —— 记忆不是实例本地状态，而是共享 store 里的对象，按第 3 节的桶键寻址；同一用户的两个会话即使绑在不同实例上，也读到同一份 `MEMORY.md`。抽取只在当次调用所在实例异步发生，不需要跨实例寻址。
- **harnax-deploy**：`docker-compose.yml` 给 agent-service 注入 `HARNESS_ENABLE_MEMORY_HOOKS` 与 `HARNAX_MEMORY_ENABLED` / `_MODEL_ID` / `_FLUSH_TRIGGER` / `_FLUSH_MIN_GAP` / `_TOOLS_ENABLED` / `_TENANT_SCOPED`，给 admin 注入 `HARNAX_MEMORY_TENANT_SCOPED`。两枚总开关在这一层取的是 `${VAR:-true}`（compose `:541`、`:542`），与 `application.yml` 里的 `false` 不一致——这是这一域唯一一处两侧故意不同值的缺省：compose 内的部署因此默认整域打开，而 `application.yml` 的 `false` 描述的是"在这份 compose 之外起的一次运行"，那里没有 MinIO，整域若也默认打开就让每个智能体都撞在装配期那道"没有 store"的拒绝上，连普通对话都起不来。其余各行仍与 `application.yml` 同值，所以不在 `.env` 里给值的部署，除这一域之外行为不变。这一层透传是这一域在集群部署里能不能打开的分界：compose 用的是逐服务的 `environment:` 而不是 `env_file`，没有对应行的变量在容器里根本不存在，`.env` 里单写 `HARNAX_MEMORY_ENABLED=true` 只会停在工作树。域的开法记在 `.env.example` 与 `docs/deploy-harnax-agent-service.md`、`docs/deploy-harnax-admin.md` 的环境表、`docs/deploy-harnax-harness-core.md` 的配置参考里：`HARNAX_MEMORY_ENABLED` 与 `HARNESS_ENABLE_MEMORY_HOOKS` 要一起给——现在两枚都由 compose 缺省成 `true`，要整域关掉得两个一起填 `false`，只给前者会在装配时拒掉整个智能体（连普通对话一起起不来）；单个智能体要不要记忆不在这里配，走向导那一枚「长期记忆」（第 4 节）。而 compose 钉成 `false` 的 `HARNESS_ENABLE_WORKSPACE_CONTEXT`（`:531`）不看这一域的请求——`enabled=true` 会强制打开它，那条 `SandboxConfigurationException` 噪声随记忆一起回来。
- **harnax-webui / harnax-ios**：向导的创建与编辑两屏各加一枚「长期记忆」——`CreateForm.tsx` 的 state 缺省 `true`，`UpdateForm.tsx` 按行上的值初始化为 `values?.memoryEnabled !== 0`，即"没答"与缺字段都算开、只有显式 `0` 才取消，与第 4 节那条反序判据一致；文案是新 key `pages.agent.memoryEnabled` 与 `pages.agent.memoryEnabledHint`（zh 588-589、en 589-590），hint 里点名"已经落盘的记忆文件不会删除，需要清理请到记忆管理页"，那条路径 `/agent/memory` 在本轮之前已存在；`typings.d.ts` 的 `AgentItem`／`AgentCreateRequest`／`AgentUpdateRequest` 三处各加 `memoryEnabled?: number`。记忆管理页与 iOS、聊天页本轮一律不动。

## 7. 边界、失败与限制

| 情形 | 行为 |
|---|---|
| `putIfVersion` 未实现就开钩子 | maintenance 一次都不跑且无 warn，`throttled` 档的抽取同样一次都不跑（事实五）；`always()` 档不经闸因此照常跑，consolidation 退回文件 watermark。修法是先补 store 再开节流；补不了的部署由装配期探测接住（第 6 节）——探测不过就把协调命名空间改成本进程应答并 warn，代价退回"每副本各整写一次"，而不是整条节流档静默不跑 |
| 沙箱分支与非沙箱分支键形状不同 | 单挂路由不在两条分支里各写一遍，而是在两条之外只挂一次（`HarnessAgentLauncher.kt:727-729`），所以同一 owner 换部署档读到的是同一个元组；分支之间不同的只有 filesystem spec 与闸所用的 base store——沙箱分支 `:647` 在要记忆时把 base store 换成 `coordinationStore(minioStore)`（`:668`），非沙箱分支 `:677` 不带闸 |
| lead agent | 装配判据 `memory.enabled && !isLead && agentSpec.memoryEnabled`（`:646`）里这一项与两条 filesystem 分支的 `!isLead` 是同一条理由（`:647`、`:677`）：lead 因此没有 filesystem，`WorkspaceManager` 的读写退回宿主盘且**不带命名空间**（`appendLocalFile`/`writeLocalFile` 落在 `workspace/<相对路径>`，`:851`、`:883`；读侧回落在 `:823`）。整域开着而装配的是主管时另留一条 info 点名原因（`:689-691`）。记忆域只属于成员与普通 agent，lead 显式排除 |
| team 成员 | 成员各按自己的 agentId 建桶；同一次委派里 lead 与成员的记忆不通 |
| 投递没有指名用户 | 装配期就把整个记忆域关掉：不挂桶路由、不装钩子、不交工具，并留一条 warn 说明原因。这类调用今天占多数，所以"记忆没生效"首先该查这次投递有没有带上 user id。不给它建一个匿名桶是刻意的 —— 匿名桶会把不同人的日报混进同一个对象键 |
| 单个智能体自己在向导上关掉 | 读写两头一起停：不挂桶路由、不装 `MemoryConfig`、不给那四枚工具，`<memory_context>` 也随之没有；`:761-767` 那三处开关（workspace context、memory tools、两个钩子）由同一个 `memoryEnabled` 一起翻。**已经落盘的对象一个都不动**——清空是记忆管理页与删用户那条链的活，翻这枚开关不产生删除。其余 agent 与整域照旧 |
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
| 2 | 已验（MinIO） | `MinioBaseStoreCasTest` 在真 MinIO 上断言 CAS 建槽、版本不符退 `false`、多写者只放一个、以及上游 `StoreBackedPeriodicGate` 按窗口只放行一次。其它 S3 兼容档给不给得出 CAS 由装配期当场定性：`StoreCasProbe` 对当次部署的 store 探一次，探不过就把协调命名空间换成本进程应答并 warn，两种相反的失效（默认退让与忽略版本前置）各自被断言。判据在 `StoreCasProbeTest`（7 条，含"探不通不算结论"）、`ProcessLocalCoordinationStoreTest`（5 条，含"协调槽不外泄给 delegate"）与 `HarnessAgentLauncherCoordinationTest`（4 条，含"结论按进程记一次"）；仍未实测的是别家 S3 档在真服务器上到底落在哪一种，以及回退档在多副本下各整写一次 `MEMORY.md` 的真实代价 |
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

不在本轮：记忆内容的页面编辑与语义检索、`extensions-mem`（Mem0、ReMe、百炼）与 `agentscope-service` 的托管记忆服务、`MEMORY.md` 的版本历史、日报归档的回读、跨 agent 的用户记忆合并、iOS 与聊天页的任何改动、以及把记忆用于跨会话检索。

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
