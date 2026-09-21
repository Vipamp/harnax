# 团队主管配置内聚（Team 自带主 agent 配置）· 设计规格

- 日期：2026-09-20
- 状态：**设计已获用户批准（第 1 节 D1~D9）；未开工**。第 0 节与各处行号是开工前快照，不随改动更新。
- 范围：`harnax-entity`（表/实体/下发 DTO）、`harnax-admin`（团队服务与内部下发）、`harnax-agent-service` + `harnax-harness-core`（主管技能装载）、`harnax-webui`（团队向导）
- 关联文档：[prod_doc/multi-agent-team-design.zh-CN.md](../../../prod_doc/multi-agent-team-design.zh-CN.md)（团队域现状事实来源）。本文覆盖其 §3 产品模型、§4.1 配置交互、§5 主管能力边界、§6.1 下发链路、§10.2 建模取舍、§11 改动面六处；开工后需把本文结论同步回该文档（中英两份）。

## 0. 需求与现状错位

需求原话（2026-09-20）：

> 智能体团队不要选择主智能体了，在团队中设置：1. 原来的智能体管理向导页的第一部分"基本信息"里面的内容信息，作为团队配置，也是主 agent 的配置；2. 可以给团队主 agent 配置 skill 即可，tool、mcp 和 cli 都不要了；3. 然后给团队配置成员智能体，这部分内容和现在保持一致。基础配置和成员配置使用向导页面来配置，先配置基本信息和 skill，然后第二步再配置成员。

现状与本需求的三处直接冲突：

| # | 冲突 | 现状证据（开工前快照） |
|---|---|---|
| ① | 主管必须是一个已存在的 agent | `Team.leadAgentId` NOT NULL（`Team.kt:34`、`V32__add_team_tables.sql:24`）；创建时 `requireUsableAgent(request.leadAgentId!!, "Lead")`（`TeamServiceImpl.kt:63`）；下发时 `agentOrThrow(team.leadAgentId, "Lead")`（`InternalApiController.kt:440`） |
| ② | 主管的技能被明确丢弃 | 装载侧 `if (isLead) { 打日志不装载 }`（`HarnessAgentLauncher.kt:434-442`）；解析侧只给成员 `withBuiltinSkills`（`AgentSpecResolver.kt:86-90`） |
| ③ | 团队页是扁平弹窗，没有向导 | `team/components/CreateForm.tsx` 单弹窗，`leadAgentId` Select 在 `:107-136`；`UpdateForm.tsx:105-130` 同形状；agent 页才有 5 步 `Steps`（`agent/components/CreateForm.tsx:220-258`） |

另有一条口径要同时改：`team.instructions` 与主管系统提示词是两个字段（`Team.kt:37`、`TeamRuntimeSpec.kt:38/78/87` 把它追加进主管角色提示），本需求只要一个。

## 1. 决策清单（已定稿）

