# iOS 实现规格 09：技能监控（谁真的加载并 follow 了哪些技能）

一屏一问：本租户可见的每一个技能，在这个窗口里被读进过几次上下文、被真的照着做过几次，以及哪些一次都没有。行为逐条对齐 harnax-webui 现存页面。所有锚点均为仓库内真实读过的行号（2026-10-11 逐条核过），格式 `相对路径:行号`：后端与 webui 从仓库根起算，iOS 从 `harnax-ios/` 起算。后端统一 `ResultVo<T>` 信封 `{code, message, data}`；admin 的 Jackson 丢弃 null 键，因此除注明「必带」外 iOS DTO 一律可选。

## 1. 范围与取舍

- 入口挂「我的」的「集群监控」块，三行依次是 Token 监控 / 技能监控 / 调用监控（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:16-23`，绘制在 `Sources/HarnaxFeatures/Me/MeView.swift:98-119`）。控制台的对应物是一级「集群监控」组（`harnax-webui/config/routes.ts:160-181`），映射是 `MonitorRoute.skillUsage` → `harnax-webui/src/pages/skill/usage.tsx`（路由项 `harnax-webui/config/routes.ts:165-169`）。iOS→控制台这条映射成立的前提是**三行都不设管理员门**：控制台的三条监控路由都不带 access（`harnax-webui/config/routes.ts:165-179`），带 access 的是同一份文件里的 API Key 路由（`:205-210`）；iOS 侧的过滤条件因此只看门面装配没装配（`Sources/HarnaxFeatures/Me/MeView.swift:98-104`），不看账号。同页的「系统管理」块（环境变量 / API Key / 渠道）是另一种东西——会写记录的才需要门（`Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:18-25`），`SystemRoute` 里不再有任何监控项。
- 一只读、一路由、一参数：整屏就是 `summary` 这一个响应，加上对它的一层客户端筛选。事件写入不在本域（服务端 ingest 走 router 侧的 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:294`），本屏没有任何 create/edit/delete。
- **服务端回的行集含零事件技能**，「没用过」的谓词是 `viewCount == 0 && useCount == 0` 两个条件同时成立，不是「或」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:145`，`harnax-ios/Sources/HarnaxCore/Contract/SkillUsage.swift:52`）。这条页的存在理由，详见 §2 末。
- 叶子屏，不 push：控制台的技能名是跳 `/context/skill/detail/{skillId}` 的链接（`harnax-webui/src/pages/skill/usage.tsx:83-91`），iOS 这一屏不给——行上的两个计数就是全部答案，技能详情在技能域已有自己的入口。
- 不做：按用户 / 按会话拆列（服务端的聚合只按技能 keyed，`harnax-entity/src/main/resources/mapper/SkillUsageMapper.xml:60`）、窗口自由输入（只有三档，§5.1）、把「只看没用过的」下推成服务端参数（这条路由只接 `days`，§2）。

## 2. 后端契约：一条路由，窗口在服务端收口

`GET /api/admin/skill-usage/summary?days=`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:23,31`；前缀 `/api/admin`，Bearer + `X-Tenant-ID`，`APIClient` 现有装配即可）。

**路径自成一族**，不挂在 `/api/admin/skills/{id}` 下：那里的 `{id}` 是 `Long`，`skills/usage` 会被当成 id 去解析而失败（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:19-23`）。

| 参数 | 线上形状 | 缺省与收口 |
|---|---|---|
| `days` | query，整数 | `defaultValue = "30"` 且形参可空（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:37,39`）；服务再夹到 **1..365**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:113`，常量 `MIN_WINDOW_DAYS` `:212` / `MAX_WINDOW_DAYS` `:215`） |

**没有 `tenantId`**，租户由调用方自己的凭据解出（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:117`），可见技能集与技能列表用同一把规则：`active = 1` 且（公开或本人创建），并允许共享 builtin 库的技能（`harnax-entity/src/main/resources/mapper/SkillUsageMapper.xml:52-59`）。

