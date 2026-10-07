# 工具 / MCP / CLI 调用指标 · 设计规格

- 日期：2026-10-05
- 状态：已实现并合入 `kotlin-dev`（原分支 `feat/tool-mcp-cli-metrics`）。本文件是 2026-10-05 的规划稿，落地后的口径以 `prod_doc/tool-mcp-cli-call-metrics-design.zh-CN.md` 为准，两份不一致时读那一份。
- 范围：`harnax-agent/harnax-harness-core`、`harnax-agent/harnax-tools-sdk`、`harnax-agent/harnax-agent-service`、`harnax-entity`、`harnax-admin`、`harnax-webui`、`prod_doc`、`docs`

## 0. 要解决的问题

管理员现在问不出「哪个工具、哪个 MCP、哪个 CLI 真的被用过、成没成、慢不慢」。三条链各自残缺：

| 域 | 表 | 写入方 | 读取方 |
|---|---|---|---|
| 内置工具 | `tool_call_log`（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:759`） | 只有继承 `ToolBox` 并走 `execute { }` 的工具（`harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt:59-150`） | 无（`ToolCallLogMapper` 只有 insert） |
| MCP | `mcp_call_log`（同文件 `:248`） | 只有 OAuth 换发 / 刷新 / 吊销三类授权动作；工具调用一次都不落 | 无 |
| CLI | 无表 | 无 | 无 |
| 技能 | `skill_usage`（同文件 `:584`） | 只报 `VIEW`；`USE` 没有写入方（`SkillUsageAdaptor` 只有 `reportViews`，`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/SkillUsageAdaptor.kt:16-27`） | 有：`/api/admin/skill-usage/summary` → webui「技能用量」 |

三处残缺是同一个根因：**记录点挂在实现方式上，而不是挂在执行通道上**。`ToolBox` 是本项目的工具基类，凡是绕过它的注册（MCP 走 `toolkit.registerMcpClient`，harness 自带的 `execute` / `read_file` / `memory_*` / `load_skill_through_path`）都不会被记到。

目标：一个事件源、一张明细、一张日聚合、三个读端点、一个新页面，覆盖 `builtin` / `mcp` / `cli` / `shell` / `framework` 五种来源，并顺带给技能用量补上 `USE` 的数据源。

## 1. 决策清单

| # | 决策 | 依据 |
|---|---|---|
| D1 | 唯一事件源是实现 `MiddlewareBase.onActing` 的中间件，装配期挂在 agent 上 | agentscope 2.0.4 的 `onActing` 收到 `ActingInput(toolCalls: List<ToolUseBlock>)`，`ToolUseBlock` 带 `id`/`name`/`input`；`ProcessLogMiddleware` 已经在用同一个钩子（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ProcessLogMiddleware.kt:86`）。这是唯一能同时覆盖内置工具、MCP 工具与沙箱 shell 的位置 |
| D2 | 一次工具调用一行，且落库时必须是终态 | 半途的行既不能算进成功率，也不能算进耗时；`ToolResultState.RUNNING` 表示外部执行尚未回传，等终态再来 |
| D3 | 一张明细表 + 一张日聚合表；默认口径读聚合，按 agent / session 的维度与单次下钻读明细 | 聚合与明细分离，才能既删得掉明细（体积）、又答得出跨保留期的趋势；agent / session 不进聚合，因为它们的基数不受控 |
| D4 | `kind` 判定只用装配期已知事实，不回查数据库 | 运行侧没有 admin 的表；一次回查就把计数器变成了每个工具调用一次 admin 往返 |
| D5 | CLI 口径 = 解析 shell 工具的 `command` 参数归因，不新增 `run_cli` 工具 | 新增工具会改模型可见的工具面与每个包 `SKILL.md` 的写法，属于产品级重构；shell 归因零侵入且反映真实使用 |
| D6 | MCP 归因在装配完成后枚举已组装好的 `Toolkit`，按工具名反查它属于哪个 server；`mcp_call_log` 原样保留 | 枚举读的是模型实际看见的那份注册结果，同名覆盖与注册失败都自然反映；不用对每个 server 多发一次 `listTools()`。`mcp_call_log` 是授权账本（谁在什么时候换了谁的令牌），与新指标职责不同，且不能被指标保留窗口删掉 |
| D7 | 技能 `USE` 定义为模型主动调 `load_skill_through_path` 加载正文，落进现有 `skill_usage` | 上游把该工具名固定为 `load_skill_through_path`（`agentscope-core` 的 `SkillToolFactory.LOAD_TOOL_NAME`），参数是 `skillId` + `path`，是「指令被取用」的最强证据 |
| D8 | `tool_call_log` 整条链删除：表、实体、Mapper、Adaptor、`ToolBox` 的日志包裹与上下文注入 | 2026-10-05 明确「完全按全新设计，不考虑历史兼容」。新事件源覆盖它的覆盖面，且 `tool_name` 写成 `ToolBox名::方法名` 与注册身份 `@Tool.name` 对不上，留着只会多一个口径 |
| D9 | 写入 = 有界队列 + 批量 insert + 溢出丢弃计数；绝不在工具执行线程同步落库，绝不抛 | 与 `SkillUsageAdaptorImpl` 同形；计数器不值得让一次回答去等一次数据库往返，更不值得因它失败 |
| D10 | 聚合与清理都在 admin：每小时先补齐「明细里有、聚合里缺」的所有日期，再删除已过保留窗口且已折算的明细 | 补齐使重算自愈（漏跑一天、首次上线都不需要 backfill 开关），删除条件挂在「已折算」上而不是挂在日期算术上，多副本同时跑无害 |
| D11 | P50 / P95 由时长分桶近似，页面上标明是近似值 | 日聚合表只有计数列，真分位数要保留全部明细才能算 |
| D12 | 租户只由服务端决定：读侧从 JWT 解，写侧在装配期随行携带 | 与 `TokenStatsController`、`SkillUsageController` 同规；查询参数里出现 `tenantId` 就等于给了跨租户读数的口子 |
| D13 | 明细保留 90 天，`args` / `result` 截断到 2000 字符，两条都可配可关 | 命令行参数与工具回执里会出现凭据字面值，默认截断 + 可整体关闭 |

