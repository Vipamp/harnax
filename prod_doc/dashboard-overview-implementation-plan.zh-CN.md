# 首页运营总览 实施方案

**目标：** 把 `harnax-webui` 的 `/welcome` 首页从一屏写死的假数字换成一个免配置的租户运营总览，屏上每个读数都由 admin 进程内的一次真实聚合读得出。

**架构：** 后端只加一个只读接口 `GET /api/admin/dashboard/overview`，它不带任何查询参数，租户从请求自身的凭据解析，四个时间窗由一次时钟读取推好；聚合读落在 `TokenStatsMapper`（复用既有租户片段）与新的 `DashboardMapper`（资产盘点）两处，不建表、不加列、不动 Flyway。前端 `Welcome.tsx` 只做取数与编排，展示拆成四个 props 进的子组件，整页一个错误态。

**技术栈：** Kotlin 2.2.21 + Spring Boot + MyBatis XML + Testcontainers MySQL 8（IT）；UmiJS max + Ant Design Pro + antd 5.25.4 + `@ant-design/plots` 2.6.8 + dayjs；biome lint、jest。

> **文档定位：** 这是可执行的实施清单，按任务逐条打勾。文档自洽，读它不需要再读别的文档。
>
> **For agentic workers：** 建议用 superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务执行；步骤用 `- [ ]` 复选框，已落地的任务已打勾并附落点。

---

## 1. 已定口径（每条都可核，勿在实现里重议）

| 编号 | 口径 | 落地位置 |
|---|---|---|
| C1 | 页面形态是运营总览仪表盘，不是产品介绍页；免配置一屏，深度分析在「监控与治理 → Token 监控」 | `src/pages/Welcome.tsx` |
| C2 | 只有一个聚合接口，路径 `GET /api/admin/dashboard/overview` | `DashboardController.kt` |
| C3 | 接口不接任何查询参数：租户、窗口、榜长、天数都不由客户端命名 | 同上 |
| C4 | 「今日请求」= 今日模型调用次数 = `token_stats` 在窗口内的行数，不跨服务读 `api_call_log`；卡片文案写「模型调用」，不写「请求」 | `getDashboardWindowStats`、`pages.welcome.today.calls` |
| C5 | 窗口写死：今日 00:00→此刻，近 14 天 | `DashboardServiceImpl` |
| C6 | 环比基准是「昨日 00:00→昨日同一钟点」，不是昨日全天 | 同上 |
| C7 | 活跃用户窗口 7 天，只取下界 | `getTenantAssetCounts` 第二参数 |
| C8 | 只按当前租户出数，不做全局管理员视图 | `TenantResolver.resolve(jwtUtil)` |
| C9 | 今日四卡 = 模型调用 / Token 消耗 / 活跃会话 / 活跃智能体；费用只作为 14 天合计出现 | `TodayStatsRow`、`TrendCard` |
| C10 | 租户资产 8 格；工具与 CLI 包是平台级，单列一行并标明「非本租户」 | `AssetStrip` |
| C11 | 新 `token_stats` SQL 进 `TokenStatsMapper`，让租户谓词只有 `tenantAndTimeWindow` 一个出处 | `TokenStatsMapper.xml` |
| C12 | 整页一个错误态 + 一次重试，不做分区降级，不轮询 | `Welcome.tsx` |
| C13 | 前端不新增取数依赖，不加 npm / Maven 包 | 全部 |
| C14 | 不设租户切换入口（`TenantSwitcher` 本身对任何版本都返回 null） | 不改该组件 |
| C15 | 保留 `/welcome` 路径，取消 `hideInMenu`，菜单名 `menu.welcome` → `menu.dashboard` | `config/routes.ts`、两个 `menu.ts` |

## 2. 不变量（违反了测试会立刻红）

- **I1 租户谓词只有两处**：`TokenStatsMapper.xml` 的 `tenantAndTimeWindow` 片段、`DashboardMapper.xml` 的 `tenantActive` 片段。新加的每一格都必须 include 其中之一，唯一豁免是 `skill_draft`（该表无 `active` 列，软删在 `status`）与 `getPlatformCounts`（两张表根本没有 `tenant_id`）。
- **I2 `tenant_id` 为 NULL 的行不属于任何工作区**：`token_stats.tenant_id`、`sys_user.tenant_id` 都可空，等值比较天然把它们排除在外。
- **I3 空窗口返回全零 + 14 个零日点**，不是缺字段、不是错误码。
- **I4 时间是本地墙钟语义**：窗口边界格式化为 `yyyy-MM-dd HH:mm:ss` 字符串与 `DATETIME` 列比较，两侧同区——`harnax-deploy/Dockerfile.admin` 把 `TZ=Asia/Shanghai` 并链接 `/etc/localtime`，compose 里 MySQL 容器同样 `TZ: Asia/Shanghai`，数据源 URL 带 `serverTimezone=Asia/Shanghai`。整条链不做 UTC 转换，「今日」就是运维者所在时区的一天。
- **I5 一次请求只读一次时钟**：`LocalDateTime.now()` 只出现在 Controller，向下传参；Service 内部禁止再次调用。
- **I6 榜的截断与排序都在服务端**：`take(5)` 取 SQL 已按 `grandTotalToken DESC` 排好的头部，前端与服务层都不重排。

## 3. 全局约束

