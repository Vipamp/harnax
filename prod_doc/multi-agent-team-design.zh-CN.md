# Harnax 多智能体团队设计（中文）

> 英文版本见 [multi-agent-team-design.en-US.md](./multi-agent-team-design.en-US.md)。
>
> 本文描述当前实现：团队配置模型、页面流程、装配链路、沙箱、产物交接、事件与确认。每条结论都对应仓库里的 Kotlin 或 TypeScript 代码，末尾的「关键文件索引」给出入口。

## 1. 目标与复用边界

多智能体协作（Multi-agent）的产品形态是：用户组一个团队，向主管提出目标，主管把具体工作委派给成员，验收成员回报的结果并汇总答复。

实现方式是把团队做成一份独立配置，并最大化复用现有 Agent 基础设施：

- 团队只自带主管这一份配置：模型、系统提示词、技能。成员的能力仍然完整保存在它自己的 `agent` 行里，组队不复制第二套 Tool、MCP、CLI 或凭证。
- 配置下发、模型与工具装配、会话路由、沙箱管理、MinIO 对象存储都沿用现有实现，团队侧只增加自己的解析分支和工具。
- 主管与成员跑在同一个 agent-service 实例内，成员使用独立会话状态与按需创建的独立沙箱，主管不创建执行沙箱。
- 没有引入工作流引擎、远端 Agent 服务发现，也没有为团队另建一套事件通道。
- 普通 Agent 的配置方式与独立使用方式不受影响：团队的成员限制只作用于团队装配出的那一次运行实例。

## 2. 产品边界

| 主题 | 结论 |
|------|------|
| Team 这个对象 | Team 是独立的配置对象，既不是 `agent` 表的一种类型，也不是某个 Agent 上的开关。 |
| 主管的来源 | 主管是 Team 自身携带的一份配置：`team.system_prompt`、`team.model_id` 加团队级技能绑定。`agent` 表里没有代表主管的行，它只有两种身份：可独立对话的 Agent、作为团队成员的 Agent。 |
| 团队侧的绑定 | 团队侧唯一的绑定表是 `team_skill_binding`。Tool、MCP、CLI 一律不在团队上配置，主管的这三项在装配时恒为空。 |
| 成员的来源 | 成员引用现有 Agent，同一个 Agent 可以进多个团队，也可以单独对话。成员能力按它自己的配置装载。 |
| 分工 | 主管只拆解、委派、验收、重派、汇总；具体执行（查资料、跑代码、处理文件、生成报告）由成员完成。 |
| 团队角色的作用范围 | 团队角色只影响本次装配出的实例，`agent` 行的持久配置不变。 |
| 沙箱与产物 | 成员在独立沙箱里执行，文件只通过 MinIO 产物交接，成员之间不共享工作区。主管无沙箱、无工作区。 |
| 确认的归属 | 成员执行过程在会话页可见，需要确认的动作交给用户，用户决定回到发起它的成员运行。 |
| 委派形态 | 委派是前台、顺序、单层的：主管的一次 `team_delegate` 调用会阻塞到成员返回，一个成员同时只跑一个任务。 |

顺序委派是当前装配与运行时共同保证的不变量：成员运行由 `TeamOrchestrator` 按成员标识占位（`busyMembers`），同一成员的第二次并发委派会被直接拒绝并返回说明文本，模型可以据此改派或等待。

## 3. 对象、数据与会话

### 3.1 三类对象

| 对象 | 承载 | 不承载 |
|------|------|--------|
| Agent | 模型、提示词、Tool、MCP、Skill、CLI 配置 | 不代表任何团队的主管；加入团队时不复制一份能力配置 |
| Team | 名称、说明、主管的系统提示词与模型、团队启停与归属、主管技能绑定 | Tool、MCP、CLI；指向某个 Agent 作为主管的引用 |
| Team Member | 团队引用、成员 Agent 引用、该成员在这个团队里的分工说明 | 成员的模型、工具、凭证、技能 |

成员的分工说明（`delegation_description`）默认取该 Agent 自己的描述，允许按团队修改，且不回写 `agent.description`。

### 3.2 表与字段

建表语句在 `harnax-admin/src/main/resources/db/migration/`，当前形状：

| 落点 | 内容 |
|------|------|
| `team` | `id`、`tenant_id`、`name`、`description`、`system_prompt`、`model_id`、`status`、`is_public`、`creator`、`active`、时间戳 |
| `team_skill_binding` | `team_id` + `skill_id`，组合唯一。没有环境值列：技能级环境变量在 Agent 侧同样没有消费者 |
| `team_member` | `team_id` + `member_agent_id`（组合唯一）、`delegation_description` |
| `team_artifact` | `file_id`（UUID，唯一）、`tenant_id`、`session_id`（根团队会话）、`team_id`、`member_agent_id`、`child_session_id`、`file_name`、`mime_type`、`size_bytes`、`object_key` |
| `session` | `agent_id` 可空；团队会话 `agent_id` 为 NULL、`team_id` 非空 |

`team.name` 的唯一性由服务层按租户校验（`TeamMapper.selectByName`），表上没有唯一键：行是逻辑删除（`active = 0`），数据库唯一键会让一个已删团队的名字永远无法复用。

`team_artifact` 的 `team_id` 与 `child_session_id` 由发布时写入，读取归属与授权一律用 `tenant_id` + `session_id`，这两列留作排查线索。

一次团队运行的归属取自各自的 spec：主管那份的 `AgentSpec.attributableAgentId` 为空（`id` 为团队主管哨兵值 0，属性把它读成 null），成员那份是成员自己的 `agent.id`。因此 `token_stats`、`tool_call_log`、`process_log` 里主管的运行行不带 Agent 归属，成员的运行行按成员归属；`process_log` 的归属在一次装配时写入该实例自己的中间件，成员构建不会改写主管后续运行行的归属。

### 3.3 会话分型与团队入口

