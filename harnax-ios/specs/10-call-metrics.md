# iOS 实现规格 10：调用监控（工具 / MCP / CLI / 内置调用指标与单次调用下钻）

两屏一问：主屏答「这个窗口里每个主体被调了多少次、成多少、败多少、慢到什么程度」，从主体行 push 出去的第二屏答「那一次调用具体是什么」——参数、结果片段、失败原因、真实耗时。行为逐条对齐 harnax-webui 现存页面。所有锚点均为仓库内真实读过的行号（2026-10-11 逐条核过），格式 `相对路径:行号`：后端与 webui 从仓库根起算，iOS 从 `harnax-ios/` 起算。后端统一 `ResultVo<T>` 信封 `{code, message, data}`；admin 的 Jackson 丢弃 null 键，因此除注明「必带」外 iOS DTO 一律可选。

## 1. 范围与取舍

- 入口挂「我的」的「集群监控」块第三行（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:21-22`，绘制与门都在 `Sources/HarnaxFeatures/Me/MeView.swift:98-119`）。控制台的对应物是一级「集群监控」组里的 `call.metrics`（`harnax-webui/config/routes.ts:170-174`），映射是 `MonitorRoute.callMetrics` → `harnax-webui/src/pages/call-metrics/index.tsx`（目的地在 `Sources/HarnaxFeatures/Me/MeView.swift:153-156`）。**三行都不设管理员门**：控制台的三条监控路由都不带 access（`harnax-webui/config/routes.ts:165-179`），带 access 的是同一份文件里的 API Key 路由（`:205-210`）；iOS 侧过滤只看门面装配没装配（`Sources/HarnaxFeatures/Me/MeView.swift:102`），不看账号。「我的」同页的「系统管理」块是另一种东西——会写记录的才需要门（`Sources/HarnaxFeatures/SystemDomain/SystemRoute.swift:18-25`），`SystemRoute` 里不再有任何监控项。
- **两读撑起主屏、一读撑起下钻**，全部为 GET：`ToolMetricsReading` 三个方法、零个写方法（`Sources/HarnaxCore/Contract/ToolMetrics.swift:297-315`）。主屏进屏即并发发 `summary` 与 `time-series`，与控制台同一个 `useEffect` 里的 `Promise.all` 同形（`harnax-webui/src/pages/call-metrics/index.tsx:78-81`）；`invocations` 只在卡片被 push 出去的第二屏发。控制台把下钻做成同一页旁边的两个抽屉（列表抽屉 `harnax-webui/src/pages/call-metrics/index.tsx:514-561`、档案抽屉 `harnax-webui/src/pages/call-metrics/index.tsx:563`），iOS 只有一个变成 push 的第二屏：档案抽屉读的是 agent / MCP / CLI / session 四张登记表，这三个路由一概不答（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:12-14`），所以它没有可移植的形状，那一屏只答「这些次调用分别是哪一次」。
- 分组维有**五个**：`tool` / `mcp` / `cli` / `agent` / `session`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:464-471`），且不是装饰性的划分——前三读小时聚合表、后二读明细表（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:69-73`）。**可下钻的门是 `subjectId` 而不是维度**：`isDrillable { subjectId != nil }`（`Sources/HarnaxCore/Contract/ToolMetrics.swift:73`），而服务端只在 mcp / cli / agent 三种行上给出这个 id，builtin / shell / framework 的 `subject_id` 是 0、session 维度直接 `CAST(NULL AS SIGNED)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:380`、`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:73-77`）。详见 §2.4 的行-kind 表——这是本屏与控制台最大的一处真实行为差（控制台把下钻挂在 calls 列上、对任何行都开抽屉，`harnax-webui/src/pages/call-metrics/index.tsx:252-262`）。
- 租户不进参数：三个路由都没有 `tenantId` 形参，调用方凭据即租户（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:20-28`，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:433`），因此 `X-Tenant-ID` 头是唯一的杠杆，与其他 admin 域一致。
- 不做：自由区间选择器（控制台是 `RangePicker` + 四个预设，iOS 只有三档预设，见 §3.5 与 §8）、逐桶的成功率与 P95（这两个是窗口级数字，`harnax-webui/src/pages/call-metrics/index.tsx:505` 的文案同一句话就说明它不是逐桶的）、主体档案抽屉（上一条）、把趋势线按主体收窄（`subjectId` 在主屏永远不发，§4）。

## 2. 后端契约：三条路由、两张表、五个分组维

### 2.1 三条路由

前缀 `/api/admin/tool-metrics`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:27`），三个 `@GetMapping` 各一条：`/summary` `:35`、`/time-series` `:56`、`/invocations` `:79`。三条都裹在 `try/catch` 里返回 `ResultVo`，失败走 `ResultVo.error`（`:49-54`、`:72-77`、`:107-128`）——HTTP 仍是 200，业务码在信封里，因此 iOS 侧的错误判定只看信封与状态码两路，与其他 admin 域同。

| 路由 | 形参（*为可缺*） | 缺省与取值 |
|---|---|---|
| `/summary` | `start`* `end`* `kind`* `groupBy` | `groupBy` `defaultValue = "tool"`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:41-48`）；不认识的值回落 `tool` 而不是报错（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:262-264`） |
| `/time-series` | `start`* `end`* `kind`* `subjectId`* `granularity` | `granularity` `defaultValue = "auto"`（`:62-71`）；`subjectId` 是「MCP server or CLI package id」，其余来源没有可查的 id |
| `/invocations` | `start`* `end`* `kind`* `toolName`* `mcpId`* `cliId`* `agentId`* `sessionId`* `outcome`* `pageNum` `pageSize` | `pageNum` 缺省 1、`pageSize` 缺省 20 且文档写明 1..200（`:103-106`），服务端 `coerceAtLeast(1)` / `coerceIn(1, MAX_PAGE_SIZE)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:139`，常量 `:462`） |

`kind` 是来源桶，五个词 `builtin` / `mcp` / `cli` / `shell` / `framework`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:45`，列注释同一份词表在 `harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql:15`）。`outcome` 是四个终态 `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`（`:101-102`，`harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql:19`）。两者都是**空串等于不发谓词**：`<if test="kind != null and kind != ''">`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:180-182`）、`<if test="outcome != null and outcome != ''">`（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:179-181`）。

### 2.2 窗口在服务端收口，`end` 是「到当前小时」

两端都接受 `yyyy-MM-dd HH:mm` 或裸 `yyyy-MM-dd`（先按小时解、再按天解，都失败即视为缺席）：`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:362-369`，小时形参的格式在 `:458`。收口顺序写在 `:330-359`，每一步都带日志、且响应把收口后的区间回显出来：

| 情形 | 服务端怎么答 |
|---|---|
| `end` 缺席或解不开 | 当前小时（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:334-339`） |
| `end` 在未来 | 夹回当前小时（`:340-343`） |
| `start` 缺席或解不开 | `end` 往前 **720 小时**（`DEFAULT_WINDOW_HOURS = 720`，`:348`、常量 `:460`）——即 30 天，与控制台 `DEFAULT_RANGE_DAYS = 30` 同（`harnax-webui/src/pages/call-metrics/index.tsx:22`） |
| `start` 晚于 `end` | 塌到 `end` 一个小时（`:349-352`） |
| 跨度超过上限 | 推 `start`，**不拒**：`MAX_WINDOW_HOURS = 8760`，即 365 天（`:353-357`、常量 `:461`） |

两端都是**闭区间的小时起点**（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:443-451`）：聚合侧直接 `stat_hour >= from AND stat_hour <= to`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:156-157`），明细侧换成 `ts >= from AND ts < to + 1h`（`:450`，谓词在 `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:109-110`、`:159-160`）。这两个配对是同一个 60 分钟窗口，写死在一处注释里（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:435-442`）——任何别的配对都会让每次请求的最后一个小时在两种读法之间错一格。

