# iOS 实现规格 07：自我进化（技能草稿审核）

会话域内的技能草稿队列与人工审核，行为逐条对齐 harnax-webui 现存页面。所有锚点均为仓库内真实读过的行号（2026-10-06 逐条 `sed` 核过），格式 `相对路径:行号`。后端统一 `ResultVo<T>` 信封 `{code, message, data}`；admin 的 Jackson 3 **丢弃 null 键**，因此除注明「必带」外，iOS DTO 一律可选。

## 1. 范围与三条取舍

- 入口挂会话域：会话列表顶部一屏一行，带待审计数。队列页与审核详情页都从这一行进。
- 队列的过滤参数是 `status` / `name` / `sessionId` 三个（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/SkillDraftController.kt:55-72`），**按会话筛是服务端能力**；本 spec 这两屏（队列页与审核详情页）自己不带会话条件，badge 因此是「本租户待审总数」，不是「本会话条数」——那行给的是审核链的导航。带 `sessionId` 的那次读在会话页的本技能列表（见 `02-session-chat.md`），webui 的会话抽屉走同一条件。
- 技能可见性设置、用量分析页、独立审核历史页不在本轮范围。草稿详情自带的 `history` 轨迹在。

## 2. 后端契约：四条路由、两个拒绝通道

`GET /api/admin/skill-drafts` · `GET /api/admin/skill-drafts/{id}` · `POST .../{id}/approve` · `POST .../{id}/reject`
（`SkillDraftController.kt:42,50,88,105,126`；前缀 `/api/admin`，Bearer + `X-Tenant-ID`，`APIClient` 现有装配即可，无需 router 侧密钥。）

| 动作 | 参数 | 响应体 |
|---|---|---|
| 队列 | `pageNum`=1 · `pageSize`=20 · `status` · `name` | `Page<SkillDraftResponse>` = `{pageNum,pageSize,total,records}` |
| 详情 | 路径 `id` | `SkillDraftDetailResponse` |
| 批准 | 路径 `id` + JSON body | `SkillDraftDecisionResponse` |
| 驳回 | 路径 `id` + JSON body | `SkillDraftDecisionResponse` |

**没有 `/page` 后缀**——这与 app 里其余列表（`/api/admin/skills/page` 等）形状不同，容易照抄错（`SkillDraftController.kt:50` 对比 `harnax-ios/Sources/HarnaxAPI/SkillEndpoint.swift:86`）。

拒绝分两类，界面处置完全相反：

1. **谁都动不了的拒绝走错误信封**：未知草稿、`status` 传了不认识的值、批准不带 `expectedDigest`（`SkillDraftServiceImpl.kt:297-300`）、驳回不带理由或理由超长（`SkillDraftServiceImpl.kt:393-396`）。iOS 落到 `APIError.business`，按现有错误横幅渲染。
2. **界面必须据以再动作的拒绝随 HTTP 200 + `code:200` 回来**，判据是 `data.outcome` 而不是状态码：`PROMOTED` / `REJECTED` / `DRAFT_CHANGED` / `ALREADY_REVIEWED` / `NAME_TAKEN`（`SkillDraftDecisionResponse.kt:20,51-65`，KDoc `:9-16` 说明了为什么不并进错误分支）。
3. **名字抢占是唯一一个走错误信封但界面要按 code 分支的拒绝**：HTTP 仍是 200，信封里 `code` 是 409（`SkillDraftController.kt:115-117` 在 `DuplicateKeyException` 上 `ResultVo.error(409, …)`）。含义是批准跑完一半名字被别的发布者占了，整笔回滚、草稿仍 PENDING，重读即修复。iOS 的 `ResponseMapper` 在信封判 code 的那一步把它映射成 `APIError.business(code: 409)`（`harnax-ios/Sources/HarnaxAPI/Transport/ResponseMapper.swift:22-24`），详情 VM 按 code 分这一支。

## 3. 数据模型：`Sources/HarnaxCore/Contract/SkillDraft.swift`（新）

### `SkillDraftRow`（`SkillDraftResponse.kt:16-55`）

| 字段 | 线上形状 | iOS 类型 | 备注 |
|---|---|---|---|
| `id` | number | `Int64?` | `Identifiable.ID` 沿用可选 id 的既有约定 |
| `name` | string | `String?` | |
| `description` | 可缺 | `String?` | |
| `status` | `PENDING`/`APPROVED`/`REJECTED`/`EXPIRED` | `String?` | 列上写着 EXPIRED 但**没有任何代码写入**，`STATUSES` 不含它（`SkillDraftServiceImpl.kt:601`，筛选项在 `:219-220` 校验） |
| `scanVerdict` | `SAFE`/`CAUTION`/`DANGEROUS` | `String?` | 只有这三个值会被回显（`SCAN_VERDICTS`，`SkillDraftServiceImpl.kt:592`） |
| `upstreamFindingCount` | **必带**，缺省 0 | `Int` | DTO 有非空默认值（`SkillDraftResponse.kt:33`） |
| `sourceSessionId` | string | `String?` | |
| `agentId` | 可缺 | `Int64?` | |
| `createTime` / `updateTime` | `yyyy-MM-dd HH:mm:ss` 字符串 | `String?` | 服务端格式化（`harnax-admin/src/main/resources/application.yml:23`） |
| `reviewedBy` / `reviewedAt` / `rejectReason` | 可缺 | `String?` | 已决才有 |

队列行**不带正文**（`SkillDraftResponse.kt:8-14`）：正文只在详情里。

### `SkillDraftDetail`（`SkillDraftResponse.kt:71-128`）

行字段之外增加：`skillmd: String?`、`resources: [String: String]?`（**这里是对象 map，不是 `SkillItem.resources` 那种 JSON 字符串**，`:87-88`）、`scripts: [SkillDraftScript]?`、`scanFindings: [String]?`（上游报的，只展示）、`localFindings: [String]?`（harnax 自己扫的，**非空就意味着批准会存成禁用**，`:99-100`）、`contentDigest: String`（必带，批准要原样回传）、`history: [SkillDraftHistoryItem]?`。

`SkillDraftScript` = `relPath` / `headPreview` / `totalLines` / `sha256`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillDraftCodec.kt:51-56,108-109`）。
`SkillDraftHistoryItem` = `action`（`PROPOSE`/`APPROVE`/`REJECT`）/ `actor` / `detail` / `createTime`（`SkillDraftResponse.kt:134-147`）。

