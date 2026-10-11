# Harnax iOS：「优化治理」与「集群监控」两块的落点

## 0. 口径与取证基线

- 网页端把这两块各设一级菜单，移动端没有可加的一级栏位：底部 tab 固定四枚（`harnax-ios/Sources/HarnaxFeatures/Root/HarnaxTab.swift:5-9`，一枚 tab 一个导航栈在 `harnax-ios/Sources/HarnaxFeatures/Root/HarnaxRootView.swift:50-62`）。这一份定的是这两块分别挂进哪一 tab 的哪一层、进去之后按什么顺序排，以及挂法由哪道门禁守住。
- 锚点取当前工作树（提交 `16c488c5` 之上的导航壳与五屏实现集）。网页侧的行号取路由对象自身的花括号闭合范围，逐条按对象边界核过；客户端行号取实际读到 `hx("…")` 或 `case` 的那一行。
- 三个名词固定：**枢纽屏**＝只画入口行、点开才是干活的那一屏；**队列**＝等人逐条批准的清单，批准与驳回各是一次写；**监控**＝只加总已有记录的读，屏上没有任何写动作。

## 1. 归属判据：两块为什么分家，各挂一处

| 判据 | 优化治理 | 集群监控 |
|---|---|---|
| 网页的闸 | 三条路由都不带 access：`:126-133` 的 `/optimization/memory/drafts`、`:139-146` 的 `/optimization/memory`、`:147-152` 的 `/optimization/skill-drafts`（`harnax-webui/config/routes.ts:119-159`） | 三条同样不带：`:165-169` 的 `/monitor/skill-usage`、`:170-174` 的 `/monitor/call-metrics`、`:175-179` 的 `/monitor/token-monitor`（`harnax-webui/config/routes.ts:160-181`） |
| 数据是谁的 | 主人自己名下的智能体长出来的东西——会话并出的整理稿、智能体提出的新技能、每台智能体记住了什么 | 全租户的聚合计数：技能被读被用的次数、工具/MCP/CLI 的调用账单、Token 花费 |
| 动作性质 | 读＋批准/驳回，一次决定改一次状态 | 纯读，屏上没有写 |
| 「我的」那一组 | 不进。「我的」的两组里，`SystemRoute` 是「有人会建有人会删的记录」（环境变量、密钥、渠道），`/system` 这一组在 `harnax-webui/config/routes.ts:182-223`，其中 `:205-210` 的 `/system/api-key` 是唯一带闸的一条 | 进，但自成一快。「我的」因此画两块而不是合成一条长清单（`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79-94` 与 `:95-119`） |
| 落点 | 智能体 tab 第四段（`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12` 的段枚举、`:59` 的出口） | 「我的」的第二块，段头「集群监控」（`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:105`） |

一句话判据：**是不是主人自己的智能体长出来的**。是，就挂在智能体 tab；是聚合读数，就挂在账号屏。

## 2. 优化治理的落点取定

四个候选与判断：

| 候选 | 落点 | 判断 |
|---|---|---|
| A 智能体 tab 第四段 | `harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12`、`:51-61` | **取定**。智能体 tab 的列本来就是「一行一台智能体」，治理的对象正是这些智能体自己长出来的东西；第四段不吃宽度，`harnax-ios/Sources/HarnaxKit/Components/HXSegmented.swift:42` 按选项等分，而上下文 tab 已排到五段 |
| B 上下文 tab 第六段 | `harnax-ios/Sources/HarnaxFeatures/Context/ContextDomain.swift:5` | 否。那五段是智能体可绑定的资源，段内分支即按那个枚举展开；审批队列与记忆都不是可绑定资源 |
| C 「我的」再开一块 | `harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:79-119` 旁再挂一组 | 否。那一屏的语义是账号与管理，把本人智能体的审批队列挂过去，就成了「管理者的审批」，而这一屏每一条的主人都是登录者本人 |
| D 聊天页 | 会话列表顶部那一行（`harnax-ios/Sources/HarnaxFeatures/Chat/ChatTab.swift:47-51`） | 不足以承载。那一行只通向技能队列，另外两屏没有入口 |

段名与枢纽屏标题是两个键：段上「治理」（`agent.segment.governance`，`harnax-ios/Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1451` 与 `harnax-ios/Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1450`）要短，屏名「优化治理」（`governance.title`，`zh:1452`／`en:1451`）与网页一级菜单同名（`harnax-webui/src/locales/zh-CN/menu.ts:14`／`harnax-webui/src/locales/en-US/menu.ts:14`）。

## 3. 枢纽屏的三行与导航形状