会话分两种，判据是 `session.team_id`：

- 非空：团队会话。`agent_id` 为 NULL，名称、说明、系统提示词、owner、模型在建会话时从 `team` 行快照一份，用于页面展示。
- 为空：普通 Agent 会话。

`session_id` 前缀分不出两者：从 Web 发起的团队会话仍然是 `web-` 开头的普通标识。运行时靠 admin 的 `GET /sessions/{sessionId}/team` 判定（读会话行的 `team_id`），再选择解析端点。`chn-`、`task-` 这类会话没有 `session` 行，因此不可能是团队会话。

团队会话按 `team_id` 解析主管配置，客户端无法提交主管身份；`SessionServiceImpl.updateSession` 拒绝给团队会话指定 `agentId`，`team_id` 只在建会话时写入，更新语句从不改写它。

### 3.4 校验、可见性与生命周期

保存团队（`TeamServiceImpl.createTeam` / `updateTeam`）时校验：

- 名称在同租户内唯一。
- 主管模型存在、对本租户可用（本租户自有或 `is_public`）、已启用、类型为 chat。运行侧对此没有第二道闸门：下发只看能否取到模型行，模型被停用不影响已保存的团队继续按它跑；模型行被删除会让主管在构建时因取不到模型配置而失败。
- 至少一个成员、成员 id 非空且不重复；每个成员必须存在、同租户、已启用，且按调用者自己的读权限可见（`is_public` 或本人创建）。能看见团队不等于可以使用其中的私有 Agent。
- 主管技能走与 Agent 侧同一套可选范围与写时校验（`SkillBindingResolver.resolveBindable`）：缺失、停用、内置 CLI 仓库来源、同名冲突都会被拒绝。
- 更新时 `members` 缺省表示不动成员，`skillIds` 为 null 表示不动技能、为空数组表示清空；技能集合先解析成功再落库。

团队可见性是 `is_public OR creator`，与列表同口径：按 id 读、改、删、启停都走 `requireVisibleTeam`，同租户的他人对私有团队一律得到 `Team not found`。

`deleteTeam` 在团队仍有会话（`active = 1`）时拒绝，并把会话标识列出来（最多写 5 个，其余折叠成省略号），必须先逐个删除会话。删除会话时由 `TeamArtifactCleaner` 清理该会话的团队产物。于是「`session.team_id` 还在、团队行已被删」这种状态不可达。

运行时解析（`InternalApiController.resolveTeamSpec`）不做静默降级：团队不存在、被停用、与会话不同租户、成员列表为空、任一成员 Agent 不存在或被停用，整次解析抛错，团队会话不会以一份残缺名册启动。

团队配置变更后，已缓存的运行时实例仍按构建时那份 spec 服务。团队列表的「关联会话」入口列出该团队的会话，配合 `agents/refresh-sessions` 推送 REFRESH 命令，让下一次消息从 admin 重新解析配置并重建主管与各成员。

## 4. 团队页面

### 4.1 团队管理页

`harnax-webui/src/pages/team/index.tsx` 是独立入口，提供分页列表、名称搜索、新建、编辑、启停开关、关联会话查看与删除。行内不提供「开始对话」——团队会话在会话页创建，见「创建团队会话与单 Agent 会话」。

删除走服务层同一判据：团队仍有会话时后端拒绝，前端把拒绝原因（含会话标识）呈现给用户。

### 4.2 两步向导

新建与编辑复用 `TeamWizard.tsx`，两步：

```text
第 1 步 · 基本信息与技能                        [下一步]
团队名称    [研究报告团队]
团队说明    [负责资料收集、分析与报告生成]
系统提示词  [你是本次协作的负责人……]            ← 主管的全部提示词
模型        [qwen3-max]
公开        [开/关]
技能        [资料检索规范] [报告撰写规范]         ← 只有 Skill，无 Tool/MCP/CLI

第 2 步 · 团队成员                              [添加成员]
资料研究员    分工：搜集资料并提供来源
数据分析员    分工：分析数据、提炼结论
报告撰写员    分工：根据资料和结论撰写报告
                                              [上一步] [取消] [保存]
```

要点：

1. 第一步的名称、说明、系统提示词、模型必填，与智能体向导第一步一致；技能选择直接复用 Agent 侧的 `SkillConfigPanel`，同一套来源过滤与前端预检（`findSkillIssue` / `describeConfigIssue`）。
2. 模型下拉只列可用模型。团队的主管模型若已停用、删除或已不是 chat 类型，编辑态会把当前值补成一个禁用项并标注原因，避免保存时只剩一个裸 id。
3. 成员由 `MembersField.tsx` 承载：可选范围是调用者能用的 Agent，分工说明默认带入 Agent 描述，按团队修改。
4. 已失效的成员与技能在编辑态保留显示并标注不可用——运行侧只是不装载它，页面上抹掉等于隐瞒一条坏绑定。
5. 团队向导没有 Tool、MCP、CLI 三类字段：能力边界由配置形态保证，装配守卫只是第二道线。
6. 成员用稳定的服务端 id 标识，显示名称与分工说明供阅读与委派决策参考。

### 4.3 创建团队会话与单 Agent 会话

会话页的「新建会话」（`SettingsModal.tsx`）里，执行者下拉分成两组：Agents 与 Teams，值形如 `agent:12` 或 `team:3`。选团队时提交 `teamId` 且不带 `agentId`，后端在 `SessionServiceImpl.createSession` 校验团队存在、同租户、对本人可见、已启用、至少一个成员，然后建出团队会话。

普通 Agent 会话的创建路径不变，也不存在把已有普通会话中途切成团队的入口。

会话列表与标题栏按 `session.teamId` 分型呈现：团队会话带 Team 标签，工具条上多一个产物入口打开 `TeamArtifactsDrawer`；会话详情（`DetailModal.tsx`）在团队会话上把关联对象一栏标为 Team Lead，主管的名字与说明按团队读，Agent 专属区块不渲染。