### 2.3 分桶：`auto` 是发出去的话，回回来的是服务端折叠的结果

`granularity` 只认四个真桶 `hour` / `day` / `week` / `month`；`auto` 与任何不认识的值都走跨度规则：≤48 小时用 hour，≤92 天用 day，再宽用 week（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:267-279`，四个常量 `:473-476`；控制器文档同调 `:70`）。答的桶写在响应 `granularity` 上回显（`:134`），所以客户端**不重算跨度**，只读回来（`Sources/HarnaxCore/Contract/ToolMetrics.swift:193-196`）。

趋势是**零填充**的：查询只答有行的桶，服务端把「窗口内每个桶 × 窗口内出现过的每个主体」这张网格补齐，缺行的桶按空行答 0（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:110-133`）。主体的身份是 `kind|dimensionId|dimensionName` 三段拼串，且**刻意不把聚合里代表 builtin 的 0 折成缺席**（`:371-377`）。桶起点对齐规则两侧必须一致：SQL 的 `DATE_FORMAT` 表达式在 `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:187-200`，Kotlin 的 `align` 在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:305-313`——hour 取整点、week 回周一、month 回 1 号、day 取 00:00。

### 2.4 五个维度读两张表，且 GROUP BY 的形状各不相同

`tool` / `mcp` / `cli` 走小时聚合表 `tool_invocation_stats`，`agent` / `session` 走明细表 `tool_invocation_log`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:69-73`）。真实分组：

- 聚合侧 `selectSubjectTotals`：`GROUP BY s.kind, s.subject_id`，仅当维度是 `tool` 才追加 `, s.tool_name`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:168-169`），`ORDER BY calls DESC, lastSeenAt DESC`（`:170`）。也就是说 mcp / cli 维度把同一个服务器的多个工具**折成一行**，而 tool 维度按 (kind, subject_id, tool_name) 分组，因此两台服务器上的同名工具是两行。
- 聚合侧对 `mcp` / `cli` 两个维度**自己钉住 kind**，不吃调用方给的 `kind`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:158-167`）；服务同样把窗口卡片的 `kind` 换成维度自带的桶（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:55-60`）。iOS 的 `effectiveKind` 就是这条规则的镜像（§4）。
- 明细侧 `selectSubjectTotalsFromDetail`：`GROUP BY l.session_id` 或 `l.agent_id`，`HAVING subjectKey IS NOT NULL`（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:122-127`）——没有 agent 行可归的调用（团队 lead 就是这种）留在窗口卡片里，但不出现在 agent 列表里，**所以 agent 维度各行之和不等于窗口卡片**（`:116-121`）。session 标题走标量子查询而不是 join，因为 `session.session_id` 没有唯一索引，join 会把一个分组扇成多行、把下面的计数翻倍（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:78-93`）。
- 窗口卡片 `selectWindowTotals` **没有 GROUP BY**，是一条十三列的和（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:173-183`），且与分组读用同一段列片段 `:98-112`——两处各写一份列表会在某个桶边界上悄悄漂移，卡片就和行集对不上了（`:88-92` 的注释就是这个理由）。

**行-kind 与可下钻性**（谓词来源 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:380` + `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:73-77`，判定 `Sources/HarnaxCore/Contract/ToolMetrics.swift:73`）：

| 维度 | 行的 kind | `subjectId` | iOS 是否 push |
|---|---|---|---|
| `tool` | `mcp` / `cli` 的行 | 服务器 / 包 id | 是 |
| `tool` | `builtin` / `shell` / `framework` 的行 | 聚合列写的是 0，读回即 null | 否 |
| `mcp` / `cli` | 维度自带，恒为 `mcp` / `cli` | 服务器 / 包 id | 是 |
| `agent` | 空串（跨来源） | `l.agent_id` | 是 |
| `session` | 空串（跨来源） | SQL 显式 `CAST(NULL AS SIGNED)` | 否 |

### 2.5 P95 是六个时长桶的边界，不是测出来的毫秒

聚合表把每小时的调用按 `duration_ms` 落进六个半开区间桶：`le_100ms` ≤100、`le_500ms` (100,500]、`le_2s` (500,2000]、`le_10s` (2000,10000]、`le_30s` (10000,30000]、`gt_30s` >30000（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:49-54`，边界不闭合的理由写在 `:5-9`）。P95 就是把这些桶累加到 `ceil(calls × 0.95)` 时落在哪个桶，答出「比较符 + 桶上界」，最后一个开区间答 `>` 30000（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:246-259`，桶序 `:479-487`；零调用答 `<=` 0，`:251`）。**agent / session 两个维度没有桶可答**，行上的 `p95Operator` 恒为 `"<="`、`p95Ms` 是该主体实际测得的最长一次（`:204-212`、`:237-238`，DTO 文档同一句话在 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:52-55`）——这是「看起来像分布、其实是一个观测值」，iOS 保留运算符原样渲染（§5.2）。

### 2.6 每一条时间戳都是服务格式化过的字符串

`lastSeenAt`、`timePoint`、`startTime`、`ts` 都由服务用 `yyyy-MM-dd HH:mm:ss` 格式化后才进响应，而不是 `LocalDateTime`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:388-396`、格式化器 `:455`）。理由写在 DTO 头上：Jackson 序列化 `java.time` 走 ISO、不吃 `spring.jackson.date-format`，typed 字段会和这一族里其他所有时间标签形状不同（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:13-16`）。聚合路径的 `lastSeenAt` 是 `MAX(s.stat_hour)`，分钟秒恒为 0（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:142-145`）；明细路径取分组内的最大时间列与单行的时间列（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:101`、`:153`），而这一列在表里是 `datetime(3)`，格式化时把毫秒丢掉（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:391-395`，列定义 `harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql:26`）。**这与技能监控那一族的 ISO 带 `T` 形状不同**，两边的解析器不可互换，见 §3.6 与 `harnax-ios/specs/09-skill-usage.md` §3。

### 2.7 聚合表怎么来的、留多久

读侧的形状由两张表的来历决定，而 V5 是那一刀。V3 建表时聚合按**天**存：`stat_date`（`harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql:37`）、唯一键 `uk_tool_invocation_stats_day`（`:56`）、表注释「Daily rollup … retained permanently」（`:58`）。V5 把粒度折到**小时**（`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql:1-2`）：先整表 `DELETE FROM tool_invocation_stats`（`:13`），再 `stat_date` → `stat_hour datetime`（`:18`）、唯一键换成 `uk_tool_invocation_stats_hour (stat_hour,tenant_id,kind,subject_id,tool_name)`（`:19`）、顺带补 `(tenant_id,stat_hour)` 的普通索引（`:20`）。天行不带过来的理由写在 `:4-8`：把一天的数当成某天的 00:00 读回，就等于让一个小时持有一整天的计数，而 `selectUnrolledHours` 会把它认作已折叠、`deleteRolledOut` 随即释放它背后的明细行；丢的只是聚合，明细仍在保留窗口内，下一轮 rollup 会重折（`:6-8`）。表注释最终落在「rows are kept permanently」（`:24`）——**卡片能答一年、明细不能**这件事的根就在这里。`:10-12` 还写明只存一种粒度：日/周/月视图是对小时行求和，不是另外一批行。

`subject_id` 用 0 而不是 NULL（`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql:21`）正是「builtin / shell / framework 行不可下钻」的源头（§2.4），`tool_name` 由每个 kind 都携带（`:22`）则是 tool 维度按名字分行、而 mcp / cli 维度把它们折成一行的源头（`:14-16` 的解释与 `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:168-169` 相对）。

折叠真正 GROUP BY 的是：`(l.tenant_id, l.kind, CASE mcp→COALESCE(mcp_id,0) / cli→COALESCE(cli_id,0) / ELSE 0, l.tool_name)`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:59-65`），SELECT 侧用的是同一个 CASE 表达式而不是聚合值（`:36-40`，理由写在 `:17-18`），小时边界靠 `ts >= #{statHour} AND ts < DATE_ADD(..., INTERVAL 1 HOUR)` 而不是对列套 `DATE_FORMAT`（`:56-57`，不可走索引的理由 `:19-21`），四个终态与六个桶全在同一个 SELECT 里（`:43-46`、`:49-54`），`ON DUPLICATE KEY UPDATE` 是整行重算而非增量（`:22-23`，列表从 `:66` 起）。

