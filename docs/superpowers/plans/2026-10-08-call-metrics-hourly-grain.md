# 调用监控小时档与维度实名化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `tool_invocation_stats` 从日行改成小时行、时间窗口选到整点，并把调用监控页的「主体」拆成工具／MCP／CLI／智能体／会话五个实名维度。

**Architecture:** 折算侧只留小时一档，日／周／月由小时求和；读侧 `ToolMetricsServiceImpl` 统一把窗口解析成整点对，再按维度分派到聚合表（tool／mcp／cli）或明细表（agent／session）；名称在 SQL 里带出，前端只做展示。

**Tech Stack:** Kotlin 2.x + Spring Boot + MyBatis(XML) + Flyway + MySQL 8 + JUnit5；React 18 + antd 5 + `@umijs/max` + dayjs + Biome。

**Spec:** `docs/superpowers/specs/2026-10-08-call-metrics-hourly-grain-design.md`

## Global Constraints

- 不考虑历史兼容，按全新设计落；已应用的 Flyway 迁移（V1~V4）一个字都不改，schema 变更走 V5 前向增量。
- 代码注释、KDoc、日志串一律英文；commit message 中文。
- 前端文案必须 `src/locales/zh-CN/pages.ts` 与 `src/locales/en-US/pages.ts` 两份同时落。
- 任何读 SQL 必须自带租户谓词（admin 不设 MyBatis 租户拦截器）。
- 门禁：后端 `mvn test` 相关模块 + 指标 IT（需 Docker，跑法见本机配方）；前端 `npx max build` + 改动文件逐个 `npx @biomejs/biome lint <file>`，**禁止 `biome check --write`**。
- 工作目录是 worktree `/Users/heqingsong/code/my_project/harnax/.worktrees/call-metrics-hourly`，分支 `feat/call-metrics-hourly`；不 push；收尾合回 `kotlin-dev`。
- 时间形状约定：整点串一律 `yyyy-MM-dd HH:mm:ss` 且分秒为 0；请求侧接受 `yyyy-MM-dd HH:mm` 与 `yyyy-MM-dd` 两种。

---

### Task 1: 聚合表升到小时档（schema + 折算 + 清理配对）

**Files:**
- Create: `harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql`
- Modify: `harnax-entity/src/test/resources/schema-test.sql`（`tool_invocation_stats` 建表块）
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`（`upsertDay`→`upsertHour`、三个读法的边界列）
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`（`selectUnrolledDates`→`selectUnrolledHours`、`deleteRolledOut` 的 EXISTS）
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt`、`ToolInvocationLogMapper.kt`
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt`（`statDate` 属性跟着列改名）
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupServiceTest.kt`、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolInvocationRollupIT.kt`、`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapperTest.kt`

**Interfaces:**
- Produces：`ToolInvocationStatsMapper.upsertHour(statHour: String): Int`；`ToolInvocationLogMapper.selectUnrolledHours(floor: String): List<String>`（元素 `yyyy-MM-dd HH:mm:ss`）；`deleteRolledOut(before: String): Int` 不变；聚合表列名 `stat_hour datetime`。
- Consumes：无（本任务是链首）。

- [ ] **Step 1: 写失败用例——同一小时折两次数字不变，且迟到一小时的明细不再被计入**

在 `ToolInvocationRollupServiceTest.kt` 里把按天命名的用例改成按小时，并加这一条（mock 两个 mapper，`rollUp()` 返回值是折算次数）：

```kotlin
@Test
fun `rollUp folds the current and the previous hour whatever the pending set says`() {
    whenever(logMapper.selectUnrolledHours(any())).thenReturn(mutableListOf())
    whenever(logMapper.deleteRolledOut(any())).thenReturn(0)
    val rolled = service.rollUp()
    val hours = argumentCaptor<String>()
    verify(statsMapper, atLeast(2)).upsertHour(hours.capture())
    // 上一个整点与当前整点必须各折一次：:05 触发时当前整点还在写，上一整点收尾那一段没人补就会丢
    assertTrue(hours.allValues.size >= 2)
    assertTrue(rolled >= 2)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolInvocationRollupServiceTest -q`
Expected: 编译失败 `unresolved reference: upsertHour` / `selectUnrolledHours`（改名任务未做）

- [ ] **Step 3: 落 V5 迁移**

`harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql`：

```sql
-- Fold tool_invocation_stats one hour at a time instead of one day at a time.
-- The day rows are not carried over: a day row sitting at 00:00 would be read back as the 00:00 hour
-- holding a whole day's counts, and selectUnrolledHours would call that hour already folded. The detail
-- table still holds the retention window, so every hour inside it is re-folded on the next rollup run.
DELETE FROM `tool_invocation_stats`;

ALTER TABLE `tool_invocation_stats`
    DROP INDEX `uk_tool_invocation_stats_day`,
    DROP INDEX `idx_tool_invocation_stats_tenant_date`,
    CHANGE COLUMN `stat_date` `stat_hour` datetime NOT NULL COMMENT 'Hour the calls fall in, `ts` truncated to the hour; minutes and seconds are always zero',
    ADD UNIQUE KEY `uk_tool_invocation_stats_hour` (`stat_hour`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
    ADD KEY `idx_tool_invocation_stats_tenant_hour` (`tenant_id`,`stat_hour`);
```

- [ ] **Step 4: 同步 schema-test.sql 的建表块**

把 `harnax-entity/src/test/resources/schema-test.sql` 里 `tool_invocation_stats` 的 `` `stat_date` date NOT NULL COMMENT 'Day of the calls, taken from tool_invocation_log.ts' `` 换成 V5 之后 `SHOW CREATE TABLE` 的那一行（`stat_hour` datetime + 注释），并把两条 KEY 名换成 `uk_tool_invocation_stats_hour` / `idx_tool_invocation_stats_tenant_hour`，列序与 MySQL 8 的重放结果逐字一致——`SchemaBaselineDriftIT` 就是拿这两份对账的。

- [ ] **Step 5: 改 `upsertDay` 为 `upsertHour`**

`ToolInvocationStatsMapper.xml`：`<insert id="upsertDay">` → `<insert id="upsertHour">`，插入列 `stat_date` → `stat_hour`，SELECT 首列 `#{statDate}` → `#{statHour}`，边界两行改成：

```xml
        WHERE l.ts &gt;= #{statHour}
        AND l.ts &lt; DATE_ADD(#{statHour}, INTERVAL 1 HOUR)
```

同时把该语句上方注释里「the day is bounded by an instant range」那段的「day」按小时重述（保留「不对列套函数所以可走索引」这条理由不动）。

- [ ] **Step 6: 改欠账与释放两条配对语句**

`ToolInvocationLogMapper.xml`：