### 4.4 团队会话页看到的

会话页只有一条 SSE 流、一个对话主线：

- 主管的一轮回答是一个持续追加的气泡；团队会话上该气泡带主管名字（取自会话快照的名称，仅 `teamId` 非空时显示），用于和卡片里的成员对读。
- 成员的一次运行嵌在主管那一次 `team_delegate` 工具卡内部，不另开并列气泡。
- 确认入口内联在发起它的那个成员运行里。
- 产物清单在抽屉里，可按 `fileId` 下载，`fileId` 可复制以便贴回给主管或成员。

## 5. 主管与成员的能力边界

装配在 `HarnessAgentLauncher.createAgentBase` 一处完成，`TeamRole` 决定分支；角色由运行时传入，配置本身无法把自己说成另一种角色。

| 能力 | 团队主管 | 团队成员 | 普通 Agent |
|------|----------|----------|------------|
| 模型与提示词 | Team 行的模型；`team.system_prompt` 叠加主管角色块（成员名册与工作方式） | 自己的模型与提示词，叠加成员文件块与本次任务简报 | 保持原有行为 |
| 会话开关 | 深度思考、联网、计划、权限模式取会话行 | 自己的模型配置，权限模式取根会话 | 保持原有行为 |
| 业务 Tool | 不装配，且不吃平台必须工具 | 按自己的配置装配（含必须工具与逐方法授权裁剪） | 保持原有行为 |
| 工具元能力 | 关闭（`enableMetaTool` 不下发），运行时不能自行取得工具 | 按自己配置 | 按自己配置 |
| MCP | 不连接（下发的列表恒为空，装配侧另有守卫） | 按自己配置连接，逐用户 OAuth 与 stdio 口径同普通 Agent | 保持原有行为 |
| Skill | 装载技能内容，与普通 Agent 同一条装载路径；技能附带的文件对它不可读不可执行，装载时写日志点名 | 按自己配置装载 | 保持原有行为 |
| CLI 与沙箱镜像 | 无 CLI 配置，沙箱与文件系统一律不装配 | 按自己的 CLI 集合解析镜像并装配沙箱 | 保持原有行为 |
| 文件系统与 shell 工具 | `disableFilesystemTools()` + `disableShellTool()` | 保留 | 保留 |
| 框架自带子代理 | `disableSubagents()` 关闭，避免第二条不受团队管理的委派路径 | 按普通 Agent 装配，可用自己会话与沙箱范围内的子代理 | 保持原有行为 |
| 团队工具 | `team_members`、`team_delegate`、`team_artifacts` | `team_artifact_publish`、`team_artifact_fetch`、`team_artifacts` | 无 |
| 输出文件自动探测 | 探测与存储组件照常装配（它没有工作区可扫） | 不装配：成员的文件只能通过发布动作离开沙箱 | 保持原有行为 |
| 单轮预算 | `harness.team.turn-timeout-seconds` | 同一数值（实际生效的是外层更紧的成员单轮超时） | `harness.turn-timeout-seconds` |

三条团队工具都写进 `PermissionContextState` 的 ALLOW 规则，与 plan、todo 等框架工具同列，因此不会被权限引擎拿去向用户请求确认；需要确认的是成员自己的业务工具。

主管的提示词块（`leadOrchestrationPrompt`）说明名册、委派方式、文件只用 `fileId` 传递、失败如实上报。它是边界的说明，不是边界本身：主管拿不到业务工具、MCP、shell 与沙箱，即使模型无视提示词也没有可执行的东西。

普通 Agent 的「必须工具」规则不因团队改变：主管没有 `agent` 行，也就没有可被收紧或删除的绑定，它的 Tool/MCP/CLI 为空来自 Team 不承载这类配置。

## 6. 配置下发与成员加载

### 6.1 装配链路

```text
Web UI 创建团队会话（只提交 teamId）
    ↓ 根 sessionId 沿现有 router 粘性路由
agent-service: AgentSpecResolver.isTeamSession(sessionId)
    ↓ admin 读 session.team_id 判定，团队会话走 /team-spec，普通会话走 /agent-spec
TeamOrchestrator 与主管一起构建（成员此时不创建）
    ↓ 主管调用 team_delegate
按成员标识懒创建成员运行（子会话 + 该成员自己的模型/Tool/MCP/Skill/CLI/沙箱）
    ↓ 成员事件带来源写进根 SSE，需要确认时挂起等用户
成员文本与已发布产物汇总成委派返回值 → 主管继续或汇总
```

### 6.2 team-spec 下发

`GET /api/internal/team-spec/{sessionId}` 返回 `TeamSpecInfoResponse`：`teamId`、`tenantId`、`teamName`、`lead`（一份完整的 `AgentSpecInfoResponse`）、`members`（每人一份完整 spec，按装配顺序）。

- 主管那份由 `specForTeam` 合成：提示词、模型、租户取自 `team` 行，技能取自 `team_skill_binding`，Tool/MCP/CLI/必须工具三份列表显式传空，`agentId` 为 0、`agentName` 为团队名。`buildAgentSpecResponse` 的四类绑定读取是参数，团队与普通 Agent 共用同一条序列化路径。
- 成员那份由 `specForAgent` 生成，与普通会话下发同一形状：模型、工具、MCP、技能、CLI 全量，不依赖主管侧的任何适配器去解释另一个 Agent 的绑定。
- `/agent-spec` 遇到 `team_id` 非空的会话会直接抛错，`/team-spec` 拒绝非团队会话；两个端点各认各的入口，猜不出来的空间被消掉。
- 成员运行产生的子会话标识从不送去 admin：它没有 `session` 行，任何按外部会话前缀解析的入口都不认识它，成员配置一律由根会话已授权的团队 spec 派生。

