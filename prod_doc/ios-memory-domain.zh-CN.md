# Harnax iOS：记忆域的落点与契约

## 0. 口径与取证基线

- 这一份是**取定但尚未实现**的设计件：客户端今天没有任何记忆界面，本文写的是入口归属、跨端契约与验收判据，实现落地后按同一形状逐条回核。
- 取证基线是 `kotlin-dev` 的 `fefe78ed`。服务端读侧三条路由、装配侧会话层的判点、客户端现有分层与门禁都逐条回源码核过，锚点写完整仓库相对路径与行号。
- 四个名词固定：**长期层**＝主人跨会话的那份整理稿（`root/MEMORY.md`）与按天流水（`memory/<date>.md`）；**会话层**＝一次对话自己的那一层，嵌套在 agent 段之内，这台 agent 开了长期记忆就每场会话都有；**候选**＝运行侧把某一场会话的会话层合并出来的那份新整理稿全文，先记在 `harnax-admin/src/main/resources/db/migration/V7__memory_draft.sql` 建的那张表里，主人批准才写进长期层；**待并入**＝这台 agent 底下还有内容没被并走的**会话**个数。

## 1. 客户端要回答的是两块，不是一块

| 块 | 内容 | 服务端依据 |
|---|---|---|
| 自助读与清 | 列出主人名下有记忆的 agent，读这一台的整理稿与每日记录，删掉这一台 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:49`、`:64`、`:83` |
| 逐台开关 | 一枚：长期记忆，落在智能体向导上。会话层没有单独的开关——开了记忆的这台 agent 底下，每个会话都有自己的那一层 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:61`，可写字段在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:55` 与 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:46` |

这一枚开关在客户端不存在：行模型 `harnax-ios/Sources/HarnaxCore/Contract/AgentSummary.swift:27` 没有这一项，保存模型 `harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift:12` 也没有，向导上因此没有可点的控件。服务端这一列已经贯通到运行时，客户端补的是同一份行模型的一个字段，不是新接口。

## 2. 读侧给到哪一格（决定客户端能做什么）

- 清单把两层一起分组（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:94`），减法落在分组之后（`:95`），所以只写过会话层的 agent 也占一行，正文与日期取的都是长期层。
- 详情先做减法再取（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:122`），返回的整理稿与每日记录全部来自长期层。会话层的对象坐在同一个前缀之下，但没有任何一条路由按会话寻址它们；候选那一屏给的是合并出来的新整理稿全文，不是会话层的原文。
- 会话层能在客户端落地的只有一枚信号：`pendingSessionLayers`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:108` 由 `:379` 数出来，判据是 admin 侧这一条 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:356`，注释写明它刻意与合并那一步的取舍对齐——整理进度对象与已归档的日记都不算还欠一次并入——并按会话去重；报文里的位置是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryAgentResponse.kt:35`）。这一枚只给数量不给正文，且**不回答开关**：会话层常开，有没有这一层取决于这台 agent 的「长期记忆」，而那一名值只在智能体行上，不在这一份记忆报文里。
- 客户端因此**不给会话层正文**。要给，服务端先加两条读路由（按会话列出、读某一个会话），键布局现成（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/util/MemoryObjectKeys.kt:199`），并且要接受两件事：会话层的正文随那一条候选被批准而清掉（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:308`，唯一调用点在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:234`），屏上显示的因此是待批的进度而不是存档；会员也装这一层，判据同样是这台 agent 的「长期记忆」（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:1220`），而它的会话键由根会话推导（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt:56`），列出来因此成排，且每一排都要主人亲自批一次才走得掉。
- 删除那一条（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:153`）按前缀列举原始对象名（`:162`），一次带走两层。确认文案必须把整理稿、全部每日记录与尚未并入的会话记忆三项都点出来，少报任何一项都是少报了它带走了什么。

## 3. 入口取定：四候选与推荐

| 候选 | 落点 | 判断 |
|---|---|---|
| A 智能体 tab 第四段 | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8` 的段枚举加一项，段名在 `:15`，出口在 `:49` 那个分支 | **推荐**。网页把这一页挂在智能体菜单组内且明确不加管理员闸（`harnax-webui/config/routes.ts:59`），第四段是同一归属；分段条按选项等分宽度（`harnax-ios/Sources/HarnaxKit/Components/HXSegmented.swift:42`），上下文 tab 已排到五段，第四段没有宽度问题 |
| B 「我的」的管理组 | `harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:13` 加一个 case | 否。那一组是管理域，非管理员少一行（`harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:19`，注册与出口在 `harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79`、`:106`）；记忆是本人数据，挂进去要么被那道闸误伤，要么破坏这一组的含义 |
| C 上下文 tab 第六段 | `harnax-ios/Sources/HarnaxFeatures/Context/ContextDomain.swift:5` 加一个 case | 否。那五段是 agent 可绑定的资源，`harnax-ios/Sources/HarnaxFeatures/Context/ContextView.swift:35` 的分支即按那个枚举展开；记忆不是可绑定资源 |
| D 智能体行内下钻 | 行的下钻出口在 `harnax-ios/Sources/HarnaxFeatures/Agents/AgentListView.swift:51` 那一段 | 否。逐台翻达不到"看全并清干净"，而这一屏存在的理由正是那一条 |