### `SkillDraftDecision`（`SkillDraftDecisionResponse.kt:19-49`）

`outcome` 用枚举并**带 `.unknown(raw: String)` 兜底**——webui 用的是字面量联合（`harnax-webui/src/typings.d.ts:647`），后端加第六个值它会静默走 default 分支，iOS 不该更脆。其余字段全可选：`skillId` / `skillStatus`（1 启用、0 被内容扫描按住）/ `promotedName` / `findings: [String]` / `reason`（**不是契约，文案按 outcome 本地化**，`:35-36`）/ `currentDigest` / `reviewedBy` / `reviewedAt` / `rejectReason`。

### 请求体

`SkillDraftApprovePayload`：`expectedDigest`（必带）+ `conflictResolution`（`"replace"`|`"rename"`，**首次批准不发**，`SkillDraftApproveRequest.kt:31-35`）+ `newName`（只有 rename 才发，服务端 trim）。三个键都用 `encodeIfPresent`，nil 不落键。
`SkillDraftRejectPayload`：`reason`。

### 三处既有 DTO 增列

- `Contract/AgentSummary.swift`：`skillSelfWrite: Int?` + `isSelfWriting: Bool { skillSelfWrite == 1 }`（后端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:58`）。
- `Contract/AgentSaveDraft.swift`：`skillSelfWrite: Int?`，`encodeIfPresent`。nil=不动，开关拨到某档时显式写 0/1（后端 `AgentCreateRequest.kt:52` / `AgentUpdateRequest.kt:43` 都是「缺字段即保持原值」）。
- `Contract/SkillItem.swift`：`origin: String?` / `originRef: String?`（后端 `SkillResponse.kt:43,45`）+ `isAgentPromoted: Bool { origin == "agent_promoted" }`，常量取 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt:13`。

## 4. 门面与传输

