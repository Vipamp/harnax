# harnax 支持 self-improving 的落地方案（按 AgentScope 2.0.4 装配）

本文给出 harnax 接入 AgentScope harness「自我改进」能力的完整改造清单，装配形态按 agentscope 2.0.4 的上游 SPI 设计：数据模型、运行时装配、admin 接口、前端、部署与生命周期、分档排期。

## 0. 范围与版本前提

AgentScope harness 里没有单一的「self-improving」开关，它是三条互不包含的机制，本方案只覆盖第一条：

| 机制 | 上游默认状态 | 有效性反馈 | 本方案 |
|---|---|---|---|
| 技能自学习闭环（agent 提议 → 草稿 → 扫描 → 晋升 → 后台整理） | 全关（`skillManageToolEnabled=false`、`skillCuratorEnabled=false`） | **无**：晋升靠人或靠策略，不靠「这个技能有没有让结果变好」 | 做，分三档（§10） |
| 记忆冲刷与固化 | 默认开 | 无 | 不做。harnax 显式关闭自有 PlanNote 与 `agent_state` 承接 |
| 工作区自编辑（`write_file`/`edit_file` 无闸门写工作区） | 默认开 | 无 | 不做。与 §2 不变量 1 冲突 |

因此本文所说 self-improving **仅指技能域**：agent 能否产出技能、产出物怎么进审核流、运营侧能否回答「哪些技能根本没被用过」与「能不能只对一部分用户开一个技能」。

明确不存在的东西，避免按想象设计：上游没有技能效果评分、没有 A/B 对照、没有「按成功率自动晋升」，后台整理里的 LLM 合并是空实现。

**锚点约定**：文中所有上游行号取 agentscope 2.0.4。

**版本前提**：本方案不以升版为前提。逐条核过：技能域这套 SPI 在 harnax 当前锁定的 2.0.2（`pom.xml:39`）上已完整存在且形状相同——`enableSkillManageTool(SkillManageConfig)` 在 2.0.2 的 `HarnessAgent.java:1883`、`enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)` 在 `:1898`，`PromotionDecision.Defer` 与 `SkillCandidate.scriptFiles`（脚本哈希预览）也都已在位。2.0.4 相对 2.0.2 在技能域只多了一样东西：把用量存储抽象成 `SkillUsageBackend` 并补一个跨节点 CAS 的 `BaseStore` 实现；2.0.2 在装配处固定用工作区单文件（`new SkillUsageStore(filesystem)`）。

因此 §3–§8 的改造在两个版本上同形，而 §4.3 与 §11 关于「不采信上游用量后端」的判断在 2.0.2 上更是无需讨论——它根本没有可注入的后端。

## 1. 上游 SPI 的实际形状

设计前先固定这七条事实，它们决定了 harnax 必须自己做什么。

1. **装配入口**：`HarnessAgent.Builder` 提供 `enableSkillManageTool(SkillManageConfig)`（`HarnessAgent.java:2126`）、`enableSkillPromotionGate(SkillPromotionGate, SkillVisibilityFilter)`（`:2141`，闸门与可见性过滤器一次注入）、`environment(String)`（`:2149`）、`enableSkillCurator(SkillCuratorConfig)`（`:2155`）。
2. **必须有 filesystem**：整块装配的前提是 `skillManageToolEnabled && filesystem != null`（`:2783`）。harnax 的团队主管分支从不设 filesystem（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:549-593` 两个分支都以 `!isLead` 为前提），**主管在物理上拿不到技能自写能力**。
3. **一定会长出一个可写的工作区技能仓库**：装配逻辑倒序找「不可写的 `WorkspaceSkillRepository`」去替换（`:2789-2802`）；找不到就**新建一个并追加**（`:2803-2807`）。harnax 调了 `disableDefaultWorkspaceSkills()`，上游的分层函数在该开关下不会生成那个默认的只读工作区仓库（`HarnessAgentBuilderSupport.java:912`），因此 harnax 必然走追加支。这个追加进来的仓库位置在最后，优先级最高。
4. **默认 `mainDir` 就是 `skills`**（`SkillManageConfig.java:35`），而 harnax 的投影器把 admin 下发的技能物化成 `/workspace/skills/<name>/SKILL.md`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/skill/SandboxSkillProjector.kt:19-31`）。两者默认同名 ⇒ 工作区里由 DB 投影来的技能会被当成「工作区技能」再装载一遍，且按第 3 条的优先级在重名时**盖过 admin 的那份**。
5. **草稿不进模型视野**：草稿仓库只是写句柄，没有被加入仓库列表（`:2809-2811` 之后没有 `orderedSkillRepos.add`）。这条性质可以直接依赖，不必另做屏蔽。
6. **用量计数器有出处门槛**：`bumpView`/`bumpUse`/`bumpPatch` 在 `createdBy` 为 null 时静默跳过（`SkillUsageStore.java:184-200`），而 `createdBy` 只由 `markAgentDraft`（`:207`）与 `markAgentCreated`（`:234`）写入。结论：**上游遥测按设计只统计 agent 自产技能，admin 里人工维护的技能永远不会有用量**。
7. **晋升闸门的接口是异步的、决定是三态的**：`Mono<PromotionDecision> review(SkillCandidate, RuntimeContext)`（`SkillPromotionGate.java:46`），决定为 `Approve(reviewerId, targetEnvironments, decidedAt)` / `Reject(reason, reviewerId)` / `Defer(retryAfter, reason)`（`:49-58`）。交给闸门的内容 `SkillCandidate` 包含技能正文、支持文件路径、用量记录、扫描结论，以及每个脚本文件的 head 预览与 sha256（`SkillCandidate.java:29-39`）。默认闸门是「全部拒绝」（`:2837`）。