- 不建表、不加列，`harnax-admin/src/main/resources/db/migration/**` 一个字节都不改（已应用的 Flyway 迁移连注释都动不得）。
- 不新增依赖：后端不加 Maven 坐标，前端 `package.json` 不动。
- 后端注释 / KDoc / 日志串一律英文；前端沿用 webui 现状的中文注释风格。
- 前端任何新文案必须同时落 `src/locales/zh-CN/pages.ts` 与 `src/locales/en-US/pages.ts`，缺一侧算未完成。
- DTO 字段一律非空带默认值：admin 的 Jackson 3 会丢掉 null 键，前端无法区分「没这个键」与「值为 0」。
- `src/services/**` 被 biome 排除（`biome.json`），因此类型必须写进 `src/typings.d.ts`。
- 本机 Maven 配方：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`，`mvn` 用 `/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn`，改过 Kotlin 必先 `spotless:apply`；命令一律 `> x.log 2>&1; echo EXIT=$?` 再看日志，别让管道吃掉退出码。
- 工作区是共享且带未提交改动的：执行者不得 `git add` / `commit` / `push` / `stash` / `clean` / `checkout --`，提交由控制方与用户决定。

## 4. 文件结构

| 文件 | 动作 | 责任 |
|---|---|---|
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt` | 新建 | 线上契约 + 行→字段的映射（`mapTo*` / `*Of`） |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/DashboardService.kt` | 新建 | `overview(tenantId, now)` 接口 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImpl.kt` | 新建 | 四个窗口推导、趋势补零、榜截断 |
| `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/DashboardController.kt` | 新建 | 解析租户、读一次时钟、`ResultVo` 信封与整页失败降级 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/DashboardMapper.kt` | 新建 | 资产盘点与平台注册表两条读 |
| `harnax-entity/src/main/resources/mapper/DashboardMapper.xml` | 新建 | `tenantActive` 片段 + 标量子查询形状 |
| `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/TokenStatsMapper.kt` | 追加 | 两个新方法，注释说明为何不放 `DashboardMapper` |
| `harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml` | 追加 | `getDashboardWindowStats`、`getDashboardDailyTrend` |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImplTest.kt` | 新建 | 窗口边界、租户传递、补零、截断、空租户 |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardOverviewIT.kt` | 新建 | 真 MySQL 上的 delta 断言 + 载荷完整性 |
| `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardTenantIsolationIT.kt` | 新建 | 租户谓词：邻居租户的量不动本租户读数 |
| `harnax-webui/src/services/ant-design-pro/dashboard.ts` | 新建 | 唯一取数函数 |
| `harnax-webui/src/typings.d.ts` | 追加 | `API.DashboardOverview` 及子类型 |
| `harnax-webui/src/pages/welcome/metrics.ts` | 新建 | `formatCount` / `formatTokens` / `deltaOf` / `deltaTone` 纯函数 |
| `harnax-webui/src/pages/welcome/metrics.test.ts` | 新建 | 环比四种边界形状 + 格式化 |
| `harnax-webui/src/pages/welcome/sections.tsx` | 新建 | `GreetingBar` / `TodayStatsRow` / `AssetStrip` / `TodoStrip` / `glassCardStyle` |
| `harnax-webui/src/pages/welcome/TrendCard.tsx` | 新建 | 14 天双序列折线 + 费用合计 |
| `harnax-webui/src/pages/welcome/RankList.tsx` | 新建 | Top 榜，行宽按榜内最大值归一 |
| `harnax-webui/src/pages/Welcome.tsx` | 重写 | 取数、编排、整页错误态 |
| `harnax-webui/src/locales/{zh-CN,en-US}/pages.ts` | 追加 | `pages.welcome.*` |
| `harnax-webui/src/locales/{zh-CN,en-US}/menu.ts` + `config/routes.ts` | 改 | 菜单名与入口可见性 |

---

## 任务 1：线上契约 DTO

**Files:** Create `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/DashboardOverviewResponse.kt`

**Interfaces:** Produces `DashboardOverviewResponse`（下表字段）、`DashboardWindowStats`、`DashboardTrendPoint`、`DashboardRankItem`、`DashboardAssetCounts`、`DashboardPlatformCounts`，以及伴生映射函数 `mapToWindowStats` / `mapToTrendPoint` / `emptyTrendPoint` / `mapToRankItem` / `mapToAssetCounts` / `mapToPlatformCounts` / `pendingSkillDraftsOf` / `totalUsersOf` / `activeUsersOf` / `recent14dFeeOf`，全部接受可空 `Map<String?, Any?>?`。

- [x] **Step 1：写 data class，每个字段带默认值**

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

| 字段 | 类型 | 语义 |
|---|---|---|
| `today` / `yesterdaySameSpan` | `DashboardWindowStats` | `calls` / `tokens` / `sessions` / `agents` 四个计数 |
| `trend` | `List<DashboardTrendPoint>` | 恒 14 点，`date` 为 `yyyy-MM-dd`，缺点补 0 |
| `topAgents` / `topModels` | `List<DashboardRankItem>` | 恒 ≤5，已排序，`name` 空=归属行已删 |
| `assets` | `DashboardAssetCounts` | 8 格租户资产 |
| `platform` | `DashboardPlatformCounts` | `tools`、`cliPackages`，平台级 |
| `pendingSkillDrafts` | `Long` | 本租户待审草稿 |
| `totalUsers` / `activeUsersLast7Days` | `Long` | 分母与活跃数 |
| `recent14dFee` | `BigDecimal` | 14 天费用合计，元，不造精度 |
| `serverTime` | `String` | 全部窗口的测量时刻，`yyyy-MM-dd HH:mm:ss` |

- [x] **Step 2：映射函数把「缺键 / SQL NULL / 驱动类型不符」一律落到 0**，`(map?.get(key) as? Number)?.toLong() ?: 0L`；榜条目故意不带 id（这两个列表纯展示，页面上没有回到那行的动作）。
- [x] **Step 3：`spotless:apply` 后编译过**（判据：`harnax-admin/target/classes/.../DashboardOverviewResponse.class` 存在）。

## 任务 2：`DashboardMapper` 与租户资产盘点

**Files:** Create `harnax-entity/.../mapper/DashboardMapper.kt`、`harnax-entity/src/main/resources/mapper/DashboardMapper.xml`

**Interfaces:** Consumes 表 `agent`/`skill`/`model`/`mcp_server`/`channel`/`team`/`session`/`sys_user`/`skill_draft`/`agent_tool`/`cli`；Produces `getTenantAssetCounts(tenantId: Long, activeUserSince: String): MutableMap<String?, Any?>?`、`getPlatformCounts(): MutableMap<String?, Any?>?`。

- [x] **Step 1：谓词片段只写一次**

```xml
<sql id="tenantActive">WHERE 1 = 1 AND tenant_id = #{tenantId} AND active = 1</sql>
```

- [x] **Step 2：一行标量子查询答出全部格子**（`ModelMapper.xml` 已有的形状，保证恒返回一行），每格 `<include refid="tenantActive"/>`；`activeUsers` 在片段后追加 `AND last_login_time &gt;= #{activeUserSince}`；`pendingSkillDrafts` 用 `tenant_id = #{tenantId} AND status = 'PENDING'`。
- [x] **Step 3：平台行不带租户谓词**：`SELECT (SELECT COUNT(*) FROM agent_tool WHERE active = 1) AS tools, (SELECT COUNT(*) FROM cli WHERE active = 1) AS cliPackages`。
- [x] **Step 4：前提核实**（写代码前先做，结论已固定）
  - 8 张资产表在 `V1__init_schema.sql` 中**都有** `tenant_id` 与 `active`；`skill_draft` 只有 `status varchar(16)`，无 `active`；`sys_user.last_login_time` 存在（`datetime` 可空）。
  - `agent_tool`、`cli` 无 `tenant_id` 列 → 这两个数只能平台级展示。

