# 自写技能的会话内可用与审核上升 · 设计规格

- 日期：2026-10-08
- 状态：已批准，实现中
- 范围：`harnax-agent/harnax-harness-core`、`harnax-agent/harnax-agent-service`、`harnax-session-router`、`harnax-admin`、`harnax-webui`、`harnax-ios`、`prod_doc`
- 上游引用一律是 agentscope 2.0.4 的类名 + 行号（`io.agentscope.harness.agent.*`），取证自 sources jar 解包件

## 0. 要解决的问题

一期把「agent 自己写技能 → 进审核队列 → 审核落成平台技能」接通了，但中间缺一块：**这份技能在提议它的那个会话里也用不上**。模型写完 `_drafts/`，要等一个人类在审核页批准、等平台把技能绑给这个 agent、等装配快照过期，才可能在**另一个**会话里第一次生效。提议者本人拿不到自己刚写的东西，"自我进化"在会话里就没有反馈。

同时队列这一头在真机部署上还是空的：`SkillDraftSubmitMiddleware.onAgent` 在答完之后再读工作区（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SkillDraftSubmitMiddleware.kt:50-51`），而此时 `SandboxLifecycleMiddleware` 已经把沙箱从文件系统上解绑，读盘一律 `No active sandbox`，上报 0 条。

目标：会话内先能用（一次人工确认），审核上升链路不变，两处都不新增表、不新增服务。

## 1. 决策清单

| # | 决策 | 依据 |
|---|---|---|
| D1 | 「本会话可用」是一份**人工确认**之后的动作，不由写完自动生效 | 2026-10-08 拍板 |
| D2 | 可用区是一棵**新目录** `harnax-skill-staging/session-enabled/`，不复用上游的 `promoted/` | harnax 调了 `disableDefaultWorkspaceSkills()`（`.../HarnessAgentBuilder.kt:261`），`HarnessAgent:2803-2807` 于是在找不到只读 `WorkspaceSkillRepository` 时新建一枚 writable 的并 **append 到列表尾 = 最高优先级**；内容放 `promoted/` 必然压过运维交付的技能，撞 `HarnessAgentBuilder.kt:258-260` 立的不变量 |
| D3 | 可用区仓库挂在 Layer 2 的 `InMemorySkillRepository` **之前**，同名时交付技能胜 | `HarnessAgentBuilderSupport.composeSkillRepositories:877-916` 按加入顺序排，`HarnessSkillMiddleware.mergeRepositories:349-357` 后者胜；`builder.skillRepository(...)` 落在 Layer 2 |
| D4 | 启用是**复制**，`_drafts/<name>/` 原样留着 | 「留在草稿目录，直到会话结束」逐字成立；且启用即快照 —— 会话里用的是确认那一刻的那份，审核看的是 `_drafts` 的最新那份 |
| D5 | 启用不走上游 `promoteSkill`/`SkillPromotionGate` | 那条链唯一出口是 gate 判 `Approve`，而 `AdminBackedPromotionGate` 按一期裁定永不 Approve（`.../AdminBackedPromotionGate.kt:85-100` 三支 `when` 分别是 Queued/Refused/Unavailable，没有 Approve 支）。会话内可用 ≠ 审核通过，reviewer 与爆炸半径都不同 |
| D6 | 上报这一跳改为「容器句柄读盘 + 上游扫描器 + Admin intake」直连，不再借 `promoteSkill` 走 gate | 答完之后没有 call 上下文，`promoteSkill` 里两处读盘（`SkillPromoter:96-107` 载草稿、gate 的 `files.read`，`.../AdminBackedPromotionGate.kt:68`）都要绑定的沙箱。句柄通道在本仓已被验证可用：`HarnessAgentWrapper.kt:1057` 就在 call 外用 `KeepAliveSandboxManager.getSandbox(sessionId)` 读文件 |
| D7 | 会话隔离**不是新加的规则**，是容器边界 | 容器 `agentscope-sandbox-<sessionId>`（`.../sandbox/KeepAliveSandboxManager.kt:328`）、`.skills-cache` 作用域 `IsolationScope.SESSION`（`.../config/SandboxConfig.kt:37`）、快照键 `snapshotSpec.build(sessionId)`（`KeepAliveSandboxManager.kt:254`）。A 会话启用什么，B 会话看不见，也不需要谁去撤销 |
| D8 | 批准后不通知运行侧、不自动绑回提议它的 agent | 2026-10-08 拍板。收敛由 D3 的名次自动完成（见 §6） |
| D9 | 本会话可用技能上限 10 条 | 每条都进系统提示；会话内技能堆多了会把运维交付的那份挤下去 |
| D10 | 可用区技能不参与 `TenantSkillVisibilityFilter` 的 CANARY/ALLOW_LIST | 策略表只按 Admin 交付的 name 下发，会话提名没有策略行，`TenantSkillVisibilityFilter.kt:76` 无 policy 即放行 |
| D11 | 启用不写平台审计日志，只在 agent-service 打 info 带操作者 | 容器与快照随会话销毁，平台侧留痕已由 `skill_draft` 行与 `approve` 的 `skill_review_log` 承担 |
| D12 | 提名不新增工具，`propose_skill` + 答完上报就是提名 | `SkillDraftResponse.kt:36` 已带 `sourceSessionId`，Admin 那行 PENDING 就是「待本会话启用」的状态位，不需要新列 |

## 2. 状态模型：三份状态，零新表

| 状态 | 落点 | 谁写 | 谁读 | 寿命 |
|---|---|---|---|---|
| 草稿（提名） | 容器内 `harnax-skill-staging/_drafts/<name>/` | 上游 `skill_manage`/`propose_skill`（`SkillManageTool:335`，写完扫，DANGEROUS 回滚归档） | 上报通道 → Admin | 到容器销毁 |
| 待审队列行 | admin 库 `skill_draft(PENDING, source_session_id)` | `SkillDraftServiceImpl.kt:103-118`，按 (tenant, PENDING, name) 合并 | 审核页 / 会话页 | 到人工决定 |
| 本会话可用 | 容器内 `harnax-skill-staging/session-enabled/<name>/` | 启用动作（§4） | 技能目录册（§3） | 到容器销毁 |

平台技能仍是唯一权威：`skill` 表 + `agent_skill_binding` + `InMemorySkillRepository` 下发 + `SandboxSkillProjector` 投影 `skills/`。本设计不碰这四样。

## 3. 装载：可用区进入技能目录册

- 新常量 `SkillDraftStaging.SESSION_ENABLED_DIR = "harnax-skill-staging/session-enabled"`，并纳入 `SkillDraftStaging.kt:93-108` 那条与 `SandboxSkillProjector.SKILLS_DIR` 不重叠的启动期断言。
- 新类 `SessionEnabledSkillRepository`（harnax 自有，实现 `RuntimeContextSkillRepository`）。`HarnessSkillMiddleware:355-357` 对这类仓库调 `getAllSkills(ctx)`，它用手上的 `WorkspaceDraftFilesReader`（`bindFilesystem()` 在 `build()` 之后装入，与 `SkillDraftStaging.bind()` 同一个晚绑定顺序）带着 ctx 读盘，因此落在绑定了沙箱的 call 内。SKILL.md 用上游 `SkillUtil` 解析，和 `WorkspaceSkillRepository` 同一条发现规则（glob `SKILL.md`，只在注册时读正文，资源按需取）。
- 在 `HarnessAgentBuilder.kt:261-263` 的 skills 段之前，仅当 selfWrite 装配（`skillStaging != null`）时 `builder.skillRepository(sessionEnabledRepo)`。Layer 2 顺序 `[sessionEnabled, inMemory]`。
- 可见时机：目录册每次 call 重算（`HarnessSkillMiddleware:322`，harnax 未设 `disableDynamicSkills`，走 `HarnessAgent:2909-2916` 的非 frozen 分支），无缓存。所以「启用后下一轮就能用」这一条腿**一行重开沙箱的代码都不需要** —— 它读盘发生在 call 内。
- `SkillDraftStaging` 的晚绑定形状不变：`bind()` 之前读为空（`SkillDraftStaging.kt:44-53`），装配期拿不到 `AbstractFilesystem`（`HarnessAgent:2442-2447` 在 `build()` 内部才解析出来），这是它存在的理由。

## 4. 启用：一条 call 外的动作

`SessionSkillStore`（新）是容器文件的唯一出入口，句柄只有一条取法：

- `filesystemFor(sessionId)`：`SandboxHandleProvider.handle(sessionId)` 现取一次活句柄，供**启用**与**上报**用（都在 call 外）。不依赖 wrapper 还活着，所以 30 分钟 TTL 过期或 agent-service 重启后仍能启用；句柄不在就是 `NoSandbox`，不猜也不重建。`enable` 里把这一句柄解一次并从头用到尾，中途不再重解。
- call 内的目录册读**不**走这条入口：`SessionEnabledSkillRepository` 用 `bindFilesystem()` 装入的工作区文件系统读盘（§3），它与 `SkillDraftStaging` 同一条晚绑定 —— 装配期拿不到 `AbstractFilesystem`，`bind()` 之后才有（`SkillDraftStaging.kt:44-53` 立的同一个顺序）。

`enableInSession(sessionId, name, actor)` 三步：

1. 读 `_drafts/<name>/` 的 `SKILL.md` 与四个支持目录（沿用 `SkillDraftFilesReader.kt:58-130` 的同一套 glob 语义：`scripts`/`references`/`templates`/`assets`，只取一层 `<name>/SKILL.md`）。源不在就回「已启用或已不在」，不建第二份。
2. 复跑 `SkillSecurityScanner.scan(name, md, resources)`，`shouldAllow(AGENT_CREATED, verdict)` 为假即拒，并把 verdict + findings 回给调用方（`SkillPromoter:102-113` 同一判据，人确认时看的就是它）。
3. 整棵**复制**进 `session-enabled/<name>/`，已存在则覆盖 = 「重新启用一次，拿最新快照」。覆盖前判一次条目数，超过 10 条拒（D9）。

## 5. 上报：把答完之后那一跳接上（D6）

`SkillDraftSubmitMiddleware` 改为：`onAgent` 答完 → 有界弹性线程上 `SessionSkillStore.listDraftNames(sessionId)` 列 `_drafts` → 逐条读正文与支持文件 → `SkillSecurityScanner.scan` → `SkillDraftAdaptor.submit(SkillDraftProposal)` → 记 `claim` 窗口（`SkillDraftSubmitMiddleware.kt:98-110` 逻辑不变）。

- 容器不在（被回收、从未起过）：warn 一条并跳过本轮，与现在的读失败姿态一致 —— 扫描失败说不了任何话，下一轮还会问一遍。
- `AdminBackedPromotionGate` **保留**并继续挂在 gate 位上：上游自己触发 promote 时仍然只入队不晋升，它是兜底而不是主路径。
- 重复上报安全：Admin intake 按 (tenant, PENDING, name) 合并（`SkillDraftServiceImpl.kt:103-118`），不会堆第二行。
- 这一跳修好之前，第 3、4 节的「可用」会在队列里看不见对应提案 —— 两者是同一条链的上下游，本轮一起改完。

## 6. 批准之后：不通知、不绑回、名次自动收敛

`approve` 只写 `skill` 表（`SkillDraftPromoter.kt:78-81`：重扫，有命中则 `status=0` 禁用存下；`:84-101`：replace 走原行覆盖）。运行侧不感知。

30 分钟装配快照过期后，Admin 那份经 `InMemorySkillRepository` 提供；按 D3 的名次，**交付那份压掉会话内那份**，本会话下一轮起用的就是审后正文。两个例外：审核把名字改了（rename 到别的 targetName）、或这条技能压根没绑给这个 agent —— 那时会话内那份继续生效到容器销毁。窗口可接受，因为两份正文都只在同一个用户的同一个容器里。

## 7. 接口契约

- `GET /api/admin/skill-drafts` 增 `sessionId` 过滤：`SkillDraftController.kt:55-79` 加一个可选参数，`SkillDraftMapper.xml` 加 `<if>`，租户谓词不动。会话页用它取「本会话的待启用提名」。
- agent-service 两条会话级端点，与 `SandboxWorkspaceController` 同一鉴权形状（`/api/agent/**` internal-only，经 session-router 代理，`SandboxWorkspaceController.kt:22-25`；会话级句柄解析用同一族的 `resolveSandbox(sessionId)`，`:99`）：
  - `GET  /api/agent/session-skills/{sessionId}` → `[{name, description, enabledAt}]`（`SessionSkillController.kt:93` 的 `SessionSkillView`），读 `session-enabled/`；`enabledAt` 取目录 mtime，容器不在回空数组。收窄到三键的理由：目录里没有读者要 verdict/findings —— webui 抽屉与 iOS 面板都只搬 `enabledAt` —— 名字出现在这份目录里本身就是「已启用」，所以应答也不带开关；扫描结论走 enable 那条应答与审计日志，人确认时看的是队列行。
  - `POST /api/agent/session-skills/{sessionId}/{name}/enable` → `{ok, name, verdict, findings, count}`；三类拒因（源不在 / 扫描拒 / 超上限）各带原因。
- 两端经 `harnax-session-router` 的 `AgentProxyController`（`@RequestMapping("/api/router/agent")`，`:28`）暴露给公网，浏览器与 iOS 只走这一条链，`/api/agent/**` 本身不给它们开路由：
  - `GET  /api/router/agent/session-skills/{sessionId}`、`POST /api/router/agent/session-skills/{sessionId}/{name}/enable`，各对应 `SessionRouterService` 一个 `proxy*` 方法 + `AgentServiceClient` 一次转发，与 `/workspace/{sessionId}/files`（`AgentProxyController.kt:198-207` → `SessionRouterService.kt:411-421` → `agentServiceClient.workspaceListFiles`）同一条形状。
  - 归属与租户判据不在新代码里重做：三个只读代理都经 `boundInstance(sessionId)`（`SessionRouterService.kt:509-521`），它第一行就是 `sessionAccessGuard.requireAccessible(sessionId)`，新端点从这条链继承。会话未绑定实例时按只读代理的既有姿态回空，不 reroute 到别的实例（`:500-507` 注释立的理由：别人的箱子里没有这份文件）。

## 8. 界面

- webui 会话页一块「本会话自写技能」抽屉（`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`）：两路读（Admin 的本会话 PENDING 提名 + agent-service 的本会话已启用目录）合成**一张列表**，每行一枚「在本会话启用」，已启用的行灰显并带上启用时间；启用被拒走 antd 的 message toast，按 `code` 分拒因。i18n 中英文各一份 key。
- iOS 会话页同一块是一张独立屏（`harnax-ios/Sources/HarnaxFeatures/Chat/SessionSkillsSheet.swift`）：行形跟随会话页既有 `HXRow` 规范，拒因不弹 toast，而是渲染在行列表**下方**的一段说明——那句话点名的是某一行的技能，读者要还能看着那行。
- 会话内启用与「批准」在界面上必须用不同词：一个是本会话可用，一个是平台技能。

## 9. 边界与失败形状

- 启用要求该会话容器活着。答完之后就再也不回的会话，容器被 reaper 收走，可用区随之消失 —— 这是 D7 的另一面，不做补偿。
- `_drafts` 里同名重复目录：upstream 的 delete 是移进 `.archive/<name>-<ts>/`，`listDraftSkillNames` 只认一层，所以归档不会被当草稿（`SkillDraftFilesReader.kt:47-57` 注释立的正是这条）。
- 一个会话同时是团队主管会话时可用区同样存在（主管沙箱归属是另一个域的记账，本设计不扩到那里）。
- 启用后的技能不进 `skill_usage` 埋点：那条链按 Admin 的 skill id 计，会话提名没有 id。

## 10. 验证

- 单测（harness-core）：Layer 2 名次（`skillRepositories` 里可用区排在交付仓库之前）；`enable` 的四支拒因各一条（源不在／扫描拒／超上限／容器拒收复制）；复制那条命令只替换 `session-enabled/` 那一侧，不碰 `_drafts`；`SessionSkillStore` 在句柄为 null 时回空且不抛；目录条目数上限；可用区目录名与 `skills/` 不重叠的启动期断言。
- IT（admin）：`SkillDraftFlowIT` 增一支 —— `sessionId` 过滤只回本租户本会话的行，邻居租户拿同一个 sessionId 仍取不到我们的行。（同 (tenant, name) 重复上报合并成一行已由 `SkillDraftServiceImplTest` 的 `an open draft of the same name is merged` 守住。）
- 上报与装载（harness-core 单测）：答完之后那一跳按 `SessionSkillStore` 读盘、复扫、交 `SkillDraftAdaptor`，DANGEROUS 不入队；读不到的草稿留到下一轮；`SessionEnabledSkillRepository.getAllSkills(ctx)` 含已启用那条，且未绑定文件系统时为空。合并后的那份目录册在本仓取不到——上游 `HarnessSkillMiddleware.skillsForCall`（`:321`）与 `mergeRepositories`（`:349`）都是 `private`，且 harness-core 无 IT 装置，因此名次与读取分两处断言，「交付同名技能压住会话内那份」的真实结果在真栈观测。
- 代理链（session-router 单测）：两条新代理各一条 —— 转发出对应实例，且会话未绑定时读回空、启用回 410，都不改绑。
- 真栈：清库与存量各跑一遍「测试会话2」路径 —— 队列出现行、会话页出现可点行、点完下一轮模型真的用上了它；同名交付技能存在时用的是审后正文。
- 门禁：`mvn -pl harnax-agent/harnax-harness-core -am test`、admin 侧改动跑 admin 门禁、session-router 侧改动跑其门禁、webui 用 `max build` + 逐文件 lint、iOS `swift build`/`swift test` 每次全新 scratch path。

## 11. 本轮不做

批准自动绑回、批准时通知运行侧归档、平台审计里记会话级启用、`promoted/` 语义改动、团队主管会话的可用区归属。