跑它的是 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt`：cron 每小时 :05（`:49`），每次都强制重折「进行中的小时 + 上一小时」再加待折集合（`:75-86`，两端理由在 `:77-82`），随后释放保留窗口之外、且所属小时已折叠的明细（`:95-96`）。窗口来自 `harnax.metrics.retention-days`，默认 90 天（`harnax-admin/src/main/resources/application.yml:177`），进来先钳进 1..3650（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt:107`，常量 `:124`、`:127`），整条链路还有一个 kill switch `rollup-enabled`（`:28`）。iOS 能问出的最宽窗口是 8760 小时（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:461`），只有聚合表活得过它；`invocations` 的路由文档自己就写着「inside the retention window only」（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:82`），但**没有任何路由答得出这个窗口是多少**，屏上只能靠一句「明细只在保留窗口内」（§5.5）提示读者，见 §8。

顺手记一处漂移：`harnax-admin/src/main/resources/application.yml:174-176` 的注释还在讲 "daily aggregate" 与 "days are rolled up"，而 V5 之后折叠单位是小时（`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql:1`）、cron 也是小时（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt:49`）。这里只记录，不动那个文件。

## 3. 数据模型

### 3.1 `CallMetricsSummary`（`Sources/HarnaxCore/Contract/ToolMetrics.swift:13-33` ↔ `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:60-75`）

| 字段 | 线上形状 | iOS 类型 | 备注 |
|---|---|---|---|
| `from` / `to` | `yyyy-MM-dd HH:mm:ss`，必带（缺省空串） | `String` | 服务端收口后的区间，页脚印这两个而不是自己问的那两个（`:14-17`） |
| `groupBy` | string，缺省 `tool` | `String` + `group` 计算属性（`:32`） | **屏的标签读这里**：不认识的维度回落 `tool` 而不失败（`:18-21`） |
| `totalCalls` / `totalSuccesses` | 必带整数 | `Int64` | 来自一条无 GROUP BY 的窗口聚合，不是行求和（§2.4） |
| `successRate` | 0..1 的小数 | `Double` | 服务端除好，格式化归屏（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:403-406`） |
| `failingCalls` | 必带整数 | `Int64` | errors + denials + interruptions 三列相加，不是 calls − successes（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:63-65`） |
| `p95Operator` / `p95Ms` | string + 整数 | `String` / `Int64` | 两半必须成对渲染，见 §5.2 |
| `rows` | 数组，缺省空 | `[CallMetricsRow]` | |

### 3.2 `CallMetricsRow`（`Sources/HarnaxCore/Contract/ToolMetrics.swift:36-74` ↔ `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:18-58`）

| 字段 | 线上形状 | iOS 类型 | 备注 |
|---|---|---|---|
| `kind` | string，缺省空串 | `String` | agent / session 维度上**就是空串**，因为一行跨了所有来源（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:226-241`）；不用枚举，不认识的来源也要能渲染 |
| `subjectKey` | string，必带 | `String` | tool 维度是工具名、session 维度是会话 id、mcp / cli / agent 维度是 id 的文本形（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:21-25`） |
| `subjectId` | 可缺 | `Int64?` | **唯一可以缺席的 id**，也是下钻谓词的唯一来源（`:43-45`）；聚合里代表 builtin 的 0 在出响应前就被折成缺席（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:380`） |
| `toolName` | string，必带 | `String` | mcp / cli / agent / session 维度上为空串（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:122-125`） |
| `subjectName` | string，必带 | `String` | 服务端已经回落成 `subjectKey`（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:128-132`），两个都空才轮到屏说「未命名」 |
| `parentName` | string，必带 | `String` | 只有 kind 为 mcp / cli 的行带拥有者（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:136-140`） |
| `calls` / `successes` / `errors` / `denials` / `interruptions` | 必带整数 | `Int64` ×5 | |
| `successRate` / `avgDurationMs` | 小数 / 整数 | `Double` / `Int64` | 均值是 `sum / calls` 的整数下取整，聚合表只支持这个（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:409-412`） |
| `p95Operator` / `p95Ms` | string / 整数 | `String` / `Int64` | 聚合维度给桶边界，明细维度给最长一次（§2.5） |
| `lastSeenAt` | 可缺的 `yyyy-MM-dd HH:mm:ss` | `String?` | 精度随来源表变，见 §3.6 |

派生三件：`id = "\(kind)-\(subjectKey)"`（`:69`）、`failingCalls = errors + denials + interruptions`（`:70`）、`isDrillable = subjectId != nil`（`:73`）。`id` 会撞——同名工具在两个维度下可以重名——所以两屏的 `ForEach` 都用下标做身份（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:390-395`、`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:96-98`），控制台同理拒绝用行身份 key 它的表（`harnax-webui/src/pages/call-metrics/index.tsx:488`）。

### 3.3 趋势与明细两种行

`CallMetricsTimeSeries`（`:100-106` ↔ `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:92-103`）：`from` / `to` / `granularity` / `points`，`granularity` 的线上缺省是 `"day"`（`:101`）而服务端总会写它自己折叠出的那个。`CallMetricsPoint`（`:77-93` ↔ `:77-90`）十个字段里只有 `dimensionId` 可缺，`dimensionName` 在 `kind = cli` 时是命中的命令名而不是工具名（`:82-83`）。

`CallInvocationRow`（`:112-140` ↔ `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:105-126`）16 个字段，可缺的七个：`agentId`（团队 lead 没有 agent 行，`harnax-admin/src/main/resources/db/migration/V3__tool_invocation_metrics.sql:12`）、`sessionName`（id 后头没有会话行）、`userId`、`mcpId`、`cliId`、`errorMessage`、`argsJson` / `resultExcerpt`（采集关掉的部署上整键不出现，`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:118-121`）。`sessionLabel`（`:136-139`）编码「标题优先、id 兜底」，与控制台同一读法（`harnax-webui/src/pages/call-metrics/index.tsx:329`）。

### 3.4 四个线上枚举，case 数是量出来的

| iOS 类型 | case 数 | 值 | 回落到 |
|---|---|---|---|
| `CallMetricsGroup`（`:147-163`） | **5** | `tool` `mcp` `cli` `agent` `session` | `init(wire:)` 不认识即 `.tool`（`:162`），与服务端 `dimension()` 同形 |
| `CallMetricsKind`（`:169-178`） | **5** | `builtin` `mcp` `cli` `shell` `framework` | 无回落：`CallMetricsLabels.kind(_:)` 认不出的来源原样印那个词（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:117-121`） |
| `CallInvocationOutcome`（`:181-191`） | **4** | `SUCCESS` `ERROR` `DENIED` `INTERRUPTED` | `init?(wire:)` 可失败，第五个状态（如夹具里的 `TIMEOUT`）不发明（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:125-128`） |
| `CallMetricsGranularity`（`:197-206`） | **5** | `auto` `hour` `day` `week` `month` | `auto` 只在请求侧存在，服务端只认后四个（§2.3）；回读不认识的值保留原文 |

`readsAggregate`（`:158`）是这三个类型身上最重要的一条判断：tool / mcp / cli 为真。控制台的同一判断写在 `harnax-webui/src/pages/call-metrics/dimensions.ts:9` 与 `:18-20`，两处必须同时改，否则表上方的提示与「最近一次」列的精度会分开漂。

### 3.5 `CallMetricsWindow`（`Sources/HarnaxCore/Contract/ToolMetrics.swift:214-238`）

`days` 在 init 处夹下界（`:217`），`start` 用设备历法把「含今天往回 `days − 1` 天」写成 `yyyy-MM-dd`（`:228-237`）。四个静态档位 + 三档预设（`:219-225`）：`today` / `last7Days` / `last30Days` / `last90Days`，而 `presets` 只列后三个——`today` 在屏上不可达，`CallMetricsWindows.default` 是 `.last30Days`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:160`）。档位名复用 token 屏的 `monitor.range.*`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:164-171`）。注意这是 `Equatable, Sendable` 而**不是 `Hashable`**，所以下钻值 `CallMetricsSubjectRef` 的 `Hashable` 是手写的：`==` 比八个字段，`hash` 只取窗口的 `days`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:58-78`），因为查询只可能依赖区间长度而不会依赖它的日期实例。