`AgentSpecResolver.resolveTeam` 把这份响应换算成 `TeamRuntimeSpec`：主管的 `AgentSpec` / `ChatSpec` 与每个成员的 `TeamMemberSpec`（含 `specInfo`，即该成员自己那份 admin 响应）。模型、MCP、工具、技能四类适配器在构建期间读 `AgentSpecContextHolder`（同步阶段的 ThreadLocal），因此每次构建前先装入自己的 spec、构建完清除：主管构建装 `leadSpecInfo`，成员工厂闭包装 `member.specInfo`。

### 6.3 委派机制与成员装配

成员不是 AgentScope 的原生子代理。SDK 的内置子代理路径继承父 toolkit 并总会追加一个通用成员，团队需要的是成员带着自己的模型、工具、MCP、技能、CLI 与沙箱运行，因此成员由 `launcher.createTeamMember` 按普通 Agent 的同一套装配构建，主管一侧再显式关掉框架子代理，避免留下第二条不受团队管理的委派路径。

主管侧的编排能力是一个 ToolBox（`TeamLeadToolBox`）而不是提示词约定：

- `team_members` 返回名册文本。
- `team_delegate(member_agent_id, task, file_ids)` 执行一次委派并阻塞到成员返回；返回值是给主管读的文本。成员 id 不在名册、任务为空、成员正忙、预算用满、团队已停止都返回可读的拒绝文本，模型可以据此改派，而不是让整轮运行抛异常结束。
- `team_artifacts` 列出本会话已发布产物。

成员侧的 `TeamMemberToolBox` 绑定成员 id，运行归属由 `orchestrator.currentRunOf(memberAgentId)` 解析：委派是前台的，一个成员同时至多一个未结束运行，因此不需要框架替携带团队身份；工具在委派之外被触达时返回「当前不在任何委派任务中」。

委派的任务简报（`buildTaskBrief`）带团队名、该成员在本团队的分工、任务原文、要处理的 `fileId` 列表和交付要求。成员看不到用户与根会话历史，这是简报要写清目标与交付标准的原因。

### 6.4 子会话标识、懒创建与复用

成员子会话标识由 `TeamSessions` 统一拼写：`team-<rootSessionId>-m<memberAgentId>`。它同时是状态存储的键、沙箱容器的键与工作区路径段，因此创建它的运行时与回放它的历史读取必须共用这一处定义；标识里没有斜杠，也不取自事件来源字符串。

- 首次委派某成员时才构建它的 agent 实例（`memberWrapper`），构建发生在 map 锁之外（构建含模型配置与 MCP 握手等网络调用）。
- 同一个根会话内该成员的后续委派复用同一个实例与同一份子会话状态，续跑它自己的对话；复用按成员实例，不按成员 id 猜任务归属。
- 一次运行变得不可信时（执行出错、被停止、反复请求确认、等待期间被抛弃）会 `evict` 掉该成员的实例：`interrupt` 是闭锁，停在确认上的轮次会留下永远没人回答的工具调用，这些污染在内存实例上，不在持久化的子会话上，下一次委派会读着同一份历史建新实例。
- 新的根调用接管事件流时（`openEventStream`）会取消上一轮遗留的等待、丢弃仍被占用的成员实例并清空运行账本与委派计数：客户端消失时可能留下一个还堵在确认上的委派线程，它持有成员的 agent。
- 上一轮根调用仍占有事件流（它的成员还在等确认）时，新请求被拒绝并回一条 `RESOURCE_LOCKED`，不会把那些事件抢进一个无关的响应里。

### 6.5 用户身份与 MCP

- 成员代表根会话的已认证用户执行：`createTeamMember` 把 `authSessionId` 显式传成根会话标识，因为 OAuth 授权属于打开根会话的人，admin 按那个会话反查身份；子会话标识 admin 不认识。
- 团队 spec 的 MCP 明细只来自成员自己的 Agent 与租户（admin 按 Agent 的租户过滤），主管不接收任何成员凭证。
- 一次团队运行没有可由调用方指定的 `userId` 参数，身份取自受信任的会话与 JWT。
- stdio 禁用口径、逐用户授权、运行侧令牌注入沿用普通 Agent 那条路径：团队不会因为成员有独立沙箱就重新开放 stdio，也不会把 MinIO、Docker 管理凭证注入成员容器。

## 7. 沙箱

团队按「主管无沙箱、成员各有一个」运行：

1. 主管不装配任何文件系统：`harness.sandbox.enabled` 与快照分支都带 `!isLead` 条件，`disableFilesystemTools()` 与 `disableShellTool()` 再关掉框架自带的两条入口。文件系统能力不会回退到宿主执行。
2. 成员按自己的配置装配沙箱：镜像由它自己的 CLI 集合解析（没有 CLI 时用默认镜像），环境变量来自它自己的 CLI 包与绑定，隔离作用域取 `harness.sandbox.isolation-scope`，快照与普通会话共用一套机制。`harness.sandbox.enabled=false` 时，配置了 MinIO 的运行退到 `RemoteFilesystemSpec` + `IsolationScope.SESSION`，成员之间仍是按会话隔离的文件空间。
3. 隔离作用域是租户、根团队会话、成员子运行三层；底层容器与工作区只接受一个会话标识，因此用子会话标识去映射该作用域，而不是用 `agentId` 或 `teamId`。不同用户、不同根会话、同一成员的不同根会话不会落到同一个容器。
4. 续跑同一子会话恢复自己的工作区与快照；成员的产物只经发布动作离开沙箱，宿主侧不会扫描成员工作区。
5. 生命周期：`STOP_SANDBOX` 命令停止执行时按根会话销毁容器，并按 `launcher.memberSessionIds(sessionId)` 把该根会话在状态存储里的成员子会话容器一并销毁；`TeamOrchestrator.stop(destroySandboxes = true)` 走同一入口。MCP 客户端随成员实例的 `release()` 关闭。
6. 独立容器是完全隔离之外的一层：不挂宿主 Docker socket，也不共享其他成员工作区，网络与资源限制由部署层的沙箱配置决定。

