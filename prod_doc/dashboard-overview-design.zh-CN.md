# 首页运营总览（welcome 重设计 + 后台聚合接口）· 设计规格

- 日期：2026-10-05
- 状态：已定稿，待实现
- 影响模块：`harnax-admin`（新接口）、`harnax-entity`（新 SQL）、`harnax-webui`（新页面）
- 取代：`src/pages/Welcome.tsx` 现有的宣传页形态（四个写死的数字与六张「平台能力」卡）

## 0. 需求与现状错位

用户要求：把 welcome 页重新设计，页面里的数据指标全部对应真实的后台接口。

现状与之错位的五处：

1. **页面零请求**。四个统计卡是字面量 `12` / `48` / `1024` / `256`（`src/pages/Welcome.tsx:369-378`），`useCountUp` 只是把写死的数从 0 滚到那个数；`features` 六张卡（同文件 273-280 行）与横幅上的四个标签（348 行）全是内联文案。全仓 Controller 扫描无 `dashboard` / `overview` / `welcome` 路由，所以后端也没有可接的东西。
2. **用户信息自造一份**。页面内 `useCurrentUser`（9-23 行）直接读 `localStorage.getItem('currentUser')`，与 `src/app.tsx` 的 `getInitialState`、`src/utils/permissionUtil.ts:85` 是同一份数据的第三个读者，且不带 token 过期判定。
3. **文案本身有错字**。「让智能体**尅性化**处理复杂任务」（276 行）、「性能指标**实时注入**」（278 行）属于机翻味残留，重设计后这些卡整体删除。
4. **可统计的数据其实都在库里，只是没有读者**。`token_stats` 有 `(tenant_id, ts)` 复合索引、`sys_user.last_login_time`、`skill_draft.status = 'PENDING'`、各资产表的 `tenant_id + active`，全部是现成列；缺的只是聚合出口。
5. **两处口径不能想当然**。`agent_tool`（`V1__init_schema.sql:88`）与 `cli`（同文件 204 行）**没有 `tenant_id` 列**，是平台级注册表，不能和租户资产并排计数；`token_stats.fee` 是 `decimal(10,0)`（747 行）只有整数元，「今日费用」这张卡绝大多数时候是 0。

## 1. 决策清单（已定稿）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 页面形态 = 运营总览仪表盘，删除六张平台能力卡与横幅特性标签 | 用户选定。首页承担「我这个工作区现在怎么样」，不承担产品介绍 |
| D2 | 后端 = 单个只读聚合接口 `GET /api/admin/dashboard/overview` | 一次 RTT；租户谓词集中在一处可审；不动既有 20 条聚合的 wire contract。备选「前端并发拼既有分页接口」被否：拿不到今日调用数与昨日环比，且列表 total 的口径由页面筛选条件决定，不是统计口径 |
| D3 | 租户 = `TenantResolver.resolve(jwtUtil)`，永不做查询参数 | 与 `TokenStatsController` 同源。能在 URL 里点名的租户就是能点名的任意租户 |
| D4 | 「今日请求」= `token_stats` 的行数，卡片文案写「模型调用」 | 每模型调用一行由 `TokenStatsTurnRowsIT` 守着，与 Token/费用同源，几个数字天然对得上。`api_call_log` 属 harnax-session-router 库，跨服务读要另走内部密钥链路，本规格不做 |
| D5 | 时间窗写死：今日 + 昨日同时段环比 + 近 14 天趋势，页面不给窗口选择器 | 与「监控与治理 → Token 监控」分工：那里是带筛选器的深度分析，首页是免配置的一屏 |
| D6 | 环比基准 = 昨日 00:00 到昨日同一钟点 | 「今日至今」对「昨日整天」必然虚假下滑，是错口径 |
| D7 | 资产盘点分两排：租户内 8 格（智能体、技能、模型、MCP 服务、渠道、团队、会话、用户），平台级 2 格（工具、CLI 包）另起一行并标明平台级 | `agent_tool` 与 `cli` 无 `tenant_id`，混进同一排会让人以为租户内只有 48 个工具 |
| D8 | 计数谓词一律 `tenant_id = 当前租户 AND active = 1`，不叠加 `status` | 停用的智能体仍是租户的资产；`status` 是开关不是有无 |
| D9 | 今日四卡 = 模型调用、Token 消耗、活跃会话、活跃智能体；费用只做「近 14 天合计」且按元显示不补小数 | `fee` 列无小数位，`¥0.00` 会假装一个并不存在的精度 |
| D10 | 新 SQL 落点：与 `token_stats` 相关的两条进 `TokenStatsMapper`（复用同文件的 `tenantAndTimeWindow` 与 `dayBucket`），其余计数进新文件 `DashboardMapper` | 该 mapper 的接口注释已预告「加第 21 条聚合也不会漏租户谓词」，新查询走 fragment 才让这句话继续成立 |
| D11 | 响应 DTO 顶层与嵌套字段一律非空带默认值，Controller 返回 `ResultVo<DashboardOverviewResponse>` 泛型具体化 | admin 的 Jackson 3 丢 null 键，前端不能靠「键在不在」判断；泛型具体化是全仓 Controller 既有规范 |
| D12 | 接口失败整体报错，不做分区块降级 | 分区降级要六份独立状态与六份重试，收益远低于成本 |
| D13 | 前端用户走 `initialState`，删掉 `useCurrentUser` 与 `useCountUp` | 同一份登录态已有两个权威读者，首页不该再开第三个；数字动画在真数据下是噪声 |
| D14 | 问候条显示租户名，但不新增租户切换入口 | `TenantSwitcher` 现整体 `return null`（`src/components/TenantSwitcher/index.tsx:92`），租户实际由登录时的 `tokenInfo.currentUser.currentTenantId` 固定并经请求拦截器打进 `X-Tenant-ID`（`src/requestErrorConfig.ts:54-55`）。给它加回入口不在本轮范围 |
| D15 | 路由路径与登录跳转保持 `/welcome` 不变，只取消 `hideInMenu` 并把菜单名从 `welcome` 改为 `dashboard` | 一个作为登录后落地页的总览在菜单里不可见是现状缺陷；改路径会牵动 `app.tsx`、登录页与既有书签 |