## 2. 全局不变量

1. **技能真值源唯一在 admin 的 DB**。工作区的草稿目录 `_drafts/` 与晋升暂存只承担「提议暂存」，本身不得成为模型可用的技能加载源，也不得与投影目录同名；唯一进模型视野的那一份是**人工在本会话确认过、复制进 `harnax-skill-staging/session-enabled/<name>/` 的副本**，作用面就是一个会话。
2. **租户判据来自 agent 归属，不来自请求体**。技能相关内容落库时 `tenant_id` 由服务端从会话/agent 反查。
3. **技能资产的生命周期属于租户，不属于 session**。会话清理不得连带删除审核记录与用量；工作区草稿随会话消亡是可以的，因为它的正式形态在 DB。
4. **未过审核的内容不得进入下发集合**。今天由 `status == 0` 与「草稿不在 `skill` 表」共同保证，改动不得破坏。
5. **闸门不得阻塞推理链路**。闸门在 agent 的事件循环内被调用，任何同步外呼都会拖住整轮，实现必须非阻塞。
6. **「本会话即时可用」是一档独立状态，既不等于审核通过也不撤销它**。会话里那一按是**人工确认**（不是模型自己写完就生效），审核批准是**另一次人工确认**，两者由不同的人、审不同的爆炸半径，谁也不按住谁：会话里用了不代表批准过，批准后这个会话也不再依赖会话内那份（会话结束即随容器与快照一起销毁）。会话内用的是**确认那一刻的快照**——复制进 `session-enabled/` 之后 `_drafts/` 再改，本会话读到的仍是那一刻那份；审核看的始终是 `_drafts/` 的最新那份，批准落地写进技能表的是**审后正文**。
7. **同名时交付那份压住会话那份**。会话可用区挂在装配的技能仓库列表里、名次排在 admin 下发那份之后，因此 30 分钟装配快照过期后，同名技能由 DB 那份提供，本会话下一轮起用的就是审后正文。两个例外让会话内那份继续生效到容器销毁：审核把名字改了（rename 到别的 `targetName`），或这条技能压根没绑给这个 agent。

**一条前提：这一整套长在沙箱上。** 草稿的答后上报经容器句柄读盘（拿活句柄读 `_drafts/`，不依赖 agent 实例还活着），会话内启用同样是往那个容器里复制文件，所以 `SANDBOX_ENABLED` 关掉时提名与启用这条特性不工作——队列不会收到任何东西。裸配置缺省即关（`harnax-agent/harnax-agent-service/src/main/resources/application.yml:98` `${SANDBOX_ENABLED:false}`），标准部署显式开启（`harnax-deploy/docker-compose.yml:499` `SANDBOX_ENABLED: "true"`）。给自写了技能却关了沙箱的 agent 装配时会打一条 warn 说明队列不会收到东西，只响亮，不改行为。

## 3. 数据模型

现状：`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:485-504` 的 `skill` 表只有 `status tinyint(1)`（0 禁用 / 1 启用）、`is_public`、`creator varchar(100)`，唯一键是 `(repository_id, active_name)`；没有来源枚举、没有审核表、没有环境维度、没有可见性维度、没有用量。绑定表 `agent_skill_binding`（`:73-82`）与 `team_skill_binding`（`:635-644`）只有主体 id 与技能 id，既无租户列也无状态列。

### 3.1 `skill` 表增列

| 列 | 类型与取值 | 归属与不变量 |
|---|---|---|
| `origin` | `varchar(16) NOT NULL DEFAULT 'human'`，取 `human` / `agent_promoted` | 晋升进来的技能与人工技能的唯一区分。**不复用 `creator`**：它存用户名、无枚举约束、历史值为 NULL，分不清「人写的」和「机器写后被人工复制的」 |
| `origin_ref` | `varchar(64) NULL` | 出处 session id，只写不读、不建索引，供审核页展示 |