## 8. 产物交接

### 8.1 发布与获取

两个动作都是成员侧的工具，作用域由服务端解析，模型只能提供路径与 `fileId`：

| 动作 | 输入 | 行为与输出 |
|------|------|------------|
| `team_artifact_publish` | 相对工作区的 `path` | 在当前子运行的沙箱里取文件、上传 MinIO、登记 `team_artifact`，返回 `fileId` |
| `team_artifact_fetch` | `file_id` + `dest_path` | 校验该产物属于本次团队会话，下载后写入自己的沙箱工作区 |
| `team_artifacts` | 无 | 列出本根会话已发布产物、大小与产出成员 |

一次典型交接：研究员发布 `data.csv` 得到 `fileId=A`；主管把 A 写进给分析员的任务里；分析员获取 A、产出结论并发布 `fileId=B`；撰写员取 B 生成 `report.md` 并发布 C；主管验收后把 C 作为最终文件引用交给用户。

主管只传引用和摘要，不下载文件：它没有工作区，也没有能读文件的工具。文件内容核验仍然委派成员做。

### 8.2 存储、引用与边界

- 存储：`MinioTeamArtifactGateway`，桶是输出桶，对象键固定为 `team-artifacts/<tenantId>/<rootSessionId>/<fileId>`。前缀 `team-artifacts` 不在通用附件路由允许的会话类型白名单里，那条路由即便拼错也指不到团队对象。
- 每次发布生成新的 UUID `fileId`，同名文件可以共存，覆盖发生不了；交给下游哪个版本由主管决定。`mime_type` 按扩展名映射表推断，未命中为 `application/octet-stream`。
- 上传成功且登记行插入成功才返回可用引用；登记失败会把对象删掉再抛出，不留一个没人能解析、也没人能清理的对象。
- 读取路径必须先 `findOwned(fileId, tenantId, rootSessionId)`：租户与根会话取自当次运行受信任的 `TeamRuntimeSpec`，`fileId` 本身不证明任何事。模型不能指定 bucket 或 objectKey。
- 发布只允许当前成员工作区内的普通文件：`SandboxFileWriter.safeRelativePath` 拒绝越界与 `..`，取大小的 `sandboxSize` 用 `-f` 排除目录、`! -L` 排除符号链接，并要求 `readlink -f` 解析后的目标仍在 `$sandboxWorkspaceRoot/` 下。获取只允许写入自己工作区内的相对路径。
- 大小上限 `harness.team.max-artifact-bytes`（默认 20 MiB）在发布与获取两处都判。
- 只发布被明确点名的文件，不自动上传工作区、环境变量、密钥或快照。
- MinIO 未启用时 `TeamArtifactGateway` bean 不存在，团队仍可委派，但三个产物工具都返回一句点名「产物存储未启用（MinIO 未配置）」的文本，包括「不要用公共链接或宿主目录代替」。列表失败同样如实返回错误文本，而不是空列表——空列表在模型读来等于「没人产出文件」。

### 8.3 用户侧下载

`TeamArtifactController` 提供 `GET /api/admin/team-artifacts?sessionId=` 与 `GET /api/admin/team-artifacts/{fileId}?sessionId=`，只在 `minio.enabled=true` 时注册。对象级授权在 `ownedTeamSession`：`sessionId` 必须匹配 `[a-zA-Z0-9_-]{1,128}`、会话必须存在且启用、`team_id` 非空、`creator` 等于当前用户；列表再按行自身的 `tenant_id` 与会话一致过滤。下载的对象键永远取自 `team_artifact` 行；`fileId` 必须匹配 UUID 形状，属于别的会话或租户的 `fileId` 返回 404 而不是 403（不向旁人证实引用存在）。`Content-Disposition` 里的文件名过滤引号、CR/LF 与分号，MIME 解析失败退到 `application/octet-stream`。

成员的运行到不了这个入口——它没有 admin 的凭据，取文件到沙箱是 agent-service 内部的服务端读取。

### 8.4 清理

会话删除是唯一会让产物失去归属的入口，`TeamArtifactCleaner.deleteForSession` 先删 MinIO 对象、再删 `team_artifact` 行：对象存储没有事务，反过来的顺序会留下没人能找到的对象，而现在的顺序下重试可以修复（MinIO 的重复删除是幂等的）。单个对象删不掉时保留它的行并记日志，让缺口可见，而不是把一个坏掉的下载留在页面上。MinIO 未配置时整批保留并告警：对象还在，删掉行就等于把它留在没人能找到的地方。

团队在有会话时删不掉，所以「团队被删、产物还挂在会话上」这条路不存在。

## 9. 事件、确认与失败

### 9.1 一条根流与来源标识

外部始终只有根会话这一条 SSE：`DefaultAgentRunner.withMemberEvents` 在订阅主管流之前打开 `TeamOrchestrator` 的成员事件流，两者 `Flux.merge` 成一个响应。路由与粘性会话按根 sessionId 走，每次委派不产生独立的外部路由请求。

来源是 `ChatEvent.source`（`EventSource`）：`teamId`、`teamName`、`memberAgentId`、`memberAgentName`、`childRunId`、`childSessionId`。成员事件在 `collectTurn` 里逐个转发并 `withSource(run.source)`；`EndEventChatEvent` 在服务端被过滤，不下发到前端。

`childRunId` 是一次委派的标识（UUID），同成员被多次委派时它区分得开；显示名与 `agentId` 都不是判据。工具参数与结果的缓冲按成员运行各自维护，因此 `toolCallId` 只需在单次运行内可配对。

成员的文本只作为委派返回值进入主管的上下文，不会被拼成主管最终答案；token 统计按各自实例的归属记录，主管与成员的行分开计数。

### 9.2 人工确认闭环