## 任务 3：`token_stats` 两处新读

**Files:** Modify `harnax-entity/.../mapper/TokenStatsMapper.kt`、`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml`（只追加，不改既有语句）

**Interfaces:** Produces `getDashboardWindowStats(startTime: String?, endTime: String?, tenantId: Long): MutableMap<String?, Any?>?`、`getDashboardDailyTrend(...): MutableList<MutableMap<String?, Any?>?>?`；Consumes 既有片段 `tenantAndTimeWindow`（参数 `alias`）与 `dayBucket`。

- [x] **Step 1：窗口读**

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

- [x] **Step 2：日趋势读**——`dayBucket` 结果 `AS timePoint` + `COUNT(*) AS calls` + `COALESCE(SUM(total_token), 0) AS tokens`，`GROUP BY timePoint ORDER BY timePoint`。列别名就是线上契约，片段与别名不能改。
- [x] **Step 3：为什么放这里**：接口注释写明——为了让租户谓词继续只有 `tenantAndTimeWindow` 一个出处，而不是在别的 mapper 里抄第二份。

## 任务 4：服务层的窗口推导与组装

**Files:** Create `.../service/DashboardService.kt`、`.../service/impl/DashboardServiceImpl.kt`

**Interfaces:** Consumes 任务 2、3 的全部 mapper 方法与既有 `aggregateByAgent` / `aggregateByModel` / `getOverallStats`；Produces `overview(tenantId: Long, now: LocalDateTime): DashboardOverviewResponse`。

- [x] **Step 1：四个窗口由同一个 `now` 推出**

```kotlin
val todayStart = now.toLocalDate().atStartOfDay()
val todayFrom = timestamp(todayStart);            val todayTo = timestamp(now)
val yesterdayFrom = timestamp(todayStart.minusDays(1)); val yesterdayTo = timestamp(now.minusDays(1))
val trendStart = todayStart.minusDays((TREND_DAYS - 1).toLong())  // 14 点，含今日
val trendFrom = timestamp(trendStart);            val trendTo = todayTo
val activeUserSince = timestamp(now.minusDays(ACTIVE_USER_DAYS))  // 7 天，只有下界
```

- [x] **Step 2：趋势补零按日 key**，行 key 取 `timePoint` 的日部分（`LocalDateTime` / `java.sql.Timestamp` / `yyyy-MM-dd HH:mm:ss` 字符串三种形状都认，其他类型丢弃该行）；常量 `TREND_DAYS = 14`、`ACTIVE_USER_DAYS = 7L`、`RANK_LIMIT = 5`。
- [x] **Step 3：榜只 `take(5)` 不重排**；费用复用 `getOverallStats` 的 `totalFee`。
- [x] **Step 4：类内不出现 `LocalDateTime.now`**（I5），`serverTime` 回填 `todayTo`。

## 任务 5：控制器

**Files:** Create `.../controller/DashboardController.kt`

- [x] **Step 1：一个无参 GET**

```kotlin
fun overview(): ResultVo<DashboardOverviewResponse> = try {
    ResultVo.success(dashboardService.overview(TenantResolver.resolve(jwtUtil), LocalDateTime.now()))
} catch (e: Exception) {
    log.error("Failed to build dashboard overview", e)
    ResultVo.error("Failed to get overview data")
}
```

- [x] **Step 2：安全配置不需要动**（已核）：`JwtAuthenticationFilter` 的跳过清单不含 `dashboard`，`SecurityConfig` 的 `anyRequest().authenticated()` 覆盖该路径；`harnax-deploy/nginx.conf` 已有 `location /api/admin/`，无需加路由。

## 任务 6：服务层单测

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/DashboardServiceImplTest.kt`

- [x] **Step 1：六个用例覆盖 I1/I3/I5/I6**——窗口边界逐秒断言（钉住 `now` 为 `2026-10-05 13:45:30`）、租户参数传递与「平台读不收租户」、14 点补零（故意缺 3 天、1 天带时分）、榜截断不重排（行序故意非降序）、字段↔行映射（含 `null` 名字行）、空租户答全零 + 14 个零日点。
- [x] **Step 2：桩一律用参数通配（`anyOrNull()` / `anyLong()`），实参另用 `verify` + captor 断**——因为这个服务处处兜 0，桩没匹配上也会全绿，必须单独把交给 mapper 的边界取出来对。
- [x] **Step 3：`@MockitoSettings(strictness = Strictness.LENIENT)` + 构造函数手工建服务**，不用 `@InjectMocks`（加一个构造参数会让整类全红）。

## 任务 7：真库 IT（总览）

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardOverviewIT.kt`

- [x] **Step 1：断言全部是 delta 而非绝对值**——「今日」只能对着墙钟量，共享容器还会留着同类其他用例写的行，绝对值就是谁也没选过的数。做法：先读接口 → 播种 → 再读，要求差值恰等刚写入的量。
- [x] **Step 2：覆盖 8 条**：today 的 calls/tokens/sessions/agents 各自 delta、yesterdaySameSpan delta 恒 0（抓「下界少减一天」）、14 天费用合计 delta、今日趋势点 delta（抓 `timePoint` 类型不符被丢）、资产格 delta、多一个智能体只动资产不动消耗、载荷每个块与每个字段都存在且非 null、榜长度 ≤5。
- [x] **Step 3：`@AfterEach` 精确按 `tenant_id + session_id IN (...)` 清理**，id 段用 960_001/960_002 与专属 sessionId，避开别的类的夹具。

## 任务 8：租户隔离 IT

**Files:** Create `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/DashboardTenantIsolationIT.kt`

**Interfaces:** Consumes `BaseAdminIT` 的 `getJson(path, tenantId)`（管理员令牌带 `X-Tenant-ID` 即可切换工作区，成员校验对管理员跳过）、`assertOk`；Produces 无。

**为什么必须单独一个类：** 任务 2 与任务 3 各自新建了聚合读，两处谓词是分别写的；一个类同时证明「本租户读数不被邻居污染」和「平台两格本来就不该被污染」，才算守住 I1。

- [x] **Step 1：写整个类**