### 3.6 两种时间戳不许混，且这一族不靠解析

这一族的每一条时刻都是服务格式化好的 `yyyy-MM-dd HH:mm:ss`（§2.6），iOS 一侧**从不把它当日期读**，只切字符：

- `CallMetricsStamp`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:65-94`）管 `lastSeenAt` / `ts` / `startTime`。`isClock` 要求 `chars.count >= 16` 且 4、7 位是 `-`、**第 10 位是空格**、13 位是 `:`（`:89-93`）；满足才按精度切：聚合维度切到 16 字符（`yyyy-MM-dd HH:mm`）、明细维度切到 19（`:70-75`），与控制台 `harnax-webui/src/pages/call-metrics/lastSeen.ts:13` 同。**切不动的串原样返回**（`:84`）：一个 `not-a-date` 就照原样出现在屏上，不猜日期。**缺席或全空白的串答 `monitor.calls.stamp.none` 这句话**（`:81-82`），英文是 "No call in this window"（`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1638`）——这里与控制台的破折号（`harnax-webui/src/pages/call-metrics/lastSeen.ts:10`）有意不同：标题写着「最近一次」而格子是破折号，读起来像数据掉了。
- `CallMetricsAxis`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:286-298`）管趋势横轴，同样要求第 10 位是空格，hour 桶出 `MM-dd HH:00`、其余出 `MM-dd`，**不认识的粒度或不成形的串整条返回**（`:289-291`）。
- 技能监控那一族（ISO 带 `T`）由另一个类型归一：`hxSpaceStamp` 只把第一个 `T` 换成空格，缺失则屏上画破折号（`Sources/HarnaxFeatures/Support/RowMeta.swift:34-38`，判据在 `harnax-ios/specs/09-skill-usage.md` §3）。**把两边的解析器互换是最容易弄坏这两屏的做法**：这一族的串本来就没有 `T`，那里的串本来带 `T`。
- 时区：文本切片因此不做任何本地化换算，机子换了时区显示的仍是服务端写下的那几个字符（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:58-64`）；控制台用 dayjs 解析再格式化，会把同一刻搬到浏览器时区去——这是两屏共同的、有意的偏差。

## 4. 门面与传输

- **门面**：`Sources/HarnaxCore/Contract/ToolMetrics.swift:297-315` 声明 `protocol ToolMetricsReading: Sendable`，三个方法 `summary(window:group:kind:)`、`timeSeries(window:kind:subjectId:granularity:)`、`invocations(_:)`，返回 `Result<…, APIError>`，第三个的载荷是 `Page<CallInvocationRow>`（`Sources/HarnaxCore/Contract/Page.swift:9`）。整个协议只有读，没有写。
- **路由拼装**：`Sources/HarnaxAPI/ToolMetricsEndpoint.swift` 是 internal enum，`root = "/api/admin/tool-metrics"`（`:16`）。三条共用一个窗口助手：只发 `start`，取 `CallMetricsWindow(days:).start`，**永不发 `end`**（`:19-21`），也就是把「到当前小时」这件事交回服务端（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:43-44`）。`kind` 为 nil 时整参数消失（`:23-26`）。
  - `summary`：`start` →（可选 `kind`）→ `groupBy`，**`groupBy` 永远发**，不留给服务端缺省（`:30-34`）。
  - `timeSeries`：`start` →（可选 `kind`）→（可选 `subjectId`）→ `granularity`，`granularity` 永远发，且屏发的是 `auto`（`:45-53`）。客户端不重算跨度，回读 `granularity`（`:50-52` 注释即此）。
  - `invocations`：`start` →（可选 `kind`）→（可选 `toolName` / `mcpId` / `cliId` / `agentId` / `sessionId` / `outcome`）→ `pageNum` → `pageSize`，顺序与服务端形参声明一致（`:58-82` ↔ `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:84-106`）。两个文本谓词过 `hxPresented`（`Sources/HarnaxCore/Contract/AgentBindings.swift:5-9`），所以 `"   "` 与 `""` 都不成谓词，而 **id 为 0 仍是一个谓词**（缺席才表示不过滤）。`pageSize` 在发之前夹到 1..200（`:81`），与服务端的 `coerceIn(1, MAX_PAGE_SIZE)`（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:139`）叠成两道闸。
- **传输**：`Sources/HarnaxAPI/ToolMetricsClient.swift:8` / `:17` / `:34` 是 `extension AdminClient: ToolMetricsReading` 的三个方法，各自 `await client.send(...)`，无额外装配、无重试、无缓存。
- **依赖槽**：`Sources/HarnaxFeatures/Support/HarnaxDependencies.swift:42` 是 `public let toolMetrics: (any ToolMetricsReading)?`，init 形参 `:100`，赋值 `:135`，`live()` 里绑同一个 `admin` 实例 `:199`。可空的理由与技能监控同一条：admin 面上没装就让行消失，不给一个注定失败的按钮（`Sources/HarnaxFeatures/Me/MeView.swift:98-104`）。
- **服务端那条 kind 收口，客户端必须镜像**：`mcp` / `cli` 两个维度自带来源桶，调用方的 `kind` 对行集和窗口卡片都不起作用（`harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:158-167`、`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:55-60`）。iOS 的 `effectiveKind`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:168-174`）就按维度把 `kind` 换成 `.mcp` / `.cli`，其余三个维度透传；`showsKindNote`（`:178`）同判，用于把来源控件换成一句说明（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:213-234`）。**发出去的请求与回来的响应因此始终描述同一个集合**，否则来源臂会在 mcp 维度下显示「framework」而表里全是服务器。
- **下钻谓词不在 Core**：`CallMetricsSubjectRef` 与它的 `invocationQuery(num:size:outcome:)` 住在视图文件 `Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:18-102`，因为它是「一个 push 值」而不是一个契约形状。八个字段的含义与逐项谓词见 §5.4。

## 5. 界面

### 5.1 主屏壳与相态

`CallMetricsView` 持 `@StateObject vm`，同时留下 `catalog` 给 push 出去的第二屏（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:116-122`），因此两屏读的是同一个门面实例。`.harnaxScreen()` + 标题 `monitor.calls.title` + 工具栏 `arrow.clockwise`（`:124-137`）；`.task` 只在 `.loading` 时读一次（`:138-140`），`.refreshable` 走同一个 `vm.refresh()`（`:141`）；`navigationDestination(for: CallMetricsSubjectRef.self)` 是唯一的 push（`:142-144`）。

