# Landing-page operations overview (welcome redesign + backend aggregation endpoint) · Design spec

- Date: 2026-10-05
- Status: finalized, pending implementation
- Affected modules: `harnax-admin` (new endpoint), `harnax-entity` (new SQL), `harnax-webui` (new page)
- Replaces: the promotional shape `src/pages/Welcome.tsx` has today (four hardcoded numbers and six "platform capability" cards)

## 0. Gap between the requirement and the current state

The request: redesign the welcome page so that every data metric on it corresponds to a real backend endpoint.

Five places where the current state does not match that:

1. **The page makes zero requests.** The four stat cards are the literals `12` / `48` / `1024` / `256` (`src/pages/Welcome.tsx:369-378`), and `useCountUp` only rolls a hardcoded number up from 0 to that number; the six `features` cards (lines 273-280 of the same file) and the four tags on the banner (line 348) are all inline copy. A repository-wide Controller scan finds no `dashboard` / `overview` / `welcome` route, so the backend has nothing to hook up either.
2. **The user info gets a third copy.** The page's own `useCurrentUser` (lines 9-23) reads `localStorage.getItem('currentUser')` directly; together with `getInitialState` in `src/app.tsx` and `src/utils/permissionUtil.ts:85` it is the third reader of the same data, and it carries no token-expiry check.
3. **The copy itself contains typos.** 「让智能体**尅性化**处理复杂任务」 (line 276; "let agents handle complex tasks **personalized**", where 尅性化 is a typo for 个性化, "personalization") and 「性能指标**实时注入**」 (line 278; "performance metrics **injected in real time**") are machine-translation leftovers, and the redesign deletes these cards outright.
4. **The statable data is already in the database; it simply has no reader.** `token_stats` has a `(tenant_id, ts)` composite index, and `sys_user.last_login_time`, `skill_draft.status = 'PENDING'` and `tenant_id + active` on the asset tables are all existing columns; what is missing is only an aggregation exit.
5. **Two metric bases cannot be assumed.** `agent_tool` (`V1__init_schema.sql:88`) and `cli` (line 204 of the same file) **have no `tenant_id` column**: they are platform-level registries and cannot be counted alongside tenant assets; `token_stats.fee` is `decimal(10,0)` (line 747), whole yuan only, so a 「今日费用」 ("today's cost") card would read 0 the vast majority of the time.

## 1. Decision list (finalized)