`status` **不扩枚举值**。下发侧与 admin 全线按 `skill.status == 0` 判禁用（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:770`；`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SkillServiceImpl.kt:212-223` 的 `toggleSkillStatus` 用 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillSourcePolicy.kt:59` 的 `requireStatus` 校验入参、用 `requireUnbound` 拦「已绑定的技能不许关」）。把草稿塞成第三个值会让每一处 `== 0` 都变成漏网点，草稿一旦被判「非 0」就等价于对外可用。草稿单独成表。

### 3.2 `skill_draft`

审核队列的载体。它的内容由上游闸门递交过来，不是由 admin 自己解析工作区文件。

```sql
CREATE TABLE `skill_draft` (
  `id`             bigint NOT NULL AUTO_INCREMENT,
  `tenant_id`      bigint NOT NULL COMMENT '服务端从 agent 归属反查，不采信请求体',
  `name`           varchar(100) NOT NULL,
  `description`    text,
  `skillmd`        mediumtext NOT NULL,
  `resources`      mediumtext COMMENT 'path -> content 的 JSON，与 skill.resources 同形状',
  `script_previews` mediumtext COMMENT '每个脚本的 relPath/head/totalLines/sha256，取自 SkillCandidate.scriptFiles',
  `scan_verdict`   varchar(16) COMMENT '上游 SkillSecurityScanner 的结论档位',
  `scan_findings`  mediumtext COMMENT '命中项 JSON；晋升时重扫并覆盖',
  `source_session_id` varchar(64) NOT NULL,
  `agent_id`       bigint NULL,
  `status`         varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
  `reviewed_by`    varchar(100) DEFAULT NULL,
  `reviewed_at`    datetime DEFAULT NULL,
  `reject_reason`  varchar(512) DEFAULT NULL,
  `create_time`    datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_draft_tenant_status` (`tenant_id`, `status`),
  KEY `idx_skill_draft_session` (`source_session_id`)
) COMMENT='agent 提议、尚未进入正式区的技能';
```

`status` 取字符串枚举，形状对齐 `mcp_user_credential.status`（`V1__init_schema.sql:323`）。**不设唯一键**：同名提议允许并存，去重发生在晋升时（撞上既有技能就报错，由审核人选替换或改名），写入端绝不覆盖既有技能。`script_previews`/`scan_verdict` 直接承接 `SkillCandidate`，这是按 2.0.4 设计比自建多出来的信息量——审核人看得到每个脚本的哈希。

### 3.3 `skill_visibility_policy`

按用户或按环境收敛可见性的策略，一条技能一行。

```sql
CREATE TABLE `skill_visibility_policy` (
  `id`          bigint NOT NULL AUTO_INCREMENT,
  `skill_id`    bigint NOT NULL,
  `tenant_id`   bigint NOT NULL,
  `mode`        varchar(16) NOT NULL COMMENT 'ALL / CANARY / ALLOW_LIST / ENV',
  `canary_pct`  int NULL COMMENT 'mode=CANARY 时的放量百分比 0-100',
  `user_ids`    text NULL COMMENT 'mode=ALLOW_LIST 时的用户 id 列表 JSON',
  `environments` varchar(255) NULL COMMENT 'mode=ENV 时的环境标签，逗号分隔',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_visibility_policy_skill` (`skill_id`)
) COMMENT='技能运行时可见性策略';
```

无策略行等价于 `ALL`（对全部用户可见）。默认语义必须是放行，否则新技能默认不可见会被当成 bug 报回来。

### 3.4 `skill_usage`

```sql
CREATE TABLE `skill_usage` (
  `id`          bigint NOT NULL AUTO_INCREMENT,
  `skill_id`    bigint NOT NULL,
  `tenant_id`   bigint NOT NULL,
  `user_id`     bigint NULL COMMENT '运行时未透传到具体用户时为空；不得写哨兵值凑非空',
  `event`       varchar(16) NOT NULL COMMENT 'VIEW=被加载进上下文, USE=其指令被实际执行',
  `session_id`  varchar(64) NOT NULL,
  `occurred_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_skill_usage_skill_time` (`skill_id`, `occurred_at`),
  KEY `idx_skill_usage_tenant_skill` (`tenant_id`, `skill_id`)
) COMMENT='技能装载与使用流水';
```

三条约束：键必须是 `skill_id`（`skill.name` 只在仓库内唯一，跨租户可重名，按 name 聚合会串户）；`user_id` 允许 NULL 直到 §4.1 完成；只插不更，读侧聚合。

这张表的存在理由在 §1 第 6 条：**上游遥测按设计不收人工技能**，所以「哪些 admin 技能没人用过」这个问题无论哪个版本都得自己答。上游那份用量（§4.3）只用来跟踪 agent 自产技能的生命周期。

### 3.5 `skill_review_log`

```sql
CREATE TABLE `skill_review_log` (
  `id`          bigint NOT NULL AUTO_INCREMENT,
  `tenant_id`   bigint NOT NULL,
  `subject`     varchar(16) NOT NULL COMMENT 'DRAFT / SKILL',
  `subject_id`  bigint NOT NULL,
  `actor`       varchar(64) NOT NULL COMMENT '真实操作者：sys_user 用户名，或哨兵 agent / system',
  `action`      varchar(32) NOT NULL COMMENT 'PROPOSE / SCAN / APPROVE / REJECT / ENABLE / DISABLE / DELETE / VISIBILITY_CHANGE',
  `detail`      mediumtext COMMENT '扫描命中、拒绝理由、策略变更前后值等 JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_skill_review_log_subject` (`subject`, `subject_id`)
) COMMENT='技能域全部状态迁移的留痕';
```

`actor` 是这张表的意义。上游审计把 `actor` 写死成 `"agent"`，采纳它就再也分不清「agent 自己写的技能」与「运营点了同意」。

绑定表 `agent_skill_binding` / `team_skill_binding` 不在改造范围内：给绑定加状态或加租户列会把「技能审核」和「绑定授权」两条独立变更耦合在一起。

### 3.6 迁移方式

变更折进 `V1__init_schema.sql` 基线并清库重建，`harnax-entity/src/test/resources/schema-test.sql` 是基线的逐字副本必须同步，`SchemaBaselineDriftIT` 守一致（该 IT 只在集成测试档跑）。四张新表与两条增列都不改既有语义，若存在不可重建的线上库，允许写成一条前向迁移。

## 4. 三条前置

### 4.1 `userId` 必须真的进到运行时上下文

`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt:1054-1056` 是 `.userId(userId ?: "")`，调用链上游从不传 userId，运行时永远看到空串——`clearSession` 甚至把它当成既定行为（`HarnessAgentLauncher.kt:772-773`）。可见性过滤器是从 `RuntimeContext` 拿 userId 做分桶的，源头为空则一切按用户维度的能力物理失效。改动是一条链：wrapper 构造参数必须由调用方给真实用户、launcher 从会话元数据取属主、admin 下发规格时带上属主。这是跨模块签名变更，不能用默认值糊过去。

### 4.2 真值源接法：把 `mainDir` 改道

按 §1 第 3、4 条，打开技能管理后一定会多出一个可写工作区仓库，且默认目录与投影目录重合。处理方式是把晋升目录指到一个 harnax 从不读取的路径：

```kotlin
SkillManageConfig.builder()
    .autoPromote(false)          // 保持草稿区
    .securityScan(true)          // 复用上游扫描，结论进 SkillCandidate
    .draftsDir("harnax-skill-staging/_drafts")
    .mainDir("harnax-skill-staging/promoted")
    .build()