```xml
    <select id="selectUnrolledHours" resultType="string">
        SELECT DATE_FORMAT(l.ts, '%Y-%m-%d %H:00:00') AS stat_hour
        FROM tool_invocation_log l
        WHERE l.tenant_id IS NOT NULL
        AND l.ts &gt;= #{floor}
        AND NOT EXISTS (
            SELECT 1 FROM tool_invocation_stats s
            WHERE s.stat_hour = DATE_FORMAT(l.ts, '%Y-%m-%d %H:00:00')
            AND s.tenant_id = l.tenant_id
        )
        GROUP BY DATE_FORMAT(l.ts, '%Y-%m-%d %H:00:00')
        ORDER BY stat_hour ASC
    </select>

    <delete id="deleteRolledOut">
        DELETE FROM tool_invocation_log
        WHERE ts &lt; #{before}
        AND (
            tenant_id IS NULL
            OR EXISTS (
                SELECT 1 FROM tool_invocation_stats s
                WHERE s.stat_hour = DATE_FORMAT(tool_invocation_log.ts, '%Y-%m-%d %H:00:00')
                AND s.tenant_id = tool_invocation_log.tenant_id
            )
        )
    </delete>
```

- [ ] **Step 7: 改两个接口签名与实体属性**

`ToolInvocationStatsMapper.kt`：`upsertDay(@Param("statDate") statDate: String)` → `upsertHour(@Param("statHour") statHour: String)`，KDoc 里「Recompute one day in full」→「Recompute one hour in full」，参数说明改成 `Hour to fold, yyyy-MM-dd HH:mm:ss with zero minutes and seconds`。
`ToolInvocationLogMapper.kt`：`selectUnrolledDates` → `selectUnrolledHours(@Param("floor") floor: String): List<String>`，KDoc 的 `@return` 改成 `Pending hours, oldest first, yyyy-MM-dd HH:mm:ss`；`deleteRolledOut` 的注释里 gate 的列名跟着换。
`ToolInvocationStats.kt`：`var statDate: LocalDate = LocalDate.now()` → `var statHour: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)`，import 从 `java.time.LocalDate` 换成 `java.time.LocalDateTime` 并补 `java.time.temporal.ChronoUnit`；`@Schema(description = "Day of the calls")` 改成 `"Hour the calls fall in"`，类头 KDoc 里「One day of [ToolInvocationLog]」→「One hour of [ToolInvocationLog]」、键名 `(statDate, ...)` → `(statHour, ...)`。这个类目前没有代码引用，但 `resultType` 走 map 之外它仍是这张表的列映射声明，属性不改就映射到一个不存在的列。

- [ ] **Step 8: 改折算任务本体**

`ToolInvocationRollupService.kt` 的 `rollUp()`：

```kotlin
    fun rollUp(): Int {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        // The current hour is folded whether or not it is pending: it is still being written, and a fold that
        // waited for the hour to close would leave the page an hour behind on every read. The previous hour is
        // folded for the mirror reason at the other end — the sweep fires at :05, so rows that arrive between
        // the last fold of an hour and its close are already covered by an aggregate row, never re-enter the
        // pending set, and would be released by deleteRolledOut uncounted.
        val hours = (
            toolInvocationLogMapper.selectUnrolledHours(UNROLLED_FLOOR) +
                currentHour.minusHours(1).format(HOUR) + currentHour.format(HOUR)
            ).distinct()

        var rolled = 0
        for (statHour in hours.sorted()) {
            if (LocalDateTime.parse(statHour, HOUR).isAfter(currentHour)) continue
            toolInvocationStatsMapper.upsertHour(statHour)
            rolled++
        }

        val before = LocalDateTime.now().minusDays(retentionWindowDays).format(TIMESTAMP)
        val deleted = toolInvocationLogMapper.deleteRolledOut(before)
        log.info("Tool invocation rollup: {} hour(s) recomputed, {} detail row(s) older than {} released", rolled, deleted, before)
        return rolled
    }
```

companion 里加 `private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")`，`UNROLLED_FLOOR` 改成 `"1970-01-01 00:00:00"`，`import java.time.temporal.ChronoUnit`；类头 KDoc 的「one day at a time」→「one hour at a time」，`retentionWindowDays` 那段关于 cutoff 的说明按小时重述（保留「窗口为 0 会毁数据而不是只停计数」这条理由）。

- [ ] **Step 8b: 把 harnax-entity 的折算用例集改锚到小时内**

`harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapperTest.kt` 是这张聚合表的真栈门禁，Task 1 之后它必然编译不过（`upsertDay` / `selectUnrolledDates` 都没了），而且它的夹具形状会**静默失真**：19 条明细现在按 1..19 点散在同一天，日档把它们折进一行；小时档会折成 19 个桶，`expectedVectors` 里每行的 `calls` 都变成 1，四条不变量断言全部对不上——而失败信息只会说「数字不对」，看不出是夹具的形状跟着粒度变了。

修法是把散点那根轴从小时换成分钟：分钟 1..19 正好都落在同一个小时内，四条「换任何一条 outcome 谓词都会改变本行」的判据一条都不失。`row(1, …)` … `row(19, …)` 的 19 个调用点一个都不用动（1..19 在两根轴上都是互不相同的值）。

夹具两个字段：

```kotlin
    /**
     * One hour for every case, read once instead of at each use: a run that crossed the hour boundary
     * between seeding and folding would otherwise fold a different hour than the one it wrote.
     */
    private val fixtureHour: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(3)

    /** A second hour, equally past the retention cutoff, held by a row that names no tenant at all. */
    private val orphanHour: LocalDateTime = fixtureHour.minusHours(1)
```

散点轴换名换语义，`row(...)` 体内的三行时间赋值跟着换、其余字段逐字不动：

```kotlin
    private fun at(hour: LocalDateTime, minute: Int): LocalDateTime = hour.plusMinutes(minute.toLong())

    private fun row(
        minute: Int,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long? = null,
        cliId: Long? = null,
        tenantId: Long? = TENANT_ID,
        hour: LocalDateTime = fixtureHour,
    )
```

帮助函数与两处裸 SQL：`private fun day(): String` → `private fun hour(): String = fixtureHour.format(HOUR_STAMP)`（companion 加 `private val HOUR_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")`，并补 `import java.time.temporal.ChronoUnit`）；`cutoff()` 从日界改成 `"${fixtureHour.plusHours(1).format(HOUR_STAMP)}"`（只要越过最后一个被折的小时即可，两个 seeded 小时都在它之前）；`statsRows()` 的 `stat_date = '${day()}'` → `stat_hour = '${hour()}'`；`detailCount` 的 `ts >= '$day' AND ts < DATE_ADD('$day', INTERVAL 1 DAY)` → `ts >= '$hour' AND ts < DATE_ADD('$hour', INTERVAL 1 HOUR)`。