夹具要点（都已核实）：邻居租户 id 用 `950_500L`（全仓零命中，不与 `TokenStatsAggregationIT` 的 `930_930L`、`ModelTenantIsolationIT` 的 `940_002L` 撞）；`token_stats` 无外键且 `tenant_id`、`ts` 都可空，可以直接种。I2 的「租户为 NULL 的行不属于任何人」只能用这两张表证——`agent.tenant_id` 是 `NOT NULL DEFAULT '1'`，塞不进 NULL。`sys_user.tenant_id` 可空，但 `username`、`password`、`nickname`、`email`、`phone` 五列 NOT NULL 且无默认值，且 `active_username` 是按 `active` 生成的唯一键，用户名必须独占。资产格与消耗读数一律走 delta，避免受其他用例残留影响。

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

- [x] **Step 2：格式化**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/heqingsong/code/my_project/harnax/tmp/worktrees/dashboard-overview
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -q spotless:apply -pl harnax-entity,harnax-admin > /tmp/spotless.log 2>&1; echo EXIT=$?
```
期望：`EXIT=0`，日志无 `format violations`。

- [x] **Step 3：先单独 test-compile 过一遍再开全量**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -q -pl harnax-admin -am test-compile > /tmp/dash-tc.log 2>&1; echo EXIT=$?
```
期望：`EXIT=0`。编译失败会让 `failsafe-reports` 变空，届时「0 项」不是全绿。

- [x] **Step 4：跑两个 IT 类**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am -Pintegration-test \
  -Dit.test='DashboardOverview*,DashboardTenantIsolation*' \
  -Dtest='Dashboard-none' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify > /tmp/dash-it.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-it.log | tail -5