`SkillUsageSummaryResponse`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:16-37`）七个键，全部有非空默认值：`days` · `since`（`LocalDateTime?`，`:20-21`）· `totalSkills` · `zeroUseCount` · `totalViews` · `totalUses` · `rows`。四个汇总数是行的算术，不是第二条查询（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:141-149`）：`totalSkills = rows.size`、`zeroUseCount = rows.count { viewCount == 0 && useCount == 0 }`、`totalViews`/`totalUses` 为各行两列之和。**汇总与行集因此天然自洽**，§5.4 拿调试台夹具算一遍给判据。

`Row`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:44-71`）九个字段：`skillId` / `name` / `repositoryId` / `repositoryName?` / `status`（0 禁用 1 启用，非布尔）/ `origin`（`human` 或 `agent_promoted`，缺省即 `Skill.ORIGIN_HUMAN`）/ `viewCount` / `useCount` / `lastUsedAt?`。

聚合的实际形状（`harnax-entity/src/main/resources/mapper/SkillUsageMapper.xml:39-62`）：`FROM skill s LEFT JOIN skill_usage u`，**窗口与事件侧租户谓词都写在 ON 子句里**而不是 WHERE——写在 WHERE 会把一次事件都没有的技能整行丢掉（`:24-32`）；`GROUP BY s.id, s.name, s.repository_id, s.status, s.origin`（`:60`）；`ORDER BY view_count DESC, use_count DESC, s.name ASC`（`:61`）；两列计数是 `CASE WHEN u.event = 'VIEW' / 'USE'` 的 `COALESCE(SUM(...), 0)`（`:44-45`），`last_used_at` 是 `MAX(u.occurred_at)`（`:46`），窗口内无事件即为 null。

## 3. 数据模型：`Sources/HarnaxCore/Contract/SkillUsage.swift`

一个文件里放三个类型：`SkillUsageSummary`（`:11-28`）、`SkillUsageRow`（`:31-54`）、门面协议 `SkillUsageReading`（`:58-64`，**就声明在这份契约文件内**，不另起文件）。

### `SkillUsageSummary`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:16-37`）

| 字段 | 线上形状 | iOS 类型 | 备注 |
|---|---|---|---|
| `days` | 必带整数 | `Int` | 服务端**实际回答**的窗口，夹完之后的值（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:113,142`），屏上的窗口句印它 |
| `since` | ISO `LocalDateTime` 字符串，可缺 | `String?` | `:20-21`；见下方「两种时间戳不许混」 |
| `totalSkills` | 必带 | `Int` | `= rows.size`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:144`） |
| `zeroUseCount` | 必带 | `Int` | `:19`，谓词与行的 `isUnused` 同一个 |
| `totalViews` / `totalUses` | 必带 | `Int` | 行求和（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:146-147`） |
| `rows` | 必带数组 | `[SkillUsageRow]` | 服务端缺省 `emptyList()`，所以空表是空数组而不是缺键 |

`usedSkillCount: Int { totalSkills - zeroUseCount }`（`:27`）**不是线上字段**，是本地派生：控制台把「用过」放在零计数旁边，而这一份响应里没有这个键。派生放在契约里而不是屏里，屏就永远不需要拿一个可能为 0 的总数去除。

### `SkillUsageRow`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:44-71`）

| 字段 | 线上形状 | iOS 类型 | 备注 |
|---|---|---|---|
| `skillId` | number，必带 | `Int64` | 直接当 `Identifiable.ID`（`:49`），这一行的身份从不缺席 |
| `name` | string，必带（缺省空串） | `String` | 空串由屏回落「未命名技能」（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:216`） |
| `repositoryId` | number，必带 | `Int64` | |
| `repositoryName` | 可缺 | `String?` | 丢 null 键的正是这一个（`Tests/HarnaxAPITests/SkillUsageWireTests.swift:112` 钉的是它） |
| `status` | 0/1 整数列 | `Int` | `isEnabled` 判 `== 1`，严格等号（`:50`） |
| `origin` | string，非空缺省 `human` | `String` | **不用枚举**：不认识的值也要能渲染，只有 `SkillDraftRules.agentPromotedOrigin`（`Sources/HarnaxCore/Contract/SkillDraft.swift:292`）这一个值意味着智能体写的（`:38-41,53`） |
| `viewCount` / `useCount` | 必带整数 | `Int` | 加载 vs 照着做，两列分开 |
| `lastUsedAt` | ISO `LocalDateTime`，可缺 | `String?` | 窗口内两类事件里最近的一个；缺即「这个窗口里没见过」 |

`isUnused: Bool { viewCount == 0 && useCount == 0 }`（`:52`）是「没用过」的唯一谓词，与服务端 `zeroUseCount` 的算式逐字同形（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:145`）、与控制台的 `Switch` 过滤同形（`harnax-webui/src/pages/skill/usage.tsx:48`）。