- `Contract/SkillDraftCataloging.swift`（新）：四个方法 `page(status:name:num:size:)`、`detail(id:)`、`approve(id:payload:)`、`reject(id:payload:)`，返回 `Result<_, APIError>`。
- `SkillDraftEndpoint.swift`（新，internal，仿 `SkillEndpoint.swift`）：`status` **一律显式发**（服务端缺省也是 PENDING，webui 也显式发，`harnax-webui/src/services/ant-design-pro/skillDraft.ts:6-8`）；`name` 先 trim、空串则整条参数省略；`pageSize` 上限 1000（`SkillDraftServiceImpl.kt:614`）。
- `SkillDraftClient.swift`（新）：`extension AdminClient: SkillDraftCataloging`。
- `Support/HarnaxDependencies.swift`：加 `drafts: (any SkillDraftCataloging)`，`live()` 里绑同一个 `admin`，位置紧邻 `skills`。

## 5. 界面

新目录 `Sources/HarnaxFeatures/SkillDrafts/`：`SkillDraftListView.swift` / `SkillDraftListViewModel.swift` / `SkillDraftDetailView.swift` / `SkillDraftDetailViewModel.swift` / `SkillDraftPresentation.swift` / `SkillDraftEntryRow.swift`。

### 5.1 会话域入口

`Chat/SessionListView.swift` 把入口行挂在 `content` 的相态**之外**（`content` 的顶部 `VStack`，`phases` 在其下），文案「技能草稿」，右侧待审计数——它导航的是租户级队列、读的是草稿，挂进列表就会跟着会话空态一起消失。两级导航都是 value push：入口行 push `SkillDraftQueueRoute`，`Chat/ChatTab.swift` 注册这个目的（它的 KDoc 就写着「只有目的住在这里」）；队列页内部再 push `SkillDraftRef(id: Int64)` 到审核详情，**行没有 id 就不给链接**，与会话行没有业务键就不给开的既有规则一致。队列不是叶子，所以它由状态呈现（`navigationDestination(item:)`/`(isPresented:)`）会被更深的 push 重复推一次——`SkillNavigationTests.testNoDomainPresentsAScreenThatPushesDeeper` 把这条形状钉成闸门。

装配缺失即撤入口：`drafts` 门面为 nil 时这一行不渲染，不给一个注定失败的按钮。计数读失败**只隐藏数字，不撤行**（入口是导航，不是计数）。计数随会话列表的下拉刷新一起重取。

### 5.2 队列页

数据：`PagedState<SkillDraftRow>` + 触底 `loadMore` + `refreshable`（`Contract/PagedState.swift`）。筛选：三档分段 `PENDING`/`APPROVED`/`REJECTED`，**没有「全部」档**，初值 PENDING（`harnax-webui/src/pages/skill/drafts.tsx:26,193-205`）。搜索：提交式，不用防抖，Enter/清除才发请求，值 trim 后进 `name`（`drafts.tsx:206-215`）。分页 20（`drafts.tsx:30-31`）。

行内容照 webui 六列的语义压成两行卡：主标题 `name`；副行 `proposed`（`createTime`，`yyyy-MM-dd HH:mm`）· `last patched`（`updateTime`）· 上报命中数 + `scanVerdict` 标签 · 状态标签（`PENDING` 橙 / `APPROVED` 绿 / `REJECTED` 红，`drafts.tsx:10-14`）· `Decided by`（`reviewedBy` + `reviewedAt` 显示 `MM-DD HH:mm`，`drafts.tsx:148-156`）。

**「已打补丁」标签谓词逐字照抄**：`updateTime` 严格晚于 `createTime` 才标，时间相等不标，`createTime` 缺失不标（`drafts.tsx:102`）。这条判据写成 Core 里的纯函数以便单测。

其余条件渲染：待审计数标签只在 `status == .pending && total > 0` 时出现（`drafts.tsx:178-182`）；空态文案按 `status == .pending` 分两句（`drafts.tsx:230-233`）；列表底部一行队列说明（`drafts.tsx:250-255`）。

### 5.3 详情页