```text
成员的工具命中 ASK 规则
    ↓ ToolConfirmEvent 带着该运行的来源下发（委派卡就地展开确认入口）
用户批准或拒绝（前端按 childRunId 回答，整轮一次性决定）
    ↓ admin/router 按根 sessionId 粘性路由回同一实例
TeamOrchestrator.answerConfirmation(childRunId, approved) 完成该运行的等待
    ↓ 成员在原来的那次委派里继续执行，输出仍走原根流
主管拿到成员结果继续安排
```

要点：

- 回答接口是 `/api/router/agent/confirm`，带 `childRunId`；`AgentRequest.ConfirmAgentRequest` 的该字段为空时按会话自己的确认处理，非空时交给团队编排器。成功时只回一条 End：续跑的输出从还开着的那条根流回来，回答本身不带内容。
- 一次等待里的多个待确认工具按一个决定处理（`toolResults.all { it.confirmed }`），混合作答等于拒绝这次运行，不会有工具在用户没勾的情况下执行。
- 决定只送达一次：`CompletableFuture` 由 `pending.getAndSet(null)` 取走，重复提交得到 `ALREADY_ANSWERED`；没有等待得到 `NO_PENDING`；标识不属于本编排器得到 `NOT_IN_THIS_TEAM`；根运行已停止得到 `STOPPED`。四种拒绝都带回可读文本，工具一律不执行。
- 等待按 `HarnessConfig.TeamConfig.confirmHeartbeatSeconds` 的 30 秒切片：装配 `TeamConfig` 时没有传这一项，取的就是数据类的默认值，`harness.team` 下因此没有对应的配置键可调。每片没人回答就在根流上补发一条 `KeepAliveChatEvent`，只带来源、不带内容也不带用量。这条心跳对着的是两处空闲判定：session-router 的 `router.proxy.stream-idle-timeout-seconds`（默认 120 秒）与 channel-service 的 `channel.proxy.stream-idle-timeout-ms`（默认 180 秒），这两处是配置项，而等待本身可以合法地持续更久。
- 确认窗口 `harness.team.confirm-timeout-seconds`（默认 600 秒）到期、线程被中断、或未来得及回答就发生停止，都得到「未完成」：工具没执行，等待不会当成批准，成员任务以失败文本回到主管手里，最多 `MAX_CONFIRM_ROUNDS`（5）轮反复请求。
- 成员还在等确认时，上一轮根调用一直持有事件流，`openEventStream` 对它返回空；此时用户再发一条消息，得到的是 `DefaultAgentRunner` 直接回的一条 `RESOURCE_LOCKED`（并记一条 warn），新调用既不接管这条等待、也不释放它，与 6.4 是同一条规则。等待被丢弃发生在下一条根调用真正开启事件流的那一刻：那一次 `openEventStream` 取消仍在等待的委派、`evict` 停在确认上的成员实例，后续对同一个 `childRunId` 的回答因此得到 `NOT_IN_THIS_TEAM`，前端提示这次确认已失效。团队运行的持久状态里不重放待确认卡片。
- 团队会话在成员运行没有返回前不会并行开第二轮（同一条事件流的归属唯一），主管自己的确认可以弹窗暂停读流、成员必须内联回答。
- 自动批准、把需确认工具从成员身上摘掉、统一自动拒绝、让主管代执行危险动作，都不算这个闭环；无交互能力的入口要接团队需要另行定义确认策略。

### 9.3 停止、失败与预算

- 成员失败一律如实回文本，从不伪成功。`runTask` 的失败收场包括：成员抛出 `ErrorEvent`、用户在执行中停止、反复请求确认被中止、等待确认超时或被取消、成员没有返回任何内容与产物、委派本身抛异常。每条都带上已完成部分的文本，失败后 `evict` 掉该成员实例并释放占用位。
- `failReport` 之外，其余失败收场以正常返回值回到主管手里（`team_delegate` 的结果不带失败标记），界面侧的收口语义见「前端呈现」。
- 停止：`stopExecution` 先 `orchestrator.stop()` 再中断主管。委派阻塞在主管的工具线程上，只打断主管会留下还在跑（或还堵在确认上）的成员；`stop()` 释放所有等待而不批准它们，设置 `stopped` 拒绝后续委派，并中断正在执行的成员。`STOP_SANDBOX` 额外销毁成员沙箱并让实例失效。已发出的外部副作用（shell、MCP 调用）取消不回滚。
- 预算：`harness.team.max-delegations`（默认 20 次）由运行时计数并拒绝，拒绝文本直接要求主管汇总已有结果；成员单轮 `member-turn-timeout-seconds`（默认 900 秒）从外部包住成员流；根一轮 `turn-timeout-seconds`（默认 1800 秒）大于前者，配反时构建会打一条警告说明生效的是哪一层。产物大小上限见上一节。
- 进程重启或运行落到另一个实例时，快照与工作区可恢复，在途委派不自动重放：中断的运行以中断收场，由用户或主管重新发起。

### 9.4 前端呈现

呈现只回答「这句话是谁说的、跑到哪一步」，不改变来源契约、确认闭环与生命周期语义。共享逻辑集中在 `harnax-webui/src/pages/session/components/teamRun.ts`，实时流与历史回放用同一套判据。