四个页面相态 `Phase`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:20-25`）配两条独立的块状态 `BlockState`（`:37-42`），块名就是两条路由 `.summary` / `.trend`（`:28-35`）。判据在 `updatePhase`（`:356-372`），**一句话：一条路由的失联不是整页的失联**——

| summary | trend | 页面 |
|---|---|---|
| loading | 任意 | `.loading` |
| failed | loaded | `.content`（失败由那一块自己说） |
| failed | 非 loaded | `.failed(该块的文案)` |
| loaded | loading / loaded 有内容 | `.content` |
| loaded | loaded 且两块都空 | `.empty`，文案 `monitor.calls.empty` |
| loaded | failed 且屏上无物 | `.failed(trend 的文案)` |

`nothingOnScreen` 是「行集为空 **且** 桶为空」两只手都空（`:375-377`）：窗口答了却没人被调用，与控制台同一句话（`harnax-webui/src/pages/call-metrics/index.tsx:495`）。趋势还有它自己的一句 `monitor.calls.trend.empty`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:231`）：零填充的响应里一个主体都没出现时，网格根本不存在（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:101-113`），这和「没有调用」不是同一件事。

### 5.2 四个数字

四张卡**只从 summary 的窗口字段来，一次也不从行里算**（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:326-334`）：`calls` / `rate` / `p95` / `failures`，顺序即控制台的 KPI 排（`harnax-webui/src/pages/call-metrics/index.tsx:150-174`）。理由在服务端形状里：卡片来自一条无 GROUP BY 的窗口聚合（§2.4），对可见行求平均会得到另一个数（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:300-306`）。

格式化全在 `CallMetricsFigures`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:11-56`），四条都与控制台逐字对齐：计数**不缩写**、原样打印（`:17`，控制台的 `calls` 列同理 `harnax-webui/src/pages/call-metrics/index.tsx:246-262`）；成功率一位小数加 `%`（`:23-25` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:39`）；时长一律 `N ms`（`:28`）；**P95 是比较符打头**（`:40-43`）——`<=2000 ms` 说的是「95 分位落在 ≤2s 这一桶」，`>30000 ms` 说的是开区间，都不是测出来的毫秒，丢掉符号就成了服务端从没观测过的数（`:30-39`，桶的来源见 §2.5）。运算符既不是 `<=` 也不是 `>`（夹具里就有一行给了空串，`App/HarnaxDebugScreens.swift:2783`）时**原样照印**，缺符号才退化成纯时长（`:41`）。数字格式化钉死 `en_US_POSIX`、不分组（`:47-55`），所以判据不会在一台机器上过、另一台上挂。

三张卡带控制台那句 tooltip 落地成的正文（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:311-320` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:160,166,172`）；`calls` 卡没有提示，因为控制台的它也没有。summary 这块失联时，四张卡整块换成「标题 + 错误横幅 + 只重试这一块」（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:272-285`、`:407-427`）。

**汇总与行集的自洽判据**（拿调试台夹具算，`App/HarnaxDebugScreens.swift:2776-2785`）：六行 calls 之和 412+96+58+23+11+4 = **604** = `totalCalls`；successes 之和 401+88+44+3+11+4 = **551** = `totalSuccesses`；`errors+denials+interruptions` 逐行为 11 / 8 / 14 / 20 / 0 / 0，合计 **53** = `failingCalls`；且 551 + 53 = 604，四终态在本窗口内确实做完备划分；回显 `successRate` 0.9123 即 551/604。**这条等式不是服务端保证的**：agent 维度因为 `HAVING subjectKey IS NOT NULL` 会把无 agent 可归的调用留在卡片里而不留在行里（`harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml:116-127`），mcp / cli 维度则因为卡片改用维度自带的桶而必然自洽（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:55-60`）。屏上因此从不把行求和显示成卡片数。

### 5.3 趋势块

块标题 `monitor.calls.trend.title`，桶数为空时只出一句 `monitor.calls.trend.empty`，有桶才给读法切换（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:289-325`）。读法两档 `TrendForm`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:50-68`）**复用 token 屏的图表/数字开关**和它的两个既有臂名，而不是发明第二种图表语言：控制台只有线（`harnax-webui/src/pages/call-metrics/index.tsx:430-434`），因为它有宽度；三条线在 90 天窗口上的 handset 上不可读。切读法不重发请求——两档都从已经在手的桶里派生（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:136-138` 注释即此）。

折叠规则在 `CallMetricsTrend.buckets(of:)`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:252-277`）：响应的粒度是「桶 × kind × 主体 × 工具」，所以 30 个工具的 10 月 5 日是 30 个点共享一个 `timePoint`，按桶求和是唯一能让三条线的名字说到它们所指的读法（同 `harnax-webui/src/pages/call-metrics/index.tsx:180-194`）。**顺序取响应顺序**、首次见某 `timePoint` 定其位（`:243-251`），这里既不加桶也不减桶，因为服务端已经零填充过（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:110-133`）；唯一被丢的点是 `timePoint` 为空的点——它无处可放，画在位置 0 会造出一个服务端没做过的尖峰（`:248-252`）。failures 那条线 = errors + denials + interruptions（`:264` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:186`）。

图为 Swift Charts 双线标记 + 点标记，x 值直接用服务端的桶文本（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:469-494`）：它无时区且 `yyyy-MM-dd HH:mm:ss` 自序，所以不需要本地时间往返（`:462-468`）。横轴标签来自 `CallMetricsAxis`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:286-298`），找不到桶时回落整条 `timePoint`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:496-506`）。图整块 `.accessibilityHidden(true)`（`:518`），读数字的那条路是 `CallMetricsTrendList`，每格 `accessibilityElement(children: .combine)`（`:525-558`）；**服务端答的 0 就印 `0`**，不留空格（`:542-544`）。三条线的图例是正文不是开关（`:327-340`）：这屏不手动收窄任何序列，关掉一条会让卡片上的和与图不符。桶大小那句 `monitor.calls.bucket.note` 只在 `answeredGranularity` 能解析成标签时出现（`:345-353`）——`auto` 从不走到这里，屏报的是服务端折叠出的那个桶而不是自己许的愿（§2.3）。

### 5.4 主体卡与下钻门

一主一卡：标题（`subjectName` → `subjectKey` → `monitor.row.unnamed` 三级回落，`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:395`）、`parentName` 次行**只在 tool 维度**（`:396`，因为它是「哪个服务器答的」，见 `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml:133-140`）、最多两枚胶囊（来源一枚、失败数一枚，且失败数为 0 就不出现，`:398-412`）、四个数字的 2×2 网格（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:622-633`）、最末一行用话说成败（`:638-649`，有失败时整行 danger）。控制台的六列（`harnax-webui/src/pages/call-metrics/index.tsx:218-291`）就是按这个次序折成一张卡的。

来源胶囊在 agent / session 行上**不会出现**，因为那两行的 `kind` 是空串，`CallMetricsLabels.kind(_:)` 对空串答 nil（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:117-121`）——一行跨了所有来源，就不该被贴上一个来源（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:204-212`）。

**门与 push 值**：`CallMetricsSubjectCard` 拿到的 `ref` 为 nil 时，卡片形状完全不变、只是没有链接和 chevron（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:566-605`）——承诺打开却服务不了的控制比没有控制更坏（`:561-565`）。`ref(at:)`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:197-211`）只用**下标**不用行 id（`:190-196`，理由见 §3.2），逐字段是：

| 字段 | 取值 | 为什么是它 |
|---|---|---|
| `subjectId` | `row.subjectId` | 谓词原料；先过 `isDrillable` 这一关（`:200`） |
| `subjectKey` | `row.subjectKey` | session 维度没有 id，谓词只能拿键（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:22-23`） |
| `title` | `subjectName` 空则 `subjectKey` | 第二屏的标题；服务端已回落过一次，这里只兜住两个都空 |
| `toolName` | `row.toolName` | 只有 tool 维度会把它发出去 |
| `kind` | `row.kind` 原文 | 认识的桶才当枚举用（`:87-101`） |
| `group` | `answeredGroup` | **响应回显的维度**，不是屏自己许的（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:184`） |
| `filterKind` | `kind`（屏的来源臂，可为 nil） | 行自己没有桶时的兜底，即 `effectiveKind` 之前的原值（`:115`） |
| `window` | `window` | 抽屉不得活过它所属的查询（`harnax-webui/src/pages/call-metrics/index.tsx:105-112`） |

谓词逐列与控制台同形（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:87-101` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:123-136`）：`toolName` 只在 `group == .tool` 时给；`mcpId` / `cliId` 看**行自己的 kind 文本**是不是 `mcp` / `cli`；`agentId` 看 `group == .agent`；`sessionId` 看 `group == .session` 并取 `subjectKey`；`kind` 取 `CallMetricsKind(rawValue: row.kind) ?? filterKind`。两处措辞值得记下：`mcp` / `cli` 判定用行 kind 而不是维度（维度是 mcp 时行的 kind 也一定是 mcp，但 tool 维度下的 mcp 行同样需要这条谓词）；`filterKind` 用的是**用户挑的那个臂**而不是 `effectiveKind`，否则 mcp 维度下钻会带上一个行集已经隐含的谓词。

**与控制台的三处真实差异**（都是 shipped 行为，不做修饰）：① 控制台把下钻挂在 calls 列上、对**任何**行都开抽屉（`harnax-webui/src/pages/call-metrics/index.tsx:252-262`），iOS 的门是 `subjectId`，于是 builtin / shell / framework 的 tool 行与全部 session 行不给 push（§2.4 表）；② 因此 iOS 的 `sessionId` 那条分支今天不可达（session 行永远过不了 `isDrillable`），控制台同一条分支可达（`harnax-webui/src/pages/call-metrics/index.tsx:133`）；③ 行 kind 是**未知词**时，控制台把它原样发出去（`kind: row.kind || kind`，`harnax-webui/src/pages/call-metrics/index.tsx:128`），iOS 的 `CallMetricsKind(rawValue:)` 认不出就回落到屏上的臂（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:91`）——后者不会拿着一个服务端零行的词去问。