| # | 决策 | 选择 | 关键理由 |
|---|---|---|---|
| D1 | 主管配置落点 | **`team` 表自身持有**（新增 `system_prompt`/`model_id`）+ 新表 `team_skill_binding`。`team.lead_agent_id` 删除 | 用户定位：`agent` 表只放"可独立对话的 agent"或"团队子 agent"，不接受一条伪装的主管行。曾评估过"团队持有一条隐藏 agent 行 + `agent.team_id` 标记"（运行侧零改动），因引入幽灵 agent、列表过滤与命名冲突这一整类长期风险被否 |
| D2 | `instructions` 的去向 | **删列，内容并入 `team.system_prompt`** | 两个提示词字段并存只产生"哪一段赢"的歧义；框架的角色/名册提示块仍在（见 4.3），主管不会因此不知道要委派 |
| D3 | 团队会话的 `agent_id` | **写 NULL**，不塞哨兵 0 | 哨兵让每个 `selectById(agent_id)` 都变成一次注定无结果的查询，且新代码更容易忘记判空。`session.agent_id` 在 DDL 里本就允许 NULL（`V1__init_schema.sql:225`），要改的只有 Kotlin 实体类型 |
| D4 | 主管 spec 的下发实现 | **复用 `buildAgentSpecResponse` 的解析段**：把"按 agentId 读四张绑定表"提到调用方，两个调用方各自传入绑定内容。不新建第二条下发链路 | 现函数（`InternalApiController.kt:530-760`）里工具/MCP/技能/CLI 的解析、停用过滤、env 解析、model/provider 拼装共 200+ 行，复制一份必然与 agent 侧漂移 |
| D5 | 主管技能装载 | **装载**：SKILL.md 文本与资源内容经 `InMemorySkillRepository`（`HarnessAgentBuilder.kt:169-170`）进主管；带脚本/需落盘执行的部分**降级并点名** | 主管无 shell、无文件系统工具（`HarnessAgentLauncher.kt:579-580`），能读到技能文本但没有东西够得着那些文件。注意「无沙箱」不成立：容器仍会为它建并落快照（记账项，见项目记忆 team-lead-config-open-issues 第 10 条）。见 4.4 关于为何不会重演技能域 TODO-10 |
| D6 | 存量团队 | **丢弃**：`team`/`team_member`/`team_artifact` 清空，团队会话保留历史并把 `session.team_id` 置 NULL，退化为普通 agent 会话 | 用户明确"可以丢弃掉不要"。存量团队会话的 `agent_id` 当时写的是真实 lead agent 行，置空 `team_id` 后其快照与历史仍完整可读，不必连对话记录一起删 |
| D7 | 主管 spec 的 `agentId` 字段 | **0**，`agentName` = 团队名 | 主管无 agent 行。**本轮评审查明的代价**：`process_log` 读写两侧都以 `agent_id != 0` 为条件（`ProcessLogMapper.xml:33`、`InternalApiController.kt:888`），主管的过程日志因此永远不可见；`token_stats`/`tool_call_log` 也出现解析不出的 0 号。先前引用的先例（`MpSessionService.kt:128`）语义是「该会话不归属任何 agent」，与「团队会话没有 agent_id」并非同一件事，不能当既成口径。修法见记忆清单 team-lead-config-open-issues 第 1、2 条 |
| D8 | tool/mcp/cli | 团队 API **不收这些字段**，`team` 侧也没有对应表 | 双重保险：运行侧 `isLead` 守卫（`HarnessAgentLauncher.kt:236/331/335/504`）保留，但已经没有入口能写进数据 |
| D9 | 成员配置 | 完全不动（`team_member` 表、校验、`delegation_description` 语义） | 需求原话"这部分内容和现在保持一致" |

## 2. 数据模型

### 2.1 表

**`team`**（成为主管配置的宿主）

```
id, tenant_id, name, description,
system_prompt   ← 新增 text NOT NULL          （向导第一步的系统提示词，含原 instructions 语义）
model_id        ← 新增 bigint NOT NULL        （向导第一步的模型）
status, is_public, creator, active, create_time, update_time
```

- 删 `lead_agent_id`、删 `instructions`。
- `name`/`description` 直接复用：团队名即主管名，团队描述即主管描述（agent 向导第一步的 `description` 是必填，团队侧 `description` 随之改为必填）。
- 名称唯一性维持服务层校验（`TeamServiceImpl.kt:58/91`），不加库级唯一键——理由与 V32 注释相同：行是逻辑删除的。

**`team_skill_binding`（新表）**

```
id, team_id, skill_id, create_time, update_time
UNIQUE KEY uk_team_skill (team_id, skill_id)
KEY idx_skill_id (skill_id)
```

与 `agent_skill_binding`（V33 加了 `UNIQUE(agent_id, skill_id)`）同构，**不带 `env_bindings`**：技能级环境变量本就没有消费者（`AgentServiceImpl.kt:476-479` 已记明）。

