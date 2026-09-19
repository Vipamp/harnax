# 技能同步结果可见化与绑定守卫 · 设计规格

- 日期：2026-09-18
- 状态：**设计已获用户批准（含第 1 节 D1~D8）；切片 1~6 已全部实现。后端切片 1~5 逐条红→绿验证；前端切片 6 只做到 `max build` 通过 + biome 无新增告警，尚未在浏览器里验收新徽标与向导校验**。剩余：第 3 节 V31/V33 在 docker-new 真库执行、第 4 节端到端验收。第 0 节的行号是开工前快照，不随改动更新。
- 范围：`harnax-admin`、`harnax-entity`、`harnax-webui`；`harnax-cli` 只受 legacy 端点保留策略影响，不改代码
- 关联文档：[prod_doc/skill-management.zh-CN.md](../../../prod_doc/skill-management.zh-CN.md)（技能域现状事实来源）。本文覆盖其安装语义、disable/删除约束与向导校验三处，其余章节仍为现状。

## 0. 要解决的四个问题

| # | 问题 | 现状证据（2026-09-18 快照） |
|---|---|---|
| ① | 整源失败时什么都不落 | `SkillSourceServiceImpl.kt:109` 的 `loadFromSource` 跑在仓库构造之前，loader 异常直接冒到 `SkillSourceController.kt:68` 的 catch → 500，仓库与原因都不留 |
| ② | 失败明细只活在一条 toast 里 | 后端已把逐技能失败收进 `SkillInstallResponse.failed`，前端 `src/utils/skillInstall.ts` 已按 success/warning/error 三档提示，但 `MAX_DETAIL_ITEMS = 3` 截断且不落库，刷新即失 |
| ③ | 前端仍在混用两代接口 | 源管理已走新 `skill-sources`；选择性同步仍打 legacy `POST /skills/batch`（`SyncSkillModal.tsx:78`）；`SkillSourceUpdateRequest` 在 `src/typings.d.ts:384` 与 `src/services/ant-design-pro/typings.d.ts:263` 重复声明且后者缺 `status`/`isPublic` |
| ④ | disable 与删除没有任何守卫 | `SkillServiceImpl.kt` 的 `toggleSkillStatus` 直接 `updateStatus`，不看 agent 绑定；`SkillSourceServiceImpl.kt:235` 删仓库经 `SkillInstaller.kt:241` 把 `agent_skill_binding` 一并删掉，活着的 agent 静默掉技能 |
| ⑤ | 向导三类校验不对称 | tools/MCP 后端拒绝非法引用（`AgentServiceImpl.kt:406-417`、`:428-439`），skill 解析后从不比对（`:459`）却照样插入绑定（`:487-494`）；前端校验在 `CreateForm.tsx:130-167` 与 `UpdateForm.tsx:220-257` 逐字重复，只在校验"下一步"，5 个 i18n key 缺失因此一直渲染硬编码英文 |

**目标一句话**：每次同步都留下一行可查的结果（含整源失败与空结果），停用/删除与 agent 绑定互为依据，向导里工具/MCP/技能三类引用吃同一套校验和同一句提示。

## 1. 决策清单（已定稿）

| # | 决策 | 选择 | 关键理由 |
|---|---|---|---|
| D1 | 部分失败的放开层级 | 逐技能失败（已支持）之外，**整源失败也允许建仓库**；空结果必须带原因 | 配置写错时"改配置 → 重装"要能走通，而不是每次重建仓库；失败不落库就等于用户只能重来 |
| D2 | 失败明细的落地方式 | `skill_repository` 上存**上次同步结果**（三列），不建历史表 | 回答的是"这个仓库现在能不能用"，历史轨迹无人消费；一张明细表的成本要新表 + 新查询 + 新区块 |
| D3 | 新 API 的收口程度 | `POST /skill-sources/{id}/install` 增可选 `{names}`，前端全切；legacy `/skills/batch` 保留给 CLI 并标 deprecated | 选择性安装的能力后端已有（`SkillInstaller.persist(only=)`），只缺一个新 API 外壳 |
| D4 | "关联"的口径 | 只算 **agent 绑定**；CLI 技能全部在内置仓库，`requireWritable` 已拒绝其一切写，不需要第二套口径 | 用户明确：CLI 绑定所在的固定仓库本就不可操作 |
| D5 | ZIP 上传的整源失败 | **例外，仍然拒绝创建** | 本地不留 archive，建成空壳没有重试路径，只会留一个永久坏仓库。D1 的"空壳可重试"只对 GIT/NPM 成立 |
| D6 | 源里已消失的技能 | 全量安装时以 `stale` **只报告，不删除** | 删除会连带撕掉 agent 绑定，那是比"没更新"更严重的后果 |
| D7 | 重装时内容扫描命中已绑定且启用的技能 | **安全门优先**：允许强制停用，并在同步明细中写明，作为 D4 守卫的唯一例外 | 停用一个被绑定的技能是代价，让风险内容留在 agent 上下文里是另一种代价，后者不可接受 |
| D8 | 硬删单个技能 | 同样要求未被 agent 绑定 | 比 disable 更狠的操作不该有更松的前提 |