调用点：`statsMapper.upsertDay(day())` → `statsMapper.upsertHour(hour())`（6 处）；`logMapper.selectUnrolledDates(...)` → `selectUnrolledHours(...)`（3 处，其中 `orphanDay.toString()` 换成 `orphanHour.format(HOUR_STAMP)`）；`detailCount(TENANT_ID, fixtureDay)` → `detailCount(TENANT_ID, fixtureHour)`；`row(2, …, day = orphanDay)` / `row(3, …, day = orphanDay)` 的 `day =` 换成 `hour =`。

三条用例名与注释里的「day」按小时重述（`a day not yet rolled up survives the retention sweep` → `an hour not yet folded survives the retention sweep`，`the rollup only owes a day it has not folded yet` → `… only owes an hour it has not folded yet`），类头那段 `@MybatisTest` 注释里的「one tenant and one day」改成「one tenant and one hour」；`withInitScript("schema-test.sql")` 不动，Step 4 已经把那份 schema 改到小时档形状。

- [ ] **Step 9: 跑折算单测与实体侧真栈用例确认通过**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolInvocationRollupServiceTest -q`
Expected: `Tests run: N, Failures: 0`

Run: `mvn -o -pl harnax-entity test -Dtest=ToolInvocationStatsMapperTest -q`（需 Docker；按本机配方先探 Docker，没在跑就把这条记成未验，不要改判据）
Expected: 全绿——`expectedVectors` 的 7 组逐条对上（该文件注释里的 "seven groups the key can build"），且 `the rollup only owes an hour it has not folded yet` 通过

- [ ] **Step 10: 跑真栈折算 IT 与漂移 IT（需 Docker）**

Run: `mvn -o -pl harnax-admin test -Dtest='ToolInvocationRollupIT+SchemaBaselineDriftIT' -q`
Expected: 全绿。`ToolInvocationRollupIT` 里若有按天的断言（`selectUnrolledDates`、`upsertDay`、按 `stat_date` 读回），本步一起改成按小时。幂等与「跨粒度求和等值」这两条落在这份 IT 上（`insert(...)` 用这份 IT 已有的明细插入帮助函数，没有就照 `ToolMetricsReadIT.call(...)` 的形状补一条同构的）：

```kotlin
@Test
fun `hourly rows summed up equal the detail rows they were folded from`() {
    val current = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    // 三条明细分布在两个小时：上一小时 1 条，当前小时 2 条。往未来种不行——rollUp 跳过当前整点之后的小时，
    // 那一行的桶永远是 0，断言就从等值检查变成时钟检查
    insert(current.minusHours(1).plusMinutes(5), TENANT, "mcp", "search_nodes", "SUCCESS", 80, mcpId = 77)
    insert(current.plusMinutes(1), TENANT, "mcp", "fetch_doc", "SUCCESS", 400, mcpId = 77)
    insert(current.plusMinutes(6), TENANT, "mcp", "search_nodes", "ERROR", 900, mcpId = 88)
    rollup.rollUp()

    val perHour = jdbc.query(
        "SELECT SUM(calls) AS c, SUM(le_100ms) AS b1, SUM(le_500ms) AS b2 FROM tool_invocation_stats " +
            "WHERE tenant_id = ? GROUP BY stat_hour ORDER BY stat_hour",
    ) { r, _ -> "${r.getLong("c")}/${r.getLong("b1")}+${r.getLong("b2")}" }

    // 桶跟着小时走：80ms 落 le_100ms，400ms 落 le_500ms，900ms 落 le_2s 所以不进这两列
    assertEquals(listOf("1/1+0", "2/0+1"), perHour)
}

@Test
fun `folding the same hour twice writes the same numbers`() {
    insert(LocalDateTime.now().truncatedTo(ChronoUnit.HOURS), TENANT, "builtin", "read_file", "SUCCESS", 10)
    rollup.rollUp()
    val before = jdbc.queryForList("SELECT stat_hour, calls, successes, sum_duration_ms FROM tool_invocation_stats ORDER BY stat_hour, id")
    rollup.rollUp()
    val after = jdbc.queryForList("SELECT stat_hour, calls, successes, sum_duration_ms FROM tool_invocation_stats ORDER BY stat_hour, id")
    // 重算而不是增量，正是这一条让本任务不需要分布式锁
    assertEquals(before, after)
}
```

- [ ] **Step 11: 提交**

```bash
git add harnax-admin/src/main/resources/db/migration/V5__tool_invocation_stats_hourly.sql \
        harnax-entity/src/test/resources/schema-test.sql \
        harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt \
        harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapperTest.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupServiceTest.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolInvocationRollupIT.kt
git commit -m "feat(metrics): 聚合表升到小时档——stat_hour 单档、折算与释放按整点配对"
```

---

### Task 2: 读侧窗口到小时与趋势自动选档

**Files:**
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt`（参数描述）
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`（`hour` 桶表达式；三个读法参数名不变、语义换整点）
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt`

**Interfaces:**
- Consumes：Task 1 的 `stat_hour` 列与整点串形状。
- Produces：`Window(from: LocalDateTime, to: LocalDateTime)`，暴露 `fromStr` / `toStr`（`yyyy-MM-dd HH:mm:ss` 整点）、`toExclusiveStr` 与 `hours`；`granularity` 取值 `hour|day|week|month`，响应 `granularity` 回实际用的那一档；`from` / `to` 两个回显字段改成整点串。

- [ ] **Step 1: 写失败用例——四种钳位与三档自动选档**

在 `ToolMetricsReadIT.kt` 加（`getJson` / `data(...)` 是这份 IT 已有的帮助函数）：

```kotlin
@Test
fun `a half-day window clamps a future end to the current hour`() {
    val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    val body = data(
        "/api/admin/tool-metrics/summary?start=${now.minusHours(6).format(HOUR_PARAM)}&end=${now.plusHours(9).format(HOUR_PARAM)}",
    )
    assertEquals(now.format(HOUR_PARAM) + ":00", body["to"].asString())
    assertEquals(now.minusHours(6).format(HOUR_PARAM) + ":00", body["from"].asString())
}

@Test
fun `start after end collapses onto the end hour and an unparseable pair falls back to the default span`() {
    val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    val collapsed = data("/api/admin/tool-metrics/summary?start=${now.plusHours(3).format(HOUR_PARAM)}&end=${now.format(HOUR_PARAM)}")
    assertEquals(collapsed["from"].asString(), collapsed["to"].asString())

    val defaulted = data("/api/admin/tool-metrics/summary?start=also-not-an-hour&end=not-a-day")
    val echoedEnd = LocalDateTime.parse(defaulted["to"].asString(), HOUR_STAMP)
    assertEquals(719L, ChronoUnit.HOURS.between(LocalDateTime.parse(defaulted["from"].asString(), HOUR_STAMP), echoedEnd))
}

@Test
fun `the bucket follows the span on both sides of each boundary`() {
    val now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    fun granularity(hours: Long): String = data(
        "/api/admin/tool-metrics/time-series?start=${now.minusHours(hours - 1).format(HOUR_PARAM)}&end=${now.format(HOUR_PARAM)}",
    )["granularity"].asString()

    assertEquals("hour", granularity(48))
    assertEquals("day", granularity(49))
    assertEquals("day", granularity(24L * 92))
    assertEquals("week", granularity(24L * 93))
    // 显式请求仍然说话算数
    assertEquals(
        "month",
        data("/api/admin/tool-metrics/time-series?start=${now.minusHours(72).format(HOUR_PARAM)}&end=${now.format(HOUR_PARAM)}&granularity=month")["granularity"].asString(),
    )
}
```

