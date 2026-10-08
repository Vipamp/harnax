# 调用监控 · 小时档与维度实名化 · 设计规格

日期：2026-10-08
分支：`feat/call-metrics-hourly`（基线 `kotlin-dev` @ `2e63c466`）

## 0. 要解决的问题

调用监控页（`harnax-webui/src/pages/call-metrics/index.tsx`）现在有三处读不顺：

1. 时间范围选择器挂在「主体明细」这张卡的右上角，但它驱动的是整页四张卡、趋势与抽屉；位置让人以为只管下面那张表。并且它只能选到「天」，看「今天下午这三小时谁在报错」答不了。
2. 分组维度下拉在三个 tab 里是同一份选项（按工具／按智能体／按会话）。在 MCP tab 里「按工具」列的是某个服务器暴露的每个工具，想按服务器看一行汇总没有档；CLI tab 同理。
3. 表格第一列统称「主体」，格子里的东西却不统一：工具维度是名字，智能体维度是裸 `agent_id`，会话维度是裸 `session_id`（`web-…` 这种）。读的人既不知道列在说什么，也没法从名字认出是哪一台服务器、哪一个包。

## 1. 决策清单

| # | 决策 | 取值 |
|---|---|---|
| D1 | 聚合粒度 | `tool_invocation_stats` 由「一天一行」改成「一小时一行」，`stat_date date` 换成 `stat_hour datetime`，只写整点 |
| D2 | 粗粒度怎么来 | 表里**只留小时一档**。日／周／月由小时求和得到，不并存两档 |
| D3 | 时间选择精度 | 到小时，不到分秒。选择器给 `YYYY-MM-DD HH:00` 形状 |
| D4 | 窗口钳位 | 由「天」改「小时」：默认 30 天=720 小时，上限 365 天=8760 小时 |
| D5 | 趋势粒度 | 服务端按窗口跨度自动选档（≤48 小时→小时，≤92 天→日，更宽→周），响应回 `granularity`，前端不加这枚控件 |
| D6 | 维度矩阵 | 工具 tab＝工具／智能体／会话，MCP tab＝MCP／智能体／会话，CLI tab＝CLI／智能体／会话。新增 `mcp`、`cli` 两个维度 |
| D7 | 名称解析 | 在服务端 SQL 里 LEFT JOIN 出 `subjectName`，不在前端逐行查登记接口 |
| D8 | 解析不到时 | 回落原 key（登记行已删、`chn-`／`task-` 会话在 `session` 表本就无行），不留空白格 |
| D9 | 选择器位置 | 移到页首 Tabs 那一行的右侧，一处驱动整页 |
| D10 | 兼容 | 不考虑历史兼容，按全新设计落；schema 变更走 V5 前向增量 |

## 2. 数据模型

### 2.1 `tool_invocation_stats` 的列变化

| 列 | 现在 | 改后 |
|---|---|---|
| `stat_date` `date` | 一天一行 | 删 |
| `stat_hour` `datetime` | — | 加，值恒为整点（分钟秒为 0），注释写明「取 `tool_invocation_log.ts` 向下取整到小时」 |
| 唯一键 | `uk_tool_invocation_stats_day (stat_date, tenant_id, kind, subject_id, tool_name)` | `uk_tool_invocation_stats_hour (stat_hour, tenant_id, kind, subject_id, tool_name)` |
| 二级索引 | `idx_tool_invocation_stats_tenant_date (tenant_id, stat_date)` | `idx_tool_invocation_stats_tenant_hour (tenant_id, stat_hour)` |

其余列的类型与取值不动：`kind`、`subject_id`、`tool_name`、五个计数、`sum_duration_ms`、`max_duration_ms`、六个耗时桶。V5 里另有三段 `MODIFY COLUMN`（`subject_id`、`tool_name`、`max_duration_ms`）和一条表注释，改的都是注释文本——V3 把它们写成了日档口径（「按天保留」「当天最长一次」），改列时不一起重写就会在 schema 里留下一份说错粒度的文档，取值与类型一个都不动。