## 2. 设计

### 2.1 同步结果成为一等数据（对应 ①②）

`skill_repository` 新增三列（Flyway **V31**，当前最高 V30）：

| 列 | 类型 | 取值 |
|---|---|---|
| `last_sync_time` | `DATETIME NULL` | 从未同步过为 NULL |
| `last_sync_status` | `VARCHAR(16) NULL` | `SUCCESS` \| `PARTIAL` \| `FAILED` \| `EMPTY` |
| `last_sync_detail` | `MEDIUMTEXT NULL` | JSON：`saved` / `installed` / `updated` / `failed[{name,reason}]` / `flagged[{name,reasons}]` / `stale[names]` / `error`。取 MEDIUMTEXT 而非 TEXT 的理由与 V9 相同：一份报告要装下整源技能名 |

状态判定唯一化：`FAILED` = loader 抛异常（D5 的 ZIP 除外）；`EMPTY` = 一条技能都没产出；`PARTIAL` = 有 `failed`；否则 `SUCCESS`。

`stale` 只在源真的被读到过时才计算：整源不可达（`sourceError` 非空）时没有任何证据说明哪个技能从源里消失了，此时比对存量只会把全部技能报成 stale。按名安装（`only != null`）同样不产 `stale`。

四个写入口（`createSkillSource`、`installSkills`、`uploadAndInstall`、`batchSaveSkillsDetailed`）统一在收尾调一次记录器（新增 `SkillSyncRecorder`），不在各处散写 JSON 拼装。

`createSkillSource` 的执行顺序相应调整：loader 异常在 `loadFromSource` 处降级为 `SkillLoadResult(emptyList(), listOf(原因))`，仓库照常插入、技能为 0、结果记 `FAILED`，响应仍是 200 + `SkillSourceInstallResponse`。`installSkills` 对已存在的仓库同样降级，不抛。

空结果归因：GIT 已有 `GitSkillLoader.describeEmptyClone`，给 `ZipSkillLoader`、`NpmSkillLoader` 补同构的 `describeEmpty`，使 `EMPTY` 永远带一句原因，不再是静默 200。

### 2.2 前端显示（对应 ②）

`SkillSourceResponse` 带出 `lastSyncTime` / `lastSyncStatus` / `lastSyncDetail`。仓库列表：行上状态徽标 +「上次同步 N 分钟前」，行内「明细」链接展开一块可滚动区域，显示**全量** `failed` 与 `flagged`（含"因内容扫描已停用"的说明）与 `stale`——左栏是 List 而非 Table，没有表格行展开可用；同步动作结束后重拉列表，使结果常驻，toast 保留但不再独占该职责。

toast 另有一处开工后补的分支：整源失败不产 `failed`（一条技能都没读到），`src/utils/skillInstall.ts` 必须先看 `sourceError`（红色）与 `emptyReason`（警告），否则会拿"源里没有可安装的技能"去描述一个拉取失败——两个不同的问题、两个不同的改法。中英文 case 同步补齐（项目强制规范）。

### 2.3 选择性安装与"更新"语义（对应 ③）

- `POST /api/admin/skill-sources/{id}/install` 接受可选 body `{names:[…]}`，缺省即全量，内部复用 `SkillInstaller.persist(only=…)`；返回 `SkillInstallResponse` 并写入 2.1 的结果。
- `POST /api/admin/skills/batch` 标注 deprecated 并指向新端点，行为不变（CLI 仍用）。
- 前端 `SyncSkillModal.tsx:78` 切到新端点，删除 `skill.ts` 中的 batch 调用。
- 契约漂移一并修：合并两份 `SkillSourceUpdateRequest`（保留字段全的那份）、`failed.reason` 改为与后端一致的非空、`agent.ts:129` 的源查询并入 `skillSource.ts`。
- **不迁移**的部分：`GET /skills/page`、`PUT /skills/toggle/{id}`、`DELETE /skills/{id}` 是按技能寻址的资源，新 API 没有对应物，留在 `/skills`。真正被取代的是 `SkillRepositoryController`（前端 0 调用），本轮不动。
- `PUT /skill-sources/{id}` 仍不自动重拉，"改配置"与"取内容"保持分离。

### 2.4 停用与删除守卫（对应 ④）

新查询 `AgentSkillBindingMapper.selectAgentBindingCounts(ids)`（按 skill_id 分组返回 agent 数，一条 SQL 避免 N+1），D4 与 D8 与响应字段共用同一次查询；仓库侧的"还差几个"由 `SkillMapper.selectEnabledCountsByRepositoryIds(repositoryIds)` 分组产出。