`HOUR_PARAM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")`、`HOUR_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")` 两条常量补进这份 IT；回显的 `from` / `to` 是整点串（Task 2 Step 3 的 `Window`），所以断言里要补上 `:00` 秒位。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: FAIL——`start=2026-10-08 09:00` 解析不出来，走的是默认 30 天档

- [ ] **Step 3: 换窗口解析**

`ToolMetricsServiceImpl.kt`：

```kotlin
    /**
     * The requested hour range, clamped to what these tables can answer.
     *
     * Both bounds are inclusive hours and a bare day is read as its 00:00 opening. A missing `end` means the
     * current hour; a missing `start` means the default span ending at `end`, so the two bounds are each
     * other's fallback rather than two separate defaults. `start` after `end` collapses onto `end`, and a
     * span wider than [MAX_WINDOW_HOURS] pushes `start` forward rather than rejecting the request. A value
     * that parses neither way counts as absent: the page can only send what its picker produced.
     */
    private fun window(
        start: String?,
        end: String?,
    ): Window {
        val currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val requestedEnd = parseHour(end)
        if (end != null && requestedEnd == null) {
            log.info("Metrics window end `$end` is neither a yyyy-MM-dd HH:mm hour nor a day, using current hour {}", currentHour)
        }
        var to = requestedEnd ?: currentHour
        if (to.isAfter(currentHour)) {
            log.info("Metrics window end {} is in the future, clamped to {}", to, currentHour)
            to = currentHour
        }
        val requestedStart = parseHour(start)
        if (start != null && requestedStart == null) {
            log.info("Metrics window start `$start` is unparseable, using {} hours before {}", DEFAULT_WINDOW_HOURS, to)
        }
        var from = requestedStart ?: to.minusHours((DEFAULT_WINDOW_HOURS - 1).toLong())
        if (from.isAfter(to)) {
            log.info("Metrics window start {} is after end {}, clamped to the end hour", from, to)
            from = to
        }
        if (ChronoUnit.HOURS.between(from, to) + 1 > MAX_WINDOW_HOURS) {
            val pushed = to.minusHours((MAX_WINDOW_HOURS - 1).toLong())
            log.info("Metrics window {}..{} spans more than {} hours, start pushed to {}", from, to, MAX_WINDOW_HOURS, pushed)
            from = pushed
        }
        return Window(from, to)
    }

    /** An hour or a bare day as an instant, with the minutes and seconds of an hour dropped; blank and unparseable both answer null. */
    private fun parseHour(
        value: String?,
    ): LocalDateTime? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDateTime.parse(text, HOUR_PARAM).truncatedTo(ChronoUnit.HOURS) }
            .recoverCatching { LocalDate.parse(text, DATE_FORMATTER).atStartOfDay() }
            .getOrNull()
    }
```

companion 常量：删 `DEFAULT_WINDOW_DAYS` / `MAX_WINDOW_DAYS`，加 `DEFAULT_WINDOW_HOURS = 720`、`MAX_WINDOW_HOURS = 8760`、`HOUR_PARAM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")`；`Window` 类改成持有两个 `LocalDateTime` 并只暴露 `fromStr` / `toStr`（`yyyy-MM-dd HH:mm:ss`），明细侧沿用同一对整点（`ts` 落在 `[from, to + 1h)`，见 Step 5）。

- [ ] **Step 4: 趋势自动选档**

```kotlin
    /** Requested granularity wins when it is one of the four; otherwise the span picks a readable bucket. */
    private fun granularityOf(
        requested: String,
        window: Window,
    ): String = when {
        requested == GRANULARITY_HOUR || requested == GRANULARITY_DAY ||
            requested == GRANULARITY_WEEK || requested == GRANULARITY_MONTH -> requested
        window.hours <= 48 -> GRANULARITY_HOUR
        window.hours <= 24L * 92 -> GRANULARITY_DAY
        else -> GRANULARITY_WEEK
    }
```

`Window` 加 `val hours: Long get() = ChronoUnit.HOURS.between(from, to) + 1`；`companion` 加 `GRANULARITY_HOUR = "hour"`。`timePoints()` 与 `align()` 各加 `hour` 一档（`plusHours(1)` / `truncatedTo(HOURS)`）。

- [ ] **Step 5: 明细侧边界跟着换**

`detailRows` 与 `getInvocations` 现在传 `window.fromStr` / `window.toStr`，SQL 侧把上界从 `<= #{to}` 改成 `< #{to} + INTERVAL 1 HOUR` 的等价写法：在 service 里算好 `toExclusive = to.plusHours(1)` 并以 `toExclusiveStr` 传入，XML 改成 `AND l.ts &lt; #{to}`。理由写进注释：整点桶是闭区间的一小时，明细侧用同一对语义才不会让聚合与明细在整点那一步上错开一格。

- [ ] **Step 6: 聚合侧的窗口边界、桶表达式与 lastSeenAt**

三个读法都从日列换到整点列。`selectSubjectTotals`、`selectWindowTotals`、`selectTimeSeries` 的窗口谓词逐字改成同一形状（`from` / `to` 两侧都是整点起点，日/周/月档把桶向下对齐后仍落在这对边界内）：

```xml
        WHERE s.tenant_id = #{tenantId}
        AND s.stat_hour &gt;= #{from}
        AND s.stat_hour &lt;= #{to}
```

`selectSubjectTotals` 的最后一条选出列连带注释改掉——`stat_hour` 本身已经是 DATETIME，`CAST` 那一层是为日列补的，留着会把小时信息截掉：

```xml
        <!-- A real hour now: the column is DATETIME, so no CAST is needed to keep the map handing back a
             LocalDateTime rather than a java.sql.Date read through the JVM default zone. -->
        MAX(s.stat_hour) AS lastSeenAt
```

`selectTimeSeries` 的 `<choose>` 最前面加一档：

```xml
            <when test="granularity == 'hour'">
                CAST(DATE_FORMAT(s.stat_hour, '%Y-%m-%d %H:00:00') AS DATETIME) AS timePoint
            </when>
```

并把 `day` / `week` / `month` 三档里的 `s.stat_date` 全换成 `s.stat_hour`（`DATE_FORMAT(s.stat_hour, '%Y-%m-%d 00:00:00')`、`DATE_SUB(s.stat_hour, INTERVAL WEEKDAY(s.stat_hour) DAY)`、`DATE_FORMAT(s.stat_hour, '%Y-%m-01 00:00:00')`）。这段 XML 的块注释里「bounds the window through `stat_date` rather than through an instant, because that column is a DATE」一句已经反了，要改成「窗口两侧就是整点，聚合列本身是 DATETIME，边界因此与明细侧的 `[from, to + 1h)` 对齐」。