## 2. 数据模型

两张表最终没有折进 `harnax-admin` 的 Flyway 基线，而是走 `harnax-admin/src/main/resources/db/migration/README.md` 记的那个例外：这个库在跑、且里面的模型 provider API Key 只有人能重填，所以基线逐字不动、改动以前向增量 `V3__tool_invocation_metrics.sql` 交付，下一次重建时再折回基线。`harnax-entity/src/test/resources/schema-test.sql` 跟的是重放到最后一个版本之后的形状，也就是基线加全部增量的并集。

### 2.1 `tool_invocation_log`（明细，append-only）

| 列 | 类型 | 含义 |
|---|---|---|
| `id` | bigint PK | |
| `tenant_id` | bigint NULL | 归属租户，装配期从 `AgentSpec.tenantId` 带来 |
| `agent_id` | bigint NULL | 归属 agent；团队主管无 `agent` 行，记 NULL（沿用 `AgentSpec.attributableAgentId`） |
| `session_id` | varchar(255) NULL | |
| `user_id` | bigint NULL | 会话背后有平台账号才填；渠道会话有意为空 |
| `kind` | varchar(16) NOT NULL | `builtin` / `mcp` / `cli` / `shell` / `framework` |
| `tool_name` | varchar(255) NOT NULL | 模型看见的工具名，`builtin` 时可与 `agent_tool.name` 对上 |
| `mcp_id` | bigint NULL | 仅 `kind='mcp'` 且归因唯一时非空 |
| `cli_id` | bigint NULL | 仅 `kind='cli'` |
| `outcome` | varchar(16) NOT NULL | `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED` |
| `error_message` | varchar(512) NULL | 截断，失败原因 |
| `args_json` | text NULL | 入参 JSON，截断，可关 |
| `result_excerpt` | text NULL | 回执首段，截断，可关 |
| `duration_ms` | bigint NOT NULL | 终态时刻减开始时刻 |
| `start_time` / `end_time` | datetime(3) NULL | 毫秒精度，秒精度会把同秒内的两次调用压成同一时刻 |
| `ts` | datetime(3) NOT NULL | = `end_time`，聚合与索引按它走 |

索引：`(tenant_id, ts)`、`(tenant_id, kind, ts)`、`(mcp_id, ts)`、`(cli_id, ts)`、`(session_id)`、`(tool_name)`。

### 2.2 `tool_invocation_stats`（日聚合，永久）

命名跟 `docs/database-design-conventions.md:30`：日志/统计表用 `_log` / `_stats` 后缀，`_daily` 两个都不沾。

键：`UNIQUE (stat_date, tenant_id, kind, subject_id, tool_name)`。