## 2. 指标定义与 SQL 谓词

### 2.1 时间窗口

一次请求内 `LocalDateTime.now()` 只取一次（记作 `now`），四个窗口都从它推：

| 窗口 | 起 | 止 | 用于 |
|---|---|---|---|
| 今日 | `now` 的 `00:00:00` | `now` | today 四卡 |
| 昨日同时段 | 昨日起点减一天 | `now` 减一天 | 环比 |
| 近 14 天 | 今日起点减 13 天 | `now` | 趋势、Top 榜、费用合计 |
| 近 7 天 | `now` 减 7 天 | `now` | 活跃用户 |

边界字符串格式 `yyyy-MM-dd HH:mm:ss`，与既有 `tenantAndTimeWindow` 片段接收字符串参数的形状一致（`>=` 与 `<=` 双闭区间，沿用不改）。

### 2.2 指标逐条

| 指标 | 表达式 | 谓词 |
|---|---|---|
| 模型调用次数 | `COUNT(*)` | `token_stats`，`tenant_id` 等值 + `ts` 在窗口内 |
| Token 消耗 | `COALESCE(SUM(total_token), 0)` | 同上 |
| 活跃会话 | `COUNT(DISTINCT session_id)` | 同上 |
| 活跃智能体 | `COUNT(DISTINCT agent_id)` | 同上 |
| 近 14 天费用合计 | `COALESCE(SUM(fee), 0)` | 既有 `getOverallStats` 的直接输出，不新写 SQL |
| 每日调用与 Token | `COUNT(*)`、`SUM(total_token)` 按 `dayBucket` 分组 | 新增查询，include `tenantAndTimeWindow` |
| Top 5 智能体 | 按 `SUM(total_token)` 降序 | 既有 `aggregateByAgent`，窗口给近 14 天 |
| Top 5 模型 | 同上 | 既有 `aggregateByModel` |
| 智能体 / 技能 / 模型 / MCP 服务 / 渠道 / 团队 / 会话 | `COUNT(*)` | 各表 `tenant_id = ? AND active = 1`，不叠加 `status` |
| 用户总数 | `COUNT(*)` | `sys_user` 同谓词（`tenant_id` 可空，NULL 行不属于任何租户） |
| 近 7 天活跃用户 | `COUNT(*)` | 再加 `last_login_time >= now - 7d` |
| 待审技能草稿 | `COUNT(*)` | `skill_draft` `tenant_id = ? AND status = 'PENDING'`（该表无 `active` 列） |
| 平台工具数 / CLI 包数 | `COUNT(*)` | `agent_tool`、`cli` 只有 `active = 1`，无租户列 |

### 2.3 不变量