- [ ] **Step 7: 控制器参数描述跟着点名**

`ToolMetricsController.kt` 三处 `@Parameter(description = ...)` 里的日期措辞改成「inclusive hour, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd`」，`granularity` 的取值域补 `hour`。

- [ ] **Step 8: 跑 IT 确认通过**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: 全绿（含既有断言——它们种的行是本日/昨日，整点窗口 720 小时仍覆盖）

- [ ] **Step 9: 提交**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt
git commit -m "feat(metrics): 读侧窗口降到整点档，趋势按跨度自动选 hour/day/week"
```

---

### Task 3: 新增 mcp 与 cli 两个分组维度

**Files:**
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`（`selectSubjectTotals` 收 `dimension`）
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt`

**Interfaces:**
- Consumes：Task 1/2 的整点边界与 `stat_hour`。
- Produces：`selectSubjectTotals(from, to, tenantId, kind, dimension)`，`dimension ∈ {tool, mcp, cli}`；`ToolMetricsServiceImpl.dimension()` 认 `agent|session|mcp|cli`，其余落 `tool`；`DIM_MCP` / `DIM_CLI` 常量。

- [ ] **Step 1: 写失败用例——一台服务器多工具在「按 MCP」下收成一行**

```kotlin
@Test
fun `the mcp dimension folds a server's several tools into one row`() {
    val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    call(hour, TENANT, "mcp", "search_nodes", "SUCCESS", 80, mcpId = 77)
    call(hour.plusMinutes(20), TENANT, "mcp", "fetch_doc", "SUCCESS", 400, mcpId = 77)
    call(hour.plusMinutes(30), TENANT, "mcp", "search_nodes", "ERROR", 900, mcpId = 88)
    rollup.rollUp()

    val rows = data("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp").get("rows").toList()
    assertEquals(2, rows.size)
    assertEquals(listOf(77L, 88L), rows.map { it["subjectId"].asLong() })
    val first = rows.first { it["subjectId"].asLong() == 77L }
    assertEquals(2L, first["calls"].asLong())
    assertEquals(0L, first["errors"].asLong())
    // 桶可加：80ms 落 le100ms、400ms 落 le500ms，两次一起给 P95 落在 <=500 ms
    assertEquals("<=", first["p95Operator"].asString())
    assertEquals(500L, first["p95Ms"].asLong())
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: FAIL——`groupBy=mcp` 被 `dimension()` 落回 `tool`，返回 3 行

- [ ] **Step 3: 分组键按维度选**

`ToolInvocationStatsMapper.xml` 的 `selectSubjectTotals` 改成：

```xml
    <select id="selectSubjectTotals" resultType="map">
        SELECT
        s.kind AS kind,
        s.subject_id AS subjectId,
        <choose>
            <when test="dimension == 'mcp' or dimension == 'cli'">'' AS toolName,</when>
            <otherwise>s.tool_name AS toolName,</otherwise>
        </choose>
        <include refid="subjectTotalsColumns"/>,
        <!-- A real hour now: the column is DATETIME, so no CAST is needed (Task 2 Step 6). -->
        MAX(s.stat_hour) AS lastSeenAt
        FROM tool_invocation_stats s
        WHERE s.tenant_id = #{tenantId}
        AND s.stat_hour &gt;= #{from}
        AND s.stat_hour &lt;= #{to}
        <if test="kind != null and kind != ''">
            AND s.kind = #{kind}
        </if>
        GROUP BY s.kind, s.subject_id
        <if test="dimension == 'tool'">, s.tool_name</if>
        ORDER BY calls DESC, lastSeenAt DESC
    </select>
```

`mcp` / `cli` 两档把 `tool_name` 选成空串而不是省掉：`ONLY_FULL_GROUP_BY` 下裸列必须要么进 GROUP BY 要么套聚合，常量列两头都合规。

- [ ] **Step 4: 接口与分派**

`ToolInvocationStatsMapper.kt` 的 `selectSubjectTotals` 加 `@Param("dimension") dimension: String`。
`ToolMetricsServiceImpl.kt`：

```kotlin
    private fun dimension(
        groupBy: String,
    ): String = if (groupBy in setOf(DIM_AGENT, DIM_SESSION, DIM_MCP, DIM_CLI)) groupBy else DIM_TOOL
```

`toolRows(window, tenantId, kind, dimension)` 多收一个参数并透传；`getSummary` 里 `if (dimension == DIM_TOOL)` 改成 `if (dimension == DIM_TOOL || dimension == DIM_MCP || dimension == DIM_CLI)`（三档都走聚合表）。companion 加 `DIM_MCP = "mcp"`、`DIM_CLI = "cli"`。

- [ ] **Step 5: 下钻谓词跟着认新维度**

`ToolMetricsController` 不动，但 `getInvocations` 的调用方（前端）会带 `mcpId` / `cliId`；后端 `detailRows` 的 `dimension` 只接受 agent/session，`mcp`/`cli` 不进这条路径——在 `detailRows` 的 KDoc 里点名这一点，避免下一个人以为漏了分支。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: 全绿；`cli` 维度补一条同形用例（`kind=cli&groupBy=cli`，两个 `cliId` 各一行）

- [ ] **Step 7: 提交**

```bash
git add harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt
git commit -m "feat(metrics): 新增按 MCP 与按 CLI 两个分组维度，一个服务器／包一行"
```

---

### Task 4: 名称解析进 SQL（subjectName 与 parentName）

