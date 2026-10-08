# 工具 / MCP / CLI 调用指标 · 设计规格

- 日期：2026-10-05，小时档与五维度修订 2026-10-08
- 状态：已实现
- 影响模块：`harnax-agent/harnax-harness-core`（事件源）、`harnax-agent/harnax-tools-sdk`（写入契约）、`harnax-agent/harnax-agent-service`（写入实现）、`harnax-entity`（两张表）、`harnax-admin`（聚合与读接口）、`harnax-webui`（新页面）、`prod_doc`
- 取代：`tool_call_log` 整条链（表、实体、Mapper、Adaptor、`ToolBox` 的日志包裹）；`mcp_call_log` 保留但职责写清

## 0. 需求与现状错位

用户要求：把「监控与治理」里的 tool / MCP / CLI 调用指标补齐并做成能看的页面，此前记录过的日志不完整，重新梳理。

现状与之错位的四处，各自都问过源码：

1. **内置工具只记到自己**。`tool_call_log`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:759-778`）唯一的写入方是 `ToolBox.execute { }`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:88`、`:125`）。它把 `tool_name` 写成 `ToolBox类名::方法名`，与注册身份 `@Tool.name` 不同名；且凡是不走 `ToolBox` 的执行——MCP 工具、harness 自带的 `execute` / `read_file` / `memory_*` / `load_skill_through_path`——一次都不落。
2. **没有任何读者**。`ToolCallLogMapper` 只有一个 `insert`（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapper.kt:15`），`ToolCallLogMapper.xml` 全文 18 行也只有一条 insert。这张表写了三年没有一处 SELECT。
3. **MCP 表不是指标表**。`mcp_call_log`（基线 `:248`）记的是授权动作（`action` 取值 ISSUE / REFRESH / REVOKE / CALL），工具调用一条不落；它是活的 OAuth 账本，不能被指标保留窗口删掉。
4. **CLI 零记录**。CLI 包只在沙箱里以 shell 命令形态被使用，全仓没有一张表记过「某个 CLI 被跑过」。

四处是同一个根因：**记录点挂在实现方式上，而不是挂在执行通道上**。凡是绕过 `ToolBox` 的注册都不被记，而绕过它的注册正是这个项目后来的主要形态。

对照参照物是「监控与治理 → 技能用量」：`skill_usage`（基线 `:584`）有写入方（`SkillUsageAdaptorImpl` 异步批量上报）、有读端点（`/api/admin/skill-usage/summary`）、有页面（`harnax-webui/src/pages/skill/usage.tsx`），但它只报 `VIEW`；页面「执行次数」那一列的提示文案自己承认没有数据来源（`src/locales/zh-CN/pages.ts:209`）。

目标：一个事件源、一张明细、一张小时聚合、三个读端点、一个新页面，覆盖 `builtin` / `mcp` / `cli` / `shell` / `framework` 五种来源，并顺带给技能用量补上 `USE` 的写入方。

## 1. 决策清单（已定稿）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 唯一事件源 = 实现 `MiddlewareBase.onActing` 的中间件，装配期挂在 agent 上 | agentscope 2.0.4 的 `onActing` 收到 `record ActingInput(List<ToolUseBlock> toolCalls)`，`ToolUseBlock` 带 `getId()` / `getName()` / `getInput(): Map<String,Object>`；`ProcessLogMiddleware.kt:86` 已经在用同一个钩子。这是唯一能同时覆盖内置工具、MCP 工具与沙箱 shell 的位置 |
| D2 | 一次工具调用一行，落库时必须是终态 | `ToolResultEndEvent` 同时带 `getToolCallId()`、`getToolCallName()`、`getState()`，按 id 关联成立（`ToolUseBlock.getId()` 可空，退路见 §3）；`ToolResultState.RUNNING` 表示外部执行还没回传，等终态再来。半途的行既进不了成功率也进不了耗时 |
| D3 | 一张明细 + 一张小时聚合；`tool` / `mcp` / `cli` 三档读聚合，按 agent / session 的维度与单次下钻读明细 | 分表才既能删明细（体积）又答得出跨保留期的趋势。agent / session 不进聚合，它们的基数不受控 |
| D4 | `kind` 只用装配期已知事实判定，不回查数据库 | 运行侧没有 admin 的表；一次回查把计数器变成每个工具调用一次 admin 往返 |
| D5 | CLI 口径 = 解析 shell 工具的 `command` 参数归因，不新增 `run_cli` 工具 | 新增工具会改模型可见的工具面与每个包 `SKILL.md` 的写法，属产品级重构；shell 归因零侵入且反映真实使用 |
| D6 | MCP 归因在装配完成后枚举已组装好的 `Toolkit` 反查所属 server；`mcp_call_log` 原样保留 | 枚举读的是模型实际看见的那份注册结果，同名覆盖与注册失败都自然反映，且不必对每个 server 多发一次 `listTools()`。可行性见 §4 |
| D7 | 技能 `USE` = 模型主动调 `load_skill_through_path` 取正文，落进现有 `skill_usage` | 上游把工具名钉死为 `load_skill_through_path`（`agentscope-core` 的 `SkillToolFactory.java:49`），参数是 `skillId` + `path`，是「指令被取用」的最强证据 |
| D8 | `tool_call_log` 整链删除，物理表由前向增量 `V4__drop_tool_call_log.sql` 删 | 2026-10-05 明确「完全按全新设计，不考虑历史兼容」。新事件源覆盖它的覆盖面，而它的 `tool_name` 与注册身份对不上，留着只会多一个口径。基线里的建表块改不动（V1 已应用，动一个字节即校验和不符），所以删除动作只能是一条 DROP 增量 |
| D9 | 写入 = 有界队列 + 批量 insert + 丢弃计数（队列拒收与写库被拒都计入同一个数）；绝不在工具执行线程同步落库，绝不抛 | 与 `SkillUsageAdaptorImpl.kt:35-78` 同形。计数器不值得让一轮回答去等一次数据库往返，更不值得因它失败 |
| D10 | 聚合与清理都在 admin，每小时第 5 分三步：补齐缺失小时 → 逐小时幂等重算（每次带上上一小时与当前小时）→ 只删已折算且过窗的明细 | 补齐使重算自愈（漏跑一个周期、首次上线都不需要 backfill 开关）；删除挂在「已折算」这个事实上而不是挂在时间算术上，多副本同时跑无害。当前小时也在重算之列，页面才不会永远落后一个整点 |
| D11 | P95 由时长分桶近似，页面标明是近似值 | 聚合表只有计数列，真分位数要留全部明细才算得出 |
| D12 | 租户只由服务端决定：读侧 `TenantResolver.resolve(jwtUtil)`，写侧装配期随行携带 | 与 `TokenStatsController.kt:37`、`SkillUsageController` 同规。查询参数里出现 `tenantId` 等于给了跨租户读数的口子 |
| D13 | 明细保留 90 天，`args_json` / `result_excerpt` 截断到 2000 字符，两条都可配可关 | 命令行参数与工具回执里会出现凭据字面值，默认截断 + 可整体关闭 |
| D14 | 窗口是一条小时区间 `start` / `end`（`yyyy-MM-dd HH:mm`，两端都是含端点的整点起点），不是天数 | 三个读端点与下钻共用同一个区间，才有「选一次时间，整页图形跟着变」。天数与区间是同一状态的两种形态，同时留就等于给一次请求留一个「谁赢」的问题；预设（近 7 / 30 / 90 / 365 天）改成纯前端的时间算术，服务端只认区间。只到小时是钉死的口径：聚合的存储粒度就是一小时，选择器给到分秒只会让页面问出一个存储答不出的边界 |
| D15 | 行名列与调用量列各开一个抽屉：前者给这一行主体的档案加本窗口指标，后者给单次调用记录 | 行名读作「这是谁」，计数读作「这几次是什么」。档案来源按维度分流：`agent` 查 agent 行、`mcp` 查 server 行、`cli` 查包行、`session` 按会话 id 字符串查会话行、`builtin` 从内置工具列表按名匹配；`shell` / `framework` 与已注销的工具没有登记行，抽屉只给指标并明说无档案 |
| D16 | 档位显示名：`builtin` 叫「可选工具」，`framework` 叫「系统内置」 | 「可选工具」是这套东西在别处的既有叫法（`/api/admin/tools/available`、iOS 的「暂无可选工具」、`prod_doc/tool-integration-design` 的可选工具/必须工具之分），页面上再叫「下发工具」等于同一物件两个名字；「系统内置」对「可选」才是同一把尺子的两端 |
| D17 | 分组维度按 tab 给矩阵：工具 tab＝工具／智能体／会话，MCP tab＝MCP／智能体／会话，CLI tab＝CLI／智能体／会话 | 三个 tab 共用一份「按工具／按智能体／按会话」时，MCP tab 里的「按工具」列的是一台服务器暴露的每个工具，想按服务器看一行汇总没有档，CLI 同理。新增 `mcp`、`cli` 两个维度，切 tab 时当前档位不在矩阵里就收敛到该 tab 的第一档 |
| D18 | 行名由服务端 SQL 带出 `subjectName`，前端不逐行查登记接口；解析不到时回落原 key | 一页几十行，逐行查登记接口等于把一张表变成几十次往返。回落而不是留空：登记行已删（服务器删了而调用记录还在）与 `chn-` / `task-` 这类在 `session` 表本就没有行的会话 id，都要读得出「是哪一行」，空白格只会让人以为数据缺了 |

## 2. 数据模型

两张表走 `harnax-admin` 的前向增量 `V3__tool_invocation_metrics.sql`，不折进基线。README（`harnax-admin/src/main/resources/db/migration/README.md`）的默认路径是「改基线与重建库是一个动作」，而这个库留着只有人能重填的模型 provider api_key，属于它写明的例外：既有库因此照常启动并由 Flyway 补放 V3，新建的库重放 V1 → V2 → V3 → V4 → V5 落到同一个形状，下一次清库重建时把 V3、V4、V5 折回基线。删旧表也走同一条通道：`V4__drop_tool_call_log.sql` 是一条 `DROP TABLE IF EXISTS`，它删的是 V1 建出的那张表（建表块本身改不动，见 §10）。聚合表由「一天一行」升到「一小时一行」同样走这条通道：`V5__tool_invocation_stats_hourly.sql` 改列（`stat_hour datetime`）、换唯一键与二级索引，并在改列前 `DELETE FROM tool_invocation_stats`——旧的一天一行若原样搬成小时档，会被读作「00:00 这一小时装了一天的量」，而待折算集合又已认定那小时折过，明细于是被放行删除、再也重折不出来。清掉不是丢数据：保留窗口内的明细全在，下一轮折算逐小时重折即可，窗口本身有界。`harnax-entity/src/test/resources/schema-test.sql` 仍**逐字**同步——漂移由 `SchemaBaselineDriftIT` 守，它比的是 Flyway 最终建出的表、列与索引名，不看名字来自哪一个文件，所以增量与基线两侧的改动都要并进这份副本，DROP 掉的表在副本里同样不能留。

命名跟 `docs/database-design-conventions.md:30`：日志/统计表用 `_log` / `_stats` 后缀，所以聚合表叫 `tool_invocation_stats` 而不是 `..._daily`。索引按最新的 `skill_usage` 形状用表名限定的 `idx_tool_invocation_log_*` / `uk_tool_invocation_stats_*`；列注释一律英文；基线里每个建表块带 `/*!40101 SET character_set_client ... */` 三行守卫，增量文件里则是裸 `CREATE TABLE`，两者建出的形状一致。

### 2.1 `tool_invocation_log`（明细，append-only）

```sql
CREATE TABLE IF NOT EXISTS `tool_invocation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Invocation event ID',
  `tenant_id` bigint DEFAULT NULL COMMENT 'Owning tenant; NULL when the delivered spec named none, and no tenant-scoped read returns such a row',
  `agent_id` bigint DEFAULT NULL COMMENT 'Owning agent; NULL for a team lead, which has no agent row',
  `session_id` varchar(255) DEFAULT NULL COMMENT 'Session that produced the call',
  `user_id` bigint DEFAULT NULL COMMENT 'End user behind the call, NULL for channel sessions and service keys',
  `kind` varchar(16) NOT NULL COMMENT 'Origin (builtin: delivered tool, mcp: MCP server tool, cli: delivered CLI package run through the shell, shell: bare shell command, framework: harness built-in)',
  `tool_name` varchar(255) NOT NULL COMMENT 'Tool name as the model sees it; the matched command name when kind = cli',
  `mcp_id` bigint DEFAULT NULL COMMENT 'MCP server row, set only when kind = mcp',
  `cli_id` bigint DEFAULT NULL COMMENT 'CLI package row, set only when kind = cli',
  `outcome` varchar(16) NOT NULL COMMENT 'Terminal state (SUCCESS, ERROR, DENIED, INTERRUPTED)',
  `error_message` varchar(512) DEFAULT NULL COMMENT 'Failure reason, truncated',
  `args_json` text COMMENT 'Tool input as JSON, truncated; NULL when payload capture is off',
  `result_excerpt` text COMMENT 'Leading part of the tool result, truncated; NULL when payload capture is off',
  `duration_ms` bigint NOT NULL COMMENT 'End time minus start time',
  `start_time` datetime(3) DEFAULT NULL COMMENT 'Call start; milliseconds because a second-resolution column collapses two calls inside one second onto one instant',
  `end_time` datetime(3) DEFAULT NULL COMMENT 'Call end',
  `ts` datetime(3) NOT NULL COMMENT 'Recorded time, equal to end_time; aggregation and indexes key on it',
  PRIMARY KEY (`id`),
  KEY `idx_tool_invocation_log_tenant_ts` (`tenant_id`,`ts`),
  KEY `idx_tool_invocation_log_tenant_kind_ts` (`tenant_id`,`kind`,`ts`),
  KEY `idx_tool_invocation_log_mcp_ts` (`mcp_id`,`ts`),
  KEY `idx_tool_invocation_log_cli_ts` (`cli_id`,`ts`),
  KEY `idx_tool_invocation_log_session` (`session_id`),
  KEY `idx_tool_invocation_log_tool_name` (`tool_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='One row per tool invocation, kept for a bounded window';