### 两种时间戳不许混

这一族的 `since` 与 `lastUsedAt` 都是 **`LocalDateTime`，Jackson 按 ISO 写、带 `T`**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/config/JacksonConfig.kt:13`：java time 由 Jackson 3 databind 内建支持，`harnax-admin/src/main/resources/application.yml:23` 配的 `yyyy-MM-dd HH:mm:ss` 管不到它）。归一化只由一个类型负责：`hxSpaceStamp`（`Sources/HarnaxFeatures/Support/RowMeta.swift:34-38`）把第一个 `T` 换成空格、其余字符一个不动；已经不带 `T` 的串原样返回；**缺失或全空白的串返回 nil**，屏上因此画破折号而不是猜一个日期（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:236-244`）。调用监控那一族的时间戳形状不同、读法也不同，见 `harnax-ios/specs/10-call-metrics.md` §3——把两边的解析器互换是最容易弄坏这两屏的做法。

## 4. 门面与传输

- `Sources/HarnaxCore/Contract/SkillUsage.swift`：`SkillUsageReading` 就声明在这里（`:58-64`），一个方法 `summary(days:) -> Result<SkillUsageSummary, APIError>`，`Sendable`。协议 KDoc 写明这条路由不接 `tenantId`（`:56-57`，对应 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:36-39` 只有 `days` 一个形参）。
- `Sources/HarnaxAPI/SkillUsageEndpoint.swift`（新，internal，仿 `Sources/HarnaxAPI/SkillEndpoint.swift:86` 那种 enum 形状）：`root = "/api/admin/skill-usage"`（`Sources/HarnaxAPI/SkillUsageEndpoint.swift:11`），`summary(days:)` 拼 `"\(root)/summary"` 并把 `days` **永远显式发出**（`Sources/HarnaxAPI/SkillUsageEndpoint.swift:15-17`）——不留给服务端 30 的缺省，因为响应会回显它答的窗口，屏要拿自己的标签去对那个回显（`Sources/HarnaxAPI/SkillUsageEndpoint.swift:13-14`）。发之前客户端先夹下界 `max(1, days)`（`Sources/HarnaxAPI/SkillUsageEndpoint.swift:16`），与服务的 `MIN_WINDOW_DAYS = 1`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:212`）同一把下界，0 或负数不会出现在线上。
- `Sources/HarnaxAPI/SkillUsageClient.swift`：`extension AdminClient: SkillUsageReading`（`:5-9`），走共享 `client.send`，无额外装配。
- `Sources/HarnaxFeatures/Support/HarnaxDependencies.swift`：`public let skillUsage: (any SkillUsageReading)?`（`:40`，可空的理由与 `memory`/`memoryDrafts` 同一条：admin 面上没装就让行消失，不给一个注定失败的按钮），init 形参 `:99`，赋值 `:134`，`live()` 里绑同一个 `admin` 实例（`:198`）。
- 导航装配：`MeView` 的监控 `ForEach` 之后由 `.navigationDestination(for: MonitorRoute.self)` 落屏，`skillUsage` 分支再解一次可选（`Sources/HarnaxFeatures/Me/MeView.swift:145-158`），`SkillUsageView(catalog:)` 收的是非可选门面（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:20-22`）。

## 5. 界面

新目录 `Sources/HarnaxFeatures/SkillUsage/`，三件文件：SkillUsageView、SkillUsageViewModel、SkillUsagePresentation（叶子屏不需要单独的入口行组件）。本节与 §3、§4 的锚点一律写全 iOS 相对路径。

### 5.1 屏壳与四个相态

`navigationTitle` 用 `skill.usage.title`（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:27`），与 `MonitorRoute.skillUsage.titleKey`（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:27`）**是同一个词的两个键**：行上印的与导航栏印的必须一致，但导航栏这一句要走 `Text(verbatim:)` 以免把键当正文（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:27`）。右上角一个刷新按钮，无障碍标签 `state.action.reload`（`:28-37`）；`.task` 只在 `phase == .loading` 时首读（`:38-40`），`refreshable` 走同一条 `refresh()`（`:41`）。