| # | Decision | Rationale |
|---|---|---|
| D1 | Page shape = an operations dashboard; delete the six platform capability cards and the banner feature tags | Chosen by the user. The landing page answers "how is this workspace doing right now", it does not introduce the product |
| D2 | Backend = a single read-only aggregation endpoint `GET /api/admin/dashboard/overview` | One RTT; the tenant predicate sits in one auditable place; the wire contract of the 20 existing aggregations is untouched. The alternative "have the front end fan out over the existing paged endpoints" was rejected: it cannot obtain today's call count or the day-over-day delta, and a list total is defined by the page's filter conditions, which is not a statistical basis |
| D3 | Tenant = `TenantResolver.resolve(jwtUtil)`, never a query parameter | Same source as `TokenStatsController`. A tenant that can be named in the URL is any tenant a caller can name |
| D4 | 「今日请求」 ("today's requests") = the row count of `token_stats`, with the card copy reading 「模型调用」 ("model calls") | One row per model call, guarded by `TokenStatsTurnRowsIT`; the same source as Token and cost, so the numbers line up by themselves. `api_call_log` belongs to the harnax-session-router database, and reading it across services needs a separate internal-key path, which this spec does not do |
| D5 | Time windows are fixed: today + the same span yesterday for the delta + a 14-day trend, and the page offers no window selector | Division of labour with 「集群监控 → Token 监控」 ("Cluster monitoring → Token monitor"): that page is deep analysis with filters, the landing page is one screen that needs no configuration |
| D6 | Delta baseline = yesterday 00:00 to the same hour yesterday | "Today so far" against "all of yesterday" is necessarily a false decline — the wrong basis |
| D7 | The asset inventory splits into two rows: 8 cells inside the tenant (agents, skills, models, MCP servers, channels, teams, sessions, users), and 2 platform-level cells (tools, CLI packages) on a row of their own marked platform-level | `agent_tool` and `cli` have no `tenant_id`; mixing them into the same row would leave the impression that the tenant has only 48 tools |
| D8 | Counting predicates are always `tenant_id = 当前租户 AND active = 1` (the current tenant), with no `status` added | A disabled agent is still an asset of the tenant; `status` is a switch, not existence |
| D9 | The four today cards = model calls, Token consumption, active sessions, active agents; cost appears only as a "14-day total", shown in yuan with no padded decimals | The `fee` column has no decimal places, and `¥0.00` would pretend to a precision that does not exist |
| D10 | Where the new SQL lands: the two `token_stats`-related queries go into `TokenStatsMapper` (reusing `tenantAndTimeWindow` and `dayBucket` from the same file), the remaining counts into a new file `DashboardMapper` | That mapper's interface comment already promises "adding a 21st aggregation will not drop the tenant predicate"; only a new query that goes through the fragment keeps that sentence true |
| D11 | Every field of the response DTO, top level and nested, is non-null with a default; the Controller returns `ResultVo<DashboardOverviewResponse>` with the generic made concrete | admin's Jackson 3 drops null keys, so the front end cannot judge by whether a key is present; a concrete generic is the existing convention for every Controller in the repository |
| D12 | A failed endpoint errors as a whole; no degradation by section | Per-section degradation would need six independent states and six retries; the gain falls far below the cost |
| D13 | The front end takes the user from `initialState`; `useCurrentUser` and `useCountUp` are deleted | One login state already has two authoritative readers, and the landing page should not open a third; number animation is noise against real data |
| D14 | The greeting bar shows the tenant name, but no tenant-switching entry is added | `TenantSwitcher` currently does `return null` as a whole (`src/components/TenantSwitcher/index.tsx:92`); in practice the tenant is fixed at login by `tokenInfo.currentUser.currentTenantId` and put into `X-Tenant-ID` by the request interceptor (`src/requestErrorConfig.ts:54-55`). Bringing back its entry point is outside this round |
| D15 | The route path and the post-login redirect stay `/welcome`; only `hideInMenu` is dropped and the menu name changes from `welcome` to `dashboard` | An overview that serves as the post-login landing page being invisible in the menu is a defect of the current state; changing the path would pull in `app.tsx`, the login page and existing bookmarks |

## 2. Metric definitions and SQL predicates

### 2.1 Time windows

Within one request `LocalDateTime.now()` is read exactly once (written `now` below), and all four windows derive from it:

| Window | Start | End | Used by |
|---|---|---|---|
| Today | the `00:00:00` of `now` | `now` | the four today cards |
| Yesterday, same span | one day before today's start | `now` minus one day | day-over-day delta |
| Last 14 days | today's start minus 13 days | `now` | trend, Top rankings, cost total |
| Last 7 days | `now` minus 7 days | `now` | active users |

The boundary string format is `yyyy-MM-dd HH:mm:ss`, matching the shape of the string parameters the existing `tenantAndTimeWindow` fragment already takes (a closed interval on both `>=` and `<=`, kept as is).

### 2.2 Metric by metric