### 2.2 为什么只留小时一档

六个耗时桶、`calls`、`successes`／`errors`／`denials`／`interruptions`、`sum_duration_ms` 全是可加量，`max_duration_ms` 取 max 也可加。所以「一天」就是当天 24 行小时求和，读出来的数与今天按天折算的逐字相同，P95 仍走同一套六桶判定。表里若同时存日行与小时行，任何一次跨粒度求和都会把同一时刻数两遍，而这没有任何读法需要它。

代价写清楚：小时行是日行的 24 倍，聚合表按永久保留设计。

### 2.3 迁移形状

`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql`：先 `DELETE FROM tool_invocation_stats`，再一条 `ALTER TABLE` 把 `stat_date` 改名成 `stat_hour`、换掉唯一键与二级索引、重写 §2.1 说的那四处注释。清表不是丢数据，且不清不行：旧的一天一行原样搬成小时档会被读作「00:00 这一小时装了一天的量」，而待折算集合按 `(stat_hour, tenant_id)` 认定那一小时已经折过，`deleteRolledOut` 于是放掉它背后的明细，那些小时再也重折不出来。明细在保留窗口内全在，`selectUnrolledHours` 不设下限、每轮把缺的小时全补上，所以 V5 之后第一次 :05 折算就把窗口内的小时行重新折出，窗口本身有界。`tool_invocation_stats` 由 V3 建，V3 与 V4 都已在现网应用且 Flyway 逐次校验和，已应用的迁移连注释都不改，因此走前向增量。

`harnax-entity/src/test/resources/schema-test.sql` 同步成 V5 之后的形状（它是 admin 库重放到最新版的 schema 副本），漂移由 `SchemaBaselineDriftIT` 守。

### 2.4 小时档不变量

编号只用 H 起头：本域另有一份覆盖两张表的 I1~I6，四个字母撞名会让「I3」在两份文档里指不同的规则。

- H1 `stat_hour` 恒为整点：写入侧只有 `upsertHour` 一处产行，它的 `SELECT` 直接把参数列写成传入整点。
- H2 一小时一 (tenant, kind, subject, tool) 至多一行：由唯一键与 upsert 一起保证。
- H3 任一跨度的窗口，`calls = successes + errors + denials + interruptions`：四种终局互斥且穷尽，粒度换档不动这条。
- H4 桶闭右开左不动，桶和恒等于 `calls`。

## 3. 折算与清理

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt` 的三处配对一起换档，跑法不变（`0 5 * * * ?`，每小时一次，无分布式锁）：

| 环节 | 现在 | 改后 |
|---|---|---|
| 找欠账 | `selectUnrolledDates` 按 `DATE(l.ts)` 去重 | `selectUnrolledHours` 按 `DATE_FORMAT(l.ts, '%Y-%m-%d %H:00:00')` 去重，`NOT EXISTS` 配 `s.stat_hour` 同一整点 |
| 折算 | `upsertDay(statDate)` 重算整天 | `upsertHour(statHour)` 重算该小时：`ts >= #{statHour} AND ts < #{statHour} + INTERVAL 1 HOUR` |
| 强制补算 | 昨天 + 今天 | 上一个整点 + 当前整点 |
| 释放明细 | `deleteRolledOut` 的 `EXISTS` 配 `s.stat_date = DATE(ts)` | 配 `s.stat_hour = DATE_FORMAT(ts, '%Y-%m-%d %H:00:00')` |

强制补算那两格的理由不变，只是从「天」缩到「小时」：折算在 :05 触发，一个整点最后几分钟迟到的明细行已经进了那一小时的聚合、不会再回到欠账集合，没有上一整点兜着，每个整点收尾那一段会被聚合覆盖却被 `deleteRolledOut` 放掉。

保留窗口仍是天为单位（`harnax.metrics.retention-days:90`，钳位 1..3650），它管的是明细行什么时候可以删，与聚合按什么粒度折无关，不动。