- **I1 租户谓词单点**：`TokenStatsMapper` 侧新查询必须 `<include refid="tenantAndTimeWindow">`；`DashboardMapper.xml` 自带一个 `tenantActive` 片段作为该文件唯一的租户谓词落点，其中 `tenant_id = #{tenantId}` 不带 `<if>`——缺租户必须读到空而不是全部。
- **I2 归属缺失即不入账**：`token_stats.tenant_id IS NULL` 与 `sys_user.tenant_id IS NULL` 的行不属于任何租户，等值谓词天然排除，与 `TokenStatsMapper` 接口注释的既有约定一致。
- **I3 空窗口不报错**：零数据租户返回全 0 与 14 个 0 点，趋势点数固定 14，缺数据的天补 0（沿用 `TokenStatsServiceImpl` 补点的语义，但首页自己按日补，不复用它的私有方法）。
- **I4 时间语义本地**：窗口边界与 `ts` 同为无时区本地 datetime。全链路一致：`docker-compose.yml:35` 给容器 `TZ: Asia/Shanghai`，DSN 带 `serverTimezone=Asia/Shanghai`（`harnax-deploy/docker-compose.yml:132`）。本规格不引入 UTC 转换。
- **I5 一个请求一个时钟**：`now` 在 Controller 一次取得并下传，Service 不再各自 `now()`，否则「今日至今」与「昨日同时段」的钟点会漂。
- **I6 榜的截断在服务端**：`topAgents` / `topModels` 最多 5 条，SQL 已按 `grandTotalToken DESC`，前端不再排序。

## 3. 后端接口

### 3.1 文件清单

| 动作 | 路径 |
|---|---|
| 新增 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/DashboardController.kt` |
| 新增 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/DashboardService.kt` |
| 新增 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImpl.kt` |
| 新增 | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt` |
| 新增 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/DashboardMapper.kt` |
| 新增 | `harnax-entity/src/main/resources/mapper/DashboardMapper.xml` |
| 修改 | `harnax-entity/.../mapper/TokenStatsMapper.kt`（两条新方法）与其 XML |

无需改装配：`@MapperScan` 覆盖 `com.agnetix.harnax.mapper`（`HarnaxAdminApplication.kt:16`），XML 走 `classpath*:mapper/*.xml`（`application.yml:53`）。

### 3.2 响应 DTO

```kotlin
/** Everything the overview page shows, for the tenant the request is standing in. */
data class DashboardOverviewResponse(
    val today: DashboardWindowStats = DashboardWindowStats(),
    val yesterdaySameSpan: DashboardWindowStats = DashboardWindowStats(),
    val trend: List<DashboardTrendPoint> = emptyList(),
    val topAgents: List<DashboardRankItem> = emptyList(),
    val topModels: List<DashboardRankItem> = emptyList(),
    val assets: DashboardAssetCounts = DashboardAssetCounts(),
    val platform: DashboardPlatformCounts = DashboardPlatformCounts(),
    val pendingSkillDrafts: Long = 0L,
    val totalUsers: Long = 0L,
    val activeUsersLast7Days: Long = 0L,
    val recent14dFee: BigDecimal = BigDecimal.ZERO,
    val serverTime: String = "",
)

/** One window of consumption: how much ran, how many conversations and agents ran it. */
data class DashboardWindowStats(
    val calls: Long = 0L,
    val tokens: Long = 0L,
    val sessions: Long = 0L,
    val agents: Long = 0L,
)

data class DashboardTrendPoint(
    val date: String = "",          // yyyy-MM-dd
    val calls: Long = 0L,
    val tokens: Long = 0L,
)

data class DashboardRankItem(
    val name: String = "",          // empty when the consuming row's agent or model is gone
    val qualifier: String = "",     // the model's provider name; always empty on the agent list
    val tokens: Long = 0L,
)

data class DashboardAssetCounts(
    val agents: Long = 0L, val skills: Long = 0L, val models: Long = 0L,
    val mcpServers: Long = 0L, val channels: Long = 0L, val teams: Long = 0L,
    val sessions: Long = 0L, val users: Long = 0L,
)

data class DashboardPlatformCounts(
    val tools: Long = 0L,           // agent_tool has no tenant column, deliberately platform-wide
    val cliPackages: Long = 0L,
)
```

榜项没有 id：这两张榜只做展示，页面上没有任何动作要回到那条 agent 或 model 的行。`aggregateByAgent` / `aggregateByModel` 用 LEFT JOIN 保住了归属行已删除的消耗，这类行返回空 `name`，前端显示「（已删除）」。也正因为同一榜里可能出现多条空名，`RankList` 的 React key 取数组下标而不是 `name`。

两条语句都按消耗行的 `chat_model_id` / `agent_id` 分组，所以**同一个显示名合法地出现两行**是常态而不是脏数据：一个模型名挂在两个 provider 下就是两行，两套智能体同名也是两行。页面上两行一模一样的标签读起来像渲染重复了，因此 `qualifier` 带上 `aggregateByModel` 已经选出来的 `providerName` 作为第二重标识，智能体维度没有对应的第二列、恒为空串。榜仍不带 id：把 id 放进线上契约是给一个页面上不存在的动作做准备。

### 3.3 新增 SQL 形状

`TokenStatsMapper.xml` 两条，均 include 既有片段：

```xml
<!-- The dashboard's window question: how much ran, in how many conversations and on how many agents. -->
<select id="getDashboardWindowStats" resultType="map">
    SELECT COUNT(*) AS calls,
           COALESCE(SUM(total_token), 0) AS tokens,
           COUNT(DISTINCT session_id) AS sessions,
           COUNT(DISTINCT agent_id) AS agents
    FROM token_stats t
    WHERE 1 = 1
    <include refid="tenantAndTimeWindow"><property name="alias" value="t"/></include>