### 5.5 第二屏：一次调用的分页清单

`CallInvocationsView` 顶部是五臂分段（nil + 四个终态，`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:43-64`）；`nil` 臂是「不发 `outcome` 参数」而不是第五个码（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:101`）。标题用 push 值里的 `title`（`:28`）。表头三句在 `CallInvocationsViewModel` 里拼（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:75-99`）：哪个主体哪个区间（`monitor.calls.detail.subject`，两个参数都是服务端或行自己的话）、这些调用被按哪个来源筛（`monitor.calls.detail.origin`，`nil` 时整句不出现）、共答了多少条（`hxCount("monitor.calls.detail.total", total)`）。空句是 `monitor.calls.detail.empty`，**它和主屏那句不同**：聚合维度的计数比明细行活得更久，趋势里有柱子而明细列表翻到空是合法状态（`Sources/HarnaxCore/Contract/ToolMetrics.swift:108-111`，路由文档同调 `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:79-83`）。`monitor.calls.retention.note` 只在**聚合维度**的行下面出现（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:84`、`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:107-112`）——主屏那条 `monitor.calls.detail.note` 讲的是反过来的那一对（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:188`）。

分页：`pageSize` 默认 20，与控制台一致（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:67` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:135`）。刷新**永远回第一页并整块替换**（`:121-133`），因为失败的请求否则会把上一个主体的调用挂在下一个主体的标题下（`harnax-webui/src/pages/call-metrics/index.tsx:117-120`）；末行 `onAppear` 触发 `loadMore()`（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:98-103`）；追加页在**滚动时那一刻的查询身份**下发，切换 outcome 之后回来的尾巴被丢弃而不是接到错的列表上（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:156-175`）；`hasMore` 比较的是「服务端交过的行数」与 total，而不是屏上的行数（`Sources/HarnaxCore/Contract/PagedState.swift:15-31`），因为翻页会漂行、去重会丢行。换臂只清页码不清臂本身（`:185-192`）。

一条记录一张卡：标题行是「状态点 + 时刻 + 状态胶囊 + 耗时」（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:168-180`），时刻取 `ts ?? startTime` 再切 19 字符（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:454`、`:75`）；四条 byline 各带自己的词，裸 id 或裸名字前面没标题就认不出是哪一列（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:184-203`）；失败原因在载荷**之上**（`:154-159`），因为它是关于状态的事实、与采集开关无关（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:470-473`）。展开器只在两个载荷列至少有一个出现过时才给，否则明说「这台部署把采集关了」（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:205-236`）——**这是本屏有意不与控制台相同的一处**：抽屉在三个可选列都空时收起展开器（`harnax-webui/src/pages/call-metrics/index.tsx:534-559`），于是「采集关着」和「这次什么都没带」看起来一样（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:426-431`）。参数那条等宽、可选中、可复制，结果摘录不等宽（`:474-491` ↔ `harnax-webui/src/pages/call-metrics/index.tsx:543-556`）。

### 5.6 三个筛选控件

维度在最前、且是分段而不是菜单，因为它改变的是**每行意味着什么**（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:181-210`；控制台的维度是一个 Select，且每个 tab 只给自己那三个维度，`harnax-webui/src/pages/call-metrics/index.tsx:459-473` 与 `harnax-webui/src/pages/call-metrics/dimensions.ts:12-16`）。区间是菜单 Picker，三项来自契约自己的 `CallMetricsWindows.presets`（`:193-206`）。来源臂把 `nil` 排在最前（`:236-239`），所以「所有来源」是一个臂而不是一种缺席——控制台的 tool tab 只提供 `builtin` / `shell` / `framework` 三个值、没有「全部」（`harnax-webui/src/pages/call-metrics/index.tsx:30`、`:453-456`），iOS 从「全部」开屏，因为没有任何操作者要求过先藏掉三个来源桶（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:109-120`）。三个 binding 都走越界回落（`:241-265`、私有 `Array[safe:]` 在 `:664-668`）。

### 5.7 竞态纪律

主屏：**每块一个计数**（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:144-150`），发起前各自 +1，回来时 tag 不等于当前值就整块丢弃（`:280`、`:304`）。共享一个计数器会坏在只重试一块的时候——另一块在飞的请求会被误判为过期，然后永远转圈。`load(blocks:)` 先把这些块的旧数据清干净再发（`:245-263`），`answered` 与 `answeredGranularity` 也一起清空，否则页脚会描述一个已经不在屏上的窗口。两读用 `withTaskGroup` 并发（`:265-276`），落地次序谁先到谁画，`updatePhase` 只在两人都到位时才下 `.empty` 的判词。三个筛子的 `didSet` 各触发一次全量 `refresh()`（`:87-120`）：换维度时连趋势一起重发，因为「画在另一张主体表之上的线」是切换最不该留下的东西（`harnax-webui/src/pages/call-metrics/index.tsx:91-94`）。全模块无 `.animation`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:111-114`）。