- 一次成员运行是一个「气泡状态」（按 `childRunId` 归组），它被渲染在它所属那次 `team_delegate` 工具卡内部。归属在运行创建时定下：实时按该成员已发出、尚未被认领的委派卡 FIFO 取第一张（`openDelegateCards`），回放按顺序扫该轮已有卡片（`claimDelegateCard`），两条路都只认成员标识与调用顺序、不认时间戳，因此实时顺序与重开后的顺序一致。
- 认领不到卡的运行仍然独立成气泡：委派被拒绝、历史里主管那一轮没留下调用记录、名册读不到，任何一种都不能把成员输出从界面上弄丢。主管气泡在委派处不切开，否则 `team_delegate` 的卡片会与它的结果事件分家。
- 收口信号是主管 `team_delegate` 的结果事件：按工具调用标识定位那张卡，从卡参数读出成员标识（`parseDelegateCall`），据此收口对应运行。卡已返回结果却没有运行认领它时，要把这个卡号从待认领里摘掉，否则它会抢走该成员下一次委派的位置。另有三处兜底收口：主管一轮结束、主管报错、根流断开，未收口的运行一律置终态。
- 一次成员运行同时只允许一个未收口，且委派为前台阻塞，这让「卡—运行」的映射保持唯一。
- 状态四态：`running`、`awaiting_confirm`、`done`、`failed`。「等你确认」就是确认闭环（`answerConfirmation` 的等待）在界面上的入口，等确认期间卡片保持展开。折叠控件是委派卡自己的头部：有运行在跑或等确认时自动展开，收口后自动收起，用户点过之后以用户为准（覆盖按卡片记，切会话即清）。收起时头部右侧仍标出这张卡给了谁，一张卡里挂着多次运行时标「名字 +N」。展开后每个运行有自己的标题行：成员名、团队名、状态、工具次数、耗时，正文是它自己的文本与工具卡。
- 「已完成」的确切含义是「这次委派已收口，且期间没有收到失败事件」。只有成员自己抛 `ErrorEvent` 那一条路径会显示 `failed`；执行中被停止、反复请求确认被中止、等确认超时、没有返回内容、运行抛异常这些都以正常委派结果收场，界面显示已完成。历史回放不带生命周期信息，重开后的成员运行一律从 `done` 起步。
- 工具级状态能看出这一轮被切在哪里：回放里配对不到结果日志的调用、以及实时流收口时仍没等到结果的调用，都收口成「已中断」，处于等待确认的卡片除外（它已有自己的状态）。
- 成员的任务文本来自主管已落库/已发出的 `team_delegate` 参数（成员自己的事件与日志都不带任务），按成员记最近一次；只在运行认领不到卡片、独立成泡时用在标题行上。

### 9.5 历史回放

`DefaultAgentRunner.loadHistory` 把 `TeamHistoryReplay.merge` 套在普通历史读取上：

1. `launcher.memberSessionIds(rootSessionId)` 在状态存储里按前缀 `team-<root>-m` 找回成员子会话。普通会话也会走这一步（判据只有这一步能给出），扫完即返回。
2. 名册由 admin 的 `getTeamSpec(rootSessionId)` 解析，为每个成员子会话拼出一份字段齐备的 `EventSource`。名字取当前名册而不是历史快照——成员改名后用户应看到它现在的名字。运行标识没有落库，回放用子会话标识顶替 `childRunId`。
3. 读每个成员子会话的持久消息，只保留 `ASSISTANT` 与 `TOOL` 两类角色（成员的 user 消息就是主管的委派简报，它已经在主管那一轮里）。
4. 穿插以「一轮」为最小单位：主管的一轮（assistant 消息加紧随其后的工具结果）是插入块，成员日志整轮移动，落在产生它的那一轮之前。按时间戳平铺会把一个成员的日志插到另一个成员的调用与结果之间，工具卡就永远收不了口。晚于主管最后一条落库消息的成员运行仍追加到末尾。
5. 子会话标识不区分委派次数，因此运行按连续性切分：与上一条同属一个子会话的成员日志并入当前运行，被主管日志打断就开新运行。实时看到几个成员运行，重开后还是几个。
6. 降级路径只影响团队部分：名册读不到、成员子会话为空、成员日志为空，都只返回主管历史，团队历史读不全不会让整个会话打不开。成员被移出团队后走同一条降级——它的子会话已不在名册里，成员日志读不出来，那次委派只留在主管气泡的 `team_delegate` 结果文本里。
7. 落库的工具日志不带工具调用标识，回放按消息内序号补一个合成标识（`${msgId}-t${seq}`）；认领只要求它在同一条消息内稳定。同一轮的多个调用先按来源收齐结果，再按名字逐个配对。

## 10. 配置项与模块职责

团队运行侧的配置前缀是 `harness.team.*`，默认值即 `TeamConfig`：

| 配置 | 默认 | 作用 |
|------|------|------|
| `max-delegations` | 20 | 一次根运行允许的委派次数 |
| `member-turn-timeout-seconds` | 900 | 成员单轮上限 |
| `turn-timeout-seconds` | 1800 | 团队一轮（主管）预算，应大于上一项 |
| `confirm-timeout-seconds` | 600 | 一次成员确认的等待窗口 |
| `max-artifact-bytes` | 20 MiB | 单个产物的发布与获取上限 |

`TeamConfig` 还带一个 `confirmHeartbeatSeconds`（30 秒），它不在上面这张表里：`harness.team` 没有对应的键，
装配 `TeamConfig` 时也不传这一项，取的就是数据类的默认值，所以等待期间的根流心跳当前固定按 30 秒切片。

| 模块 | 团队相关职责 |
|------|--------------|
| `harnax-entity` | `Team`、`TeamMember`、`TeamSkillBinding`、`TeamArtifact`、`Session.agentId` 可空与 `teamId`；`TeamSpecInfoResponse` 与 mapper |
| `harnax-admin` | 团队 CRUD 与校验、会话分型与快照、`/team-spec` 与 `/sessions/{id}/team` 下发、产物元数据与受鉴权下载、会话删除时清理产物 |
| `harnax-agent-service` | 团队会话判定与 spec 解析、编排器与主管构建、成员工厂与线程上下文、事件流合并、确认分派、停止、历史回放合并 |
| `harnax-harness-core` | 角色装配（主管/成员）、团队工具、编排器、子会话标识、产物网关、沙箱与统计归属 |
| `harnax-protocol` | `EventSource` 字段、`withSource`、`KeepAliveChatEvent`、确认请求上的 `childRunId` |
| `harnax-webui` | 团队列表与两步向导、执行者选择、委派卡内的成员运行、内联确认、产物抽屉、团队标识呈现 |
| `harnax-session-router` | 根 sessionId 粘性路由与流空闲超时（团队不新增外部路由维度，`childRunId` 随请求透传） |
| channel / scheduler / client | 按各自协议消费点处理来源与心跳；团队入口当前只有 Web 会话 |