四个相态各归各的句（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:20-31`）：`.loading` / `.content` / `.empty`（租户一个技能都看不见，即 `rows.isEmpty`，`:161`）/ `.failed(String)`。空态与错误态不许互相借词（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:49-54`）；「筛选后没匹配到」是第三种消息，见 §5.2。被拒的**再读**不清屏：`inlineError` 以 `HXBanner` 画在控件之上、列表仍在（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:63-70`，`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:129-133`）。

正文顺序是控制台的顺序：控件 → 五个数字 → 表 → 窗口句（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:71-74` 对应 `harnax-webui/src/pages/skill/usage.tsx:152-232`）。底部留白 24 而不是 tab 净空——它是从「我的」push 进来的，不贴 tab 栏（`:78-79`）。

### 5.2 窗口与唯一筛选

窗口是三次按压里选一个的分段控件，永不是输入框（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:110-118`），三档就是控制台 `Select` 的三档（`harnax-webui/src/pages/skill/usage.tsx:195-199`）：`Window` 的 rawValue 直接是发送的 `days`（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:35-44`），控件 id 与请求参数因此不可能各说一套。初值 30——控制台的初值（`harnax-webui/src/pages/skill/usage.tsx:22`）与服务端的缺省（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:46`）都是 30（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:58`）。分段控件报回一个不属于三档的 id 时回落初值，而不是把一个屏无法标注的数发出去（`:62-64`）。切换窗口即重读（`:86-89`），因为它是这条路由的唯一参数。

`unusedOnly` 是客户端筛选，与控制台的 `Switch` 一样不花请求（`:94-96` 只调 `recompute()`）——这条路由没有「只要没人用过的」这个参数（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:36-39` 只有 `days`）。它保留的是 §1 那条谓词为真的行（`:160`），并把「一行都没被筛掉但筛选没中」单独说：`showsNoUnusedNote` 要三个条件同时成立——开关开着、当前列表空、原始行集非空（`:111-113`），文案 `skill.usage.unusedOnly.none`（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:97-103`）。

分段条是内联画的，不由 tab 壳下发：这一屏是被 push 的叶子，本身不占任何一列（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:108-110`，闸门在 `Tests/HarnaxFeaturesTests/SegmentBarGateTests.swift:62-79`）。

### 5.3 五个数字

`metrics` 五块，顺序是控制台四张卡的顺序再加派生的那一块（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:142-150`；卡序见 `harnax-webui/src/pages/skill/usage.tsx:51-75`）：`skills` / `loads` / `uses` / `unused` / `used`（`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:13-24`）。三个数字有解释句，两个没有——`captionKey` 只在 loads / uses / unused 三档非空（`:29-36`），逐一对应控制台真有 Tooltip 的三张卡（`harnax-webui/src/pages/skill/usage.tsx:61,67,73`）；技能总数与派生的「至少用过一次」不编造解释。色位走调色板槽而非字面色（`:40-48`，对应 `harnax-webui/src/pages/skill/usage.tsx:55,60,66,72` 的四色）。