| 列 | 类型 | 含义 |
|---|---|---|
| `stat_date` | date NOT NULL | 按 `ts` 的日期 |
| `tenant_id` | bigint NOT NULL | 明细的 `tenant_id` 可空（admin 下发的 spec 没带租户时不猜），这类行不进聚合，因而在页面上不可见；正常部署始终带租户 |
| `kind` | varchar(16) NOT NULL | |
| `subject_id` | bigint NOT NULL default 0 | `mcp` 存 `mcp_id`、`cli` 存 `cli_id`、其余为 0 |
| `tool_name` | varchar(255) NOT NULL default '' | `cli` 行存命中的命令名；留空表示只按主体聚合 |
| `calls` / `successes` / `errors` / `denials` / `interruptions` | int NOT NULL | |
| `sum_duration_ms` / `max_duration_ms` | bigint NOT NULL | 均值由 `sum / calls` 算 |
| `le_100ms` / `le_500ms` / `le_2s` / `le_10s` / `le_30s` / `gt_30s` | int NOT NULL | 六桶，分位数在此近似 |

`subject_id` 用 0 而不是 NULL 进唯一键：MySQL 唯一索引不把 NULL 视为相等，用 NULL 会允许同一天同一主体重复插行，`ON DUPLICATE KEY UPDATE` 就失效。

### 2.3 不变量

- I1 一次工具调用至多一行明细；`kind` 与 `mcp_id` / `cli_id` 的填充关系由 D4 的判定顺序唯一决定。
- I2 `outcome` 只取四个终态值，`RUNNING` 永不落库。
- I3 明细行的 `tenant_id` 与 `agent_id` 在写入时就定死，读侧不再猜。
- I4 聚合表任一行的 `calls` 等于四个计数列之和，且等于六个桶之和。
- I5 聚合可整体重算且不改变结果（幂等），因此重算与并发副本都无害。
- I6 任一明细日在被折算进聚合之前不会被删除（删除语句以「该 `(DATE(ts), tenant_id)` 已存在于聚合表」为条件）。唯一例外是 `tenant_id IS NULL` 的明细：聚合表那列 `NOT NULL` 让它们不进任何聚合，因此只看保留窗口。

## 3. 事件源与判定

新增 `ToolInvocationMiddleware`，与 `ProcessLogMiddleware` 同包同目录：`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt`，挂载沿用 `agentBuilder.addMiddleware(...)`（`HarnessAgentLauncher.kt:587`、`:601` 那两行是同一家族）。

上游形状已核过，实现时不必再猜：`ActingInput` 是 `record ActingInput(List<ToolUseBlock> toolCalls)`，`ToolUseBlock` 给 `getId()` / `getName()` / `getInput(): Map<String, Object>`；`ToolResultEndEvent` 同时带 `getToolCallId()`、`getToolCallName()` 和 `getState()`，所以按 id 关联成立且名字可作二次校验（`ToolUseBlock.getId()` 上游可空，缺 id 与键冲突时的退路见下）；`ToolResultState` 五个值就是 §3 映射表那五个。

`onActing` 内为本次 acting 批次建一张起点表，键取 `toolCallId`、缺 id 时退到 `toolCallName`，从 `input.toolCalls` 起表（记录 `name`、`input`、开始时刻）；同一批次算出同一个键的调用在键尾加序号，另按 `name` 保存一组未终态键的先进先出队列，于是每个起点都可寻址。`ToolUseBlock` 的名字上游可空（由模型给的 JSON 反序列化而来，上游构造器不校验）：无名的调用既算不出键、也没有可写的 `tool_name`（该列 `NOT NULL`），因此不进起点表、不落库；登记本身绝不把异常抛出 `onActing`，D9 的「绝不抛」在事件流开始之前同样成立。在 `next.apply(input)` 的事件流上：

| 事件 | 动作 |
|---|---|
| `TOOL_RESULT_END`（带 `toolCallId`、`state`） | 按 `key(toolCallId, toolCallName)` 找到起点，未命中时取该名字队列里最早的一个（匹配只 peek）；队列与起点表按键一一对应——登记时同处加入，投递与收流两条路径都按起点记录的 `name` 同处摘除，所以 peek 到的必是一个未终态起点；起点在投递前从表里摘除，同一个键在名字队列里按起点记录的 `name` 摘除而不是按帧名，所以改名的终态帧不会留下顶掉后续调用的幽灵键；重复的终态帧找不到起点，不会再落第二行 |
| 流 `onComplete` 时仍有未终态 id | 补一行 `INTERRUPTED`，时长到完成时刻，`error_message` 写 `stream ended before the tool returned` |
| 流 `onError` / `onCancel` 时同理 | 同上，`error_message` 取异常文本；已经流出的增量文本仍进 `result_excerpt`（按写入侧截断）|