| Metric | Expression | Predicate |
|---|---|---|
| Model call count | `COUNT(*)` | `token_stats`, equality on `tenant_id` plus `ts` inside the window |
| Token consumption | `COALESCE(SUM(total_token), 0)` | Same as above |
| Active sessions | `COUNT(DISTINCT session_id)` | Same as above |
| Active agents | `COUNT(DISTINCT agent_id)` | Same as above |
| Last-14-day cost total | `COALESCE(SUM(fee), 0)` | Direct output of the existing `getOverallStats`, no new SQL |
| Daily calls and Token | `COUNT(*)`, `SUM(total_token)` grouped by `dayBucket` | New query, includes `tenantAndTimeWindow` |
| Top 5 agents | Ordered by `SUM(total_token)` descending | Existing `aggregateByAgent`, window set to the last 14 days |
| Top 5 models | Same as above | Existing `aggregateByModel` |
| Agents / skills / models / MCP servers / channels / teams / sessions | `COUNT(*)` | `tenant_id = ? AND active = 1` on each table, with no `status` added |
| Total users | `COUNT(*)` | `sys_user` with the same predicate (`tenant_id` is nullable, and NULL rows belong to no tenant) |
| Active users in the last 7 days | `COUNT(*)` | Plus `last_login_time >= now - 7d` |
| Skill drafts pending review | `COUNT(*)` | `skill_draft` `tenant_id = ? AND status = 'PENDING'` (that table has no `active` column) |
| Platform tools / CLI packages | `COUNT(*)` | `agent_tool` and `cli` have `active = 1` only, no tenant column |

### 2.3 Invariants

- **I1 single landing point for the tenant predicate**: a new query on the `TokenStatsMapper` side must `<include refid="tenantAndTimeWindow">`; `DashboardMapper.xml` carries one `tenantActive` fragment as that file's only landing point for the tenant predicate, and inside it `tenant_id = #{tenantId}` has no `<if>` — a missing tenant has to read as nothing rather than as everything.
- **I2 no owner means no credit**: rows with `token_stats.tenant_id IS NULL` and `sys_user.tenant_id IS NULL` belong to no tenant, and the equality predicate excludes them by nature, consistent with the convention already stated in the `TokenStatsMapper` interface comment.
- **I3 an empty window does not error**: a tenant with zero data returns all zeros and 14 zero points, the trend point count is fixed at 14, and days without data are zero-padded (following the zero-padding semantics of `TokenStatsServiceImpl`, but the landing page pads by day on its own and does not reuse that private method).
- **I4 time semantics stay local**: the window boundaries and `ts` are both timezone-less local datetime. The whole chain agrees: `docker-compose.yml:35` gives the container `TZ: Asia/Shanghai`, and the DSN carries `serverTimezone=Asia/Shanghai` (`harnax-deploy/docker-compose.yml:132`). This spec introduces no UTC conversion.
- **I5 one clock per request**: `now` is taken once in the Controller and passed down; the Service does not call `now()` on its own, otherwise the hour of "today so far" and of "the same span yesterday" would drift.
- **I6 ranking truncation happens server-side**: `topAgents` / `topModels` carry at most 5 items, the SQL already orders by `grandTotalToken DESC`, and the front end does not sort again.

## 3. Backend endpoint

### 3.1 File inventory

| Action | Path |
|---|---|
| New | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/DashboardController.kt` |
| New | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/DashboardService.kt` |
| New | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImpl.kt` |
| New | `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt` |
| New | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/DashboardMapper.kt` |
| New | `harnax-entity/src/main/resources/mapper/DashboardMapper.xml` |
| Modify | `harnax-entity/.../mapper/TokenStatsMapper.kt` (two new methods) and its XML |

No wiring change is needed: `@MapperScan` covers `com.agnetix.harnax.mapper` (`HarnaxAdminApplication.kt:16`), and the XML is picked up through `classpath*:mapper/*.xml` (`application.yml:53`).

### 3.2 Response DTO

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

Rank items carry no id: both rankings are display only, and no action on the page has to go back to that agent or model row. `aggregateByAgent` / `aggregateByModel` use a LEFT JOIN, which keeps the consumption whose owning row has been deleted; such a row returns an empty `name`, and the front end shows 「（已删除）」 ("(deleted)"). It is also because several empty names can appear in one ranking that the React key of `RankList` takes the array index rather than `name`.

Both statements group by the consuming row's `chat_model_id` / `agent_id`, so **one display name legitimately appearing on two rows** is the normal shape rather than dirty data: one model name registered under two providers is two rows, and two agents of the same name are two rows. Two identical labels on the page read as a rendering fault, so `qualifier` carries the `providerName` that `aggregateByModel` already selects as the second label; the agent dimension has no second column and it stays empty. The rankings still carry no id — putting one on the wire would be preparing for an action the page does not have.