- 三行的顺序就是网页组的顺序：会话记忆审批 → 智能体记忆 → 技能晋升。会话并出的整理稿要先有人批，才谈得上长期层里已经装了什么，所以队列排在展示页之前（`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceRoute.swift:21-27`，网页侧同一顺序是 `harnax-webui/config/routes.ts:126-133` 的 `/optimization/memory/drafts` 早于 `:139-146` 的 `/optimization/memory`）。
- 枢纽屏 `harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:80-88` 只把**装得上**的队列列出来：这一域的读清门面在装配时为空，那一行整个不出现，而不是留一个注定失败的按钮。装配好的 App 里三条都在，因为 `/api/admin/**` 的每一个门面都由同一个客户端应答（`harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:196-199`）。
- 三行都是 value push：`GovernanceRoute` 作为导航值在 `harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:29-44` 注册目的地。队列屏与记忆清单都不是叶子屏（各自还要往详情 push），所以只能由路径驱动，不能由状态呈现——状态呈现的非叶子屏会被自己更深的 push 重推一次。
- 分段条随列滚动：枢纽屏把 `HXSegmentBarRow()` 画在自己的滚动容器里（`harnax-ios/Sources/HarnaxFeatures/Governance/GovernanceHomeView.swift:51-74`），宿主只把条交下来（`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:38-44`）。条钉在列外时，行在滚、标题在滚、它不动。

## 4. 集群监控的三行与可见性

- 三行由 `MonitorRoute` 承载（`harnax-ios/Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:16-22`），顺序取网页「集群监控」组：技能监控、调用监控、Token 监控。
- 三行都不带闸：`/system` 这一组（`harnax-webui/config/routes.ts:182-223`）里只有 `:205-210` 的 `/system/api-key` 挂了 access，监控三条一条都没有，所以会员看到的行与管理者一样多；`SystemRoute.visible(for:)` 的按身份裁剪只作用于上面那一块（`harnax-ios/Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:23-25`）。
- 与枢纽屏同一存在条件：技能监控与调用监控按门面是否装配过滤（`harnax-ios/Sources/HarnaxFeatures/Me/MeView.swift:98-104`），Token 监控恒在，因为它读的是这一域一直有的那份统计。
- 调用监控往下还有一层：汇总行的五档分组里，只有能定位到一个对象的行才给下钻（`harnax-ios/Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:142` 注册的目的地），点进去是这一个对象的调用清单。

## 5. 一个队列两个入口，是有意保留

技能晋升在 App 上有两条路：治理枢纽的一行，和会话列表顶部那一行（`harnax-ios/Sources/HarnaxFeatures/Chat/ChatTab.swift:47-51`）。保留后者的理由是**草稿最可能在哪被注意到**——产出它的正是那一场会话，操作者在会话列表里就看见「N 条待审」。网页同样是一个菜单行加一处页内链接，两条路通向同一个队列。

会话列表那一行与枢纽行、队列屏同用「技能晋升」一条键（`skill.draft.title`）：行名即屏名的判据对三处一视同仁，没有例外（第 9 节第 3 条）。

## 6. 命名判据：一行一名，行名即屏名

每一条入口行的 `titleKey` 就是它通向的那一屏自己 `navigationTitle` 用的键，两份目录表里不再另立一枚"行专用"标题键。同处一地两个名字会分叉：这一轮的会话记忆审批行曾通向一屏标题为「记忆合并候选」的队列。这六个页面名本身就取网页的菜单名（`harnax-webui/src/locales/zh-CN/menu.ts:14-17` 与 `:29-32`，英文同处在 `harnax-webui/src/locales/en-US/menu.ts:14-17` 与 `:29-32`）。

| 行／屏 | 唯一的键 | 中文 | 英文 |
|---|---|---|---|
| 会话记忆审批 | `memory.draft.title`（`zh:1500`／`en:1499`） | 会话记忆审批 | Session memory approvals |
| 智能体记忆 | `memory.list.title`（`zh:1467`／`en:1466`） | 智能体记忆 | Agent memory |
| 技能晋升 | `skill.draft.title`（`zh:1341`／`en:1340`） | 技能晋升 | Skill promotion |
| 技能监控 | `skill.usage.title`（`zh:1584`／`en:1583`） | 技能监控 | Skill monitoring |
| 调用监控 | `monitor.calls.title`（`zh:1458`／`en:1457`） | 调用监控 | Call metrics |
| Token 监控 | `monitor.title` | Token 监控 | Token monitor |

副标题是行自己的（`governance.memoryDrafts.subtitle` 等三枚在 `zh:1453-1455`／`en:1452-1454`，`monitor.skillUsage.subtitle` 在 `zh:1457`／`en:1456`），目标屏从不显示那句话，因此不参与同名判据。段名 `agent.segment.governance` 与枢纽屏名 `governance.title` 同理是两个键，不是分叉。

## 7. 依赖装配与新增文件