- **停用守卫**：技能被任一 agent 绑定时拒绝 `status 1→0`。覆盖**所有**状态写入口——`PUT /skills/toggle/{id}` 与 `PUT /skills/update/{id}`（后者也路由 `status`，漏一处即等于开后门）。
- **删除守卫**：`deleteSkillSource` 要求该仓库下 `active=1` 的技能全部处于停用态，否则拒绝并说明剩余数量；空仓库（含 D1 建出的失败空壳）直接放行。**开工后评审补一条**：停用不等于解绑，而 D7 正是那条能留下"已停用但仍被绑定"技能的写入，`deleteWithSkills` 的级联会连带删掉活着的 agent 的绑定行——那正是本条守卫要挡的后果。故守卫再看一次 `selectAgentBindingCounts`，有绑定即拒绝。前端按钮仍按用户定的口径（启用数）禁用，绑定这条由服务端兜住并报出原因。
- 硬删单技能按 D8 同规则。
- 前端做成**事前**可见：被绑定的技能开关置禁用态 + tooltip 说明原因；仓库删除按钮在不满足条件时禁用并说明还差几个。需要 `SkillResponse.boundAgentCount`，由同一次查询产出。

### 2.5 向导三类校验对齐（对应 ⑤）

- 后端给 skill 绑定补 tools/MCP 同级校验（`AgentServiceImpl.resolveBindableSkills`）：存在性 + 租户可见 + 未停用（后者守住 2.4 的不变量，CLI 绕过 picker 时同样拦得住），句式沿用 MCP 那条 `MCP server is missing, deleted, or outside your tenant: [ids]`（工具那条不带租户，技能有租户）。写入端同时把一次请求里的重复 id 去重，绑定行按解析到的技能行写，不再按请求 id 写。
- 前端 `CreateForm.tsx:130-167` 与 `UpdateForm.tsx:220-257` 的两份重复抽成单个纯校验函数（`pages/agent/components/configValidation.ts`，三类共用、按 step 或 `'all'` 取首个问题），两个表单的"下一步"与"完成"提交前都过一遍；补齐缺失的 5 个 i18n key；`SkillConfigPanel.tsx:134` 选项去重（tool/mcp 的 picker 已阻止重复，skill 未阻止）。
- `agent_skill_binding` 补 `UNIQUE(agent_id, skill_id)`（Flyway **V33**，V32 已被 team 表占用），对齐 tool（V18）与 MCP（V19）。**迁移须先去重再建索引**，否则存量重复行会让建索引直接失败。

## 3. 兼容与迁移

- V31 三列全部可空，存量仓库 `last_sync_* = NULL` 渲染为"从未同步"，不改写数据。
- V33 先去重（同 `(agent_id, skill_id)` 保留最大 id 行，与 V18/V19 一致）再加唯一索引。索引效果已在 Testcontainers MySQL 8 上红→绿验证（`AgentSkillBindingUniqueIT`）；docker-new 真库的存量重复组计数尚未执行。
- legacy `/skills/batch` 保留，CLI 无需改动；`SkillSourceResponse` 只增字段，对现有消费者是加法。

## 4. 验收

- 每条规则至少一个红→绿用例：整源失败仍建仓库并记 `FAILED`、空结果带原因、被绑定技能停用被拒（两条入口分别覆盖）、未全停用删仓库被拒、绑定停用技能被拒、`stale` 报告、源不可达时不产 `stale`、新端点按名安装、绑定的技能缺失/跨租户/停用被拒、重复 id 收敛成一条绑定、V33 去重迁移。
- `mvn -pl harnax-admin test` 全绿并过 spotless；V31/V33 在 docker-new 真库跑通（尚未执行）。
- 端到端：用已验证可用的 Gitee 仓库建 GIT 源，另用一条故意放坏 `SKILL.md` 的分支验证部分失败在页面上的呈现。
- 部署面：仅 `admin` + `frontend` 两个服务，走 `docker-new/deploy-service.sh`，不需要整栈冷启动。

## 5. 本轮不做（记账）

- 同步结果历史表：D2 明确不建；若将来要"看某仓库的历次同步"再开。
- `SkillRepositoryController`（8 个端点、前端 0 调用）下线清理。
- 前端 `typings.d.ts` 与 `services/ant-design-pro/typings.d.ts` 的全量去重，只处理本轮涉及的 `SkillSource*Request`。
- 重装时自动清理 `stale` 技能（D6 只报不删），需要时按"显式删除端点 + 绑定守卫"另案。
- V33 去重语句只验到"能在 MySQL 上执行"（IT 容器的 Flyway 已真跑一遍该语句），没验"重复组里保留最大 id 行"。要断言就得在共享容器上临时摘掉唯一键再装重复行，风险大于收益；该语句与 V18/V19 已两次在生产跑通的形态同构。
- 评审另四条查过不改：① `savedCount==0 → EMPTY` 排在 `PARTIAL` 前是 §2.1 定的口径，且行上"未保存 N 个"与展开明细照旧带原因；② 前端 `names` 为空数组的第三条语义不可达（同步弹窗先拦"至少选一个"），`{}` 只在"整源重装"按钮上发；③ `failed` 与 `stale` 用同一个 `agentSkill.name.trim()` 取名，不存在目录名/前端名两套键；④ 绑定计数不带租户是有意的——`MybatisTenantInterceptor` 整体注释掉已是空转，共享内置技能的绑定本就跨租户，按租户过滤反而会让守卫漏判。