## 4. 读侧窗口与粒度

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt`：

- `window()` 收 `yyyy-MM-dd HH:mm`；只给日期（`yyyy-MM-dd`）按当天 00:00 起算，两种形状都认。
- 钳位逐条换到小时：`end` 缺省=当前整点，落在未来钳回当前整点；`start` 缺省=`end` 往前 720 小时；`start > end` 塌到 `end`；跨度超 8760 小时把 `start` 往后推而不是拒。每一次钳位仍打一条带解析结果的日志，响应里回的是真正数出来的那个窗口。
- 聚合侧三个读法（`selectWindowTotals`、`selectSubjectTotals`、`selectTimeSeries`）边界由 `stat_date` 的日期串比较改成 `stat_hour` 的整点比较，闭区间上界就是所选整点本身。
- 明细侧两个读法（智能体／会话维度）仍用 instant 边界，只是上界不再固定是「当天 23:59:59.999」而是所选整点。
- `selectTimeSeries` 的桶表达式加 `hour` 一档：`DATE_FORMAT(s.stat_hour, '%Y-%m-%d %H:00:00')`；`week`／`month`／`day` 三档仍对 `stat_hour` 求和，对齐规则不变（周对齐周一）。
- 自动选档：跨度 ≤48 小时给 `hour`，≤92 天给 `day`，更宽给 `week`。请求里显式带 `granularity` 时以请求为准，`month` 仍可达。响应回实际用的那一档。

## 5. 维度与名称解析

### 5.1 四个维度

| `groupBy` | 分组键 | 取数表 | 有六桶（P95 为桶口径） |
|---|---|---|---|
| `tool` | `kind, subject_id, tool_name` | 聚合 | 是 |
| `mcp` | `kind='mcp', subject_id` | 聚合 | 是 |
| `cli` | `kind='cli', subject_id` | 聚合 | 是 |
| `agent` | `agent_id` | 明细 | 否（给的是最长一次，算子 `<=`） |
| `session` | `session_id` | 明细 | 否（同上） |

`mcp`／`cli` 两个新维度在聚合表上按 (kind, subject_id) 收，`tool_name` 不参与分组：一个服务器一行，它当天暴露的几个工具的计数与桶一起加进来。这两个维度各自钉死自己的 `kind`，与页面上方 tab 的 `kind` 是同一件事，所以 tab 切到 MCP 而 `groupBy=mcp` 时不需要额外 `kind` 谓词也不会串味。

`dimension()` 的合法性判定跟着扩：`agent`／`session`／`mcp`／`cli` 之外一律落 `tool`。

### 5.2 名称从哪来

`ToolMetricsRow` 新增 `subjectName`，由 SQL 直接带出：

| 维度 | 名称列 | 取法 |
|---|---|---|
| `tool` | `tool_name` 本身 | 无；来源为 `mcp`／`cli` 的行另带所属服务器／包名 `parentName`，取法同下面两行（按 `subject_id`） |
| `mcp` | `mcp_server.name` | `LEFT JOIN mcp_server m ON m.id = s.subject_id AND m.tenant_id = s.tenant_id` |
| `cli` | `cli.name` | `LEFT JOIN cli c ON c.id = s.subject_id`（`cli` 表无租户列，登记模型本来不分租户） |
| `agent` | `agent.name` | `LEFT JOIN agent a ON a.id = l.agent_id AND a.tenant_id = l.tenant_id` |
| `session` | `session.title` | 标量子查询：`(SELECT ss.title FROM session ss WHERE ss.session_id = l.session_id AND ss.tenant_id = l.tenant_id ORDER BY ss.id DESC LIMIT 1)` |

四张登记表里只有 `mcp_server`／`cli`／`agent` 是按主键 `id` 对上的，1 对至多 1，LEFT JOIN 不会改行数。`session` 不行：它的 `session_id` 只在注释里写着 "Unique session identifier"，索引清单里 `session_id` 连一条普通索引都没有，更不必说唯一索引，所以数据库并不拦同一 `session_id` 的两行。一旦真有两行，JOIN 会把 GROUP BY 之后的一行扇成两行，`COUNT(*)` 与五个计数当场翻倍——聚合侧的数与明细侧的数从此对不上，而页面上看不出来。标量子查询每行只跑一次且恒返一个值，无论重复与否都不改行数。

`subjectKey` 保留不动（它是抽屉与下钻定位用的那把键），`subjectName` 只用于展示；两者不同名时页面读名字，点进去仍按 key 取数。新增两档的 `subjectKey` 取 `subject_id` 的字符串形式，`subjectName` 取登记名，登记不到就回落这把键。

`mcp_server`／`agent`／`session` 三张表都有 `tenant_id`，JOIN 就带这条谓词：admin 侧不设 MyBatis 租户拦截器，租户安全只看 SQL 里有没有谓词。

### 5.3 前端列名与文案

- 第一列表头按当前维度给名：`工具`／`MCP`／`CLI`／`智能体`／`会话`。`pages.callMetrics.col.subject` 这条 key 撤掉，改成按维度取 key。
- 卡片名「主体明细」→「明细」；抽屉标题「主体信息」→「登记信息」；提示语里「主体」全部换成具体维度名。
- `#id` 那一种渲染撤掉：第一列一律显示服务端带出的 `subjectName`，缺失时回落 `subjectKey`，`#4` 对读的人没有信息量。工具档的限定名 `parentName` 字段与那一格都留着，但 D6 的矩阵只在工具 tab 给「按工具」，而该 tab 的来源选择器只有 `builtin`／`shell`／`framework`，所以这一格在当前页面上取不到行——它等的是来源选择器给出跨来源的工具档。
- 会话维度格子显示 `session.title`，无行时回落 `session_id`；智能体维度显示 `agent.name`，已删时回落 id 字符串。
- 记录抽屉（点调用量列开的那只）里的「会话」列同样按 title 优先、id 兜底渲染。