第二屏：`refreshGeneration` + `isRefreshing` + `rerunRequested` 的合并刷新（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:108-119`），追加期间来了刷新就把追加记下来等刷新完补做（`:147-154`、`:177-181`）；失败分两路——屏上已有行时只留 `inlineError`，一行都没有时才 `.failed`（`:134-143`），且 `.failed` 前先看 `ErrorMessage.carriesNews`，同样的 503 不该把同一句话再砸一次。

## 6. 本地化

命名空间一条都不必新增：`monitor` 早在闸门表内（`Tests/HarnaxKitTests/LocalizationKeyTests.swift:10-16`），本域与 Token 监控共用它，两份目录各追加一块。en 的块是 `Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1614-1685`（66 条，`:1614-1615` 是块头两句说明），zh 是 `Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1615-1686`（同 66 条，`:1615-1616` 为块头）；两屏的行标题与副标题不在这个块里，它们与 Token 监控、技能监控的行文案排在「集群监控」那一段——en `Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1456-1457`、zh `Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1457-1458`，取用位是 `Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:24-38`。

**口径与数量**（脚本量的：`Sources/HarnaxFeatures/CallMetrics/` 五个文件加枢纽行 `Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift` 里所有点号字面量，再展开拼接族与复数族）：两屏加枢纽那一行一共问到 **84 条目录项**，全部在 en 与 zh 存在，缺失 0 条。拆账是 `monitor.calls.*` 单值 66 条 ＋ 复数族的两臂 ＋ 复用既有键 16 条：

- **直接写全的点分字面量 57 个**，其中 56 个就是目录键，第 57 个是复数基名 `monitor.calls.detail.total`（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:91`），目录里只有它的 `.one`/`.other` 两臂（en `Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1672-1673`、zh `Sources/HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings:1673-1674`，中文两值同文、英文单复数同词而 `%d` 两侧都在）。66 条单值 ＋ 这 2 臂正好把 `monitor.calls.*` 一族 68 行用满，没有留一条死键。
- **六条拼接族 26 臂**，键由 `"…\(rawValue)"` 拼出、从不以字面量出现在 Swift 里：`monitor.calls.group.*` 五臂（`Sources/HarnaxCore/Contract/ToolMetrics.swift:155`）、`monitor.calls.kind.*` 五臂（`:177`）、`monitor.calls.outcome.*` 四臂（`:188`）、`monitor.calls.granularity.*` 五臂（`:205`）、`monitor.calls.card.*` 四臂与 `monitor.calls.series.*` 三臂（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:310`、`:215`）。目录里逐臂都在（en `Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:1640-1644`、`:1648-1652`、`:1654-1657`、`:1659-1663`、`:1617-1620`、`:1628-1630`）。
- **复用既有键 16 条**：本域两屏用到 12 条——`monitor.filter.range`（`Sources/HarnaxKit/Resources/en.lproj/Localizable.strings:935`）、窗口三档 `monitor.range.sevenDays`/`.thirtyDays`/`.ninetyDays`（`:938-940`，取用位 `Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:164-171`）、`monitor.axis.time`（`:977`）、`monitor.row.unnamed`（`:978`）、趋势两档 `monitor.trend.form.chart`/`.list`（`:967-968`，`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:56-61`，与 Token 监控共用同一对词）、`state.action.reload`（`:981`）、`state.error.title`（`:12`）、`state.filter.all`（`:14`）、`common.retry`（`:2`）；枢纽那一行另外 4 条属于旁域（`monitor.title`、`system.monitor.subtitle`、`skill.usage.title`、`monitor.skillUsage.subtitle`）。

**这条族的覆盖边界**：`testEveryKeyUsedInSourceExistsInBothCatalogs`（`Tests/HarnaxKitTests/LocalizationKeyTests.swift:99-110`）只认引号里写全的点分字面量，26 条拼接臂不受检；`hxCount` 的两个复数臂受检（`:189-197`），所以 `monitor.calls.detail.total` 那一句在检。两侧目录的键集另有 `:28` 一条用例锁死，因此「en 有 zh 没有」这一类根本进不来，族里剩下的风险只有一个——服务端真给第六个 origin token 或第五个 outcome 时拼不出键，界面把 token 原样印成文字（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:117-123`、`:125-129`），这一条是屏的既定处置而不是缺键。

**带参的目录值 15 条**：13 个单值键——`monitor.calls.bucket.note`、`monitor.calls.window.days`、`monitor.calls.window.asked`、`monitor.calls.window.note`、`monitor.calls.row.failed`、`monitor.calls.row.succeeded`、`monitor.calls.detail.subject`、`monitor.calls.detail.origin`、`monitor.calls.row.tool`、`monitor.calls.row.origin`、`monitor.calls.row.session`、`monitor.calls.row.agent`、`monitor.calls.row.failure`——再加复数族的两臂 `monitor.calls.detail.total.one` / `.other`。`testTemplatedCopyIsAlwaysCalledWithItsArguments`（`Tests/HarnaxKitTests/LocalizationKeyTests.swift:68-80`）不许任何模板句被裸调用，`CallMetricsWindows.title(_:)` 因此分了两臂：带 `%d` 的那一句传天数、`monitor.range.*` 那三句不传（`Sources/HarnaxFeatures/CallMetrics/CallMetricsPresentation.swift:173-176`）。

**图标名避开命名空间前缀**：`monitor`、`state`、`common`、`skill`、`token` 都在闸门表里，屏上用到的 `arrow.clockwise`（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:133`）、`exclamationmark.triangle`（`:418`、`Sources/HarnaxFeatures/CallMetrics/CallInvocationsView.swift:91`）与枢纽三行的 `chart.line.uptrend.xyaxis` / `books.vertical` / `wrench.and.screwdriver`（`Sources/HarnaxFeatures/SystemDomain/MonitorRoute.swift:42-46`）没有一枚以表内词开头。

## 7. 测试矩阵

- `Tests/HarnaxAPITests/CallMetricsWireTests.swift`：**16 个 `func test`**（实测计数，290 行）。发送侧 13 条——三条读只发下界（`:26`）、窗口按整天往回取（`:40`）、空窗口是 1 天不是 0（`:53`）、summary 点名自己的维度且不带上没选的来源（`:63`）、trend 恒发 `auto` 且只在点了对象时收窄（`:73`）、明细把每个选中的筛选按声明顺序发出（`:86`）、空白文本筛掉而 0 仍然算一个 id（`:113`）、页大小夹在路由接受的区间里（`:127`）、下一页带着第一页的全部筛选（`:136`）、五个维度按读哪张表分两档（`:154`）、线上来了不认识的维度回落默认（`:160`）、筛选词用服务端自己的词（`:168`）、每条路由都是鉴权 admin 读且不带租户参数（`:177`）。解码侧 3 条——只有带 id 的行可下钻（`:195`）、trend 带着它自己的档位（`:231`）、明细行留住它发生在那一会话并在抓取关闭时丢下载荷（`:253`）。
- `Tests/HarnaxFeaturesTests/CallMetricsViewModelTests.swift`：**23 个 `func test`**（实测计数，677 行）。请求预算：进屏恰好发那两次读（`:95`）、每个筛选变化两读各重来一次（`:126`）、把同一个值再写一遍什么都不发（`:162`）、趋势两档切换不花请求（`:639`）。竞态：旧窗口那个 trend 晚到只能落空（`:186`）、一块重读只清那块（`:222`）、只重读一块不动另一块的答复（`:249`）。三态判定：summary 失败但 trend 有数仍是内容态（`:292`）、summary 失败且没有别的答复才是整页失败（`:304`）、两次读都答了空才是空态（`:325`）、summary 已回而 trend 未回是内容态还不是空态（`:339`）、空表底下的 trend 失败按 trend 自己的话报错（`:362`）、有行时的 trend 失败仍是内容（`:372`）。来源：mcp/cli 两维钉住自己的来源（`:385`）、其余三维带上选中的来源（`:407`）。行与下钻：行按答复答的那个维度打标（`:443`）、认不出的 `groupBy` 回落 tool 而不失败（`:472`）、push 值带齐谓词要每个字段（`:487`）、没得下钻的行不给 push 值（`:520`）、push 值跟答复顺序而非行身份（`:537`）。其余：脚注印服务端答出的区间（`:565`）、四张卡就是 summary 自己那窗口的数（`:588`）、桶从答复折出且不重排（`:609`）。
- `Tests/HarnaxFeaturesTests/CallInvocationsViewModelTests.swift`：**21 个 `func test`**（实测计数，571 行）。进屏只发被 push 的那个谓词且不发别的（`:77`）、屏以哪个页大小打开线上就是哪个（`:103`）、行自己不带来源时借列表的筛选（`:118`）、每个维度按对象所属那一列收窄（`:136`）、换结果档作废它原先持有的页（`:167`）、写成屏幕上已有的结果档不发读（`:216`）。翻页竞态：尾巴页在筛选移动之后才答就丢掉（`:236`）、刷新期间请求的尾巴在刷新后补发（`:264`）、补发时新页已把列表到头就作罢（`:294`）、飞行中的第二次刷新排在第一次之后而不是并行（`:315`）。错误口径：有行时的刷新失败是列表上方一条横幅（`:353`）、第一次读失败就是屏的失败（`:366`）、被读者自己拆掉的读什么都不能说（`:380`）、尾巴页失败只说不碰已有行（`:400`）。表与信封：答复到列表之间不重排不丢行（`:420`）、计数与「还有更多」都来自信封（`:454`）、空页是这一次读自己的空（`:479`）、列表已没有更多处再要就什么都不发（`:494`）、尾巴在网上一段时间里滚两次只花一次读（`:502`）。页头：标题点名被 push 的对象与窗口（`:525`）、来源那句跟着谓词解析到的 kind（`:545`）。
- 夹具 `Tests/HarnaxFeaturesTests/CallMetricsFakes.swift`：**0 个 `func test`**（558 行，纯支撑）。它按 `Tests/HarnaxFeaturesTests/SkillUsageFakes.swift:22-39` 的排队纪律立身——没人排答复的请求以解码失败回、但仍在那条路由的日志里留痕，于是进屏多出第三次读会被数成计数不对而不是静默通过。开关与放行在 `:46`（`gate`）、`:50`（`gateTrend`）、`:114`（`release()`）、`:119`（`releaseTrend()`），`:144` 的锁不是装饰——`CallMetricsViewModel.load` 用 task group 把两条路由同时放出去（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:265-276`），两个任务同时写日志。五份 `stub`（`:199`、`:229`、`:343`、`:361`、`:433`）与 `extension Page where T == CallInvocationRow` 上的 `Page.stub`（`:531`、`:533`）都经解码器构造，测试因此造不出服务端不发的一列。
- 入口侧的三条在本域的闸门（`Tests/HarnaxFeaturesTests/SystemHomeTests.swift`，8 个 `func test` 里的三条）：「一行叫什么名字，就得是它打开那屏自己 `harnaxTitle` 用的键」把 `MonitorRoute.callMetrics.titleKey` 与 `Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift` 钉在同一条用例上（`:44`）；「三行监控自成一个块、顺序是控制台那套」锁死 `MonitorRoute.allCases == [.skillUsage, .callMetrics, .token]` 并挡住 Token 监控还以管理行出现（`:71`）；三行的标题与副标题在中英两侧都解得出（`:79`）。
- Core 层对本域没有用例：`Tests/HarnaxCoreTests/` 里 `ToolMetrics`、`CallMetrics`、`CallInvocation` 三个词零命中（实测计数 0）。`Sources/HarnaxCore/Contract/ToolMetrics.swift` 的解码语义只由第一份文件那 16 条间接覆盖；信封侧的翻页语义另有既有测试兜着（`Tests/HarnaxCoreTests/PagedStateTests.swift`）。
- 本域 60 条用例（16＋23＋21）与全量 2322 条同批跑过，0 失败；`swift build` 通过，调试台的 `xcrun -sdk macosx swiftc -typecheck` 一次通过。