数值是**不加千分位的裸整数字符串**（`:58-61`），因为控制台的 `Statistic` 也是把原始列直接喂进去（`harnax-webui/src/pages/skill/usage.tsx:51-75`）：两边都读 `1200`。每块右上角一条同色小色带（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:187-192`），色相本身不承载信息，读序仍是标题→数字（`:195-205`）。

### 5.4 汇总与行集的自洽（一个可算的实例）

服务端四个汇总数全是行的算术（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:141-149`），所以任何一份合法响应都必须满足：`totalSkills == rows.count`、`totalViews == Σ viewCount`、`totalUses == Σ useCount`、`zeroUseCount == rows.filter(isUnused).count`。调试台夹具 `App/HarnaxDebugScreens.swift:2759-2766` 就是这样一份自洽实例，可直接手算：四行的 `viewCount` 46 + 9 + 3 + 0 = **58**，等于 `totalViews`；`useCount` 12 + 4 + 0 + 0 = **16**，等于 `totalUses`；行数 4 = `totalSkills`；只有 id 40 那一行两个计数同时为 0，故 `zeroUseCount` = 1；本地派生 `usedSkillCount` = 4 − 1 = 3。夹具里那一行还顺手钉住另外两件事：`name` 是空白串（屏回落「未命名技能」，`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:216`），且 `lastUsedAt` 与 `repositoryName` 两个键都不出现（破折号与「无仓库 pill」，`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:142-147`）。

屏**不校验**这四条等式、也不重算任何一条：汇总列原样画（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:143-146`），唯一的本地算式是 `usedSkillCount`（`Sources/HarnaxCore/Contract/SkillUsage.swift:27`）。

### 5.5 一行卡

控制台七列表在这里压成一张卡（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:209-264`）：标题行是 `name`（空回落 `skill.table.unnamed`）加右侧两列数字 `loads` / `uses`，两列的小标题复用 §5.3 那三个词里的两个 `Kind.titleKey`（`:252-262`），所以卡上的 `Loads` 与上面的卡是同一个词。第二行是两枚 pill 加可选仓库 chip：状态（`state.badge.enabled` / `state.badge.disabled`，0/1 严格判 `== 1`）、来源（`skill.origin.agent` / `skill.origin.human`），顺序固定状态在前（`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:98-109`）；**这两枚在这里永远都在**，与技能列表不同——这份 payload 的 `origin` 不可空且缺省即 `human`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:60-61`）。仓库 chip 只在 trim 后非空时画（`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:144-145`）：控制台在此处印一个裸 `-`（`harnax-webui/src/pages/skill/usage.tsx:98`），而 pill 画成 `-` 会读成一个值，这一行的沉默正是重点。第三行是「最近事件」标签加时间戳，没有则 `skill.usage.lastEvent.none`（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:232-245`）。

排序：**屏一律不再排**（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:157-160`），表的顺序就是响应的顺序，而响应是 `view_count DESC, use_count DESC, s.name ASC`（`harnax-entity/src/main/resources/mapper/SkillUsageMapper.xml:61`）；控制台也没排（`harnax-webui/src/pages/skill/usage.tsx:46-49`），这里再排一次会让两张表顶上不同行。

### 5.6 表格下面那句窗口话

两行 caption，各自条件渲染（`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:143-156`）：`skill.usage.window` 印的是 `answeredDays`——**服务端回显的窗口**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:17-18`，`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:140`），不是本屏请求的那个数；回显缺失就不印这句而不是印一个猜测的跨度。`skill.usage.since` 印 `since` 归一化后的文本（`:141`）。前者带 `%d`、后者带 `%@`（`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1615-1616`）。

### 5.7 竞态：后到的旧答复什么都拿不走