```

`tenant_id` 可空跟 `token_stats:748` 同形（`AgentSpec.tenantId` 本身可空，不猜工作区）；`agent_id` 可空沿用 `AgentSpec.attributableAgentId`（团队主管无 `agent` 行）。

### 2.2 `tool_invocation_stats`（小时聚合，永久）

唯一键 `(stat_hour, tenant_id, kind, subject_id, tool_name)`。

```sql
CREATE TABLE IF NOT EXISTS `tool_invocation_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Aggregate row ID',
  `stat_hour` datetime NOT NULL COMMENT 'Hour the calls fall in, `tool_invocation_log.ts` truncated to the hour; minutes and seconds are always zero',
  `tenant_id` bigint NOT NULL COMMENT 'Owning tenant; detail rows without one are not aggregated at all',
  `kind` varchar(16) NOT NULL COMMENT 'Origin bucket, same vocabulary as the detail table',
  `subject_id` bigint NOT NULL DEFAULT '0' COMMENT 'mcp_id when kind = mcp, cli_id when kind = cli, 0 otherwise; 0 rather than NULL because a unique index does not treat NULLs as equal, and NULL would make the upsert insert a second row for the same hour',
  `tool_name` varchar(255) NOT NULL DEFAULT '' COMMENT 'Tool name as the model sees it; every kind carries it, so two tools of one MCP server are two rows in an hour',
  `calls` int NOT NULL COMMENT 'Total invocations',
  `successes` int NOT NULL COMMENT 'Invocations ending SUCCESS',
  `errors` int NOT NULL COMMENT 'Invocations ending ERROR',
  `denials` int NOT NULL COMMENT 'Invocations ending DENIED',
  `interruptions` int NOT NULL COMMENT 'Invocations ending INTERRUPTED',
  `sum_duration_ms` bigint NOT NULL COMMENT 'Duration total; the mean is this divided by calls',
  `max_duration_ms` bigint NOT NULL COMMENT 'Longest single call of the hour',
  `le_100ms` int NOT NULL DEFAULT '0' COMMENT 'Calls of at most 100 ms',
  `le_500ms` int NOT NULL DEFAULT '0' COMMENT 'Calls over 100 ms and at most 500 ms',
  `le_2s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 500 ms and at most 2 s',
  `le_10s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 2 s and at most 10 s',
  `le_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 10 s and at most 30 s',
  `gt_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 30 s',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_invocation_stats_hour` (`stat_hour`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
  KEY `idx_tool_invocation_stats_tenant_hour` (`tenant_id`,`stat_hour`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Hourly rollup of tool_invocation_log; rows are kept permanently';
```

表里**只存小时一档**。计数四终态、六个耗时桶、`sum_duration_ms` 都是按小时可加的量，`max_duration_ms` 是按小时取 max 也可加，所以页面上的日／周／月视图就是这些行按桶求和，不需要第二套行；同时存两档的话任何一次跨粒度求和都会把同一时刻数两遍，而没有一种读法需要它。代价是小时行的量是日行的 24 倍，而这张表按永久保留设计。

六桶是**半开区间**（`le_500ms` = `(100, 500]`），否则「正好 500ms」会被两个桶重复认领，`calls` 与桶和就不恒等了。

### 2.3 不变量

- I1 一次工具调用至多一行明细；`kind` 与 `mcp_id` / `cli_id` 的填充关系由 §3 的判定顺序唯一决定。
- I2 `outcome` 只取四个终态值，`RUNNING` 永不落库。
- I3 明细行的 `tenant_id` 与 `agent_id` 在写入时就定死，读侧不再猜。
- I4 聚合任一行的 `calls` = 四个终态计数之和 = 六个桶之和。
- I5 聚合可整体重算且结果不变（幂等），因此重算与并发副本都无害。
- I6 任一明细小时在被折算进聚合之前不会被删除（删除语句以「该 `(小时, tenant_id)` 已存在于聚合表」为条件，小时取 `ts` 向下整点到小时）。唯一的例外是 `tenant_id IS NULL` 的明细：聚合表那列 `NOT NULL`（§2.2）让它们不进任何聚合，因此只看保留窗口，否则永远删不掉。

## 3. 事件源与判定

新增 `ToolInvocationMiddleware`，与 `ProcessLogMiddleware` 同包同目录：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt`；挂载沿用 `agentBuilder.addMiddleware(...)`（现例 `HarnessAgentLauncher.kt:587`、`:601`）。中间件每次装配新建一个实例，与 `ProcessLogMiddleware` 的 `initial()` 教训同因（同文件 `:585-592` 的注释记录了共享实例如何把两 Sessions 的归属写串）。

`onActing` 内为本次 acting 批次建一张起点表，键取 `toolCallId`、缺 id 时退到 `toolCallName`，从 `input.toolCalls` 起表（记 `name`、`input`、开始时刻）；同一批次算出同一个键的调用（同名的无 id 调用）在键尾加序号，另按 `name` 保存一组未终态键的先进先出队列，于是每个起点都可寻址。`ToolUseBlock` 的名字上游可空（它由模型给的 JSON 反序列化而来，上游构造器不校验）：无名的调用既算不出键、也没有可写的 `tool_name`（该列 `NOT NULL`），因此不进起点表、不落库；登记这一步本身绝不把异常抛出 `onActing`，D9 的「绝不抛」在事件流开始之前同样成立。在 `next.apply(input)` 的事件流上：

| 事件 | 动作 |
|---|---|
| `TOOL_RESULT_END` | 先按 `key(toolCallId, toolCallName)` 命中未终态起点，未命中时取该 `toolCallName` 队列里最早的一个（一轮之内模型按发出的顺序收到自己的答复，FIFO 是唯一站得住的猜测，猜错的代价是时长对错了起点，不是丢一行；匹配过程只 peek，不在队列里摘键；队列与起点表按键一一对应，登记时同处加入、投递与收流两条路径都按起点记录的 `name` 同处摘除，所以 peek 到的必是一个未终态起点），出 `outcome` 与 `duration_ms`，投递适配器；`toolCallName` 与起点名字不符时以起点记录的 `name` 为准并 warn。起点在投递之前从表里摘除，队列里的同一个键按起点记录的 `name` 定位摘除，而不是按终态帧的 `toolCallName`——按帧名摘除会在队列里留下一个已经消费过的键，下一个同名调用会被这个幽灵键顶掉而丢一行。同一个 id 重复的终态帧，在该名字没有其它未终态起点时找不到起点、不会再落第二行；但若此刻还有同名的调用在飞，兜底是按帧的 `toolCallName` 而不是按 id 找队列的（`matchKey`），它会把那一个起点顶掉——重复帧落一行，被顶掉的真调用此后既无起点也不再出现在收流兜底里（`started` 与队列都已不再持它的键），整条消失。这是「匹配而不是猜」之外唯一剩下的猜，触发前提只有一个：同一个终态帧到达两次。 |
| 流 `onComplete` 仍有未终态 id | 补一行 `INTERRUPTED`，时长到完成时刻，`error_message` 写 `stream ended before the tool returned` |
| 流 `onError` / `onCancel` | 同上，`error_message` 取异常文本；该调用此前已经流出的增量文本仍进 `result_excerpt`（按写入侧截断），因为一次中断最有用的信息就是它停下来之前说了什么 |

增量文本按事件自带的键累积（有 `toolCallId` 用 id，缺 id 用名字），投递时先按起点键取、取不到再按终态帧自己的键取，因此一侧带 id、另一侧缺 id 的配对不会把已经流出的正文丢掉；未投递的起点在收流结束时同样先按自己的键取、再按记录的 `name` 取。真正共享一份缓冲的是帧侧无可分辨键的同名调用：delta 不带 id 时两侧的增量都落进同一个名字键缓冲，与那两个起点自己有没有 id 无关。这种情况下能保住的是行数，文本归并是已知让步，而归并后的正文落在哪一行取决于收流时的遍历顺序，不保证稳定。另一侧的损失同样已知：一帧终态既不带 id、其 `toolCallName` 又撞不到任何名字队列时配不上起点，那一次调用由收流兜底记成 `INTERRUPTED`。这里不引入「本轮只剩一个未终态起点就把它配上」的回退——那已经是猜，而猜错会把一次真正中断的调用记成成功，按 I4 那一行就从聚合表的 `interruptions` 挪进 `successes`，两个计数同时错位；记成中断至少是一个可数的损失。每个键的缓冲另有一个堆上界 32,000 字符（`MAX_ACCUMULATED_RESULT_CHARS`），它故意设在写入侧 `capture-max-chars` 的可配上限 20,000（`MAX_CAPTURE_MAX_CHARS`）之上：到这一层才被截掉的正文本来就长过任何列装得下的量，所以截断的语义仍然只有写入侧一处。

`ToolResultState` 映射：`SUCCESS→SUCCESS`、`ERROR→ERROR`、`DENIED→DENIED`、`INTERRUPTED→INTERRUPTED`、`RUNNING→不落库`。

`kind` 判定顺序，第一个命中即定：

| 序 | 条件 | 结果 |
|---|---|---|
| 1 | 工具名在 MCP 映射表里（该表由最终注册表枚举而来） | `kind=mcp`，`mcp_id` 取映射值 |
| 2 | 工具名是 shell 工具且 `command` 命中本会话已下发 CLI | `kind=cli`，`cli_id` 与 `tool_name`（命中的命令名）来自 CLI 映射 |
| 3 | 工具名是 shell 工具但未命中 | `kind=shell` |
| 4 | 工具名在 `AgentSpec.toolSpecs[].toolName` 里 | `kind=builtin` |
| 5 | 其余 | `kind=framework` |

shell 工具名有两个来源，都认：harness 的 `ShellExecuteTool.NAME = "execute"`（`agentscope-harness` 的 `io.agentscope.harness.agent.tool.ShellExecuteTool:32`）与 core 的 `ShellCommandTool`（`execute_shell_command`，`agentscope-core` 的 `io.agentscope.core.tool.coding.ShellCommandTool:354`）；参数名都是 `command`。命中 CLI 之后仍只记一行，命令串里出现第二个 CLI 不复制行（见 §12）。

判定与归因写成一个纯函数，输入 `(toolName, input, mcpIdsByTool, cliIdsByCommand, builtinToolNames)`，输出一个 `InvocationKind` 值对象（`kind` / `mcpId` / `cliId` / 归因命令名）；中间件只做收集，便于单测穷举。落点同目录 `ToolInvocationClassifier.kt`。

技能装载工具 `load_skill_through_path` 不在 `toolSpecs` 里，因此落 `kind=framework`——技能不是五种 `kind` 之一，它的 USE 只进 `skill_usage`（§9）。

## 4. 装配期三张映射

都在 `HarnessAgentLauncher` 建 agent 时组好，交给中间件构造器，与 `SkillViewRecorder` 现在的归因方式同形（`HarnessAgentLauncher.kt:472`、`:490`）。

- **MCP**：全部 `addMcp` 完成后枚举已组装好的 `Toolkit`——`getToolNames()` 逐个 `getTool(name)`，命中 `McpTool` 的取 `getClientName()`（上游返回 `clientWrapper.getName()`，而本项目的 `McpHelper` 用 `McpClientBuilder.create(mcpConfig.name)` 建 wrapper，见 `harnax-agent/harnax-agent-utils/.../McpHelper.kt:186-205`），再与本会话 `mcpServices` 的 name→id 对上。可行性三条已核：`HarnessAgentBuilder.addMcp` 是 `registerMcpClient(...).block()`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:76-80`，同步，注册当场生效）；`toolkit` 是该类的私有字段，需新增一个只读访问器；`HarnessAgent.Builder.build()` 会把这份 toolkit 深拷贝后再挂 harness 自带工具，所以枚举到的是「MCP + `addTool` 那一半」，不含 `execute` / `read_file`——本节只要 MCP 归属，不含也够用。
- **CLI**：`agentSpec.cliSpecs` 每条贡献一个键集——包名 `name` 并上它的 `checkCommand` **每个分段首词去掉路径前缀后的名字**（`ToolInvocationClassifier.commandHeads` 按 `|` / `||` / `&&` / `;` 拆段并跳过 `VAR=value` 前缀，见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifier.kt:90-103`；`CliSpec` 字段见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:175-186`）。键集落在 `cliIdsByCommand`（`HarnessAgentLauncher.kt:619-623`），一个名字对应一条 `cliId`；live 命令侧同样先 `substringAfterLast('/')`，所以模型写 `./bin/foo` 或 `/usr/local/bin/foo` 都能命中包名。包物化与这份键集无关：包内二进制既不在包名里、也不在 `checkCommand` 任一分段的首词里时就归因不到（见 §12）。
- **技能**：沿用 `SkillViewRecorder.attribute(skillName, skillId)` 那条映射，再并上 `AgentSkill.getSkillId()`（上游实现为 `name + "_" + source`，`agentscope-core` 的 `AgentSkill.java:267`）→ `skill.id`，供 `load_skill_through_path` 的 `skillId` 参数反解。

## 5. 写入链路

```
ToolInvocationMiddleware → ToolInvocationAdaptor(harnax-tools-sdk 定义)
  → ToolInvocationAdaptorImpl(harnax-agent-service：单线程 + 有界队列 + 批量 insert)
  → ToolInvocationLogMapper.batchInsert → tool_invocation_log
```

配置项（`harness.metrics.invocation.*`，agent-service 侧）：写侧归 `harness.*` 家族——那是该服务放中间件装配开关的前缀（`harnax-agent/harnax-agent-service/src/main/resources/application.yml:42`），`harnax.*` 则放服务基础设施（同文件 `:137`）。§6 的聚合与清理跑在 admin 进程里，键因此落在另一个前缀下，两侧不是同一份配置。

| 键 | 默认 | 作用 |
|---|---|---|
| `enabled` | true | 关掉就整个中间件不装，一行不记 |
| `queue-capacity` | 512 | |
| `batch-size` | 64 | |
| `flush-interval-ms` | 200 | 不满一批也在此间隔落库 |
| `capture-payload` | true | 只管这两列：关时 `args_json` 与 `result_excerpt` 恒为 NULL；`error_message` 不受它控制，因为非终态调用的失败原因就是工具自己的输出（§3） |
| `capture-max-chars` | 2000 | 两处各自的截断长度，尾部加 `…(truncated)` |

适配器契约与 `SkillUsageAdaptor` 一致：`emit` 立即返回、永不抛。丢的行都加到同一个计数上，因为「页面是空的」需要一个数来解释它，而三条路都会造成那个空页面：队列打满拒收新事件、`shutdown()` 已把 `running` 置 false 之后的拒收（并入前者而不是另立一个数）、以及一批已经离队的行被数据库整批拒绝——最后一处由 `flushBatch` 统一吸收并计数，排空、收尾与稳态循环共用这一层守卫，而不是三份各自的策略。warn 的密度按损失形状分开：按条的损失第 1 条与之后每 50 条各一次（`DROP_LOG_EVERY`）并附累计数，按批的损失每个失败批一次。

打满意味着工具调用已经每秒数百次——那时少记几行比让整轮回答卡在数据库往返上更划算。写库被拒的批同样释放而不重试：重试会在库挂着的时候空转，而它身后排着的是计数器。

`batchInsert` 用 `foreach` 多行 VALUES（现例 `harnax-entity/src/main/resources/mapper/MpChatMessageMapper.xml:25`），空批在 Kotlin 侧早退，不能把空列表交给 `foreach` 生成非法 SQL。Mapper XML 注释里不许出现 `--`，`MapperXmlParseTest` 会解析每一份 XML 并因此炸掉全部服务的 `SqlSessionFactory`。

## 6. 聚合与清理

落在 admin，新增 `ToolInvocationRollupService` + 一个 `@Scheduled` 入口。admin 此前没有任何 `@Scheduled`（`docs/deploy-harnax-admin.md:166` 明写「调度全在独立服务 `harnax-scheduler`」），需要在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt` 补 `@EnableScheduling`。

**不引入分布式锁**：仓里没有 ShedLock，也没有锁表；`harnax-scheduler` 那套 Quartz JDBC 集群（`harnax-scheduler/src/main/resources/application.yml:56`、`:94`）与本表无关。并发安全由下面第 2 步的幂等性兜住。

每次运行按顺序做三件事：

1. 取明细里 `tenant_id IS NOT NULL` 的出现过的全部小时（`DATE_FORMAT(ts, '%Y-%m-%d %H:00:00')`），与 `tool_invocation_stats` 已有的 `stat_hour` 相减，得到「该折算却没折算」的小时集合。无租户的明细排除在外，否则那些小时每小时都被报成待折算、而聚合又永远不会为它们产生行（聚合表 `tenant_id NOT NULL`），差集就补不完。下界写死 `1970-01-01 00:00:00` 而不是一个滚动的起点：任何下界都会让一个从未折过的小时掉出待折算集合而永远不折，而下一条的删除恰恰拒绝释放这样的小时里的行。下界比的是 `l.ts` 而不是它的截断值——对列取函数用不上索引；差集用一次 `NOT EXISTS` 加 `GROUP BY` 而不是对聚合表做子查询差，一趟 `(tenant_id, ts)` 索引就够。
2. 对这个集合**加上当前小时与上一小时**逐小时重算，一条 `INSERT INTO tool_invocation_stats SELECT ... FROM tool_invocation_log WHERE ts >= ? AND ts < DATE_ADD(?, INTERVAL 1 HOUR) GROUP BY ... ON DUPLICATE KEY UPDATE` 整行覆盖（小时用瞬间区间圈而不用 `DATE_FORMAT(ts, ...) = ?`，因为对列取函数用不上索引）。未来的小时直接跳过，晚于当前小时的参数永远不折。当前小时必须在集合里，不管它有没有出现在第 1 步的差集：它还在被写入，等它关掉再折会让页面每次都少读一个小时。上一小时同样必须在集合里：清理跑在每小时第 5 分，一个小时的最后一次折算发生在它关闭之前，此后到整点之间落进来的明细再不会把那一小时报成待折算，而保留窗口一到就把它们删走——不重算上一小时，每个小时结尾那一段就是永久少计。漏跑一个周期或首次上线由第 1 步的差集兜住，不需要额外的 backfill 入口。
3. 删除 `ts < now - retentionDays` 且（其 `(小时, tenant_id)` 已存在于聚合表 **或** `tenant_id IS NULL`）的明细。删除挂在「已折算」这个事实上的理由见 I6；无租户那半是唯一的例外出口，它们不进聚合，因此不能等聚合来放行。

`harnax.metrics.retention-days` 默认 90、`harnax.metrics.rollup-enabled` 默认 true，调度表达式每小时第 5 分。保留天数在服务启动时夹进 `1..3650` 并在夹动时 warn 一条而不是拒启动：`retention-days: 0` 会让清理的界限等于运行瞬间，下一个 :05 就释放掉所有已折算小时的明细——这是这一层唯一会毁数据而不是只让计数停住的错配；上限不是列宽而是一道地平线：聚合行永久保留，调大保留天数只把明细能答的窗口往外延。两个键进 `harnax-admin/src/main/resources/application.yml` 的 `harnax:` 块（`:135-172`，与 `harnax.cli.archive-retention-days` 同形，用构造器 `@Value` 注入——全仓 `@ConfigurationProperties` 只有一处且是因为要 `@ConditionalOnProperty`）。

分位数：桶边界 100 / 500 / 2000 / 10000 / 30000 ms。P95 的算法是「按桶累加到 ≥ 0.95·calls，落在哪个桶就报该桶上界，落进 `gt_30s` 报 `>30s`」。窗口内明细未过期时，`/invocations` 给出的单次真耗时是复核依据。

## 7. 读侧 API

`ToolMetricsController`，前缀 `/api/admin/tool-metrics`，与 `SkillUsageController` / `TokenStatsController` 同规：Kotlin 主构造器注入（admin 内零 `@RequiredArgsConstructor`）、`@Tag` / `@Operation` / `@Parameter` 齐全、方法体是 `= try { ... } catch (e: Exception) { log.error(...); ResultVo.error(ApiErrors.message(e, fallback)) }` 表达式函数、租户走 `TenantResolver.resolve(jwtUtil)` 且永不做查询参数、窗口是一条 `start` / `end` 整点小时区间且夹取全在服务端。

| 端点 | 参数 | 返回 |
|---|---|---|
| `GET /summary` | `start`、`end`（`yyyy-MM-dd HH:mm`，可缺省；裸 `yyyy-MM-dd` 整天也收，读作那天 00:00 那个整点）、`kind`、`groupBy`=`tool`（默认）\| `mcp` \| `cli` \| `agent` \| `session` | 卡片计数 + 行列表（`kind` / `subjectKey` / `subjectId` / `subjectName` / `parentName` / `toolName` / `calls` / 四终态 / `successRate` / `avgDurationMs` / `p95Operator`（`<=` 或 `>`）/ `p95Ms`（Long）/ `lastSeenAt`）。`tool` / `mcp` / `cli` 三档读聚合表，`agent` / `session` 读明细表。P95 给「算符 + 数值」两个字段而不是一个显示串：文案由前端 `pages.callMetrics.*` 造，服务端只给数，排序与画条要的是数值。行名一律服务端带出（D18），页面上不再拿 id 去猜是谁 |
| `GET /time-series` | `start`、`end`、`granularity`=`auto`（默认）\| `hour` \| `day` \| `week` \| `month`、`kind`、`subjectId` | 按桶补零的时间序列（`timePoint` × 维度，行内带 `dimensionId` / `dimensionName`），响应另回 `granularity`——选档发生在服务端，页面按它标出的档位画轴而不是自己再算一遍 |
| `GET /invocations` | `start`、`end`、`kind`、`toolName`、`mcpId`、`cliId`、`agentId`、`sessionId`、`outcome`、`pageNum` / `pageSize` | 明细分页 `ResultVo<Page<...>>`，含 `error_message` / `args_json` / `result_excerpt` |

区间的解析只有一处（`ToolMetricsServiceImpl.window`），三个端点与下钻共用：`end` 缺省为当前整点、晚于当前小时夹到当前小时；`start` 缺省为 `end` 往前 719 小时（即默认 720 小时 = 30 天窗口）、晚于 `end` 夹到 `end`、跨度超过 8760 小时（365 天）时把 `start` 推到 `end - 8759 小时`；两个值任一解析不出来就整体按缺省走并 `log.info` 一条。夹取一律改写成边界而不是拒答，被夹过的值都留一行日志。窗口两端在响应里回成 `from` / `to`（`/time-series` 也回这两个字段，另外回 `granularity`），响应里没有天数字段：天数是区间的派生量，页要标注的是它选中的那两个整点。

趋势的档位由服务端选：请求显式给了 `hour` / `day` / `week` / `month` 就照它，`auto` 或缺省时按跨度选——不超过 48 小时给小时，不超过 92 天给日，再宽给周。补零的桶起点走的是与 SQL 完全相同的四种对齐（小时取本身、日取 00:00、周取周一、月取 1 号），两边从不同的原点走会每一个桶都查不到行、回一片零。

`groupBy=mcp|cli` 与 `tool` 一样读聚合表，可答超过保留期的窗口；`agent` / `session` 走明细表（聚合表不带这两个维度），因此受保留窗口限制。明细这条路上没有耗时桶可答，所以这两档每行的 P95 是 `<=` 配上该行窗口内**实测最长的一次**（`MAX(duration_ms)`，见 `ToolMetricsServiceImpl.detailRows`）——它是分位数的上界而不是分位数本身，同一次响应里卡片上那个窗口 P95 仍按桶算（同文件 `p95()`）。明细这条路上聚合侧与它必须答同一个区间：两条读法都从起始整点取起，但聚合侧比到 `stat_hour <= to`（那一行覆盖六十分钟），明细侧比到 `ts < to + 1 小时`，配对错了就会让每个窗口的最后一个小时在两张表里差一步。分页沿用 `PageHelper.startPage` + `admin/dto/Page.fromPageInfo`（现例 `AgentToolController.kt:26-39` 与 `AgentToolServiceImpl.kt:26-31`）。

响应形状受 admin 既有出参约定约束：`application.yml` 的 `default-property-inclusion: non_null` 让 Jackson 3 丢掉值为 null 的键，DTO 一律给非空默认值；业务错误是 HTTP 200 带 `code`。

`subjectId` 的 0 就落在这条约定上。聚合表里 `subject_id` 的定义是「`kind=mcp` 时是 `mcp_id`、`kind=cli` 时是 `cli_id`、其余为 `0`」（DDL `V3__tool_invocation_metrics.sql`），三个来源共用一个字段承载同一个值；服务侧出参前把 `0` 折成 null（`ToolMetricsServiceImpl.subjectIdOf`），配上 `non_null`，所以 builtin / shell / framework 那一档的 JSON 里**没有 `subjectId` 这个键**，而不是它等于 `null`。`subjectKey` 是这一行的归属键：`tool` 档就是工具名本身，其余四档是 id 的字符串形式（会话档是 `session_id` 字符串）。`parentName` 只在 `kind` 为 `mcp` 或 `cli` 的行上有值——工具档带它才说得出这一行是哪台服务器/哪个包答的。

## 8. 前端

`harnax-webui`：

- 路由：`config/routes.ts` 的 `monitor` 分组（本分支 `:137-168`，页面条目 `:147-151`）挂 `{ name: 'call.metrics', path: '/monitor/call-metrics', component: './call-metrics' }`。monitor 子项一律不带 `access`（全仓只有 `/system/*` 三条有门禁）。
- 菜单：`src/locales/{zh-CN,en-US}/menu.ts:26` 补 `menu.monitor.call.metrics`（「调用监控」/「Call Metrics」）。`layout.locale = true`，缺 key 会在侧栏直接渲染出裸 key。
- 服务：`src/services/ant-design-pro/toolMetrics.ts`，三个函数 `getToolMetricsSummary` / `getToolMetricsTimeSeries` / `getToolInvocations`，窗口一律是 `start` / `end` 两个 `yyyy-MM-dd HH:mm` 参数（缺省、夹取、上限都在服务端，见 §7），形状照 `skillUsage.ts`（`// @ts-ignore` + `/* eslint-disable */` + `request<API.Result<T>>('/api/admin/...')`，不写 baseUrl，路径由 `config/proxy.ts` 的 `/api/admin/` 通配与生产 nginx 承接）。
- 类型：写 `src/typings.d.ts` 的 `API` 命名空间（`src/services/**` 被 `biome.json` 排除，类型放服务文件里等于没被检查）。
- 页面：`src/pages/call-metrics/index.tsx`。工具 / MCP / CLI 三个 tab 共用一套形状——四张卡（调用量、成功率、P95、失败数）、一张 `@ant-design/plots` 的 `Line` 趋势、一张第一列按当前档位命名的行表，失败行的 `error_message` 直接展开。骨架对齐 `src/pages/skill/usage.tsx`（`PageContainer` + `Row/Col + Card + Statistic` + `Spin` + `className="styled-pro-table"` 的 `Table` + `response.code === 200` 判成功）。
- 档位与列名：`src/pages/call-metrics/dimensions.ts` 是唯一一处「哪个 tab 有哪几档」（`TAB_DIMENSIONS`）与「哪几档读聚合」（`readsAggregate`）的表。分组下拉由当前 tab 的三档生成，切 tab 时当前档位不在矩阵里就收敛到该 tab 第一档；行表第一列的标题随档位取 `pages.callMetrics.dim.*`，格子里显示服务端带出的 `subjectName`（缺失时回落 `subjectKey`），`tool` 档上这一行是 MCP / CLI 提供的工具时把 `parentName` 作灰色限定跟在名字后。`readsAggregate` 供两处判断：受保留窗口限制的提示只在读明细的两档显示，「最近调用」的精度也按它分档。
- 两个抽屉挂在两列上，互斥：行名列（标题随档位是「工具 / MCP / CLI / 智能体 / 会话」）开 `SubjectDrawer.tsx`，`调用量` 列开调用记录抽屉（`/invocations`，带同一区间，分页 20 条）；开一枚即关另一枚，区间一变就关掉记录抽屉——它列的是旧区间数出来的行，换区间继续翻页会把两批混在一起。两枚抽屉的标题都拼上这一行的 `subjectName`（回落 `subjectKey`）。行名抽屉上半是这一行本身的四项计数（调用量、成功率、P95、最近调用，直接取自行本身，不再发第二次指标请求），下半是档案，档案取自行本身已带的 `subjectId` / `subjectKey`。档案来源是纯函数 `subjectProfile.ts` 的 `profileTargetOf(row, groupBy)`，取值 `agent` / `mcp` / `cli` / `session` / `tool` / `none`，用例钉在 `subjectProfile.test.ts`；`none` 就是 `shell` / `framework` 与注册表里已不存在的工具，此时抽屉只给计数并用一条 `Alert` 明说没有档案——读失败与没登记是两种答案，分别落 `Alert type=error` 与 `type=info`，都不渲染空态占位。档案接口按来源各一个：`/api/admin/agents/{id}`、`/api/admin/mcp/{id}`、`/api/admin/clis/{id}`、`/api/admin/sessions/{sessionId}/config`（会话只有这一条按 id 字符串查的读端点）、`/api/admin/tools/builtin`（列表按名匹配，没有按名单查的端点）。抽屉形状照 `src/pages/cli/components/CliDetailDrawer.tsx`（`Drawer` + `Spin` + `Descriptions bordered size=small column=1`）。
- 图表用 plots v2 形状：**`colorField` 而不是 `seriesField`**，数据是 `flatMap` 出的长表 `{ date, type, value }`，配色走 `scale.color.range` 的字面 hex（现例 `src/pages/token-monitor/index.tsx:615`、`:667`），空态 `<Empty>`。文本/边框/背景用 `var(--vip-*)` 令牌自动跟深色主题，凡要与透明度拼接的颜色必须写十六进制。X 轴标签按响应回出的 `granularity` 选格式（小时档 `MM-DD HH:00`，其余档 `MM-DD`），页面不自己按跨度重选一次档；趋势读失败时把桶清成空而不是留着旧档——卡片与表已经移到新区间，还画着上一个区间的线等于画一条谁都不是的趋势。
- 窗口是一条 `DatePicker.RangePicker`，挂在页首 `Tabs` 那一行的右侧（`tabBarExtraContent.right`），因为它驱动的是整页四张卡、趋势与两枚抽屉，放在下面那张表的头上会被读成只管这张表。`showTime` 只开小时（面板 `format: 'HH'` 且 `showMinute` 与 `showSecond` 都关，输入框显示 `YYYY-MM-DD HH:00`），四个预设（近 7 / 30 / 90 / 365 天）由 `presets` 给出、算的是显式时间，选中的值和预设都向下取整到整点，晚于当前小时的不可选、不允许清空。选中的区间是页面唯一的时间状态，同时喂 `/summary`、`/time-series` 与下钻的 `/invocations`，所以四张卡、趋势线、行表和抽屉里的记录永远同一个窗口。
- 文案全走 `pages.callMetrics.*`，中英两个 locale 都必须加，缺一侧算未完成。

## 9. 技能用量的连带改动

- `SkillUsageAdaptor`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/SkillUsageAdaptor.kt`）从 `fun interface` 变成两个方法：`reportViews` 与 `reportUses`，后者签名与前者一致。
- `AdminApiClient.reportSkillUsage` 现在把 `"VIEW"` 写死在事件体里（`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt:265`），要改成带事件种类入参；服务端 `SkillUsageServiceImpl.report` 已允许 `VIEW` / `USE` 两类（`:91-94`），不需要放宽。
- 上报点在 `ToolInvocationMiddleware` 的 `TOOL_RESULT_END` 分支：工具名是 `load_skill_through_path` 且 `path` 为 `SKILL.md` 且 `outcome=SUCCESS` 时，按 `skillId` 反解出 `skill.id`，经 `SkillUsageAdaptorImpl` 现成的异步队列发 `POST /api/admin/internal/skills/usage`（`InternalApiController.kt:291`）。技能没取到正文就不算被用过。
- 不做冷却：一次装载就是一次 USE，模型不会在同一轮里反复装载同一技能；`VIEW` 那 60 秒冷却的理由（harness 每次组装系统提示都重读仓库）在这里不存在。
- 技能用量页去掉自述文案：`pages.skill.usage.usesHint`（`harnax-webui/src/locales/zh-CN/pages.ts:209` 与 `en-US/pages.ts:209`）改为定义本身。

## 10. 删除清单

`tool_call_log` 整链移除（D8）。以下是对着源码数出来的，不是按符号名推的：

本节与 §1 差距表里的行号指**当前**的 `V1__init_schema.sql`：这个文件一个字节没动（见 §2，改它等于要求清库重建），所以两边的同一位置仍是同一张表。两张新表的 DDL 在 `V3__tool_invocation_metrics.sql`（明细表 `:8`、聚合表 `:34`），旧表的删除在 `V4__drop_tool_call_log.sql`。

- `tool_call_log` 的建表块在基线里**逐字不动**（`V1__init_schema.sql:759-778`）：V1 已应用到现网，改一个字节就是校验和不符、admin 起不来。删除动作是一条前向增量 `V4__drop_tool_call_log.sql`，正文一句 `DROP TABLE IF EXISTS`——既有库下次启动即删，新建的库 V1 建完 V4 删掉，两边落同一个形状。`schema-test.sql` 的同一建表块与它的三条夹具随之删掉：漂移闸门比的是 Flyway 重放到最后版本的形状，那边已经没有这张表，副本留着就是一张生产建不出来的表
- `harnax-entity`：`entity/ToolCallLogEntity.kt`、`mapper/ToolCallLogMapper.kt`、`resources/mapper/ToolCallLogMapper.xml`、`test/.../mapper/ToolCallLogMapperTest.kt`。**`.kt` 与 `.xml` 必须同一次提交删**：XML 靠 `mapper-locations: classpath*:mapper/*.xml` 通配绑定，没有任何配置按名字引用它，只删接口会让一份孤立 XML 继续被解析
- `harnax-tools-sdk`：`adaptor/ToolCallLogAdaptor.kt` 整文件（`ToolCallInfo` 在 `:13-30`，与接口同文件，一次删除带走两者）；`ToolBox` 的 `init` / 两个 `execute` / `executeInternal` / `logToolCall` / `logToolCallError` / `userIdentifier()` 与三个 `@Volatile` 字段 / `lateinit var name` / `log`；`ToolCallContext.kt` 里的 `SessionMetaContext`
- `ToolBox` 只剩 `abstract fun name(): String`。`userIdentifier()` 在 main 里除自身声明外零调用方（全仓 `grep -rn "userIdentifier()" --include=*.kt` 只命中 `ToolBox.kt:41`；`DefaultAgentRunner` 用的是 `UserIdentifier` 类型不是这个访问器），所以 `init(...)` 整体消失而不是瘦身为 `init(userIdentifier)`
- `harnax-agent-service`：`adaptor/ToolCallLogAdaptorImpl.kt` 与 `adaptor/ToolCallLogAdaptorImplTest.kt` 两个整文件
- `harnax-harness-core`：`HarnessAgentLauncher` 的 `toolCallLogAdaptor` 形参（`:117`）、KDoc `:102`、`toolBox.init(...)` 三处（`:390-394`、`:559`、`:566`）与 `:553-555` 的 `teamSessionMeta`、`initLauncher` 形参 `:1155` 与透传 `:1226`；`HarnessAutoConfiguration` 的 import `:27`、provider 形参 `:320`、no-op 兜底 `:334-335`、注入 `:350`；`LauncherBean.kt:35-62` 注释块里两处 `toolCallLogAdaptor`
- 去掉 `execute(...) { }` 包裹：`EmailToolBox.kt:64`（闭合在 `:124`）、`TimeToolBox.kt:23`、`:27`、`TeamToolBoxes.kt:26`、`:43`、`:58`、`:90`、`:104`、`:114`。其中 `:26` / `:58` / `:114` 与 `TimeToolBox` 两处走的是无参重载 `execute { }`；`:43`、`:90`、`:104` 内有 `return@execute`，拆包裹时改成普通 `return`
- 测试夹具：引用 `toolCallLogAdaptor` 形参的 harness-core 测试共 **14 个**（`HarnessAgentLauncherMemoryTest`、`HarnessAgentTokenRecordingTest`、`HarnessAgentTurnBudgetTest`、`memory/MemoryBucketPipelineTest`、`HarnessAgentLauncherLeadSkillTest`、`HarnessAgentLauncherSkillSelfWriteTest`、`HarnessAgentLauncherCliEnvTest`、`HarnessAgentLauncherSkillVisibilityTest`、`HarnessAgentLauncherSkillUsageTest`、`HarnessAgentRunAttributionTest`、`HarnessAgentProcessLogAttributionTest`、`HarnessAgentSessionHistoryReadTest`、`HarnessAgentLauncherCoordinationTest`、`memory/MemoryGateFalsificationTest`），每个都是 import + 具名实参两处；另改 `tools-sdk` 的 `ToolBoxTest.kt`（`TestableToolBox` 与断言日志的那批用例）和 `ToolCallContextTest.kt`（删 `SessionMetaContextTests` 内层类，保留 `UserIdentifierTests`）、`EmailToolBoxTest.kt`、`EmailToolBoxIntegrationTest.kt`、`TimeToolBoxTest.kt`、`team/TeamToolBoxesTest.kt` 里 `SessionMetaContext` 夹具与日志断言
- 文档：`docs/architecture.md`、`docs/tools-sdk-architecture.md`、`docs/harnax-harness-core.md`、`docs/database-design-conventions.md:30`、`:78`、`docs/backend-code-conventions.md:595`、`docs/session-classification-design.md:121`、`harnax-agent/HARNESS_CORE_DOC.md`、`harnax-agent/harnax-tools-sdk/TOOL-DEV-GUIDE.md`、`harnax-agent/harnax-agent-service/docs/conversation-flow.md`、`harnax-admin/TOOL_INTEGRATION_DESIGN.md`（§2.4 整节与 `:352` 那条「保留不删」）、`AgentSpec.kt:63-72` 的 KDoc 三件套，以及 `prod_doc` 里 `tool-capability` / `tool-integration-design` / `multi-agent-team-design` / `product-overview` / `skill-management` 五个中英成对文件的对应段落

`ToolCallContext.kt` 里的 `UserIdentifier` 与 marker 接口 `ToolCallContext` 都保留（launcher 与 MCP 授权链路在用），删的只有 `SessionMetaContext`。`mcp_call_log` 一行不动，连表注释也不动（同一个理由：基线冻结），「它是授权账本而不是指标源」这条只写在本文与 `prod_doc/tool-capability` 的正文里。

没有需要人工执行的清理：`tool_call_log` 的删除挂在 V4 上，既有库与新库都由 Flyway 落到同一个形状，`docs/deploy-harnax-admin.md` 因此不再给 `DROP TABLE` 命令。下一次清库重建时把 V3、V4 一起折回基线（建表块随之从基线消失），本文件与 `db/migration/README.md` 的那条规则就重新对齐。`mcp_call_log` 不在这条清理之列——它是 MCP 授权账本，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:618` 仍在写它，只是不当指标源用。

## 11. 测试与验收

- 单测：`kind` 判定纯函数穷举五种输入形状与优先级冲突；CLI 命令解析（管道、`&&`、绝对路径、带空格的引号）；`ToolResultState` → `outcome` 映射；未终态补 `INTERRUPTED`；截断与关闭开关；丢弃计数。纯函数测试用 JUnit5 `org.junit.jupiter.api.Assertions` + 反引号句子的方法名（现例 `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/skill/TenantSkillVisibilityFilterTest.kt`）；中间件流测用模块已有的 `reactor-test`。
- 持久层测（`harnax-entity`，真实 MySQL 8 容器）：`harnax-entity` 没有 failsafe，也没有 `integration-test` profile，所以这一层的容器测叫 `*MapperTest`，形状照 `TokenStatsMapperTest.kt:33-61`（`@Testcontainers @MybatisTest @AutoConfigureTestDatabase(NONE) @ActiveProfiles("test")` + companion 里每类一个 `MySQLContainer("mysql:8.0").withInitScript("schema-test.sql")`）。闸门：同一会话三次调用必须落三行且三个不同时刻；`batchInsert` 一批 N 行返回 N 且空批不发 SQL；upsert 重算两次结果不变；六桶之和 = `calls` = 四终态计数之和。
- 端到端持久化测试（`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/`，继承 `BaseAdminIT`）：聚合、读侧租户谓词与清理三条闸门放这里，因为只有 admin 侧 IT 重放真实的 `db/migration` 全套（基线加上目录里的全部前向增量；共享容器、schema 由迁移建）。两个类都在 `@BeforeEach` 清空两张指标表再自造夹具，读侧另写一行 `agent`、一行 `mcp_server`、一行 `cli`——admin 的 IT 库除 `skill_repository` 外不种子任何登记行，名称解析那条闸门无夹具可借，用完必须删干净（共享容器重跑会撞唯一键，别的类读这三张表时只 expect 自己插的那几行）。读侧写进 tenant 1 并另开邻居 `950_500`，两个方向都断才证明租户谓词真在收敛而不是恰好只有一批数据。
  - 读侧：租户谓词必须把自己的数据滤出来——邻居租户那两行就落在与本页数据同一天的另一个整点小时里，所以缺一次租户谓词的响应会把它的两行一起答出来。整点区间的四条夹取各有用例：`end` 晚于当前小时夹到当前小时（`futureEndClampsToTheCurrentHour`）、`start` 晚于 `end` 塌到 `end`（塌比翻好，翻过来的区间答一张空表却还声称一个窗口）、跨度超上限时保住 `end` 把 `start` 推到往前第 8759 个小时（拒答会因为一个读者不会去要的区间而清空整排卡片）、两个值都解析不出来整体按默认 720 小时窗口走并断 `totalCalls` 非零，证明坏选择器答的是图而不是错误卡。四条都在响应回出的 `from` / `to` 上断而不是在日志上断，两个回显本身必须落在整点上：回显请求原值等于告诉页面「你问的那个边界我答了」，而它并不存在。粒度这条另有一用例，在 48 小时与 92 天两条边界各取两侧（边界归更细那一档），并证明显式 `granularity` 压过区间。夹具里 `start` / `end` 用裸日期、其余用 `HH:mm`，两条解析路径都在这一个类里过。
  - 聚合与清理：`retention-days=0` 够不着——它被 `ToolInvocationRollupService.clampToWindow` 夹回下限 1，所以这一层的门不是「窗口多小」而是「未折算即不删」。折算与清理各写了成套用例（`ToolInvocationRollupIT`，全部按方法名认）：整小时缺失由下一轮补上（`missedHourIsCaughtUp`，顺带断半开桶——120 ms 落 `(100,500]` 不落 `<=100ms`）；当前小时被重算且后写的行在下一次带进来（`currentHourIsRerolled`）；已折算那一小时之后迟到的行靠上一小时的强制重算捞回（`lateRowOfTheClosingHourIsFolded`）；无租户的明细根本不进折算（`tenantlessRowIsNeverRolled`——聚合表 `tenant_id NOT NULL`，把它算进待折集会让差集永不空、饿死能折的小时）；小时在将来的行不折（`futureDatedRowIsNotFolded`，取六小时之后保证它对本用例的每一轮都是将来，而不只是晚于午夜）；过窗一小时在同一轮里既被折算又被释放且顺序不可颠倒（`expiredRowWaitsForItsRollup`）；未折算的小时删除语句碰不到（`unrolledHourIsNeverReleased`，在 mapper 接缝上证明，因为正常一轮会先折再释放）；无租户的过期行不等聚合就被释放（`tenantlessExpiredRowsAreReleased`）；窗口内刚过一小时的行存活（`rowInsideWindowSurvives`，这条让保留窗口的值可观测而不是测试与 `application-it.yml` 之间抄来的数）；小时行相加等于它们折出来的明细且桶跟着小时走（`hourlyRowsSumToTheirDetail`）；同一小时折两次结果不变（`foldingTheSameHourTwiceChangesNothing`）；开关关掉两张表都不动（`killSwitchWritesNothing`，`rollUpHourly()` 是 `rollup-enabled` 唯一读者）。
  - `SchemaBaselineDriftIT` 是隐形闸门：新表只进迁移（V3）不进 `schema-test.sql` 会让它六条断言全红；反过来被 DROP 掉的表（V4 的 `tool_call_log`）留在 `schema-test.sql` 里，等于夹具带着一张生产建不出来的表——`DROP TABLE` 是它已建模的语句形状，两边同删它就仍然绿。列名与索引这两侧也各自双向比：V5 把 `stat_date` 改名成 `stat_hour` 并换掉两条键，任何一侧没跟上都会红在「迁移有而基线没有」和「基线有而迁移没有」两条上，一次改漏不可能只红一条。
  - 时区陷阱：Testcontainers 的 MySQL 是 UTC，Java 侧 `LocalDateTime` 按 JVM 时区写 `datetime`，所以折算与清理的用例一律从 `LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)` 往前往后推相对整点，不写绝对日期——写死的日子在 UTC 与 JVM 时区之间会漂出一个小时，而漂移一小时的夹具刚好落在「未折算」那一侧，用例会以一种谁也解释不了的方式红。
- 跑法：`mvn -o test -pl harnax-entity`；admin 侧 `mvn -o verify -pl harnax-admin -am -Pintegration-test -Dit.test=<类名> -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`（`-am` 与两个 `failIfNoSpecifiedTests` 都不能少，`docs/unit-test-cases.md:799-810`）。删过源文件的模块跑 IT 前必须 `clean`，残留 `.class` 会进 jar 造出假绿。
- 前端：`npm run build`（`max build`）与 `npx @biomejs/biome lint <改过的文件>` 各自单独跑并落日志再看退出码；`biome check --write` 会把上千行既有文件一起铺开，不许当闸门用；`npm run lint` 会串 `tsc --noEmit`，本仓有一批既有噪声，也不作为闸门。页面里的纯函数各有用例（`lastSeen.test.ts`、`subjectProfile.test.ts`），`dimensions.ts` 那张 tab→维度矩阵是给页面直接读的常量，不另立用例；`npm test` 走 jest，本仓的 `jest.config.ts` 在新检出里加载不起来（主检出同样如此），跑单测要直接把配置内联喂给 jest。
- 端到端（harnax-deploy 真栈）：一个装了 MCP、勾了一个 CLI 和一个技能的 agent 跑一轮，页面上 `mcp` tab 有非零调用、`cli` tab 记到该命令、技能用量的 USE 从 0 变正。

## 12. 已知边界与不做

- 一条 shell 命令串里出现两个已下发 CLI 时只记最左命中的那个（复合命令拆行会破坏 I1，代价是漏记；`args_json` 里有完整命令串可复核）。
- CLI 命令别名只来自 `name` + `checkCommand` 每个分段的首词（去掉路径前缀）：别名集在装配期算出（`HarnessAgentLauncher.kt:619-623`），包物化在同一个方法的更后面（同文件 `:686` 的 `resolveImage`），所以不存在「按未物化的别名集先判一次」这条分支。包内二进制若既不在包名里、也不在 `checkCommand` 任一分段的首词里，就归因不到，落 `kind=shell`。`checkCommand` 是清单必填项（缺它直接判解析失败，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/registrar/CliPackageParser.kt:252-253`）且在镜像内执行，它的首词按构造就在 PATH 上——这也是当初否掉「扫 `/bin` 目录补别名」的理由。
- 两个 MCP server 暴露同名工具时，按名覆盖发生在上游的 `io.agentscope.core.tool.ToolRegistry`——它以工具名为键（`tools` 是名字到工具的 map，`registerTool` 直接 `tools.put(toolName, tool)`），`Toolkit` 只是持有并转调它，所以模型侧本来就只能看见后注册的那一个。归因跟着这份注册表的枚举结果走（`HarnessAgentBuilder.kt:305-307`），因此记给活下来的那个 server，与运行时实际调用的是谁一致。本项目的 `com.agnetix.harnax.tools.sdk.registry.ToolRegistry` 是另一个同名的类：它按 Spring bean 名键控 admin 下发的 `ToolBox`（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/registry/ToolRegistry.kt:23`、`:29-30`），不参与 MCP 注册。
- harness 自带的 `read_file` / `memory_*` 不进 MCP 枚举（build 时才挂上），也不在 admin 下发的 `toolSpecs` 里，因此落 `kind=framework` 而不是 `builtin`；页面上这两个档位的名字按 D16 是「可选工具」（admin 下发的那份名单）与「系统内置」（harness 自己带的）。`execute` 是这一条的例外：shell 判定排在 `builtinToolNames` 之前（`ToolInvocationClassifier.kt:50-58`），所以它先试 CLI 归因、没命中就落 `kind=shell`，两边都不会是 framework。
- 行名抽屉只在「注册表里真有一行」时才有内容：`agent` 维度查 `agent` 表、`mcp` / `cli` 维度查 `mcp_server` / `cli` 表、`session` 维度查会话配置，四者都由 admin 既有读端答出。`kind` 为 `shell` / `framework` 的行，以及 `builtin` 里未在 admin 工具注册表命中的名字，压根没有一行可查——抽屉这时只给这一行本身的计数，另起一条 `pages.callMetrics.profile.empty`（「这一项没有登记信息」）而不是编一份，因为系统内置工具的身份只存在于装配期的 Toolkit 里。`tool` 维度不按名查注册表（`AgentToolController` 的 `/{id}` 是 Long），走的是列表按名匹配，匹配不到同样落「没有登记信息」。
- 时间序列的桶由服务端按区间选（`granularity=auto`），显式 `hour` / `day` / `week` / `month` 压过区间；页面上不给档位选择器，因为这条读端的桶从来不是读者要的口径，他要的是一条看得清的线。区间拉到 365 天上限时是 53 个周格而不是 8760 个小时格。
- 不做 OTel / 分布式 trace；不做工具级成本核算；不给 `mcp_call_log` 加指标读端；不引入 ClickHouse 之类外部指标存储；不给 admin 引分布式锁。
- 明细里的 `args_json` 可能含敏感字面值，默认截断 + 可用 `capture-payload=false` 整体关闭；本设计不做字段级脱敏。
- 读侧：`/summary` 的 `agent` / `session` 两档行取自明细表，所选区间落在保留窗口（默认 90 天）之外的那几个小时必然答不全——明细按「已折算即释放」清理，区间往前推过窗口时那一段只剩聚合；同一份响应里的卡片合计走 `selectWindowTotals`（聚合表），是这两档唯一不受窗口影响的数，下钻抽屉同样受区间限制。`tool` / `mcp` / `cli` 三档读聚合，不受这条限制。页面的提示按**维度**给而不是按区间长度给（`harnax-webui/src/pages/call-metrics/dimensions.ts` 的 `readsAggregate` 为假才显示）——区间整体落在 90 天内的 agent/session 也只看得到明细，这条口径不该被读成「区间 ≤ 90 天就完整」。
- 团队主管的调用没有 `agent` 可归：`AgentSpec.attributableAgentId` 把 `LEAD_ID` 折成 null（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:64-72`），主管背后本来就没有一行 `agent`，所以它的调用只出现在 `session` 维度与窗口卡片里。`selectSubjectTotalsFromDetail` 在 agent 这条维度上的 `HAVING subjectKey IS NOT NULL`（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`）把这一整组排除在外，于是同一份 `/summary` 里「各行 calls 相加」会小于卡片的合计，差的正是主管那部分。
- 折算：`rollUp()` 只重算「聚合表里还没有这个小时的那些小时」再加上当前小时与上一小时，`deleteRolledOut` 只看窗口，所以一个**已经折算过、且已不再是当前小时或上一小时的那个整点后来才迟到的明细行会被直接释放、永不计入聚合**。这是有意付的代价：要造出迟到行得有时钟回拨超过一小时，而为了它重开每个已折的小时等于每小时重读整张保留窗口内的明细表（取舍写在 `ToolInvocationRollupService.rollUp()` 的 KDoc）。集成测试看不见这条——清理永远够不到还在窗口内的行。

## 13. 落点

| 动作 | 位置 |
|---|---|
| 新增 | 中间件 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt` + 同目录的 `ToolInvocationClassifier.kt`（`kind` 判定与 CLI 归因纯函数）；写入契约 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt`；实现 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt`；两张表 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/{ToolInvocationLog,ToolInvocationStats}.kt`（新实体跟 `TokenStats.kt`、`SkillUsage.kt` 一致，不带 `Entity` 后缀）+ `.../mapper/{ToolInvocationLogMapper,ToolInvocationStatsMapper}.kt` + `harnax-entity/src/main/resources/mapper/*.xml`；读端 `harnax-admin/.../controller/ToolMetricsController.kt` + `.../service/ToolMetricsService.kt` + `.../service/impl/ToolMetricsServiceImpl.kt` + `.../service/ToolInvocationRollupService.kt` + `admin/dto/` 的响应 DTO，IT 落 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/`；DDL 落 `harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql`（前向增量，理由见 §2）、`V4__drop_tool_call_log.sql`（同形状的第二条，`DROP TABLE IF EXISTS`）与 `V5__tool_invocation_stats_hourly.sql`（第三条：先清空聚合表再把 `stat_date` 改名成 `stat_hour` 并换上小时的两条键，形状与理由见 §2.2）；前端 `harnax-webui/src/pages/call-metrics/index.tsx`（区间 `RangePicker` + 两枚抽屉）+ `.../call-metrics/dimensions.ts`（tab→维度矩阵与「这一档读哪张表」）+ `.../call-metrics/lastSeen.ts` + `lastSeen.test.ts` + `.../call-metrics/subjectProfile.ts` + `subjectProfile.test.ts` + `.../call-metrics/SubjectDrawer.tsx` + `src/services/ant-design-pro/toolMetrics.ts` + `src/typings.d.ts` 的 `API.CallMetrics*` / `API.CallInvocationRow` / `API.AgentToolItem` 类型 + 两份 locale |
| 修改 | `schema-test.sql`（并入两张新表的建表块、并删掉 `tool_call_log` 的建表块与它的三行夹具；聚合表那块要与 V5 同步成 `stat_hour` 和小时的两条键，改漏由 `SchemaBaselineDriftIT` 双向拦下；`V1__init_schema.sql` 逐字未动）、`HarnessAgentLauncher`、`HarnessAgentBuilder`（toolkit 只读访问器）、`HarnessAutoConfiguration`、`SkillUsageAdaptor` / `SkillUsageAdaptorImpl` / `AdminApiClient`、`HarnaxAdminApplication`（`@EnableScheduling`）、两侧 `application.yml`、`config/routes.ts`、`src/services/ant-design-pro/session.ts`（新增 `getSessionConfig`：会话档案只有 `/api/admin/sessions/{sessionId}/config` 这一条按 id 字符串查的读端点）、技能用量页文案、`prod_doc` 双语包（工具能力 / MCP 管理 / CLI 包 / 技能）与 `docs/deploy-harnax-admin.md`（保留窗口、本域那三条前向增量与既有库免重建、`tool_call_log` 由 V4 代清因此无手工步骤、V5 会清空 `tool_invocation_stats` 并从保留窗口重新折出小时行） |
| 删除 | §10 清单 |