## 6. 前端

`harnax-webui/src/pages/call-metrics/index.tsx`：

- RangePicker 移到 `Tabs` 的 `tabBarExtraContent`（页首右侧），`showTime={{ showMinute: false, showSecond: false }}`，`format="YYYY-MM-DD HH:00"`。预设近 7／30／90／365 天保留，按整点对齐到「现在」。
- 一处 range 驱动整页这条不变：四张卡、趋势、表格、两只抽屉共用同一对 `start`/`end`。
- 换 tab 时若当前 `groupBy` 在新 tab 不合法（例如从工具 tab 的「工具」切到 MCP tab），落到该 tab 的第一档；「智能体」「会话」两档跨 tab 保留。
- 维度下拉的选项由一张 `TAB_DIMENSIONS: Record<tab, [dim, dim, dim]>` 给出，标签按维度取 i18n key。
- 趋势 X 轴按响应回的 `granularity` 格式化：`hour` 给 `MM-DD HH:00`，其余仍 `MM-DD`。
- 新增/改名的文案 `zh-CN` 与 `en-US` 两份 locale 同步落。
- `subjectProfile.ts` 的 `profileTargetOf` 要认 `mcp`／`cli` 两个新维度（都按 `subjectId` 取登记行）。

## 7. 测试与验收

后端（`harnax-admin` 的 IT，跑法见本机配方）：

- 折算幂等：同一整点跑两次 `rollUp()`，小时行的每个数逐字不变。
- 迟到行取舍：待折算集合不再点名已折过的整点，兜住它的是每轮强制重折「上一个整点 + 当前整点」，这条由 `lateRowOfTheClosingHourIsFolded` 钉住；比这更早的整点折完又来一行时不进这一轮，明细按窗口到期即被 `deleteRolledOut` 放掉——那是接受的成本，只有时钟偏差超过一小时才出得来。
- 新维度：`mcp`／`cli` 各一条，断言一个服务器多工具时收成一行且桶求和正确。
- 维度不串味：`groupBy=mcp`／`cli` 而调用方不给 `kind` 时，这两档自己钉死来源桶，聚合里 `builtin`／`shell`／`framework` 的行不进结果。
- 名称：五个维度各一条命中；再加回落（登记行删掉、`chn-` 会话无 `session` 行）。记录抽屉的会话列同一条用例里断言 title 优先、无登记行时回落到 id。
- 不扇出：同一 `session_id` 造两行 `session`，断言会话维度的 `calls` 与四计数仍是明细行数本身、只多出一个标题选择。这条是标量子查询代替 JOIN 的唯一正面证据。
- 窗口钳位：`end` 在未来、`start > end`、超 8760 小时、不可解析四种，断言解析后的窗口与日志档位。
- 自动选档：48 小时／92 天两条边界两侧各一。
- 粒度换档：`ToolInvocationRollupIT` 断言小时行的 `calls` 与桶和就是它折自的明细行数（这条是 D2 的正面证据——旧的按天折算已被 V5 清掉，没有第二份口径可比）。