```

这样追加进来的仓库指向一个空目录，模型侧可见技能集合仍完全等于 admin 下发的那份，不变量 1 成立，也不需要动投影器。副作用是上游晋升路径（`SkillPromoter` 把草稿 move 到 `mainDir` 并 `markAgentCreated`）产出的文件对模型不可见——所以晋升动作必须由 harnax 完成（§9 的路线 A），这一点在下面明确写成约束而不是巧合。

### 4.3 用量与审核落在 harnax 自己的库

上游用量存 `["skills","usage"]` 命名空间、键就是技能名（`BaseStoreSkillUsageBackend.java:42`），而 harnax 的 MinIO 键布局是 `<keyPrefix><namespace>/<key>`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/minio/MinioBaseStore.kt:137-144`，桶 `harnax-store`、前缀 `store/`）⇒ 落点为 `store/skills/usage/<name>`，**全租户共用一条键**。并且这个后端是从 `distributedStore` 自动选出来的（`HarnessAgent.java:2812-2815`），装配侧无法注入自定义 store 来加租户前缀。结论：上游那份只能当 agent 自产技能的短期状态机用，凡是要按租户统计、按用户灰度、给运营看的，一律走 §3.4/§3.5。

## 5. 运行时装配

改造集中在 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:162-173`：今天它只做 `disableDefaultWorkspaceSkills()` 加一个只读内存仓库。新增一个按 agent 开关的技能自写分支。

1. **只对显式授权的 agent 装配**。`skill_manage` 与 `propose_skill` 直接注册进 toolkit（`HarnessAgent.java:2825-2826`），**不经过 harnax 现有的工具配置过滤器**——上游只对 shell 工具做了 `ToolFilter.isAllowed` 判断（`:2870`）。因此「哪个 agent 能自写技能」必须由 harnax 在装配期决定：给 agent 规格加一个开关，关闭时根本不调 `enableSkillManageTool`。若必须事后摘除，`Toolkit.removeTool(String)` 可用（`agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java:807`），但装配期决定更干净。
2. **注入自写闸门与自写过滤器**：`enableSkillPromotionGate(AdminBackedPromotionGate, TenantSkillVisibilityFilter)`。闸门职责见 §6.1；过滤器职责见本节第 3、4 条。
3. **可见性过滤器自己实现**。上游四个内置过滤器都按「有没有 `createdBy == "agent"` 的用量记录」筛，缺记录一律放行，对 harnax 的 DB 技能是直通。而 `SkillVisibilityFilter.filter(List<AgentSkill>, RuntimeContext)` 是在仓库合并之后、进系统提示之前对**最终列表**调用的（`HarnessAgent.java:2900-2915`），所以自写的过滤器完全有能力按租户/用户/环境收起 admin 技能的可见性。
4. **策略随规格下发，不在过滤器里做同步外呼**。过滤器每轮都会被调用，逐次查 admin 会把审核面变成运行时依赖。做法是在会话规格的技能明细里带上 `visibilityPolicy`（来自 §3.3），过滤器只在内存判定。
5. **装载埋点**。`skill_usage` 的 VIEW 事件在内存仓库的读取入口埋点最省：那个仓库是技能进入上下文的唯一出口（`HarnessAgentBuilder.kt:211-233`）。上游的 `SkillUsageMiddleware` 认的是 `load_skill_through_path`/`read_skill` 两个工具名，harnax 自己的工具流水里名字被拼成 `"$name::$toolName"`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:98`），技能加载工具又不经过 `ToolBox`，因此现有 `tool_call_log` 里没有技能这一维，USE 事件判不出来就只记 VIEW，不要猜。
6. **主管分支**：`filesystem` 缺席意味着技能自写在团队主管上不可用（§1 第 2 条）。可见性过滤器与装载埋点不依赖 filesystem，主管上照常生效。方案不为此改主管的装配（那是另一条待拍板项）。