</select>

<!-- Day buckets carry a call count too, which the token-only series never had. -->
<select id="getDashboardDailyTrend" resultType="map">
    SELECT <include refid="dayBucket"><property name="alias" value="t"/></include> AS timePoint,
           COUNT(*) AS calls,
           COALESCE(SUM(total_token), 0) AS tokens
    FROM token_stats t
    WHERE 1 = 1
    <include refid="tenantAndTimeWindow"><property name="alias" value="t"/></include>
    GROUP BY timePoint ORDER BY timePoint
</select>
```

`DashboardMapper.xml` 两条，一格一个标量子查询（先例：`ModelMapper.xml:74-80` 的 `selectUsageByModelId`）：

```xml
<!-- The one tenant predicate in this file, and it carries no <if>: a missing tenant has to read
     as nothing, not as everything. -->
<sql id="tenantActive">WHERE 1 = 1 AND tenant_id = #{tenantId} AND active = 1</sql>

<!-- One row for the whole asset strip. Every cell includes tenantActive, so a new cell written
     without it shows up in the diff as the only subquery missing the fragment. -->
<select id="getTenantAssetCounts" resultType="map">
    SELECT (SELECT COUNT(*) FROM agent <include refid="tenantActive"/>) AS agents,
           (SELECT COUNT(*) FROM skill <include refid="tenantActive"/>) AS skills,
           (SELECT COUNT(*) FROM `model` <include refid="tenantActive"/>) AS models,
           (SELECT COUNT(*) FROM mcp_server <include refid="tenantActive"/>) AS mcpServers,
           (SELECT COUNT(*) FROM channel <include refid="tenantActive"/>) AS channels,
           (SELECT COUNT(*) FROM team <include refid="tenantActive"/>) AS teams,
           (SELECT COUNT(*) FROM session <include refid="tenantActive"/>) AS sessions,
           (SELECT COUNT(*) FROM sys_user <include refid="tenantActive"/>) AS users,
           (SELECT COUNT(*) FROM sys_user <include refid="tenantActive"/>
              AND last_login_time &gt;= #{activeUserSince}) AS activeUsers,
           (SELECT COUNT(*) FROM skill_draft WHERE 1 = 1 AND tenant_id = #{tenantId}
              AND status = 'PENDING') AS pendingSkillDrafts
</select>

<!-- No tenant column on either table, so this one legitimately has no tenant predicate. -->
<select id="getPlatformCounts" resultType="map">
    SELECT (SELECT COUNT(*) FROM agent_tool WHERE active = 1) AS tools,
           (SELECT COUNT(*) FROM cli WHERE active = 1) AS cliPackages
</select>
```

`skill_draft` 那一格不用 `tenantActive`：该表没有 `active` 列，软删语义由 `status` 承担（`V1__init_schema.sql:513-534`）。

列别名即契约：`resultType="map"` 下 Service 按这些 camelCase 键读，与 `TokenStatsAggregationResponse` 的既有做法一致。

### 3.4 Service

`DashboardServiceImpl` 职责，全部只读、无事务、无缓存：

1. 签名 `overview(tenantId: Long, now: LocalDateTime)`，由 Controller 一次取钟下传（I5），从这里推出 §2.1 四个窗口的字符串边界。
2. `getDashboardWindowStats` 调两次（今日、昨日同时段）；两次结果都是单行 map，无行即全 0。
3. `getDashboardDailyTrend` 取行后**由 Service 补齐 14 个日点**：以 `todayStart - 13d` 起逐日生成键，行里的 `timePoint` 转成 `yyyy-MM-dd` 对齐；未命中的天填 0。
4. `getOverallStats(近 14 天窗口)` 取 `totalFee`（复用既有语句，不新写）。
5. `aggregateByAgent` / `aggregateByModel` 传同一近 14 天窗口，各取前 5 映射成 `DashboardRankItem`；模型榜多带一列 `providerName` 落成 `qualifier`，智能体榜没有第二列、`qualifier` 留空。
6. `getTenantAssetCounts(tenantId, activeUserSince)` 与 `getPlatformCounts()`。
7. `serverTime` = `now` 按 `yyyy-MM-dd HH:mm:ss` 格式化，前端用它显示「数据截至」。

`from` / `to` 参数命名与既有 mapper 一致（`startTime`、`endTime`、`tenantId`，`tenantId` 为非空 `Long`——这是 `TokenStatsMapper` 接口注释立的规矩：没有值表示「所有租户」）。

### 3.5 Controller 与鉴权

```kotlin
@RestController
@RequestMapping("/api/admin/dashboard")
@Tag(name = "Dashboard", description = "Tenant-scoped overview metrics for the landing page")
class DashboardController(
    private val dashboardService: DashboardService,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(DashboardController::class.java)

    @GetMapping("/overview")
    @Operation(summary = "Get the overview metrics for the caller's tenant")
    fun overview(): ResultVo<DashboardOverviewResponse> = try {
        // One clock read for the whole page: the today window, its yesterday counterpart and the
        // 14-day trend must all hang off the same instant, or "up to this hour yesterday" drifts.
        ResultVo.success(dashboardService.overview(TenantResolver.resolve(jwtUtil), LocalDateTime.now()))
    } catch (e: Exception) {
        log.error("Failed to build dashboard overview", e)
        ResultVo.error("Failed to get overview data")
    }
}
```

- 无任何查询参数，接口签名保持 `overview()` 空参——一旦有了 `days` 或 `tenantId` 参数，D3 与 D5 就破了。
- `now` 是 Service 的参数而不是 Service 内部的 `LocalDateTime.now()`：单测要能在一个固定时刻上断言四个窗口（§6.1），线上一天里多次调用也共用同一条注入路径。
- 鉴权走既有链路：`SecurityConfig.kt:52-53` 的 `anyRequest().authenticated()` + `JwtAuthenticationFilter`，不新增白名单，不进 `/api/admin/internal/**`。
- 转发链无需改动：本地开发 `/api/admin/` 已通配代理到 `localhost:8080`（`harnax-webui/config/proxy.ts:16-19`），部署侧 nginx `location /api/admin/` 整前缀转 admin（`harnax-deploy/nginx.conf:191`）。
- 异常统一 `ResultVo.error`（HTTP 200 + code 500），与 `TokenStatsController` 同形。

## 4. 前端页面

### 4.1 结构

```
PageContainer
├─ 问候条        Hi {nickname}｜{租户名}｜数据截至 {serverTime}｜刷新（文字链）
├─ 今日四卡      模型调用 / Token 消耗 / 活跃会话 / 活跃智能体，每卡底部一行环比
├─ 趋势卡        近 14 天双序列折线（调用次数、Token），右下角「近 14 天费用 ¥{fee}」
├─ 榜（两列）    Top 5 智能体｜Top 5 模型，行内条形占比
├─ 租户资产条    8 格：智能体 技能 模型 MCP 服务 渠道 团队 会话 用户
├─ 平台内置行    工具 {n} · CLI 包 {m}，前缀小字「平台级，非本租户」
└─ 待办与入口    待审草稿 {k} → /monitor/skill-drafts；近 7 天活跃用户 {a}/{u}
                快速入口三个文字链：创建智能体 / 管理技能 / Token 监控
```

删除项：六张 `AnimatedFeatureCard`、`features` 数组、横幅四个标签、`useCountUp`、`useCurrentUser`、`AnimatedStatCard` 的入场延迟动画。

### 4.2 组件拆分

`Welcome.tsx` 只保留取数与编排，目标 200 行内；四个子组件放同目录 `src/pages/welcome/`：

| 文件 | 职责 | 依赖 |
|---|---|---|
| `sections.tsx` | `GreetingBar`、`TodayStatsRow`、`AssetStrip`、`TodoStrip` | 纯展示，props 进 |
| `TrendCard.tsx` | 折线，`Line` from `@ant-design/plots` | 既有依赖，与 token-monitor 同款 |
| `RankList.tsx` | Top 榜，行宽按榜内最大值归一，标签取 `rankLabels` | 纯展示 |
| `metrics.ts` | `formatCount`、`formatTokens`、`deltaOf`、`deltaTone`、`rankLabels` | 纯函数，可单测 |

`deltaOf(current, previous)` 的返回形状：previous 为 0 且 current 为 0 → 显示「持平」；previous 为 0 且 current 大于 0 → 显示「新增」，**不显示 +∞%、不显示 100%**；其余按 `(今 - 昨) / 昨` 的百分比一位小数，**方向跟的是这个四舍五入之后的数**——抹到 `0.0%` 的差值判「持平」，不许出现「上升 0.0%」。这两条都要在 `metrics.ts` 的测试里钉住：零基线是新工作区最常见的状态，而今日对昨日的 Token 差一位数就能算出 0.03%。

`rankLabels(items)` 的返回形状（与榜项一一对应的展示名）：名字在本榜出现多于一次且这一行带得上 `qualifier` → `名字（qualifier）`；名字唯一、或 `qualifier` 缺失/全空白 → **原样返回**，不许编造区分度；`name` 为空 → 返回空串，由 `RankList` 渲染「（已删除）」，且这类行**不计入撞名统计**——把它们算进去会让一个真实名字莫名带上括号。这三条同样落在 `metrics.ts` 的测试里：撞名在部署库上是真实存在的形状，而「只在撞名时补」和「总是补」在只有一条数据的页面上看不出差别。

### 4.3 service 与类型

```ts
// src/services/ant-design-pro/dashboard.ts
export async function getDashboardOverview() {
  return request<API.Result<API.DashboardOverview>>('/api/admin/dashboard/overview', {
    method: 'GET',
  });
}
```

`API.DashboardOverview` 及子类型追加到 `src/typings.d.ts`，字段与 §3.2 逐字对应，数值一律 `number`（Long 在 JSON 里就是数字）。注意 `src/services/**` 被 biome 排除（`biome.json:11`），所以类型必须写在 `typings.d.ts` 而不是 service 文件里。

### 4.4 取数与状态

单 `useEffect` + 一次请求，三个状态 `loading` / `error` / `data`：

- `loading`：分区骨架只在「一个读数都还没有」时给（`firstLoad = loading && data === null`）——四卡与资产条给 `Skeleton`，趋势卡给 `Spin`，不做骨架屏以外的编造；已经取到过读数之后再点刷新，旧读数原地留着，反馈交给问候条那个转动图标和重试按钮的 `loading`。
- `error`：整页一个错误态卡 + 「重试」按钮（复用既有 key `pages.common.retry`），不做局部降级（D12）。这一屏的取数带 `skipErrorHandler: true`，否则 umi 的全局处理器会在页面卡片之外再弹一条红 toast，一处错误态变成两处。
- 成功后不轮询、不 `setInterval`；刷新靠问候条那个文字链。
- `code !== 200` 也算错误，判定写 `res.code === 200`，与 token-monitor 一致。

### 4.5 文案与 i18n

新增 key 落 `src/locales/{zh-CN,en-US}/pages.ts`，两侧 key 集合必须完全相同：

```
pages.welcome.greeting / tenant / dataAsOf / refresh
pages.welcome.today.calls / tokens / sessions / agents
pages.welcome.delta.up / down / flat / new          // 环比四种说法
pages.welcome.trend.title / calls / tokens / fee14d
pages.welcome.rank.agents / rank.models / rank.deleted
pages.welcome.assets.title / agents / skills / models / mcp / channels / teams / sessions / users
pages.welcome.platform.title / note / tools / cli
pages.welcome.todo.drafts / todo.activeUsers7d
pages.welcome.quick.agent / skill / tokenMonitor
pages.welcome.loadFailed
```

菜单 key：`menu.welcome` 两行替换为 `menu.dashboard`（zh `总览` / en `Overview`）。

`config/routes.ts:25-31` 的 `name` 从 `welcome` 改 `dashboard`、去掉 `hideInMenu`，`path` 与 `component` 不动；`redirect: '/welcome'`（207-210 行）不动。

### 4.6 样式与响应式

- 沿用玻璃拟态：`background: 'var(--glass-bg)'`、`backdropFilter: 'blur(16px)'`、`border: '1px solid var(--glass-border)'`、`boxShadow: 'var(--glass-shadow)'`，与 `SearchFilterBar` 同形状；卡片已有 `.glass-card` 工具类可用（`global.less:149-155`）。
- **凡需要与透明度拼接的颜色必须写十六进制**，不能用 `var(--vip-primary)`（`EntityCard` 已踩过：模板串拼 `15` 后缀对 CSS 变量无效）。纯色文字与描边可以用 CSS 变量，深色主题自动跟随。
- 数字一律 `toLocaleString()` 千分位；Token 超过 1e6 走 `M`、超过 1e3 走 `K`（与 token-monitor 的 `formatToken` 同一规则，抽到 `metrics.ts` 共用）。
- 响应式：四卡 `xs=24 sm=12 lg=6`；资产条 `xs=12 sm=6 lg=3`；两列榜在 `isMobile` 下纵向堆叠。用既有 `useIsMobile`（`src/utils/responsive.ts:28`）。
- 折线在 `xs` 下关掉图例、只留 tooltip，避免移动端挤压。

## 5. 错误处理

| 情况 | 行为 |
|---|---|
| 未登录 / token 过期 | 既有链路：`app.tsx` 的 token 过期检查 + 请求拦截器，接口通常收不到请求。若令牌在页面打开后过期，本页取数带 `skipErrorHandler: true`（整页只留一个错误态，D12 与 §4.4 那条），全局那条 401 → `/login` 随之关闭，因此这一跳由本页接：清掉 `currentUser`/`tokenInfo` 后 `history.push('/login?redirect=%2Fwelcome')` |
| 某条 count 的表不存在（迁移未跑） | 异常上抛 → `ResultVo.error` → 前端整页错误态。不做吞异常返回 0，因为「全 0」和「查不到」在页面上看起来一样 |
| 租户解析不到 | `TenantResolver.resolve` 兜到 `DEFAULT_TENANT_ID = 1`，与全仓其余 admin 读路径一致；总览是只读，兜底不会写脏数据 |
| 归属行已删除的消耗 | 榜单里保留数值、名字空，前端显示「（已删除）」，不整条丢弃（与 `aggregateByModel` 的 LEFT JOIN 语义同源） |
| 同一榜里两条同名条目 | 模型榜给这几行补 `（provider）`，其余行不动；模型行被删时 provider 一并没了，那两行只能同名——榜不带 id，页面上没有要回到某一行的动作，加 id 是给不存在的动作做准备 |

## 6. 测试

### 6.1 后端单测

`DashboardServiceImplTest`（Mockito 打桩两个 mapper，`any()` 不用于可空与原始类型参数——Kotlin + Mockito 的已知静默陷阱）：

- 窗口边界：给定固定 `now`，断言下发给 mapper 的四个字符串窗口逐字符合 §2.1（含昨日同时段的钟点相等）。
- 补点：mapper 返回缺 3 天的 11 行，断言输出 14 个点且缺失天为 0。
- 榜截断：mapper 返回 7 行，断言取到 5 行且顺序不变（Service 不再排序）。
- 榜的限定词：模型榜的行带 `providerName`，断言逐条落成 `qualifier`；智能体榜走无第二列的映射，断言 `qualifier` 为空串而不是被别的列填上。
- 租户透传：`verify` 每个 mapper 调用都带了传入的 `tenantId`，`getPlatformCounts` 不带（用无参签名保证）。

### 6.2 集成测试（真 MySQL，testcontainers）

两个类，继承 `BaseAdminIT`，按 `TokenStatsAggregationIT` 的先例用 `JdbcTemplate` 直插具 id 行：

**`DashboardOverviewIT`** — 断言**差值**而不是绝对值。因为「今日」窗口只能落在真实时间上，而共享容器会留着同 JVM 其他 IT 类写进行为；做法是 seed 前调一次 `/api/admin/dashboard/overview`、seed 后调一次，断言 `calls` / `tokens` / `sessions` 的增量恰等于刚插入的行。插入形状：3 行 `token_stats`（同租户同会话 2 行、另一会话 1 行）→ 断言 calls +3、sessions +2、agents +1、tokens +三行和。再插 1 行 `agent` 断言 `assets.agents` +1。

再加一条同名榜的用例：插两个 `model_provider`、两条显示名相同而 `model_name` 不同的 `model`，以及各落到一条 model 上的消耗（Token 数取到能进前 5，否则被截断后断言数的是空列表）。断言 `topModels` 里该名字恰出现两次、两行的 `qualifier` 分别是两个 provider，且 `topAgents` 的 `qualifier` 为空串。桩不出这一条——`qualifier` 从 SQL 到 JSON 的每一跳都可能被吃掉，单测只看得到 Service 那一段。

**`DashboardTenantIsolationIT`** — 邻居租户用一个只属于本类的合成 id，先例是 `TokenStatsAggregationIT.NEIGHBOUR_TENANT_ID = 930_930L` 与 `ModelTenantIsolationIT.otherTenant = 940_002L`；不用种子数据里的租户 4 与 5，那里的既有行会把差值断言变成碰运气。做法：往合成租户插 `token_stats` 与 `agent` 行，用 `exchange(..., tenantId = 1)` 取两次 overview，断言租户 1 的每个数字**一字未动**。这是 I1/I2 唯一便宜的证法——去掉 SQL 里的租户谓词，这两个断言必须变红。

跑法：`mvn -Pintegration-test`（先探 Docker 可用性，本机不可用则显式记为未验，不当作通过）。删过源文件要 `clean` 再跑，残留 `.class` 会让旧路由继续应答并造出假绿。

### 6.3 前端闸门

1. `npm run build`（max build，唯一可靠的类型收口）+ `npx @biomejs/biome lint`。`tsc --noEmit` 有一批既有噪声，不作为通过判据。
2. 真渲染：起 dev server 用无头 Chromium 走 HTTP 回环（部署侧 https 自签打不开），核三件事——四卡显示真实数字、环比文案出现、控制台无新报错。
3. 数字对账：页面读数与一条手写 SQL 比对（`SELECT COUNT(*) FROM token_stats WHERE tenant_id = ? AND ts >= CURDATE()`），不看「页面渲染出来了」就当数据对。

## 7. 验收清单

- [x] `/api/admin/dashboard/overview` 无参数，响应 `ResultVo` 且 `data` 含 §3.2 全部 12 个顶层字段（`DashboardOverviewIT.responseCarriesEveryFieldWithDefaults` 逐项断言；真库载荷 12 个键齐）
- [x] 两个 IT 在真 MySQL 上跑绿（`DashboardOverviewIT` 3 项、隔离类 2 项），其中两条是变异探针：人为删掉 SQL 的租户谓词后红的正是邻居用例（`Tests run: 9, Failures: 1`），人为去掉模型榜的 `providerName` 后红的正是同名榜用例（`Tests run: 3, Failures: 1`，actual `<[, ]>`）
- [x] `Welcome.tsx` 内不再出现任何写死的统计数字，`grep -nE 'value=\{[0-9]+\}' src/pages/Welcome.tsx` 零命中（0）
- [x] 六张平台能力卡与横幅标签已删，`features` 数组零残留（`AnimatedFeatureCard` 在 `src/` 下零命中；`features` 在 `src/pages/Welcome.tsx` 与 `src/pages/welcome/` 下零命中——`src/pages/user/login/index.tsx:223,802` 那两处同名变量属登录页自身，与本页无关）
- [x] `useCountUp` / `useCurrentUser` 从页面移除，`localStorage.getItem('currentUser')` 在 `src/pages/` 下零命中（三项均 0）
- [x] 菜单顶位出现「总览 / Overview」并指向 `/welcome`，`/` 仍重定向到它，登录后落地页不变（`menu.dashboard` = zh 总览 / en Overview；`/welcome` 之前的 `/login` 块带 `layout: false`（`routes.ts:16`）不渲染菜单，所以它是侧栏菜单里的第一项；`routes.ts:177-178` 的 `path: '/'` + `redirect: '/welcome'` 未动）
- [x] zh 与 en 的 `pages.welcome.*` key 集合差为空（两侧各 38，双向差集为空，且 38 个全有消费方）
- [x] `max build` 与 `biome lint` 绿；无头 Chromium 复核过真实数字（构建 8 轮 `EXIT=0`；窄范围 lint `Checked 7 files` 零诊断；1400 与 390 两档截图读数逐字对上手写 SQL）
- [x] 工具与 CLI 两格在页面上明确标为平台级，不与租户资产同排（`Divider` 之后独立一行，标题紧跟「平台级，非本租户」小字）
- [x] 同一榜里的同名条目在页面上可区分：无头 Chromium 读模型榜四行为 `qwen3.7-flash（阿里百炼）` / `qwen3.7-flash（内部网关）` / `gpt-5` / `（已删除）`——撞名的两行各带 provider，没撞名的那行原样，空名那行不计入撞名。夹具是 `logs/payload.json` 加 `qualifier` 得到的：两行同名的 Token 数（250.71K / 180.00K）取自部署库真跑出来的载荷，两个 provider 名是夹具造的，因为部署库的只读查询被权限层拒绝、线上真名取不到。`rankLabels` 两个方向都被变异探过：去掉撞名判定红 2 项、去掉补限定词红 1 项，19 项全绿
- [x] 部署侧未新增 nginx / proxy / 白名单规则（复用 `/api/admin/` 整前缀转发——`git status --short -- harnax-deploy` 为空，`nginx.conf:191` 的 `location /api/admin/` 已覆盖）

## 8. 范围外

- `api_call_log` 网关侧指标（QPS、成功率、P95 延迟）——跨服务读表，需要内部密钥链路与 router 侧新接口。
- 时间窗口选择器、自动刷新、指标告警、导出。
- 租户切换入口（`TenantSwitcher` 现整体隐藏，本轮不动它）。
- 超管跨租户全局视图与租户间排行。
- `fee` 列 `decimal(10,0)` 的精度问题（属数据模型，改它要动已应用的 Flyway 基线）。
- `token-monitor` 页面自身的重构。

## 9. 落地顺序

1. `DashboardMapper.kt` + `DashboardMapper.xml`（含 `tenantActive` 片段与两条计数语句）
2. `TokenStatsMapper` 两条新方法与其 XML（`getDashboardWindowStats`、`getDashboardDailyTrend`）
3. `DashboardOverviewResponse` DTO + `DashboardService` / `Impl` + `DashboardServiceImplTest`
4. `DashboardController` + 两个 IT
5. 前端 `dashboard.ts` service + `typings.d.ts` 类型 + `metrics.ts` 及其单测
6. `src/pages/welcome/` 四个子组件 + `Welcome.tsx` 重写 + i18n 两侧 + `routes.ts` 与菜单 key
7. 闸门：两个 IT、`max build`、`biome lint`、无头 Chromium 真渲染与 SQL 对账