```
期望：`EXIT=0` 且日志出现 `Tests run: 2, Failures: 0, Errors: 0`（`DashboardOverviewIT`）与 `Tests run: 2, Failures: 0, Errors: 0`（隔离类，两条：邻居双向不污染、无租户行不属于任何工作区）。`-am` 不能省，否则 `harnax-entity` 从 `~/.m2` 的旧 SNAPSHOT 解析，本次新增的两条语句根本不生效；`-Dtest` 给一个故意不匹配的值是为了跳过单测阶段，让容器只起一次。判据是 `Tests run:` 那一行，不是 `BUILD SUCCESS`。

- [x] **Step 5：变异检查（I1/I2 唯一便宜的证法）**——绿测不等于谓词在起作用；把租户谓词临时摘掉，隔离 IT 必须变红。

做法：`DashboardMapper.xml` 的 `tenantActive` 片段与 `TokenStatsMapper.xml` 两条新查询的 `tenant_id` 条件各去掉一次，跑任务 8 Step 4 那条命令，跑完逐字还原（`git diff` 复查这两个文件回到原样）。
实测（证据 `logs/it-mutation.log`）：`Tests run: 9, Failures: 1`。9 项不是隔离类一家——`-Dit.test` 会盖掉 pom 里 `**/*IT.class` 的 include，所以同一轮里 `DashboardOverviewIT`（2）、隔离类（1，当时只有一条用例）、`DashboardServiceImplTest`（6）都被 failsafe 圈了进来，surefire 那一段 0 项。唯一红的是 `neighbourRowsStayOutOfTheOverview:171`，租户 1 的 `today` 从 `{calls:0,tokens:0,sessions:0,agents:0}` 变成 `{calls:2,tokens:900,sessions:2,agents:2}`，`assets.sessions` 从 0 变 1，正是邻居那两行漏了进来。断言是活的，不是恒真。
限定一条时间线：这一轮跑的时候隔离类只有邻居那一条用例，第二条「无租户行不入账」是之后补的（首次出现在 23:58 之后那一轮，`logs/it-dashboard3.log` 才是 2 项）。所以被变异证活的是邻居用例，第二条没单独跑过变异。

## 任务 9：前端整页组装与入口

**Files:** Modify `harnax-webui/src/pages/Welcome.tsx`；追加两个 `pages.ts`；改 `config/routes.ts` 与两个 `menu.ts`

- [x] **Step 1：`Welcome.tsx` 只做取数与编排**

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

屏序：`GreetingBar` → `TodayStatsRow` → `TrendCard` + 费用 → 两列 `RankList` → `AssetStrip` → `TodoStrip`；`error` 非空时整页一个 `Alert` + 一个重试按钮（C12），不做分区降级、不轮询。

- [x] **Step 2：租户名不额外发请求**：`API.CurrentUser` 里没有租户字段，`GreetingBar` 的 `tenantName` 留空即整段不渲染——不为一个标签去调 `/api/admin/tenant/{id}`，那会给首屏加一次可能无权限的读。
- [x] **Step 3：环比规则由 `metrics.ts` 单测钉住**——昨 0 且今 0 → 持平；昨 0 且今 >0 → 「新增」，不出现 `+Infinity%` 也不出现 100%；非有限值或负值 → 持平且无百分比；其余一位小数。
- [x] **Step 4：38 条 `pages.welcome.*` 中英同时落盘**（`pages.ts` 两侧计数相等：`agents/channels/mcp/models/sessions/skills/teams/users/title`、`dataAsOf`、`greeting`、`platform.{cli,note,title,tools}`、`quick.{agent,skill,tokenMonitor}`、`rank.deleted`、`refresh`、`tenant`、`today.{agents,calls,sessions,tokens}`、`todo.{activeUsers7d,drafts}`、`trend.{calls,fee14d,title,tokens}`）。
- [x] **Step 5：入口**：`config/routes.ts` 的 `/welcome` 去掉 `hideInMenu`、`name` 由 `welcome` 改 `dashboard`；`menu.ts` 两侧把 `menu.welcome` 换成 `menu.dashboard`。旧地址 `/welcome` 未改，收藏与登录后跳转不受影响。
- [x] **Step 6：快速入口指向迁移后的正式地址**：`/monitor/token-monitor`、`/monitor/skill-drafts`、`/agent/manager`、`/context/skill`（菜单迁移是用户未提交的改动，`/context/*` 旧地址已改 redirect，页面里不挂 redirect 壳）。

## 任务 10：验证闸门（全部由控制方重跑）

- [x] **Step 1：后端单测**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am test \
  -Dtest='DashboardServiceImplTest,!com.agnetix.harnax.admin.it.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > /tmp/dash-unit.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-unit.log | tail -2
```
期望：`EXIT=0` + `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。
实测：`EXIT=0`，`DashboardServiceImplTest` `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`（证据在 worktree 的 `logs/unit-dashboard.log`；下面每一步都在 worktree `tmp/worktrees/dashboard-overview` 上跑，不在主检出）。这是当时的计数，第四遍为榜的限定词补了一项，交付时是 7 项（见文末「复核四遍」）。

- [x] **Step 2：两个 IT（真 MySQL）**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/heqingsong/code/my_project/harnax/tmp/worktrees/dashboard-overview
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o -pl harnax-admin -am -Pintegration-test \
  -Dit.test='DashboardOverview*,DashboardTenantIsolation*' \
  -Dtest='Dashboard-none' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify > /tmp/dash-it2.log 2>&1; echo EXIT=$?
grep -h '^Tests run' /tmp/dash-it2.log | tail -5
```
期望：`EXIT=0`，出现 `Tests run: 2, Failures: 0, Errors: 0`（`DashboardOverviewIT`）与 `Tests run: 2, Failures: 0, Errors: 0`（隔离类，两条：邻居双向不污染、无租户行不属于任何工作区）；`ls harnax-admin/target/failsafe-reports/*.xml | wc -l` 非 0（`BUILD SUCCESS` 本身不是判据——漏了 `-Pintegration-test` 也照样 SUCCESS 而一条 IT 都不跑）。
实测：`EXIT=0`，`DashboardOverviewIT` 2 / `DashboardTenantIsolationIT` 2 / `DashboardServiceImplTest` 6，合计 `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`（证据 `logs/it-dashboard3.log`；前两轮 `it-dashboard.log`、`it-dashboard2.log` 是排查过程中的失败记录，保留作过程证据）。这是当时的计数，第四遍为同名模型行补了一条真库用例，交付时是 3 / 2 / 7 = 12（见文末「复核四遍」）。
- [x] **Step 3：全量 reactor 不退化**

```bash
/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn -o test -Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**,!com.agnetix.harnax.harness.memory.**,!com.agnetix.harnax.harness.minio.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > /tmp/reactor.log 2>&1; echo EXIT=$?
grep -c 'SUCCESS \[' /tmp/reactor.log
```
期望：`EXIT=0`、26 个 `SUCCESS [`。已知噪声只有 `harnax-session-router` 的 `RateLimiterTest`（并发窗口抖动，单跑即绿）。
实测：`EXIT=0`、`grep -c 'SUCCESS \['` = 26、日志尾部 `BUILD SUCCESS`，全篇无 `Failures` 非 0 的行；已知噪声 `RateLimiterTest` 本轮 `Tests run: 12, Failures: 0`（证据 `logs/reactor.log`）。
- [x] **Step 4：前端构建**

```bash
cd harnax-webui && npm run build > /tmp/webui-build.log 2>&1; echo EXIT=$?
```
期望：`EXIT=0` 且产出 `dist/`。`npx tsc --noEmit` 在本仓会报几百条既有噪声（未生成 `.umi` 类型），不作为闸门。
实测：改动过程中共跑 8 轮，每轮 `EXIT=0` 且以 `event - Build index.html` 收尾（末轮 `logs/webui-build8.log`），产物在 worktree 的 `harnax-webui/dist/`。worktree 的 `node_modules` 是指向主检出同名目录的软链——依赖没装第二份，但主检出的 `node_modules` 因此是共享的，不要在它上面做 `npm ci`。
- [x] **Step 5：biome lint**

```bash
cd harnax-webui && npx @biomejs/biome lint src/pages src/locales > /tmp/webui-lint.log 2>&1; echo EXIT=$?
```
本仓基线在这条宽命令上是红的：`Checked 152 files` / `Found 34 errors, 203 warnings`，命中文件全部是本次没碰过的既有页面（如 `src/pages/token-monitor/index.tsx` 的 `react(noArrayIndexKey)`）。所以能当闸门的只有窄范围——把被检文件收到本次改动集：

```bash
cd harnax-webui && npx @biomejs/biome lint src/pages/welcome src/locales/en-US/pages.ts src/locales/zh-CN/pages.ts
```
实测：`Checked 7 files in 56ms. No fixes applied.`，无诊断退出 0。宽命令的红是本方案范围外的既有债，证据留在 `logs/webui-lint.log`。
- [x] **Step 6：metrics 单测**

```bash
cd harnax-webui && npm test -- src/pages/welcome/metrics.test.ts > /tmp/jest-metrics.log 2>&1; echo EXIT=$?
grep -E 'Tests:|Suites:' /tmp/jest-metrics.log
```
上面这条宽命令在本机跑不起来：`jest.config.ts` 加载即失败（`Cannot find module '@umijs/max/test'`），在主检出上同样复现，属既有环境问题、与本方案无关。可复现的跑法是把同一份配置经 CJS 解析成 JSON 再交给 jest（脚本 `logs/jest-config-dump.cjs` → `logs/jest.resolved.json`）：

```bash
cd harnax-webui && npx jest --config ../logs/jest.resolved.json src/pages/welcome/metrics.test.ts
```
实测：`EXIT=0`、`Test Suites: 1 passed`、`Tests: 13 passed, 13 total`（`deltaOf` 7 项 / `deltaTone` 1 项 / `formatCount` 3 项 / `formatTokens` 2 项，证据 `logs/jest-metrics2.log`）。复核第一轮为「差值抹到 0.0%」补了一条双向用例，到 14 项（`logs/jest-metrics3.log`）；第四遍又为榜标签补了 5 项，交付时的套件是 19 项（`deltaOf` 8 项 / `deltaTone` 1 项 / `formatCount` 3 项 / `formatTokens` 2 项 / `rankLabels` 5 项，证据 `logs/jest-metrics4.log`，见文末「复核四遍」）。
- [x] **Step 7：真渲染**——`npm run start:dev` 起 dev server 后用无头 Chromium 打开 `/welcome`，逐屏核对：四卡读数、14 点折线、两列榜（≤5 行）、8 格资产 + 平台行的「非本租户」小字、待审草稿链接、`serverTime` 显示的「数据截至」；再看 console 无未捕获异常、Network 里 `/api/admin/dashboard/overview` 一条 200。
本步没有走 dev server：部署栈是用户正在用的，起新端口不干扰但也不必要，而部署的 frontend 容器是自签 https、浏览器打不开。实际通道是**静态 `dist/` + 取证代理 + CDP 无头 Chromium**——`logs/forensic-server.mjs`（8123）只桩掉 `GET /api/admin/dashboard/overview`，其余 `/api/*` 原样转发到宿主 28080 的真 admin；登录态从真标签页抓下来写进 `logs/login-seed.json` 再由 `?seed=1` 注入。桩的载荷 `logs/payload.json` 是手写 SQL 在部署库上真跑出来的（`logs/overview-from-sql.sql`），不是编的。
逐屏核对已完成：桌面 1400（`logs/trend-desktop-final.png`，2800×2334 @2x）、移动 390（`logs/trend-mobile3.png`，780×4134 @2x）、点「刷新」重新取数（`logs/probe-refresh.mjs`）、注入 500 的错误态（`logs/welcome-error.png`，走 `GET /__fail?on=1` 运行期开关）、错误态后重试恢复（`logs/welcome-recovered.png`）。console 除故意注入的那条 500 之外无未捕获异常。
**真渲染抓到一个只靠构建和单测抓不到的缺陷**：ProLayout 侧栏在首屏带一段宽度过渡，而 `@ant-design/plots` 的 autoFit 只在挂载那一刻量一次容器、此后只跟 window resize。量到过渡中途的值，趋势图画布就一直比卡片宽 192px，整页被顶出横向滚动条（1400 视口下 `scrollWidth` 1547）。修法是自己用 `ResizeObserver` 盯住卡片宽度、把宽度显式交给图表并关掉 autoFit（`TrendCard.tsx`）；先用「数据为空就不挂图」试过一次，改了溢出不了——已在产物里 grep 到新代码才确认那条假设是错的。
- [x] **Step 8：SQL 对账**——在部署库上把页面读数与手写 SQL 对上（口径逐条对，不只看总数）：

```bash
docker exec harnax-mysql mysql -uroot -proot123456 harnax_admin -e \
 "SELECT (SELECT COUNT(*) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS calls,
         (SELECT COALESCE(SUM(total_token),0) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS tokens,
         (SELECT COUNT(DISTINCT session_id) FROM token_stats WHERE tenant_id=1 AND ts>=CURDATE()) AS sessions,
         (SELECT COUNT(*) FROM agent WHERE tenant_id=1 AND active=1) AS agents;"
```
期望：与页面上「今日模型调用 / Token 消耗 / 活跃会话」以及资产条的智能体格逐字相等。
实测（为了让今日窗口非零，先按 `logs/seed-forensic.sql` 临时种 5 行 id 970001-970005，取完证按 id 删除并复查残留：`id BETWEEN 970001 AND 970005` 与 `session_id LIKE 'dashboard-forensic%'` 均为 0）：`calls=3`、`tokens=225000`、`sessions=2`、`agents=1`，同时段昨日 `calls=2`、`tokens=24000`，14 天费用合计 `6`——页面渲染成 今日模型调用 3（环比 +50.0%）、Token 225.0K（+837.5%）、活跃会话 2、智能体 1，右下角 ¥6，趋势末两点 `10-05 calls=16` / `10-06 calls=3`，榜首 `测试 430.7K`，逐字一致。
这条对账证明的是**前端映射与格式化**（补零、单位、环比符号）；端点自身的 SQL 口径不在这里证，由 `DashboardOverviewIT` + `DashboardTenantIsolationIT` 在 Testcontainers 真 MySQL 上钉住。两者的分工要写清：本步的载荷是手写 SQL 直接产出的，而**端点从未在部署的 admin 镜像里跑过**（见任务 11）。

## 任务 11：部署与回滚

- [x] **Step 1：无迁移、无新环境变量、无新容器**。改动只在 admin jar 与前端静态产物；`harnax-deploy` 的 compose、`nginx.conf`（已有 `location /api/admin/`）、Flyway 基线都不动。
三条判据已核实：改动集里 `git status --short -- 'harnax-*/src/main/resources/db/migration'` 为空；新增的三个后端文件（`DashboardController.kt`、`DashboardServiceImpl.kt`、`DashboardMapper.kt`）不含 `@Value` / `System.getenv` / `@ConfigurationProperties`，即没有新配置项；`git status --short -- harnax-deploy` 为空，且 `harnax-deploy/nginx.conf:191` 已有 `location /api/admin/`，新增子路径不需要动代理。
- [ ] **Step 2：部署**（未执行）：`bash harnax-deploy/build.sh admin` 后 `bash harnax-deploy/deploy-service.sh admin`；前端同理构 `frontend` 镜像。端口按既有规则（对外 80/443，admin 宿主 28080，MySQL 宿主 23306）。
这一步会重建并重启用户正在使用的 `harnax-admin` / `harnax-frontend` 容器，属于影响他现场的共享动作，未获点头前不做。**因此端点至今没有在部署镜像里跑过一次。**
- [ ] **Step 3：线上判据**（未执行，随 Step 2）：`docker compose -f harnax-deploy/docker-compose.yml ps` 全 `up`；带管理员令牌 `curl -s localhost:28080/api/admin/dashboard/overview` 返回 `code=200` 且 `trend` 数组长度 14；`logs harnax-admin` 无 `Failed to build dashboard overview`。
- [x] **Step 4：回滚形状**：只回退这两个镜像即可——没有 DDL、没有迁移、没有配置项，回滚不残留。前端旧 `/welcome` 路径未改，回退后菜单入口仍在。这条由 Step 1 的三条判据直接得出。