增量文本按事件自带的键累积（有 `toolCallId` 用 id，缺 id 用名字），投递时先按起点键取、取不到再按终态帧自己的键取，未投递的起点在收流结束时同样先按自己的键、再按记录的 `name` 取。真正共享同一份缓冲的是帧侧无可分辨键的同名调用：delta 不带 id 时两侧的增量都落进同一个名字键缓冲，与那两个起点自己有没有 id 无关。这种情况下能保住的是行数，文本归并是已知让步，而归并后的正文落在哪一行取决于收流时的遍历顺序，不保证稳定。另一侧的损失同样已知：一帧终态既不带 id、其 `toolCallName` 又撞不到任何名字队列时配不上起点，那一次调用由收流兜底记成 `INTERRUPTED`。这里不引入「本轮只剩一个未终态起点就把它配上」的回退——那已经是猜，而猜错会把一次真正中断的调用记成成功，按 I4 那一行就从聚合表的 `interruptions` 挪进 `successes`，两个计数同时错位；记成中断至少是一个可数的损失。

`ToolResultState` 映射：`SUCCESS→SUCCESS`、`ERROR→ERROR`、`DENIED→DENIED`、`INTERRUPTED→INTERRUPTED`、`RUNNING→不落库`。

`kind` 判定顺序，第一个命中即定：

| 序 | 条件 | 结果 |
|---|---|---|
| 1 | 工具名在 MCP 映射表里（该表由最终注册表枚举而来） | `kind=mcp`，`mcp_id` 取映射值 |
| 2 | 工具名是 shell 工具且 `command` 命中本会话已下发 CLI | `kind=cli`，`cli_id` 与 `tool_name`（命中的命令名）来自 CLI 映射 |
| 3 | 工具名是 shell 工具但未命中 | `kind=shell` |
| 4 | 工具名在 `AgentSpec.toolSpecs[].toolName` 里 | `kind=builtin` |
| 5 | 其余 | `kind=framework` |

shell 工具名有两个上游来源，都认：harness 的 `ShellExecuteTool.NAME = "execute"` 与 core 的 `ShellCommandTool`（`execute_shell_command`），参数名都是 `command`。命中 CLI 之后仍然只记一行，命令串里出现第二个 CLI 时不复制行（见 §12）。

判定与归因都写成一个纯函数（输入：工具名、参数、三张映射表；输出：`kind` / `mcp_id` / `cli_id` / 归因命令名），中间件只做收集，便于单测穷举。

## 4. 装配期三张映射

都在 `HarnessAgentLauncher` 建 agent 时组好，交给中间件构造器，与 `SkillViewRecorder` 现在的归因方式同形（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:472`、`:490`）。

- **MCP**：全部 `addMcp` 做完之后枚举已组装好的 `Toolkit`——`getToolNames()` 逐个 `getTool(name)`，命中 `McpTool` 的取其 `getClientName()`（上游实现返回 `clientWrapper.getName()`，而本项目的 `McpHelper` 就是用 `mcpServer.name` 建 wrapper 的），再与本会话下发的 `mcpSpecs` 的 name→id 对上。走这条路而不是 `client.listTools()` 有两个理由：它反映同名工具被覆盖之后的真实结果（上游 `ToolRegistry` 是 `tools.put(name, tool)`，后者胜出），且不再对每个 server 发一次网络请求。装载循环与 `McpSpec` 的来源见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt:285-342`。可行性已核：`HarnessAgentBuilder.addMcp` 是 `registerMcpClient(...).block()`（`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt:76-80`，同步），注册当场生效；`toolkit` 是它的私有字段，要新增一个只读访问器；`HarnessAgent.Builder.build()` 会把这份 toolkit 深拷贝再往上挂 harness 自带工具，所以枚举看到的是「MCP + `addTool` 注册的那一半」，不含 `execute` / `read_file` 等自带工具——本节要的只是 MCP 归属，不含也够用。
- **CLI**：`agentSpec.cliSpecs` 每条给两个键——`name` 与 `checkCommand` 的首个词（`CliSpec` 字段见 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:175-186`）。若本会话的 CLI payload 树已在本地物化（`CliPackageStore.materialize` 的产物），再并进去向 `/bin`、`/usr/local/bin` 下的文件名。
- **技能**：沿用 `SkillViewRecorder.attribute(skillName, skillId)` 那条映射，再并上 `AgentSkill.getSkillId()`（上游实现为 `name + "_" + source`）→ `skill.id`，供 `load_skill_through_path` 的 `skillId` 参数反解。

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

适配器契约与 `SkillUsageAdaptor` 一致：立即返回、不抛、溢出丢弃并计数（每丢弃一批 warn 一次，附丢弃总数）。丢弃只发生在队列打满时，而打满意味着工具调用已经每秒数百次——那时少记几行比让整轮回答卡在数据库往返上更划算。

## 6. 聚合与清理

落在 admin，新增 `ToolInvocationRollupService` + 一个 `@Scheduled` 入口（admin 目前没有任何 `@Scheduled`，需要在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt` 上补 `@EnableScheduling`）。