**`agent`**：**不加列**。一条 agent 行只有两种身份——能独立对话的 agent，或被 `team_member` 引用的子 agent。

**`team_member` / `team_artifact`**：不变。

**`session`**：DDL 不改（`agent_id` 已允许 NULL）。改的是实体类型与写入语义：团队会话 `agent_id = NULL`、`team_id = <团队id>`，`name/description/system_prompt/model_id/owner` 五项快照改为从 `team` 行取（现在从 lead agent 取，`SessionServiceImpl.kt:183-187`）。

### 2.2 不变量

1. 主管配置的唯一真相源是 `team` 行 + `team_skill_binding`；`agent` 表里查不到它。
2. `session` 二选一：`agent_id` 与 `team_id` 不同时有值。
3. 团队只有一把开关（`team.status`）。不再有"lead agent 被禁用"这第二把，因此 `agentOrThrow` 对主管那一半的 status 校验随字段一起消失。
4. 按 `agentId` 读会话能力的地方，遇到 `teamId != null` 必须改读 team 侧（`SessionServiceImpl.kt:116/127` 的 MCP/skill 展示是仅有的两处；团队会话的 MCP 列表为空是正确结果，技能列表来自 `team_skill_binding`）。
5. 删除团队时同事务删 `team_skill_binding`（与 `teamMemberMapper.deleteByTeamId` 并列）。

## 3. admin 侧改动

### 3.1 请求/响应 DTO

- `TeamCreateRequest`：去 `leadAgentId`/`instructions`；加 `systemPrompt`（`@NotBlank`）、`modelId`（`@NotNull`）、`skillIds: List<Long>?`；`description` 改为必填（对齐 agent 向导第一步）。`members` 保持 `@NotEmpty @Valid`。
- `TeamUpdateRequest`：同形状，`skillIds` 非 null 即整表替换（沿用 `members` 的既有语义），null 即不动。向导只在技能集合真的变过时才发这个字段——整表替换会拒收任何已失效的绑定，一条坏技能就会让团队连改名都改不动（成员仍整体替换：表单里删掉的行本就该消失）。
- `TeamResponse`：去 `leadAgentId`/`leadAgentName`/`leadAgentDescription`/`instructions`；加 `systemPrompt`/`modelId`/`modelName`/`skillList[{skillId, skillName, skillDescription, repositoryId, repositoryName}]`。`modelName` 取 `model.model_name`，与 agent 列表同一列（两页显示同一个模型必须是同一个字符串），行没了则回 `null`、前端回落成 `#id`。`memberList` 与 `agentAvailable` 语义不变。
- `TeamSpecInfoResponse`：去 `instructions`；`lead` 仍是 `AgentSpecInfoResponse`（形状不变，来源变）。成员部分不变。

### 3.2 服务层（`TeamServiceImpl`）

- 删 `requireUsableAgent(leadAgentId, "Lead")` 与 `validateMembers` 里"主管不能是成员"的判定（`leadAgentId` 参数整体消失）。成员校验其余保留。
- `modelId` 保存时校验（`requireUsableModel`）：行存在、可见（`is_public=1` 或本人创建，与模型下拉同口径）、`status==1`、`modelType=='chat'`。**不按租户判**——共享的公开模型本来就可选。agent 侧目前没有这条校验（`AgentServiceImpl.createAgent` 只 `!!` 非空），团队侧要加——主管的模型解析不到时运行侧没有任何回退路径（`specForTeam` 只能拿它去查 `model`）。
- `saveTeamSkills(teamId, skillIds)`：复用 agent 侧 `saveSkillBindings` 的四条写时校验——可解析（缺失/跨租户拒绝）、停用拒绝、内置 CLI 仓库技能不可直接绑定、同名技能不可并存（`AgentServiceImpl.kt:481-528`）。**这四条抽成共享校验函数由两处调用，不在团队侧重写一遍**（否则两边规则会漂）。
- `convertToResponse`：`modelName` 查 `model`，技能列表读 `team_skill_binding`。
- 新增 `getTeamDetail` 需要的技能 id，供前端向导回显。