**Files:**
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt`
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`、`ToolInvocationLogMapper.xml`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt`

**Interfaces:**
- Consumes：Task 3 的 `dimension` 参数；`mcp_server` / `cli` / `agent` / `session` 四张表。
- Produces：`ToolMetricsRow.subjectName: String = ""`、`ToolMetricsRow.parentName: String = ""`；SQL 别名 `subjectName` / `parentName`。

- [ ] **Step 1: 写失败用例——五档命中名称，两条回落，一条防扇出**

```kotlin
    private fun firstRow(
        path: String,
    ): JsonNode = data(path)["rows"][0]

    private fun currentHour(): LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)

    @Test
    fun `each dimension names its subject and falls back to the key when nothing is registered`() {
        val hour = currentHour()
        // id 必须显式给：自增值不可预测，而名称是按 m.id = subject_id 对上的
        jdbc.update("INSERT INTO mcp_server (id, tenant_id, name, type) VALUES (77, ?, '文档检索服务', 'stdio')", TENANT)
        jdbc.update("INSERT INTO cli (id, name) VALUES (55, 'lark-cli')")
        insert(hour, TENANT, "mcp", "search_nodes", "SUCCESS", 80, mcpId = 77)
        insert(hour, TENANT, "cli", "lark", "SUCCESS", 90, cliId = 55)
        rollup.rollUp()

        assertEquals("文档检索服务", firstRow("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")["subjectName"].asString())
        assertEquals("lark-cli", firstRow("/api/admin/tool-metrics/summary?kind=cli&groupBy=cli")["subjectName"].asString())
        assertEquals("search_nodes", firstRow("/api/admin/tool-metrics/summary?kind=mcp&groupBy=tool")["subjectName"].asString())
        // 工具档在 MCP tab 下还要带出所属服务器，页面才说得出是哪一台答的
        assertEquals("文档检索服务", firstRow("/api/admin/tool-metrics/summary?kind=mcp&groupBy=tool")["parentName"].asString())
        assertEquals(agentName, firstRow("/api/admin/tool-metrics/summary?groupBy=agent")["subjectName"].asString())

        // 登记行删掉：名字回落成 subjectKey，不留空白格
        jdbc.update("DELETE FROM mcp_server WHERE tenant_id = ?", TENANT)
        val orphan = firstRow("/api/admin/tool-metrics/summary?kind=mcp&groupBy=mcp")
        assertEquals(orphan["subjectKey"].asString(), orphan["subjectName"].asString())
    }

    @Test
    fun `duplicate session rows do not double the counts`() {
        val hour = currentHour()
        insert(hour, TENANT, "builtin", "read_file", "SUCCESS", 10, session = "s-dup")
        insert(hour.plusMinutes(5), TENANT, "builtin", "read_file", "SUCCESS", 10, session = "s-dup")
        jdbc.update("INSERT INTO session (tenant_id, session_id, title) VALUES (?, 's-dup', '标题甲')", TENANT)
        jdbc.update("INSERT INTO session (tenant_id, session_id, title) VALUES (?, 's-dup', '标题乙')", TENANT)
        rollup.rollUp()

        val row = data("/api/admin/tool-metrics/summary?groupBy=session")["rows"]
            .first { it["subjectKey"].asString() == "s-dup" }
        // 两行 session 若走 JOIN 这里会变成 4
        assertEquals(2L, row["calls"].asLong())
        assertEquals(2L, row["successes"].asLong())
        assertEquals("标题乙", row["subjectName"].asString())
    }
```

`agentName` 是这份 IT 已有的智能体夹具名（`BaseAdminIT` 建的那一行），没有就在 `@BeforeEach` 里补一条 `INSERT INTO agent (tenant_id, name) VALUES (?, '取数助手')` 并把这个变量指过去；`insert(...)` 同 Task 1 的说明。回落断言取 `ORDER BY ss.id DESC LIMIT 1` 的语义，所以重复标题时取后插入的那一条。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: FAIL——响应里没有 `subjectName` 键（admin 丢 null 键，`asString()` 抛 NPE 或断言 `""` 不等）

- [ ] **Step 3: DTO 加两个字段**

`ToolMetricsResponse.kt` 的 `ToolMetricsRow` 里 `toolName` 之后插：

```kotlin
    @Schema(description = "Display name for this row's own subject: the registered name of the MCP server / CLI package / agent, the session title, or the tool name when grouped by tool. Falls back to subjectKey when nothing is registered.")
    val subjectName: String = "",
    @Schema(description = "Owning MCP server or CLI package name, present only on rows whose kind is mcp or cli; the page shows it beside a tool name so the row says which server answered")
    val parentName: String = "",
```

- [ ] **Step 4: 聚合侧带名**

`selectSubjectTotals` 的 SELECT 列表补两列，并挂 JOIN（`mcp_server` / `cli` 都按主键 `id` 对，1 对至多 1，行数不动）：

```xml
        <choose>
            <when test="dimension == 'mcp'">COALESCE(m.name, CAST(s.subject_id AS CHAR)) AS subjectName,</when>
            <when test="dimension == 'cli'">COALESCE(c.name, CAST(s.subject_id AS CHAR)) AS subjectName,</when>
            <otherwise>s.tool_name AS subjectName,</otherwise>
        </choose>
        CASE
        WHEN s.kind = 'mcp' THEN m2.name
        WHEN s.kind = 'cli' THEN c2.name
        ELSE ''
        END AS parentName,
        ...
        FROM tool_invocation_stats s
        <if test="dimension == 'mcp'">LEFT JOIN mcp_server m ON m.id = s.subject_id AND m.tenant_id = s.tenant_id</if>
        <if test="dimension == 'cli'">LEFT JOIN cli c ON c.id = s.subject_id</if>
        LEFT JOIN mcp_server m2 ON s.kind = 'mcp' AND m2.id = s.subject_id AND m2.tenant_id = s.tenant_id
        LEFT JOIN cli c2 ON s.kind = 'cli' AND c2.id = s.subject_id
        GROUP BY s.kind, s.subject_id
        <if test="dimension == 'tool'">, s.tool_name</if>
        <if test="dimension == 'mcp' or dimension == 'cli'">, m.name, c.name</if>
```

`ONLY_FULL_GROUP_BY` 要求裸列进 GROUP BY 或套聚合，所以名称列一律进 GROUP BY（它们与 `subject_id` 一一对应，不改变分组基数）。`tool` 维度下 `parentName` 走 `m2` / `c2`，与 `m` / `c` 分开写是因为同一语句里两条同键 JOIN 会被 MyBatis 的 `<if>` 组合成未定义的别名。若嫌绕，可只保留 `m2` / `c2` 两条 JOIN，`mcp` / `cli` 档的 `subjectName` 直接读 `m2.name` / `c2.name`——取后一种，删掉 `m` / `c`。

- [ ] **Step 5: 明细侧带名（会话必须走标量子查询）**

`selectSubjectTotalsFromDetail` 的 SELECT 补：

```xml
        <choose>
            <when test="groupBy == 'session'">
                COALESCE((SELECT ss.title FROM session ss
                    WHERE ss.session_id = l.session_id AND ss.tenant_id = l.tenant_id
                    ORDER BY ss.id DESC LIMIT 1), l.session_id) AS subjectName,
            </when>
            <otherwise>
                COALESCE(a.name, CAST(l.agent_id AS CHAR)) AS subjectName,
            </otherwise>
        </choose>
```

`FROM tool_invocation_log l` 后加 `<if test="groupBy != 'session'">LEFT JOIN agent a ON a.id = l.agent_id AND a.tenant_id = l.tenant_id</if>`，GROUP BY 补 `, a.name`（仅 agent 档）。
注释里写死这条理由：`session.session_id` 只有注释写着 unique，索引清单里既无唯一索引也无普通索引，JOIN 会把 GROUP BY 后的一行扇成两行、五个计数当场翻倍；标量子查询恒返一个值，无论重复与否都不改行数。

- [ ] **Step 6: service 读两个新别名**

`toolRows` 与 `detailRows` 各补 `subjectName = row.stringOf("subjectName")`、`parentName = row.stringOf("parentName")`（明细侧 `parentName` 无列，留默认空串）。

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q`
Expected: 全绿，含防扇出那条