`generation` 在每次发送前自增、答复落地前比对（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:100-103,116-127`），标签不符的答复连行带五个数字和窗口回显一起不要。会咬人的正是这一例：连着换窗口时，90 天的答复比随后发出的 7 天请求后到——若不做代际判定，屏上的窗口句会印 90 而控件指着 7。首读之外的读不动 `phase`（`:120`），所以下拉刷新不会把整屏打回 loading。错误也走同一道判定，且 `ErrorMessage.carriesNews` 先过一道，被取消的读不弹错误卡（`:125-128`）。

## 6. 本地化

命名空间 `skill` 与 `monitor` 都已在 `Tests/HarnaxKitTests/LocalizationKeyTests.swift:10-16` 的闸门表内，两份 `Localizable.strings` 里本域各占一个注释块：`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1585-1616`、`Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1586-1617`。

屏上出现的键，全部已在中英两侧各自存在（逐条查过，无缺失）：

- 屏与表：`skill.usage.title` · `skill.usage.section` · `skill.usage.empty`
- 五个数字：`skill.usage.metric.skills` / `.loads` / `.uses` / `.unused` / `.used`（五个都有，`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:16-24`）
- 三条解释：`skill.usage.hint.loads` / `.uses` / `.unused`（`:29-36`）
- 筛选与其独有消息：`skill.usage.unusedOnly` · `skill.usage.unusedOnly.none`
- 行的最后一列：`skill.usage.lastEvent` · `skill.usage.lastEvent.none`
- 窗口两句：`skill.usage.window`（`%d`）· `skill.usage.since`（`%@`）
- 复用的既有键：`skill.table.unnamed`、`state.badge.enabled`、`state.badge.disabled`、`skill.origin.agent`、`skill.origin.human`、`state.action.reload`、`state.error.title`
- 窗口三档与 `monitor` 面：`monitor.range.sevenDays` / `.thirtyDays` / `.ninetyDays`（`Sources/HarnaxFeatures/SkillUsage/SkillUsageViewModel.swift:48-53`，与 Token 监控共用同一批跨度词）、`monitor.section`（块头，`Sources/HarnaxFeatures/Me/MeView.swift:105`）、`monitor.skillUsage.subtitle`（行上那句副标题，`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:35`，在表中占 `Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1457`、`Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1458`）；行标题不再另造键，它复用屏自己的 `skill.usage.title`（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:27`，`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1584`、`Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1585`）