骨架：顶部摘要卡（name / proposed / description（空则占位符）/ last patched）+ 状态标签 + 操作区；下面六个 Tab 的 `TabView`：正文（`skillmd`，Markdown 走 `HXMarkdownText`）· 文件（`resources` 的 path→content，空则空态）· 脚本（`relPath` / 行数 / `sha256` 前 16 位 + 可复制全文，展开看 `headPreview`；说明这组数由 harnax 对落库字节现算，不是沙箱报的，`draftDetail.tsx:559-605`）· 内容扫描（两份清单并置，见下）· 来源（`sourceSessionId` 可复制、`agentId`、`contentDigest`、说明「来源是上下文不是条件」）· 轨迹（`history`，action/actor/when/note，`draftDetail.tsx:709-725`）。

**两份扫描不可混为一谈**：上卡是 harnax 内容扫描（`localFindings`），非空即红、并标注「这一份决定启用与否」，空则「无命中，批准后将启用」；下卡是沙箱上报（`scanVerdict` + `scanFindings`），非空即橙、并标注「仅展示，永远不会因此把技能存成禁用」（`draftDetail.tsx:607-673`）。

批准/驳回两个按钮**只在 `status == PENDING` 时出现**（`draftDetail.tsx:353-363`）。已决的草稿显示一条已决提示，REJECTED 时把 `rejectReason` 作为副文案（`draftDetail.tsx:390-415`）。PENDING 时显示 digest 前 12 位（`draftDetail.tsx:399`）。

### 5.4 批准流与六条 outcome

发请求前先取当前 `contentDigest`；取不到就报错并重读，不发请求（`draftDetail.tsx:222-232`）。二次确认后才发 `{expectedDigest}`。`expectedDigest` 每次重读都刷新（`draftDetail.tsx:70-91`）。

| outcome | 界面处置 | 是否重读 | 锚点 |
|---|---|---|---|
| `PROMOTED` + `skillStatus == 1` | 成功提示「{name} 已成为技能并启用」，给「打开技能」 | 是 | `draftDetail.tsx:99-127` |
| `PROMOTED` + 其他（0 / 缺） | 警告提示「已存为 {name} 但被按住：内容扫描命中 {n} 条」，`n = findings.count` | 是 | 同上，判据是**严格 == 1** |
| `DRAFT_CHANGED` | 警告 + 可复制 `currentDigest` 前 16 位，提示「草稿在你加载后被改过，请重读后批准你真看到的内容」 | 是 | `draftDetail.tsx:156-176` |
| `ALREADY_REVIEWED` | 信息框：谁（缺省 `-`）在何时（`yyyy-MM-dd HH:mm`，缺省 `-`）定的，有 `rejectReason` 才附理由 | 是 | `draftDetail.tsx:129-146` |
| `NAME_TAKEN` | 打开冲突框，显示的 name 是**这次请求要落的那个名字**（rename 时即 `newName`），不是草稿名 | **否，早退** | `draftDetail.tsx:179-184` |
| `REJECTED`（驳回成功） | 成功提示 | 是 | `draftDetail.tsx:154` |
| 未知 outcome | 错误提示，兜底用 `reason`，再兜底「审核未生效」 | 是 | `draftDetail.tsx:185-189` |
| `code == 409` | 警告「名字在审批期间被占用，草稿仍待审」 | 是 | `draftDetail.tsx:200-211` |

「打开技能」跳已有技能详情（`Skills/SkillDetailView.swift`），依赖 `HarnaxDependencies.skills.skill(id:)`；拿不到 skills 门面时这句提示不带跳转。

### 5.5 冲突框

`NAME_TAKEN` 才开。**预选 `rename`**（`draftDetail.tsx:511`），选项顺序 rename 在前、replace 在后。`newName` 输入框只在选中 rename 时出现，占位符是草稿名，规则：必填、非空白、长度 ≤100；校验时不 trim、提交时 trim（`draftDetail.tsx:524-551,264-273`）。有 `skillId` 时给「查看占用该名的技能」入口（`draftDetail.tsx:501-510`）。**取消时关框 + 清表单 + 重读**（`draftDetail.tsx:485-489`）——重读是为了拿到可能已变的 digest。

### 5.6 驳回流