每次运行按顺序做三件事：

1. 取明细里 `tenant_id IS NOT NULL` 的出现过的全部 `DATE(ts)`，减去 `tool_invocation_stats` 已有的 `stat_date`，得到「该折算却没折算」的日期集合。无租户明细排除在外，否则那一天每小时都被报成待折算而聚合永远不为它产生行。
2. 对这个集合**加上今天**逐日重算，`INSERT INTO tool_invocation_stats SELECT ... WHERE DATE(ts) = ? GROUP BY ... ON DUPLICATE KEY UPDATE` 整行覆盖。今天无条件重算，否则当天第一次调度写下的行就成了那天的终值。漏跑一天由第 1 步的差集兜住，不需要 backfill 开关。
3. 删除 `ts < now - retentionDays` 且（`(DATE(ts), tenant_id)` 已存在于聚合表 **或** `tenant_id IS NULL`）的明细。理由见 I6；无租户那半是唯一例外出口。

`harnax.metrics.retention-days` 默认 90，`harnax.metrics.rollup-enabled` 默认 true，调度表达式每小时第 5 分。多副本同时跑由第 2 步的幂等性兜住。

分位数：桶边界取 100 / 500 / 2000 / 10000 / 30000 ms。P95 的算法是「按桶累加到 ≥ 0.95·calls，落在哪个桶就报该桶上界，落进 `gt_30s` 报 `>30s`」。窗口内明细未过期时，`/invocations` 给出的单次真耗时是复核依据。

## 7. 读侧 API

`ToolMetricsController`，前缀 `/api/admin/tool-metrics`，与 `SkillUsageController` / `TokenStatsController` 同规：租户从 JWT 解、异常统一包 `ResultVo.error`、`days` 夹到合法区间。

| 端点 | 参数 | 返回 |
|---|---|---|
| `GET /summary` | `days`（1..365，默认 30）、`kind`、`groupBy`=`tool`（默认）\| `agent` \| `session` | 卡片计数 + 主体行列表（`calls` / `successRate` / `avgDurationMs` / `p95Bucket` / `lastSeenAt` / 按 kind 的 `mcpId` \| `cliId` / `toolName` / 名称） |
| `GET /time-series` | `days`、`granularity`=`day`\|`week`\|`month`、`kind`、`subjectId` | 按桶补零的时间序列（`timePoint` × 维度，行内带 `dimensionId` / `dimensionName`） |
| `GET /invocations` | `days`、`kind`、`toolName`、`mcpId`、`cliId`、`agentId`、`sessionId`、`outcome`、分页 | 明细分页，含 `error_message` / `args_json` / `result_excerpt` |

`groupBy=agent|session` 走明细表（日聚合表不带这两个维度），因此受保留窗口限制；`/summary` 默认口径走日聚合表，可答超过 90 天的窗口。

响应形状受 admin 既有出参约定约束：Jackson 3 序列化会丢掉值为 null 的键，前端不能假设有 `undefined` 之外的占位；业务错误是 HTTP 200 带 `code=400`。

## 8. 前端

`harnax-webui`：

- 路由：`config/routes.ts` 的 `monitor` 分组下新增 `{ path: '/monitor/call-metrics', name: 'call-metrics', component: './monitor/call-metrics' }`，位置在「技能用量」之后。
- 菜单：`src/locales/zh-CN/menu.ts` 与 `en-US/menu.ts` 补 `menu.monitor.call-metrics`（「调用监控」/「Call Metrics」）。
- 服务：`src/services/ant-design-pro/toolMetrics.ts`，三个 `getToolMetrics*` 函数，类型写进 `typings.d.ts`。
- 页面：工具 / MCP / CLI 三个 tab，共用一套形状——四张卡（调用量、成功率、P95、失败数）、一张 `@ant-design/plots` 的 `Line` 趋势、一张主体表（`harnax-webui/src/pages/token-monitor/index.tsx` 已有同型用法；页面骨架与卡片样式对齐 `src/pages/skill/usage.tsx`）。主体表行名可点开抽屉，列该主体最近的明细（`/invocations`），失败行的 `error_message` 直接展开。
- 窗口选择器与技能用量页同形（7 / 30 / 90 / 365 天）。
- 文案全部走 `pages.callMetrics.*`，中英两个 locale 都加。

## 9. 技能用量的连带改动