- [ ] **Step 8: 提交**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt
git commit -m "feat(metrics): 行带出登记名——四张表按主键 JOIN，会话名走标量子查询防扇出"
```

---

### Task 5: 前端——选择器上移、维度矩阵、列名实名

**Files:**
- Modify: `harnax-webui/src/pages/call-metrics/index.tsx`
- Modify: `harnax-webui/src/pages/call-metrics/SubjectDrawer.tsx`、`subjectProfile.ts`、`subjectProfile.test.ts`、`lastSeen.ts`、`lastSeen.test.ts`
- Modify: `harnax-webui/src/services/ant-design-pro/toolMetrics.ts`、`harnax-webui/src/typings.d.ts`
- Modify: `harnax-webui/src/locales/zh-CN/pages.ts`、`harnax-webui/src/locales/en-US/pages.ts`

**Interfaces:**
- Consumes：Task 2~4 的契约——`start`/`end` 收 `yyyy-MM-dd HH:mm`、响应 `from`/`to` 回整点串、`granularity ∈ hour|day|week|month`、行新增 `subjectName`/`parentName`、`groupBy ∈ tool|mcp|cli|agent|session`。
- Produces：页面无对外接口。

- [ ] **Step 1: 契约类型先改**

`typings.d.ts` 的 `API.CallMetricsRow` 加 `subjectName?: string; parentName?: string;`；`CallMetricsSummary.from`/`to` 注释改成整点。`toolMetrics.ts` 三个函数的参数注释点名 `start`/`end` 的形状。

- [ ] **Step 2: 维度矩阵与档位标签**

`index.tsx` 顶部：

```tsx
/** Which dimensions each tab offers: the first is that tab's own subject, then the two cross-tab views. */
const TAB_DIMENSIONS: Record<string, string[]> = {
  tool: ['tool', 'agent', 'session'],
  mcp: ['mcp', 'agent', 'session'],
  cli: ['cli', 'agent', 'session'],
};
```

`groupBy` 的 Select 用 `TAB_DIMENSIONS[tab].map(...)`，标签取 `pages.callMetrics.dim.${value}`；`Tabs.onChange` 里补一行收敛：

```tsx
            if (!TAB_DIMENSIONS[key].includes(groupBy)) setGroupBy(TAB_DIMENSIONS[key][0]);
```

- [ ] **Step 3: 选择器上移并降到小时档**

从「明细」卡的 `extra` 摘掉 RangePicker，挂到 Tabs 上：

```tsx
        <Tabs
          activeKey={tab}
          onChange={...}
          tabBarExtraContent={{
            right: (
              <RangePicker
                value={range}
                showTime={{ format: 'HH:mm', showMinute: true, showSecond: false, defaultValue: [dayjs().startOf('hour'), dayjs().startOf('hour')] } as any}
                format="YYYY-MM-DD HH:mm"
                presets={rangePresets}
                allowClear={false}
                style={{ width: 320 }}
                disabledDate={(current) => current.isAfter(dayjs().endOf('day'))}
                onChange={...}
              />
            ),
          }}
```

`start` / `end` 两个派生值改成 `range[0].format('YYYY-MM-DD HH:mm')` / `range[1].format('YYYY-MM-DD HH:mm')`；`rangePresets` 的每个区间两端都 `.startOf('hour')`。分钟只允许 00：`showTime` 里用 `renderTime` 把非 00 分置灰，或直接接受任意分钟（服务端会向下取整到整点，见 Task 2 的 `parseHour`）——取后者，并在 `onChange` 里立刻 `setRange([dates[0].startOf('hour'), dates[1].startOf('hour')])`，让输入框显示的就是真正生效的那个整点。

- [ ] **Step 4: 第一列按维度给名与给值**

```tsx
  const subjectTitle = intl.formatMessage({ id: `pages.callMetrics.dim.${groupBy}` });
```

列定义 `title: subjectTitle`，render 换成：

```tsx
        <a onClick={...}>
          {groupBy === 'tool' ? (row.parentName || row.subjectName) : row.subjectName}
          {groupBy === 'tool' && row.parentName ? (
            <Typography.Text type="secondary" style={{ marginLeft: 6, fontSize: 12 }}>
              {row.toolName}
            </Typography.Text>
          ) : null}
        </a>
```

`#id` 那枚后缀整段删掉。记录抽屉的「会话」列同样 `title` 优先、`sessionId` 兜底（数据来自 `API.CallInvocationRow`，本轮不加字段，仍显示 id，但列名从「会话」保留）。

- [ ] **Step 5: 趋势轴按 granularity 格式化**

`points` 之外再存一份 `granularity` state，`axis.x.labelFormatter` 改成：

```tsx
      x: { labelFormatter: (time: string) => (time ? dayjs(time).format(granularity === 'hour' ? 'MM-DD HH:00' : 'MM-DD') : time) },
```

- [ ] **Step 6: 文案两份落**

`zh-CN/pages.ts`：删 `col.subject`、`groupBy.tool|agent|session`、`subjectTitle`、`profile.title` 六条旧 key，新增：

```ts
  'pages.callMetrics.dim.tool': '工具',
  'pages.callMetrics.dim.mcp': 'MCP',
  'pages.callMetrics.dim.cli': 'CLI',
  'pages.callMetrics.dim.agent': '智能体',
  'pages.callMetrics.dim.session': '会话',
  'pages.callMetrics.groupByPrefix': '按{dimension}',
  'pages.callMetrics.detailTitle': '明细',
  'pages.callMetrics.profile.title': '登记信息',
```

`en-US/pages.ts` 同步 `Tool` / `MCP` / `CLI` / `Agent` / `Session` / `Group by {dimension}` / `Detail` / `Registered info`。`detailHint` 一句按新维度名重写（仍说 90 天保留窗口只覆盖智能体／会话两档）。

- [ ] **Step 7: profile 与 lastSeen 认新维度**

`subjectProfile.ts`：

```ts
export function profileTargetOf(row: API.CallMetricsRow, groupBy: string): ProfileTarget {
  if (groupBy === 'mcp' || groupBy === 'cli') {
    const id = row.subjectId ?? Number(row.subjectKey);
    return Number.isFinite(id) && id > 0 ? { source: groupBy, id } : { source: 'none' };
  }
  ...
}
```

`SubjectDrawer.tsx` 的标题取 `row.subjectName`。

`lastSeen.ts` 整个文件重写——日聚合那列是 `CAST(MAX(stat_date) AS DATETIME)`，永远落在午夜，所以旧实现按维度把秒藏掉；小时档之后聚合侧给的是真整点，不再需要「防伪」，但秒仍是噪音（详情表才有的分辨率）：