### 3.3 内部下发（`InternalApiController`）

- `resolveTeamSpec`（`:426-483`）：不再读 `team.leadAgentId`；改 `specForTeam(team, session 开关)`。团队级校验（存在、`status==1`、成员非空、租户一致）保持。
- `buildAgentSpecResponse`（`:530`）签名按 D4 改造：入参从 `agentId` 换成 `toolBindings / mcpBindings / skillIds / cliBindings` 四组已取出的绑定；`specForAgent` 负责按 `agent.id` 读表后调用，`specForTeam` 传空工具/MCP/CLI 列表 + 团队技能 id，`agentId = 0`、`agentName = team.name`、`systemPrompt = team.systemPrompt`、`modelId = team.modelId`。
- 普通 agent 下发路径（`:342`）加守卫：`session.teamId != null` 时明确拒绝并指向 `/team-spec`，而不是拿 NULL `agent_id` 去 `selectById` 后报"Agent not found: 0"。
- `getSessionInfo`（`:197-205`）的 `agentId` 变可空；`agentName` 取 `session.name`（团队会话即团队名），router 侧无需改（该字段仅用于 call-log 补全）。

### 3.4 会话服务（`SessionServiceImpl`）

- `createSession`（`:146-209`）：team 分支不再 `agentService.getAgent(leadAgentId)`，改为校验团队（已存在）后用 `team` 行填快照，`session.agentId = null`；`enable_think` 的默认值当前由 `agent.modelId` 推导模型思考模式（`:191-192`），团队分支改由 `team.modelId` 走同一条推导。非团队分支不变。
- `updateSession`（`:215-222`）：团队会话禁止改绑 agent 的判定保留，比较对象改为 `session.agentId == null`。
- `Session.agentId: Long` → `Long?`（`Session.kt:29`），连带 `SessionResponse.agentId`、`SessionCreateRequest.agentId`（团队会话不传 agent）。

### 3.5 顺带清理

- `TeamMapper.selectByLeadAgentId`（`TeamMapper.xml:45`）成死代码，删。
- `AgentServiceImpl.deleteAgent` 的"此 agent 正领导团队"守卫（`:187-193`）删——已无此种引用；"是团队成员"守卫保留。
- `Team` 实体与 `TeamMapper.xml` 的 `lead_agent_id`/`instructions` 列映射删（`:10/50/62`）。

## 4. 运行侧改动

### 4.1 配置与状态快照

`AgentSpecResolver.resolveTeam`（`:81-130`）取 `teamSpec.lead` 的逻辑不变（形状没变），只是这份 lead 来自 team 行。`ChatSpec` 仍不带 plan 模式（主管无工作区）。

### 4.2 团队会话路由

原判「NULL `agent_id` 不影响 agent-service」**不成立**：它原本从 admin 下发的 `AgentSpecInfoResponse.teamId` 嗅探团队身份（`DefaultAgentRunner.buildAgent`），而 §3.3 把 `/agent-spec` 对团队会话改成拒绝之后，这个信号再也拿不到——不改道的话团队会话直接报"Session runs as a team"。

改道后的形状：