### 3.3 Shape of the new SQL

Two statements in `TokenStatsMapper.xml`, both including the existing fragments:

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

Two statements in `DashboardMapper.xml`, one scalar subquery per cell (precedent: `selectUsageByModelId` in `ModelMapper.xml:74-80`):

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

The `skill_draft` cell does not use `tenantActive`: that table has no `active` column, and the soft-delete semantics are carried by `status` (`V1__init_schema.sql:513-534`).

The column aliases are the contract: under `resultType="map"` the Service reads these camelCase keys, consistent with what `TokenStatsAggregationResponse` already does.

### 3.4 Service

Responsibilities of `DashboardServiceImpl`, all read-only, no transaction, no cache:

1. Signature `overview(tenantId: Long, now: LocalDateTime)`; the Controller reads the clock once and passes it down (I5), and the string boundaries of the four windows of §2.1 are derived from here.
2. `getDashboardWindowStats` is called twice (today, the same span yesterday); both results are a single-row map, and no row means all zeros.
3. After `getDashboardDailyTrend` returns rows, **the Service pads them to 14 day points**: keys are generated day by day starting from `todayStart - 13d`, and each row's `timePoint` is converted to `yyyy-MM-dd` to align; days with no match are filled with 0.
4. `getOverallStats(近 14 天窗口)` (the last-14-days window) yields `totalFee` (reusing the existing statement, not written anew).
5. `aggregateByAgent` / `aggregateByModel` are given the same last-14-days window, and the first 5 of each are mapped into `DashboardRankItem`; the model list takes one column more, `providerName` landing on `qualifier`, while the agent list has no second column and leaves `qualifier` empty.
6. `getTenantAssetCounts(tenantId, activeUserSince)` and `getPlatformCounts()`.
7. `serverTime` = `now` formatted as `yyyy-MM-dd HH:mm:ss`, which the front end uses to render 「数据截至」 ("data as of").

The `from` / `to` parameter naming matches the existing mappers (`startTime`, `endTime`, `tenantId`, with `tenantId` a non-null `Long` — this is the rule the `TokenStatsMapper` interface comment sets: no value means "all tenants").

### 3.5 Controller and authentication

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

- No query parameters at all, and the endpoint signature keeps `overview()` with an empty argument list — the moment a `days` or `tenantId` parameter exists, D3 and D5 are broken.
- `now` is a parameter of the Service rather than `LocalDateTime.now()` inside the Service: the unit test has to be able to assert the four windows on a fixed instant (§6.1), and the many calls within one production day share the same injection path.
- Authentication takes the existing chain: `anyRequest().authenticated()` at `SecurityConfig.kt:52-53` plus `JwtAuthenticationFilter`; no new allow-list entry, and it does not go into `/api/admin/internal/**`.
- The forwarding chain needs no change: local development already proxies `/api/admin/` with a wildcard to `localhost:8080` (`harnax-webui/config/proxy.ts:16-19`), and on the deployment side nginx forwards the whole prefix `location /api/admin/` to admin (`harnax-deploy/nginx.conf:191`).
- Exceptions go uniformly to `ResultVo.error` (HTTP 200 + code 500), the same shape as `TokenStatsController`.

## 4. Front-end page

### 4.1 Structure

```
PageContainer
├─ 问候条        Hi {nickname}｜{租户名}｜数据截至 {serverTime}｜刷新（文字链）
├─ 今日四卡      模型调用 / Token 消耗 / 活跃会话 / 活跃智能体，每卡底部一行环比
├─ 趋势卡        近 14 天双序列折线（调用次数、Token），右下角「近 14 天费用 ¥{fee}」
├─ 榜（两列）    Top 5 智能体｜Top 5 模型，行内条形占比
├─ 租户资产条    8 格：智能体 技能 模型 MCP 服务 渠道 团队 会话 用户
├─ 平台内置行    工具 {n} · CLI 包 {m}，前缀小字「平台级，非本租户」
└─ 待办与入口    技能晋升待审 {k} → /optimization/skill-drafts；近 7 天活跃用户 {a}/{u}
                快速入口三个文字链：创建智能体 / 管理技能 / Token 监控
```