## 6. admin 侧

### 6.1 闸门实现 `AdminBackedPromotionGate`

实现 `SkillPromotionGate`，把候选写进审核队列：

```
review(candidate, ctx):
  非阻塞 POST /internal/skills/drafts   （body 由 SkillCandidate 映射：正文/资源/脚本预览/扫描结论/出处）
  → 成功返回 Defer(retryAfter = 足够长的间隔, reason = "awaiting human review")
  → 失败返回 Reject(reason, reviewerId = "gate")
```

三条实现要求：必须走响应式客户端（不变量 5，闸门在事件循环里）；`tenant_id` 由 `ctx.sessionId` → `session` → `agent` 反查，不采信请求体（不变量 2）；重复提交由服务端归一——agent 每轮结束都会把本会话暂存的全部草稿重投一遍，同会话同名且 `name`/`description`/`skillmd`/`resources` 四列摘要逐字相同的那次算重投，直接回答已有行的 id，不动 `update_time`、不写轨迹；确有改动的才合并进仍 PENDING 的同名行（`selectPendingByTenantAndName` 跨会话取最近触碰者），该行已被裁决时另起一行。

`Defer` 的实际语义是「草稿原地不动，等外部动作」：晋升器把它转成一条 `PromotionResult.deferred(reason, retryAfter)` 就结束（`SkillPromoter.java:132-134`），`retryAfter` 没有任何内置调度器消费；而 `promote(...)` 的唯一调用方是外部的 `HarnessAgent.promoteSkill`（`HarnessAgent.java:320`）。所以批准动作必须由 harnax 自己闭环，见 §9。

### 6.2 晋升与驳回

两个动作。接口先定形状，页面（§7）只是它的最小输入集：

```
POST /api/admin/skill-drafts/{id}:approve
  body: { expectedDigest, targetRepositoryId?, conflictResolution?: "replace" | "rename", newName? }
  → 200 { skillId, status }            status 由重扫决定，可能仍是禁用
  → 409 { reason: "DRAFT_CHANGED" | "ALREADY_REVIEWED" | "NAME_TAKEN", currentDigest? }

POST /api/admin/skill-drafts/{id}:reject
  body: { reason }
  → 200                                理由同时进 skill_draft.reject_reason 与 skill_review_log.detail
```

四条接口级约束：

