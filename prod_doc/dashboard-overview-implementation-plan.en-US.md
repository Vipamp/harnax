# Landing Page Operations Overview: Implementation Plan

**Goal:** replace the screen of hard-coded fake numbers on `harnax-webui`'s `/welcome` landing page with a zero-configuration tenant operations overview, where every reading on screen comes out of one real aggregate read inside the admin process.

**Architecture:** the backend adds exactly one read-only endpoint, `GET /api/admin/dashboard/overview`; it takes no query parameters, the tenant is resolved from the request's own credentials, and the four time windows are derived up front from a single clock read; the aggregate reads land in `TokenStatsMapper` (reusing the existing tenant fragment) and the new `DashboardMapper` (asset inventory), with no table created, no column added and Flyway untouched. On the frontend `Welcome.tsx` only fetches and orchestrates, the display is split into four sub-components fed by props, and the whole page has one error state.

**Tech stack:** Kotlin 2.2.21 + Spring Boot + MyBatis XML + Testcontainers MySQL 8 (IT); UmiJS max + Ant Design Pro + antd 5.25.4 + `@ant-design/plots` 2.6.8 + dayjs; biome lint, jest.

> **Positioning of this document:** it is an executable implementation checklist, ticked task by task. It is self-contained — reading it requires no other document.
>
> **For agentic workers:** running it task by task with superpowers:subagent-driven-development or superpowers:executing-plans is recommended; steps use `- [ ]` checkboxes, and tasks already landed are ticked with their landing spots attached.

---

## 1. Settled decisions (every one is verifiable; do not re-litigate them in the implementation)