Removed: the six `AnimatedFeatureCard` cards, the `features` array, the four banner tags, `useCountUp`, `useCurrentUser`, and the entry-delay animation of `AnimatedStatCard`.

### 4.2 Component split

`Welcome.tsx` keeps only fetching and orchestration, targeting under 200 lines; the four sub-components go in the sibling directory `src/pages/welcome/`:

| File | Responsibility | Dependencies |
|---|---|---|
| `sections.tsx` | `GreetingBar`, `TodayStatsRow`, `AssetStrip`, `TodoStrip` | Display only, props in |
| `TrendCard.tsx` | The line chart, `Line` from `@ant-design/plots` | An existing dependency, the same one token-monitor uses |
| `RankList.tsx` | The Top rankings, row width normalised to the largest value in the ranking, labels taken from `rankLabels` | Display only |
| `metrics.ts` | `formatCount`, `formatTokens`, `deltaOf`, `deltaTone`, `rankLabels` | Pure functions, unit testable |

The return shape of `deltaOf(current, previous)`: previous is 0 and current is 0 → show 「持平」 ("flat"); previous is 0 and current is greater than 0 → show 「新增」 ("new"), **not +∞% and not 100%**; everything else is `(today - yesterday) / yesterday` as a percentage with one decimal, and **the direction follows that rounded value** — a difference smoothed to `0.0%` reads as flat, and the page must never say `上升 0.0%` ("up 0.0%"). Both halves have to be pinned down in the `metrics.ts` test: a zero baseline is the most common state of a new workspace, and today's tokens differing from yesterday's by a single digit already computes to 0.03%.

The return shape of `rankLabels(items)` (the display name per ranking row): a name appearing more than once in the ranking whose row carries a `qualifier` → `name（qualifier）`; a name that occurs once, or a `qualifier` that is missing or all whitespace → **returned as is**, because inventing a discriminator is worse than two identical rows; an empty `name` → an empty string, which `RankList` renders as 「（已删除）」 ("(deleted)"), and those rows are **kept out of the collision count** — counting them would hang a parenthesis on a real name for no reason. These three rules are pinned in the same `metrics.ts` test: a collision is a shape that exists in the deployed workspace, and on a page holding one row per name "qualify on collision" and "always qualify" are indistinguishable.

### 4.3 service and types

```ts
// src/services/ant-design-pro/dashboard.ts
export async function getDashboardOverview() {
  return request<API.Result<API.DashboardOverview>>('/api/admin/dashboard/overview', {
    method: 'GET',
  });
}
```

`API.DashboardOverview` and its sub-types are appended to `src/typings.d.ts`, with fields corresponding to §3.2 verbatim and numeric values always `number` (a Long is simply a number in JSON). Note that `src/services/**` is excluded from biome (`biome.json:11`), so the types must be written in `typings.d.ts` rather than in the service file.

### 4.4 Fetching and state

A single `useEffect` plus one request, with three states `loading` / `error` / `data`:

- `loading`: section skeletons are shown only while "not a single reading exists yet" (`firstLoad = loading && data === null`) — the four cards and the asset strip get a `Skeleton`, the trend card gets a `Spin`, and nothing is fabricated beyond the skeleton. Once readings have been fetched, a further refresh keeps them in place and the feedback is carried by the spinning icon in the greeting bar plus `loading` on the retry button.
- `error`: one error card for the whole page plus a 「重试」 ("retry") button (reusing the existing key `pages.common.retry`), with no partial degradation (D12). This screen's fetch passes `skipErrorHandler: true`, because umi's global handler would raise a red toast on top of the page's own card and turn one error state into two.
- After success there is no polling and no `setInterval`; refreshing goes through that text link in the greeting bar.
- `code !== 200` counts as an error too, with the check written as `res.code === 200`, same as token-monitor.