四块新域各有一份可选门面挂在依赖包上（`harnax-ios/Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:33-42`，形参在 `:97-100`）：`memory`、`memoryDrafts`、`skillUsage`、`toolMetrics`；技能队列沿用既有的 `drafts`（`:24-27`）。传输侧每域一对文件，路由前缀分别是 `harnax-ios/Sources/HarnaxAPI/MemoryEndpoint.swift:12`、`harnax-ios/Sources/HarnaxAPI/MemoryDraftEndpoint.swift:12`、`harnax-ios/Sources/HarnaxAPI/SkillUsageEndpoint.swift:11`、`harnax-ios/Sources/HarnaxAPI/ToolMetricsEndpoint.swift:16`。

界面按域各成一目录：`harnax-ios/Sources/HarnaxFeatures/Governance/`、`harnax-ios/Sources/HarnaxFeatures/Memory/`、`harnax-ios/Sources/HarnaxFeatures/MemoryDrafts/`、`harnax-ios/Sources/HarnaxFeatures/SkillUsage/`、`harnax-ios/Sources/HarnaxFeatures/CallMetrics/`。长期记忆那一枚开关落在智能体向导（`harnax-ios/Sources/HarnaxFeatures/Agents/AgentFormView.swift:366-383`），它的三态读写规则与技能自我进化那枚同形：没搬动过就不发这个键。

## 8. 门禁清单

| 门禁 | 守什么 |
|---|---|
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:44` | 九行（管理三、监控三、治理三）的行名等于目标屏自己的标题键，读的是九份屏源码而不是路由常量 |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:15`、`:22`、`:31`、`:37` | 底部四枚 tab 且没有"系统"槽；管理员／会员／未知账号各看到哪几行 |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:71` | 三枚监控自成一快且顺序同网页；Token 监控不再被算作管理行 |
| `harnax-ios/Tests/HarnaxFeaturesTests/SystemHomeTests.swift:79`、`:93` | 每一行的标题与副标题在中英两份目录表都解析得出 |
| `harnax-ios/Tests/HarnaxFeaturesTests/SegmentBarGateTests.swift:12-15`、`:19-29` | 两个宿主把分段条交下去、九列把条画在自己的滚动里，治理枢纽是第九列 |
| `harnax-ios/Tests/HarnaxKitTests/LocalizationKeyTests.swift:28`、`:99` | 中英键集合逐字相等；源码用到的每个键都在两份表里存在 |

## 9. 判定条款

1. **两块各挂一处，不重复出现。** 「优化治理」三行只出现在智能体 tab 第四段的枢纽屏上，「集群监控」三行只出现在「我的」的第二块；同一屏内任何一行都只画一次。
2. **段第四、列第五。** 智能体 tab 的四段是智能体、团队、任务、治理，治理是最后一段（`harnax-ios/Sources/HarnaxFeatures/Agents/AgentHomeView.swift:8-12`），段名等分宽度、不换行截断。
3. **入口名即页面名。** 从任一行进去，导航栏出现的文字与那一行的标题同一份文案；会话列表顶部那一行也按这条走，同用队列屏自己的 `skill.draft.title`，没有例外。
4. **监控行不因身份增减。** 会员看到的监控行与管理者完全一样，三枚都在；管理块仍按身份少一行密钥清单。
5. **门面没装就整行消失。** 装配里没有某一域的读能力时，那一行不出现，屏上不留下点了会失败的按钮。
6. **分段条跟着列走。** 枢纽屏与九列一样把条画进自己的滚动容器，滚动时条与行同速。
7. **批准与驳回是写。** 队列屏的两个决定各发一次写请求，失败留在屏上可重试；屏上没有一处把批准做成批量动作。

## 10. 不做与已决

不做：给这两块单独加一枚底部 tab（四枚的约束在第 0 节）；把治理枢纽做成一页仪表盘式卡片；把三枚监控合并成一屏分段切换；给队列加批量批准；在 App 上提供记忆正文的手工编辑。

三处记账已决（2026-10-11）：

| 项 | 结论 |
|---|---|
| 会话列表那一行的名字 | 统一到「技能晋升」：与枢纽行、队列屏同用 `skill.draft.title` 一条键，已落地——`skill.draft.entry` 键从两份目录删除，行改读 `skill.draft.title`（`harnax-ios/Sources/HarnaxFeatures/SkillDrafts/SkillDraftEntryRow.swift:53`），行名＝屏名由 `harnax-ios/Tests/HarnaxFeaturesTests/SkillNavigationTests.swift:222` 的新闸门守着 |
| 治理枢纽的屏名 | 保留「优化治理」：与网页一级菜单同名，离开 tab 后还看得出这一屏对应网页的哪一组；段名「治理」与屏名两个键的分工照旧（第 2 节） |
| 调用监控的第五档 | 维持现状：只把能定位到对象的行做成可下钻，其余三档在网页也是纯读数，是否补入口看真实用法再定 |