- `SkillUsageAdaptor` 从 `fun interface` 变成两个方法：`reportViews` 与 `reportUses`（后者签名带 `skillIds` + `sessionId` + `userId`，与前者一致）。
- 上报点在中间件：判定为 `load_skill_through_path` 且 `path` 为 `SKILL.md` 时，按 `skillId` 反解出 `skill.id`，经 `SkillUsageAdaptorImpl` 现成的异步队列发 `POST /api/admin/internal/skills/usage`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:291`）。服务端 `report` 已经校验事件种类只允许 `VIEW` / `USE`，不需要放宽。技能不是五种 `kind` 之一：这次调用自身按 §3 的判定落一行 `kind=framework` 的明细（它不在 `toolSpecs` 里），USE 只进 `skill_usage`，两处不互相代替。
- 不做冷却：一次 `load_skill_through_path` 就是一次 USE，模型不会在同一轮里反复装载同一技能，`VIEW` 那 60 秒冷却的理由（harness 每次组装系统提示都重读仓库）在这里不存在。上报点同在 `ToolInvocationMiddleware` 的 `TOOL_RESULT_END` 分支上，只在 `outcome=SUCCESS` 时发——技能没取到正文就不算被用过。
- 技能用量页去掉「执行次数这一列还没有数据来源」的自述：`pages.skill.usage.usesHint`（`harnax-webui/src/locales/zh-CN/pages.ts:209` 与 `en-US/pages.ts:209`）改为定义本身。

## 10. 删除清单

`tool_call_log` 这条链整体移除（D8）。以下清单是对着源码数出来的，不是按符号名推的：

- 基线里的 `tool_call_log` 建表块（`harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:757-779`，含它上下的 `/*!40101 SET character_set_client ... */` 守卫行）+ `schema-test.sql` 的同一建表块（`:741-765`）与它的三条夹具（`:870-873`）
- `harnax-entity`：`entity/ToolCallLogEntity.kt`、`mapper/ToolCallLogMapper.kt`、`resources/mapper/ToolCallLogMapper.xml`、`test/.../mapper/ToolCallLogMapperTest.kt`。**`.kt` 与 `.xml` 必须同一次提交删掉**：XML 靠 `mapper-locations: classpath*:mapper/*.xml` 通配绑定，没有任何配置按名字引用它，只删接口会让一份孤立的 XML 继续被 `SqlSessionFactory` 解析
- `harnax-tools-sdk`：`adaptor/ToolCallLogAdaptor.kt` 整文件——`ToolCallInfo`（`:13-30`）与接口同文件，一次删除带走两者；`ToolBox` 的 `init` / 两个 `execute` / `executeInternal` / `logToolCall` / `logToolCallError` / `userIdentifier()` / `userIdentifierValue` / `sessionMetaContextValue` / `toolCallLogAdaptorValue` / `lateinit var name` / `log`；`ToolCallContext.kt` 里的 `SessionMetaContext`
- `ToolBox` 只剩 `abstract fun name(): String`。`userIdentifier()` 在 main 里除自身声明外零调用方（`grep -rn "userIdentifier()" --include=*.kt` 只命中 `ToolBox.kt:41`；`DefaultAgentRunner` 用的是 `UserIdentifier` 类型不是这个访问器），所以 `init(...)` 整体消失而不是瘦身为 `init(userIdentifier)`
- `harnax-agent-service`：`adaptor/ToolCallLogAdaptorImpl.kt` 与 `adaptor/ToolCallLogAdaptorImplTest.kt`（两个整文件）
- `harnax-harness-core`：`HarnessAgentLauncher` 的 `toolCallLogAdaptor` 形参（`:117`）、KDoc `:102`、`toolBox.init(...)` 三处（`:390-394`、`:559`、`:566`）与 `:553-555` 的 `teamSessionMeta`、`initLauncher` 形参（`:1155`）与透传（`:1226`）；`HarnessAutoConfiguration` 的 import `:27`、provider 形参 `:320`、no-op 兜底 `:334-335`、注入 `:350`；`LauncherBean.kt:35-62` 那段注释掉的 `createLauncher` 里两处 `toolCallLogAdaptor`
- 工具实现去掉 `execute(...) { }` 包裹：`EmailToolBox.kt:64`（闭合在 `:124`）、`TimeToolBox.kt:23`、`TimeToolBox.kt:27`、`TeamToolBoxes.kt:26`、`:43`、`:58`、`:90`、`:104`、`:114`。后四个与被删的 `execute(vararg Pair, action)` 之外的三个是无参重载 `execute { }`，同样直接返回方法体；`:43` 与 `:90`、`:104` 里有 `return@execute`，拆包裹时改成普通 `return`
- 测试夹具：引用 `toolCallLogAdaptor` 形参的 harness-core 测试共 14 个（`HarnessAgentLauncherMemoryTest:22/:72`、`HarnessAgentTokenRecordingTest:14/:44`、`HarnessAgentTurnBudgetTest:20/:48`、`memory/MemoryBucketPipelineTest:19/:126`、`HarnessAgentLauncherLeadSkillTest:21/:74`、`HarnessAgentLauncherSkillSelfWriteTest:17/:65`、`HarnessAgentLauncherCliEnvTest:15/:44`、`HarnessAgentLauncherSkillVisibilityTest:16/:72`、`HarnessAgentLauncherSkillUsageTest:15/:86`、`HarnessAgentRunAttributionTest:18/:49`、`HarnessAgentProcessLogAttributionTest:19/:57`、`HarnessAgentSessionHistoryReadTest:12/:89`、`HarnessAgentLauncherCoordinationTest:15/:46`、`memory/MemoryGateFalsificationTest:20/:186`）；另需改 `tools-sdk` 的 `ToolBoxTest.kt`（`:26` 的 `TestableToolBox`、`:29/:31/:33` 的 `execute`、`:40/:47` 与 `:73-157` 那批断言日志的用例）与 `ToolCallContextTest.kt`（删 `SessionMetaContextTests` 内层类 `:19-64`，保留 `UserIdentifierTests` `:66-93`）、`EmailToolBoxTest.kt:3/:56` 与 `:121-131`/`:341-347`、`EmailToolBoxIntegrationTest.kt:3/:62`、`TimeToolBoxTest.kt:3/:39` 与 `:73-116`、`team/TeamToolBoxesTest.kt:3/:39` 与 `:29-36` 的 `leadCalls`/`memberCalls`/`wiredInto`
- 文档：`docs/architecture.md:263`、`:289`，`docs/tools-sdk-architecture.md:58-64`、`:176`，`docs/harnax-harness-core.md:331`，`docs/database-design-conventions.md:30`、`:78`，`docs/backend-code-conventions.md:595`，`docs/session-classification-design.md:121`，`harnax-agent/HARNESS_CORE_DOC.md:42`、`:93`、`:213`、`:262-263`、`:274-290`，`harnax-agent/harnax-tools-sdk/TOOL-DEV-GUIDE.md:25`、`:28`，`harnax-agent/harnax-agent-service/docs/conversation-flow.md:265-266`、`:720`，`harnax-admin/TOOL_INTEGRATION_DESIGN.md`（§2.4 整节与 `:352` 那条「保留不删」），`AgentSpec.kt:63-72` 的 KDoc 三件套，以及 `prod_doc/` 里 `tool-capability` / `tool-integration-design` / `multi-agent-team-design` / `product-overview` / `skill-management` 五个中英成对文件的对应段落

`ToolCallContext.kt` 里的 `UserIdentifier` 与 marker 接口 `ToolCallContext` 都保留（launcher 与 MCP 授权链路在用），删的只有 `SessionMetaContext`。`mcp_call_log` 一行不动，但它的 DDL 注释与文档要写明它是授权账本、不是指标源。

一处删不干净的地方要写进部署文档：改了基线不会 DROP 已在跑的库里的 `tool_call_log`（Flyway 没有后续迁移，清库重建才会没）。留着它只占一张空表；要真删得手工 `DROP TABLE`，文档给命令并标明它是有损动作。

## 11. 测试与验收

- 单测：`kind` 判定纯函数穷举五种输入形状与优先级冲突；CLI 命令解析（管道、`&&`、绝对路径、带空格的引号）；`ToolResultState` 到 `outcome` 的映射；未终态补 `INTERRUPTED`；截断与关闭开关；丢弃计数。
- 持久层测（`harnax-entity`，真实 MySQL 8 容器）：`harnax-entity` 没有 failsafe，也没有 `integration-test` profile，所以这一层的容器测就叫 `*MapperTest`，形状照 `TokenStatsMapperTest.kt:33-61`（`@Testcontainers @MybatisTest @AutoConfigureTestDatabase(NONE) @ActiveProfiles("test")` + companion 里每类一个 `MySQLContainer("mysql:8.0").withInitScript("schema-test.sql")`）。闸门：
  - 同一会话三次调用必须落三行、且三个不同的时刻（`start_time` 是 `datetime(3)`，夹具仍显式给出相隔的毫秒戳），断言行数＝调用数。
  - `batchInsert` 一批 N 行返回 N；空批不调用（`foreach` 会生成非法 SQL）。
  - upsert 重算两次结果不变；六桶之和与 `calls` 相等；`calls` 与四个终态计数之和相等。
- 端到端持久化测（`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/`，继承 `BaseAdminIT`）：聚合、读侧租户谓词与清理三条闸门放在这里，因为只有 admin 侧的 IT 跑真实 Flyway 基线（共享一个容器、schema 由迁移建，夹具用 `931_931` 那种自造私有租户号，不碰 tenant 1）。
  - 读侧：租户谓词必须把自己的数据滤出来，窗口边界那天不能出现在另一个租户的行里。
  - 聚合与清理：`retention-days=0` 且该日尚未折算时删除必须不动它；折算过之后同一批行被删掉；把聚合表里某一天删掉再跑一次，那一天必须被补齐。
  - `SchemaBaselineDriftIT` 是这张页的隐形闸门：新表只进 `V1__init_schema.sql` 而不进 `schema-test.sql` 会让它的六条断言全红（它逐字对比两侧表、列、索引名）。
  - 时区陷阱：Testcontainers 的 MySQL 是 UTC，Java 侧 `LocalDateTime` 按 JVM 时区写 `datetime`，按 `DATE(ts)` 分桶的用例要么固定 UTC 要么用相对当天而不是绝对日期。
- 跑法：`mvn -o test -pl harnax-entity`；admin 侧 `mvn -o verify -pl harnax-admin -am -Pintegration-test -Dit.test=<类名> -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`（`-am` 与两个 `failIfNoSpecifiedTests` 都不能少，`docs/unit-test-cases.md:799-810`）。
- 前端：`max build` + `biome lint` 过（本仓 webui 的可用闸门，`tsc` 全是既有噪声）。
- 端到端（harnax-deploy 真栈）：一个装了 MCP、勾了一个 CLI 和一个技能的 agent 跑一轮，页面上 `mcp` tab 有非零调用、`cli` tab 记到该命令、技能用量的 USE 从 0 变正。

## 12. 已知边界与不做

- 一条 shell 命令串里出现两个已下发 CLI 时，只记最左命中的那个（复合命令拆行会破坏 I1「一次调用一行」，代价是漏记；`args_json` 里有完整命令串可复核）。
- CLI 命令别名只来自 `name` + `checkCommand` 首词（payload 未物化时）：包内二进制若与包名不同且 `checkCommand` 里不出现它，就归因不到，落 `kind=shell`。
- 两个 MCP server 暴露同名工具时，按名覆盖发生在上游的 `io.agentscope.core.tool.ToolRegistry`（它以工具名为键，`Toolkit` 持有并转调），模型侧本来就只能看见后注册的那一个；归因跟着枚举结果走，因此记给活下来的那个 server，与运行时实际调用的是谁一致。本项目的 `com.agnetix.harnax.tools.sdk.registry.ToolRegistry` 是另一个同名的类，它按 Spring bean 名键控 admin 下发的 `ToolBox`，不参与 MCP 注册。
- 不做按小时聚合（窗口超过保留期就没有小时粒度）；不做 OTel / 分布式 trace；不做工具级成本核算；不给 `mcp_call_log` 加指标读端；不引入 ClickHouse 之类外部指标存储。
- 明细里的 `args_json` 可能含敏感字面值，默认截断 + 可用 `capture-payload=false` 整体关闭；本设计不做字段级脱敏。

## 13. 落点

| 动作 | 位置 |
|---|---|
| 新增 | 中间件 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt` + 同目录的 `kind` 判定与 CLI 归因纯函数；写入契约 `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt`；实现 `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt`；两张表 `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/{ToolInvocationLog,ToolInvocationStats}.kt`（新表跟 `TokenStats.kt`、`SkillUsage.kt` 不带 `Entity` 后缀）+ `.../mapper/{ToolInvocationLogMapper,ToolInvocationStatsMapper}.kt` + `harnax-entity/src/main/resources/mapper/*.xml`；读端 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt` + `.../service/ToolMetricsService.kt` + `.../service/impl/ToolMetricsServiceImpl.kt` + `.../service/ToolInvocationRollupService.kt`，IT 落 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/`；前端 `harnax-webui/src/pages/call-metrics/index.tsx` + `harnax-webui/src/services/ant-design-pro/toolMetrics.ts` + `src/typings.d.ts` 的类型 + 两份 locale |
| 修改 | Flyway 基线 + `schema-test.sql`、`HarnessAgentLauncher`、`HarnessAutoConfiguration`、`SkillUsageAdaptor` / `SkillUsageAdaptorImpl` / `AdminApiClient`、`HarnaxAdminApplication`（`@EnableScheduling`）、`config/routes.ts`、技能用量页文案、`prod_doc` 双语包（工具能力 / MCP 管理 / CLI 包 / 技能）与 `docs/deploy-harnax-admin.md`（保留窗口与重建库） |
| 删除 | §10 清单 |