部署沿用 `harnax-deploy`，团队不引入新服务。成员独立沙箱会带来容器、镜像与对象存储的资源开销，容量限制、生命周期与部署说明与沙箱、MinIO 两份口径一起维护。

## 11. 明确不做

| 不做 | 当前形态 |
|------|----------|
| 团队作为 Agent 的一种、或在 Agent 上加带队开关 | 团队是独立配置对象，主管配置住在 `team` 行 |
| 代表主管的隐藏 `agent` 行 | 主管 spec 由 `specForTeam` 合成，`agent` 表只有对话 Agent 与成员两种身份 |
| 团队持有 Tool、MCP、CLI 绑定 | 团队只带 `team_skill_binding`；这三类只在成员 Agent 上配置 |
| 主管使用业务工具、MCP、shell、沙箱执行工作 | 装配处直接不给，提示词只是说明 |
| 成员之间共享可写工作区或共享一个沙箱 | 每人独立沙箱，文件只经 MinIO 产物交接 |
| 用工作区快照或整段文件内容传递业务文件 | 快照只用于工作区恢复；文件只以 `fileId` 引用传递 |
| 团队内的并行、后台、嵌套或跨团队委派 | 前台、顺序、单层；同成员并发委派被拒绝 |
| 群聊协商、工作流画布、A2A 与远端 Agent 发现 | 未实现，也不在现有代码路径上 |
| 自动批准、统一自动拒绝、让主管代执行危险动作 | 确认闭环要求用户决定回到成员运行 |
| 为成员另开独立子会话聊天窗口 | 成员过程嵌在主管的委派卡内，仍是一条会话 |
| 在已有普通会话上切换成团队模式 | 团队会话只能创建 |
| 团队渠道投递（钉钉/微信/定时任务）作为团队入口 | `chn-`、`task-` 会话没有 `session` 行，团队判定不覆盖它们 |

## 12. 关键文件索引

| 路径 | 内容 |
|------|------|
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Team.kt` | 团队行，主管配置的宿主 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamMember.kt` | 成员引用与团队内分工 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamSkillBinding.kt` | 主管技能绑定 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/TeamArtifact.kt` | 产物元数据与引用语义 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Session.kt` | `agentId` 可空与 `teamId` |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/dto/TeamSpecInfoResponse.kt` | 团队 spec 下发形状 |
| `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql` | admin 的 schema 基线：`team`、`team_member`、`team_artifact`、`team_skill_binding` 四张表的建表语句与 `session.team_id` 都写在这一个脚本里，主管配置 `system_prompt`、`model_id` 两列就在 `team` 自己的建表语句中 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt` | 团队 CRUD、启停、关联会话接口 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt` | 保存校验、可见性、删除拒绝 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt` | `/team-spec`、`/sessions/{id}/team`、`specForTeam`、`buildAgentSpecResponse` |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SessionServiceImpl.kt` | 团队会话创建、快照与拒绝改挂 Agent |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamArtifactController.kt` | 产物清单与对象级鉴权下载 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamArtifactCleaner.kt` | 会话删除时的产物清理 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRuntimeSpec.kt` | 受信任的团队运行配置与子会话标识拼写、主管提示词块 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamRole.kt` | 主管/成员角色 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt` | 主管三条团队工具与成员三条产物工具 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamOrchestrator.kt` | 委派、成员实例与运行账本、确认等待与心跳、产物发布获取、停止与预算 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamArtifactGateway.kt` | MinIO 产物网关与对象键规则 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt` | `createTeamLead`、`createTeamMember`、角色装配差异、`memberSessionIds` |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/config/HarnessConfig.kt` | `TeamConfig` 默认值 |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt` | `harness.team.*` 绑定与 `teamArtifactGateway` bean |
| `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt` | `attributableAgentId`（主管无 Agent 归属） |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/AgentSpecResolver.kt` | `isTeamSession`、`resolveTeam` |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt` | `buildTeamAgent`、事件流合并、确认分派、停止、历史入口 |
| `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/TeamHistoryReplay.kt` | 成员子会话找回、来源补齐与按轮穿插 |
| `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt` | `EventSource` 字段与 `withSource` |
| `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt` | 确认请求上的 `childRunId` |
| `harnax-webui/src/pages/team/index.tsx` | 团队管理页 |
| `harnax-webui/src/pages/team/components/TeamWizard.tsx` | 两步向导与不可用项标注 |
| `harnax-webui/src/pages/team/components/MembersField.tsx` | 成员选择与分工 |
| `harnax-webui/src/pages/session/components/SettingsModal.tsx` | 执行者分组选择，团队会话创建 |
| `harnax-webui/src/pages/session/components/teamRun.ts` | 委派卡认领、任务解析、运行状态判据 |
| `harnax-webui/src/pages/session/components/ChatWindow.tsx` | 成员运行渲染、内联确认、折叠与历史回放认领 |
| `harnax-webui/src/pages/session/components/TeamArtifactsDrawer.tsx` | 产物清单与下载 |
| `harnax-webui/src/pages/session/components/DetailModal.tsx` | 团队会话的详情分型 |
| `harnax-webui/src/services/ant-design-pro/team.ts` | 团队与产物接口封装 |
| `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/service/AgentServiceClient.kt` | 根流空闲超时（心跳对着它） |
| `harnax-channel/harnax-channel-service/src/main/kotlin/com/agnetix/harnax/channel/service/client/RouterClient.kt` | 渠道侧流空闲超时 |