前端：`subjectProfile.test.ts`、`lastSeen.test.ts` 补新维度 case；`npx max build` 通过；改动文件逐个 `npx @biomejs/biome lint`（不许用 `check --write`）。

现网：admin 起来后 `docker-compose logs admin` 见 `now at version v5`；V5 已清空聚合表，所以第一次 :05 折算会把保留窗口内所有还留着明细的小时重新折出，看 `tool_invocation_stats` 出现小时行且任一整点的 `calls` 与 `tool_invocation_log` 里同一整点、同一 `tenant_id`、同一 `kind` 的行数相等（没有旧日行可对了，能对的只有明细）；页面在 MCP tab 切「按 MCP」能出一行一台服务器。

## 8. 已知边界与不做

- 智能体／会话两维仍读明细表，仍只覆盖保留窗口（默认 90 天）内的调用；这条不是本轮引入的，本轮只把文案里的维度名换准。
- 不做分秒：窗口只到整点，`< 整点+1h` 的边界保证一个整点桶里就是那 60 分钟。
- 不在聚合表加 `agent_id`／`session_id` 维度：那会把小时行的基数再乘一个会话数，而这两个维度今天读明细就够了。
- 不做前端逐行查登记接口来凑名字：一页 20 行就是 20 次请求，且失败时只能显示空白。
- 不改写入侧（`ToolInvocationMiddleware` 与明细表）：粒度只影响折算与读法。

## 9. 落点

| 文件 | 动作 |
|---|---|
| `harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql` | 新建 |
| `harnax-entity/src/test/resources/schema-test.sql` | `tool_invocation_stats` 换列与键 |
| `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml` | `upsertHour`、三个读法换 `stat_hour`、`hour` 桶、`mcp`／`cli` 分组与名称 JOIN |
| `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml` | `selectUnrolledHours`、`deleteRolledOut` 按整点配对、两个明细读法补名称 JOIN |
| `harnax-entity/.../ToolInvocationStatsMapper.kt`、`ToolInvocationLogMapper.kt` | 接口签名跟着换 |
| `harnax-admin/.../ToolInvocationRollupService.kt` | 小时档折算与补算窗口 |
| `harnax-admin/.../ToolMetricsServiceImpl.kt` | 窗口到小时、自动选档、四个维度、`subjectName` |
| `harnax-admin/.../dto/ToolMetricsResponse.kt` | `subjectName` 字段 |
| `harnax-admin/.../controller/ToolMetricsController.kt` | `start`／`end` 参数说明与 `groupBy` 取值域 |
| `harnax-webui/src/pages/call-metrics/index.tsx`、`SubjectDrawer.tsx`、`subjectProfile.ts` | 选择器上移与小时档、维度矩阵、列名与实名 |
| `harnax-webui/src/services/ant-design-pro/toolMetrics.ts`、`src/typings.d.ts` | 契约与类型 |
| `harnax-webui/src/locales/{zh-CN,en-US}/pages.ts` | 文案两份 |
| `prod_doc/tool-mcp-cli-call-metrics-design.{zh-CN,en-US}.md` | 现状两份同步（成对判据逐条跑） |
| `docs/deploy-harnax-admin.md` | V5 与折算口径 |