- **批准是「晋升」，不是「启用」**。沿用 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillInstaller.kt:189-206` 的 `targetStatus = if (findings.isEmpty()) 1 else 0`：扫描命中就落 `status=0`，等运营在列表里做第二次动作。`SkillInstaller.kt:201-203` 的注释记的正是「禁用是一份故意的隔离」，把两步压成一步会拆掉它。
- **批准必须带内容摘要**。PENDING 期间 agent 还能 patch 同一份草稿（§6.1），`expectedDigest` 按审核人所见内容计算，服务端不匹配就返回 `DRAFT_CHANGED`，重载后重新确认——否则批准的是被改过的正文。
- **状态迁移用条件更新**。`PENDING → APPROVED / REJECTED` 由一条带 `status = 'PENDING'` 条件的 UPDATE 完成，第二个人拿到 `ALREADY_REVIEWED`，不做静默覆盖。
- **落点仓库要么必选要么固定**。`skill` 的唯一键是 `(repository_id, active_name)`，晋升必须有落点。两种定法：给 agent 技能设一个专用仓库，`targetRepositoryId` 就此省略、界面只剩一次确认（推荐）；否则它是必填项且不给默认值。

同名冲突返回 `NAME_TAKEN`，要求审核人显式选 `replace` 或 `rename`（改名要过 `active_name` 唯一性校验），绝不静默覆盖；替换走既有 `updateById` 路径并保持 `status` 仍由重扫决定。

晋升时**必须重扫**（草稿在被提议之后还能被 agent patch），扫描器用 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillContentScanner.kt:42-88` 那 9 条正则；上游 `SkillSecurityScanner` 的档位更细，两份结论都进 `skill_draft`，以 harnax 那份作为是否禁用的判据，避免同一内容有两条互相不知道的保护规则。

下发集合（`InternalApiController.kt:759-781`）不需要改就满足不变量 4，但有一条要复核：该处租户判据允许 `repositoryId == builtinRepositoryId` 的行跨租户通过，所以草稿永远不得借用内置仓库身份。

审核服务不套正式区的校验链：`SkillServiceImpl.kt:252-266` 的 `requireUnbound` 面向已绑定关系，草稿不可能被绑定，套上的后果是删除草稿被「已被 agent 绑定」这类不相干的错误挡住。

### 6.3 可见性策略