理由必填，校验判据逐字为：trim 后为空即拒；trim 后长度 > 512 即拒（等号成立即合法，512 字符合法）。提交发 trim 后的值（`draftDetail.tsx:245-262`、`SkillDraftServiceImpl.kt:393-396`）。webui 的输入框上限是 513 个字符，好让用户打出第 513 个字符从而看见校验错（`draftDetail.tsx:474`）；iOS 用不限长输入 + 同一判据的校验，**判据等价、不设第二套规则**。附一行说明「这是智能体再次提交同名技能时能看到的原话」。

### 5.7 跨语言计数判据（本轮最易错的一处）

服务端 `String.length` 是 UTF-16 码元数，webui 的 JS `.length` 也是 UTF-16；Swift 的 `String.count` 是**字素簇**数。emoji 在前者是 2、在后者是 1——按 `.count` 判会把服务端会拒的输入放过去。iOS 侧所有镜像长度（驳回理由 512、改名 100）一律用 `utf16.count`，并落在 Core 的纯函数里由单测钉住。

## 6. 本地化

新键全部走 `skill.` 前缀——`skill` 已在 `Tests/HarnaxKitTests/LocalizationKeyTests.swift:12` 的命名空间闸内，无需改那张表。两份 `Localizable.strings`（各 1178 条）同一次改动里加齐，键集必须逐字相同、占位符种类与个数必须一致（`LocalizationKeyTests.swift:27-45` 两条测试各管一头）。

键分组（`skill.draft.*` 为主，另加 `skill.origin.agent` / `skill.origin.human`）：入口与标题、三档状态名、六个列名与两个提示、四个 Tab 名、批准确认两条、五种 outcome 的标题与正文、promoted 的启用/按住两句、冲突框五条（选项两个 + 提示 + 校验 + 查看占用者）、驳回三条（标题/提示/超长）、来源区四条、扫描区六条、脚本区两条、轨迹区四条、错误兜底两条、队列说明与两种空态。带参数的键必须把个数写清：待审计数 `%d`、命中条数 `%d`、姓名 `%@`、时间 `%@`、会话 id `%@`、digest `%@`。

注意命名空间词同时也是 SF Symbol 前缀的雷区（`LocalizationKeyTests.swift:7-9`）：图标名不能用 `skill.` 开头的符号串。

## 7. 测试矩阵

- `Tests/HarnaxCoreTests/`：`isPatched`（相等不标 / 缺 createTime 不标 / 晚于才标）、`utf16` 长度判据（512 合法、513 拒、emoji 串与服务端同判）、digest 截断 12/16、`origin == "agent_promoted"` 判定、`skillStatus` 严格 == 1、状态与 verdict 的颜色/键映射。
- `Tests/HarnaxAPITests/`：endpoint 构造（路径无 `/page`、`status` 必发、`name` trim 且空则省略、分页参数）、两个 payload 的编码形状（首次批准恰好一个键、rename 三键、replace 两键且无 `newName`）、解码夹具（admin 丢 null 键的形状、`resources` 是对象、五种 outcome 各一份、第六个未知 outcome 走 `.unknown`）。
- `Tests/HarnaxFeaturesTests/`：两个 VM 用 fake 门面。列表——筛选切换重置页码、搜索提交、触底翻页、pending 标签的两条件；详情——六个 outcome 的状态迁移（含 `NAME_TAKEN` **不重读**、其余都重读）、409 分支、无 digest 时不发请求、冲突框取消会重读、驳回 trim 值入参。
- 门禁：worktree 内 `swift build --build-tests` + `swift test`（宿主 macOS，无外部依赖）。基线 2092 测试全绿，任何新增都必须保持全绿。

## 8. 记账（本轮不做或做不了）

- 本会话级计数与提名：`sessionId` 过滤参数已在服务端交付，读它的是会话页那块本会话技能列表（`02-session-chat.md`），入口行与队列页仍按租户全量。
- 无推送、无轮询：新草稿要下拉刷新才反映。webui 同样需要手动刷新，故不算偏离，但也不会更即时。
- `EXPIRED` 不上筛选项：后端 `STATUSES` 不含它，按它筛会被拒（`SkillDraftServiceImpl.kt:601`，校验在 `:219-220`）。
- 详情正文渲染复用 `HXMarkdownText`，其离线解析器对表格/代码块的覆盖不及 webui 的 `ReactMarkdown + remarkGfm`；这是既有约束，不在本轮扩。