推荐形态的导航：智能体 tab 第四段 → 主人名下有记忆的 agent 清单（一行一台，正文取整理稿，日期计数与「待并入」计数并列）→ 单台详情（整理稿全文与按天流水）→ 行内删除，删除前给确认对话框并点全三层内容。

## 4. 跨端契约要点

1. 那一名路径参数叫 `agentId`，取的是**运行时用的 agent 名字**，不是数据库 id（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:70`、`:89`）。行模型里的 `name` 才是键，拿 `id` 去寻址读回来是"没有这台"。
2. 业务失败是 200 响应带信封里的错误码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:58`、`:77`），详情在主人没有这一台记忆时给 `404` 码（`:73`）。「还没有记忆」是空态不是错误态，屏上按空态渲染。
3. 清单返回的是数组而不是分页体（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:54`），客户端那套分页状态机在这一条路由上没有作用域。
4. 主人身份不进参数：租户与用户都由会话凭据解析，共享传输注入的那一个租户头是唯一杠杆，客户端不拼任何主人前缀，也不许把 agent 名字之外的东西放进路径。
5. 这一枚开关是可空三态：行上读到 `0`/`1`，保存时被碰过才写键、没碰过整个键缺席，照 `skillSelfWrite` 的既有做法（`harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormViewModel.swift:183` 存开关与"被碰过"两个状态，写回在 `:1089`，回填在 `:1131`）。把读到的值原样写回会是一次操作员没有要求的写入。
6. 主管那一档不给这一枚：主管背后没有智能体行，服务端因此给它下发固定的 `1`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:666`），真正把它挡在记忆域外的是运行侧自己的 `!isLead`；团队保存模型 `harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift:168` 里没有这个字段，团队向导因此不出现这枚控件。

## 5. 落点文件清单

| 动作 | 文件 |
|---|---|
| 新建 | `harnax-ios/Sources/HarnaxCore/Contract/MemorySummary.swift`：清单行、单台详情、删除结果三份载荷 |
| 新建 | `harnax-ios/Sources/HarnaxCore/Contract/MemoryCataloging.swift`：这一域的读清 facade 协议，形状照 `harnax-ios/Sources/HarnaxCore/Contract/TokenStatsCataloging.swift:19` |
| 新建 | `harnax-ios/Sources/HarnaxAPI/MemoryEndpoint.swift`：三条路由的路径与参数，照 `harnax-ios/Sources/HarnaxAPI/TokenStatsEndpoint.swift:14` |
| 新建 | `harnax-ios/Sources/HarnaxAPI/MemoryClient.swift`：`extension AdminClient` 那份实现，出口在 `harnax-ios/Sources/HarnaxAPI/AdminClient.swift:6` |
| 新建 | `harnax-ios/Sources/HarnaxFeatures/Memory/MemoryListView.swift`、`MemoryListViewModel.swift`、`MemoryDetailView.swift`、`MemoryDetailViewModel.swift` |
| 新建 | `harnax-ios/specs/08-memory.md`：落地后的客户端规格，与本文同一形状 |
| 改 | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift`：段枚举、标题键与分支出口 |
| 改 | `harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:32`：加这一域的依赖，装配在 `:157` 那一段 |
| 改 | `harnax-ios/Sources/HarnaxCore/Contract/AgentSummary.swift`、`harnax-ios/Sources/HarnaxCore/Contract/AgentSaveDraft.swift`：一个字段与三态编码 |
| 改 | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormView.swift:338`、`harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormViewModel.swift`：一枚开关一行控件与一套状态迁移 |
| 改 | `harnax-ios/Sources/HarnaxKit/Resources/en.lproj/Localizable.strings`、`harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings`：新键 |