---

## 验收清单

1. 首屏不再有任何写死的数字：`Welcome.tsx` 里没有字面量读数，`grep -n "value=\"1[0-9][0-9]\"" src/pages/Welcome.tsx` 为空。
2. `GET /api/admin/dashboard/overview` 无查询参数，租户只从请求凭据解析；带 `X-Tenant-ID` 换工作区时读数随之变化。
3. 今日四卡 = 模型调用 / Token / 活跃会话 / 活跃智能体，每卡一行「对昨日同时段」环比；昨 0 的两种形状显示持平/新增。
4. 趋势恒 14 点，缺数据日为 0；右下角只有 14 天费用合计，卡片里没有「今日费用 ¥0.00」。
5. 两个榜各 ≤5 行、行宽按榜内最大值归一、空名行显示「（已删除）」而不是被丢弃；榜内出现同名条目时那几行带限定词（模型榜=provider 名，智能体榜恒为空）。
6. 资产 8 格 + 平台行分排并标明「平台级，非本租户」。
7. 中英两份 `pages.ts` 的 `pages.welcome.*` 计数相等；菜单项在中英两侧都叫「总览 / Overview」（`menu.dashboard`），且 `/welcome` 位于菜单首位、`/` 仍重定向到它。
8. 后端：单测 7 项、两个 IT 共 5 项（`DashboardOverviewIT` 3 项 + `DashboardTenantIsolationIT` 2 项）全绿；全量 reactor 26/26 SUCCESS（`logs/it-round4.log`、`logs/reactor-round4.log`）。
9. 前端：`npm run build` exit 0、改动文件窄范围 biome lint 零诊断、`metrics.test.ts` 19 项全绿、并由「静态产物 + 取证代理 + CDP 无头 Chromium」在 1400 与 390 两档真实渲染核对读数。
10. 未新增依赖、未改 Flyway、未改安全配置、未改 nginx。