- admin 新增探测端点 `GET /api/admin/internal/sessions/{sessionId}/team`（`InternalApiController.kt:339`），只回 `session.teamId`。选它而不是复用 `/sessions/{id}/info`：后者会顺带查一次模型，而这里只要一个归属判断；也不能靠前缀猜，团队会话的 id 就是普通的 `web-`。
- `AdminApiClient.isTeamSession(sessionId): Boolean`（对外只暴露布尔，避免"可空 Long 桩返 0"那类静默错位）→ `AgentSpecResolver.isTeamSession`（`DefaultAgentRunner` 已注入 resolver，HTTP 不进 runner）→ `buildAgent` 先问归属再决定走 `/team-spec` 还是 `/agent-spec`。每次 agent 构建多一次索引查询，团队身份在会话生命周期内不变。
- `AgentSpecInfoResponse.teamId` 与其 KDoc 删除，`resolveFromSession` 尾部那次 `.copy(teamId = ...)` 一并退场——它存在的唯一理由就是这次嗅探。
- `AgentSpecBuilder.build()` 的 `require(id > 0)` 放宽为 `id >= 0`：主管的 spec 按 D7 带 `agentId = 0`，而 `-1` 仍是"未设置"。这条是运行期测试逼出来的——放宽前，团队会话在构建主管时直接抛 `Agent id must be greater than 0`。

### 4.3 主管提示词

`TeamRuntimeSpec.kt:38/78/87` 三处 `instructions` 删除；`leadOrchestrationPrompt`（`HarnessAgentLauncher.kt:206`）继续追加角色/名册块。主管提示词 = `team.system_prompt` + 角色块。

### 4.4 主管技能装载（对应 D5）

`HarnessAgentLauncher.kt:434-442` 的 `if (isLead) 只打日志` 改成与成员同路径 `agentBuilder.addSkill(skill)`；差异只在一条 warn：当 `SkillDetailDto.resources` 非空（该技能带资源文件/脚本）时，记 `主管无 shell 与沙箱，技能 X 的 N 个文件不可执行，仅其文本可见`。

**为什么不会重演技能域 TODO-10**：TODO-10 的坏味道来自提示词里渲染出 `<files-root>` 指向一个永远不会被投影的容器内路径。主管走 `disableShellTool()` → `ShellPathPolicy.noShell()`，压根不渲染该前缀，所以模型不会拿到一个"看着像真的、其实永远为空"的路径。这一点写进装载处的注释，避免后来者误以为主管也该投影技能文件。

`AgentSpecResolver.kt:86-90` 的注释需改写：主管现在确实装载技能，但 `withBuiltinSkills` 对主管仍是 no-op（内置技能只由 CLI 绑定引出，主管没有 CLI），不是"被扣住"。

## 5. 前端（`harnax-webui`）两步向导

- 新增共享 `team/components/TeamWizard.tsx`：Create/Update 只差初始值与提交调用，避免照 agent 页那样把 5 段 `Steps` 抄两遍。
- **Step 1「基本信息」**：`name`、`description`（改为必填）、`systemPrompt`（必填）、`modelId`（必填）、`isPublic`；下方挂 `SkillConfigPanel`（该组件是受控组件、不绑 Form 实例，可原样抬进来说明位置）。切步校验字段：`['name','description','systemPrompt','modelId']`，与 agent 页一致。
- **Step 2「成员」**：`MembersField` 原样复用，删掉"候选排除主管"的逻辑（已无主管可选）。
- `configValidation.ts` 的 `TOOL_STEP/MCP_STEP/SKILL_STEP` 常量为 agent 向导专用，团队向导另开一条，不改 agent 行为。
- 团队列表页：删主管列，改为「主管模型」标签 + 成员标签两列。成员列展示的是**名字不是数量**——被删/停用的成员要显示成 `#id` 并带提示，只给一个数字就看不出团队已经缺人。
- 两步都保持挂载（`display` 切换而非条件渲染）：技能面板与成员行的本地 state 在来回切步时不能丢，否则第二步退回第一步再前进就看不到已选技能。
- `SettingsModal.tsx:80-100/179-206`：团队会话的 agent 回退不再读 `team.leadAgentId`。
- typings `TeamItem`/`TeamCreateRequest`（`src/services/ant-design-pro/typings.d.ts:408-467`）与 `SessionItem.agentId` 可空同步。
- i18n：`src/locales/{zh-CN,en-US}/pages.ts` 团队块同步增删 key（zh 1234-1269 / en 1230-1265 附近）。