带参键只有两个，占位符各一个：`%d` 是天数、`%@` 是窗口起点文本。`testTemplatedCopyIsAlwaysCalledWithItsArguments`（`Tests/HarnaxKitTests/LocalizationKeyTests.swift:68-80`）不允许任何模板句被裸调用，`testEveryKeyUsedInSourceExistsInBothCatalogs`（`:99-110`）会把「源码用到、目录缺键」判红。它的取键方式决定了覆盖边界：除 `hx`/`HXText` 的调用位（`:198-201`）与 `hxCount` 的两个复数臂（`:189-197`）之外，它只认**引号里写全的点分字面量**（`:202-208`），因此经 `titleKey` 变量转手但整串写全的键在检（本域五个 `skill.usage.metric.*`、三个 `skill.usage.hint.*` 正是这一类，`Sources/HarnaxFeatures/SkillUsage/SkillUsagePresentation.swift:18-22,32-34`），而 `"...group.\(rawValue)"` 这种拼接落不进正则、整族不受检——那一族只能靠文档逐条数，见 `harnax-ios/specs/10-call-metrics.md` §6。图标名避开命名空间前缀：本屏用的 `books.vertical` / `square.stack` / `eye` / `bolt` / `moon` / `checkmark.circle` / `arrow.clockwise` / `exclamationmark.triangle` 都不以任一namespace 开头（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:45`，`Sources/HarnaxFeatures/SkillUsage/SkillUsageView.swift:199-204,33,67`）。

## 7. 测试矩阵

- `Tests/HarnaxAPITests/SkillUsageWireTests.swift`：**8 个 `func test`**（实测计数）。路由形状（GET、自己的族、不许落在 `/api/admin/skills/` 前缀下，`:26-36`）、`days` 永远发（`:41-47`）、三档窗口（`:51-55`）、0 与负数夹成 1（`:60-67`）、鉴权 admin 读且线上无租户参数（`:69-76`）、零行留在答复里且谓词各就各位（`:82-114`）、`since` 的 ISO 形状（`:120-131`）、空租户解码成 0 而不是失败（`:134-145`）。
- `Tests/HarnaxFeaturesTests/SkillUsageViewModelTests.swift`：**20 个 `func test`**（实测计数），fake 在 `Tests/HarnaxFeaturesTests/SkillUsageFakes.swift`（回复队列纪律：没排队答复的请求以解码失败回，并且仍在调用日志里留痕，`:5-40`）。覆盖：三档初值 30 且必发（`:48`）、越界 id 回落（`:65`）、回显的是服务端答的窗口（`:73`）、被取代的答复什么都拿不到（`:82`）、五个数字＝控制台四个＋派生补位（`:112`）、`used` 在本地派生而非读线上（`:128`）、每个零用行都站着（`:147`）、筛选不花请求（`:168`）、筛不中不等于空态（`:186`）、行的两枚 pill（`:208`）、不认识的 origin 不当智能体技能（`:229`）、空白仓库名当缺（`:237`）、空窗口是空态不是失败（`:246`）、传输失败是错误态（`:257`）、非 200 信封码同样是（`:267`）、被拒的刷新留行加横幅（`:277`）、被取消的读不弹错误卡（`:291`）、「没数据」与「读失败」两句分开（`:303`）、表保持答复顺序（`:322`）、时间戳只掉 `T` 别的一个字符不动（`:352`）。
- 夹具都经解码器构造（`Tests/HarnaxFeaturesTests/SkillUsageFakes.swift:49-74`），两个契约类型故意为「没有成员化初始化器」，测试因此造不出服务端不发的一列——`usedSkillCount` 正是最容易凭空造出来的那一个。
- 门禁：`Tests/HarnaxKitTests/LocalizationKeyTests.swift:28-110`（键集、空值、占位符、裸调用、用到即存在五条）、`Tests/HarnaxFeaturesTests/SegmentBarGateTests.swift:62-79`（内联分段条）、`Tests/HarnaxFeaturesTests/ListToolbarGateTests.swift` 与 `Tests/HarnaxFeaturesTests/ListRefreshIdentityTests.swift` 一类既有列表闸门。构建与跑测由主控会话执行，本 spec 不代跑。

## 8. 记账（本轮不做或做不了）

- **不做服务端侧筛选**：这条路由只有 `days` 一个参数（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillUsageController.kt:36-39`），「只看没用过的」只能是客户端筛选，控制台的 `Switch` 同样是纯前端（`harnax-webui/src/pages/skill/usage.tsx:46-49`）。两边都受同一约束：筛选是在**已答复的行集**上做的，服务端没有「未使用」谓词可下推。
- **分页不做**：屏一次拿全租户可见技能（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/SkillUsageSummaryResponse.kt:35-36` 的 `rows` 无分页信封），控制台的 `Table` 分页只是前端 20 条一页（`harnax-webui/src/pages/skill/usage.tsx:224-228`）。iOS 一张卡一行、不分页，因此技能数很多时会是一屏长列表；这是服务端没有分页读所决定的，不是屏偷懒。
- **技能详情跳转不做**：控制台的技能名是链到 `/context/skill/detail/{id}` 的（`harnax-webui/src/pages/skill/usage.tsx:83-91`），iOS 这一屏是叶子（§1）。技能详情在技能域有入口，从监控页再开一条同类链会与技能列表的导航重复。
- **按用户 / 按会话 / 按仓库拆列不做**：`selectUsageByTenant` 的 `GROUP BY` 只有技能五列（`harnax-entity/src/main/resources/mapper/SkillUsageMapper.xml:60`），`SkillUsage` 事件表虽有 `user_id` / `session_id`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillUsageServiceImpl.kt:97-104` 写入），但管理面对外只有这一份聚合形状；iOS 侧无法凭现契约拆出这些维度。
- **无推送、无轮询**：换窗口或下拉才有新数。控制台也一样要手动改 `Select` 才重取（`harnax-webui/src/pages/skill/usage.tsx:27-44` 依赖 `[days]`），故不算偏离，但也不会更即时。
- **Core 层没有本域单测**：`isUnused` / `isEnabled` / `isAgentPromoted` 三个谓词在 `Tests/HarnaxCoreTests/` 下没有直接对应用例（实测：该目录不含任何 `SkillUsage` 字样），它们由 `SkillUsageWireTests` 的解码断言间接钉住（`Tests/HarnaxAPITests/SkillUsageWireTests.swift:103-113`）以及 Features 侧的 `:208-244`。`hxSpaceStamp` 的「解不出即破折号」也只在 Features 侧钉（`Tests/HarnaxFeaturesTests/SkillUsageViewModelTests.swift:352`），Core 无独立用例。
- **调试台只钉住 30 天一档**：`HarnaxDebugSkillUsage.summary(days:)` 无视入参、恒回同一份夹具（`App/HarnaxDebugScreens.swift:1648-1652`），夹具里的 `days` 与 `since` 就是 30 天那档（`:2760`）。7 天与 90 天的截图形态只能靠真机切换得到。