## 8. 记账（本轮不做或做不了）

- **主表不分页**：summary 路由一次回全部行且没有分页信封（`Sources/HarnaxCore/Contract/ToolMetrics.swift:30` 的 `rows` 就是答复里的一个数组），控制台的 20 条一页是纯前端的（`harnax-webui/src/pages/call-metrics/index.tsx:497-499`）。iOS 一行一卡、全量列出，主体很多时会是一屏长列表——这是服务端没有分页读决定的，不是屏偷懒。
- **趋势图例不做开关**：三条线的名字只作说明（`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:327-340`）。隐藏一条会让上面的四张卡与图对不上：卡是 range-wide 的单次聚合（`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt:55-66`），不随前端选线而变，控制台也没有这个开关。
- **对象画像那一半不搬**：明细屏只搬控制台的记录抽屉。抽屉的另一个面板读的是旁域五份读（agent、cli、mcp、session、tool，`harnax-webui/src/pages/call-metrics/subjectProfile.ts:1-5`），而 `ToolMetricsReading` 只答三条调用读（`Sources/HarnaxCore/Contract/ToolMetrics.swift:297-315`）；那些事实要另一条域的门面才拿得到，本轮不为一屏去开第二条链。
- **`granularity` 不做控件**：屏恒发 `auto`、读回服务端折出的档位并只把它印成一句说明（`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:298-303`、`Sources/HarnaxFeatures/CallMetrics/CallMetricsView.swift:346-352`）。控制台同样不把档位开放给操作者——它 `useState` 存的是答复回显（`harnax-webui/src/pages/call-metrics/index.tsx:59`、`:89`），仅用来决定 X 轴取哪几个字符。
- **明细屏的翻页只向前**：`loadMore()` 追加下一页（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:147-154`），没有跳页与页码选择器；控制台的抽屉有（`harnax-webui/src/pages/call-metrics/index.tsx:527-533`）。换档时页集整块作废重来（`:42-50`），所以「跳到第 5 页」在这屏上没有对应的服务端语义之外的收益。
- **翻页与回显的四处窄缝本轮不补**，判据都是「只在双重竞态下可达，且没有一处能把错数据留在屏上」。其一：`reissueAppend()` 的守卫（`Sources/HarnaxFeatures/CallMetrics/CallInvocationsViewModel.swift:177-181`）在 `canLoadMore` 或 `isAppending` 不成立时直接 return，`appendRequested` 留着不清，于是「曾经要过尾巴」这个记忆能活到下一次刷新之后——但补发条件本身由新答复重定，最坏是多一次追加读，不是把页接到别的列表上。其二：追加失败只把文案落进 `inlineError`（`:171-173`），没走 `ErrorMessage.carriesNews`（`Sources/HarnaxFeatures/Support/ErrorMessage.swift:24-27`）那道「被取消的读保持安静」的闸；同仓库的 `Sources/HarnaxFeatures/MemoryDrafts/MemoryDraftListViewModel.swift:179-181` 在自己的追加路径上同样不带，这是既有口径而不是本域漏的一项。其三：`resetPages()` 作废页集时不动 `canLoadMore`（`:185-192`），那枚旗标要等下一次 `apply()`（`:200`）才被新答复重定，所以在作废与首答之间滚一下能过 `loadMore()` 的守卫（`:148`）；那一次读按代际放行、回来的页又会被随后的 `pages.replace(with:)` 整块换掉，形态上是浪费一次读。其四：主屏的行标签跟着答复答出的维度（`answeredGroup`，`Sources/HarnaxFeatures/CallMetrics/CallMetricsViewModel.swift:184`），而那句说明跟着屏上问出的维度（`showsDetailNote`，`:188`），两者只在服务端回声与问出的不一致时分叉，而那正是 `testAnUnrecognisedGroupByFallsBackToToolWithoutFailing`（`Tests/HarnaxFeaturesTests/CallMetricsViewModelTests.swift:472`）唯一排到的支路。
- **无推送、无轮询**：进屏、换窗口/维度/来源、下拉刷新才有新数，与 Token 监控同口径。小时档 `stat_hour` 在服务端按周期折叠，屏上不重复实现那套时钟。
- **调试台只钉住 `tool` 维度与 30 天一档**：三份夹具无视入参、恒回同一份（`App/HarnaxDebugScreens.swift:1661-1680`，夹具正文在 `:2776-2785`）。`groupBy` 钉死 `tool`，于是「mcp/cli 维度下来源控件换成一句说明」那一支、7 天与 90 天的窗口回显、以及 `answeredGroup` 与屏上控件不一致的那一类形态，都拍不到；能拍到的形态只有夹具本身排的那六种行——两行带 `subjectId`、四行不带、一行 `subjectName` 为空且 `lastSeenAt` 是解不出的串、一行根本没有 `lastSeenAt`、一行 kind 与 key 都是空白。
- **真机验收未做**：本轮的编译与单测都在命令行跑，界面形态只在模拟器外的调试台夹具与文档口径上核过；装机后按方案甲的四条入口点一遍（治理第四段、枢纽三行、集群监控三行、下钻屏）仍是开口。