```ts
import dayjs from 'dayjs';

/** The aggregate-backed dimensions answer at the hour; the two detail-backed ones answer at the call's own instant. */
const AGGREGATE_DIMENSIONS: string[] = ['tool', 'mcp', 'cli'];

export function formatLastSeen(value?: string, groupBy?: string): string {
  if (!value) return '-';
  const stamp = dayjs(value);
  if (!stamp.isValid()) return '-';
  return AGGREGATE_DIMENSIONS.includes(groupBy ?? '') ? stamp.format('YYYY-MM-DD HH:mm') : stamp.format('YYYY-MM-DD HH:mm:ss');
}
```

- [ ] **Step 8: 两份单测改到位并跑**

`lastSeen.test.ts` 的旧第一 case 断言的是「掉伪造午夜」，小时档后那条判据已经换掉，整个 describe 改成：

```ts
describe('formatLastSeen', () => {
  it('reads the aggregate-backed dimensions at the hour', () => {
    expect(formatLastSeen('2026-10-08 14:00:00', 'tool')).toBe('2026-10-08 14:00');
    expect(formatLastSeen('2026-10-08 14:00:00', 'mcp')).toBe('2026-10-08 14:00');
    expect(formatLastSeen('2026-10-08 14:00:00', 'cli')).toBe('2026-10-08 14:00');
  });

  it('keeps the real instant on the detail-backed views', () => {
    expect(formatLastSeen('2026-10-08 14:23:11', 'agent')).toBe('2026-10-08 14:23:11');
    expect(formatLastSeen('2026-10-08 14:23:11', 'session')).toBe('2026-10-08 14:23:11');
  });

  it('answers a missing stamp with a dash rather than Invalid Date', () => {
    expect(formatLastSeen(undefined, 'tool')).toBe('-');
    expect(formatLastSeen('', 'agent')).toBe('-');
    expect(formatLastSeen('not a time', 'tool')).toBe('-');
  });
});
```

`subjectProfile.test.ts` 加两档各一条，断言按 `subjectId` 取登记行（沿用文件顶部已有的 `row()` 夹具 helper，它补齐 `API.CallMetricsRow` 的必填字段）：

```ts
  it('reads a dimension row grouped by MCP server through the id the row carries', () => {
    expect(profileTargetOf(row({ kind: 'mcp', subjectKey: '77', subjectId: 77 }), 'mcp')).toEqual({ source: 'mcp', id: 77 });
  });

  it('reads a dimension row grouped by CLI package through the id the row carries', () => {
    expect(profileTargetOf(row({ kind: 'cli', subjectKey: '55', subjectId: 55 }), 'cli')).toEqual({ source: 'cli', id: 55 });
  });
```

Run: `cd harnax-webui && npx jest src/pages/call-metrics 2>&1 | tail -20`
（`package.json` 的 `"test": "jest"`，jest.config.ts 在仓库根，不要新造 runner）
Expected: 三个文件用例全绿

- [ ] **Step 9: 构建与逐文件 lint**

```bash
cd harnax-webui && npx max build 2>&1 | tail -5
for f in src/pages/call-metrics/index.tsx src/pages/call-metrics/SubjectDrawer.tsx src/pages/call-metrics/subjectProfile.ts src/pages/call-metrics/subjectProfile.test.ts src/pages/call-metrics/lastSeen.ts src/pages/call-metrics/lastSeen.test.ts src/services/ant-design-pro/toolMetrics.ts src/locales/zh-CN/pages.ts src/locales/en-US/pages.ts; do npx @biomejs/biome lint "$f"; done
```
Expected: build 出 `umi.<hash>.js`；lint 0 error（禁止 `check --write`）

- [ ] **Step 10: 提交**

```bash
git add harnax-webui/src/pages/call-metrics/ harnax-webui/src/services/ant-design-pro/toolMetrics.ts \
        harnax-webui/src/typings.d.ts harnax-webui/src/locales/zh-CN/pages.ts harnax-webui/src/locales/en-US/pages.ts
git commit -m "feat(webui): 调用监控时间范围上移页首并降到小时档，主体列拆成五个实名维度"
```

---

### Task 6: 文档同步、全分支门禁与合回

**Files:**
- Modify: `prod_doc/tool-mcp-cli-call-metrics-design.zh-CN.md`、`.en-US.md`
- Modify: `docs/deploy-harnax-admin.md`
- Test: 全量后端门禁 + 前端 build

- [ ] **Step 1: 现状两份改口**

中文那份把「日聚合」「`stat_date`」「按工具/智能体/会话」「主体」四处口径改成小时档、`stat_hour`、五档维度、实名列；英文镜像逐节跟。成对判据五条逐条跑（`^#{2,3}` 编号序列相等、表格行数与每行竖线数相等、中文侧每个反引号片段在英文侧逐字命中、英文侧独有反引号片段=0、英文侧汉字计数=0 除了引号内的出货文案）。

- [ ] **Step 2: 部署文档补 V5 与折算**

`docs/deploy-harnax-admin.md` 的迁移小节加 V5 一行，并写明这条迁移会清空 `tool_invocation_stats`、聚合历史从保留窗口重新折出；`harnax.metrics.retention-days` 的语义不变。

- [ ] **Step 3: 后端全量门禁**

Run（worktree 根）: `mvn -o -pl harnax-entity,harnax-admin -am test -q`
Expected: 聚合行的 `Tests run` 之和与失败 0；指标三张 IT 全绿（Docker 在跑时）

- [ ] **Step 4: 残余扫描**

```bash
grep -rn "stat_date\|upsertDay\|selectUnrolledDates\|col.subject\|DEFAULT_WINDOW_DAYS\|MAX_WINDOW_DAYS" \
  harnax-admin/src harnax-entity/src harnax-webui/src --include=*.kt --include=*.xml --include=*.ts --include=*.tsx | grep -v schema-test.sql
```
Expected: 只剩 V5 里 DROP 旧索引名与 CHANGE COLUMN 的旧列名两处（那是迁移自己的历史名）。

- [ ] **Step 5: 合并回 kotlin-dev 并在合并结果上复验**

```bash
cd /Users/heqingsong/code/my_project/harnax
git status --porcelain   # 必须为空或只含他人未提交集，先数归属
git merge --no-ff feat/call-metrics-hourly
mvn -o -pl harnax-admin test -Dtest=ToolMetricsReadIT -q
```
Expected: 合并后 IT 仍全绿。不 push。

- [ ] **Step 6: 部署给他人验**

```bash
bash harnax-deploy/deploy-service.sh admin && bash harnax-deploy/deploy-service.sh frontend
docker compose -f harnax-deploy/docker-compose.yml logs --no-log-prefix admin | grep -E "now at version|Tomcat started"
```
Expected: `now at version v5`；等一次 :05 折算后页面「按 MCP」出一行一台服务器，趋势在 ≤48 小时窗口下 X 轴出现 `HH:00`。