## 6. 文案与本地化门禁

- 两份目录表的新键**只追加到文件末尾的独立记忆段**，标题类文案与悬停说明各成一条，不在中间行插入：智能体 tab 的段名、清单页标题、空态、删除确认的三层点名列、这一枚开关及其悬停说明。
- 两道门禁各自守什么：`harnax-ios/Tests/HarnaxKitTests/LocalizationKeyTests.swift:27` 要两份目录表的键集合逐字相等，`:98` 要源码里用到的每个键都在两份目录表里存在。新增文案因此必须中文与英文同批落，缺一份直接红。
- 删除确认那句话指的路径必须在渲染它的那一屏真的存在：它点名的"尚未并入的会话记忆"由服务端按前缀清扫带走，客户端虽不列出会话层正文，这一项照样要写。

## 7. 判定条款

1. **清单只列长期层。** 一台只写过会话层的 agent 出现在清单里，正文与日期为空，「待并入」给数字；点进详情读到的是长期层那份整理稿，屏上不出现任何会话层正文。
2. **没有记忆与读失败分得开。** 服务端给 `404` 码时屏上是空态，给其它错误码或传输失败时是错误态，两种不许共用一条文案。
3. **删除点全三层。** 确认对话框同时出现整理稿、每日记录与尚未并入的会话记忆三项，删除成功后这一台从清单里消失，`deletedObjects` 的数字照常报出。
4. **这一枚开关的三态不破。** 打开向导没碰开关就保存，请求体里这个键整个缺席；碰过的按 `0`/`1` 写出，行上原值不参与。
5. **寻址用名字。** 清单行到详情的跳转带的是 agent 名字，一台名字含点号或连字符的 agent 能读回自己的记忆，不落到"没有这台"。
6. **主管屏上没有这一枚。** 团队向导的任何一档都不出现长期记忆的控件。
7. **「待并入」不等于「等你批」。** 这一枚计数说的是还有几个会话层没并走，不是几条候选停在你面前：一次驳回不带走会话层（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryDraftServiceImpl.kt:260`），那一层的候选下一轮还会再提一次，所以屏上的文案只能写「还没并走」，不能写成候选条数。

## 8. 不做与待拍板

客户端不做：会话层正文的展示（读侧没有按会话寻址的路由）；记忆内容的手工编辑；按会话删除；"立即沉淀"这类手动催一次合并出候选的动作；把记忆做成聊天页的气泡；跨 agent 的主人视图汇总。

待拍板，控制方给结论才动：

| 待拍板项 | 选项与推荐 |
|---|---|
| 入口 | 推荐 A（智能体 tab 第四段），次选 B（「我的」里单开一枚非管理行）；C、D 的否决理由见本文第 3 节那一表 |
| 两块是否同批 | 推荐同批：这一枚开关的控件在智能体向导上有天然落点，读清那一屏是同一域的另半边，拆开只多一次回归 |
| 候选审批屏上不上 iOS | 推荐先不上：那四条路由已经齐了（清单 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryDraftController.kt:56`、详情 `:94`、批准 `:111`、驳回 `:129`），但批准是把会话内容并进主人的长期层，是一个决策动作而不是自助读清，这一轮由网页那一屏承载；要给 iOS 加，形状是清单行上多一枚「有候选」的入口而不是再开一段 |
| 会话层正文 | 推荐先不做：会员也装这一层（判据见本文第 2 节），按会话列出因此是一屏待批进度而不是存档，而候选本身已经有表承载 |