### 4.5 Copy and i18n

The new keys land in `src/locales/{zh-CN,en-US}/pages.ts`, and the key sets on the two sides must be exactly the same:

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

Menu key: the two `menu.welcome` lines are replaced by `menu.dashboard` (zh `总览` / en `Overview`).

At `config/routes.ts:25-30` the `name` changes from `welcome` to `dashboard` and `hideInMenu` is dropped; `path` and `component` do not move, and `redirect: '/welcome'` (lines 224-227) does not move.

### 4.6 Styling and responsive

- Keep the glassmorphism: `background: 'var(--glass-bg)'`, `backdropFilter: 'blur(16px)'`, `border: '1px solid var(--glass-border)'`, `boxShadow: 'var(--glass-shadow)'`, the same shape as `SearchFilterBar`; cards can already use the existing `.glass-card` utility class (`global.less:149-155`).
- **Any colour that has to be concatenated with an alpha value must be written in hexadecimal**, not `var(--vip-primary)` (`EntityCard` already fell into this: appending a `15` suffix in a template string does not work against a CSS variable). Plain text colour and borders may use CSS variables, and the dark theme follows automatically.
- Numbers always take `toLocaleString()` thousands separators; Token above 1e6 goes to `M`, above 1e3 goes to `K` (the same rule as token-monitor's `formatToken`, extracted into `metrics.ts` for sharing).
- Responsive: the four cards `xs=24 sm=12 lg=6`; the asset strip `xs=12 sm=6 lg=3`; the two ranking columns stack vertically under `isMobile`. Use the existing `useIsMobile` (`src/utils/responsive.ts:28`).
- On `xs` the line chart turns the legend off and keeps only the tooltip, to avoid crowding on mobile.

## 5. Error handling

| Situation | Behaviour |
|---|---|
| Not signed in / token expired | The existing chain: the token-expiry check in `app.tsx` plus the request interceptor, so the endpoint normally never receives the request. If the token expires after the page is open, this screen fetches with `skipErrorHandler: true` (one error state for the whole page — D12 and the §4.4 line), which also switches off the global 401 → `/login` hop, so the page wires that hop itself: it clears `currentUser`/`tokenInfo` and calls `history.push('/login?redirect=%2Fwelcome')` |
| The table behind one count does not exist (migration not run) | The exception propagates → `ResultVo.error` → a whole-page error state on the front end. Exceptions are not swallowed into a returned 0, because "all zeros" and "could not read it" look identical on the page |
| The tenant cannot be resolved | `TenantResolver.resolve` falls back to `DEFAULT_TENANT_ID = 1`, consistent with the rest of admin's read paths in the repository; the overview is read-only, so the fallback writes no dirty data |
| Consumption whose owning row is deleted | The ranking keeps the value with an empty name, the front end shows 「（已删除）」 ("(deleted)"), and the row is not dropped (the same origin as the LEFT JOIN semantics of `aggregateByModel`) |
| Two same-named entries in one ranking | The model list hangs `（provider）` on those rows and leaves the rest alone; when the model row is deleted its provider goes with it, and those two rows can only look alike — the ranking carries no id because no action on the page goes back to a row, and an id would be preparation for an action that does not exist |

## 6. Tests

### 6.1 Backend unit tests

`DashboardServiceImplTest` (Mockito stubs the two mappers, and `any()` is not used on nullable or primitive-typed parameters — the known silent trap of Kotlin + Mockito):

- Window boundaries: given a fixed `now`, assert that the four string windows handed to the mappers match §2.1 verbatim (including the clock hour of the yesterday-same-span window being equal).
- Zero-padding: the mapper returns 11 rows with 3 days missing, and the assertion is 14 points out with the missing days at 0.
- Ranking truncation: the mapper returns 7 rows, and the assertion is that 5 rows are taken with the order unchanged (the Service does not sort again).
- The ranking's qualifier: model rows carry `providerName`, and the assertion is that each lands on `qualifier` row by row; the agent list goes through the mapping that has no second column, and the assertion is that its `qualifier` is an empty string rather than something filled in from another column.
- Tenant pass-through: `verify` that every mapper call carries the `tenantId` passed in, and that `getPlatformCounts` carries none (guaranteed by its no-argument signature).

### 6.2 Integration tests (real MySQL, testcontainers)

Two classes, extending `BaseAdminIT`, following the precedent of `TokenStatsAggregationIT` by inserting rows with explicit ids directly through `JdbcTemplate`:

**`DashboardOverviewIT`** — asserts **deltas** instead of absolute values, because the "today" window can only land on real time while the shared container keeps writes made by other IT classes in the same JVM. The approach is to call `/api/admin/dashboard/overview` once before seeding and once after, and assert that the increments of `calls` / `tokens` / `sessions` equal exactly the rows just inserted. Insert shape: 3 rows of `token_stats` (2 rows for the same tenant and the same session, 1 row for another session) → assert calls +3, sessions +2, agents +1, tokens + the sum of the three rows. Then insert 1 row of `agent` and assert `assets.agents` +1.

One further case seeds a collision: two `model_provider` rows, two `model` rows that share the display name while their `model_name` differs, and one consumption row per model (with token values large enough to survive the cut — below five, the assertion would be counting an empty list). It asserts that the shared name appears exactly twice in `topModels` with the two providers as their `qualifier`, and that `topAgents` answers an empty `qualifier`. No stub reaches this: every hop from the SQL to the JSON can eat the column, and the unit test only sees the Service's half.

**`DashboardTenantIsolationIT`** — the neighbour tenant uses a synthetic id belonging to this class alone, with `TokenStatsAggregationIT.NEIGHBOUR_TENANT_ID = 930_930L` and `ModelTenantIsolationIT.otherTenant = 940_002L` as precedent; tenants 4 and 5 from the seed data are not used, because their pre-existing rows would turn the delta assertions into a matter of luck. The approach: insert `token_stats` and `agent` rows into the synthetic tenant, take the overview twice with `exchange(..., tenantId = 1)`, and assert that every number of tenant 1 has **not moved by a single character**. This is the only cheap evidence for I1/I2 — remove the tenant predicate from the SQL and these two assertions must go red.

How to run: `mvn -Pintegration-test` (probe Docker availability first, and if Docker is unavailable locally record it explicitly as unverified rather than treating it as a pass). After deleting a source file, run `clean` before rerunning, because a leftover `.class` lets the old route keep answering and produces a false green.

### 6.3 Front-end verification gates

1. `npm run build` (max build, the only reliable type close-out) + `npx @biomejs/biome lint`. `tsc --noEmit` carries a batch of pre-existing noise and is not used as an acceptance check.
2. Real render: start the dev server and drive headless Chromium over the HTTP loopback (the deployment side's https with a self-signed certificate will not open), checking three things — the four cards show real numbers, the day-over-day delta copy appears, and the console has no new errors.
3. Number reconciliation: compare the reading on the page against one hand-written SQL (`SELECT COUNT(*) FROM token_stats WHERE tenant_id = ? AND ts >= CURDATE()`); do not treat "the page rendered" as proof that the data is right.

## 7. Acceptance checklist

- [x] `/api/admin/dashboard/overview` takes no parameters, the response is a `ResultVo`, and `data` carries all 12 top-level fields of §3.2 (`DashboardOverviewIT.responseCarriesEveryFieldWithDefaults` asserts them item by item; the real-database payload has all 12 keys)
- [x] Both ITs run green on real MySQL (`DashboardOverviewIT` 3 items, the isolation class 2), two of them being mutation probes: removing the tenant predicate from the SQL makes exactly the neighbour case red (`Tests run: 9, Failures: 1`), and dropping `providerName` from the model ranking makes exactly the same-name case red (`Tests run: 3, Failures: 1`, actual `<[, ]>`)
- [x] No hardcoded statistic number appears in `Welcome.tsx` any more, and `grep -nE 'value=\{[0-9]+\}' src/pages/Welcome.tsx` has zero hits (0)
- [x] The six platform capability cards and the banner tags are deleted, and the `features` array leaves no residue (`AnimatedFeatureCard` has zero hits under `src/`; `features` has zero hits in `src/pages/Welcome.tsx` and `src/pages/welcome/` — the two same-named variables at `src/pages/user/login/index.tsx:223,802` belong to the login page itself and have nothing to do with this page)
- [x] `useCountUp` / `useCurrentUser` are removed from the page, and `localStorage.getItem('currentUser')` has zero hits under `src/pages/` (all three items 0)
- [x] 「总览 / Overview」 appears at the top of the menu and points at `/welcome`, `/` still redirects to it, and the post-login landing page is unchanged (`menu.dashboard` = zh 总览 / en Overview; the `/login` block before `/welcome` carries `layout: false` (`routes.ts:16`) and renders no menu, so this is the first item in the sidebar menu; `path: '/'` + `redirect: '/welcome'` at `routes.ts:225-226` is untouched)
- [x] The set difference between the zh and en `pages.welcome.*` keys is empty (38 on each side, empty in both directions, and all 38 have consumers)
- [x] `max build` and `biome lint` are green; headless Chromium re-checked the real numbers (8 build rounds with `EXIT=0`; the narrow-scope lint reported `Checked 7 files` with zero diagnostics; the readings in the 1400 and 390 screenshots match the hand-written SQL verbatim)
- [x] The tools and CLI cells are clearly marked as platform-level on the page, not in the same row as the tenant assets (a row of its own after `Divider`, the title immediately followed by the small print 「平台级，非本租户」 ("platform-wide, not this tenant"))
- [x] Same-named entries in one ranking are distinguishable on the page: headless Chromium reads the four model rows as `qwen3.7-flash（阿里百炼）` / `qwen3.7-flash（内部网关）` / `gpt-5` / `（已删除）` — the two colliding rows each carry their provider, the row that collides with nothing is untouched, and the empty-name row is kept out of the collision count. The fixture is `logs/payload.json` with `qualifier` added: the two colliding token values (250.71K / 180.00K) come from the payload really produced against the deployed database, while the two provider names are invented in the fixture, because the read-only query on that database was refused by the permission layer and the real names could not be retrieved. `rankLabels` was probed in both directions: removing the collision test turns 2 items red, never qualifying turns 1 red, and all 19 are green
- [x] No new nginx / proxy / allow-list rule on the deployment side (reusing the whole `/api/admin/` prefix forwarding — `git status --short -- harnax-deploy` is empty, and `location /api/admin/` at `nginx.conf:191` already covers it)

## 8. Out of scope

- Gateway-side metrics from `api_call_log` (QPS, success rate, P95 latency) — reading that table across services needs the internal-key chain and a new endpoint on the router side.
- A time-window selector, auto refresh, metric alerting, export.
- A tenant-switching entry (`TenantSwitcher` is hidden as a whole today; this round does not touch it).
- A super-admin cross-tenant global view and inter-tenant rankings.
- The precision problem of the `fee` column `decimal(10,0)` (a data-model matter; changing it means touching an already-applied Flyway baseline).
- Refactoring the `token-monitor` page itself.

## 9. Implementation order

1. `DashboardMapper.kt` + `DashboardMapper.xml` (including the `tenantActive` fragment and the two counting statements)
2. The two new methods on `TokenStatsMapper` and their XML (`getDashboardWindowStats`, `getDashboardDailyTrend`)
3. `DashboardOverviewResponse` DTO + `DashboardService` / `Impl` + `DashboardServiceImplTest`
4. `DashboardController` + the two ITs
5. The front-end `dashboard.ts` service + the `typings.d.ts` types + `metrics.ts` and its unit test
6. The four sub-components in `src/pages/welcome/` + the rewrite of `Welcome.tsx` + i18n on both sides + `routes.ts` and the menu key
7. Verification gates: the two ITs, `max build`, `biome lint`, the headless Chromium real render and the SQL reconciliation