## 6. 迁移与存量处置（Flyway **V34**，当前最高 V33）

顺序：**先清数据再改列**。`team` 里的存量行本来就要丢弃，先加 `NOT NULL` 列会给已有行造成隐式默认值，读起来像迁移在保数据——顺序反过来才与 D6 一致。

```sql
-- 1. D6：存量团队与主管引用一律丢弃
DELETE FROM team_artifact; DELETE FROM team_member; DELETE FROM team;
UPDATE session SET team_id = NULL WHERE team_id IS NOT NULL;   -- 团队会话退化为普通 agent 会话，历史保留
-- 2. team 换列
ALTER TABLE team
    DROP COLUMN lead_agent_id, DROP COLUMN instructions,
    ADD COLUMN system_prompt text NOT NULL COMMENT '...',
    ADD COLUMN model_id bigint NOT NULL COMMENT '...';
-- 3. 主管技能绑定
CREATE TABLE team_skill_binding (...);
```

`harnax-entity/src/test/resources/schema-test.sql` 是手工基线，必须同步镜像（`team` 定义在 V32 之后已有，`session.agent_id` 处需确认口径），否则 mapper 测试以 `BadSqlGrammar` 红。

`lead_agent_id` 上的索引真名是 `idx_lead_agent_id`（`V32__add_team_tables.sql:34`），随列一起被 MySQL 删掉；V34 里那行注释把它写成了 `idx_team_lead_agent_id`。**已应用的迁移不能改**——改一个字就连校验和一起红（本机实测：启动即 `Migration checksum mismatch for version 34`），所以这条口径记在这里，不留进 SQL。

## 7. 测试与验收（TDD：先红后绿）

| 层 | 断言 |
|---|---|
| admin 服务 | 创建团队写入 `team.system_prompt/model_id` 与 `team_skill_binding`，且**不产生任何 agent 行**；缺 `systemPrompt`/`modelId` 被拒；`skillIds` 整表替换；删除团队连带删技能绑定；成员校验四条不变；主管模型四条各自成测（缺失/不可见被拒、停用被拒、非 chat 被拒、他人的公开模型可用）；团队名与 agent 无关（成员可以是任何可用 agent，主管不再是 agent） |
| 共享技能校验 | agent 与 team 两条入口对同一坏输入（缺失/停用/内置 CLI 仓库/同名）给出同一句拒绝 |
| 下发 | `specForTeam` 产出 `agentId=0`、`agentName=团队名`、`skillDetails=团队技能`、`toolDetails/mcpDetails/cliDetails` 恒空；`/agent-spec` 打到团队会话被明确拒绝而非"Agent not found: 0" |
| 会话 | 团队会话 `agent_id IS NULL` 且五项快照来自 team；`SessionResponse.skillList` 对团队会话来自 `team_skill_binding` |
| harness | 主管的 admin 技能进入 `InMemorySkillRepository`（照 `HarnessAgentBuilderSkillTest` 的路子断真产物）；带 resources 的主管技能出 warn 且不抛 |
| mapper | `team` 新列/`team_skill_binding` 读写；`TeamMapper` 不再有 `lead_agent_id` 相关语句 |
| 前端 | `max build` + biome 无新增告警（本仓库 tsc 有既有噪声，不作闸门） |
| 端到端 | docker-new 建一个带 2 个技能的团队，跑一次团队会话：主管回复体现技能文本；成员不受影响；`team_artifact` 交接仍通 |

## 8. 不在范围内

- 渠道（`channel.agent_id`）与定时任务（`agent_task`）指向团队：它们仍只能指向 agent，本次不改。
- 主管沙箱化：主管继续无 shell、无工作区。
- `agent` 表的历史数据：不动。
- 团队模板/多主管：不做。