`skill_visibility_policy` 的读写接口 + 会话规格下发时把策略带进技能明细。这里要补一条既有口径的复核：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/SkillBindingResolver.kt:112-131` 的 `deliverable` 是下发集合的唯一闸门，策略字段要在这条路径上带出，不要在控制器里另查一次。

## 7. 前端

审核这一屏不许自造词汇。技能域今天唯一的「审核」可见物是同步结果行里可展开的明细块中的「Disabled pending review ({count})」分组（`harnax-webui/src/pages/skill/components/RepositoryList.tsx:160-172`）和一条同义警告（`harnax-webui/src/utils/skillInstall.ts:72-78`），运营的实际动作是把 `StatusSwitch` 再打开一次（`harnax-webui/src/pages/skill/components/SkillList.tsx:124-135`）。草稿页说的是同一件事：扫描命中 → 落库为禁用 → 人来启用。

- **入口**：`harnax-webui/config/routes.ts:147-152` 列表与 `:153-157` 详情挂在一级「优化治理」组（`:119-159`）下，菜单名「技能晋升」；PENDING 计数挂在首页待办那枚入口上（`harnax-webui/src/pages/welcome/sections.tsx:284-289`），不在菜单项上；技能列表加一列 `origin` 区分人工与 agent 晋升。草稿不混进技能列表，理由见下条。
- **草稿为什么不进技能列表**：`harnax-webui/src/components/StatusSwitch/index.tsx:55-64` 的判据是 `status === 1` 与 `checked ? 1 : 0`，只有二值。把 PENDING 塞进去会得到一个「拨上去但内容还没落库」的开关。草稿页用 Tag 表达状态，不给开关。
- **详情容器**：沿用技能详情页的 PageContainer + Card + Tabs 布局（`harnax-webui/src/pages/skill/detail.tsx:535`、`:585`、`:597`），Tab 分「正文」「资源」「脚本」「扫描结论」「出处」。脚本 Tab 展示上游 `SkillCandidate` 已带出的 head 与 sha256（§1），扫描结论 Tab 把 harnax 与上游两份并列并标出哪一份决定禁用，出处 Tab 给 sessionId / agentId / 首次提议时间；出处会话已被清理时显示「出处会话已清理」而不是报错——审核人仍应能就内容本身做决定。
- **人要输入的只有三处**：批准本身是一次点击（§6.2 若定了专用落点仓库，`targetRepositoryId` 就不出现在界面上）；同名冲突时追加一次 `replace` / `rename` 二选一，改名要过 `active_name` 唯一性校验；驳回必须填理由。其余全是展示，取自 `skill_draft` 行，绝不让审核人在页面上重打正文——那是 `expectedDigest` 对不上号的头号来源。
- **批准之后的文案按 `status` 分支**：`status=1` 说「已晋升并启用」；`status=0` 说「已晋升，内容扫描命中 N 条，当前为禁用，需要你手动启用」，并把启用入口直接带上。措辞与 `skillInstall.ts:72-78` 保持一致，两步的语义差别（晋升 ≠ 启用，§6.2）靠这句文案承担，不靠界面布局。
- **两个 409 要有可见后果**：`DRAFT_CHANGED` 说明草稿在你看它之后被 agent patch 过，展示 `currentDigest` 并强制重载 + 重新确认，不能做成静默重试；`ALREADY_REVIEWED` 展示是谁在什么时候以什么理由批/驳的，数据来自 `skill_review_log`。
- **列表列**：名称 / 首次提议时间 / 最近 patch 时间 / 扫描命中数 / 状态 / 操作。最近 patch 时间必须显示，它是 `DRAFT_CHANGED` 的唯一事前预警；PENDING 为空时用 `Empty`（`detail.tsx:3` 已引入）。
- **用量分析页**：读 `skill_usage` 聚合（近 30 天 VIEW/USE、零使用清单），纯只读。
- **iOS**：`harnax-ios` 技能侧是只读消费方，审核不进移动端；改动限于同步过滤，保证草稿态不下发。
- **灰度控制面**：仓库里没有任何 feature-flag 或灰度机制，这一屏从零到有，单独估工，不并入草稿页。
- **不做批量批准**：一次批准要带一份 `expectedDigest`，批量等于用一个摘要盖 N 份不同内容。
- **不做通知内批准**：可以深度链接到草稿页，不能让通知按钮直接打 §6.2 的端点——那会把审核人从「看过内容的人」变成「点过通知的人」，`skill_review_log.actor` 随即失去含义。
- **i18n**：中英文案 key 成对补齐；启用/停用沿用 `pages.common.enabled` / `pages.common.disabled`（`StatusSwitch/index.tsx:58-63`），新增的只有草稿域那几条。

## 8. 部署、生命周期与运维

- **副本数**：`harnax-deploy/docker-compose.yml:463-465` 用 `container_name` 钉死单容器且无 `deploy.replicas`，当前单副本。DB 路线没有跨节点写冲突；上游那份用量若被采信，跨节点一致性要靠 BaseStore 的 CAS（`BaseStoreSkillUsageBackend.java:98-147`，5 次重试后放弃并只留一条 warn），这一点在扩副本前必须重新评估。
- **`clearSession` 清单**：`HarnessAgentLauncher.kt:771-778` 删状态、删 PlanNote、销毁沙箱。要确认草稿的 DB 行、用量、审核记录都不在这条路径上（不变量 3），并把它写成回归测试，而不是靠 review 看代码。
- **投影与暂存互不干扰**：投影器的收敛只删自己清单里列过的路径（`SandboxSkillProjector.kt:198-212`），不会删 harnax 未写过的文件；配合 §4.2 的目录改道，两边在不同目录上工作，不产生互相覆盖。
- **可观测**：晋升与驳回都写 `skill_review_log`，运营要能在技能详情看到「谁在什么时候为什么同意了这个 agent 产的技能」。

## 9. 晋升动作归谁：两条子路线

上游的晋升入口是 `HarnessAgent.promoteSkill(name, reviewerId, ctx)`（`HarnessAgent.java:312-321`），它要求「一个装配了技能管理、且能解析到同一份草稿命名空间的 agent 实例」——javadoc 明确写了会话级工作区必须用带 ctx 的重载，否则草稿解析不到正确命名空间（`:308-310`）。harnax 的草稿落在按 session 分岔的工作区里（`HarnessAgentLauncher.kt:218`，非沙箱档还显式设 `IsolationScope.SESSION`，`:591`），于是：

| 子路线 | 晋升执行者 | 成立性 |
|---|---|---|
| **A 内容回搬**（推荐） | admin 批准后把 `skill_draft` 正文写入 `skill` 表，下一轮由投影器物化进容器；工作区草稿随会话自然消亡 | 成立。真值源唯一（不变量 1）、审核延迟与 session 生命周期解耦（不变量 3）、主管以外的所有分支一视同仁 |
| **B 上游晋升** | agent-service 在批准时用同一 `sessionId` 构造 ctx 调 `promoteSkill`，由 `SkillPromoter` 把草稿 move 进 `mainDir` | 不成立为主路径。审核可能滞后数日，期间会话被清理则草稿永久丢失；且要求为一个审核动作重建 agent 实例 |

也就是说：按 2.0.4 设计仍然要用上游的**提议、扫描、候选装载、闸门协议**，但**晋升语义归 harnax**。这不是打了折扣的方案——上游那条晋升路径本身是为单机/用户级工作区设计的，harnax 的会话级隔离让它在架构上对不上。

顺带一条必须记的：不走 B 就意味着上游的 `markAgentCreated` 不会被调用，`SkillCurator` 那份生命周期（DRAFT/ACTIVE/STALE/ARCHIVED/pinned）对 harnax 的晋升技能也不适用。后台整理因此整体不开启，改由 `skill_usage` 出「长期零装载」清单交人工处置。

## 10. 分档排期

| 档 | 内容 | 前置 | 验收判据 |
|---|---|---|---|
| **L1 用量与审计** | §3.1 增列 + `skill_usage` + `skill_review_log` + §5 第 5 条埋点 + webui 只读分析页 | 无（`user_id` 允许空） | 技能列表能回答「近 30 天零装载的技能有哪些」，计数按 `skill_id` 聚合、跨租户不串 |
| **L2 可见性与灰度** | §3.3 策略表 + 自写 `SkillVisibilityFilter` + 策略随规格下发 + 灰度控制面 + §4.1 userId 透传 | L1；userId 链要改签名 | 同一技能对用户 A 可见、对 B 不可见，且不可见技能不出现在给模型的清单里；分桶用真实 userId |
| **L3 agent 自写技能** | 装配分支（§4.2 目录改道 + §5 第 1 条按 agent 开关）+ `AdminBackedPromotionGate` + `skill_draft` + 审核接口与 UI | L1 的 `origin` 语义、L2 的出处透传与 userId | agent 在会话里产出一份技能 → 出现在待审列表并带脚本哈希 → 批准后进入正式区且只在该租户可见 → 未批准内容在任何下发路径取不到 → 工作区里没有一个技能是从文件加载进来的 |

建议顺序 L1 → L2 → L3，每档独立可交付、独立可回滚，三档都不以升版为前提（§0）。真正吃 2.0.4 的只有一件事：若将来想采信上游那份跨节点用量后端，才必须先升版——而 §11 已把它列为不做项。

## 11. 明确不做与风险

不做：

- 记忆冲刷与固化、工作区自编辑（§0）。
- 上游自带的三个晋升闸门实现与后台整理（`enableSkillCurator`）。内置闸门要么全部拒绝、要么单机交互提示、要么写文件等人回调，都不适合服务端多租户形态；后台整理读的那份用量既不含人工技能（§1 第 6 条）也不分租户（§4.3），且其中的 LLM 合并是空实现。本方案用闸门的**接口**（`SkillPromotionGate`），实现自写（§6.1）。
- 采信上游 BaseStore 用量后端做统计：装配点由 `distributedStore` 决定、不给注入口子，键里没有租户。
- 给绑定表加状态或租户列。
- agent 侧自动改写技能以「提升成功率」：没有任何机制度量成功率，做了只是随机扰动。

风险与未解问题：

1. **`skill_manage` 有六种动作且没有权限判断**。该类直接实现 `AgentTool`（`SkillManageTool.java:60`），没有挂任何权限确认钩子；动作分支为 create/edit/patch/write_file/remove_file/delete（`:257-274`），既无 per-agent 限权也无频控。harnax 侧的两道兜底是「装配期开关」与「批准前不进 DB」，运行时没有第三道；一个被攻破的会话可以持续往自己的暂存目录写垃圾并撑大审核队列，方案里只能靠队列长度与提交频率告警，不承诺自动拦截。
2. **`origin` 只是列，不是约束**。运营手工复制 agent 产的内容进正式区时，与人工技能看不出区别，审核日志是唯一追溯手段——所以 §3.5 不是可选项。
3. **VIEW 与 USE 的界限靠推断**。上游自己的语义是「模型决定调用这一刻」，在工具返回之前就计数（`SkillUsageMiddleware.java:37-40`），且不要求成功。harnax 自建后可以选择更严的判据，但两者都不构成效果信号；看板只能回答「用没用过」，回答不了「有没有用对」，不要把零使用清单直接当成删除依据。
4. **闸门是新的运行时外呼**。它在推理链路内被调用，实现必须非阻塞且失败要吞掉并留下可观测痕迹；把 admin 变成运行时强依赖是这个方案最容易踩的坑。
5. **L2 的 userId 透传触及会话元数据与下发协议**，失败模式是「灰度看起来生效但全落同一桶」。这一档的验收必须含真实多用户对照，单用户验证证明不了它。
6. **目录改道是约定不是强制**。`mainDir` 由配置决定，若将来有人改回默认 `skills`，就会重新与投影目录重合、并让工作区副本在重名时盖过 admin 版本（§1 第 3、4 条）。这条要写成带理由的配置约束加一条启动期断言：晋升目录与投影根目录同名时直接拒绝启动。