## 复核四遍（流程通顺 + 边界充分）

四遍各查一个方向，每遍改完都回到真 DOM 上证。前三遍只动前端；第四遍动的是产品代码——后端两处（`DashboardOverviewResponse.kt`、`DashboardServiceImpl.kt`）、前端三处（`metrics.ts`、`RankList.tsx`、`typings.d.ts`），外加四个测试文件（后端单测 +1 项、真库 IT +1 项、前端 +5 项），任务 8 与任务 10 的那两轮结论要按本节末尾的重跑记录读。

**第一遍：取数链路逐跳核载荷**（Controller → Service → mapper 别名 → DTO → 前端 service → 卡片）。链路是通的：四条新语句（`getDashboardWindowStats`、`getDashboardDailyTrend`、`getTenantAssetCounts`、`getPlatformCounts`）的 SQL 别名与 `DashboardOverviewResponse.kt` 的取值键逐字对得上，两个榜复用既有 `aggregateByAgent`/`aggregateByModel` 的 `grandTotalToken`，14 天费用复用既有 overall 语句；`ResultVo` 的 `code`/`data`/`message` 三键在前端信封类型上都在，`errorThrower` 只在 `code !== 200` 时抛。
这一遍抓到一处边界不成立：环比的方向跟的是未取整的比值。`deltaOf(3_200_000, 3_199_000)` 是 0.031%，文案按一位小数抹成 `0.0%`，箭头却仍判「上升」，页面会出现「较昨日同时段上升 0.0%」这句自相矛盾的话。
改法（`metrics.ts` 的 `deltaOf`）：先把百分比格式化成一个字符串，方向跟着这个四舍五入之后的数走——抹到 `0.0%` 的差值判持平。`metrics.test.ts` 补一条双向用例（13 → 14 项）。

**第二遍：错误与降级路径**。C12 定的是整页一个错误态，但实现里页面上是两处：错误卡之外，umi 的 `errorThrower` 抛的 BizError 还会被 `requestErrorConfig.ts` 的 `errorHandler` 弹一条全局红 toast。
改法：这一屏的取数带 `skipErrorHandler: true`（仓内既有先例——`team/relatedSessions`、`agentTask` 的删除与触发），错误只落在页面那块卡上。代价是全局那条 401 → 登录页的跳转一并被关掉，所以这条链路本页自己接：命中 `requestError.response.status === 401` 就清掉 `currentUser`/`tokenInfo`，再 `history.push('/login?redirect=%2Fwelcome')`。服务端那句失败消息是写死的英文常量、原因在 admin log 里，页面只说自己那句 `pages.welcome.loadFailed`。

**第三遍：加载态与空态**。手动刷新时 `loading` 由 false→true 会把已经加载的四卡、趋势、两个榜、资产条整体换回骨架，读数闪一下就没。
改法：分区骨架只在「一个读数都还没有」时给（`firstLoad = loading && data === null`），刷新中的反馈交给问候条那个转动图标和重试按钮上的 `loading`。

真 DOM 证据（`logs/probe-review.out`、`logs/probe-chips.out`；通道 = 重建后的 `dist/` + `logs/forensic-server.mjs` 桩 + CDP 无头 Chromium）：
- 请求进行中（1200 ms 的读，在第 500 ms 取快照）：`cards: ["3","225.00K","2","1"]`、`skeletons: 0`——刷新不再把读数换成骨架；
- 500：`errorTexts: ["总览数据加载失败"]`、`toasts: 0`、`messageNodes: []`——错误态只剩一处；
- 401：`path: "/login"`、`search: "?redirect=%2Fwelcome"`、`storageCleared: true`——关掉全局处理之后跳转仍通；
- 环比 fixture（把昨日 `tokens` 改成 3,199,000，使今日差 0.03%）：四条文案 `["较昨日同时段上升 66.7%","与昨日同时段持平","与昨日同时段持平","与昨日同时段持平"]`、读数 `["5","3.20M","2","1"]`——`0.0%` 不再带方向。fixture 已按 `logs/payload.orig.json` 还原。

闸门在同一份代码上重跑：`metrics.test.ts` 14 项全绿（`logs/jest-metrics3.log`）、窄范围 biome lint `Checked 6 files` 零诊断（`logs/lint-welcome3.log`）、`npm run build` `EXIT=0`（`logs/build-welcome3.log`）。

**第四遍：榜的每一行在页面上读得出是哪一行**。前三遍核的是数字对不对，这一遍核数字挂在哪一行上——榜的标签。部署库真跑出来的载荷里 `topModels` 有两行同名：`qwen3.7-flash` 250,710 与 `qwen3.7-flash` 180,000。根因不是脏数据，`aggregateByModel` 按 `t.chat_model_id, m.name, p.name` 分组，同一个显示名挂在两个 provider 下就是合法的两行；`providerName` 那条语句本来就选出来了，是 `DashboardRankItem` 把它丢在 DTO 那一跳。页面上两行一模一样的标签会被读成渲染重复。
改法：后端 `DashboardRankItem` 加一个非空 `qualifier`（默认空串，跟整个响应「无 null 键」的契约一致），`mapToRankItem` 多一个可选 `qualifierKey`，只有模型榜传 `"providerName"`，智能体榜没有第二列、留空；前端 `metrics.ts` 新增纯函数 `rankLabels`，只给真正撞名的行补 `名字（provider）`，`RankList` 渲染它算出的标签而不是裸 `name`。榜仍然不带 id：页面上没有要回到某一行的动作，把 id 放进线上契约是给不存在的动作做准备。
两处边界跟着写进实现：`qualifier` 缺失或全空白时原样返回（模型行被删时 provider 一并没了，那两行只能同名，不编造区分度）；空名行不计入撞名统计，否则一个真实名字会莫名带上括号。
真 DOM 证据（`logs/probe-ranklabels.out`）：模型榜四行读作 `qwen3.7-flash（阿里百炼）` / `qwen3.7-flash（内部网关）` / `gpt-5` / `（已删除）`——撞名的两行各带 provider，没撞名那行原样，空名行仍走「（已删除）」。夹具是 `logs/payload.json` 加 `qualifier` 得到的：两行同名的 Token 数取自部署库真跑出来的载荷，两个 provider 名是夹具造的，因为对部署库的只读查询被权限层拒绝、线上真名取不到。探针跑完已按 `logs/payload.orig.json` 还原，`diff -q` 与线上载荷逐字相同。
变异双向：去掉 Service 那一处的 `"providerName"`，`DashboardOverviewIT` 红在 `sameNamedModelRowsCarryTheirProvider`（`Tests run: 3, Failures: 1`，actual `<[, ]>`，`logs/it-mutation-qualifier.log`）；`rankLabels` 去掉撞名判定红 2 项、改成永不补限定词红 1 项。两处都已还原并复绿，还原后 `grep` 零残留。
对账工具同步：`logs/overview-from-sql.sql` 的 `topModels` 补上 provider 连接与 `qualifier`，`topAgents` 输出空串，让手工对账与线上契约同形。
闸门在还原后的同一份代码上重跑：后端 `spotless:apply` → `test-compile` → `-Pintegration-test` 一段给出 surefire `Tests run: 7`（`DashboardServiceImplTest`）与 failsafe `Tests run: 3`（`DashboardOverviewIT`）+ `Tests run: 2`（隔离类），`BUILD SUCCESS`（`logs/it-round4.log`）；前端 `Tests: 19 passed`（`logs/jest-metrics4.log`）、窄范围 lint `Checked 5 files` 零诊断（`logs/lint-round4.log`）、`npm run build` `EXIT=0`（`logs/build-round4.log`）。