| # | Decision | Where it lands |
|---|---|---|
| C1 | The page form is an operations overview dashboard, not a product introduction page; a zero-configuration single screen, with deep analysis under `监控与治理 → Token 监控` (Monitoring & Governance → Token Monitoring) | `src/pages/Welcome.tsx` |
| C2 | There is exactly one aggregate endpoint, at path `GET /api/admin/dashboard/overview` | `DashboardController.kt` |
| C3 | The endpoint takes no query parameter at all: tenant, window, list length and day count are never named by the client | same as above |
| C4 | `今日请求` (today's requests) = today's model call count = the number of `token_stats` rows inside the window; `api_call_log` is not read across services. The card is labelled `模型调用` / "Model calls", never "requests" | `getDashboardWindowStats`, `pages.welcome.today.calls` |
| C5 | The windows are hard-coded: today 00:00 → this instant, and the last 14 days | `DashboardServiceImpl` |
| C6 | The day-over-day baseline is "yesterday 00:00 → yesterday at the same clock time", not the whole of yesterday | same as above |
| C7 | The active-user window is 7 days, and only the lower bound is taken | second parameter of `getTenantAssetCounts` |
| C8 | Numbers are produced for the current tenant only; there is no global administrator view | `TenantResolver.resolve(jwtUtil)` |
| C9 | The four today cards = `模型调用` (model calls) / `Token 消耗` (token consumption) / `活跃会话` (active sessions) / `活跃智能体` (active agents); cost appears only as the 14-day total | `TodayStatsRow`, `TrendCard` |
| C10 | The tenant asset strip has 8 cells; tools and CLI packages are platform-level, so they get their own row marked `非本租户` (not this tenant) | `AssetStrip` |
| C11 | New `token_stats` SQL goes into `TokenStatsMapper`, keeping the tenant predicate at a single home: `tenantAndTimeWindow` | `TokenStatsMapper.xml` |
| C12 | One error state for the whole page + one retry; no per-section degradation, no polling | `Welcome.tsx` |
| C13 | The frontend adds no new data-fetching dependency and no npm / Maven packages | everything |
| C14 | No tenant-switching entry point is provided (`TenantSwitcher` itself returns null for every version) | the component is left alone |
| C15 | The `/welcome` path is kept, `hideInMenu` is removed, and the menu name `menu.welcome` → `menu.dashboard` | `config/routes.ts`, both `menu.ts` |

## 2. Invariants (break one and the tests go red immediately)

- **I1 The tenant predicate lives in exactly two places**: the `tenantAndTimeWindow` fragment in `TokenStatsMapper.xml` and the `tenantActive` fragment in `DashboardMapper.xml`. Every cell added must include one of them; the only exemptions are `skill_draft` (that table has no `active` column, its soft delete lives in `status`) and `getPlatformCounts` (those two tables have no `tenant_id` at all).
- **I2 A row with `tenant_id` NULL belongs to no workspace**: both `token_stats.tenant_id` and `sys_user.tenant_id` are nullable, and equality comparison naturally excludes them.
- **I3 An empty window returns all zeros + 14 zero day points**, not missing fields and not an error code.
- **I4 Time has local wall-clock semantics**: window boundaries are formatted as `yyyy-MM-dd HH:mm:ss` strings and compared against `DATETIME` columns, both sides in the same zone — `harnax-deploy/Dockerfile.admin` sets `TZ=Asia/Shanghai` and links `/etc/localtime`, the compose file gives the MySQL container the same `TZ: Asia/Shanghai`, and the datasource URL carries `serverTimezone=Asia/Shanghai`. The whole chain performs no UTC conversion, so "today" is one day in the operator's own timezone.
- **I5 One request reads the clock exactly once**: `LocalDateTime.now()` appears only in the Controller and is passed downwards; calling it again inside the Service is forbidden.
- **I6 Truncation and sorting of the lists both happen server-side**: `take(5)` takes the head of what SQL has already ordered by `grandTotalToken DESC`; neither the frontend nor the service layer re-sorts.

## 3. Global constraints

- No table is created and no column is added; not a single byte of `harnax-admin/src/main/resources/db/migration/**` changes (an already-applied Flyway migration cannot be touched even in its comments).
- No new dependency: no Maven coordinate on the backend, and `package.json` untouched on the frontend.
- Backend comments / KDoc / log strings are always English; the frontend follows the existing Chinese-comment style of webui.
- Any new frontend copy must land in both `src/locales/zh-CN/pages.ts` and `src/locales/en-US/pages.ts`; a missing side counts as unfinished.
- DTO fields are always non-null with default values: admin's Jackson 3 drops null keys, and the frontend cannot tell "this key is absent" from "the value is 0".
- `src/services/**` is excluded from biome (`biome.json`), so the types must be written into `src/typings.d.ts`.
- Local Maven recipe: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`, `mvn` is `/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn`, and after any Kotlin change `spotless:apply` must run first; every command goes `> x.log 2>&1; echo EXIT=$?` and then the log is read, so a pipe never swallows the exit code.
- The workspace is shared and carries uncommitted changes: the executor must not `git add` / `commit` / `push` / `stash` / `clean` / `checkout --`; committing is decided by the controller together with the user.

## 4. File structure

| File | Action | Responsibility |
|---|---|---|
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt` | new | the wire contract + row→field mapping (`mapTo*` / `*Of`) |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/DashboardService.kt` | new | the `overview(tenantId, now)` interface |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImpl.kt` | new | derivation of the four windows, trend zero-padding, list truncation |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/DashboardController.kt` | new | tenant resolution, one clock read, the `ResultVo` envelope and whole-page failure degradation |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/DashboardMapper.kt` | new | the two reads: asset inventory and platform registries |
| `harnax-entity/src/main/resources/mapper/DashboardMapper.xml` | new | the `tenantActive` fragment + the scalar-subquery shape |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/TokenStatsMapper.kt` | append | two new methods, with a comment on why they are not in `DashboardMapper` |
| `harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml` | append | `getDashboardWindowStats`, `getDashboardDailyTrend` |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImplTest.kt` | new | window boundaries, tenant passing, zero-padding, truncation, empty tenant |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardOverviewIT.kt` | new | delta assertions on real MySQL + payload completeness |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardTenantIsolationIT.kt` | new | the tenant predicate: a neighbouring tenant's volume does not move this tenant's readings |
| `harnax-webui/src/services/ant-design-pro/dashboard.ts` | new | the only data-fetching function |
| `harnax-webui/src/typings.d.ts` | append | `API.DashboardOverview` and its sub-types |
| `harnax-webui/src/pages/welcome/metrics.ts` | new | the pure functions `formatCount` / `formatTokens` / `deltaOf` / `deltaTone` |
| `harnax-webui/src/pages/welcome/metrics.test.ts` | new | the four boundary shapes of the day-over-day delta + formatting |
| `harnax-webui/src/pages/welcome/sections.tsx` | new | `GreetingBar` / `TodayStatsRow` / `AssetStrip` / `TodoStrip` / `glassCardStyle` |
| `harnax-webui/src/pages/welcome/TrendCard.tsx` | new | the 14-day two-series line chart + the cost total |
| `harnax-webui/src/pages/welcome/RankList.tsx` | new | the Top list, with row widths normalised to the maximum inside the list |
| `harnax-webui/src/pages/Welcome.tsx` | rewrite | fetching, orchestration, whole-page error state |
| `harnax-webui/src/locales/{zh-CN,en-US}/pages.ts` | append | `pages.welcome.*` |
| `harnax-webui/src/locales/{zh-CN,en-US}/menu.ts` + `config/routes.ts` | modify | menu name and entry visibility |

---

## Task 1: The wire-contract DTO

**Files:** Create `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt`

**Interfaces:** Produces `DashboardOverviewResponse` (the fields in the table below), `DashboardWindowStats`, `DashboardTrendPoint`, `DashboardRankItem`, `DashboardAssetCounts`, `DashboardPlatformCounts`, plus the companion mapping functions `mapToWindowStats` / `mapToTrendPoint` / `emptyTrendPoint` / `mapToRankItem` / `mapToAssetCounts` / `mapToPlatformCounts` / `pendingSkillDraftsOf` / `totalUsersOf` / `activeUsersOf` / `recent14dFeeOf`, all of which accept a nullable `Map<String?, Any?>?`.

- [x] **Step 1: write the data class with a default value on every field**

```kotlin
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
) : Serializable
```

| Field | Type | Meaning |
|---|---|---|
| `today` / `yesterdaySameSpan` | `DashboardWindowStats` | the four counts `calls` / `tokens` / `sessions` / `agents` |
| `trend` | `List<DashboardTrendPoint>` | always 14 points, `date` is `yyyy-MM-dd`, missing days padded with 0 |
| `topAgents` / `topModels` | `List<DashboardRankItem>` | always ≤5, already sorted, empty `name` = the owning row has been deleted |
| `assets` | `DashboardAssetCounts` | the 8 tenant asset cells |
| `platform` | `DashboardPlatformCounts` | `tools`, `cliPackages`, platform-level |
| `pendingSkillDrafts` | `Long` | this tenant's pending drafts |
| `totalUsers` / `activeUsersLast7Days` | `Long` | the denominator and the active count |
| `recent14dFee` | `BigDecimal` | the 14-day cost total, in yuan, with no invented precision |
| `serverTime` | `String` | the measurement instant of all windows, `yyyy-MM-dd HH:mm:ss` |

- [x] **Step 2: the mapping functions collapse "key missing / SQL NULL / driver type mismatch" into 0 in every case**, `(map?.get(key) as? Number)?.toLong() ?: 0L`; list items deliberately carry no id (both lists are pure display — there is no action on the page that goes back to that row).
- [x] **Step 3: it compiles after `spotless:apply`** (acceptance check: `harnax-admin/target/classes/.../DashboardOverviewResponse.class` exists).

## Task 2: `DashboardMapper` and the tenant asset inventory

**Files:** Create `harnax-entity/.../mapper/DashboardMapper.kt`, `harnax-entity/src/main/resources/mapper/DashboardMapper.xml`

**Interfaces:** Consumes the tables `agent`/`skill`/`model`/`mcp_server`/`channel`/`team`/`session`/`sys_user`/`skill_draft`/`agent_tool`/`cli`; Produces `getTenantAssetCounts(tenantId: Long, activeUserSince: String): MutableMap<String?, Any?>?`, `getPlatformCounts(): MutableMap<String?, Any?>?`.

- [x] **Step 1: write the predicate fragment exactly once**

```xml
<sql id="tenantActive">WHERE 1 = 1 AND tenant_id = #{tenantId} AND active = 1</sql>
```

- [x] **Step 2: one row of scalar subqueries answers every cell** (the shape already used in `ModelMapper.xml`, which guarantees a row is always returned), with `<include refid="tenantActive"/>` in each cell; `activeUsers` appends `AND last_login_time &gt;= #{activeUserSince}` after the fragment; `pendingSkillDrafts` uses `tenant_id = #{tenantId} AND status = 'PENDING'`.
- [x] **Step 3: the platform row carries no tenant predicate**: `SELECT (SELECT COUNT(*) FROM agent_tool WHERE active = 1) AS tools, (SELECT COUNT(*) FROM cli WHERE active = 1) AS cliPackages`.
- [x] **Step 4: precondition verification** (done before writing any code; the conclusions are fixed)
  - All 8 asset tables **have** both `tenant_id` and `active` in `V1__init_schema.sql`; `skill_draft` has only `status varchar(16)` and no `active`; `sys_user.last_login_time` exists (nullable `datetime`).
  - `agent_tool` and `cli` have no `tenant_id` column → those two numbers can only be shown at platform level.

## Task 3: The two new `token_stats` reads

**Files:** Modify `harnax-entity/.../mapper/TokenStatsMapper.kt`, `harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml` (append only, existing statements untouched)

**Interfaces:** Produces `getDashboardWindowStats(startTime: String?, endTime: String?, tenantId: Long): MutableMap<String?, Any?>?`, `getDashboardDailyTrend(...): MutableList<MutableMap<String?, Any?>?>?`; Consumes the existing fragments `tenantAndTimeWindow` (parameter `alias`) and `dayBucket`.

- [x] **Step 1: the window read**

```xml
<select id="getDashboardWindowStats" resultType="map">
    SELECT COUNT(*) AS calls,
    COALESCE(SUM(total_token), 0) AS tokens,
    COUNT(DISTINCT session_id) AS sessions,
    COUNT(DISTINCT agent_id) AS agents
    FROM token_stats t
    WHERE 1 = 1
    <include refid="tenantAndTimeWindow">
        <property name="alias" value="t"/>
    </include>
</select>
```

- [x] **Step 2: the daily-trend read** — the `dayBucket` result `AS timePoint` + `COUNT(*) AS calls` + `COALESCE(SUM(total_token), 0) AS tokens`, `GROUP BY timePoint ORDER BY timePoint`. The column aliases are the wire contract, so neither the fragment nor the aliases may be changed.
- [x] **Step 3: why it lives here**: the interface comment states it — so the tenant predicate keeps exactly one home, `tenantAndTimeWindow`, instead of a second copy being written into another mapper.

## Task 4: Window derivation and assembly in the service layer

**Files:** Create `.../service/DashboardService.kt`, `.../service/impl/DashboardServiceImpl.kt`

**Interfaces:** Consumes all the mapper methods of tasks 2 and 3 plus the existing `aggregateByAgent` / `aggregateByModel` / `getOverallStats`; Produces `overview(tenantId: Long, now: LocalDateTime): DashboardOverviewResponse`.

- [x] **Step 1: the four windows derive from the same `now`**

```kotlin
val todayStart = now.toLocalDate().atStartOfDay()
val todayFrom = timestamp(todayStart);            val todayTo = timestamp(now)
val yesterdayFrom = timestamp(todayStart.minusDays(1)); val yesterdayTo = timestamp(now.minusDays(1))
val trendStart = todayStart.minusDays((TREND_DAYS - 1).toLong())  // 14 点，含今日
val trendFrom = timestamp(trendStart);            val trendTo = todayTo
val activeUserSince = timestamp(now.minusDays(ACTIVE_USER_DAYS))  // 7 天，只有下界
```

- [x] **Step 2: trend zero-padding is keyed by day**, taking the row key from the day part of `timePoint` (all three shapes are accepted: `LocalDateTime`, `java.sql.Timestamp` and a `yyyy-MM-dd HH:mm:ss` string; any other type drops the row); the constants are `TREND_DAYS = 14`, `ACTIVE_USER_DAYS = 7L`, `RANK_LIMIT = 5`.
- [x] **Step 3: the lists only `take(5)` and are never re-sorted**; the cost reuses `totalFee` from `getOverallStats`.
- [x] **Step 4: `LocalDateTime.now` never appears inside the class** (I5), and `serverTime` is filled back from `todayTo`.

## Task 5: The controller

**Files:** Create `.../controller/DashboardController.kt`

- [x] **Step 1: one GET with no parameters**

```kotlin
fun overview(): ResultVo<DashboardOverviewResponse> = try {
    ResultVo.success(dashboardService.overview(TenantResolver.resolve(jwtUtil), LocalDateTime.now()))
} catch (e: Exception) {
    log.error("Failed to build dashboard overview", e)
    ResultVo.error("Failed to get overview data")
}
```

- [x] **Step 2: the security configuration needs no change** (verified): the skip list of `JwtAuthenticationFilter` does not contain `dashboard`, and `SecurityConfig`'s `anyRequest().authenticated()` covers the path; `harnax-deploy/nginx.conf` already has `location /api/admin/`, so no route has to be added.

## Task 6: Service-layer unit tests

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImplTest.kt`

- [x] **Step 1: six cases covering I1/I3/I5/I6** — window boundaries asserted second by second (with `now` pinned to `2026-10-05 13:45:30`), tenant parameter passing together with "the platform read takes no tenant", 14-point zero-padding (3 days deliberately missing, 1 day carrying hour and minute), list truncation without re-sorting (row order deliberately not descending), field↔row mapping (including a row whose name is `null`), and an empty tenant answering all zeros + 14 zero day points.
- [x] **Step 2: stubs always use argument wildcards (`anyOrNull()` / `anyLong()`), while the actual arguments are asserted separately with `verify` + a captor** — because this service defaults to 0 everywhere, a stub that fails to match would still come out green, so the boundaries handed to the mapper must be pulled out and checked on their own.
- [x] **Step 3: `@MockitoSettings(strictness = Strictness.LENIENT)` plus building the service by hand through its constructor**, not `@InjectMocks` (adding one constructor parameter would turn the whole class red).

## Task 7: IT against a real database (overview)

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardOverviewIT.kt`

- [x] **Step 1: every assertion is a delta, never an absolute value** — "today" can only be measured against the wall clock, and the shared container still holds rows written by other cases of the same class, so an absolute value would be a number nobody ever chose. The method: read the endpoint → seed → read again, and require the difference to equal exactly what was just written.
- [x] **Step 2: 8 items covered**: the delta of each of today's calls/tokens/sessions/agents, the `yesterdaySameSpan` delta always 0 (catches "the lower bound forgot to subtract a day"), the 14-day cost total delta, today's trend-point delta (catches a `timePoint` type mismatch silently dropping the row), the asset-cell delta, one extra agent moving only the assets and not the consumption, every block and every field of the payload present and non-null, and list length ≤5.
- [x] **Step 3: `@AfterEach` cleans up precisely by `tenant_id + session_id IN (...)`**, using the id range 960_001/960_002 and a dedicated sessionId so other classes' fixtures are not touched.

## Task 8: Tenant isolation IT

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardTenantIsolationIT.kt`

**Interfaces:** Consumes `getJson(path, tenantId)` from `BaseAdminIT` (an admin token carrying `X-Tenant-ID` is enough to switch workspace, because the membership check is skipped for admins) and `assertOk`; Produces nothing.

**Why this needs a class of its own:** tasks 2 and 3 each add new aggregate reads, and the two predicates are written separately; only one class that simultaneously proves "this tenant's readings are not contaminated by the neighbour" and "the two platform cells should never be contaminated in the first place" counts as holding I1.

- [x] **Step 1: write the whole class**

Fixture notes (all verified): the neighbour tenant id is `950_500L` (zero hits across the whole repository, so it does not collide with `930_930L` from `TokenStatsAggregationIT` or `940_002L` from `ModelTenantIsolationIT`); `token_stats` has no foreign key and both `tenant_id` and `ts` are nullable, so it can be seeded directly. I2's "a row whose tenant is NULL belongs to nobody" can only be proven on these two tables — `agent.tenant_id` is `NOT NULL DEFAULT '1'`, so a NULL cannot be squeezed in there. `sys_user.tenant_id` is nullable, but the five columns `username`, `password`, `nickname`, `email`, `phone` are NOT NULL without defaults, and `active_username` is a unique key generated from `active`, so the username must be exclusively owned. Asset cells and consumption readings always go through deltas, so leftovers from other cases cannot affect them.

```kotlin
package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overview shows one workspace's numbers and no other's (design 6.2, invariants I1 and I2).
 *
 * Two directions are asserted, and the second one is what makes the first worth having:
 *
 * 1. The neighbour tenant's own rows are visible to the neighbour. Without this, a seed that silently
 *    landed nowhere would leave the "nothing moved" assertion below passing on an empty database.
 * 2. Tenant 1's response is byte-identical before and after the neighbour's rows appear, field for field
 *    including the nested blocks and both rankings.
 *
 * A third case covers I2 on its own terms: rows whose `tenant_id` is null, which equality excludes from
 * both workspaces.
 *
 * The neighbour id is synthetic and belongs to this class alone — `TokenStatsAggregationIT` holds 930_930
 * and `ModelTenantIsolationIT` holds 940_002, and the tenants in the seed data (4 and 5) carry rows this
 * class does not control, which would turn a difference assertion into a guess. The row ids this class
 * writes are its own band too, 961_001 upwards, so no primary key of another fixture is ever overwritten.
 * Every tenant-scoped table named by the asset query gets one row, so dropping the shared `tenantActive`
 * fragment from any single cell of that projection shows up as a changed number rather than a coincidence.
 */
class DashboardTenantIsolationIT : BaseAdminIT() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun clearNeighbourRows() {
        jdbc.update("DELETE FROM token_stats WHERE tenant_id = ? OR session_id = ?", NEIGHBOUR_TENANT_ID, UNOWNED_SESSION_ID)
        jdbc.update("DELETE FROM session WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM agent WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM skill WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM skill_draft WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM `model` WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM mcp_server WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM channel WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM team WHERE tenant_id = ?", NEIGHBOUR_TENANT_ID)
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ? OR id = ?", NEIGHBOUR_TENANT_ID, UNOWNED_USER_ID)
    }

    /** One of everything the overview counts, written into the neighbour's name and nowhere else. */
    private fun seedNeighbourRows() {
        val now = LocalDateTime.now().format(TIMESTAMP)

        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Neighbour Agent', 'neighbour', 1)",
            NEIGHBOUR_AGENT_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO agent (id, tenant_id, name, creator, active) VALUES (?, ?, 'Neighbour Second Agent', 'neighbour', 1)",
            NEIGHBOUR_SECOND_AGENT_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO session (id, tenant_id, title, session_id, agent_id, creator, active) VALUES (?, ?, 'Neighbour Session', ?, ?, 'neighbour', 1)",
            NEIGHBOUR_SESSION_ROW_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_SESSION_ID,
            NEIGHBOUR_AGENT_ID,
        )
        jdbc.update(
            "INSERT INTO skill (id, tenant_id, name, repository_id, creator, active) VALUES (?, ?, 'Neighbour Skill', ?, 'neighbour', 1)",
            NEIGHBOUR_SKILL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_REPOSITORY_ID,
        )
        jdbc.update(
            "INSERT INTO `model` (id, tenant_id, name, model_name, provider_id, model_type, active) VALUES (?, ?, 'Neighbour Model', 'neighbour-model', ?, 'chat', 1)",
            NEIGHBOUR_MODEL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_PROVIDER_ID,
        )
        jdbc.update(
            "INSERT INTO mcp_server (id, tenant_id, name, type, active) VALUES (?, ?, 'Neighbour MCP', 'stdio', 1)",
            NEIGHBOUR_MCP_ID,
            NEIGHBOUR_TENANT_ID,
        )
        jdbc.update(
            "INSERT INTO channel (id, tenant_id, name, type, agent_id, callback_key, session_id, active) " +
                "VALUES (?, ?, 'Neighbour Channel', 'http', ?, 'neighbour-callback-key', ?, 1)",
            NEIGHBOUR_CHANNEL_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_AGENT_ID,
            NEIGHBOUR_SESSION_ID,
        )
        jdbc.update(
            "INSERT INTO team (id, tenant_id, name, system_prompt, model_id, active) VALUES (?, ?, 'Neighbour Team', 'Rules', ?, 1)",
            NEIGHBOUR_TEAM_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_MODEL_ID,
        )
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active, last_login_time) " +
                "VALUES (?, ?, 'neighbour_user_it', 'x', 'Neighbour', 'neighbour@example.com', '000', 1, ?)",
            NEIGHBOUR_USER_ID,
            NEIGHBOUR_TENANT_ID,
            now,
        )
        jdbc.update(
            "INSERT INTO skill_draft (id, tenant_id, name, skillmd, source_session_id, status) " +
                "VALUES (?, ?, 'neighbour-draft', 'body', ?, 'PENDING')",
            NEIGHBOUR_DRAFT_ID,
            NEIGHBOUR_TENANT_ID,
            NEIGHBOUR_SESSION_ID,
        )
        // Consumption dated today, since that is the window the four cards measure.
        insertNeighbourStats(NEIGHBOUR_SESSION_ID, NEIGHBOUR_AGENT_ID, 400L, now)
        insertNeighbourStats("neighbour-other-session", NEIGHBOUR_SECOND_AGENT_ID, 500L, now)
    }

    private fun insertNeighbourStats(
        sessionId: String,
        agentId: Long,
        tokens: Long,
        ts: String,
    ) {
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (?, ?, ?, NULL, ?, 0, ?, 0, ?)",
            NEIGHBOUR_TENANT_ID,
            agentId,
            sessionId,
            tokens,
            tokens,
            ts,
        )
    }

    @Test
    @DisplayName("another workspace's rows move none of this one's numbers, in either direction")
    fun neighbourRowsStayOutOfTheOverview() {
        val before = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))

        seedNeighbourRows()

        // Direction one: the neighbour sees its own rows. If this reads as nothing, the seed went nowhere
        // and direction two below would pass without proving anything about the predicates.
        val asNeighbour = assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))
        assertEquals(2L, asNeighbour["today"]["calls"].asLong(), "two calls were written for the neighbour")
        assertEquals(900L, asNeighbour["today"]["tokens"].asLong(), "and 900 tokens")
        assertEquals(2L, asNeighbour["today"]["sessions"].asLong(), "over two conversations")
        assertEquals(2L, asNeighbour["today"]["agents"].asLong(), "on two agents")
        assertEquals(2L, asNeighbour["assets"]["agents"].asLong(), "the neighbour owns two agents")
        assertEquals(1L, asNeighbour["assets"]["skills"].asLong(), "one skill")
        assertEquals(1L, asNeighbour["assets"]["models"].asLong(), "one model")
        assertEquals(1L, asNeighbour["assets"]["mcpServers"].asLong(), "one MCP server")
        assertEquals(1L, asNeighbour["assets"]["channels"].asLong(), "one channel")
        assertEquals(1L, asNeighbour["assets"]["teams"].asLong(), "one team")
        assertEquals(1L, asNeighbour["assets"]["sessions"].asLong(), "one session row")
        assertEquals(1L, asNeighbour["assets"]["users"].asLong(), "one user")
        assertEquals(1L, asNeighbour["totalUsers"].asLong(), "the same user, as the todo line's denominator")
        assertEquals(1L, asNeighbour["activeUsersLast7Days"].asLong(), "who logged in inside the 7-day window")
        assertEquals(1L, asNeighbour["pendingSkillDrafts"].asLong(), "and one draft waiting for review")

        // Direction two: nothing about this tenant moved. Compared as whole payloads rather than field by
        // field, so a change in any cell — a ranking entry appearing, an order flipping — fails too. Only
        // the measured instant differs between two reads, so that one key is set aside and checked apart.
        val after = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))
        assertTrue(after["serverTime"].asText().isNotEmpty(), "the clock still answers")
        assertEquals(withoutClock(before), withoutClock(after), "every number of tenant 1 is unmoved by another tenant's rows")

        // And the platform registries, which carry no tenant at all, answer the same for both callers.
        assertEquals(before["platform"].toString(), asNeighbour["platform"].toString(), "tools and CLI packages are platform-wide")
    }

    /**
     * A row nobody owns is nobody's number (invariant I2).
     *
     * `token_stats.tenant_id` and `sys_user.tenant_id` are both nullable and equality excludes null, so the
     * two rows below belong to no workspace. The case is here for the shape that would break it: a clause
     * added to "also show the unassigned rows" turns one of these into every page's consumption, and the
     * only way to see that is to measure two workspaces against their own baselines.
     */
    @Test
    @DisplayName("a row with no tenant is counted for no workspace")
    fun unownedRowsBelongToNoWorkspace() {
        val ownBefore = assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))
        val neighbourBefore = assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))

        val now = LocalDateTime.now().format(TIMESTAMP)
        // Dated today, and carrying an agent id rather than null, so a leak would move both cells at once.
        jdbc.update(
            "INSERT INTO token_stats (tenant_id, agent_id, session_id, chat_model_id, input_token, output_token, total_token, fee, ts) " +
                "VALUES (NULL, ?, ?, NULL, 700, 0, 700, 0, ?)",
            NEIGHBOUR_AGENT_ID,
            UNOWNED_SESSION_ID,
            now,
        )
        jdbc.update(
            "INSERT INTO sys_user (id, tenant_id, username, password, nickname, email, phone, active, last_login_time) " +
                "VALUES (?, NULL, 'unowned_dashboard_it_user', 'x', 'Unowned', 'unowned@example.com', '000', 1, ?)",
            UNOWNED_USER_ID,
            now,
        )

        // Positive control: both rows really are in the tables. Without this the untouched payloads below
        // would also be produced by a seed that landed nowhere.
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM token_stats WHERE session_id = ?", Int::class.java, UNOWNED_SESSION_ID),
            "the unowned consumption row exists",
        )
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id = ?", Int::class.java, UNOWNED_USER_ID),
            "and so does the unowned user row",
        )

        assertEquals(
            withoutClock(ownBefore),
            withoutClock(assertOk(getJson(OVERVIEW, tenantId = OWN_TENANT_ID))),
            "neither the unowned call nor the unowned user reaches this workspace's page",
        )
        assertEquals(
            withoutClock(neighbourBefore),
            withoutClock(assertOk(getJson(OVERVIEW, tenantId = NEIGHBOUR_TENANT_ID))),
            "and neither of them reaches another workspace's page",
        )
    }

    /**
     * The payload with the read's own instant taken out, as a string two reads can be compared on.
     *
     * `serverTime` is the only field two calls are allowed to differ in — it is the instant the numbers
     * were measured at, and the second read happens seconds later. Everything else in the tree, nested
     * blocks and lists included, has to match character for character.
     */
    private fun withoutClock(node: JsonNode): String = (node as ObjectNode).deepCopy().apply { remove("serverTime") }.toString()

    private companion object {
        /** The workspace the overview is read for: the admin token's own, which is also the resolver's default. */
        const val OWN_TENANT_ID = 1L

        /** A tenant nothing else in the suite belongs to. */
        const val NEIGHBOUR_TENANT_ID = 950_500L

        const val OVERVIEW = "/api/admin/dashboard/overview"

        const val NEIGHBOUR_AGENT_ID = 961_001L
        const val NEIGHBOUR_SECOND_AGENT_ID = 961_002L
        const val NEIGHBOUR_SESSION_ROW_ID = 961_003L
        const val NEIGHBOUR_SKILL_ID = 961_004L
        const val NEIGHBOUR_MODEL_ID = 961_005L
        const val NEIGHBOUR_PROVIDER_ID = 961_006L
        const val NEIGHBOUR_MCP_ID = 961_007L
        const val NEIGHBOUR_CHANNEL_ID = 961_008L
        const val NEIGHBOUR_TEAM_ID = 961_009L
        const val NEIGHBOUR_USER_ID = 961_010L
        const val NEIGHBOUR_DRAFT_ID = 961_011L
        const val NEIGHBOUR_REPOSITORY_ID = 961_012L

        const val NEIGHBOUR_SESSION_ID = "dashboard-isolation-neighbour-session"

        /** The unowned row's own handles: nothing but this class can delete a null-tenant row by tenant. */
        const val UNOWNED_USER_ID = 961_013L

        const val UNOWNED_SESSION_ID = "dashboard-isolation-unowned-session"

        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
```

- [x] **Step 2: format**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/heqingsong/code/my_project/harnax/tmp/worktrees/dashboard-overview
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -q spotless:apply -pl harnax-entity,harnax-admin > /tmp/spotless.log 2>&1; echo EXIT=$?
```
Expected: `EXIT=0`, and no `format violations` in the log.

- [x] **Step 3: run test-compile on its own before starting the full build**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -q -pl harnax-admin -am test-compile > /tmp/dash-tc.log 2>&1; echo EXIT=$?
```
Expected: `EXIT=0`. A compile failure empties `failsafe-reports`, and then "0 items" is not green.

- [x] **Step 4: run the two IT classes**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am -Pintegration-test \
  -Dit.test='DashboardOverview*,DashboardTenantIsolation*' \
  -Dtest='Dashboard-none' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify > /tmp/dash-it.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-it.log | tail -5
```
Expected: `EXIT=0`, and the log showing `Tests run: 2, Failures: 0, Errors: 0` (`DashboardOverviewIT`) and `Tests run: 2, Failures: 0, Errors: 0` (the isolation class, two cases: the neighbour contaminates neither direction, and rows with no tenant belong to no workspace). `-am` must not be dropped, otherwise `harnax-entity` resolves from the stale SNAPSHOT in `~/.m2` and the two statements added in this round never take effect; `-Dtest` is given a deliberately non-matching value to skip the unit-test phase so the container starts only once. The acceptance check is the `Tests run:` line, not `BUILD SUCCESS`.

- [x] **Step 5: mutation check (the only cheap proof for I1/I2)** — green tests do not mean the predicate is doing work; detach the tenant predicate temporarily and the isolation IT must turn red.

Method: remove the `tenant_id` condition once from the `tenantActive` fragment in `DashboardMapper.xml` and once from the two new queries in `TokenStatsMapper.xml`, run the same command as Task 8 Step 4, and restore both files byte-for-byte afterwards (`git diff` re-checked to confirm the two files are back to their original content).
Actual run (evidence `logs/it-mutation.log`): `Tests run: 9, Failures: 1`. Those 9 are not the isolation class alone — `-Dit.test` overrides the pom's `**/*IT.class` include, so the same round swept in `DashboardOverviewIT` (2), the isolation class (1, it had a single case at the time) and `DashboardServiceImplTest` (6) under failsafe, while the surefire section ran 0. The one red case is `neighbourRowsStayOutOfTheOverview:171` — tenant 1's `today` changed from `{calls:0,tokens:0,sessions:0,agents:0}` to `{calls:2,tokens:900,sessions:2,agents:2}`, and `assets.sessions` from 0 to 1, which is exactly the neighbour's two rows leaking in. The assertions are live, not tautological.
One timeline qualifier: at the time that round ran, the isolation class had only the neighbour case; the second one, "rows with no tenant are never counted", was added afterwards (it first appears in the round after 23:58, and `logs/it-dashboard3.log` is the one with 2 items). So what the mutation proved live is the neighbour case; the second case was never run through a mutation on its own.

## Task 9: Whole-page frontend assembly and entry point

**Files:** Modify `harnax-webui/src/pages/Welcome.tsx`; append the two `pages.ts`; change `config/routes.ts` and the two `menu.ts`

- [x] **Step 1: `Welcome.tsx` only fetches and orchestrates**

```tsx
const { initialState } = useModel('@@initialState');
const [data, setData] = useState<API.DashboardOverview | null>(null);
const [loading, setLoading] = useState<boolean>(true);
const [error, setError] = useState<string | null>(null);

const load = useCallback(async () => {
  setLoading(true);
  setError(null);
  try {
    const res = await getDashboardOverview();
    if (res?.code === 200 && res.data) {
      setData(res.data);
    } else {
      setError(res?.message || 'load failed');
    }
  } catch (e: any) {
    setError(e?.message || 'load failed');
  } finally {
    setLoading(false);
  }
}, []);

useEffect(() => {
  void load();
}, [load]);
```

Screen order: `GreetingBar` → `TodayStatsRow` → `TrendCard` + cost → two-column `RankList` → `AssetStrip` → `TodoStrip`; when `error` is non-empty the whole page shows one `Alert` and one retry button (C12), with no per-section degradation and no polling.

- [x] **Step 2: no extra request for the tenant name**: `API.CurrentUser` has no tenant field, so leaving `GreetingBar`'s `tenantName` empty makes the whole segment not render — one does not call `/api/admin/tenant/{id}` for a single label, since that would add a possibly unauthorised read to the first screen.
- [x] **Step 3: the day-over-day delta rules are pinned by `metrics.ts`' unit tests** — yesterday 0 and today 0 → flat; yesterday 0 and today >0 → `新增` (new), with neither `+Infinity%` nor 100%; a non-finite or negative value → flat with no percentage; everything else to one decimal place.
- [x] **Step 4: 38 `pages.welcome.*` keys written to disk in Chinese and English at the same time** (the two sides of `pages.ts` have equal counts: `agents/channels/mcp/models/sessions/skills/teams/users/title`, `dataAsOf`, `greeting`, `platform.{cli,note,title,tools}`, `quick.{agent,skill,tokenMonitor}`, `rank.deleted`, `refresh`, `tenant`, `today.{agents,calls,sessions,tokens}`, `todo.{activeUsers7d,drafts}`, `trend.{calls,fee14d,title,tokens}`).
- [x] **Step 5: the entry point**: in `config/routes.ts` the `/welcome` entry drops `hideInMenu` and its `name` changes from `welcome` to `dashboard`; both sides of `menu.ts` replace `menu.welcome` with `menu.dashboard`. The old address `/welcome` is unchanged, so bookmarks and the post-login redirect are unaffected.
- [x] **Step 6: the quick entries point at the canonical addresses after the migration**: `/monitor/token-monitor`, `/monitor/skill-drafts`, `/agent/manager`, `/context/skill` (the menu migration is the user's uncommitted change, the old `/context/*` addresses have been turned into redirects, and no redirect shell is mounted in the page).

## Task 10: Verification gates (all of them re-run by the controller)

- [x] **Step 1: backend unit tests**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am test \
  -Dtest='DashboardServiceImplTest,!com.agnetix.harnax.admin.it.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > /tmp/dash-unit.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-unit.log | tail -2
```
Expected: `EXIT=0` + `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.
Actual run: `EXIT=0`, `DashboardServiceImplTest` `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` (evidence in the worktree's `logs/unit-dashboard.log`; every step below runs in the worktree `tmp/worktrees/dashboard-overview`, not in the main checkout). That was the count at the time; the fourth pass added one case for the ranking qualifier, so the shipped suite is 7 (see the `Four review passes` section at the end).

- [x] **Step 2: the two ITs (real MySQL)**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/heqingsong/code/my_project/harnax/tmp/worktrees/dashboard-overview
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am -Pintegration-test \
  -Dit.test='DashboardOverview*,DashboardTenantIsolation*' \
  -Dtest='Dashboard-none' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify > /tmp/dash-it2.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-it2.log | tail -5
```
Expected: `EXIT=0`, with `Tests run: 2, Failures: 0, Errors: 0` (`DashboardOverviewIT`) and `Tests run: 2, Failures: 0, Errors: 0` (the isolation class, two cases: the neighbour contaminates neither direction, and rows with no tenant belong to no workspace); `ls harnax-admin/target/failsafe-reports/*.xml | wc -l` is not 0 (`BUILD SUCCESS` on its own is not the acceptance check — dropping `-Pintegration-test` also yields SUCCESS while running not a single IT).
Actual run: `EXIT=0`, `DashboardOverviewIT` 2 / `DashboardTenantIsolationIT` 2 / `DashboardServiceImplTest` 6, totalling `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` (evidence `logs/it-dashboard3.log`; the two earlier rounds `it-dashboard.log` and `it-dashboard2.log` are failure records from the triage process, kept as process evidence). That was the count at the time; the fourth pass added one real-database case for the same-named model rows, so the shipped shape is 3 / 2 / 7 = 12 (see the `Four review passes` section at the end).
- [x] **Step 3: the full reactor does not regress**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o test -Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**,!com.agnetix.harnax.harness.memory.**,!com.agnetix.harnax.harness.minio.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > /tmp/reactor.log 2>&1; echo EXIT=$?
grep -c 'SUCCESS \[' /tmp/reactor.log
```
Expected: `EXIT=0`, 26 occurrences of `SUCCESS [`. The only known noise is `RateLimiterTest` in `harnax-session-router` (concurrency window jitter, green when run alone).
Actual run: `EXIT=0`, `grep -c 'SUCCESS \['` = 26, `BUILD SUCCESS` at the end of the log, and no line anywhere in it where `Failures` is not 0; the known noise `RateLimiterTest` came out `Tests run: 12, Failures: 0` this round (evidence `logs/reactor.log`).
- [x] **Step 4: frontend build**

```bash
cd harnax-webui && npm run build > /tmp/webui-build.log 2>&1; echo EXIT=$?
```
Expected: `EXIT=0` with `dist/` produced. `npx tsc --noEmit` reports several hundred pre-existing noise lines in this repository (the `.umi` types are not generated), so it is not used as a gate.
Actual run: 8 rounds across the change work, each `EXIT=0` and ending with `event - Build index.html` (last round `logs/webui-build8.log`), output in the worktree's `harnax-webui/dist/`. The worktree's `node_modules` is a symlink to the directory of the same name in the main checkout — dependencies are not installed twice, but that makes the main checkout's `node_modules` shared, so do not run `npm ci` on it.
- [x] **Step 5: biome lint**

```bash
cd harnax-webui && npx @biomejs/biome lint src/pages src/locales > /tmp/webui-lint.log 2>&1; echo EXIT=$?
```
This repository's baseline is red on this wide command: `Checked 152 files` / `Found 34 errors, 203 warnings`, and every file hit is a pre-existing page this change never touched (for instance `react(noArrayIndexKey)` in `src/pages/token-monitor/index.tsx`). So only the narrow scope can serve as a gate — restrict the checked files to this change's file set:

```bash
cd harnax-webui && npx @biomejs/biome lint src/pages/welcome src/locales/en-US/pages.ts src/locales/zh-CN/pages.ts
```
Actual run: `Checked 7 files in 56ms. No fixes applied.`, exit 0 with no diagnostics. The wide command's red is pre-existing debt outside this plan's scope, and the evidence stays in `logs/webui-lint.log`.
- [x] **Step 6: metrics unit tests**

```bash
cd harnax-webui && npm test -- src/pages/welcome/metrics.test.ts > /tmp/jest-metrics.log 2>&1; echo EXIT=$?
grep -E 'Tests:|Suites:' /tmp/jest-metrics.log
```
The wide command above cannot run on this machine: `jest.config.ts` fails as soon as it loads (`Cannot find module '@umijs/max/test'`), reproduced on the main checkout as well — a pre-existing environment issue, unrelated to this plan. The reproducible way to run it is to resolve the same configuration to JSON through CJS and hand that to jest (script `logs/jest-config-dump.cjs` → `logs/jest.resolved.json`):

```bash
cd harnax-webui && npx jest --config ../logs/jest.resolved.json src/pages/welcome/metrics.test.ts
```
Actual run: `EXIT=0`, `Test Suites: 1 passed`, `Tests: 13 passed, 13 total` (`deltaOf` 7 / `deltaTone` 1 / `formatCount` 3 / `formatTokens` 2, evidence `logs/jest-metrics2.log`). The first review round added one bidirectional case for rounding a delta down to `0.0%`, taking it to 14 (`logs/jest-metrics3.log`); the fourth pass added 5 cases for the ranking labels, so the shipped suite is 19 (`deltaOf` 8 / `deltaTone` 1 / `formatCount` 3 / `formatTokens` 2 / `rankLabels` 5, evidence `logs/jest-metrics4.log`, see the `Four review passes` section at the end).
- [x] **Step 7: real rendering** — start the dev server with `npm run start:dev`, open `/welcome` in headless Chromium, and check screen by screen: the four card readings, the 14-point line chart, the two-column lists (≤5 rows), the 8 asset cells + the `非本租户` (not this tenant) fine print on the platform row, the pending-draft link, and the `数据截至` (data as of) rendered from `serverTime`; then confirm no uncaught exception in the console and exactly one 200 for `/api/admin/dashboard/overview` in Network.
This step did not go through the dev server: the deployment stack is the one the user is working on, so opening a new port does not interfere but is not necessary either, and the deployed frontend container serves self-signed https, which the browser cannot open. The actual channel was **static `dist/` + a forensics proxy + CDP headless Chromium** — `logs/forensic-server.mjs` (8123) stubs only `GET /api/admin/dashboard/overview` and forwards every other `/api/*` as-is to the real admin on host port 28080; the login state was captured from a real tab, written into `logs/login-seed.json`, and injected via `?seed=1`. The stubbed payload `logs/payload.json` was really produced by hand-written SQL against the deployed database (`logs/overview-from-sql.sql`); it was not made up.
Screen-by-screen verification is complete: desktop 1400 (`logs/trend-desktop-final.png`, 2800×2334 @2x), mobile 390 (`logs/trend-mobile3.png`, 780×4134 @2x), re-fetching by clicking `刷新` (Refresh) (`logs/probe-refresh.mjs`), the error state with an injected 500 (`logs/welcome-error.png`, via the `GET /__fail?on=1` runtime switch), and recovery by retrying from the error state (`logs/welcome-recovered.png`). Apart from the deliberately injected 500, the console has no uncaught exception.
**Real rendering caught one defect that the build and the unit tests alone could never have caught**: the ProLayout sidebar runs a width transition on the first screen, while autoFit in `@ant-design/plots` measures the container once at the moment of mounting and afterwards only follows window resize. Measuring a mid-transition value left the trend chart's canvas permanently 192px wider than its card, pushing the whole page into a horizontal scrollbar (`scrollWidth` 1547 in a 1400 viewport). The fix watches the card width with our own `ResizeObserver`, passes the width to the chart explicitly and turns autoFit off (`TrendCard.tsx`); the first attempt was "do not mount the chart when the data is empty", which did not fix the overflow — only grepping the new code in the built output confirmed that assumption to be wrong.
- [x] **Step 8: SQL reconciliation** — on the deployed database, match the page's readings against hand-written SQL (checking each definition one by one, not just the totals):

```bash
docker exec harnax-mysql mysql -uroot -proot123456 harnax_admin -e \
 "SELECT (SELECT COUNT(*) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS calls,
         (SELECT COALESCE(SUM(total_token),0) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS tokens,
         (SELECT COUNT(DISTINCT session_id) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS sessions,
         (SELECT COUNT(*) FROM agent WHERE tenant_id=1 AND active=1) AS agents;"
```
Expected: byte-for-byte equal to the page's `今日模型调用` / `Token 消耗` / `活跃会话` (today's model calls / token consumption / active sessions) and to the agents cell of the asset strip.
Actual run (to make today's window non-zero, 5 rows with ids 970001-970005 were seeded first per `logs/seed-forensic.sql`, then deleted by id once the forensics were taken, with residue re-checked: both `id BETWEEN 970001 AND 970005` and `session_id LIKE 'dashboard-forensic%'` are 0): `calls=3`, `tokens=225000`, `sessions=2`, `agents=1`, over the same span yesterday `calls=2`, `tokens=24000`, and the 14-day cost total `6` — the page renders `今日模型调用` 3 (delta +50.0%), `Token` 225.0K (+837.5%), `活跃会话` 2, `智能体` 1, ¥6 in the bottom-right corner, the last two trend points `10-05 calls=16` / `10-06 calls=3`, top of the list `测试 430.7K`, byte-for-byte consistent.
What this reconciliation proves is **the frontend mapping and formatting** (zero-padding, units, the sign of the day-over-day delta); the SQL semantics of the endpoint itself are not proven here — they are pinned by `DashboardOverviewIT` + `DashboardTenantIsolationIT` on Testcontainers' real MySQL. The split of duties must be stated plainly: this step's payload was produced directly by hand-written SQL, and **the endpoint has never run once inside the deployed admin image** (see task 11).

## Task 11: Deployment and rollback

- [x] **Step 1: no migration, no new environment variable, no new container**. The change touches only the admin jar and the frontend static output; `harnax-deploy`'s compose, `nginx.conf` (which already has `location /api/admin/`) and the Flyway baseline stay untouched.
The three acceptance checks are verified: `git status --short -- 'harnax-*/src/main/resources/db/migration'` is empty over the change set; the three new backend files (`DashboardController.kt`, `DashboardServiceImpl.kt`, `DashboardMapper.kt`) contain no `@Value` / `System.getenv` / `@ConfigurationProperties`, i.e. no new configuration key; `git status --short -- harnax-deploy` is empty, and `harnax-deploy/nginx.conf:191` already has `location /api/admin/`, so the new sub-path needs no proxy change.
- [ ] **Step 2: deploy** (not executed): `bash harnax-deploy/build.sh admin` then `bash harnax-deploy/deploy-service.sh admin`; the frontend likewise builds a `frontend` image. Ports follow the existing rules (80/443 externally, admin on host 28080, MySQL on host 23306).
This step rebuilds and restarts the `harnax-admin` / `harnax-frontend` containers the user is actively working on; it is a shared action affecting someone else's live environment, so it is not done before an explicit nod. **That is why the endpoint has never run once inside the deployed image.**
- [ ] **Step 3: live acceptance checks** (not executed, together with Step 2): `docker compose -f harnax-deploy/docker-compose.yml ps` all `up`; with an admin token, `curl -s localhost:28080/api/admin/dashboard/overview` returns `code=200` and a `trend` array of length 14; `logs harnax-admin` shows no `Failed to build dashboard overview`.
- [x] **Step 4: rollback shape**: rolling back the two images is enough — no DDL, no migration, no configuration key, so the rollback leaves nothing behind. The frontend's old `/welcome` path is unchanged, so after a rollback the menu entry is still there. This follows directly from the three acceptance checks of Step 1.

---

## Acceptance checklist

1. The first screen contains no hard-coded number anywhere: there is no literal reading in `Welcome.tsx`, and `grep -n "value=\"1[0-9][0-9]\"" src/pages/Welcome.tsx` is empty.
2. `GET /api/admin/dashboard/overview` takes no query parameter, and the tenant is resolved only from the request's credentials; when `X-Tenant-ID` switches workspace, the readings change with it.
3. The four today cards = model calls / Token / active sessions / active agents, each card carrying one `对昨日同时段` (vs. yesterday, same span) day-over-day delta line; the two shapes where yesterday is 0 show flat / new.
4. The trend is always 14 points, with days that have no data at 0; the bottom-right corner shows only the 14-day cost total, and no card shows `今日费用 ¥0.00` (today's cost ¥0.00).
5. Both lists have ≤5 rows each, row widths normalised to the maximum inside the list, a row with an empty name shows `（已删除）` (deleted) instead of being dropped, and rows that share a name inside one list carry a qualifier (the model list: the provider name; the agent list: always empty).
6. The 8 asset cells and the platform row are laid out on separate lines, the latter marked `平台级，非本租户` (platform-level, not this tenant).
7. The Chinese and English `pages.ts` hold equal counts of `pages.welcome.*`; on both sides the menu item is called `总览` / Overview (`menu.dashboard`), `/welcome` sits first in the menu, and `/` still redirects to it.
8. Backend: 7 unit tests and 5 across the two ITs (`DashboardOverviewIT` 3 + `DashboardTenantIsolationIT` 2), all green; the full reactor 26/26 SUCCESS (`logs/it-round4.log`, `logs/reactor-round4.log`).
9. Frontend: `npm run build` exit 0, zero diagnostics from the narrow-scope biome lint over the changed files, `metrics.test.ts` 19 all green, and readings verified by real rendering at both 1400 and 390 through "static output + forensics proxy + CDP headless Chromium".
10. No dependency added, Flyway untouched, the security configuration untouched, nginx untouched.

## Four review passes (flow soundness + boundary sufficiency)

Four rounds, each looking in one direction, and every fix was verified back on the real DOM. The first three touched only the frontend; the fourth changed product code — two backend files (`DashboardOverviewResponse.kt`, `DashboardServiceImpl.kt`) and three frontend ones (`metrics.ts`, `RankList.tsx`, `typings.d.ts`) — plus four test files (backend unit +1 case, real-database IT +1 case, frontend +5 cases), so the conclusions of task 8 and task 10 have to be read against the re-run record at the end of this section.

**Pass 1: the data path, hop by hop** (Controller → Service → mapper aliases → DTO → frontend service → cards). The chain holds: the aliases of the four new statements (`getDashboardWindowStats`, `getDashboardDailyTrend`, `getTenantAssetCounts`, `getPlatformCounts`) match the keys `DashboardOverviewResponse.kt` reads, byte for byte; both lists reuse the `grandTotalToken` of the existing `aggregateByAgent`/`aggregateByModel`, and the 14-day cost reuses the existing overall statement; the three `ResultVo` keys `code`/`data`/`message` are all present on the frontend envelope type, and `errorThrower` throws only when `code !== 200`.
This pass caught one boundary that does not hold: the direction of the day-over-day chip followed the unrounded ratio. `deltaOf(3_200_000, 3_199_000)` is 0.031%, the copy rounds to one decimal and prints `0.0%`, yet the arrow still said "up" — the page would show a sentence that contradicts itself: `较昨日同时段上升 0.0%` (up 0.0% versus the same span yesterday).
The fix (`deltaOf` in `metrics.ts`): format the percentage into a string first, and let the direction follow that rounded value — a difference smoothed to `0.0%` counts as flat. `metrics.test.ts` gained a bidirectional case (13 → 14).

**Pass 2: the error and degradation path**. C12 fixes the page at one error state, but the implementation had two: besides the page's own error card, the BizError thrown by umi's `errorThrower` was surfaced by `errorHandler` in `requestErrorConfig.ts` as a global red toast.
The fix: this screen's fetch passes `skipErrorHandler: true` (precedents already in the repository — `team/relatedSessions`, and the delete/trigger calls in `agentTask`), so the error lands only on the page's card. The price is that the global 401 → login redirect gets switched off too, so this page wires that hop itself: on `requestError.response.status === 401` it clears `currentUser`/`tokenInfo` and calls `history.push('/login?redirect=%2Fwelcome')`. The server's failure message is a hard-coded English constant whose cause lives in the admin log, so the page says only its own sentence, `pages.welcome.loadFailed`.

**Pass 3: loading and empty states**. During a manual refresh, `loading` flipping false→true swapped the already-loaded four cards, trend, both lists and the asset strip back to skeletons, so the readings vanished for a moment.
The fix: section skeletons are shown only when "not a single reading exists yet" (`firstLoad = loading && data === null`); the feedback for a refresh in flight is carried by the spinning icon in the greeting bar and by `loading` on the retry button.

Real-DOM evidence (`logs/probe-review.out`, `logs/probe-chips.out`; channel = the rebuilt `dist/` + the `logs/forensic-server.mjs` stub + CDP headless Chromium):
- mid-request (a 1200 ms read, snapshot taken at 500 ms): `cards: ["3","225.00K","2","1"]`, `skeletons: 0` — a refresh no longer replaces the readings with skeletons;
- on 500: `errorTexts: ["总览数据加载失败"]`, `toasts: 0`, `messageNodes: []` — one error surface left;
- on 401: `path: "/login"`, `search: "?redirect=%2Fwelcome"`, `storageCleared: true` — the redirect still works after the global handler was switched off;
- the chip fixture (yesterday's `tokens` set to 3,199,000 so today differs by 0.03%): four chips `["较昨日同时段上升 66.7%","与昨日同时段持平","与昨日同时段持平","与昨日同时段持平"]` with readings `["5","3.20M","2","1"]` — `0.0%` no longer carries a direction. The fixture was restored from `logs/payload.orig.json`.

The gates were re-run over the same code: `metrics.test.ts` 14 all green (`logs/jest-metrics3.log`), the narrow-scope biome lint reporting `Checked 6 files` with zero diagnostics (`logs/lint-welcome3.log`), and `npm run build` with `EXIT=0` (`logs/build-welcome3.log`).

**Pass 4: every ranking row reads as the row it is.** The first three passes checked whether the numbers were right; this one checks which row a number sits on — the ranking labels. In the payload really read from the deployed database, `topModels` contained two rows with the same name: `qwen3.7-flash` 250,710 and `qwen3.7-flash` 180,000. The cause is not dirty data: `aggregateByModel` groups by `t.chat_model_id, m.name, p.name`, so one display name carried by two providers is legitimately two rows; the SQL already selected `providerName`, and it was `DashboardRankItem` that dropped it at the DTO hop. Two identical labels in a row read as a rendering duplicate.
The fix: the backend adds a non-null `qualifier` to `DashboardRankItem` (defaulting to the empty string, in keeping with the response-wide "no null keys" contract), `mapToRankItem` gains an optional `qualifierKey`, and only the model list passes `"providerName"` — the agent list has no second column and stays empty. The frontend adds the pure function `rankLabels` to `metrics.ts`, which appends `名字（provider）` (name (provider)) only to rows that actually collide, and `RankList` renders the label it computes instead of the bare `name`. The ranking still carries no id: no action on the page goes back to a row, and putting an id in the wire contract would be preparation for an action that does not exist.
Two boundaries went into the implementation with it: a `qualifier` that is absent or all whitespace returns the name unchanged (when a model row is deleted its provider goes with it, so those rows can only look alike — no discriminator is invented); rows with an empty name are kept out of the collision count, otherwise a real name would pick up parentheses for no reason.
Real-DOM evidence (`logs/probe-ranklabels.out`): the four model rows read as `qwen3.7-flash（阿里百炼）` / `qwen3.7-flash（内部网关）` / `gpt-5` / `（已删除）` — the two colliding rows each carry a provider, the non-colliding row is untouched, and the empty-named row still renders `（已删除）` (deleted). The fixture is `logs/payload.json` with `qualifier` added: the two same-named rows' Token values come from the payload really read from the deployed database, while the two provider names are invented by the fixture, because the read-only query against the deployed database was refused by the permission layer and the real names could not be obtained. After the probe the payload was restored from `logs/payload.orig.json`, and `diff -q` reports it byte-identical to the captured one.
Mutations in both directions: removing `"providerName"` at the Service call site turns `DashboardOverviewIT` red in `sameNamedModelRowsCarryTheirProvider` (`Tests run: 3, Failures: 1`, actual `<[, ]>`, `logs/it-mutation-qualifier.log`); removing the collision test from `rankLabels` fails 2 cases, and making it never qualify fails 1. Both were restored and are green again, with zero residue in `grep` after restoration.
Reconciliation tool kept in sync: the `topModels` query in `logs/overview-from-sql.sql` now joins the provider and emits `qualifier`, and `topAgents` emits an empty string, so the hand-written reconciliation has the same shape as the wire contract.
The gates were re-run over the restored code: on the backend `spotless:apply` → `test-compile` → `-Pintegration-test` yields surefire `Tests run: 7` (`DashboardServiceImplTest`) and failsafe `Tests run: 3` (`DashboardOverviewIT`) + `Tests run: 2` (the isolation class) with `BUILD SUCCESS` (`logs/it-round4.log`); on the frontend `Tests: 19 passed` (`logs/jest-metrics4.log`), the narrow-scope lint reporting `Checked 5 files` with zero diagnostics (`logs/lint-round4.log`), and `npm run build` with `EXIT=0` (`logs/build-round4.log`).

## Accounting (unverified)

- **A failed manual refresh replaces the whole loaded page with the error card**: this round made "one error surface" real (no global toast stacked on top), but did not change the "failure discards the readings" shape — that is the literal reading of C12, and it triggers only on that one manual refresh (the page never polls). Turning it into "keep the stale readings plus one failure line at the top" is a change of decision and awaits a ruling.
- **The `<if>` window guards in `tenantAndTimeWindow` err on the permissive side**: if a caller passes a null or empty window, the SQL loses one `ts` condition and silently widens to the whole history instead of failing. All three dashboard call sites format their bounds into `yyyy-MM-dd HH:mm:ss` strings before passing them (`DashboardServiceImpl`), but the fragment is shared with over a dozen token-monitor statements, so any new caller has to guarantee non-empty on its own; turning it into a hard failure first requires confirming that no token-monitor call has a legitimate empty window.
- **The endpoint has never run once inside the deployed admin image** (the only functional unverified item left by this plan): Step 2/3 of task 11 were not executed, because rebuilding `harnax-admin` restarts the containers the user is actively working on. The coverage right now is "the ITs exercise the full filter chain down to `/api/admin/dashboard/overview` on Testcontainers' real MySQL" + "the frontend really renders against a stubbed payload", and the seam between those two halves (Spring's wiring inside the deployed image, the execution plan against the real database) can only be closed by that one deployment.
- **The fix for the mount-width defect was swept at only two viewport sizes**: `ResizeObserver` + `autoFit:false` verified at 1400 and 390 (`logs/trend-desktop-final.png`, `logs/trend-mobile3.png`); the collapsed sidebar state, the area around the 768 breakpoint, and the intermediate states while dragging the window were not swept size by size. The overflow check (`document.documentElement.scrollWidth <= innerWidth`) holds at both sizes.
- **The trend is two independent line series, and the price is that comparing across series at the same instant needs a separate hover for each**: the same-axis dual series (DualAxes) was rejected because the two differ by four orders of magnitude, and sharing one linear axis would press the call count down into a flat line at the bottom. In the current shape each tooltip reports only its own series.
- **`labelAutoHide` is a silent no-op on `@antv/g2` 5.4.8**: discovered only because the screenshots were byte-for-byte identical. On narrow screens the date ticks rely on a hand-written `tickFilter` that keeps one out of every three (`TrendCard.tsx`); this "three" was set from the measured layout of 14 points at 390 width — if a time-window picker is added later or the point count changes, this has to be re-evaluated rather than inherited.
- **`metrics.test.ts` is not on a path the regular command can reach**: `jest.config.ts` fails on load on this machine (`Cannot find module '@umijs/max/test'`), reproduced on the main checkout as well. It only runs through the CJS-resolved `logs/jest.resolved.json`; if CI uses the same `npm test`, these 19 items may never have been executed.
- **The wide-scope frontend lint baseline was already red**: `biome lint src/pages src/locales` = 34 errors / 203 warnings, and every file hit is a pre-existing page this change never touched. This plan only guarantees zero diagnostics on the changed files.
- **Build residue `harnax-webui/src/.umi-undefined/`**: `harnax-webui/.gitignore` lists only `.umi`, `.umi-production`, `.umi-test`, so this suffix is not ignored. Do not commit it.
- **The two halves were written to disk in two separate places**: the design and the implementation plan live only in the main checkout's `prod_doc/`, while the code changes live only in the worktree `tmp/worktrees/dashboard-overview` (branch `dashboard-overview`, cut from `98b79301`), and the two have not converged; the entire change set has still not been `git add`ed, and the commit and merge shape is the user's decision.
- **Crossing the day boundary**: the two ITs write their rows at `LocalDateTime.now()`; should a case happen to straddle midnight 00:00, the "today" window shifts by a day and the deltas become invalid. The trigger probability is very low but not zero, and the failure looks like cases of this kind going red — it is not a product defect.
- **The isolation IT also relies on one pre-existing behaviour**: reading as the neighbour depends on `X-Tenant-ID` plus the admin token skipping the membership check (`BaseAdminIT` states it, `ModelTenantIsolationIT` already uses it). That behaviour is not itself under test in this plan, so if the class goes red, the first step is to tell a missing predicate apart from this precondition having changed.
- **The new files' comments cite design section numbers** (such as `设计 §4.2` and `（I3）`, 17 places in total, all in the 6 frontend files: `sections.tsx` 6 / `metrics.ts` 4 / `RankList.tsx` 3 / `Welcome.tsx` 2 / `TrendCard.tsx` 1 / `metrics.test.ts` 1; not one in the new backend or mapper-side files): decided to keep them. The reason is that both documents live in the same repository as the code (`prod_doc/`), and the repository already has a precedent of the same shape (`（I5）` in `InternalApiControllerTest.kt:995`). The plan document itself still does not reference any other md.
- **`initialState.currentUser` comes from `localStorage`**: all this change does is stop reading it directly inside the page (it now goes through `useModel`); how the login state itself gets written is outside the scope of this plan.

## Out of scope

- Refactoring of the Token monitoring page itself; a cross-tenant view for the global administrator; a time-window picker; session/sandbox active counts (they belong to another data plane of session-router); `api_call_log` request volume; tenant-switching UI.