## 记账（未验）

- **手动刷新失败会把已加载的整页换成错误卡**：本轮把「一处错误态」做实了（不再叠加全局 toast），但没改「失败即丢读数」这个形状——那是 C12 的字面口径，且只在手动刷新那一次触发（页面不轮询）。要改成「保留旧读数 + 顶部一条失败提示」属于口径变更，等拍板。
- **`tenantAndTimeWindow` 的 `<if>` 窗口守卫是宽松方向**：调用方传 null 或空串窗口时，SQL 少一条 `ts` 条件、静默扩到全历史，而不是报错。dashboard 侧三处调用都先把边界格式化成 `yyyy-MM-dd HH:mm:ss` 字符串再传（`DashboardServiceImpl`），但这个片段与 Token 监控页共用十几条语句，新增调用方要自己保证非空；把它改成硬失败得先确认 Token 监控那条链路里没有合法的空调用。
- **端点没有在部署的 admin 镜像里跑过一次**（本方案唯一残留的功能性未验项）：任务 11 的 Step 2/3 未执行，因为重建 `harnax-admin` 会重启用户正在使用的容器。目前的覆盖是「IT 在 Testcontainers 真 MySQL 上走完整过滤器链打到 `/api/admin/dashboard/overview`」+「前端在桩载荷上真渲染」，两段之间的接缝（部署镜像里的 Spring 装配、真库上的执行计划）要靠那一次部署才能闭。
- **挂载宽度缺陷的修法只扫了两档视口**：`ResizeObserver` + `autoFit:false` 在 1400 与 390 上验过（`logs/trend-desktop-final.png`、`logs/trend-mobile3.png`），侧栏折叠态、768 断点附近、以及拖动窗口的中间态没逐档扫。溢出这条判据（`document.documentElement.scrollWidth <= innerWidth`）在两档都成立。
- **趋势是两张独立折线，代价是跨序列同刻对比要分别 hover**：否掉同轴双序列（DualAxes）的原因是两者差着四个数量级，共用一根线性轴会把调用次数压成贴底的直线。当前形态下每个 tooltip 只报自己那条。
- **`labelAutoHide` 在 `@antv/g2` 5.4.8 上是静默 no-op**：截图逐字节相同才发现。窄屏的日期刻度靠手写 `tickFilter` 隔三取一（`TrendCard.tsx`），这个「三」是按 14 点在 390 宽下的实测排布定的——将来加时间窗选择器或点数变化，这里要重估而不是沿用。
- **`metrics.test.ts` 不在常规命令能跑到的路径上**：`jest.config.ts` 在本机加载即失败（`Cannot find module '@umijs/max/test'`），主检出同样复现。只能靠 CJS 解析出的 `logs/jest.resolved.json` 跑；若 CI 用的是同一条 `npm test`，这 19 项可能从未被执行过。
- **前端 lint 的宽范围基线本来就是红的**：`biome lint src/pages src/locales` = 34 errors / 203 warnings，命中文件全是本次没碰过的既有页面。本方案只保证改动文件零诊断。
- **构建残留 `harnax-webui/src/.umi-undefined/`**：`harnax-webui/.gitignore` 只列了 `.umi`、`.umi-production`、`.umi-test`，这个后缀不在忽略内。提交时不要带上它。
- **落盘位置两份分开**：设计稿与实施方案只在主检出的 `prod_doc/`，代码改动只在 worktree `tmp/worktrees/dashboard-overview`（分支 `dashboard-overview`，从 `98b79301` 切出），两边尚未汇合；整套改动至今未 `git add`，提交与合并形状由用户决定。
- **跨日边界**：两个 IT 把行写在 `LocalDateTime.now()`，若用例恰好跨过午夜 00:00，「今日」窗口会位移一天而使 delta 失效。触发概率极低但非零，失败表现是这一类用例红，不是产品缺陷。
- **隔离 IT 同时依赖一条既有行为**：以邻居身份读数靠的是 `X-Tenant-ID` + 管理员令牌跳过成员校验（`BaseAdminIT` 已写明，`ModelTenantIsolationIT` 已在用）。这条本身不是本方案的被测对象，所以该类若红，第一步要分清是谓词漏了还是这条前提变了。
- **新文件注释里引用了设计小节号**（如「设计 §4.2」「（I3）」，共 17 处，全在前端 6 个文件：`sections.tsx` 6 / `metrics.ts` 4 / `RankList.tsx` 3 / `Welcome.tsx` 2 / `TrendCard.tsx` 1 / `metrics.test.ts` 1；后端与 mapper 侧的新增文件一条都没有）：已决保留。理由是两份文档就与代码同仓（`prod_doc/`），且仓内已有同形先例（`InternalApiControllerTest.kt:995` 的「（I5）」）。方案文档自身仍保持不引用其他 md。
- **`initialState.currentUser` 的来源是 `localStorage`**：本次只是不再于页面内直接读它（改走 `useModel`），登录态本身如何写入不在此方案范围。

## 范围外

- Token 监控页自身的重构；全局管理员跨租户视图；时间窗选择器；会话/沙箱活跃数（属 session-router 另一套数据面）；`api_call_log` 请求量；租户切换 UI。
