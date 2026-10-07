# 工具 / MCP / CLI 调用指标 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 tool / MCP / CLI 调用建立唯一事件源、一张明细表、一张日聚合表、三个读端点与一个前端页面，并补上技能用量缺失的 `USE` 写入方，同时整链删除 `tool_call_log`。

**Architecture:** agentscope 的 `MiddlewareBase.onActing` 是唯一能同时看见内置工具、MCP 工具与沙箱 shell 的位置，所以事件源就是一个中间件：它在 `TOOL_RESULT_END` 按 `toolCallId` 关联起点与终态，把一个不可变事件交给 tools-sdk 定义的写入契约，agent-service 侧的实现用有界队列 + 批量 insert 落 `tool_invocation_log`。`kind` 与 CLI 归因由装配期已知的三张映射决定（纯函数，不回查数据库）。admin 每小时把明细折算进 `tool_invocation_stats` 并只删已折算的过期行，读侧三个端点默认读聚合、按 agent/session 下钻读明细。

**Tech Stack:** Kotlin 2.2.21 + Spring Boot（JDK 21）+ MyBatis（`@Mapper` + `classpath*:mapper/*.xml`）+ Flyway 单基线 + Testcontainers（MySQL 8）+ Reactor（`reactor-test`）+ JUnit 5 + UmiJS/AntD Pro + `@ant-design/plots` 2.6.8。

**规格来源：** `prod_doc/tool-mcp-cli-call-metrics-design.zh-CN.md`（决策 D1–D13、两张表 DDL、§10 删除清单、§11 验收）。本计划不重开任何已定决策；与规格冲突时以规格为准并在报告里提出异议。

## Global Constraints

- **注释、KDoc、日志字符串一律英文**（全仓规范）；面向人的文案走 i18n。
- **一次工具调用至多一行明细，且只落终态**（I1、I2）：`outcome` ∈ `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`，`RUNNING` 永不落库。
- **租户只由服务端决定**（D12）：读侧 `TenantResolver.resolve(jwtUtil)`，写侧装配期随行携带；查询参数里出现 `tenantId` 即为越权口子。
- **写入侧绝不同步落库、绝不抛**（D9）：适配器立即返回，溢出丢弃并计数。
- **基线与 `schema-test.sql` 逐字同步**：`SchemaBaselineDriftIT` 同时比表、列与索引名，只补一边会六条断言全红。
- **Mapper 多参数方法每个参数都要 `@Param`**；Mapper XML 里不许出现 `--`（`MapperXmlParseTest` 会解析每一份 XML 并炸掉全部服务的 `SqlSessionFactory`）。
- **前端文案中英两个 locale 都必须加**，缺一侧算未完成。
- **webui 闸门只有 `npm run build` 与 `npx @biomejs/biome lint`**，`npm run lint` 串的 `tsc --noEmit` 有一批既有噪声，不作为闸门。
- **不动 Dashboard 轨的文件**：`harnax-entity/.../mapper/TokenStatsMapper.kt`、`.../mapper/TokenStatsMapper.xml` 现在有未提交改动，属另一条轨道，本计划一律不碰。
- **提交只 stage 本任务列出的路径**：工作树是共享的，`git add -A` 会把别人的半成品一起提交。
- **不 push**；不使用 `--no-verify`。`git commit` 有 post-commit 钩子，可能跑两分钟，超时后先 `git log --oneline -1` 再决定重试。

## 环境与命令配方

每条 `mvn` 都要显式带 JDK 与离线标志，且**改完任何 Kotlin（含只改注释）先跑 spotless**：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN=/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn
docker version > /dev/null 2>&1; echo "DOCKER=$?"   # 决定要不要排除容器测
```

- 模块单测：`$MVN -o -q spotless:apply -pl <模块>` 然后 `$MVN -o test -pl <模块> > /tmp/t.log 2>&1; echo EXIT=$?`，再看 `/tmp/t.log` 里的 `Tests run:` 行。凡 `-pl harnax-agent/**` 的 `test` 与 `test-compile` 必须带 `-am`：本地仓库里的 harnax-entity 构件是旧的，不带时表现为 `Unresolved reference 'ToolInvocationLog'` 一类的假红。`spotless:apply` 不带 `-am`，否则会顺手改到上游模块的文件，在共享工作树里留下与本任务无关的改动。
- admin 定向 IT（命令串里**不能出现字面 `*IT`**，且 `-Dtest` 要给一个故意不匹配的值，否则同一批 IT 在 surefire 与 failsafe 各跑一遍）：
  ```bash
  $MVN -o -pl harnax-admin -am -Pintegration-test \
    -Dit.test='ToolMetrics*' -Dtest='ToolMetrics-none' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
  ```
  `-am` 不能省，否则 `harnax-entity` 从 `~/.m2` 的旧 SNAPSHOT 解析，本次改的 mapper XML 与 `schema-test.sql` 都不生效。判据是 `Tests run:` 行与 `target/failsafe-reports/*.xml` 条数，不是 `BUILD SUCCESS`（漏 `-Pintegration-test` 也是 SUCCESS 且一条 IT 没跑）。
- Docker 不可用时排除容器包：`-Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**' -Dsurefire.failIfNoSpecifiedTests=false`。
- 前端：`cd harnax-webui && npm run build > /tmp/build.log 2>&1; echo EXIT=$?` 与 `npx @biomejs/biome lint src/pages src/locales > /tmp/lint.log 2>&1; echo EXIT=$?` 分别跑、分别落日志。
- 删除过源文件的模块跑 IT 前必须 `clean`，残留 `.class` 会进 jar，旧路由照样响应。

## 文件结构

| 动作 | 路径 | 职责 |
|---|---|---|
| 新增 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationLog.kt` | 明细行模型 + `kind`/`outcome` 词表常量（全仓唯一定义处） |
| 新增 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt` | 日聚合行模型 |
| 新增 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt` + `resources/mapper/ToolInvocationLogMapper.xml` | 明细写入、待折算日期、过期清理 |
| 新增 | `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt` + `resources/mapper/ToolInvocationStatsMapper.xml` | 逐日幂等重算 |
| 新增 | `harnax-agent/harnax-tools-sdk/.../tools/sdk/adaptor/ToolInvocationAdaptor.kt` | 写入契约 + 不可变事件载荷 |
| 新增 | `harnax-agent/harnax-harness-core/.../agent/provider/middleware/ToolInvocationClassifier.kt` | `kind` 判定与 CLI 命令归因（纯函数） |
| 新增 | `harnax-agent/harnax-harness-core/.../agent/provider/middleware/ToolInvocationMiddleware.kt` | 唯一事件源：收集起点、关联终态、投递 |
| 新增 | `harnax-agent/harnax-agent-service/.../service/adaptor/ToolInvocationAdaptorImpl.kt` | 有界队列 + 批量 insert + 截断 + 丢弃计数 |
| 新增 | `harnax-admin/.../service/ToolInvocationRollupService.kt` | 三步聚合与清理 |
| 新增 | `harnax-admin/.../service/ToolMetricsService.kt` + `impl/ToolMetricsServiceImpl.kt` + `controller/ToolMetricsController.kt` + `dto/ToolMetrics*.kt` | 三个读端点 |
| 新增 | `harnax-webui/src/pages/call-metrics/index.tsx`、`src/services/ant-design-pro/toolMetrics.ts` | 调用监控页 |
| 修改 | Flyway 基线、`schema-test.sql`、`HarnessAgentBuilder`、`HarnessAgentLauncher`、`HarnessAutoConfiguration`、`SkillUsageAdaptor` / `SkillUsageAdaptorImpl` / `AdminApiClient`、`HarnaxAdminApplication`、两侧 `application.yml`、`config/routes.ts`、`locales/*/menu.ts`、`locales/*/pages.ts`、`typings.d.ts`、`pages/skill/usage.tsx` | |
| 删除 | §10 清单（Task 9） | `tool_call_log` 整链 |

测试落点：纯函数与中间件在 `harnax-agent/harnax-harness-core/src/test/`，持久层在 `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/`（该模块叫 `*MapperTest`，没有 failsafe），端到端闸门在 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/`（只有这里跑真 Flyway 基线）。

---

### Task 1: 两张表进基线 + 明细实体与批量写入

**Files:**
- Modify: `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql`（插在 `token_stats` 建表块的收尾守卫之后、`tool_call_log` 建表块的起始守卫之前；当前 `token_stats` 块在 `:738-756`，`tool_call_log` 块从 `:757` 起）
- Modify: `harnax-entity/src/test/resources/schema-test.sql`（同一位置逐字复制；当前 `token_stats` 块在 `:722`、`tool_call_log` 在 `:743`）
- Create: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationLog.kt`
- Create: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt`
- Create: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt`
- Create: `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`
- Test: `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapperTest.kt`

**Interfaces:**
- Consumes: 无（本任务是起点）。
- Produces: `ToolInvocationLog`（`var` 属性：`id/tenantId/agentId/sessionId/userId/kind/toolName/mcpId/cliId/outcome/errorMessage/argsJson/resultExcerpt/durationMs/startTime/endTime/ts`）与常量 `ToolInvocationLog.KIND_{BUILTIN,MCP,CLI,SHELL,FRAMEWORK}`、`OUTCOME_{SUCCESS,ERROR,DENIED,INTERRUPTED}`；`ToolInvocationStats`；`ToolInvocationLogMapper.batchInsert(logs: List<ToolInvocationLog>): Int`。Task 3 与 Task 6 用这些常量而不是自己再拼一遍字符串，Task 7 用 `batchInsert`。

- [ ] **Step 1: 把两张表的建表语句折进 Flyway 基线**

在 `V1__init_schema.sql` 中 `) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Token Statistics table';` 与其后的 `/*!40101 SET character_set_client = @saved_cs_client */;` 之后插入（每个建表块都带这三行前置守卫 + 一行后置守卫，跟邻居同形）：

```sql
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
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
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE IF NOT EXISTS `tool_invocation_stats` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Aggregate row ID',
  `stat_date` date NOT NULL COMMENT 'Day of the calls, taken from tool_invocation_log.ts',
  `tenant_id` bigint NOT NULL COMMENT 'Owning tenant; detail rows without one are not aggregated at all',
  `kind` varchar(16) NOT NULL COMMENT 'Origin bucket, same vocabulary as the detail table',
  `subject_id` bigint NOT NULL DEFAULT '0' COMMENT 'mcp_id when kind = mcp, cli_id when kind = cli, 0 otherwise; 0 rather than NULL because a unique index does not treat NULLs as equal, and NULL would make the upsert insert a second row for the same day',
  `tool_name` varchar(255) NOT NULL DEFAULT '' COMMENT 'Tool name as the model sees it; every kind carries it, so two tools of one MCP server are two rows on a day',
  `calls` int NOT NULL COMMENT 'Total invocations',
  `successes` int NOT NULL COMMENT 'Invocations ending SUCCESS',
  `errors` int NOT NULL COMMENT 'Invocations ending ERROR',
  `denials` int NOT NULL COMMENT 'Invocations ending DENIED',
  `interruptions` int NOT NULL COMMENT 'Invocations ending INTERRUPTED',
  `sum_duration_ms` bigint NOT NULL COMMENT 'Duration total; the mean is this divided by calls',
  `max_duration_ms` bigint NOT NULL COMMENT 'Longest single call of the day',
  `le_100ms` int NOT NULL DEFAULT '0' COMMENT 'Calls of at most 100 ms',
  `le_500ms` int NOT NULL DEFAULT '0' COMMENT 'Calls over 100 ms and at most 500 ms',
  `le_2s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 500 ms and at most 2 s',
  `le_10s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 2 s and at most 10 s',
  `le_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 10 s and at most 30 s',
  `gt_30s` int NOT NULL DEFAULT '0' COMMENT 'Calls over 30 s',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_invocation_stats_day` (`stat_date`,`tenant_id`,`kind`,`subject_id`,`tool_name`),
  KEY `idx_tool_invocation_stats_tenant_date` (`tenant_id`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Daily rollup of tool_invocation_log, retained permanently';
/*!40101 SET character_set_client = @saved_cs_client */;
```

- [ ] **Step 2: 把同一段 SQL 逐字复制进 `schema-test.sql`**

复制 Step 1 插入的整段（含守卫行）到 `harnax-entity/src/test/resources/schema-test.sql` 里 `token_stats` 块之后、`tool_call_log` 块之前。两份必须逐字一致——`SchemaBaselineDriftIT` 比表、列与索引名三份清单。

- [ ] **Step 3: 写两个实体**

`ToolInvocationLog.kt`：

```kotlin
package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One tool invocation, stored once at its terminal state.
 *
 * Append-only. The window this table covers is bounded by design: long trends live in
 * [ToolInvocationStats], so a reader that needs a single call's arguments or error text asks within the
 * retention window and a reader that needs a trend never touches these rows.
 *
 * [kind] and [outcome] are strings rather than an enum because they are the vocabulary of the analytics
 * page and of the aggregate table's rows; renaming a constant here is a data migration, not a code change.
 */
@Schema(description = "Tool invocation event")
class ToolInvocationLog : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** A tool Admin delivered with the agent's spec. */
        const val KIND_BUILTIN = "builtin"

        /** A tool that came from an MCP server registered for this session. */
        const val KIND_MCP = "mcp"

        /** A CLI package Admin delivered, run through the shell tool. */
        const val KIND_CLI = "cli"

        /** The shell tool used for something that is not a delivered CLI. */
        const val KIND_SHELL = "shell"

        /** A tool the harness itself registered; nothing in Admin's tables names it. */
        const val KIND_FRAMEWORK = "framework"

        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_ERROR = "ERROR"
        const val OUTCOME_DENIED = "DENIED"
        const val OUTCOME_INTERRUPTED = "INTERRUPTED"
    }

    @Schema(description = "Invocation ID")
    var id: Long = 0

    @Schema(description = "Owning tenant, null when the delivered spec named none")
    var tenantId: Long? = null

    @Schema(description = "Owning agent, null for a team lead")
    var agentId: Long? = null

    @Schema(description = "Session that produced the call")
    var sessionId: String? = null

    @Schema(description = "End user behind the call, null for channel sessions and service keys")
    var userId: Long? = null

    @Schema(description = "Origin (builtin / mcp / cli / shell / framework)")
    var kind: String = KIND_FRAMEWORK

    @Schema(description = "Tool name as the model sees it; the command name when kind is cli")
    var toolName: String = ""

    @Schema(description = "MCP server row, set only when kind is mcp")
    var mcpId: Long? = null

    @Schema(description = "CLI package row, set only when kind is cli")
    var cliId: Long? = null

    @Schema(description = "Terminal state (SUCCESS / ERROR / DENIED / INTERRUPTED)")
    var outcome: String = OUTCOME_SUCCESS

    @Schema(description = "Failure reason, truncated")
    var errorMessage: String? = null

    @Schema(description = "Tool input as JSON, truncated; null when payload capture is off")
    var argsJson: String? = null

    @Schema(description = "Leading part of the tool result, truncated")
    var resultExcerpt: String? = null

    @Schema(description = "End time minus start time, milliseconds")
    var durationMs: Long = 0

    @Schema(description = "Call start")
    var startTime: LocalDateTime? = null

    @Schema(description = "Call end")
    var endTime: LocalDateTime? = null

    @Schema(description = "Recorded time, equal to endTime")
    var ts: LocalDateTime = LocalDateTime.now()
}
```

`ToolInvocationStats.kt`：

```kotlin
package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDate

/**
 * One day of [ToolInvocationLog] folded into counters, keyed by `(statDate, tenantId, kind, subjectId,
 * toolName)`.
 *
 * Rows are recomputable and overwritten whole, so a re-run of any day changes nothing; that is what lets
 * several replicas schedule this rollup without a lock. The key holds no `agentId` or `sessionId`: their
 * cardinality is not bounded, and a day keyed by them would grow as fast as the detail table.
 *
 * [subjectId] is `0` rather than null for the kinds with no subject because a unique index does not treat
 * nulls as equal — a null there would let the upsert insert a second row for the same day.
 */
@Schema(description = "Daily tool invocation aggregate")
class ToolInvocationStats : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Upper bounds of the six duration buckets, in milliseconds. */
        val BUCKET_BOUNDS = listOf(100L, 500L, 2000L, 10000L, 30000L)
    }

    @Schema(description = "Aggregate row ID")
    var id: Long = 0

    @Schema(description = "Day of the calls")
    var statDate: LocalDate = LocalDate.now()

    @Schema(description = "Owning tenant")
    var tenantId: Long = 0

    @Schema(description = "Origin bucket")
    var kind: String = ToolInvocationLog.KIND_FRAMEWORK

    @Schema(description = "mcp_id or cli_id, 0 when neither applies")
    var subjectId: Long = 0

    @Schema(description = "Command name for kind cli, empty otherwise")
    var toolName: String = ""

    @Schema(description = "Total invocations")
    var calls: Int = 0

    @Schema(description = "Invocations ending SUCCESS")
    var successes: Int = 0

    @Schema(description = "Invocations ending ERROR")
    var errors: Int = 0

    @Schema(description = "Invocations ending DENIED")
    var denials: Int = 0

    @Schema(description = "Invocations ending INTERRUPTED")
    var interruptions: Int = 0

    @Schema(description = "Duration total, milliseconds")
    var sumDurationMs: Long = 0

    @Schema(description = "Longest single call of the day, milliseconds")
    var maxDurationMs: Long = 0

    @Schema(description = "Calls of at most 100 ms")
    var le100ms: Int = 0

    @Schema(description = "Calls over 100 ms and at most 500 ms")
    var le500ms: Int = 0

    @Schema(description = "Calls over 500 ms and at most 2 s")
    var le2s: Int = 0

    @Schema(description = "Calls over 2 s and at most 10 s")
    var le10s: Int = 0

    @Schema(description = "Calls over 10 s and at most 30 s")
    var le30s: Int = 0

    @Schema(description = "Calls over 30 s")
    var gt30s: Int = 0
}
```

- [ ] **Step 4: 写明细 Mapper 接口与 XML（本任务只要 `batchInsert`）**

`ToolInvocationLogMapper.kt`：

```kotlin
package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Tool invocation detail writes and the retention sweep the rollup needs.
 *
 * Every read here names a tenant, and `tenantId` is a non-null `Long` where a read is tenant-scoped: there
 * is no value that means "all tenants". The rollup's three statements are the exception and are commented
 * at their own methods, because they run over the whole server rather than over one caller's window.
 *
 * SQL lives in `resources/mapper/ToolInvocationLogMapper.xml`.
 */
@Mapper
interface ToolInvocationLogMapper {

    /**
     * Append one batch of invocation rows.
     *
     * @param logs Rows to write; an empty list must not reach this method — the caller returns early
     * @return Number of rows written
     */
    fun batchInsert(
        @Param("list") logs: List<ToolInvocationLog>,
    ): Int
}
```

`ToolInvocationLogMapper.xml`（注意：注释里不许出现 `--`，`MapperXmlParseTest` 会因此炸掉全部服务）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.agnetix.harnax.mapper.ToolInvocationLogMapper">

    <!--
        One statement per batch, not one per call: a turn that runs six tools writes six rows in one
        round trip. `ts` is written by the caller rather than by NOW() because the duration and the
        aggregation bucket are both computed from the runtime's own clock, and a database-side default
        would make the two disagree by the insert latency.
    -->
    <insert id="batchInsert" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO tool_invocation_log (
        tenant_id, agent_id, session_id, user_id, kind, tool_name, mcp_id, cli_id,
        outcome, error_message, args_json, result_excerpt, duration_ms, start_time, end_time, ts
        ) VALUES
        <foreach collection="list" item="row" separator=",">
            (#{row.tenantId}, #{row.agentId}, #{row.sessionId}, #{row.userId}, #{row.kind},
             #{row.toolName}, #{row.mcpId}, #{row.cliId}, #{row.outcome}, #{row.errorMessage},
             #{row.argsJson}, #{row.resultExcerpt}, #{row.durationMs}, #{row.startTime},
             #{row.endTime}, #{row.ts})
        </foreach>
    </insert>

</mapper>
```

- [ ] **Step 5: 写失败测试**

`ToolInvocationLogMapperTest.kt` — 形状照 `TokenStatsMapperTest.kt:33-61`。闸门是「一次调用一行且三个不同时刻」，这是 I1 唯一能被持久层证明的形。

```kotlin
package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

/**
 * `tool_invocation_log` holds one row per invocation, at millisecond resolution.
 *
 * The reason a fixture here stamps explicit times a second apart rather than three calls of the same
 * instant: `start_time` is `datetime(3)`, and the only way a second-resolution column shows up is a
 * window that collapses two calls onto one instant and then reports one row for them.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class ToolInvocationLogMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        /** Seeded rows belong to tenant 1, so this class works in its own tenant. */
        private const val TENANT_ID = 21L

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var mapper: ToolInvocationLogMapper

    private fun row(
        sessionId: String,
        toolName: String,
        at: LocalDateTime,
    ) = ToolInvocationLog().apply {
        tenantId = TENANT_ID
        agentId = 7L
        this.sessionId = sessionId
        kind = ToolInvocationLog.KIND_BUILTIN
        this.toolName = toolName
        outcome = ToolInvocationLog.OUTCOME_SUCCESS
        durationMs = 42L
        startTime = at
        endTime = at
        ts = at
    }

    @Nested
    inner class Writes {
        @Test
        fun `one batch of three calls writes three rows at three distinct instants`() {
            val sessionId = "web-tool-invocation-${System.nanoTime()}"
            val base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val rows = listOf(
                row(sessionId, "read_file", base),
                row(sessionId, "read_file", base.plusSeconds(1)),
                row(sessionId, "write_file", base.plusSeconds(2)),
            )

            assertEquals(3, mapper.batchInsert(rows))

            val written = mysqlRows(sessionId)
            assertEquals(3, written.size)
            assertEquals(
                setOf(base, base.plusSeconds(1), base.plusSeconds(2)),
                written.map { it.startTime }.toSet(),
                "two calls inside one second must still be two instants",
            )
        }

        @Test
        fun `an empty batch is not sent to the database`() {
            // The guard lives in the Kotlin caller; this case pins the contract that a caller relies on:
            // handing an empty list to `foreach` produces SQL with no VALUES clause, which is a syntax error
            // rather than a zero.
            assertEquals(0, mapper.batchInsert(emptyList()))
        }
    }

    private fun mysqlRows(sessionId: String): List<ToolInvocationLog> {
        val sql = "SELECT tool_name, start_time, ts FROM tool_invocation_log WHERE session_id = ?"
        val out = mutableListOf<ToolInvocationLog>()
        mysqlContainer.createConnection("").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out += ToolInvocationLog().apply {
                            toolName = rs.getString("tool_name")
                            startTime = rs.getObject("start_time", LocalDateTime::class.java)
                            ts = rs.getObject("ts", LocalDateTime::class.java)
                        }
                    }
                }
            }
        }
        return out
    }
}
```

> 注意第二条用例与 Step 4 的注释矛盾之处：`batchInsert(emptyList())` 真库上会抛语法错误。跑测试若在此处红，正确做法是**删掉这条用例**（空批早退属 Task 6 的适配器职责，那里用假 mapper 断言「空批不调用数据库」），不要给 mapper 加空列表守卫。本计划的 Step 6 以第一条用例为准。

- [ ] **Step 6: 跑测试确认第二条被删后第一条绿**

```bash
$MVN -o -q spotless:apply -pl harnax-entity
$MVN -o test -pl harnax-entity -Dtest=ToolInvocationLogMapperTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t1.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t1.log
```
预期：`Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交**

```bash
git add harnax-admin/src/main/resources/db/migration/V1__init_schema.sql \
        harnax-entity/src/test/resources/schema-test.sql \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationLog.kt \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolInvocationStats.kt \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapperTest.kt
git commit -m "feat(metrics): tool_invocation_log 与 tool_invocation_stats 进基线并跑通批量写入"
```

---

### Task 2: 聚合 Mapper——逐日重算、待折算日期、过期清理

**Files:**
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt`
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`
- Create: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt`
- Create: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`
- Test: `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapperTest.kt`

**Interfaces:**
- Consumes: Task 1 的两张表与 `ToolInvocationLogMapper.batchInsert`。
- Produces: `ToolInvocationLogMapper.selectUnrolledDates(floor: String): List<String>`、`ToolInvocationLogMapper.deleteRolledOut(before: String): Int`、`ToolInvocationStatsMapper.upsertDay(statDate: String): Int`。Task 10 的 rollup service 按「取差集 → 逐日 `upsertDay` → `deleteRolledOut`」的顺序调用它们。日期一律 `yyyy-MM-dd`、时刻一律 `yyyy-MM-dd HH:mm:ss` 字符串（与 `TokenStatsMapper` 同形，避免 `DATE(datetime)` 与 Java 时区的第二次换算）。

- [ ] **Step 1: 写失败测试**

三条闸门：六桶之和 = `calls` = 四终态之和（I4）、重算两次结果不变（I5）、未折算的过期行删不掉（I6）。

```kotlin
package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daily rollup recomputes a whole day in one statement.
 *
 * These three gates are the invariants the design claims for the aggregate table: buckets and counters
 * both sum to `calls` (I4), an identical re-run changes nothing (I5), and a detail day the rollup has not
 * folded yet survives the retention sweep (I6). Only a real MySQL answers them — `ON DUPLICATE KEY UPDATE`
 * and `DATE()` over a `datetime(3)` are exactly what an in-memory engine gets wrong.
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class ToolInvocationStatsMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        /** Own tenant again: the seed data lives in tenant 1 and a neighbour class may use another. */
        private const val TENANT_ID = 22L

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var logMapper: ToolInvocationLogMapper

    @Autowired
    private lateinit var statsMapper: ToolInvocationStatsMapper

    private fun day(): String = java.time.LocalDate.now().minusDays(3).toString()

    private fun at(hour: Int): LocalDateTime =
        java.time.LocalDate.now().minusDays(3).atTime(hour, 0, 0).truncatedTo(ChronoUnit.SECONDS)

    private fun row(
        hour: Int,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long?,
    ) = ToolInvocationLog().apply {
        tenantId = TENANT_ID
        agentId = 7L
        sessionId = "web-rollup-fixture"
        kind = if (mcpId == null) ToolInvocationLog.KIND_BUILTIN else ToolInvocationLog.KIND_MCP
        this.toolName = toolName
        this.mcpId = mcpId
        this.cliId = null
        this.outcome = outcome
        this.durationMs = durationMs
        startTime = at(hour)
        endTime = at(hour)
        ts = at(hour)
    }

    /**
     * Six calls on one day: three buckets, two outcomes, one MCP subject keyed by its server id.
     * Chosen so the bucket sums and the outcome sums both have to come to six, and so a bucket boundary
     * is exercised from both sides (500 ms lands in le_500ms, 501 ms in le_2s).
     */
    private fun seedDay() {
        logMapper.batchInsert(
            listOf(
                row(1, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 80L, null),
                row(2, "read_file", ToolInvocationLog.OUTCOME_SUCCESS, 500L, null),
                row(3, "read_file", ToolInvocationLog.OUTCOME_ERROR, 501L, null),
                row(4, "write_file", ToolInvocationLog.OUTCOME_DENIED, 2000L, null),
                row(5, "write_file", ToolInvocationLog.OUTCOME_INTERRUPTED, 30000L, null),
                row(6, "search", ToolInvocationLog.OUTCOME_SUCCESS, 30001L, 900L),
            ),
        )
    }

    private fun query(sql: String): List<Map<String, Any?>> {
        val out = mutableListOf<MutableMap<String, Any?>>()
        mysqlContainer.createConnection("").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val md = rs.metaData
                    while (rs.next()) {
                        val row = mutableMapOf<String, Any?>()
                        for (i in 1..md.columnCount) row[md.getColumnLabel(i)] = rs.getObject(i)
                        out += row
                    }
                }
            }
        }
        return out
    }

    private fun statsRows(): List<Map<String, Any?>> =
        query("SELECT * FROM tool_invocation_stats WHERE tenant_id = $TENANT_ID AND stat_date = '$day()'".replace("$day()", day()))

    @Nested
    inner class Rollup {
        @Test
        fun `counters and buckets each sum to calls`() {
            seedDay()
            statsMapper.upsertDay(day())
            val rows = statsRows()
            assertTrue(rows.isNotEmpty())
            rows.forEach {
                val calls = (it["calls"] as Number).toInt()
                val outcomes = listOf("successes", "errors", "denials", "interruptions")
                    .sumOf { c -> (it[c] as Number).toInt() }
                val buckets = listOf("le_100ms", "le_500ms", "le_2s", "le_10s", "le_30s", "gt_30s")
                    .sumOf { c -> (it[c] as Number).toInt() }
                assertEquals(calls, outcomes, "outcome counters must equal calls")
                assertEquals(calls, buckets, "duration buckets must equal calls")
            }
            assertEquals(6, rows.sumOf { (it["calls"] as Number).toInt() })
        }

        @Test
        fun `recomputing the same day twice changes nothing`() {
            seedDay()
            statsMapper.upsertDay(day())
            val first = statsRows().sortedBy { "${it["kind"]}/${it["tool_name"]}/${it["subject_id"]}" }
            statsMapper.upsertDay(day())
            val second = statsRows().sortedBy { "${it["kind"]}/${it["tool_name"]}/${it["subject_id"]}" }
            assertEquals(first, second, "the rollup must be idempotent, so a second replica is harmless")
        }

        @Test
        fun `a day not yet rolled up survives the retention sweep`() {
            seedDay()
            val before = query("SELECT COUNT(*) AS c FROM tool_invocation_log WHERE tenant_id = $TENANT_ID").first()["c"]
            // Everything is three days old, so a window of two days would delete it all if the sweep did
            // not gate on "already rolled up" (I6).
            val deleted = logMapper.deleteRolledOut(
                java.time.LocalDate.now().minusDays(2).atStartOfDay().toString().replace('T', ' '),
            )
            assertEquals(0, deleted)
            val after = query("SELECT COUNT(*) AS c FROM tool_invocation_log WHERE tenant_id = $TENANT_ID").first()["c"]
            assertEquals(before, after)

            statsMapper.upsertDay(day())
            val rolledDeleted = logMapper.deleteRolledOut(
                java.time.LocalDate.now().minusDays(2).atStartOfDay().toString().replace('T', ' '),
            )
            assertEquals(6, rolledDeleted)
        }
    }
}
```

> 时区陷阱：容器是 UTC，JVM 按本地时区写 `datetime`。所有断言都用「相对当天」（`LocalDate.now().minusDays(3)`）而不是绝对日期，且写入与查询走同一 JVM，因此不比较绝对日界。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o test -pl harnax-entity -Dtest=ToolInvocationStatsMapperTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t2-red.log 2>&1; echo EXIT=$?
```
预期：`test-compile` 失败，`unresolved reference` 指向 `ToolInvocationStatsMapper`、`selectUnrolledDates`、`deleteRolledOut`、`upsertDay`。

- [ ] **Step 3: 给明细 Mapper 补两条语句**

`ToolInvocationLogMapper.kt` 追加：

```kotlin
    /**
     * Days the detail table holds but the aggregate does not.
     *
     * Detail rows with no tenant are excluded on purpose: the aggregate table's `tenant_id` is `NOT NULL`,
     * so no such day would ever gain a row to be compared against, and every hourly run would report it
     * pending. Those rows are still pruned by [deleteRolledOut], which names them as its one exception.
     *
     * @param floor Oldest day to consider, `yyyy-MM-dd`; normally the retention window's edge
     * @return Pending days, oldest first, as `yyyy-MM-dd`
     */
    fun selectUnrolledDates(
        @Param("floor") floor: String,
    ): List<String>

    /**
     * Prune detail rows older than [before], and only those the rollup has already folded.
     *
     * The gate is the aggregate table rather than the date arithmetic because that is the only way I6
     * survives a missed run: a day that never got rolled up stays queryable instead of vanishing. Rows
     * with no tenant are the exception and leave on the window alone — nothing will ever fold them.
     *
     * @param before Cutoff instant, `yyyy-MM-dd HH:mm:ss`
     * @return Number of detail rows deleted
     */
    fun deleteRolledOut(
        @Param("before") before: String,
    ): Int
```

`ToolInvocationLogMapper.xml` 追加（`<delete>` 标签必须用 `delete`，MyBatis 的 Kotlin 集成里 `<update>` 装 delete 语句会报错）：

```xml
    <!--
        The date set the rollup owes, oldest first. Grouping rather than a subquery against the aggregate
        keeps this one pass over the index on (tenant_id, ts).
    -->
    <select id="selectUnrolledDates" resultType="string">
        SELECT DATE_FORMAT(l.ts, '%Y-%m-%d') AS stat_date
        FROM tool_invocation_log l
        WHERE l.tenant_id IS NOT NULL
        AND l.ts &gt;= #{floor}
        AND NOT EXISTS (
            SELECT 1 FROM tool_invocation_stats s
            WHERE s.stat_date = DATE(l.ts)
            AND s.tenant_id = l.tenant_id
        )
        GROUP BY DATE_FORMAT(l.ts, '%Y-%m-%d')
        ORDER BY stat_date ASC
    </select>

    <delete id="deleteRolledOut">
        DELETE FROM tool_invocation_log
        WHERE ts &lt; #{before}
        AND (
            tenant_id IS NULL
            OR EXISTS (
                SELECT 1 FROM tool_invocation_stats s
                WHERE s.stat_date = DATE(tool_invocation_log.ts)
                AND s.tenant_id = tool_invocation_log.tenant_id
            )
        )
    </delete>
```

- [ ] **Step 4: 写聚合 Mapper 与 XML**

`ToolInvocationStatsMapper.kt`：

```kotlin
package com.agnetix.harnax.mapper

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * The daily fold of `tool_invocation_log`.
 *
 * One statement, and it is an aggregate recompute rather than an increment: the whole day is counted from
 * the detail rows and every column is overwritten. That is what makes the rollup safe to run twice, safe
 * to run on two replicas at once, and safe to run for a day that was missed a week ago — none of which an
 * `calls = calls + n` increment would give.
 *
 * These two reads are deliberately not tenant-scoped, unlike every read in `ToolInvocationLogMapper`: the
 * rollup is a server-wide maintenance job that writes one row per tenant, and its own tenant predicate
 * would fold only the workspace that happens to run the job.
 */
@Mapper
interface ToolInvocationStatsMapper {

    /**
     * Recompute one day in full.
     *
     * @param statDate Day to fold, `yyyy-MM-dd`
     * @return Rows touched; MySQL counts an updated row as 2 and an unchanged one as 0, so the number is
     * not a row count and callers must not read it as one
     */
    fun upsertDay(
        @Param("statDate") statDate: String,
    ): Int
}
```

`ToolInvocationStatsMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.agnetix.harnax.mapper.ToolInvocationStatsMapper">

    <!--
        Buckets are half open on the low side, so a call of exactly 500 ms lands in le_500ms and one of
        501 ms in le_2s. Closed intervals on both sides would claim a boundary call twice and the bucket
        sum would stop equalling `calls` (I4).

        `subject_id` is written with a COALESCE rather than left null, because the unique key treats two
        NULLs as different rows: a NULL subject would let the same day insert twice.
        The subject is also part of the grouping key, because it is what an operator reads the row as: one
        agent mounts several MCP servers, and a single `mcp` group per tenant would leave the highest server
        id holding every call of that day. The tool name is part of it too, and for every kind: one server
        exposes several tools and one agent has several builtins, so a group that dropped the name would fold
        tools the page lists apart into one row, and a reader could not tell which tool of a server answered.
        The same expression is selected and grouped, so the value is the group's own key rather than an
        aggregate taken over it.
        The day is bounded by an instant range rather than by `DATE(l.ts) = #{statDate}`: a function over the
        column is not sargable, so the optimizer cannot use it to narrow the scan.
        The update list covers every column the SELECT produces, including the counters, because this is a
        recompute and not an increment.
    -->
    <insert id="upsertDay">
        INSERT INTO tool_invocation_stats (
        stat_date, tenant_id, kind, subject_id, tool_name,
        calls, successes, errors, denials, interruptions,
        sum_duration_ms, max_duration_ms,
        le_100ms, le_500ms, le_2s, le_10s, le_30s, gt_30s
        )
        SELECT
        #{statDate},
        l.tenant_id,
        l.kind,
        CASE
        WHEN l.kind = 'mcp' THEN COALESCE(l.mcp_id, 0)
        WHEN l.kind = 'cli' THEN COALESCE(l.cli_id, 0)
        ELSE 0
        END,
        l.tool_name,
        COUNT(*),
        SUM(CASE WHEN l.outcome = 'SUCCESS' THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.outcome = 'ERROR' THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.outcome = 'DENIED' THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.outcome = 'INTERRUPTED' THEN 1 ELSE 0 END),
        SUM(l.duration_ms),
        MAX(l.duration_ms),
        SUM(CASE WHEN l.duration_ms &lt;= 100 THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.duration_ms &gt; 100 AND l.duration_ms &lt;= 500 THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.duration_ms &gt; 500 AND l.duration_ms &lt;= 2000 THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.duration_ms &gt; 2000 AND l.duration_ms &lt;= 10000 THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.duration_ms &gt; 10000 AND l.duration_ms &lt;= 30000 THEN 1 ELSE 0 END),
        SUM(CASE WHEN l.duration_ms &gt; 30000 THEN 1 ELSE 0 END)
        FROM tool_invocation_log l
        WHERE l.ts &gt;= #{statDate}
        AND l.ts &lt; DATE_ADD(#{statDate}, INTERVAL 1 DAY)
        AND l.tenant_id IS NOT NULL
        GROUP BY l.tenant_id, l.kind,
        CASE
        WHEN l.kind = 'mcp' THEN COALESCE(l.mcp_id, 0)
        WHEN l.kind = 'cli' THEN COALESCE(l.cli_id, 0)
        ELSE 0
        END,
        l.tool_name
        ON DUPLICATE KEY UPDATE
        calls = VALUES(calls),
        successes = VALUES(successes),
        errors = VALUES(errors),
        denials = VALUES(denials),
        interruptions = VALUES(interruptions),
        sum_duration_ms = VALUES(sum_duration_ms),
        max_duration_ms = VALUES(max_duration_ms),
        le_100ms = VALUES(le_100ms),
        le_500ms = VALUES(le_500ms),
        le_2s = VALUES(le_2s),
        le_10s = VALUES(le_10s),
        le_30s = VALUES(le_30s),
        gt_30s = VALUES(gt_30s)
    </insert>

</mapper>
```

> `subject_id` 必须与 `GROUP BY` 用同一个确定表达式，不能写成 `MAX()`：一个智能体挂多个 MCP server，`kind = mcp` 的一组里本来就会有多个 `mcp_id`，折成一行等于把全天的调用记到 id 最大的那个 server 上。Task 11 的读侧按 `(kind, subject_id, tool_name)` 分组、Task 12 直接把 `subjectId` 当 `mcpId`/`cliId`，所以这一条是聚合表能不能答题的分界。（本行原文写的是「由分类器保证一组内 `mcp_id` 唯一」，那是错的；落地时按上述改法纠正。）

- [ ] **Step 5: 跑测试确认绿**

```bash
$MVN -o -q spotless:apply -pl harnax-entity
$MVN -o test -pl harnax-entity -Dtest=ToolInvocationStatsMapperTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t2.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t2.log
```
预期：`Failures: 0, Errors: 0`。`Tests run` 等于这一步测试文件里 `@Test` 的条数，不在此处钉死。

- [ ] **Step 6: 提交**

```bash
git add harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapperTest.kt
git commit -m "feat(metrics): 调用指标的逐日折算、待补日期与已折算才删的清理"
```

---

### Task 3: kind 判定与 CLI 归因的纯函数

**Files:**
- Create: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifier.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifierTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ToolInvocationLog.KIND_*` 常量（`harnax-harness-core` 的 pom 已依赖 `harnax-entity`）。
- Produces:
  - `data class InvocationAttribution(val kind: String, val toolName: String, val mcpId: Long?, val cliId: Long?)`
  - `ToolInvocationClassifier.classify(toolName: String, input: Map<String, Any?>, mcpIdsByTool: Map<String, Long>, cliIdsByCommand: Map<String, Long>, builtinToolNames: Set<String>): InvocationAttribution`
  - `ToolInvocationClassifier.cliCommandName(command: String?, cliIdsByCommand: Map<String, Long>): String?`
  - `ToolInvocationClassifier.SHELL_TOOL_NAMES`、`SKILL_LOAD_TOOL_NAME`
  - `ToolInvocationClassifier.commandHeads(command: String): List<String>`（公开，Task 8 用它从 `CliSpec.checkCommand` 取别名）
  - Task 6 的中间件只调 `classify` 与 `SKILL_LOAD_TOOL_NAME`；Task 8 调 `commandHeads` 组 CLI 别名表。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * `kind` is decided by what assembly already knew, in one fixed order (design D4).
 *
 * The five cases below are the five shapes an incoming tool name can have; the priority cases are the
 * ones that would silently misfile a call if the order were read differently — an MCP server that ships a
 * tool named like a delivered one, and a shell command that runs a delivered CLI, are both real.
 */
class ToolInvocationClassifierTest {

    private val mcpIds = mapOf("github_search" to 11L)
    private val cliIds = mapOf("gh" to 21L, "aws" to 22L)
    private val builtins = setOf("send_email", "now")

    @Nested
    inner class Kind {
        @Test
        fun `a tool registered from an MCP server is mcp`() {
            val a = ToolInvocationClassifier.classify("github_search", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_MCP, a.kind)
            assertEquals(11L, a.mcpId)
            assertEquals("github_search", a.toolName)
        }

        @Test
        fun `the shell tool running a delivered CLI is cli keyed by the command name`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "gh pr view 12"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_CLI, a.kind)
            assertEquals(21L, a.cliId)
            assertEquals("gh", a.toolName, "the row must name the CLI, not the shell tool that ran it")
        }

        @Test
        fun `the shell tool without a delivered CLI is shell`() {
            val a = ToolInvocationClassifier.classify(
                "execute_shell_command",
                mapOf("command" to "ls -la"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_SHELL, a.kind)
            assertEquals("execute_shell_command", a.toolName)
            assertNull(a.cliId)
        }

        @Test
        fun `a tool delivered with the agent spec is builtin`() {
            val a = ToolInvocationClassifier.classify("send_email", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, a.kind)
        }

        @Test
        fun `anything else the harness registered is framework`() {
            val a = ToolInvocationClassifier.classify("read_file", emptyMap(), mcpIds, cliIds, builtins)
            assertEquals(ToolInvocationLog.KIND_FRAMEWORK, a.kind)
        }
    }

    @Nested
    inner class Priority {
        @Test
        fun `mcp wins over builtin when both names could match`() {
            val a = ToolInvocationClassifier.classify(
                "github_search",
                emptyMap(),
                mcpIds,
                cliIds,
                setOf("github_search"),
            )
            assertEquals(ToolInvocationLog.KIND_MCP, a.kind, "the registry is what the model actually sees")
        }

        @Test
        fun `a shell command that names an mcp tool is still shell not mcp`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "github_search"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(ToolInvocationLog.KIND_SHELL, a.kind)
        }

        @Test
        fun `a compound command records only the leftmost delivered cli`() {
            val a = ToolInvocationClassifier.classify(
                "execute",
                mapOf("command" to "gh pr view && aws s3 ls"),
                mcpIds,
                cliIds,
                builtins,
            )
            assertEquals(21L, a.cliId, "one invocation is one row (I1), so the second CLI is not duplicated")
        }
    }

    @Nested
    inner class CommandNames {
        @Test
        fun `an absolute path is matched by its file name`() {
            assertEquals(
                "aws",
                ToolInvocationClassifier.cliCommandName("/usr/local/bin/aws s3 ls", cliIds),
            )
        }

        @Test
        fun `an environment prefix does not become the command`() {
            assertEquals(
                "gh",
                ToolInvocationClassifier.cliCommandName("GH_PAGER=cat gh pr list", cliIds),
            )
        }

        @Test
        fun `a quoted command with a space is still one word`() {
            val ids = mapOf("my tool" to 31L)
            assertEquals("my tool", ToolInvocationClassifier.cliCommandName("\"my tool\" --version", ids))
        }

        @Test
        fun `pipes and semicolons open a new segment`() {
            assertEquals(
                "aws",
                ToolInvocationClassifier.cliCommandName("echo x | /bin/aws s3 ls", cliIds),
            )
            assertEquals("aws", ToolInvocationClassifier.cliCommandName("cd /tmp; aws s3 ls", cliIds))
        }

        @Test
        fun `nothing delivered matches an ordinary command`() {
            assertNull(ToolInvocationClassifier.cliCommandName("uname -a", cliIds))
            assertNull(ToolInvocationClassifier.cliCommandName(null, cliIds))
            assertNull(ToolInvocationClassifier.cliCommandName("gh --version", emptyMap()))
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest=ToolInvocationClassifierTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t3-red.log 2>&1; echo EXIT=$?
```
预期：`test-compile` 报 `unresolved reference: ToolInvocationClassifier`。

- [ ] **Step 3: 写实现**

```kotlin
package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.entity.ToolInvocationLog

/**
 * Where a tool call came from, decided from what assembly already knew.
 *
 * No database read here (design D4): the runtime holds none of Admin's tables, and a lookup per call
 * would turn a counter into a round trip on the inference path. The three maps below are built once per
 * agent build and are the only facts this needs.
 *
 * The order is the contract, not an implementation detail. The registry is what the model could actually
 * call, so an MCP answer beats a name that also appears in the delivered tool list; the shell is checked
 * before the delivered list because a CLI runs *through* it, and a name that is neither lands as
 * `framework` — a harness built-in nothing in Admin's tables names.
 */
object ToolInvocationClassifier {

    /** Both shell shapes: the harness sandbox tool and the core coding tool. */
    val SHELL_TOOL_NAMES = setOf("execute", "execute_shell_command")

    /** Upstream's fixed name for the skill loader; it is package private upstream, so it is a literal here. */
    const val SKILL_LOAD_TOOL_NAME = "load_skill_through_path"

    /** Argument that holds the command line on either shell tool. */
    private const val COMMAND_ARG = "command"

    /** A `VAR=value` word before a command is not the command. */
    private val ENV_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""")

    private val SEGMENT_OPERATORS = setOf("|", "||", "&&", ";")
}

/**
 * The row's identity beyond the tool name: which `kind` it is and which subject it belongs to.
 *
 * [toolName] differs from the called tool only for `kind = cli`, where the CLI's command name is what an
 * operator reads; the shell tool that ran it is the same for every CLI.
 */
data class InvocationAttribution(
    val kind: String,
    val toolName: String,
    val mcpId: Long?,
    val cliId: Long?,
)
```

> 上面这个文件先放常量与类型；`classify` 与命令解析放在同一文件的下面，保持一次 `classify` 调用就能读完整判定。

在同文件继续追加：

```kotlin
// Appended inside `object ToolInvocationClassifier`:

    /**
     * @param toolName Name as the model sees it
     * @param input Tool arguments, used to read the shell command line
     * @param mcpIdsByTool Tool name to MCP server row, from the assembled registry
     * @param cliIdsByCommand CLI command name to package row, from the delivered spec
     * @param builtinToolNames Framework names of the tools Admin delivered
     */
    fun classify(
        toolName: String,
        input: Map<String, Any?>,
        mcpIdsByTool: Map<String, Long>,
        cliIdsByCommand: Map<String, Long>,
        builtinToolNames: Set<String>,
    ): InvocationAttribution {
        mcpIdsByTool[toolName]?.let {
            return InvocationAttribution(ToolInvocationLog.KIND_MCP, toolName, it, null)
        }
        if (toolName in SHELL_TOOL_NAMES) {
            val command = input[COMMAND_ARG] as? String
            val name = cliCommandName(command, cliIdsByCommand)
            return if (name == null) {
                InvocationAttribution(ToolInvocationLog.KIND_SHELL, toolName, null, null)
            } else {
                InvocationAttribution(ToolInvocationLog.KIND_CLI, name, null, cliIdsByCommand[name])
            }
        }
        if (toolName in builtinToolNames) {
            return InvocationAttribution(ToolInvocationLog.KIND_BUILTIN, toolName, null, null)
        }
        return InvocationAttribution(ToolInvocationLog.KIND_FRAMEWORK, toolName, null, null)
    }

    /**
     * The leftmost word of [command] that names a delivered CLI, or null when none does.
     *
     * Only the first match is returned: a compound command is still one invocation of one tool, and
     * splitting it into rows would break the one-row rule (I1). The full command line stays in
     * `args_json` for anyone who needs to check the attribution afterwards.
     */
    fun cliCommandName(
        command: String?,
        cliIdsByCommand: Map<String, Long>,
    ): String? {
        if (command.isNullOrBlank() || cliIdsByCommand.isEmpty()) return null
        for (head in commandHeads(command)) {
            val name = head.substringAfterLast('/')
            if (name.isNotEmpty() && cliIdsByCommand.containsKey(name)) return name
        }
        return null
    }

    /**
     * First word of each segment: segments split on `|`, `||`, `&&`, `;`, and a `VAR=value` prefix is skipped.
     *
     * Public because assembly uses it to read a delivered CLI package's `checkCommand` for the names that
     * package can be invoked by (Task 8), which is the same question this function answers for a live command.
     */
    fun commandHeads(command: String): List<String> {
        val heads = mutableListOf<String>()
        var expectHead = true
        for (token in tokenize(command)) {
            when {
                token in SEGMENT_OPERATORS -> expectHead = true
                expectHead && !ENV_ASSIGNMENT.matches(token) -> {
                    heads += token
                    expectHead = false
                }
            }
        }
        return heads
    }

    /**
     * Split on whitespace outside quotes, treating the pipeline operators as boundaries of their own.
     *
     * Quotes are honoured because a delivered CLI may contain a space, and an operator inside a quoted
     * argument must not open a segment. An unquoted operator inside an argument is accepted as a segment
     * break: no command name this feature matches contains one, and the cost of getting it wrong is a
     * row filed as `shell` rather than as the CLI.
     */
    private fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                if (c == quote) {
                    quote = null
                    if (buf.isNotEmpty()) {
                        tokens += buf.toString()
                        buf.setLength(0)
                    }
                } else {
                    buf.append(c)
                }
                i++
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                i++
                continue
            }
            if (c.isWhitespace()) {
                if (buf.isNotEmpty()) {
                    tokens += buf.toString()
                    buf.setLength(0)
                }
                i++
                continue
            }
            if (c == '|' || c == '&' || c == ';') {
                if (buf.isNotEmpty()) {
                    tokens += buf.toString()
                    buf.setLength(0)
                }
                val doubled = i + 1 < command.length && command[i + 1] == c
                tokens += if (doubled) "$c$c" else "$c"
                i += if (doubled) 2 else 1
                continue
            }
            buf.append(c)
            i++
        }
        if (buf.isNotEmpty()) tokens += buf.toString()
        return tokens
    }
```

`tokenize` 是 `private`，不给它开可见性；`commandHeads` 必须保持 `public`，Task 8 要用它从 `CliSpec.checkCommand` 取 CLI 别名表（写成 `private` 会让 Task 8 编译不过）。

- [ ] **Step 4: 跑测试确认绿**

```bash
$MVN -o -q spotless:apply -pl harnax-agent/harnax-harness-core
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest=ToolInvocationClassifierTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t3.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t3.log
```
预期：`Failures: 0, Errors: 0`。`Tests run` 等于这一步测试文件里 `@Test` 的条数，不在此处钉死（落地时实跑为 13）。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifier.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationClassifierTest.kt
git commit -m "feat(metrics): 调用 kind 判定与 CLI 命令归因的纯函数"
```

---

### Task 4: tools-sdk 的写入契约

**Files:**
- Create: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt`

**Interfaces:**
- Consumes: 无（纯契约，tools-sdk 不依赖 harness-core）。
- Produces: `fun interface ToolInvocationAdaptor { fun emit(event: ToolInvocationEvent) }` 与 `data class ToolInvocationEvent(tenantId: Long?, agentId: Long?, sessionId: String, userId: Long?, kind: String, toolName: String, mcpId: Long? = null, cliId: Long? = null, outcome: String, argsJson: String?, resultText: String?, errorMessage: String?, startEpochMilli: Long, endEpochMilli: Long)`——`kind` 与 `outcome` 的取值就是 Task 1 的 `ToolInvocationLog.KIND_*` / `OUTCOME_*` 常量，由调用方（Task 6 中间件）填常量而不是自己拼字符串。Task 6 投递、Task 7 实现。

> 契约里不能引 `InvocationAttribution`（那个类型在 harness-core），也不能引 `ToolInvocationLog`（tools-sdk 不依赖 harnax-entity 的这张表）。所以载荷用三个平摊字段：`kind: String`、`mcpId: Long?`、`cliId: Long?`。

- [ ] **Step 1: 写契约与载荷**

```kotlin
package com.agnetix.harnax.tools.sdk.adaptor

/**
 * Files the fact that one tool call of one session reached a terminal state.
 *
 * The event source is the acting middleware, which sees every tool the model could call: a delivered
 * tool, an MCP server's tool, the shell, and a harness built-in. That is why this is a runtime contract
 * rather than a method on a tool base class — a recording point that lives on an implementation class
 * only ever sees the implementations that go through it, which is exactly how the previous log came to
 * hold nothing but built-in tools.
 *
 * Implementations must return without waiting for the database and must never throw. This runs while the
 * model's acting step is streaming: a reporter that blocks adds its latency to the answer, and one that
 * throws fails the answer over a lost counter. Failures belong to the implementation — log them, drop
 * the event, never rethrow.
 */
fun interface ToolInvocationAdaptor {
    fun emit(event: ToolInvocationEvent)
}

/**
 * One invocation, already classified, with its own attribution carried in.
 *
 * [kind], [mcpId] and [cliId] are flat rather than a value object because the SDK holds no dependency on
 * the harness module that decides them; [ToolInvocationAdaptor] implementations read them as they arrive.
 * Times are epoch milliseconds because the caller measured them with `System.currentTimeMillis()` and a
 * conversion to wall-clock columns belongs to whoever writes the row.
 */
data class ToolInvocationEvent(
    /** Tenant of the run, from `AgentSpec.tenantId`. Null means nothing attributed it: the row stays unattributed. */
    val tenantId: Long?,
    /** Agent of the run, null for a team lead, which has no `agent` row. */
    val agentId: Long?,
    val sessionId: String,
    /** End user behind the run, null for a channel conversation or a service key. */
    val userId: Long?,
    /** `builtin` / `mcp` / `cli` / `shell` / `framework`. */
    val kind: String,
    /** Tool name as the model sees it, or the CLI command name when [kind] is `cli`. */
    val toolName: String,
    val mcpId: Long? = null,
    val cliId: Long? = null,
    /** `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`. A non-terminal state never produces an event. */
    val outcome: String,
    /** Tool input as JSON, or null when payload capture is off. */
    val argsJson: String?,
    /** Accumulated result text, or null when payload capture is off. */
    val resultText: String?,
    /** Failure reason, present on the non-success outcomes. */
    val errorMessage: String?,
    val startEpochMilli: Long,
    val endEpochMilli: Long,
)
```

- [ ] **Step 2: 编译验证**

```bash
$MVN -o -q spotless:apply -pl harnax-agent/harnax-tools-sdk
$MVN -o -am -q test-compile -pl harnax-agent/harnax-tools-sdk > /tmp/t4.log 2>&1; echo EXIT=$?
```
预期：EXIT=0，日志无 `[ERROR]`。

- [ ] **Step 3: 提交**

```bash
git add harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolInvocationAdaptor.kt
git commit -m "feat(metrics): 工具调用事件的写入契约"
```

---

### Task 5: 技能 USE 的上报链路（先于中间件，因为中间件要调它）

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/SkillUsageAdaptor.kt`
- Modify: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillUsageAdaptorImpl.kt`
- Modify: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt:254-280`
- Test: `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillUsageAdaptorImplTest.kt`（新增用例 + 改 9 处既有实参 + 补两处排空断言）
- Modify: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/SkillViewRecorderTest.kt:19`（`FakeAdaptor`）
- Modify: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherSkillUsageTest.kt:35`（`FakeUsage`）

**Interfaces:**
- Consumes: Task 4 无（本任务与调用指标表无关，是规格 §9 的连带改动）。
- Produces: `SkillUsageAdaptor.reportUses(sessionId: String, skillIds: List<Long>, userId: Long?)`，以及 `AdminApiClient.reportSkillUsage(sessionId: String, skillIds: List<Long>, userId: Long?, event: String): Boolean`——**四个必填参数、不给默认值**，因为 `SkillUsageAdaptorImplTest` 按精确实参打桩，一个带默认值的参数会让 Mockito 的验证落到 `$default` 桥接上而静默匹配不到。Task 6 的中间件调 `reportUses`。
- 服务端不需放宽：`SkillUsageServiceImpl.report`（`:91-94`）已同时接受 `VIEW` 与 `USE`。

- [ ] **Step 1: 写失败测试**

在 `SkillUsageAdaptorImplTest` 末尾追加一条用例（该类已有 `client` / `adaptor` / `tearDown` 三件，直接用）：

```kotlin
    @Test
    fun `a use reports USE rather than reusing the load event`() {
        // The two events answer different questions and the page shows them in different columns; a reporter
        // that filed the load vocabulary for both would make every use count read as a load.
        `when`(client.reportSkillUsage("web-1", listOf(7L), 1L, "USE")).thenReturn(true)

        adaptor.reportUses("web-1", listOf(7L), 1L)

        verify(client, timeout(5_000)).reportSkillUsage("web-1", listOf(7L), 1L, "USE")
    }

    @Test
    fun `an empty use asks Admin nothing`() {
        adaptor.reportUses("web-1", emptyList(), 1L)

        // Same drain as `an empty read asks Admin nothing`, for the same reason.
        adaptor.shutdown()

        verifyNoInteractions(client)
    }
```

同时把该文件里 9 处三参调用补上第四个实参 `"VIEW"`——打桩 5 处（`:36`、`:63`、`:75`、`:76`、`:89`）与验证 4 处（`:68`、`:83`、`:84`、`:94`）都要改，漏一处是编译错误而不是静默通过，因为第四个参数没有默认值：

```kotlin
        `when`(client.reportSkillUsage("web-1", listOf(7L), 1L, "VIEW")).thenAnswer {
        verify(client, timeout(5_000)).reportSkillUsage("web-1", listOf(9L), 1L, "VIEW")
```

`an empty read asks Admin nothing` 与上面新加的空批用例还得各补一行：两者都用 `verifyNoInteractions`，而 `submit` 只做入队，断言跑在 worker 之前，把 `if (skillIds.isEmpty()) return` 整行删掉两条用例照样绿（变异实测 4/4 存活）。断言前先 `adaptor.shutdown()` 把队列排空——本类 `a batch queued before shutdown is still sent` 已经是这个形状，该文件 `:81-82` 的注释也已经在说同一件事：不排空的验证「worker 抢到就跑赢、没抢到就失败」。
- [ ] **Step 2: 跑测试确认红**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN=/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn
$MVN -o -am test -pl harnax-agent/harnax-agent-service -Dtest=SkillUsageAdaptorImplTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t5-red.log 2>&1; echo EXIT=$?
grep -E "unresolved reference|Tests run" /tmp/t5-red.log | head
```
预期：`EXIT != 0`，先以 `unresolved reference: reportUses` / `No value passed for parameter` 形式的**编译失败**出现——这就是红，不必等断言失败。

- [ ] **Step 3: 契约改为两个方法**

`SkillUsageAdaptor.kt` 整文件替换（`fun interface` 必须变成普通接口，两个方法无法用 SAM 表达）：

```kotlin
package com.agnetix.harnax.agent.adaptor

/**
 * Files what this session did with the skills Admin delivered to it.
 *
 * The runtime holds no skill table of its own — Admin delivered the text — so the count has to travel back
 * over the same internal API that delivered it. Two events exist because two questions exist: whether a
 * skill entered the context, and whether the model then worked through its body.
 *
 * Implementations must return without waiting for the network. Both calls sit on a path that streams an
 * answer: a reporter that blocks would add its latency to every model call, and one that throws would fail
 * a turn over a lost counter. Failures belong to the implementation — log them, drop the batch, never
 * rethrow.
 */
interface SkillUsageAdaptor {
    /**
     * @param userId the [com.agnetix.harnax.tools.sdk.UserIdentifier] this run is attributed to, or null when
     * the conversation names no harnax user — a channel conversation, or a service caller that did not
     * resolve one. Admin keeps the row and leaves its user column empty; it does not take the id on faith,
     * since the tenant of the report is resolved from [sessionId] and a user outside that tenant would put a
     * count on somebody else's analytics page.
     */
    fun reportViews(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    )

    /**
     * A skill whose instructions the model actually worked through: its `SKILL.md` was read back through the
     * skill loader and came back successfully. Reading a resource file of the same skill is not a use —
     * only the body carries instructions.
     *
     * No cooldown, unlike [reportViews]: a load repeats because the harness re-reads the repository on every
     * system-prompt assembly, while one turn loads one skill once. Same argument shape and same non-blocking,
     * non-throwing contract.
     */
    fun reportUses(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    )
}
```

- [ ] **Step 4: 实现与客户端**

`SkillUsageAdaptorImpl.kt`：把 `reportViews` 的函数体抽成私有 `submit(...)`，两个 override 各传自己的事件词，并把 `companion object` 补上两个常量。整段替换 `:45-95`。类 KDoc 一并改口径：它写的是「Posts skill VIEW events」且只说 VIEW，现在两类都从这里出去；而「溢出丢弃只亏一个冷却窗口」那句只对装载成立——USE 没有冷却、一轮只用一次，丢了就没了，照实把两类写成分开的两句。

```kotlin
    override fun reportViews(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    ) = submit(sessionId, skillIds, userId, EVENT_VIEW)

    override fun reportUses(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    ) = submit(sessionId, skillIds, userId, EVENT_USE)

    private fun submit(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
        event: String,
    ) {
        if (skillIds.isEmpty()) return
        try {
            reporter.execute {
                try {
                    // The client turns transport failures into `false`; logged here too, so the reason for a
                    // missing count survives even when Admin answered with a business error instead of throwing.
                    if (!adminApiClient.reportSkillUsage(sessionId, skillIds, userId, event)) {
                        log.debug("Skill {} batch for session {} ({} skill(s)) was not accepted by Admin", event, sessionId, skillIds.size)
                    }
                } catch (e: Exception) {
                    // Caught rather than left to kill the worker: an implementation that throws is breaking
                    // its side of the contract, and the batches after it still owe Admin a count.
                    log.warn("Skill {} batch for session {} ({} skill(s)) failed: {}", event, sessionId, skillIds.size, e.message)
                }
            }
        } catch (e: Exception) {
            // RejectedExecutionException, both when the queue is full and after shutdown. A counter
            // never reaches the caller: this is the last line of the non-blocking, never-throws contract.
            val total = dropped.incrementAndGet()
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                log.warn("Skill {} batch for session {} dropped ({} dropped so far): {}", event, sessionId, total, e.message)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        // Give what is already queued a few seconds, then stop: pending events are counters, not state.
        reporter.shutdown()
        try {
            if (!reporter.awaitTermination(5, TimeUnit.SECONDS)) reporter.shutdownNow()
        } catch (e: InterruptedException) {
            reporter.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 64
        private const val DROP_LOG_EVERY = 50L
        private const val EVENT_VIEW = "VIEW"
        private const val EVENT_USE = "USE"
    }
```

`AdminApiClient.kt:254-266`：`reportSkillUsage` 增加第四个必填参数 `event: String`，KDoc 里「VIEW is the one event this caller can assert」那句改掉（现在由调用方决定），日志与事件体用参数：

```kotlin
    fun reportSkillUsage(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
        event: String,
    ): Boolean {
        val url = "$adminUrl/api/admin/internal/skills/usage"
        log.debug("[Agent→Admin] POST {} - reporting {} {} event(s)", url, skillIds.size, event)

        val body = mapOf(
            "sessionId" to sessionId,
            "userId" to userId,
            "events" to skillIds.map { mapOf("skillId" to it, "event" to event) },
        )
```
其余（`responseType`、`exchange`、成功判定）一行不动。

- [ ] **Step 5: 改两个测试替身**

`SkillViewRecorderTest.kt` 的 `FakeAdaptor`（`:19` 起）与 `HarnessAgentLauncherSkillUsageTest.kt` 的 `FakeUsage`（`:35` 起）都是手写的 `SkillUsageAdaptor` 实现，接口多了一个方法就必须各补一个 `reportUses`，否则整个 harness-core 测试源码集编译不过。两边都在各自已有的两个列表旁边加一对平行列表，不与 `batches` 合并——合并后断言就分不清哪一批是装载、哪一批是使用：

`SkillViewRecorderTest.kt` 的 `FakeAdaptor`，在 `val users = mutableListOf<Long?>()` 之后加列表，在 `reportViews` 之后加实现：

```kotlin
        /** Separate from [batches]: the recorder under test only ever reports loads, so this stays empty. */
        val uses = mutableListOf<List<Long>>()

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            uses += skillIds
        }
```

`HarnessAgentLauncherSkillUsageTest.kt` 的 `FakeUsage`，同位置加：

```kotlin
        val useBatches = mutableListOf<Pair<String, List<Long>>>()
        val useUsers = mutableListOf<Long?>()

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            useBatches += sessionId to skillIds
            useUsers += userId
        }
```

两个类的既有断言都不用改：装载与使用都发生在 Task 6 的中间件里，recorder 和 launcher 这两个被测对象不会调 `reportUses`，所以新列表在这两个测试里恒空——这正是它们值得单列一份的原因，日后若有人把 USE 错挂到 recorder 上，这里会立刻有非空列表可断。

- [ ] **Step 6: 跑绿**

```bash
$MVN -q spotless:apply -pl harnax-agent/harnax-harness-core,harnax-agent/harnax-agent-service
$MVN -o -am test -pl harnax-agent/harnax-agent-service -Dtest=SkillUsageAdaptorImplTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t5.log 2>&1; echo EXIT=$?
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest='SkillViewRecorderTest,HarnessAgentLauncherSkillUsageTest' -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t5b.log 2>&1; echo EXIT=$?
grep -E "Tests run:.*Failures" /tmp/t5.log /tmp/t5b.log | tail -4
```
预期：两份日志都有 `Tests run:` 行且 `Failures: 0, Errors: 0`（`SkillViewRecorderTest` 用 `-Dtest` 点名时**不会**触发容器测，`Memory*Test` 那两个类不在名单里）。

- [ ] **Step 7: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/SkillUsageAdaptor.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/SkillViewRecorderTest.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncherSkillUsageTest.kt \
        harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillUsageAdaptorImpl.kt \
        harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/client/AdminApiClient.kt \
        harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/SkillUsageAdaptorImplTest.kt
git commit -m "feat(skill): 技能用量补 USE 事件的写入方与上报词"
```

---

### Task 6: ToolInvocationMiddleware——唯一事件源

**Files:**
- Create: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt`
- Test: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddlewareTest.kt`

**Interfaces:**
- Consumes: Task 3 的 `ToolInvocationClassifier.classify(...)` / `SKILL_LOAD_TOOL_NAME`、Task 4 的 `ToolInvocationAdaptor` + `ToolInvocationEvent`、Task 5 的 `SkillUsageAdaptor.reportUses`、Task 1 的 `ToolInvocationLog.KIND_*` / `OUTCOME_*` 常量。
- Produces: `class ToolInvocationMiddleware(adaptor: ToolInvocationAdaptor, tenantId: Long?, agentId: Long?, sessionId: String, userId: Long?, mcpIdsByTool: Map<String, Long>, cliIdsByCommand: Map<String, Long>, builtinToolNames: Set<String>, skillUsageAdaptor: SkillUsageAdaptor?, adminSkillIdsBySkillId: Map<String, Long>) : MiddlewareBase`，只覆盖 `onActing`。Task 8 按这个构造器装配。

三条从上游签名来的硬事实，测试与实现都据它们写（2026-10-05 用 `javap -cp agentscope-core-2.0.4.jar` 复核）：
- `ToolResultEndEvent` 只有 `getReplyId/getToolCallId/getToolCallName/getState`，**不带结果正文**。
- 正文在 `ToolResultTextDeltaEvent`，它有 `getToolCallId()`，所以按 id 累积、`TOOL_RESULT_END` 时取走。
- `ToolUseBlock` 有 `getId/getName/getInput(): Map<String, Object>`，`input` 就是分类要看的 `command` / `skillId` / `path` 来源。

- [ ] **Step 1: 写失败测试**

模板取 `ProcessLogMiddlewareTest.kt`（同目录、同 mock 形状：`mock(Agent::class.java)` + `mock(RuntimeContext::class.java)` + `mock(ActingInput::class.java)` + `Function<ActingInput, Flux<AgentEvent>>` + `StepVerifier`）。事件用 mock 而不是真构造器，因为 `ToolResultEndEvent` 的三参重载形参顺序在签名里读不出来。

```kotlin
package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.function.Function

/**
 * One row per call, only on a terminal state, and never at the cost of the turn (design I1/I2, D9).
 *
 * The assertions are about those three promises: a call that ends is filed once, a call that never ends is
 * filed as interrupted rather than lost, and a stream that carries on is unaffected by what the recorder did.
 */
class ToolInvocationMiddlewareTest {

    private val events = mutableListOf<ToolInvocationEvent>()
    private val uses = mutableListOf<Pair<String, List<Long>>>()
    private val agent = mock(Agent::class.java)
    private val ctx = mock(RuntimeContext::class.java)

    private inner class FakeSkillUsage : SkillUsageAdaptor {
        override fun reportViews(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            // Loads are reported by SkillViewRecorder, not by this middleware; nothing to record here.
        }

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            uses += sessionId to skillIds
        }
    }

    private fun middleware(
        mcpIdsByTool: Map<String, Long> = emptyMap(),
        cliIdsByCommand: Map<String, Long> = emptyMap(),
        builtinToolNames: Set<String> = emptySet(),
        adminSkillIdsBySkillId: Map<String, Long> = emptyMap(),
    ) = ToolInvocationMiddleware(
        adaptor = ToolInvocationAdaptor { events.add(it) },
        tenantId = 5L,
        agentId = 7L,
        sessionId = "web-1",
        userId = 9L,
        mcpIdsByTool = mcpIdsByTool,
        cliIdsByCommand = cliIdsByCommand,
        builtinToolNames = builtinToolNames,
        skillUsageAdaptor = FakeSkillUsage(),
        adminSkillIdsBySkillId = adminSkillIdsBySkillId,
    )

    private fun actingInput(vararg calls: ToolUseBlock): ActingInput {
        val input = mock(ActingInput::class.java)
        `when`(input.toolCalls).thenReturn(calls.toList())
        return input
    }

    private fun end(id: String?, name: String, state: ToolResultState): ToolResultEndEvent {
        val event = mock(ToolResultEndEvent::class.java)
        `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_END)
        // An id-less end event is left unstubbed rather than stubbed with null: Mockito already answers
        // null for that getter, and that is what such an event carries.
        if (id != null) `when`(event.toolCallId).thenReturn(id)
        `when`(event.toolCallName).thenReturn(name)
        `when`(event.state).thenReturn(state)
        return event
    }

    private fun delta(id: String, name: String, text: String): ToolResultTextDeltaEvent {
        val event = mock(ToolResultTextDeltaEvent::class.java)
        `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
        `when`(event.toolCallId).thenReturn(id)
        `when`(event.toolCallName).thenReturn(name)
        `when`(event.delta).thenReturn(text)
        return event
    }

    @Nested
    @DisplayName("terminal states")
    inner class TerminalStates {
        @Test
        fun `a successful call files exactly one row with its outcome and duration`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware(builtinToolNames = setOf("now"))

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, event.outcome)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, event.kind)
            assertEquals("now", event.toolName)
            assertEquals(5L, event.tenantId)
            assertEquals(7L, event.agentId)
            assertEquals("web-1", event.sessionId)
            assertEquals(9L, event.userId)
            assertTrue(event.endEpochMilli >= event.startEpochMilli)
        }

        @Test
        fun `every terminal state maps to its own outcome`() {
            val cases = listOf(
                ToolResultState.ERROR to ToolInvocationLog.OUTCOME_ERROR,
                ToolResultState.DENIED to ToolInvocationLog.OUTCOME_DENIED,
                ToolResultState.INTERRUPTED to ToolInvocationLog.OUTCOME_INTERRUPTED,
            )
            cases.forEach { (state, outcome) ->
                events.clear()
                val call = ToolUseBlock("t1", "now", emptyMap())
                val mw = middleware()
                StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", state)) }))
                    .expectNextCount(1)
                    .verifyComplete()
                assertEquals(outcome, events.single().outcome)
            }
        }

        @Test
        fun `a running end event files nothing because the call is still in flight`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.RUNNING)) }))
                .expectNextCount(1)
                .verifyComplete()

            // I2: RUNNING never reaches the table. Nothing is filed here, and the start is kept rather than
            // dropped, so a later terminal event for the same id still gets its row.
            assertTrue(events.none { it.outcome == "RUNNING" })
        }
    }

    @Nested
    @DisplayName("payload")
    inner class Payload {
        @Test
        fun `result text accumulates across deltas and reaches the row`() {
            val call = ToolUseBlock("t1", "read_file", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(call), Function {
                    Flux.just(delta("t1", "read_file", "alpha "), delta("t1", "read_file", "beta"), end("t1", "read_file", ToolResultState.SUCCESS))
                }),
            ).expectNextCount(3).verifyComplete()

            assertEquals("alpha beta", events.single().resultText)
        }

        @Test
        fun `deltas of another call never mix in`() {
            val one = ToolUseBlock("t1", "a", emptyMap())
            val two = ToolUseBlock("t2", "b", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(one, two), Function {
                    Flux.just(delta("t1", "a", "for-one"), delta("t2", "b", "for-two"), end("t1", "a", ToolResultState.SUCCESS), end("t2", "b", ToolResultState.SUCCESS))
                }),
            ).expectNextCount(4).verifyComplete()

            assertEquals(2, events.size)
            assertEquals("for-one", events[0].resultText)
            assertEquals("for-two", events[1].resultText)
        }

        @Test
        fun `tool input is filed as json so a shell command can be read back`() {
            val call = ToolUseBlock("t1", "execute", mapOf("command" to "gh pr view 12"))
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "execute", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(events.single().argsJson!!.contains("gh pr view 12"))
        }

        @Test
        fun `a failure states its reason instead of leaving the row silent`() {
            val call = ToolUseBlock("t1", "send_email", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(call), Function {
                    Flux.just(delta("t1", "send_email", "smtp refused"), end("t1", "send_email", ToolResultState.ERROR))
                }),
            ).expectNextCount(2).verifyComplete()

            assertTrue(events.single().errorMessage!!.contains("smtp refused"))
        }
    }

    @Nested
    @DisplayName("unresolved and mismatched")
    inner class Unresolved {
        @Test
        fun `a stream that completes without an end event files the call as interrupted`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.empty<AgentEvent>() })).verifyComplete()

            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, events.single().outcome)
        }

        @Test
        fun `a stream that fails carries the failure text into the interrupted row`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.error(RuntimeException("sandbox died")) }))
                .expectError(RuntimeException::class.java)
                .verify()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertTrue(event.errorMessage!!.contains("sandbox died"))
        }

        @Test
        fun `a resolved call is not filed twice`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            // A repeated terminal frame for one id is the shape this pins: the accumulator is dropped as the
            // first END is handled, so the second has nothing left to time and files nothing.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS), end("t1", "now", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertEquals(1, events.size)
        }

        @Test
        fun `the name recorded at the start wins when the end event disagrees`() {
            val call = ToolUseBlock("t1", "github_search", emptyMap())
            val mw = middleware(mcpIdsByTool = mapOf("github_search" to 11L))

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "renamed_by_upstream", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertEquals("github_search", events.single().toolName)
            assertEquals(11L, events.single().mcpId)
        }

        @Test
        fun `an end event for an unknown id files nothing`() {
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(), Function { Flux.just(end("t9", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            // Nothing was timed for this id, so a row would carry a duration invented here rather than measured.
            assertTrue(events.isEmpty())
        }

        @Test
        fun `two nameless calls of one name file two rows`() {
            // No id gives nothing to tell the two apart, but the count is still owed to both: the second
            // start must not erase the first accumulator, and the two ENDs are answered in issue order.
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock(null, "now", emptyMap()), ToolUseBlock(null, "now", emptyMap())),
                    Function { Flux.just(end(null, "now", ToolResultState.SUCCESS), end(null, "now", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertEquals(2, events.size)
        }

        @Test
        fun `a nameless start matched by an id-bearing end still files one row`() {
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(ToolUseBlock(null, "now", emptyMap())), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }),
            ).expectNextCount(1).verifyComplete()

            // The keys never match, so only a fallback by name files this call at all.
            assertEquals(1, events.size)
        }

        @Test
        fun `an interrupted call keeps the output it had already streamed`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(call), Function {
                    Flux.just<AgentEvent>(delta("t1", "now", "half an answer")).concatWith(Flux.error(RuntimeException("sandbox died")))
                }),
            ).expectNextCount(1).verifyError()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertEquals("half an answer", event.resultText)
            assertTrue(event.errorMessage!!.contains("sandbox died"))
        }
    }

    @Nested
    @DisplayName("skill use")
    inner class SkillUse {
        private fun loadCall(path: String) = ToolUseBlock(
            "t1",
            ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME,
            mapOf("skillId" to "web-search_custom", "path" to path),
        )

        @Test
        fun `reading the skill body successfully reports one use for the delivered skill`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(loadCall("SKILL.md")), Function {
                    Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                }),
            ).expectNextCount(1).verifyComplete()

            assertEquals(listOf("web-1" to listOf(44L)), uses)
        }

        @Test
        fun `reading a resource file is not a use`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(loadCall("references/api.md")), Function {
                    Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                }),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }

        @Test
        fun `a failed load is not a use`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(loadCall("SKILL.md")), Function {
                    Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.ERROR))
                }),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }

        @Test
        fun `a skill id this run was not delivered asks for nothing`() {
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(loadCall("SKILL.md")), Function {
                    Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                }),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }
    }

    @Nested
    @DisplayName("pass through and safety")
    inner class PassThrough {
        @Test
        fun `every event of the acting stream reaches the caller untouched`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()
            val e1 = delta("t1", "now", "text")
            val e2 = end("t1", "now", ToolResultState.SUCCESS)

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(e1, e2) }))
                .expectNext(e1, e2)
                .verifyComplete()
        }

        @Test
        fun `an adaptor that throws does not fail the turn`() {
            val throwing = ToolInvocationMiddleware(
                adaptor = ToolInvocationAdaptor { throw IllegalStateException("reporter broke") },
                tenantId = null,
                agentId = null,
                sessionId = "web-1",
                userId = null,
                mcpIdsByTool = emptyMap(),
                cliIdsByCommand = emptyMap(),
                builtinToolNames = emptySet(),
                skillUsageAdaptor = null,
                adminSkillIdsBySkillId = emptyMap(),
            )
            val call = ToolUseBlock("t1", "now", emptyMap())

            StepVerifier.create(throwing.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()
        }
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest=ToolInvocationMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t6-red.log 2>&1; echo EXIT=$?
grep -E "unresolved reference|Tests run" /tmp/t6-red.log | head -3
```
预期：编译失败 `unresolved reference: ToolInvocationMiddleware`。

- [ ] **Step 3: 写中间件**

```kotlin
package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.MiddlewareBase
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

/**
 * The one place that sees every tool call this session makes (design section 3).
 *
 * `onActing` is handed the calls the model asked for before any of them run, and it watches the same
 * stream's result events afterwards, so both halves of a call — what was asked and how it ended — are
 * available here with no per-tool cooperation. That is why the previous `tool_call_log`, which recorded
 * from inside a tool base class, held nothing but built-in tools: an MCP tool or a shell command never
 * passed through the class that wrote the row.
 *
 * A fresh instance per assembled agent, for the reason [ProcessLogMiddleware]'s comment records: this holds
 * the run's attribution in fields, and a shared instance lets the last build decide whose rows everybody's
 * calls are attributed to.
 */
class ToolInvocationMiddleware(
    private val adaptor: ToolInvocationAdaptor,
    private val tenantId: Long?,
    private val agentId: Long?,
    private val sessionId: String,
    private val userId: Long?,
    private val mcpIdsByTool: Map<String, Long> = emptyMap(),
    private val cliIdsByCommand: Map<String, Long> = emptyMap(),
    private val builtinToolNames: Set<String> = emptySet(),
    private val skillUsageAdaptor: SkillUsageAdaptor? = null,
    private val adminSkillIdsBySkillId: Map<String, Long> = emptyMap(),
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(ToolInvocationMiddleware::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    /** One call as it started: the name is the authority when the end event's copy disagrees. */
    private class Start(
        val name: String,
        val input: Map<String, Any?>,
        val startMillis: Long,
    )

    override fun onActing(
        agent: Agent,
        ctx: RuntimeContext,
        input: ActingInput,
        next: Function<ActingInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        val started = ConcurrentHashMap<String, Start>()
        val results = ConcurrentHashMap<String, StringBuffer>()
        val openByName = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()
        val failure = AtomicReference<Throwable?>()
        var suffix = 0
        input.toolCalls.forEach { call ->
            // One turn can ask twice for the same tool, and `ToolUseBlock.id` is nullable upstream, so two
            // calls can compute the same key. A plain put would drop the first accumulator and that call
            // would never be counted, so a colliding key gets a private suffix and every open key stays
            // reachable by name in issue order. That queue is also how an end event finds its start when
            // the id is present on one side only.
            val base = key(call.id, call.name)
            var k = base
            while (started.putIfAbsent(k, Start(call.name, call.input ?: emptyMap(), System.currentTimeMillis())) != null) {
                k = "$base#${++suffix}"
            }
            openByName.computeIfAbsent(call.name) { ConcurrentLinkedDeque() }.addLast(k)
        }
        return next.apply(input)
            .doOnNext { event -> onEvent(event, started, results, openByName) }
            .doOnError { error -> failure.set(error) }
            .doFinally { emitUnresolved(started, results, openByName, failure.get()) }
    }

    /**
     * The accumulator key: the call's id when the runtime gave one, otherwise its name. The name fallback
     * is unique only while a single call of that name is open, which is why `onActing` suffixes a collision
     * and `matchKey` falls back to issue order.
     */
    private fun key(
        id: String?,
        name: String?,
    ): String = id ?: name ?: ""

    private fun onEvent(
        event: AgentEvent,
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ) {
        runCatching {
            when (event.type) {
                AgentEventType.TOOL_RESULT_TEXT_DELTA -> {
                    val delta = event as ToolResultTextDeltaEvent
                    val text = delta.delta
                    if (!text.isNullOrEmpty()) results.computeIfAbsent(key(delta.toolCallId, delta.toolCallName)) { StringBuffer() }.append(text)
                }

                AgentEventType.TOOL_RESULT_END -> resolve(event as ToolResultEndEvent, started, results, openByName)
                else -> {}
            }
        }.exceptionOrNull()?.let {
            log.warn("Tool invocation recording skipped for session {}: {}", sessionId, it.message)
        }
    }

    private fun resolve(
        end: ToolResultEndEvent,
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ) {
        val key = key(end.toolCallId, end.toolCallName)
        val startKey = matchKey(key, end.toolCallName, started, openByName) ?: return
        val start = started[startKey] ?: return
        val outcome = when (end.state) {
            ToolResultState.SUCCESS -> ToolInvocationLog.OUTCOME_SUCCESS
            ToolResultState.ERROR -> ToolInvocationLog.OUTCOME_ERROR
            ToolResultState.DENIED -> ToolInvocationLog.OUTCOME_DENIED
            ToolResultState.INTERRUPTED -> ToolInvocationLog.OUTCOME_INTERRUPTED
            // Still in flight (an async tool's first end event): keep the start so the terminal event that
            // follows can still time it.
            else -> return
        }
        started.remove(startKey)
        // Deltas of a call that never carried an id accumulate under its name, so two same-name calls that
        // both lack an id share one buffer. Their output is not separable upstream; the row count still is.
        val resultText = results.remove(startKey)?.toString()
        if (end.toolCallName != null && end.toolCallName != start.name) {
            log.warn(
                "Tool call {} in session {} ended under name '{}' but was recorded as '{}'",
                startKey,
                sessionId,
                end.toolCallName,
                start.name,
            )
        }
        emit(start, start.name, outcome, resultText, failureText(outcome, resultText), System.currentTimeMillis())
        if (outcome == ToolInvocationLog.OUTCOME_SUCCESS) reportSkillUse(start)
    }

    /**
     * Which accumulator this end event owns: the exact key when it is still open, otherwise the oldest call
     * still open under this tool name. A model gets its own calls answered in the order it asked for them,
     * so FIFO is the only defensible guess, and a wrong guess costs a duration measured against the wrong
     * start rather than a lost row.
     */
    private fun matchKey(
        key: String,
        name: String?,
        started: ConcurrentHashMap<String, Start>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
    ): String? {
        val open = name?.let { openByName[it] }
        val matched = if (started.containsKey(key)) key else open?.pollFirst() ?: return null
        open?.remove(matched)
        return matched
    }

    /** Non-success rows carry a reason: the tool's own output is the reason when it produced one. */
    private fun failureText(
        outcome: String,
        resultText: String?,
    ): String? = if (outcome == ToolInvocationLog.OUTCOME_SUCCESS) null else resultText?.takeIf { it.isNotBlank() } ?: outcome

    private fun emitUnresolved(
        started: ConcurrentHashMap<String, Start>,
        results: ConcurrentHashMap<String, StringBuffer>,
        openByName: ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>,
        failure: Throwable?,
    ) {
        started.keys.toList().forEach { key ->
            val start = started.remove(key) ?: return@forEach
            openByName[start.name]?.remove(key)
            // Whatever the tool streamed before the stream died is the most readable part of the row, so it
            // is filed rather than dropped: the reason goes to `errorMessage`, the partial output to
            // `resultText`, and the writer truncates it like any other.
            val text = failure?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" } ?: "stream ended before the tool returned"
            emit(start, start.name, ToolInvocationLog.OUTCOME_INTERRUPTED, results.remove(key)?.toString(), text, System.currentTimeMillis())
        }
    }

    private fun emit(
        start: Start,
        name: String,
        outcome: String,
        resultText: String?,
        errorMessage: String?,
        endMillis: Long,
    ) {
        runCatching {
            val attribution = ToolInvocationClassifier.classify(name, start.input, mcpIdsByTool, cliIdsByCommand, builtinToolNames)
            adaptor.emit(
                ToolInvocationEvent(
                    tenantId = tenantId,
                    agentId = agentId,
                    sessionId = sessionId,
                    userId = userId,
                    kind = attribution.kind,
                    toolName = attribution.toolName,
                    mcpId = attribution.mcpId,
                    cliId = attribution.cliId,
                    outcome = outcome,
                    argsJson = jsonOf(start.input),
                    resultText = resultText,
                    errorMessage = errorMessage,
                    startEpochMilli = start.startMillis,
                    endEpochMilli = endMillis,
                ),
            )
        }.exceptionOrNull()?.let {
            // The reporter's own contract is that it does not throw; this covers everything else, including
            // an adaptor implemented by somebody else's code. A lost row is worth less than a lost answer.
            log.warn("Tool invocation event for '{}' in session {} was not filed: {}", name, sessionId, it.message)
        }
    }

    private fun jsonOf(input: Map<String, Any?>): String? = runCatching { objectMapper.writeValueAsString(input) }.getOrNull()

    /**
     * The model worked through this skill's instructions: it asked the loader for the skill body and got it.
     *
     * `SKILL.md` is what carries the instructions, so a skill whose resource file was read is not yet used,
     * and a load that failed told the model nothing. No cooldown: one load is one use.
     */
    private fun reportSkillUse(start: Start) {
        if (start.name != ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME) return
        val skillId = start.input[PARAM_SKILL_ID] as? String ?: return
        val path = start.input[PARAM_PATH] as? String ?: return
        if (path != SKILL_BODY) return
        val adminSkillId = adminSkillIdsBySkillId[skillId] ?: return
        runCatching { skillUsageAdaptor?.reportUses(sessionId, listOf(adminSkillId), userId) }
            .exceptionOrNull()
            ?.let { log.warn("Skill use report for skill {} in session {} was not filed: {}", skillId, sessionId, it.message) }
    }

    companion object {
        private const val PARAM_SKILL_ID = "skillId"
        private const val PARAM_PATH = "path"
        private const val SKILL_BODY = "SKILL.md"
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
$MVN -q spotless:apply -pl harnax-agent/harnax-harness-core
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest=ToolInvocationMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t6.log 2>&1; echo EXIT=$?
grep -E "ToolInvocationMiddlewareTest|BUILD" /tmp/t6.log | tail -6
```
预期：`ToolInvocationMiddlewareTest` 那一行 `Tests run: 21, Failures: 0, Errors: 0`。以该步文件里的 `@Test` 数为准，改了用例就同时改这个数；`-am` 会带上游模块进 reactor，它们在 `-Dtest` 点名下跑 0 个，别把 reactor 汇总行当成本任务的数。若 `an adaptor that throws does not fail the turn` 红了，说明 `runCatching` 没盖住投递点——不许改成「让适配器自己吞」，中间件这一层的契约就是不把任何记录故障带上流。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddleware.kt \
        harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/agent/provider/middleware/ToolInvocationMiddlewareTest.kt
git commit -m "feat(metrics): 工具调用唯一事件源的 acting 中间件"
```

---

### Task 7: agent-service 的写入实现——队列、批量、截断

**Files:**
- Create: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt`
- Test: `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImplTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `ToolInvocationAdaptor` / `ToolInvocationEvent`、Task 1 的 `ToolInvocationLog`（`var` 属性 + 无参构造）与 `ToolInvocationLogMapper.batchInsert(List<ToolInvocationLog>): Int`。agent-service 已依赖 `harnax-entity`（被删的旧 `ToolCallLogAdaptorImpl` 就是这么直接写库的），不需要绕 admin 的 HTTP。
- Produces: `@Component class ToolInvocationAdaptorImpl : ToolInvocationAdaptor`，带 `internal fun drainAndFlush(): Int`（测试与工作线程共用的落库缝）、`internal val droppedCount: Long`、`@PostConstruct fun startWriter()`、`@PreDestroy fun shutdown()`。Task 8 靠 Spring 按类型找到这个 bean。

两个实现约束值得先说明，因为它们决定了下面的形状：
- **线程不在构造器里起**，而在 `@PostConstruct` 里起。单测直接 `new` 这个类、只调 `drainAndFlush()`，于是要验的行为（批量、截断、开关、溢出）全部与线程时序无关；`shutdown()` 自己再把队列排空，因此「停机不丢事件」也不靠等线程。
- **`error_message` 按列宽截到 500 字符，而不是按 `capture-max-chars`**。`capture-max-chars` 管的是 `args_json` 与 `result_excerpt` 两列（都是 `text`）；`error_message` 是 `varchar(512)`，一行超长会让整批 `INSERT` 失败，连带丢掉同批里本来能记下的调用。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

/**
 * The writer's own promises: never block the caller, never throw at the caller, never send an empty
 * statement, and never let a long payload break the batch it travelled in.
 *
 * Rows are asserted field by field rather than by count, because a batch that writes the wrong tenant onto
 * a row passes a count-shaped test and then shows somebody else's calls on their own page.
 */
class ToolInvocationAdaptorImplTest {

    private val mapper = mock(ToolInvocationLogMapper::class.java)

    private fun adaptor(
        queueCapacity: Int = 512,
        batchSize: Int = 64,
        capturePayload: Boolean = true,
        captureMaxChars: Int = 2000,
    ) = ToolInvocationAdaptorImpl(
        toolInvocationLogMapper = mapper,
        queueCapacity = queueCapacity,
        batchSize = batchSize,
        flushIntervalMs = 200L,
        capturePayload = capturePayload,
        captureMaxChars = captureMaxChars,
    )

    private fun event(
        kind: String = ToolInvocationLog.KIND_BUILTIN,
        toolName: String = "send_email",
        argsJson: String? = "{\"to\":\"a@b.c\"}",
        resultText: String? = "sent",
        errorMessage: String? = null,
    ) = ToolInvocationEvent(
        tenantId = 5L,
        agentId = 7L,
        sessionId = "web-1",
        userId = 9L,
        kind = kind,
        toolName = toolName,
        outcome = if (errorMessage == null) ToolInvocationLog.OUTCOME_SUCCESS else ToolInvocationLog.OUTCOME_ERROR,
        argsJson = argsJson,
        resultText = resultText,
        errorMessage = errorMessage,
        startEpochMilli = 1_700_000_000_000L,
        endEpochMilli = 1_700_000_000_250L,
    )

    private fun capturedRows(): List<ToolInvocationLog> {
        val captor = ArgumentCaptor.forClass(List::class.java)
        verify(mapper).batchInsert(captor.capture())
        @Suppress("UNCHECKED_CAST")
        return captor.value as List<ToolInvocationLog>
    }

    /** A column back to the event's own millis, so an assertion on `ts` checks the instant and not a copy of it. */
    private fun millisOf(time: LocalDateTime?): Long = requireNotNull(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Nested
    @DisplayName("row shape")
    inner class RowShape {
        @Test
        fun `an event becomes one row carrying its attribution and measured duration`() {
            val writer = adaptor()
            writer.emit(event())

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()
            assertEquals(5L, row.tenantId)
            assertEquals(7L, row.agentId)
            assertEquals("web-1", row.sessionId)
            assertEquals(9L, row.userId)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, row.kind)
            assertEquals("send_email", row.toolName)
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, row.outcome)
            assertEquals(250L, row.durationMs)
            assertEquals("{\"to\":\"a@b.c\"}", row.argsJson)
            assertEquals("sent", row.resultExcerpt)
            // ts is what the indexes and the rollup key on: the call's own end instant at millisecond
            // precision, not the writer's clock and not a truncated second.
            assertEquals(1_700_000_000_250L, millisOf(row.ts))
            assertEquals(millisOf(row.ts), millisOf(row.endTime))
            assertEquals(1_700_000_000_000L, millisOf(row.startTime))
        }

        @Test
        fun `a negative duration from a clock that went backwards is filed as zero`() {
            val writer = adaptor()
            writer.emit(ToolInvocationEvent(5L, 7L, "web-1", 9L, ToolInvocationLog.KIND_BUILTIN, "now", outcome = ToolInvocationLog.OUTCOME_SUCCESS, argsJson = null, resultText = null, errorMessage = null, startEpochMilli = 200L, endEpochMilli = 100L))

            writer.drainAndFlush()

            assertEquals(0L, capturedRows().single().durationMs)
        }
    }

    @Nested
    @DisplayName("payload switches")
    inner class Payload {
        @Test
        fun `capture-payload off leaves both body columns null`() {
            val writer = adaptor(capturePayload = false)
            writer.emit(event())

            assertEquals(1, writer.drainAndFlush())
            val row = capturedRows().single()
            assertNull(row.argsJson)
            assertNull(row.resultExcerpt)
            // The measurement is not a payload: what is counted has to survive turning bodies off.
            assertEquals(250L, row.durationMs)
        }

        @Test
        fun `a body over the limit is cut with a marker instead of vanishing`() {
            val writer = adaptor(captureMaxChars = 10)
            writer.emit(event(argsJson = "x".repeat(50), resultText = "y".repeat(50)))

            writer.drainAndFlush()
            val row = capturedRows().single()

            assertTrue(row.argsJson!!.startsWith("xxxxxxxxxx"))
            assertTrue(row.argsJson!!.endsWith("(truncated)"))
            assertEquals(10 + "…(truncated)".length, row.argsJson!!.length)
            assertTrue(row.resultExcerpt!!.endsWith("(truncated)"))
        }

        @Test
        fun `a failure reason is cut to the column width whatever the payload limit says`() {
            val writer = adaptor(captureMaxChars = 2000)
            writer.emit(event(errorMessage = "boom ".repeat(400)))

            writer.drainAndFlush()

            // error_message is varchar(512): letting the payload limit decide it would fail the whole insert.
            assertTrue(capturedRows().single().errorMessage!!.length <= 512)
        }
    }

    @Nested
    @DisplayName("batching and overflow")
    inner class Batching {
        @Test
        fun `an empty queue sends no statement at all`() {
            val writer = adaptor()

            assertEquals(0, writer.drainAndFlush())

            // foreach over an empty list is invalid SQL; the guard is here rather than in the XML.
            verifyNoInteractions(mapper)
        }

        @Test
        fun `a batch of N rows goes out in one statement`() {
            val writer = adaptor(batchSize = 8)
            repeat(5) { writer.emit(event(toolName = "tool-$it")) }

            assertEquals(5, writer.drainAndFlush())

            val rows = capturedRows()
            assertEquals(5, rows.size)
            assertEquals(listOf("tool-0", "tool-1", "tool-2", "tool-3", "tool-4"), rows.map { it.toolName })
        }

        @Test
        fun `a drain stops at the batch size and leaves the rest queued`() {
            val writer = adaptor(batchSize = 2)
            repeat(5) { writer.emit(event()) }

            assertEquals(2, writer.drainAndFlush())
            assertEquals(2, writer.drainAndFlush())
            assertEquals(1, writer.drainAndFlush())
            assertEquals(0, writer.drainAndFlush())
        }

        @Test
        fun `a full queue drops the newest event and counts it instead of blocking`() {
            val writer = adaptor(queueCapacity = 1)
            writer.emit(event(toolName = "first"))

            assertDoesNotThrow {
                writer.emit(event(toolName = "second"))
                writer.emit(event(toolName = "third"))
            }

            assertTrue(writer.droppedCount >= 2)
            assertEquals(1, writer.drainAndFlush())
            assertEquals("first", capturedRows().single().toolName)
        }

        @Test
        fun `what was queued before shutdown is still written`() {
            val writer = adaptor()
            writer.emit(event())
            writer.emit(event())

            writer.shutdown()

            // The worker is not running in a unit test, so this asserts the drain in shutdown() itself.
            assertEquals(2, capturedRows().size)
        }

        @Test
        fun `a mapper that throws is not carried up to the caller`() {
            val failing = mock(ToolInvocationLogMapper::class.java)
            `when`(failing.batchInsert(anyList())).thenThrow(IllegalStateException("db down"))
            val writer =
                ToolInvocationAdaptorImpl(
                    toolInvocationLogMapper = failing,
                    queueCapacity = 512,
                    batchSize = 64,
                    flushIntervalMs = 200L,
                    capturePayload = true,
                    captureMaxChars = 2000,
                )
            writer.emit(event())

            assertDoesNotThrow { writer.drainAndFlush() }
        }
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
$MVN -o -am test -pl harnax-agent/harnax-agent-service -Dtest=ToolInvocationAdaptorImplTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t7-red.log 2>&1; echo EXIT=$?
grep -E "unresolved reference|Tests run" /tmp/t7-red.log | head -3
```
预期：`unresolved reference: ToolInvocationAdaptorImpl`。

- [ ] **Step 3: 写实现**

```kotlin
package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes tool invocation events to `tool_invocation_log` on a thread that is not the model's.
 *
 * The event source sits inside a streaming turn, so this half of the chain owns both promises the contract
 * makes: `emit` returns as soon as the event is queued, and a database that is slow, down, or rejecting a
 * row never reaches the answer being streamed. Rows are written in batches because a tool call is a small
 * event at a high rate, and one `INSERT` per call would put its cost on every call.
 *
 * When the queue is full the newest event is dropped and counted rather than the backlog grown. A full
 * queue means hundreds of calls per second; a missing counter is the cheaper failure, and the bound is what
 * keeps memory flat when the database stays unreachable.
 */
@Component
class ToolInvocationAdaptorImpl(
    private val toolInvocationLogMapper: ToolInvocationLogMapper,
    @Value("\${harness.metrics.invocation.queue-capacity:512}") queueCapacity: Int,
    @Value("\${harness.metrics.invocation.batch-size:64}") private val batchSize: Int,
    @Value("\${harness.metrics.invocation.flush-interval-ms:200}") private val flushIntervalMs: Long,
    @Value("\${harness.metrics.invocation.capture-payload:true}") private val capturePayload: Boolean,
    @Value("\${harness.metrics.invocation.capture-max-chars:2000}") private val captureMaxChars: Int,
) : ToolInvocationAdaptor {

    private val log = LoggerFactory.getLogger(ToolInvocationAdaptorImpl::class.java)
    private val queue = ArrayBlockingQueue<ToolInvocationEvent>(queueCapacity)

    /** Counted rather than silently discarded: "the page is empty" needs a number proving the queue overflowed. */
    private val dropped = AtomicLong()
    internal val droppedCount: Long get() = dropped.get()

    @Volatile
    private var running = true
    private var writer: Thread? = null

    override fun emit(event: ToolInvocationEvent) {
        if (queue.offer(event)) return
        val total = dropped.incrementAndGet()
        if (total == 1L || total % DROP_LOG_EVERY == 0L) {
            log.warn("Tool invocation event for '{}' in session {} dropped ({} dropped so far)", event.toolName, event.sessionId, total)
        }
    }

    /**
     * Started by the container, not by the constructor: the writing behaviour is asserted without any test
     * having to race a thread for it.
     */
    @PostConstruct
    fun startWriter() {
        writer = Thread(::pump, "tool-invocation-writer").apply { isDaemon = true }
        writer?.start()
    }

    private fun pump() {
        val batch = ArrayList<ToolInvocationEvent>(batchSize)
        while (running) {
            try {
                // The timeout is what commits a partial batch: a session that made three calls and went quiet
                // must not leave them queued until the next one arrives.
                val first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (first != null) {
                    batch += first
                    queue.drainTo(batch, batchSize - batch.size)
                }
                if (batch.isNotEmpty() && (first == null || batch.size >= batchSize)) {
                    write(batch)
                    batch.clear()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                // The batch is dropped, not retried: a writer that retried the same broken statement would
                // spin against a database that is down, and the events behind it are counters.
                log.warn("Tool invocation batch of {} row(s) was not written: {}", batch.size, e.message)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) write(batch)
    }

    /**
     * Take up to a batch out of the queue and write it; the seam the worker loop, `shutdown()` and the tests
     * all use. The return value is the number of events taken, so a caller can loop until it reaches zero —
     * it is not a count of rows the database accepted, which the caller cannot act on either way.
     */
    internal fun drainAndFlush(): Int {
        val batch = ArrayList<ToolInvocationEvent>(batchSize)
        queue.drainTo(batch, batchSize)
        if (batch.isEmpty()) return 0
        return try {
            write(batch)
        } catch (e: Exception) {
            log.warn("Tool invocation batch of {} row(s) was not written: {}", batch.size, e.message)
            batch.size
        }
    }

    private fun write(batch: List<ToolInvocationEvent>): Int {
        if (batch.isEmpty()) return 0
        val rows = batch.map { toRow(it) }
        toolInvocationLogMapper.batchInsert(rows)
        return rows.size
    }

    private fun toRow(event: ToolInvocationEvent): ToolInvocationLog {
        val end = ofEpoch(event.endEpochMilli)
        return ToolInvocationLog().apply {
            tenantId = event.tenantId
            agentId = event.agentId
            sessionId = event.sessionId
            userId = event.userId
            kind = event.kind
            toolName = event.toolName
            mcpId = event.mcpId
            cliId = event.cliId
            outcome = event.outcome
            errorMessage = truncate(event.errorMessage, MAX_ERROR_CHARS)
            argsJson = if (capturePayload) truncate(event.argsJson, captureMaxChars) else null
            resultExcerpt = if (capturePayload) truncate(event.resultText, captureMaxChars) else null
            durationMs = (event.endEpochMilli - event.startEpochMilli).coerceAtLeast(0L)
            startTime = ofEpoch(event.startEpochMilli)
            endTime = end
            ts = end
        }
    }

    private fun ofEpoch(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    /** Cut to [max] with a marker, so a reader can tell a long body from a short one. */
    private fun truncate(
        text: String?,
        max: Int,
    ): String? {
        if (text == null || text.length <= max) return text
        return text.take(max) + TRUNCATION_SUFFIX
    }

    @PreDestroy
    fun shutdown() {
        running = false
        writer?.interrupt()
        // Whatever is queued is counters, not state — but a graceful stop still owes them a write, and it is
        // done here rather than awaited on the worker so the promise holds without a timing assumption.
        while (drainAndFlush() > 0) {
            // Bounded below by the queue capacity; break out if the writer is falling behind.
        }
    }

    companion object {
        private const val DROP_LOG_EVERY = 50L
        private const val TRUNCATION_SUFFIX = "…(truncated)"

        /** `error_message` is varchar(512); this keeps the marker inside the column whatever the payload limit says. */
        private const val MAX_ERROR_CHARS = 500
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
$MVN -q spotless:apply -pl harnax-agent/harnax-agent-service
$MVN -o -am test -pl harnax-agent/harnax-agent-service -Dtest=ToolInvocationAdaptorImplTest -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t7.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t7.log | tail -3
```
预期：`Tests run: 11, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交**

```bash
git add harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImpl.kt \
        harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolInvocationAdaptorImplTest.kt
git commit -m "feat(metrics): 工具调用事件的批量写入适配器"
```

---

### Task 8: 装配接线——三张映射与中间件挂载

**Files:**
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt`（在 `:293` 的 `getTool` 之后加一个只读枚举）
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`（构造器参数在 `:136` 的 `skillUsageAdaptor` 之后、MCP 起于 `:264` 的 `mcpClients` 而终于 `:356` 的聚合告警、技能循环 `:481-506`、挂载点 `:601` 之后、`initLauncher` 的形参 `:1166` 与透传 `:1241`）
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt`（`fun harnessAgentLauncher` 在 `:313`，`ObjectProvider` 先例 `:331`，透传 `:365`，闭括号 `:370`）
- Modify: `harnax-agent/harnax-agent-service/src/main/resources/application.yml`（`harness:` 块内，与 `harness.memory:` 同级）

**Interfaces:**
- Consumes: Task 6 的中间件构造器、Task 3 的 `commandHeads`、Task 7 的 bean（由 Spring 按 `ToolInvocationAdaptor` 类型找到）、`McpTool.getClientName()`、`AgentSkill.getSkillId()`（2.0.4 里这两个访问器都在，`javap` 已核）。
- Produces: `HarnessAgentLauncher.toolInvocationAdaptor: ToolInvocationAdaptor? = null` 一个构造器参数（**开关只有一个落点**：`enabled` 在 bean 装配处把 provider 变成 null，launcher 不再持第二个布尔，否则两处判断互为影子、单行变异谁都杀不死）；`HarnessAgentBuilder.mcpToolClientNames(): Map<String, String>`。Task 9 删除旧链时不再回来改这里。
- 需要的 import（`HarnessAgentLauncher.kt` 现在三个都没有）：`com.agnetix.harnax.agent.provider.middleware.ToolInvocationMiddleware`、`com.agnetix.harnax.agent.provider.middleware.ToolInvocationClassifier`（在 harness-core 自己里面，不在 tools-sdk）、`com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor`。

- [ ] **Step 1: builder 的只读枚举**

`HarnessAgentBuilder.kt`：在 `fun getTool(...)`（`:293`）之后插入，并补 import `io.agentscope.core.tool.mcp.McpTool`（该文件已 import `io.agentscope.core.tool.mcp.McpClientWrapper`，同族）：

```kotlin
    /**
     * Toolkit tools that came from an MCP server, mapped to the client that registered them.
     *
     * Read-only, and asked for once after every `addMcp`: the caller has the server rows and needs the tool
     * names, while only the live registry knows which server a name belongs to. `addMcp` registers
     * synchronously, so what it installed is visible here. Harness' own built-ins (`execute`, `read_file`,
     * `memory_*`) are attached later, at `build()`, so they are absent — which is exactly right, since none
     * of them belongs to an MCP server.
     */
    fun mcpToolClientNames(): Map<String, String> =
        toolkit.getToolNames().mapNotNull { name ->
            (toolkit.getTool(name) as? McpTool)?.clientName?.let { name to it }
        }.toMap()
```

- [ ] **Step 2: launcher 的三张映射**

MCP 循环（`:264` 起）之前声明收集表，循环内 `agentBuilder.addMcp(created)`（`:333`）之后登记，循环结束后（`:356` 的聚合告警之后）折算：

```kotlin
        // The runtime needs this for attribution, not for loading: a call of an MCP tool is filed against the
        // server that exposed it, and the tool name alone does not say which one it was. Keyed by the client
        // wrapper's name, which `McpHelper` builds from `mcpConfig.name`.
        val mcpIdByClientName = mutableMapOf<String, Long>()
        ...
                agentBuilder.addMcp(created)
                mcpClients += created
                mcpIdByClientName[mcpConfig.name] = mcpSpec.mcpId
        ...
        val mcpIdsByTool: Map<String, Long> = if (mcpIdByClientName.isEmpty()) {
            emptyMap()
        } else {
            agentBuilder.mcpToolClientNames().mapNotNull { (toolName, clientName) ->
                mcpIdByClientName[clientName]?.let { toolName to it }
            }.toMap()
        }
        if (mcpClients.isNotEmpty() && mcpIdsByTool.isEmpty()) {
            log.warn(
                "Agent '{}' has {} MCP client(s) but the registry named none of their tools: their calls will be " +
                    "filed as framework rather than as mcp",
                agentSpec.name,
                mcpClients.size,
            )
        }
```

CLI 别名表（放在挂载点之前即可，`agentSpec.cliSpecs` 此刻已在手）：

```kotlin
        // A delivered CLI package is not a tool: the model reaches it through the shell. Attribution therefore
        // reads the command name off the command string, and the candidate names are the package name plus the
        // first word of each segment of its own check command. A binary inside the package whose name matches
        // neither is filed as `shell` (design section 12).
        val cliIdsByCommand: Map<String, Long> = agentSpec.cliSpecs.flatMap { spec ->
            (setOf(spec.name) + ToolInvocationClassifier.commandHeads(spec.checkCommand).map { it.substringAfterLast('/') })
                .filter { it.isNotBlank() }
                .map { it to spec.cliId }
        }.toMap()
```

技能映射：在技能循环（`:481`）之前声明 `val adminSkillIdsBySkillId = mutableMapOf<String, Long>()`，循环内 `skillViewRecorder?.attribute(skill.name, it.skillId)`（`:490`）之后加一行：

```kotlin
                // The loader is handed `AgentSkill.skillId` (`name_source`, upstream-derived) and not the Admin row
                // id, so the use event can only be attributed back through a map built here.
                adminSkillIdsBySkillId[skill.skillId] = it.skillId
```

- [ ] **Step 3: 挂载**

`ProcessLogMiddleware` 挂载之后（`:601` 之后）插入：

```kotlin
        // ----- Tool invocation metrics -----
        // Third fresh instance per build for the same reason as the two above: the run's attribution lives in
        // its fields. One guard only: `enabled=false` has already turned the adaptor into null where the bean
        // is wired (Step 4), so absence here means "nothing to write to" and the recording path is exactly as
        // it was, rather than a per-turn cost for a counter with nowhere to go.
        if (toolInvocationAdaptor != null) {
            agentBuilder.addMiddleware(
                ToolInvocationMiddleware(
                    adaptor = toolInvocationAdaptor,
                    tenantId = agentSpec.tenantId,
                    agentId = agentSpec.attributableAgentId,
                    sessionId = sessionId,
                    userId = userIdentifier.userId,
                    mcpIdsByTool = mcpIdsByTool,
                    cliIdsByCommand = cliIdsByCommand,
                    builtinToolNames = agentSpec.toolSpecs.map { it.toolName }.toSet(),
                    skillUsageAdaptor = skillUsageAdaptor,
                    adminSkillIdsBySkillId = adminSkillIdsBySkillId,
                ),
            )
        }
```

构造器参数（`:136`，紧跟 `skillUsageAdaptor: SkillUsageAdaptor? = null,`）与 `initLauncher` 的形参（`:1166`，同一句位置关系）与透传（`:1241` 的 `skillUsageAdaptor = skillUsageAdaptor,` 之后）各加**一条**：

```kotlin
    val toolInvocationAdaptor: ToolInvocationAdaptor? = null,
```

- [ ] **Step 4: bean 与开关**

`HarnessAutoConfiguration.kt` 的 `harnessAgentLauncher` 形参表末尾（`:332` 之后）加两条，并在 `initLauncher(...)` 调用里透传：

```kotlin
        toolInvocationAdaptorProvider: ObjectProvider<ToolInvocationAdaptor>,
        @Value("\${harness.metrics.invocation.enabled:true}") invocationMetricsEnabled: Boolean,
```
```kotlin
            // Absent means no tool call is filed: a runtime with nowhere to write must not pay for a recorder.
            toolInvocationAdaptor = toolInvocationAdaptorProvider.ifAvailable?.takeIf { invocationMetricsEnabled },
```
补 import `com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor`。`enabled=false` 时 `takeIf` 把适配器变成 null，于是 Step 3 的挂载条件自己就不成立——**开关只有这一个落点**，launcher 不持第二个布尔。

`harnax-agent-service/src/main/resources/application.yml` 在 `harness:` 块内、与 `memory:` 同级插入：

```yaml
  # One row per tool call in `tool_invocation_log`, filed by the acting middleware. `enabled=false` installs
  # no middleware at all. Bodies (args and result) are optional: they can carry literal secrets, so a
  # deployment that only wants counts and durations turns `capture-payload` off and keeps every measure.
  metrics:
    invocation:
      enabled: ${HARNESS_METRICS_INVOCATION_ENABLED:true}
      queue-capacity: ${HARNESS_METRICS_INVOCATION_QUEUE_CAPACITY:512}
      batch-size: ${HARNESS_METRICS_INVOCATION_BATCH_SIZE:64}
      flush-interval-ms: ${HARNESS_METRICS_INVOCATION_FLUSH_INTERVAL_MS:200}
      capture-payload: ${HARNESS_METRICS_INVOCATION_CAPTURE_PAYLOAD:true}
      capture-max-chars: ${HARNESS_METRICS_INVOCATION_CAPTURE_MAX_CHARS:2000}
```

- [ ] **Step 5: 编译与既有测试不破**

```bash
$MVN -q spotless:apply -pl harnax-agent/harnax-harness-core,harnax-agent/harnax-agent-service
$MVN -o -am -q test-compile -pl harnax-agent/harnax-harness-core > /tmp/t8-compile.log 2>&1; echo EXIT=$?
$MVN -o -am test -pl harnax-agent/harnax-harness-core -Dtest='HarnessAgentLauncher*Test,ToolInvocation*Test' -Dsurefire.failIfNoSpecifiedTests=false > /tmp/t8.log 2>&1; echo EXIT=$?
grep -E "Tests run:.*Failures|BUILD" /tmp/t8.log | tail -3
```
预期：`EXIT=0` 且 `Tests run:` 非零。新增的两个具名参数都带默认值，所以 14 个 harness-core 夹具**不需要**在这一跳改——它们用具名实参调 `initLauncher`，多出来的参数取默认。若这里红了，是默认值没给或具名实参写错，不是夹具的问题。

- [ ] **Step 6: 提交**

```bash
git add harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentBuilder.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt \
        harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt \
        harnax-agent/harnax-agent-service/src/main/resources/application.yml
git commit -m "feat(metrics): 装配期三张映射与调用指标中间件挂载"
```

---

### Task 9: `tool_call_log` 整链下线

**Files:**
- Modify: `harnax-admin/src/main/resources/db/migration/V1__init_schema.sql:759`（删掉 `tool_call_log` 建表块）
- Modify: `harnax-entity/src/test/resources/schema-test.sql:743`（同一建表块）与 `:870`（同一份种子 INSERT）
- Delete: `harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt`
- Delete: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapper.kt`
- Delete: `harnax-entity/src/main/resources/mapper/ToolCallLogMapper.xml`
- Delete: `harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapperTest.kt`
- Delete: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolCallLogAdaptor.kt`
- Delete: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt`
- Delete: `harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImplTest.kt`
- Modify: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolBox.kt`（整文件替换）
- Modify: `harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContext.kt:11`（删 `SessionMetaContext`）
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentLauncher.kt`（`:52`、`:55`、`:102`、`:117`、`:386-399`、`:553-567`、`:1155`）
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/spring/HarnessAutoConfiguration.kt`（`:27`、`:320`、`:334-335`、`:350`）
- Modify: `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/LauncherBean.kt:46,62`（注释块里的两行）
- Modify: `harnax-tools-external/harnax-tools-buildin/src/main/kotlin/com/agnetix/harnax/tools/buildin/EmailToolBox.kt:64`、`TimeToolBox.kt:23,27`
- Modify: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxes.kt:26,43,58,90,104,114`
- Delete: `harnax-agent/harnax-tools-sdk/src/test/kotlin/com/agnetix/harnax/tools/sdk/ToolBoxTest.kt`（整文件，理由见 Step 8）
- Modify: `harnax-agent/harnax-tools-sdk/src/test/kotlin/com/agnetix/harnax/tools/sdk/ToolCallContextTest.kt:20-65`
- Modify: `harnax-tools-external/harnax-tools-buildin/src/test/kotlin/com/agnetix/harnax/tools/buildin/TimeToolBoxTest.kt:32,39-43,71-85,104-115`、`EmailToolBoxTest.kt:40,53-58,105-133,321-350`、`EmailToolBoxIntegrationTest.kt:32,59-64`
- Modify: `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/team/TeamToolBoxesTest.kt:29-41,109-116`
- Modify: 14 个 harness-core 夹具（清单见 Step 9）

**Interfaces:**
- Consumes: Task 6/7/8 已经接管工具调用的记录，本任务删的是**旧链**，不是唯一记录源。
- Produces: `ToolBox` 只剩 `abstract fun name(): String`——**没有 `execute`，也没有 `init`**：`userIdentifier()` 在 main 里除自身声明外零调用方（现核 `ToolBox.kt:41` 加一份待删的 `ToolBoxTest.kt`），所以三个 `@Volatile` 字段与 `init` 一起消失而不是瘦身为 `init(userIdentifier)`（规格 §10 已裁）。子类直接返回自己的结果。`SessionMetaContext`、`ToolCallLogAdaptor`、`ToolCallInfo` 不再存在，后续任何任务引用它们都是编译错误。

这条链的读侧是干净的：`ToolCallLogMapper` 只有一个 `insert`，全仓没有一处 select（`harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapper.kt:7-13` 的 KDoc 自己写明「written once and never read back through this mapper」），`harnax-admin` 也没有接口读它——所以删除不需要先做读侧迁移，也不会有页面变空。规格 §10 的判据正是这个：只有写、没有读、且新表覆盖同一事实。

- [ ] **Step 1: 两张 schema 与种子行**

`V1__init_schema.sql` 从 `:759` 的 ``CREATE TABLE IF NOT EXISTS `tool_call_log` (`` 起，删到该表的 ``) ENGINE=InnoDB ... COMMENT='...';`` 为止（含该行）——整块删，不留注释说明它曾经存在。

`schema-test.sql:743` 删**逐字相同**的一块（`SchemaBaselineDriftIT` 要求两份基线一致，只删一侧会红），再删 `:870` 那条 ``INSERT INTO `tool_call_log` (`agent_id`, `session_id`, ...) VALUES`` 种子行——一行一条语句，整行删。

- [ ] **Step 2: 删七个文件**

```bash
git rm harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/ToolCallLogEntity.kt \
       harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapper.kt \
       harnax-entity/src/main/resources/mapper/ToolCallLogMapper.xml \
       harnax-entity/src/test/kotlin/com/agnetix/harnax/mapper/ToolCallLogMapperTest.kt \
       harnax-agent/harnax-tools-sdk/src/main/kotlin/com/agnetix/harnax/tools/sdk/adaptor/ToolCallLogAdaptor.kt \
       harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImpl.kt \
       harnax-agent/harnax-agent-service/src/test/kotlin/com/agnetix/harnax/agent/service/adaptor/ToolCallLogAdaptorImplTest.kt
```

`ToolCallLogAdaptor.kt` 里除了 `fun interface` 还有 `data class ToolCallInfo`，两者一起走。`ToolCallInfo` 的引用全在本任务删除或改写的测试里；`harnax-admin` 的 `InternalApiController` 与 `agent.protocol` 里那个叫 `ToolInfo` 的是另一个类型，与它无关，别误删。

- [ ] **Step 3: `ToolBox` 整文件替换**

```kotlin
package com.agnetix.harnax.tools.sdk

/**
 * Base class for a toolbox registered as a model-facing tool.
 *
 * It carries no state: measuring and recording the call belongs to `ToolInvocationMiddleware`, which sees
 * every call — including an MCP tool or a shell command, neither of which is a `ToolBox`. A tool that
 * needs to act as the end user takes that value as its own argument, the way the delivered tools already
 * do, rather than through a base-class seam nothing calls.
 */
abstract class ToolBox {
    /**
     * 获取工具名称（子类必须实现）
     */
    abstract fun name(): String
}
```

`execute`/`executeInternal`/`logToolCall`/`logToolCallError`（`:44-150`）、只被那两条 warn 用到的 `name` 字段（`:22`）、`sessionMetaContextValue`、`toolCallLogAdaptorValue`、`userIdentifierValue`、`init`（`:30-38`）、`userIdentifier()`（`:41-42`）以及 `LoggerFactory`/`UserIdentifier`/`ToolCallInfo`/`ToolCallLogAdaptor` 四个 import 一起去掉；`@Author/@Date/@Description` 那一段旧文件头注释按全仓现状不再保留。删 `init` 之后 Step 5 的三处调用点跟着消失，不是换成单参形式。

- [ ] **Step 4: `ToolCallContext.kt` 删 `SessionMetaContext`**

保留 marker 接口 `ToolCallContext` 与 `data class UserIdentifier`，但理由要按现核写：`UserIdentifier` 有四个 main 引用方（`HarnessAgentLauncher`、`SkillUsageAdaptor`、`DefaultAgentRunner`、admin 的 `MemoryObjectKeys`），必须留；`ToolCallContext` 在 `SessionMetaContext` 删掉后只剩一个实现方且全仓没有按该类型消费的调用点（main 里五处命中全是它自己的声明与两个 `: ToolCallContext`），摘掉它要连带动 `UserIdentifier` 的声明与 import，是旧链之外的独立清理，本轮不做。删掉 `data class SessionMetaContext`（`:11` 起整个类），并把接口 KDoc 里点名 `tool_call_log` 的那句改成只讲「一次调用的归属」，不点表名。

- [ ] **Step 5: 装配侧删接线（`HarnessAgentLauncher.kt`）**

删五行（行号现核于 `HarnessAgentLauncher.kt`，Task 8 落地后按内容再推一遍）：

```kotlin
// :52 与 :55
import com.agnetix.harnax.tools.sdk.SessionMetaContext
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor

// :102 KDoc
 * @param toolCallLogAdaptor adaptor for tool call logging (optional)

// :117 构造器
    val toolCallLogAdaptor: ToolCallLogAdaptor,

// :1155 initLauncher 形参 —— 它是第 7 个必填参数，删掉会改 arity；
// Step 9 的 14 处具名实参必须跟着删，否则 harness-core 测试源码集编译不过。
            toolCallLogAdaptor: ToolCallLogAdaptor,

// :1226 initLauncher 体内的具名实参 —— 漏这一行就是「no parameter with name 'toolCallLogAdaptor' found」，
// 形参与实参是一对，删一侧编译不过。
                toolCallLogAdaptor = toolCallLogAdaptor,
```

`:386-399` 注册块里删掉整段 `toolBox.init(...)`（三行调用连同 `SessionMetaContext(...)` 那个实参），块只剩注册与记账：

```kotlin
                        val toolBox = toolRegistry?.createToolBoxInstance(beanName)
                        if (toolBox != null) {
                            agentBuilder.addTool(toolBox)
                            addedToolBoxBeans.add(beanName)
                        }
```

`:553-567` 团队工具：删 `val teamSessionMeta = SessionMetaContext(...)` 及其上方两句注释（`// One context for both roles: ...` 两行），两处 `toolBox.init(toolCallLogAdaptor, teamSessionMeta, userIdentifier)` 整行删掉。改完这段是：

```kotlin
        // ----- Team tools -----
        // Registered after the tool sweep, so nothing on the ordinary path removes them: that sweep only
        // walks ToolBoxes known to the registry, and these are built here.
        val teamToolNames: Set<String> = when (teamRole) {
            is TeamRole.Lead -> {
                val toolBox = TeamLeadToolBox(teamRole.orchestrator)
                agentBuilder.addTool(toolBox)
                TeamLeadToolBox.TOOL_NAMES
            }

            is TeamRole.Member -> {
                val toolBox = TeamMemberToolBox(teamRole.orchestrator, teamRole.member.memberAgentId)
                agentBuilder.addTool(toolBox)
                TeamMemberToolBox.TOOL_NAMES
            }

            null -> emptySet()
        }
```

- [ ] **Step 6: `HarnessAutoConfiguration.kt` 与 `LauncherBean.kt`**

`HarnessAutoConfiguration.kt` 删四行：`:27` 的 `import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor`、`:320` 的形参 `toolCallLogAdaptorProvider: ObjectProvider<ToolCallLogAdaptor>,`、`:334-335` 的 no-op 兜底（`val toolCallLogAdaptor = toolCallLogAdaptorProvider.ifAvailable` 与 `?: ToolCallLogAdaptor { /* no-op */ }`）、`:350` 的 `toolCallLogAdaptor = toolCallLogAdaptor,`。`ObjectProvider` 的 import 仍被其它 provider 用到，不动。

`LauncherBean.kt:46` 与 `:62` 是那段整体注释掉的 `AscopeAgentLauncher` bean 里的两行（`//     @Autowired(required = false) toolCallLogAdaptor: ToolCallLogAdaptor,` 与 `//         toolCallLogAdaptor,`）——注释不参与编译，但留着就是「这个类型还存在」的假线索，一并删。

- [ ] **Step 7: 拆掉 9 处 `execute { }` 包裹**

`TimeToolBox.kt:23,27` 两行改成表达式体：

```kotlin
    fun getDate(): String = SimpleDateFormat(YYYY_MM_DD).format(Date())

    fun getDatetime(): String = SimpleDateFormat(YYYY_MM_DD_HH_MM_SS).format(Date())
```

`EmailToolBox.kt:64` 的 `): String = execute("to" to to, "subject" to subject) {` 改成 `) {`；块内最后一行 `"Email sent successfully to $to"`（`:124`）改成 `return "Email sent successfully to $to"`；块体缩进减一层。`execute` 的两个参数快照本来就只为日志存在，删掉后没有别的用处。

`TeamToolBoxes.kt` 六处：`26`/`58`/`114` 是单行表达式体（`fun x(): String = execute { E }` → `fun x(): String = E`）；`43`/`90`/`104` 是块形式（开头 `): String = execute(...) {` → `) {`，结尾去掉一层缩进）；`45`/`91`/`105` 三处 `?: return@execute TEXT` 改成 `?: return TEXT`。

- [ ] **Step 8: 改受影响的单元测试**

`ToolBoxTest.kt` **整文件删除**（`git rm`）——拆完之后 `ToolBox` 只剩一个抽象 `name()`，没有任何可断言的行为；留一份「造个空子类断言它返回自己的名字」的用例就是给自己造死状态测试，正是本轮 Task 5 I1 被打回的那一类。它现在 158 行里 8 个 `@Test`（`InitTests` 一个、`ExecuteSuccessTests` 三个、`ExecuteErrorTests` 两个、`AdaptorErrorTests` 两个）全部围着 `execute` 的日志与 `init` 存 user，两者一并消失。

`ToolCallContextTest.kt:20-65` 删掉整个 `SessionMetaContextTests` 内部类（含 `@DisplayName("SessionMetaContext Tests")`），`UserIdentifierTests` 一行不动；文件顶部同时提到两个类的类注释改成就讲 `UserIdentifier`。

`TimeToolBoxTest.kt`：删 `private lateinit var mockAdaptor: ToolCallLogAdaptor`（`:32`）；`setUp` 里 `mockAdaptor = mock()` 与 `init(...)` 两处一起删（`:39-43` 只剩 `timeToolBox = TimeToolBox()` 一行）；删 `getDate should log tool call`（`:71-85`）与 `getDatetime should log tool call`（`:104-115`）两个用例；删 `SessionMetaContext`/`ToolCallInfo`/`ToolCallLogAdaptor`/`UserIdentifier` 四个 import。

`EmailToolBoxTest.kt`：同形——删字段（`:40`）、`setUp` 的 `mockAdaptor = mock()` 与 `init(...)`（`:53-58` 只剩构造那一行）、`sendEmail plain text should log tool call`（`:105-133`）与 `sendEmail should log error on Transport failure`（`:321-350`）两个用例、四个 import。`sendEmail should throw when Transport send fails`（`:303`）保留——它验的是异常照旧上抛，与日志无关。

`EmailToolBoxIntegrationTest.kt`：删 `:32` 字段与 `:59-64` 的 `mockAdaptor = mock()` + `init(...)` 两行（`setUp` 只剩 `emailToolBox = EmailToolBox(...)`）；`SessionMetaContext`、`ToolCallLogAdaptor`、`UserIdentifier` 三个 import 一并删。这个文件整类 `@Disabled`（`:28`），但 `@Disabled` 只跳过运行不跳过编译，test-compile 照样红，别漏。

`TeamToolBoxesTest.kt`：`wiredInto` 连同它上方的 KDoc（`:35-41`）整个 helper 删掉——它唯一的作用就是那次三参 `init`，两处调用点（`:32-33`）改成直接构造 `TeamLeadToolBox(orchestrator)` / `TeamMemberToolBox(orchestrator, memberAgentId = 2L)`；两个列表（`:29-30`，`leadCalls`/`memberCalls` 是它的收集桶）与 `:109-116` 那条 `a refusal is logged as a tool call like any other result` 用例删掉——「一次拒绝仍然要被记为一次调用」这条承诺现在住在 Task 6 的 `every terminal state maps to its own outcome` 与 `a stream that completes without an end event files the call as interrupted` 里，测的是中间件而不是盒子。`ToolCallInfo`/`SessionMetaContext`/`UserIdentifier` 三个 import 一起删。

- [ ] **Step 9: 14 处夹具删具名实参**

每个文件删 `toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),` 一行与 `import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor` 一行（路径均在 `harnax-agent/harnax-harness-core/src/test/kotlin/com/agnetix/harnax/harness/`）：

```
HarnessAgentLauncherCliEnvTest.kt:44         HarnessAgentLauncherLeadSkillTest.kt:74
HarnessAgentLauncherCoordinationTest.kt:46   HarnessAgentLauncherMemoryTest.kt:72
HarnessAgentLauncherSkillSelfWriteTest.kt:65 HarnessAgentLauncherSkillUsageTest.kt:86
HarnessAgentLauncherSkillVisibilityTest.kt:72 HarnessAgentProcessLogAttributionTest.kt:57
HarnessAgentRunAttributionTest.kt:49         HarnessAgentSessionHistoryReadTest.kt:89
HarnessAgentTokenRecordingTest.kt:44         HarnessAgentTurnBudgetTest.kt:48
memory/MemoryBucketPipelineTest.kt:126       memory/MemoryGateFalsificationTest.kt:186
```

- [ ] **Step 10: 三处注释里的表名**

`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt:924` 与 `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/AgentSpec.kt:68` 的注释都列举了 `tool_call_log`，把这一项从列举里去掉（留下 `token_stats` / `process_log`）；第三处在 `ToolCallContext.kt`，随 Step 4 一起改。

- [ ] **Step 11: 编译闭合判据**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN=/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn
$MVN -q spotless:apply -pl harnax-entity,harnax-agent/harnax-tools-sdk,harnax-agent/harnax-harness-core,harnax-agent/harnax-agent-service,harnax-tools-external/harnax-tools-buildin
$MVN -o -am test -pl harnax-agent/harnax-tools-sdk,harnax-agent/harnax-harness-core,harnax-tools-external/harnax-tools-buildin,harnax-agent/harnax-agent-service > /tmp/t9.log 2>&1; echo EXIT=$?
grep -E "Tests run:.*Failures|unresolved reference|BUILD" /tmp/t9.log | tail -8
```
预期：`EXIT=0`，且 `unresolved reference: ToolCallLogAdaptor` / `SessionMetaContext` / `execute` 一条都不出现。

残留判据在仓库根跑，命中数必须为 0（只扫参与构建的源码目录）：

```bash
grep -rn "tool_call_log\|ToolCallLog\|ToolCallInfo\|SessionMetaContext" \
  --include=*.kt --include=*.xml --include=*.sql --include=*.yml \
  harnax-admin/src harnax-agent harnax-entity/src harnax-tools-external harnax-webui/src harnax-deploy
```

命中不为 0 就是漏删。`harnax-app`（已裁定废弃的 uni-app 工程）里的同名残留不属本任务，别顺手改。

- [ ] **Step 12: 提交**

```bash
git add -A harnax-entity/src harnax-agent harnax-tools-external/harnax-tools-buildin/src \
           harnax-admin/src/main/resources/db/migration/V1__init_schema.sql \
           harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt
git diff --cached --stat
git commit -m "refactor(metrics): tool_call_log 整链下线，记录源交给调用指标中间件"
```

`git add -A` 只限上面这些路径。Step 2 的 7 个删除必须与接线改动进同一笔，否则 HEAD 单独检出编不过。物理表在既有库里不会被 Flyway 删掉，`DROP TABLE tool_call_log` 是删数据的动作，Task 13 的部署文档要写明「需人工执行且不可回退」。

---

### Task 10: admin 侧每小时折算与保留窗口清理

**Files:**
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt:3-4,17`（加 `@EnableScheduling`）
- Modify: `harnax-admin/src/main/resources/application.yml`（`harnax:` 块末尾，即 `harnax.cli` 之后）
- Create: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolInvocationRollupIT.kt`

**Interfaces:**
- Consumes: Task 2 的 `ToolInvocationLogMapper.selectUnrolledDates(floor: String): List<String>`、`ToolInvocationLogMapper.deleteRolledOut(before: String): Int`、`ToolInvocationStatsMapper.upsertDay(statDate: String): Int`。
- Produces: `ToolInvocationRollupService.rollUp(): Int`（本次重算了多少天，含每次都会重访的昨天与今天）与 `@Scheduled` 入口 `rollUpHourly()`。Task 11 的读侧与它无耦合：读侧永远先查聚合表，表里有昨天的行就答得出昨天。

**只有 `@Service` 一个类、不配 interface**：admin 的 interface + `impl` 双文件是给控制器注入用的，这个类没有任何控制器调用它，加一层接口只会多一个文件。

- [ ] **Step 1: 写失败 IT**

三条闸门按规格 §6 的三步走：漏跑一天能补上、今天的行每小时被覆盖而不是停在第一个周期、没折算的过期行删不掉。日期用 2020 年的固定窗口（与 `TokenStatsAggregationIT` 同法），避免任何「今天」的时序假设。

```kotlin
package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The hourly rollup against a real MySQL: which days get recomputed, and what the detail cleanup is
 * allowed to delete.
 *
 * Every case drives `rollUp()` directly rather than waiting for a cron, because the whole contract is about
 * which rows survive one run — a claim a timer cannot be asserted against.
 */
class ToolInvocationRollupIT : BaseAdminIT() {

    @Autowired private lateinit var rollup: ToolInvocationRollupService

    private fun call(
        at: LocalDateTime,
        tenantId: Long?,
        outcome: String,
        durationMs: Long,
        toolName: String = "send_email",
    ) {
        jdbc.update(
            """
                INSERT INTO tool_invocation_log
                (tenant_id, agent_id, session_id, user_id, kind, tool_name, outcome, duration_ms, start_time, end_time, ts)
                VALUES (?, ?, ?, ?, 'builtin', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            1L,
            "rollup-session",
            1L,
            toolName,
            outcome,
            durationMs,
            at.minusSeconds(durationMs / 1000L + 1L),
            at,
            at,
        )
    }

    private fun statsFor(date: LocalDate, tenantId: Long): Map<String, Any?>? =
        jdbc.queryForMap(
            "SELECT calls, successes, errors, le_100ms, le_500ms, gt_30s, sum_duration_ms FROM tool_invocation_stats" +
                " WHERE stat_date = ? AND tenant_id = ? AND kind = 'builtin' AND subject_id = 0 AND tool_name = 'send_email'",
            date.toString(),
            tenantId,
        ).ifEmpty { null }

    private fun detailCount(before: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_log WHERE ts < ?", Int::class.java, before)

    @BeforeEach
    fun clearRows() {
        jdbc.update("DELETE FROM tool_invocation_stats")
        jdbc.update("DELETE FROM tool_invocation_log")
    }

    @Nested
    @DisplayName("which days get rolled")
    inner class WhichDays {

        @Test
        @DisplayName("a day that was missed entirely is rolled on the next run") {
            val missed = LocalDate.now().minusDays(3)
            call(missed.atTime(9, 0), TENANT_ID, "SUCCESS", 120L)
            call(missed.atTime(9, 5), TENANT_ID, "ERROR", 900L)

            // The missed day, plus the two days every run revisits.
            assertEquals(3, rollup.rollUp())

            val stats = requireNotNull(statsFor(missed, TENANT_ID))
            assertEquals(2L, (stats["calls"] as Number).toLong())
            assertEquals(1L, (stats["successes"] as Number).toLong())
            assertEquals(1L, (stats["errors"] as Number).toLong())
            // Half-open buckets: 120 ms belongs to (100, 500], not to <=100ms.
            assertEquals(0L, (stats["le_100ms"] as Number).toLong())
            assertEquals(1L, (stats["le_500ms"] as Number).toLong())
        }

        @Test
        @DisplayName("today is re-rolled so the last run of the day is the day's final value") {
            val earlier = LocalDateTime.now().minusHours(2)
            call(earlier, TENANT_ID, "SUCCESS", 50L)
            rollup.rollUp()
            val afterFirst = requireNotNull(statsFor(LocalDate.now(), TENANT_ID))["calls"]

            call(LocalDateTime.now(), TENANT_ID, "SUCCESS", 60L)

            rollup.rollUp()

            val afterSecond = requireNotNull(statsFor(LocalDate.now(), TENANT_ID))["calls"]
            assertEquals(1L, (afterFirst as Number).toLong())
            assertEquals(2L, (afterSecond as Number).toLong())
        }

        @Test
        @DisplayName("a row that lands after a day was folded still gets folded") {
            // The pending set stops naming a day once that day has an aggregate row, so only the forced
            // revisit of yesterday can pick up detail arriving between a day's last fold and midnight.
            val yesterday = LocalDate.now().minusDays(1)
            call(yesterday.atTime(22, 0), TENANT_ID, "SUCCESS", 100L)
            rollup.rollUp()
            assertEquals(1L, (requireNotNull(statsFor(yesterday, TENANT_ID))["calls"] as Number).toLong())

            call(yesterday.atTime(23, 58), TENANT_ID, "SUCCESS", 100L)
            rollup.rollUp()

            assertEquals(2L, (requireNotNull(statsFor(yesterday, TENANT_ID))["calls"] as Number).toLong())
        }

        @Test
        @DisplayName("a detail row with no tenant is not rolled up at all") {
            // The aggregate table cannot hold it (tenant_id NOT NULL); reporting it as pending would make
            // the difference set never empty and starve the days that can be rolled.
            call(LocalDate.now().minusDays(2).atTime(9, 0), null, "SUCCESS", 100L)

            // Only the two days every run revisits: a tenant-less day never enters the pending set.
            assertEquals(2, rollup.rollUp())

            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_stats", Int::class.java))
        }
    }

    @Nested
    @DisplayName("what the cleanup may delete")
    inner class Cleanup {

        @Test
        @DisplayName("an expired day is deleted only once its rollup exists") {
            val stale = LocalDateTime.now().minusDays(REENTION_DAYS + 1L).withHour(9).withMinute(0)
            call(stale, TENANT_ID, "SUCCESS", 100L)

            // First run rolls the day, second run is the one allowed to release the detail rows.
            rollup.rollUp()
            assertEquals(1, detailCount(before = LocalDateTime.now().minusDays(REENTION_DAYS).format(DB_TS)))

            rollup.rollUp()
            assertEquals(0, detailCount(before = LocalDateTime.now().minusDays(REENTION_DAYS).format(DB_TS)))
        }

        @Test
        @DisplayName("expired rows with no tenant are released without a rollup") {
            // They never enter the aggregate table, so waiting for one would keep them forever.
            val stale = LocalDateTime.now().minusDays(REENTION_DAYS + 1L).withHour(9)
            call(stale, null, "SUCCESS", 100L)

            rollup.rollUp()

            assertTrue(detailCount(before = LocalDateTime.now().minusDays(REENTION_DAYS).format(DB_TS)) == 0)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_stats", Int::class.java))
        }
    }

    private companion object {
        const val TENANT_ID = 1L
        const val REENTION_DAYS = 365L
        val DB_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
```

`REENTION_DAYS = 365` 是 IT 自己传不进去的——保留天数由 yml 决定，IT 用的是 `application-it.yml` 的覆盖值，见 Step 5 的第 4 条。**这一跳必须先在 `src/test/resources/application-it.yml` 里把 `harnax.metrics.retention-days` 显式设成 365**，否则上面两条过期用例默认落在 90 天前，`stale` 那行根本不在窗口外，`detailCount` 恒为 0 而假绿。把 `REENTION_DAYS` 写成 `365L` 就是把这条覆盖值抄进测试。

- [ ] **Step 2: 跑 IT 确认红**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN=/Users/heqingsong/software/apache-maven-3.9.12/bin/mvn
$MVN -o verify -pl harnax-admin -am -Dit.test='ToolInvocationRollupIT' -Dtest='ToolInvocationRollup-none' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Pintegration-test > /tmp/t10-red.log 2>&1; echo EXIT=$?
grep -E "unresolved reference|Tests run|ERROR.*Rollup" /tmp/t10-red.log | head -5
```
预期：`EXIT != 0`，以 `unresolved reference: ToolInvocationRollupService` 形式编译失败。

- [ ] **Step 3: service**

```kotlin
package com.agnetix.harnax.admin.service

import com.agnetix.harnax.mapper.ToolInvocationLogMapper
import com.agnetix.harnax.mapper.ToolInvocationStatsMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Turns `tool_invocation_log` into `tool_invocation_stats` one day at a time, then releases the detail rows
 * the aggregate has already taken over.
 *
 * Runs hourly rather than nightly so a day's aggregate lags at most one period behind it, and it recomputes
 * a whole day instead of merging deltas because that is what makes the run idempotent: two instances firing
 * at 05:00 write the same numbers, so this needs no distributed lock and the repository has no lock table to
 * buy one with.
 */
@Service
class ToolInvocationRollupService(
    private val toolInvocationLogMapper: ToolInvocationLogMapper,
    private val toolInvocationStatsMapper: ToolInvocationStatsMapper,
    @Value("\${harnax.metrics.retention-days:90}") private val retentionDays: Long,
    @Value("\${harnax.metrics.rollup-enabled:true}") private val rollupEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(ToolInvocationRollupService::class.java)

    /** Hourly at :05 so the run does not collide with anything that writes the top of the hour. */
    @Scheduled(cron = "0 5 * * * ?")
    fun rollUpHourly() {
        if (!rollupEnabled) return
        try {
            rollUp()
        } catch (e: Exception) {
            // A scheduler that throws stops firing for the rest of the process's life; a logged failure
            // retries on the next period, and the day it missed is still in the difference set then.
            log.error("Tool invocation rollup failed: {}", e.message, e)
        }
    }

    /**
     * Roll every day the detail table says it owes and delete what is now safe to lose; returns the number
     * of days recomputed. The public seam the integration test drives instead of waiting for a cron.
     */
    fun rollUp(): Int {
        val today = LocalDate.now()
        // Today is always rolled, whether or not it shows up as pending: otherwise the first run of a day
        // writes that day's final value and the aggregate trails by a whole day instead of one period.
        // Yesterday is always rolled for the same reason at the other end. The fold fires at :05, so a detail
        // row that arrives between a day's last fold and midnight is already covered by an aggregate row and
        // never reappears in the pending set; without yesterday the closing slice of every day is folded
        // never, and deleteRolledOut releases those rows anyway.
        val days = (
            toolInvocationLogMapper.selectUnrolledDates(UNROLLED_FLOOR) +
                today.minusDays(1).toString() + today.toString()
            ).distinct()

        var rolled = 0
        for (statDate in days.sorted()) {
            if (LocalDate.parse(statDate).isAfter(today)) continue
            toolInvocationStatsMapper.upsertDay(statDate)
            rolled++
        }

        val before = LocalDateTime.now().minusDays(retentionDays).format(TIMESTAMP)
        val deleted = toolInvocationLogMapper.deleteRolledOut(before)
        log.info("Tool invocation rollup: {} day(s) recomputed, {} detail row(s) older than {} released", rolled, deleted, before)
        return rolled
    }

    companion object {
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * Unbounded on purpose: the detail table is already bounded by the retention window, so scanning all
         * of it is cheap, and any floor here would let a day that never got rolled slip out of the difference
         * set and stay unrolled forever — `deleteRolledOut` refuses to release such a row.
         */
        private const val UNROLLED_FLOOR = "1970-01-01"
    }
}
```

`isAfter(today)` 那一跳是必需的：`selectUnrolledDates` 只看明细里有什么，一台时钟快了几秒的实例能把未来一天的行写进明细，而聚合表那一天永远不该有行（它还没过完）。

- [ ] **Step 4: `@EnableScheduling` 与配置键**

`HarnaxAdminApplication.kt` 加 import 与注解：

```kotlin
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    scanBasePackages = [
        "com.agnetix.harnax.admin",
        "com.agnetix.harnax.tools",
    ],
)
@MapperScan(basePackages = ["com.agnetix.harnax.mapper", "com.agnetix.harnax.admin.mapper"])
@EnableScheduling
class HarnaxAdminApplication
```

`application.yml` 的 `harnax:` 块末尾（`harnax.cli.archive-retention-days` 之后）加：

```yaml
  metrics:
    # How long a call stays readable as a single row before only its daily aggregate survives. The window is
    # what keeps the detail table a bounded size on a shared database; days are rolled up before anything is
    # deleted, so a shorter window only costs drill-down depth.
    retention-days: ${HARNAX_METRICS_RETENTION_DAYS:90}
    # One kill switch for the hourly rollup. Off, the aggregate table stops growing and every page that reads
    # it answers for a window that ends at the last run — the detail table still fills.
    rollup-enabled: ${HARNAX_METRICS_ROLLUP_ENABLED:true}
```

`harnax-admin/src/test/resources/application-it.yml` 末尾加 `harnax.metrics.retention-days: 365`，与 Step 1 里 `REENTION_DAYS = 365L` 对齐。

- [ ] **Step 5: 跑绿**

```bash
$MVN -q spotless:apply -pl harnax-admin
$MVN -o verify -pl harnax-admin -am -Dit.test='ToolInvocationRollupIT' -Dtest='ToolInvocationRollup-none' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Pintegration-test > /tmp/t10.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t10.log | tail -4
```
预期：`Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交**

```bash
git add harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/HarnaxAdminApplication.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolInvocationRollupService.kt \
        harnax-admin/src/main/resources/application.yml \
        harnax-admin/src/test/resources/application-it.yml \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolInvocationRollupIT.kt
git commit -m "feat(metrics): 调用指标的每小时折算与保留窗口清理"
```

---

### Task 11: 读侧 API `/api/admin/tool-metrics`

**Files:**
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt`（加 3 个读方法）
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml`
- Modify: `harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt`（加 2 个读方法）
- Modify: `harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml`
- Create: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt`
- Create: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolMetricsService.kt`
- Create: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt`
- Create: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt`
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt`

**Interfaces:**
- Consumes: Task 1/2 的两张表；`ResultVo`（`com.agnetix.harnax.common.dto`）、`ApiErrors.message`、`TenantResolver.resolve(jwtUtil)`、`PageHelper` + `admin/dto/Page.fromPageInfo`。
- Produces: 三个 GET 端点与 `ToolMetricsSummaryResponse` / `ToolMetricsTimeSeriesResponse` / `ToolInvocationRow` 三个形状，Task 12 的前端类型逐字段对齐它们。

- [ ] **Step 1: 读侧 mapper 语句**

`ToolInvocationStatsMapper.kt` 加两个方法（`@Param` 一个都不能少，多参数方法没有它 MyBatis 直接抛）：

```kotlin
    /**
     * One row per subject over a day range, summed across days: `tool_name` for `kind = cli`, otherwise the
     * day's subject column. Aliases are the wire contract — the service reads the map by these keys.
     */
    fun selectSubjectTotals(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
    ): MutableList<MutableMap<String?, Any?>?>?

    /** The same totals per day, for the trend line. */
    fun selectTimeSeries(
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("tenantId") tenantId: Long,
        @Param("kind") kind: String?,
        @Param("subjectId") subjectId: Long?,
        @Param("granularity") granularity: String,
    ): MutableList<MutableMap<String?, Any?>?>?
```

`ToolInvocationStatsMapper.xml` 加两条（`resultType="map"`，桶表达式与 `TokenStatsMapper.xml:64-90` 同形）：

```xml
    <select id="selectSubjectTotals" resultType="map">
        SELECT
        s.kind AS kind,
        s.subject_id AS subjectId,
        s.tool_name AS toolName,
        COALESCE(SUM(s.calls), 0) AS calls,
        COALESCE(SUM(s.successes), 0) AS successes,
        COALESCE(SUM(s.errors), 0) AS errors,
        COALESCE(SUM(s.denials), 0) AS denials,
        COALESCE(SUM(s.interruptions), 0) AS interruptions,
        COALESCE(SUM(s.sum_duration_ms), 0) AS sumDurationMs,
        COALESCE(MAX(s.max_duration_ms), 0) AS maxDurationMs,
        COALESCE(SUM(s.le_100ms), 0) AS le100ms,
        COALESCE(SUM(s.le_500ms), 0) AS le500ms,
        COALESCE(SUM(s.le_2s), 0) AS le2s,
        COALESCE(SUM(s.le_10s), 0) AS le10s,
        COALESCE(SUM(s.le_30s), 0) AS le30s,
        COALESCE(SUM(s.gt_30s), 0) AS gt30s,
        MAX(s.stat_date) AS lastSeenDate
        FROM tool_invocation_stats s
        WHERE s.tenant_id = #{tenantId}
        AND s.stat_date &gt;= #{from}
        AND s.stat_date &lt;= #{to}
        <if test="kind != null and kind != ''">
            AND s.kind = #{kind}
        </if>
        GROUP BY s.kind, s.subject_id, s.tool_name
        ORDER BY calls DESC, lastSeenDate DESC
    </select>

    <select id="selectTimeSeries" resultType="map">
        SELECT
        <choose>
            <when test="granularity == 'week'">
                CAST(DATE_FORMAT(DATE_SUB(s.stat_date, INTERVAL WEEKDAY(s.stat_date) DAY), '%Y-%m-%d 00:00:00') AS DATETIME) AS timePoint
            </when>
            <when test="granularity == 'month'">
                CAST(DATE_FORMAT(s.stat_date, '%Y-%m-01 00:00:00') AS DATETIME) AS timePoint
            </when>
            <otherwise>
                CAST(DATE_FORMAT(s.stat_date, '%Y-%m-%d 00:00:00') AS DATETIME) AS timePoint
            </otherwise>
        </choose>
        ,
        s.kind AS kind,
        s.subject_id AS subjectId,
        s.tool_name AS toolName,
        COALESCE(SUM(s.calls), 0) AS calls,
        COALESCE(SUM(s.successes), 0) AS successes,
        COALESCE(SUM(s.errors), 0) AS errors,
        COALESCE(SUM(s.denials), 0) AS denials,
        COALESCE(SUM(s.interruptions), 0) AS interruptions,
        COALESCE(SUM(s.sum_duration_ms), 0) AS sumDurationMs
        FROM tool_invocation_stats s
        WHERE s.tenant_id = #{tenantId}
        AND s.stat_date &gt;= #{from}
        AND s.stat_date &lt;= #{to}
        <if test="kind != null and kind != ''">
            AND s.kind = #{kind}
        </if>
        <if test="subjectId != null">
            AND s.subject_id = #{subjectId}
        </if>
        GROUP BY timePoint, s.kind, s.subject_id, s.tool_name
        ORDER BY timePoint, calls DESC
    </select>
```

`ToolInvocationLogMapper.kt` 加两个方法，XML 里对应两条语句：`selectSubjectTotalsFromDetail`（`groupBy` 只允许 `agent` / `session` 两个字面值，用 `<choose>` 展开成写死的列名，`#{}` 之外的任何拼接都不接受——`session_id` 是字符串维度，所以这条返回 `subjectKey`）和 `selectInvocationPage`（明细分页，带 `outcome` / `toolName` / `mcpId` / `cliId` / `sessionId` / `agentId` 六个可选谓词）。两条都无条件带 `tenant_id = #{tenantId}`。

```xml
    <select id="selectSubjectTotalsFromDetail" resultType="map">
        SELECT
        <choose>
            <when test="groupBy == 'session'">l.session_id</when>
            <otherwise>l.agent_id</otherwise>
        </choose>
        AS subjectKey,
        <choose>
            <when test="groupBy == 'session'">CAST(NULL AS SIGNED)</when>
            <otherwise>l.agent_id</otherwise>
        </choose>
        AS subjectId,
        COUNT(*) AS calls,
        SUM(l.outcome = 'SUCCESS') AS successes,
        SUM(l.outcome = 'ERROR') AS errors,
        SUM(l.outcome = 'DENIED') AS denials,
        SUM(l.outcome = 'INTERRUPTED') AS interruptions,
        COALESCE(SUM(l.duration_ms), 0) AS sumDurationMs,
        COALESCE(MAX(l.duration_ms), 0) AS maxDurationMs,
        MAX(l.ts) AS lastSeenAt
        FROM tool_invocation_log l
        WHERE l.tenant_id = #{tenantId}
        AND l.ts &gt;= #{from}
        AND l.ts &lt;= #{to}
        <if test="kind != null and kind != ''">
            AND l.kind = #{kind}
        </if>
        GROUP BY subjectKey
        ORDER BY calls DESC
    </select>

    <select id="selectInvocationPage" resultType="map">
        SELECT
        l.id AS id,
        l.kind AS kind,
        l.tool_name AS toolName,
        l.agent_id AS agentId,
        l.session_id AS sessionId,
        l.user_id AS userId,
        l.mcp_id AS mcpId,
        l.cli_id AS cliId,
        l.outcome AS outcome,
        l.error_message AS errorMessage,
        l.args_json AS argsJson,
        l.result_excerpt AS resultExcerpt,
        l.duration_ms AS durationMs,
        l.start_time AS startTime,
        l.ts AS ts
        FROM tool_invocation_log l
        WHERE l.tenant_id = #{tenantId}
        <if test="from != null and from != ''">
            AND l.ts &gt;= #{from}
        </if>
        <if test="to != null and to != ''">
            AND l.ts &lt;= #{to}
        </if>
        <if test="kind != null and kind != ''">
            AND l.kind = #{kind}
        </if>
        <if test="toolName != null and toolName != ''">
            AND l.tool_name = #{toolName}
        </if>
        <if test="mcpId != null">
            AND l.mcp_id = #{mcpId}
        </if>
        <if test="cliId != null">
            AND l.cli_id = #{cliId}
        </if>
        <if test="agentId != null">
            AND l.agent_id = #{agentId}
        </if>
        <if test="sessionId != null and sessionId != ''">
            AND l.session_id = #{sessionId}
        </if>
        <if test="outcome != null and outcome != ''">
            AND l.outcome = #{outcome}
        </if>
        ORDER BY l.ts DESC, l.id DESC
    </select>
```

- [ ] **Step 2: 写失败 IT**

五条闸门：租户收敛、跨窗口边界的日期谓词、P95 落在哪个桶、`lastSeenAt` 在两条路径上的精度差、以及明细里别的租户的行一条都不能出现。

```kotlin
package com.agnetix.harnax.admin.it

import com.agnetix.harnax.entity.ToolInvocationLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime

/**
 * The three read endpoints over a seeded pair of tables.
 *
 * Numbers are asserted from the JSON rather than from the mapper because the contract the page consumes is
 * the JSON: `successRate`, the P95 bucket name and which column `lastSeenAt` came from are all made here.
 */
class ToolMetricsReadIT : BaseAdminIT() {

    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private val window = "?startTime=2020-03-01+00:00:00&endTime=2020-03-31+23:59:59"

    private fun insert(
        at: LocalDateTime,
        tenantId: Long,
        kind: String,
        toolName: String,
        outcome: String,
        durationMs: Long,
        mcpId: Long? = null,
        cliId: Long? = null,
    ) {
        jdbc.update(
            """
                INSERT INTO tool_invocation_log
                (tenant_id, agent_id, session_id, user_id, kind, tool_name, mcp_id, cli_id, outcome,
                 duration_ms, start_time, end_time, ts)
                VALUES (?, 1, 'metrics-session', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenantId,
            kind,
            toolName,
            mcpId,
            cliId,
            outcome,
            durationMs,
            at.minusSeconds(1L),
            at,
            at,
        )
    }

    @BeforeEach
    fun seedRows() {
        jdbc.update("DELETE FROM tool_invocation_stats")
        jdbc.update("DELETE FROM tool_invocation_log")
        // Five calls on one day, one on the next, and a neighbour's row that must never appear.
        insert(LocalDateTime.of(2020, 3, 5, 10, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "SUCCESS", 50L)
        insert(LocalDateTime.of(2020, 3, 5, 10, 1), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "ERROR", 400L)
        insert(LocalDateTime.of(2020, 3, 5, 10, 2), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "DENIED", 4_000L)
        insert(LocalDateTime.of(2020, 3, 5, 10, 3), TENANT_ID, ToolInvocationLog.KIND_MCP, "fetch_url", "SUCCESS", 900L, mcpId = 77L)
        insert(LocalDateTime.of(2020, 3, 5, 10, 4), TENANT_ID, ToolInvocationLog.KIND_CLI, "gh", "SUCCESS", 40_000L, cliId = 88L)
        insert(LocalDateTime.of(2020, 3, 6, 10, 0), TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "send_email", "SUCCESS", 120L)
        insert(LocalDateTime.of(2020, 3, 5, 11, 0), NEIGHBOUR_TENANT_ID, ToolInvocationLog.KIND_BUILTIN, "leaked", "SUCCESS", 10L)
        rollupService.rollUp()
    }

    private fun json(path: String): JsonNode {
        val response = rest.exchange(
            "$base$path",
            HttpMethod.GET,
            HttpEntity<String>(authHeaders()),
            String::class.java,
        )
        assertEquals(200, response.statusCode.value())
        val body = objectMapper.readTree(response.body)
        assertEquals(200, body["code"].asInt(), body.toString())
        return body["data"]
    }

    @Test
    @DisplayName("summary answers per subject with the rates the page shows") {
        val rows = json("/api/admin/tool-metrics/summary$window")["rows"]
        val emails = rows.first { it["toolName"].asString() == "send_email" }
        assertEquals(4, emails["calls"].asInt())
        assertEquals(2, emails["successes"].asInt())
        assertEquals(0.5, emails["successRate"].asDouble(), 1e-9)
        // (50+400+4000+120)/4 = 1142.5 -> 1142
        assertEquals(1142L, emails["avgDurationMs"].asLong())
        assertEquals("builtin", emails["kind"].asString())
        assertEquals("2020-03-06", emails["lastSeenAt"].asString().substring(0, 10))
    }

    @Test
    @DisplayName("p95 is the bucket the accumulated count reaches 95% in") {
        val rows = json("/api/admin/tool-metrics/summary$window")["rows"]
        // send_email: one <=100ms, one (100,500], one (2s,10s]... 4 calls -> 95th percentile needs 3.8
        // rows -> the (2,10] bucket holds the 3rd call, the 4th is already past 3.8 -> report <=10s.
        val emails = rows.first { it["toolName"].asString() == "send_email" }
        assertEquals("<=", emails["p95Operator"].asString())
        assertEquals(10_000L, emails["p95Ms"].asLong())
        val gh = rows.first { it["toolName"].asString() == "gh" }
        assertEquals(">", gh["p95Operator"].asString())
        assertEquals(30_000L, gh["p95Ms"].asLong())
    }

    @Test
    @DisplayName("a neighbour tenant's calls never appear") {
        val names = json("/api/admin/tool-metrics/summary$window")["rows"].map { it["toolName"].asString() }
        assertTrue("leaked" !in names, names.toString())
        val page = json("/api/admin/tool-metrics/invocations$window")
        assertEquals(6, page["total"].asInt())
        assertTrue(page["records"].map { it["toolName"].asString() }.none { it == "leaked" })
    }

    @Test
    @DisplayName("groupBy=session reads the detail table and answers a day-precise lastSeen for it") {
        val rows = json("/api/admin/tool-metrics/summary$window&groupBy=session")["rows"]
        val row = rows.single { it["subjectKey"].asString() == "metrics-session" }
        assertEquals(6, row["calls"].asInt())
        // The detail path carries the exact instant; the aggregate path can only carry a day.
        assertEquals("2020-03-06 10:00:00", row["lastSeenAt"].asString())
    }

    @Test
    @DisplayName("time-series buckets the days and zero-fills the empty ones") {
        val points = json("/api/admin/tool-metrics/time-series$window")["points"]
        val days = points.map { it["timePoint"].asString().substring(0, 10) }.distinct().sorted()
        // The window is a full month, so every day of March 2020 is answered, not only the two that have rows.
        assertTrue(days.contains("2020-03-05") && days.contains("2020-03-06") && days.contains("2020-03-20"))
        assertEquals(31, days.size)
        val empty = points.first { it["timePoint"].asString().startsWith("2020-03-20") }
        assertEquals(0, empty["calls"].asInt())
        assertNotNull(empty["kind"])
    }

    private companion object {
        const val TENANT_ID = 1L
        const val NEIGHBOUR_TENANT_ID = 930_930L
    }
}
```

`rollupService` / `rest` / `base` / `objectMapper` / `authHeaders()` / `HttpEntity` 都取 `BaseAdminIT` 现成的成员（`TokenStatsAggregationIT` 同法），IT 里只补 `@Autowired private lateinit var rollupService: ToolInvocationRollupService`。

- [ ] **Step 3: DTO**

```kotlin
package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Responses for the call-metrics pages.
 *
 * Every field defaults to a non-null value where one exists, because admin serialises with
 * `default-property-inclusion: non_null` and a null here would silently delete the key the page reads.
 */
data class ToolMetricsRow(
    @Schema(description = "Origin bucket: builtin / mcp / cli / shell / framework")
    val kind: String = "",
    @Schema(description = "Subject key: tool name, or the agent id / session id when grouped that way")
    val subjectKey: String = "",
    @Schema(description = "Agent id when groupBy=agent, MCP server id when kind=mcp, CLI package id when kind=cli")
    val subjectId: Long? = null,
    @Schema(description = "Tool name, or the matched command name for kind=cli")
    val toolName: String = "",
    val mcpId: Long? = null,
    val cliId: Long? = null,
    val calls: Long = 0L,
    val successes: Long = 0L,
    val errors: Long = 0L,
    val denials: Long = 0L,
    val interruptions: Long = 0L,
    val successRate: Double = 0.0,
    val avgDurationMs: Long = 0L,
    @Schema(description = "'<=' when the 95th percentile lands inside a bucket, '>' for the open-ended one")
    val p95Operator: String = "<=",
    val p95Ms: Long = 0L,
    @Schema(description = "Day precision from the aggregate table, second precision from the detail table")
    val lastSeenAt: LocalDateTime? = null,
)

data class ToolMetricsSummaryResponse(
    val days: Int = 30,
    val from: String = "",
    val to: String = "",
    val groupBy: String = "tool",
    val totalCalls: Long = 0L,
    val totalSuccesses: Long = 0L,
    val successRate: Double = 0.0,
    val failingCalls: Long = 0L,
    val p95Operator: String = "<=",
    val p95Ms: Long = 0L,
    val rows: List<ToolMetricsRow> = emptyList(),
)

data class ToolMetricsPoint(
    val timePoint: String = "",
    val kind: String = "",
    val subjectKey: String = "",
    val dimensionId: Long? = null,
    val dimensionName: String = "",
    val calls: Long = 0L,
    val successes: Long = 0L,
    val errors: Long = 0L,
    val denials: Long = 0L,
    val interruptions: Long = 0L,
    val avgDurationMs: Long = 0L,
)

data class ToolMetricsTimeSeriesResponse(
    val days: Int = 30,
    val granularity: String = "day",
    val points: List<ToolMetricsPoint> = emptyList(),
)

data class ToolInvocationRow(
    val id: Long = 0L,
    val kind: String = "",
    val toolName: String = "",
    val agentId: Long? = null,
    val sessionId: String = "",
    val userId: Long? = null,
    val mcpId: Long? = null,
    val cliId: Long? = null,
    val outcome: String = "",
    val errorMessage: String? = null,
    val argsJson: String? = null,
    val resultExcerpt: String? = null,
    val durationMs: Long = 0L,
    val startTime: LocalDateTime? = null,
    val ts: LocalDateTime? = null,
)
```

`mcpId` / `cliId` / `errorMessage` / `argsJson` / `resultExcerpt` 是真可空（没有就是没有），页面按 `?.` 读；其余全部给非空默认值。

- [ ] **Step 4: service 与实现**

`ToolMetricsService.kt`：

```kotlin
package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse
import com.agnetix.harnax.admin.dto.ToolInvocationRow

/**
 * Read-only views over the call metrics. The tenant is always a parameter because every query is
 * tenant-scoped, and it never comes from a query string.
 */
interface ToolMetricsService {

    fun getSummary(
        from: String,
        to: String,
        tenantId: Long,
        days: Int,
        kind: String?,
        groupBy: String,
    ): ToolMetricsSummaryResponse

    fun getTimeSeries(
        from: String,
        to: String,
        tenantId: Long,
        days: Int,
        kind: String?,
        subjectId: Long?,
        granularity: String,
    ): ToolMetricsTimeSeriesResponse

    fun getInvocations(
        query: InvocationQuery,
        tenantId: Long,
    ): Page<ToolInvocationRow>
}
```

`InvocationQuery` 是 `ToolMetricsService` 同文件里的一个 data class（`from/to/kind/toolName/mcpId/cliId/agentId/sessionId/outcome/pageNum/pageSize`，全带默认值），因为十一个参数进接口方法会撞上仓里「四个以上参数走对象」的形状，也让 IT 能按具名字段构造。

`ToolMetricsServiceImpl.kt` 的三条承诺，落地时按下面三段实现，每段都有对应的 IT 断言盯着：

```kotlin
    /** Bucket upper bounds in ms, matching the six columns; the last entry is the open-ended one. */
    private val BUCKETS: List<Pair<String, Long>> =
        listOf("le100ms" to 100L, "le500ms" to 500L, "le2s" to 2_000L, "le10s" to 10_000L, "le30s" to 30_000L, "gt30s" to 30_000L)

    private fun p95(
        row: Map<String, Any?>,
        calls: Long,
    ): Pair<String, Long> {
        if (calls <= 0L) return "<=" to 0L
        val needed = Math.ceil(calls * 0.95).toLong()
        var seen = 0L
        for ((key, bound) in BUCKETS) {
            seen += (row[key] as? Number)?.toLong() ?: 0L
            if (seen >= needed) return (if (key == "gt30s") ">" else "<=") to bound
        }
        return ">" to 30_000L
    }
```

`getSummary` 先按 `groupBy` 分叉：`tool` 走 `selectSubjectTotals`（日期字符串 `yyyy-MM-dd`，从 `from`/`to` 各取前 10 个字符），`agent`/`session` 走 `selectSubjectTotalsFromDetail`（时刻字符串）；两条都过 `p95`——聚合路径的桶来自六列之和，明细路径没有桶，于是明细行的 `p95Operator`/`p95Ms` 取 `maxDurationMs` 原值并把 operator 写成 `"<="`（一个真实测到的耗时，不是一个估算档位）。`lastSeenAt`：聚合路径把 `lastSeenDate`（`java.sql.Date`）转 `LocalDate.atStartOfDay()`，明细路径直接用 `MAX(l.ts)` 的 `LocalDateTime`——这个精度差就是 IT 第 4 条断言的内容。

`subjectKey` 这一列两条路径的来历不同，必须按这条规则填而不是留给实现者猜：聚合查询只有 `subject_id`（一个 `Long`，`kind` 为 `builtin`/`shell`/`framework` 时恒为 `0`），没有字符串键，所以聚合行的 `subjectKey` 取 `toolName`，`subjectId` 只有在 `kind=mcp` 或 `kind=cli` 时才随查询结果带上（`0` 不是主体，写进响应会让页面以为能拿它去查明细）；明细行的 `subjectKey` 是 `GROUP BY` 出来的那一列的原值（`agent` 维度是 `agent_id` 的字符串形式，`session` 维度是 `session_id`），`subjectId` 只在 `agent` 维度上填。Task 12 的页面按 `groupBy === 'tool' ? toolName : subjectKey` 显示行名、按 `subjectId` 组明细谓词，靠的就是这条规则。

`getTimeSeries` 把 `selectTimeSeries` 的稀疏行补成整窗：先按 `granularity` 生成桶起点列表（day 逐日、week 逐周一、month 逐月初），再对每个桶 × 每个出现的 `(kind, subject)` 组合补 `calls = 0` 的行。补零是必须的，否则页面上的趋势线会在没有调用的日子跳过去。

`getInvocations` 里 `PageHelper.startPage(query.pageNum, query.pageSize)` 紧跟 `selectInvocationPage`，返回 `Page.fromPageInfo(PageInfo.of(maps))`，`mapRecords { ToolInvocationRow(...) }` 按上面的别名取值。

- [ ] **Step 5: controller**

```kotlin
package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolInvocationRow
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse
import com.agnetix.harnax.admin.service.InvocationQuery
import com.agnetix.harnax.admin.service.ToolMetricsService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Call metrics for tools, MCP servers and CLI packages (design section 7).
 *
 * The tenant always comes from the token: a `tenantId` query parameter here would let one operator ask for
 * another's counts, which every other admin read endpoint already refuses.
 */
@RestController
@RequestMapping("/api/admin/tool-metrics")
@Tag(name = "Tool Call Metrics", description = "Tool, MCP and CLI invocation metrics")
class ToolMetricsController(
    private val toolMetricsService: ToolMetricsService,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(ToolMetricsController::class.java)

    private fun currentTenantId(): Long = TenantResolver.resolve(jwtUtil)

    @GetMapping("/summary")
    @Operation(summary = "Call counts per subject", description = "Totals over the window; the tool view reads the daily aggregate, agent and session read the detail table")
    fun getSummary(
        @Parameter(description = "Window length in days, 1..365")
        @RequestParam(name = "days", required = false, defaultValue = "30") days: Int,
        @Parameter(description = "Origin filter: builtin / mcp / cli / shell / framework")
        @RequestParam(name = "kind", required = false) kind: String?,
        @Parameter(description = "Subject dimension: tool (default) / agent / session")
        @RequestParam(name = "groupBy", required = false, defaultValue = "tool") groupBy: String,
    ): ResultVo<ToolMetricsSummaryResponse> = try {
        val window = window(days)
        ResultVo.success(toolMetricsService.getSummary(window.first, window.second, currentTenantId(), days.coerceIn(1, 365), kind, groupBy))
    } catch (e: Exception) {
        log.error("Failed to get tool metrics summary", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call metrics"))
    }

    @GetMapping("/time-series")
    @Operation(summary = "Call counts per time bucket", description = "Zero-filled buckets so a quiet day does not make the trend line skip")
    fun getTimeSeries(
        @RequestParam(name = "days", required = false, defaultValue = "30") days: Int,
        @RequestParam(name = "granularity", required = false, defaultValue = "day") granularity: String,
        @RequestParam(name = "kind", required = false) kind: String?,
        @Parameter(description = "MCP server or CLI package id")
        @RequestParam(name = "subjectId", required = false) subjectId: Long?,
    ): ResultVo<ToolMetricsTimeSeriesResponse> = try {
        val window = window(days)
        ResultVo.success(toolMetricsService.getTimeSeries(window.first, window.second, currentTenantId(), days.coerceIn(1, 365), kind, subjectId, granularity))
    } catch (e: Exception) {
        log.error("Failed to get tool metrics time series", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call trend"))
    }

    @GetMapping("/invocations")
    @Operation(summary = "Detail page of single calls", description = "Real durations and failure reasons, inside the retention window only")
    fun getInvocations(
        @RequestParam(name = "days", required = false, defaultValue = "30") days: Int,
        @RequestParam(name = "kind", required = false) kind: String?,
        @RequestParam(name = "toolName", required = false) toolName: String?,
        @RequestParam(name = "mcpId", required = false) mcpId: Long?,
        @RequestParam(name = "cliId", required = false) cliId: Long?,
        @RequestParam(name = "agentId", required = false) agentId: Long?,
        @RequestParam(name = "sessionId", required = false) sessionId: String?,
        @RequestParam(name = "outcome", required = false) outcome: String?,
        @RequestParam(name = "pageNum", required = false, defaultValue = "1") pageNum: Int,
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") pageSize: Int,
    ): ResultVo<Page<ToolInvocationRow>> = try {
        val window = window(days)
        val query =
            InvocationQuery(
                from = window.first,
                to = window.second,
                kind = kind,
                toolName = toolName,
                mcpId = mcpId,
                cliId = cliId,
                agentId = agentId,
                sessionId = sessionId,
                outcome = outcome,
                pageNum = pageNum.coerceAtLeast(1),
                pageSize = pageSize.coerceIn(1, 200),
            )
        ResultVo.success(toolMetricsService.getInvocations(query, currentTenantId()))
    } catch (e: Exception) {
        log.error("Failed to get tool invocation page", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call details"))
    }

    /** The requested window as the two strings every mapper below compares against `ts`. */
    private fun window(days: Int): Pair<String, String> {
        val clamped = days.coerceIn(1, 365)
        val to = LocalDateTime.now()
        return to.minusDays((clamped - 1).toLong()).toLocalDate().atStartOfDay().format(TIMESTAMP) to to.format(TIMESTAMP)
    }

    companion object {
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
```

`JwtUtil` 的 import 与 `TokenStatsController` 一致（`com.agnetix.harnax.admin.util.JwtUtil` 或 `common` 下的那个，按 `TokenStatsController.kt:1-25` 抄）。

- [ ] **Step 6: 跑绿**

```bash
$MVN -q spotless:apply -pl harnax-entity,harnax-admin
$MVN -o verify -pl harnax-admin -am -Dit.test='ToolMetricsReadIT,ToolInvocationRollupIT,SchemaBaselineDriftIT' -Dtest='ToolMetrics-none' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Pintegration-test > /tmp/t11.log 2>&1; echo EXIT=$?
grep -E "Tests run|BUILD" /tmp/t11.log | tail -5
```
预期：`ToolMetricsReadIT` 5 条全过，`Tests run:` 行里 `Failures: 0, Errors: 0`。

- [ ] **Step 7: 提交**

```bash
git add harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationStatsMapper.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationStatsMapper.xml \
        harnax-entity/src/main/kotlin/com/agnetix/harnax/mapper/ToolInvocationLogMapper.kt \
        harnax-entity/src/main/resources/mapper/ToolInvocationLogMapper.xml \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/ToolMetricsService.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/ToolMetricsServiceImpl.kt \
        harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt \
        harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/ToolMetricsReadIT.kt
git commit -m "feat(metrics): 调用指标的汇总、趋势与明细三个读端点"
```

---

### Task 12: 前端调用指标页

**Files:**
- Create: `harnax-webui/src/pages/call-metrics/index.tsx`
- Create: `harnax-webui/src/services/ant-design-pro/toolMetrics.ts`
- Modify: `harnax-webui/src/typings.d.ts`（接在 `SkillUsageSummary` 之后，该块以 `:552` 的 `};` 收尾）
- Modify: `harnax-webui/config/routes.ts`
- Modify: `harnax-webui/src/locales/zh-CN/menu.ts`、`harnax-webui/src/locales/en-US/menu.ts`
- Modify: `harnax-webui/src/locales/zh-CN/pages.ts`、`harnax-webui/src/locales/en-US/pages.ts`

**Interfaces:**
- Consumes: Task 11 的三个端点 `/api/admin/tool-metrics/{summary,time-series,invocations}` 与 `ToolMetricsSummaryResponse` / `ToolMetricsRow` / `ToolMetricsTimeSeriesResponse` / `ToolMetricsPoint` / `ToolInvocationRow` + admin `Page` 的序列化形状。
- Produces: 一个菜单位 `/call-metrics`（挂在哪个分组由 Step 1 判定），Task 13 的部署文档按这个路径写访问方式。

两条前置事实，都要在动手前认清楚：

- **这一组路径在主检出里是 Dashboard 轨的未提交文件**（`git diff --stat` 在工作树里能看到 `config/routes.ts`、两份 `menu.ts`、两份 `pages.ts`、`typings.d.ts`、`Welcome.tsx` 全在改动集内）。本任务只在 worktree 里改它们：worktree 检出的是 HEAD，那份未提交的 Dashboard 改动不在里面，两边不会互相覆盖。**在 `kotlin-dev` 检出上做这一步会直接撞上那轨的在途工作，禁止。**
- **落点有分叉，是核实过的而不是猜的**：HEAD 的 `harnax-webui/config/routes.ts` 里**没有** `monitor` 分组——技能用量与待审草稿还在 `context` 组内（`config/routes.ts:102-106` 是 `{ name: 'skill.usage', path: '/context/skill-usage', component: './skill/usage' }`），菜单 key 是 `menu.context.skill.usage`（zh/en 两份 `menu.ts` 都在 `:10`）。工作树那份未提交改动把这两项移进了新建的 `monitor` 组并把旧地址改成 redirect。设计文档 §8 写的是「monitor 组内、技能用量之后」，那是按工作树写的；worktree 里没有这个组，所以 Step 1 先判形再落。

- [ ] **Step 1: 判形并插路由**

```bash
cd harnax-webui && grep -n "path: '/monitor'" config/routes.ts
```

**零命中 = 形状 A（worktree 的当前事实）**。在 `context` 组的技能用量之后插入，路径 `/context/call-metrics`：

```ts
      {
        name: 'skill.usage',
        path: '/context/skill-usage',
        component: './skill/usage',
      },
      {
        name: 'call.metrics',
        path: '/context/call-metrics',
        component: './call-metrics',
      },
```

**有命中 = 形状 B（Dashboard 轨已合入之后）**。在 `monitor` 组的技能用量之后插入，路径 `/monitor/call-metrics`，其余完全一致：

```ts
      {
        name: 'skill.usage',
        path: '/monitor/skill-usage',
        component: './skill/usage',
      },
      {
        name: 'call.metrics',
        path: '/monitor/call-metrics',
        component: './call-metrics',
      },
```

两种形状都**不带 `access`**：全仓只有 `/system/*` 三条路由有门禁，monitor/context 的子项一律没有，加上去会让菜单在普通账号下整条消失。菜单 key 由 `name` 的最后一段与父级路径拼出，`name: 'call.metrics'` 在 A 下取 `menu.context.call.metrics`、在 B 下取 `menu.monitor.call.metrics`——`layout` 开着 locale，缺 key 会在侧栏直接渲染出裸 key 字符串，所以 Step 2 与这一步是同一笔提交。

- [ ] **Step 2: 菜单文案（两侧）**

形状 A 在 `'menu.context.skill.usage'` 那一行之后插；形状 B 在 `'menu.monitor.skill.usage'` 之后插。

`src/locales/zh-CN/menu.ts`：

```ts
  'menu.context.call.metrics': '调用监控',
```

`src/locales/en-US/menu.ts`：

```ts
  'menu.context.call.metrics': 'Call Metrics',
```

形状 B 时 key 换成 `menu.monitor.call.metrics`，值不变。

- [ ] **Step 3: 服务层三个函数**

`src/services/ant-design-pro/toolMetrics.ts` 整文件：

```ts
// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** GET /api/admin/tool-metrics/summary — per-subject totals over one window */
export async function getToolMetricsSummary(
  params: {
    days?: number;
    kind?: string;
    groupBy?: string;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallMetricsSummary>>('/api/admin/tool-metrics/summary', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/admin/tool-metrics/time-series — zero-filled buckets for the trend line */
export async function getToolMetricsTimeSeries(
  params: {
    days?: number;
    granularity?: string;
    kind?: string;
    subjectId?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallMetricsTrend>>('/api/admin/tool-metrics/time-series', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** GET /api/admin/tool-metrics/invocations — detail rows, inside the retention window only */
export async function getToolInvocations(
  params: {
    days?: number;
    kind?: string;
    toolName?: string;
    mcpId?: number;
    cliId?: number;
    agentId?: number;
    sessionId?: string;
    outcome?: string;
    pageNum?: number;
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.Result<API.CallInvocationPage>>('/api/admin/tool-metrics/invocations', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}
```

形状照 `src/services/ant-design-pro/skillUsage.ts`：不写 baseUrl，路径由 `config/proxy.ts` 的 `/api/admin/` 通配与生产 nginx 承接；`undefined` 的键由 umi 的 request 丢掉，正好对上后端「参数缺省即不加谓词」。

- [ ] **Step 4: 前端类型**

admin 序列化 `Page<T>` 出来的键是 `pageNum` / `pageSize` / `total` / `records`，与 `src/services/ant-design-pro/typings.d.ts:69` 的 `PageResult`（`size` / `current`）不是同一形状，仓库里已经为这件事写过一份类型（`src/typings.d.ts:578-586` 的 `SkillDraftPage`），这里同样另立一份而不是复用 `PageResult`。

接在 `SkillUsageSummary` 的 `};` 之后插入：

```ts
  /**
   * @zh-CN 调用指标的一个主体行：按工具聚合时看 toolName，按智能体/会话聚合时看 subjectKey
   *（对应后端 ToolMetricsRow）
   */
  export type CallMetricsRow = {
    kind: string;
    subjectKey: string;
    subjectId?: number;
    toolName: string;
    mcpId?: number;
    cliId?: number;
    calls: number;
    successes: number;
    errors: number;
    denials: number;
    interruptions: number;
    /** 0..1，服务端算好的比值，页面只做百分比格式化 */
    successRate: number;
    avgDurationMs: number;
    /** '<=' 或 '>'，六桶近似出来的 P95 落在桶里还是落在开口桶外 */
    p95Operator: string;
    p95Ms: number;
    /** 聚合路径是日精度、明细路径是秒精度，页面统一按 YYYY-MM-DD HH:mm:ss 显示 */
    lastSeenAt?: string;
  };

  /**
   * @zh-CN 窗口内的调用总量与卡片计数（对应后端 ToolMetricsSummaryResponse）
   */
  export type CallMetricsSummary = {
    days: number;
    from: string;
    to: string;
    groupBy: string;
    totalCalls: number;
    totalSuccesses: number;
    successRate: number;
    failingCalls: number;
    p95Operator: string;
    p95Ms: number;
    rows: CallMetricsRow[];
  };

  /**
   * @zh-CN 趋势的一个补零桶（对应后端 ToolMetricsPoint）
   */
  export type CallMetricsPoint = {
    timePoint: string;
    kind: string;
    subjectKey: string;
    dimensionId?: number;
    dimensionName: string;
    calls: number;
    successes: number;
    errors: number;
    denials: number;
    interruptions: number;
    avgDurationMs: number;
  };

  /**
   * @zh-CN 时间序列响应（对应后端 ToolMetricsTimeSeriesResponse）
   */
  export type CallMetricsTrend = {
    days: number;
    granularity: string;
    points: CallMetricsPoint[];
  };

  /**
   * @zh-CN 一次调用的明细行，只在保留窗口内可查（对应后端 ToolInvocationRow）
   */
  export type CallInvocationRow = {
    id: number;
    kind: string;
    toolName: string;
    agentId?: number;
    sessionId: string;
    userId?: number;
    mcpId?: number;
    cliId?: number;
    outcome: string;
    errorMessage?: string;
    /** capture-payload=false 时整列为 null，页面按「没有就不显示」处理 */
    argsJson?: string;
    resultExcerpt?: string;
    durationMs: number;
    startTime?: string;
    ts?: string;
  };

  /**
   * @zh-CN admin 的 Page 序列化出的就是这四个字段，与 PageResult 的 size/current 形状不同
   */
  export type CallInvocationPage = {
    pageNum: number;
    pageSize: number;
    total: number;
    records: CallInvocationRow[];
  };
```

类型必须写在这里而不是服务文件里：`biome.json` 的 `files.includes` 显式排除了 `!**/src/services/**`，放在服务文件里等于没被检查。

- [ ] **Step 5: 页面文案（两侧）**

`src/locales/zh-CN/pages.ts`，在技能用量那段之后（`:220` 的 `windowHint` 之后）插入：

```ts
  // 调用监控
  'pages.callMetrics.title': '调用监控',
  'pages.callMetrics.tab.tool': '工具',
  'pages.callMetrics.tab.mcp': 'MCP',
  'pages.callMetrics.tab.cli': 'CLI',
  'pages.callMetrics.totalCalls': '调用量',
  'pages.callMetrics.successRate': '成功率',
  'pages.callMetrics.successRateHint': '以 SUCCESS 收尾的占比；拒绝与中断都算失败',
  'pages.callMetrics.p95': 'P95 耗时',
  'pages.callMetrics.p95Hint': '由六个耗时分桶近似，按工具口径时给的是桶边界而不是精确毫秒',
  'pages.callMetrics.failingCalls': '失败数',
  'pages.callMetrics.failingHint': 'ERROR / DENIED / INTERRUPTED 三类合计',
  'pages.callMetrics.trendTitle': '调用趋势',
  'pages.callMetrics.series.calls': '调用量',
  'pages.callMetrics.series.successes': '成功',
  'pages.callMetrics.series.failures': '失败',
  'pages.callMetrics.subjectTitle': '主体明细',
  'pages.callMetrics.col.subject': '主体',
  'pages.callMetrics.col.calls': '调用量',
  'pages.callMetrics.col.successRate': '成功率',
  'pages.callMetrics.col.avgDuration': '平均耗时',
  'pages.callMetrics.col.p95': 'P95',
  'pages.callMetrics.col.lastSeen': '最近调用',
  'pages.callMetrics.col.outcome': '结果',
  'pages.callMetrics.col.duration': '耗时',
  'pages.callMetrics.col.time': '时间',
  'pages.callMetrics.col.session': '会话',
  'pages.callMetrics.col.agent': '智能体',
  'pages.callMetrics.groupBy.label': '维度',
  'pages.callMetrics.groupBy.tool': '按工具',
  'pages.callMetrics.groupBy.agent': '按智能体',
  'pages.callMetrics.groupBy.session': '按会话',
  'pages.callMetrics.kind.label': '来源',
  'pages.callMetrics.kind.builtin': '下发工具',
  'pages.callMetrics.kind.shell': '裸 shell 命令',
  'pages.callMetrics.kind.framework': '运行时自带',
  'pages.callMetrics.window.7': '近 7 天',
  'pages.callMetrics.window.30': '近 30 天',
  'pages.callMetrics.window.90': '近 90 天',
  'pages.callMetrics.window.365': '近 365 天',
  'pages.callMetrics.detailHint': '按智能体 / 会话统计读的是明细表，只覆盖保留窗口内的调用；更早的记录只有按工具口径的日聚合能答。',
  'pages.callMetrics.detail.title': '最近调用',
  'pages.callMetrics.detail.errorMessage': '失败原因',
  'pages.callMetrics.detail.args': '入参',
  'pages.callMetrics.detail.result': '结果摘要',
  'pages.callMetrics.outcome.SUCCESS': '成功',
  'pages.callMetrics.outcome.ERROR': '失败',
  'pages.callMetrics.outcome.DENIED': '被拒绝',
  'pages.callMetrics.outcome.INTERRUPTED': '被中断',
  'pages.callMetrics.empty': '该窗口内没有调用记录',
  'pages.callMetrics.loadFailed': '加载调用指标失败',
  'pages.callMetrics.windowHint': '计数口径为最近 {days} 天。成功率与 P95 是窗口内的整体值，不是某一天的值。',
```

`src/locales/en-US/pages.ts` 同一位置插入对应英文：

```ts
  // Call metrics
  'pages.callMetrics.title': 'Call Metrics',
  'pages.callMetrics.tab.tool': 'Tools',
  'pages.callMetrics.tab.mcp': 'MCP',
  'pages.callMetrics.tab.cli': 'CLI',
  'pages.callMetrics.totalCalls': 'Calls',
  'pages.callMetrics.successRate': 'Success rate',
  'pages.callMetrics.successRateHint': 'Share of calls ending SUCCESS; denials and interruptions count as failures',
  'pages.callMetrics.p95': 'P95 duration',
  'pages.callMetrics.p95Hint': 'Approximated from six duration buckets, so the per-tool view gives a bucket boundary rather than an exact millisecond',
  'pages.callMetrics.failingCalls': 'Failed calls',
  'pages.callMetrics.failingHint': 'ERROR, DENIED and INTERRUPTED together',
  'pages.callMetrics.trendTitle': 'Call trend',
  'pages.callMetrics.series.calls': 'Calls',
  'pages.callMetrics.series.successes': 'Successes',
  'pages.callMetrics.series.failures': 'Failures',
  'pages.callMetrics.subjectTitle': 'Per-subject detail',
  'pages.callMetrics.col.subject': 'Subject',
  'pages.callMetrics.col.calls': 'Calls',
  'pages.callMetrics.col.successRate': 'Success rate',
  'pages.callMetrics.col.avgDuration': 'Avg duration',
  'pages.callMetrics.col.p95': 'P95',
  'pages.callMetrics.col.lastSeen': 'Last call',
  'pages.callMetrics.col.outcome': 'Outcome',
  'pages.callMetrics.col.duration': 'Duration',
  'pages.callMetrics.col.time': 'Time',
  'pages.callMetrics.col.session': 'Session',
  'pages.callMetrics.col.agent': 'Agent',
  'pages.callMetrics.groupBy.label': 'Group',
  'pages.callMetrics.groupBy.tool': 'By tool',
  'pages.callMetrics.groupBy.agent': 'By agent',
  'pages.callMetrics.groupBy.session': 'By session',
  'pages.callMetrics.kind.label': 'Origin',
  'pages.callMetrics.kind.builtin': 'Delivered tools',
  'pages.callMetrics.kind.shell': 'Bare shell commands',
  'pages.callMetrics.kind.framework': 'Runtime built-ins',
  'pages.callMetrics.window.7': 'Last 7 days',
  'pages.callMetrics.window.30': 'Last 30 days',
  'pages.callMetrics.window.90': 'Last 90 days',
  'pages.callMetrics.window.365': 'Last 365 days',
  'pages.callMetrics.detailHint': 'The agent and session views read the detail table, so they only cover calls inside the retention window; older history is answerable only per tool, from the daily aggregate.',
  'pages.callMetrics.detail.title': 'Recent calls',
  'pages.callMetrics.detail.errorMessage': 'Failure reason',
  'pages.callMetrics.detail.args': 'Input',
  'pages.callMetrics.detail.result': 'Result excerpt',
  'pages.callMetrics.outcome.SUCCESS': 'Success',
  'pages.callMetrics.outcome.ERROR': 'Failed',
  'pages.callMetrics.outcome.DENIED': 'Denied',
  'pages.callMetrics.outcome.INTERRUPTED': 'Interrupted',
  'pages.callMetrics.empty': 'No call inside this window',
  'pages.callMetrics.loadFailed': 'Failed to load call metrics',
  'pages.callMetrics.windowHint': 'Counts cover the last {days} days. Success rate and P95 are window-wide figures, not per-day ones.',
```

同一步里改掉技能用量页的自述文案（设计 §9）——USE 现在有写入方了，那两句「这一列还没有数据来源」必须撤掉。

`src/locales/zh-CN/pages.ts:209`：

```ts
  'pages.skill.usage.usesHint': '技能指令被实际执行的次数；装载成功时由运行时上报',
```

`src/locales/en-US/pages.ts:209`：

```ts
  'pages.skill.usage.usesHint': "Times a skill's instructions were actually followed, reported by the runtime when a load succeeds",
```

这两行是**整行替换**，不是追加：两份文件在这一段的其他行一律不动。

- [ ] **Step 6: 页面**

`src/pages/call-metrics/index.tsx` 整文件。骨架对齐 `src/pages/skill/usage.tsx`（`PageContainer` + `Spin` + `Row/Col + Card + Statistic` + `className="styled-pro-table"` 的 `Table` + `response.code === 200` 判成功 + `var(--vip-*)` 令牌）；折线用 plots v2 形状，`colorField` 而不是 `seriesField`，数据是 `flatMap` 出的长表，配色走 `scale.color.range` 的字面 hex——现例 `src/pages/token-monitor/index.tsx:601-660` 的 `lineConfig`。

```tsx
import React, { useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Card, Col, Descriptions, Drawer, Empty, Row, Select, Spin, Statistic, Table, Tabs, Tag, Tooltip, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Line } from '@ant-design/plots';
import { DashboardOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import dayjs from 'dayjs';
import { getToolInvocations, getToolMetricsSummary, getToolMetricsTimeSeries } from '@/services/ant-design-pro/toolMetrics';

const CALLS_COLOR = '#4f6ef7';
const SUCCESS_COLOR = '#10b981';
const FAILURE_COLOR = '#ef4444';

/**
 * Tab to origin bucket. `shell` and `framework` stay reachable inside the tool tab because the endpoint takes
 * exactly one `kind` and has no way to say "everything except mcp and cli" — a fourth tab would have to be
 * built out of exclusions the server does not support.
 */
const TAB_KINDS: Record<string, string> = { tool: 'builtin', mcp: 'mcp', cli: 'cli' };
const TOOL_TAB_KINDS: string[] = ['builtin', 'shell', 'framework'];

const OUTCOME_COLORS: Record<string, string> = {
  SUCCESS: 'green',
  ERROR: 'red',
  DENIED: 'orange',
  INTERRUPTED: 'default',
};

const formatRate = (rate: number) => `${(rate * 100).toFixed(1)}%`;

/**
 * Call metrics over `tool_invocation_log` / `tool_invocation_stats` (design section 8).
 *
 * Three tabs share one shape on purpose: the backend returns the same row for every origin bucket, so the
 * difference between a tool, an MCP server and a CLI package is only which of mcp_id / cli_id got filled.
 */
const CallMetrics: React.FC = () => {
  const intl = useIntl();
  const [tab, setTab] = useState<string>('tool');
  const [toolKind, setToolKind] = useState<string>('builtin');
  const [days, setDays] = useState<number>(30);
  const [groupBy, setGroupBy] = useState<string>('tool');
  const [summary, setSummary] = useState<API.CallMetricsSummary | null>(null);
  const [points, setPoints] = useState<API.CallMetricsPoint[]>([]);
  const [loading, setLoading] = useState(false);

  const [subject, setSubject] = useState<API.CallMetricsRow | null>(null);
  const [details, setDetails] = useState<API.CallInvocationRow[]>([]);
  const [detailTotal, setDetailTotal] = useState<number>(0);
  const [detailPage, setDetailPage] = useState<number>(1);
  const [detailLoading, setDetailLoading] = useState(false);

  const kind = tab === 'tool' ? toolKind : TAB_KINDS[tab];

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        const [summaryRes, trendRes] = await Promise.all([
          getToolMetricsSummary({ days, kind, groupBy }),
          getToolMetricsTimeSeries({ days, kind, granularity: 'day' }),
        ]);
        if (summaryRes.code === 200) {
          setSummary(summaryRes.data ?? null);
        } else {
          message.error(summaryRes.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
        }
        if (trendRes.code === 200) {
          setPoints(trendRes.data?.points ?? []);
        }
      } catch (error: any) {
        message.error(error?.message || error?.info?.errorMessage || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
      } finally {
        setLoading(false);
      }
    };
    load();
  }, [days, kind, groupBy]);

  const loadDetails = async (row: API.CallMetricsRow, page: number) => {
    setSubject(row);
    setDetailPage(page);
    setDetailLoading(true);
    try {
      const res = await getToolInvocations({
        days,
        kind: row.kind,
        toolName: groupBy === 'tool' ? row.toolName : undefined,
        mcpId: row.kind === 'mcp' ? row.subjectId : undefined,
        cliId: row.kind === 'cli' ? row.subjectId : undefined,
        agentId: groupBy === 'agent' ? row.subjectId : undefined,
        sessionId: groupBy === 'session' ? row.subjectKey : undefined,
        pageNum: page,
        pageSize: 20,
      });
      if (res.code === 200) {
        setDetails(res.data?.records ?? []);
        setDetailTotal(res.data?.total ?? 0);
      } else {
        message.error(res.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
      }
    } catch (error: any) {
      message.error(error?.message || intl.formatMessage({ id: 'pages.callMetrics.loadFailed', defaultMessage: 'Failed to load call metrics' }));
    } finally {
      setDetailLoading(false);
    }
  };

  const statCards: { title: string; value: string | number; color: string; hint?: string }[] = [
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.totalCalls', defaultMessage: 'Calls' }),
      value: summary?.totalCalls ?? 0,
      color: CALLS_COLOR,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.successRate', defaultMessage: 'Success rate' }),
      value: formatRate(summary?.successRate ?? 0),
      color: SUCCESS_COLOR,
      hint: intl.formatMessage({ id: 'pages.callMetrics.successRateHint', defaultMessage: 'Share of calls ending SUCCESS; denials and interruptions count as failures' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.p95', defaultMessage: 'P95 duration' }),
      value: `${summary?.p95Operator ?? '<='}${summary?.p95Ms ?? 0} ms`,
      color: '#0ea5e9',
      hint: intl.formatMessage({ id: 'pages.callMetrics.p95Hint', defaultMessage: 'Approximated from six duration buckets, so the per-tool view gives a bucket boundary rather than an exact millisecond' }),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.failingCalls', defaultMessage: 'Failed calls' }),
      value: summary?.failingCalls ?? 0,
      color: FAILURE_COLOR,
      hint: intl.formatMessage({ id: 'pages.callMetrics.failingHint', defaultMessage: 'ERROR, DENIED and INTERRUPTED together' }),
    },
  ];

  const trendData = useMemo(
    () =>
      points.flatMap((point) => [
        { time: point.timePoint, type: intl.formatMessage({ id: 'pages.callMetrics.series.calls', defaultMessage: 'Calls' }), value: point.calls },
        { time: point.timePoint, type: intl.formatMessage({ id: 'pages.callMetrics.series.successes', defaultMessage: 'Successes' }), value: point.successes },
        {
          time: point.timePoint,
          type: intl.formatMessage({ id: 'pages.callMetrics.series.failures', defaultMessage: 'Failures' }),
          value: point.errors + point.denials + point.interruptions,
        },
      ]),
    [points, intl],
  );

  const lineConfig = {
    data: trendData,
    xField: 'time',
    yField: 'value',
    colorField: 'type',
    shapeField: 'smooth',
    height: 260,
    axis: {
      x: { labelFormatter: (time: string) => (time ? dayjs(time).format('MM-DD') : time), labelAutoRotate: false },
      y: { labelFormatter: (value: number) => `${value}` },
    },
    legend: { color: { position: 'top' as const, layout: { justifyContent: 'center' } } },
    tooltip: { title: 'time' },
    style: { lineWidth: 2 },
    scale: { color: { range: [CALLS_COLOR, SUCCESS_COLOR, FAILURE_COLOR] } },
    point: { shapeField: 'circle', sizeField: 3 },
  };

  const subjectColumns: ColumnsType<API.CallMetricsRow> = [
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.subject', defaultMessage: 'Subject' }),
      key: 'subject',
      width: 240,
      ellipsis: true,
      render: (_: unknown, row) => (
        <a onClick={() => loadDetails(row, 1)} style={{ fontWeight: 500, cursor: 'pointer' }}>
          {groupBy === 'tool' ? row.toolName : row.subjectKey}
        </a>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.calls', defaultMessage: 'Calls' }),
      dataIndex: 'calls',
      key: 'calls',
      width: 100,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.successRate', defaultMessage: 'Success rate' }),
      dataIndex: 'successRate',
      key: 'successRate',
      width: 110,
      render: (rate: number) => formatRate(rate),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.avgDuration', defaultMessage: 'Avg duration' }),
      dataIndex: 'avgDurationMs',
      key: 'avgDurationMs',
      width: 110,
      render: (ms: number) => `${ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.p95', defaultMessage: 'P95' }),
      key: 'p95',
      width: 110,
      render: (_: unknown, row) => `${row.p95Operator}${row.p95Ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.lastSeen', defaultMessage: 'Last call' }),
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      width: 170,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
  ];

  const detailColumns: ColumnsType<API.CallInvocationRow> = [
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.time', defaultMessage: 'Time' }),
      dataIndex: 'ts',
      key: 'ts',
      width: 170,
      render: (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss.SSS') : '-'),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.outcome', defaultMessage: 'Outcome' }),
      dataIndex: 'outcome',
      key: 'outcome',
      width: 100,
      render: (outcome: string) => (
        <Tag color={OUTCOME_COLORS[outcome] || 'default'}>
          {intl.formatMessage({ id: `pages.callMetrics.outcome.${outcome}`, defaultMessage: outcome })}
        </Tag>
      ),
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.duration', defaultMessage: 'Duration' }),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => `${ms} ms`,
    },
    {
      title: intl.formatMessage({ id: 'pages.callMetrics.col.session', defaultMessage: 'Session' }),
      dataIndex: 'sessionId',
      key: 'sessionId',
      ellipsis: true,
      render: (sessionId: string) => sessionId || '-',
    },
  ];

  const windowOptions = [7, 30, 90, 365].map((value) => ({
    value,
    label: intl.formatMessage({ id: `pages.callMetrics.window.${value}`, defaultMessage: `Last ${value} days` }),
  }));

  return (
    <PageContainer
      header={{
        title: (
          <span style={{ fontSize: '18px', fontWeight: 600, color: 'var(--vip-text-primary)' }}>
            <DashboardOutlined style={{ marginRight: 10, color: 'var(--vip-primary)' }} />
            {intl.formatMessage({ id: 'pages.callMetrics.title', defaultMessage: 'Call Metrics' })}
          </span>
        ),
      }}
    >
      <Spin spinning={loading}>
        <Tabs
          activeKey={tab}
          onChange={(key) => {
            setTab(key);
            if (key !== 'tool') setToolKind('builtin');
          }}
          items={[
            { key: 'tool', label: intl.formatMessage({ id: 'pages.callMetrics.tab.tool', defaultMessage: 'Tools' }) },
            { key: 'mcp', label: intl.formatMessage({ id: 'pages.callMetrics.tab.mcp', defaultMessage: 'MCP' }) },
            { key: 'cli', label: intl.formatMessage({ id: 'pages.callMetrics.tab.cli', defaultMessage: 'CLI' }) },
          ]}
        />

        <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
          {statCards.map((card) => (
            <Col xs={12} sm={12} md={6} key={card.title}>
              <Card
                style={{
                  borderRadius: 12,
                  border: '1px solid var(--vip-border)',
                  background: 'var(--vip-bg-container)',
                }}
              >
                <Statistic
                  title={
                    card.hint ? (
                      <Tooltip title={card.hint}>
                        <span>
                          {card.title} <QuestionCircleOutlined style={{ color: 'var(--vip-text-tertiary)' }} />
                        </span>
                      </Tooltip>
                    ) : (
                      card.title
                    )
                  }
                  value={card.value}
                  valueStyle={{ color: card.color, fontWeight: 600 }}
                />
              </Card>
            </Col>
          ))}
        </Row>

        <Card
          title={
            <span style={{ fontSize: '14px', fontWeight: 600 }}>
              {intl.formatMessage({ id: 'pages.callMetrics.trendTitle', defaultMessage: 'Call trend' })}
            </span>
          }
          styles={{ body: { padding: '12px' } }}
          style={{ marginBottom: 16 }}
        >
          {trendData.length > 0 ? (
            <Line {...lineConfig} />
          ) : (
            <Empty description={intl.formatMessage({ id: 'pages.callMetrics.empty', defaultMessage: 'No call inside this window' })} style={{ padding: '48px 0' }} />
          )}
        </Card>

        <Card
          title={
            <span style={{ fontSize: '14px', fontWeight: 600 }}>
              {intl.formatMessage({ id: 'pages.callMetrics.subjectTitle', defaultMessage: 'Per-subject detail' })}
            </span>
          }
          extra={
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              {tab === 'tool' && (
                <Select
                  value={toolKind}
                  onChange={setToolKind}
                  style={{ width: 150 }}
                  options={TOOL_TAB_KINDS.map((value) => ({
                    value,
                    label: intl.formatMessage({ id: `pages.callMetrics.kind.${value}`, defaultMessage: value }),
                  }))}
                />
              )}
              <Select
                value={groupBy}
                onChange={setGroupBy}
                style={{ width: 130 }}
                options={['tool', 'agent', 'session'].map((value) => ({
                  value,
                  label: intl.formatMessage({ id: `pages.callMetrics.groupBy.${value}`, defaultMessage: value }),
                }))}
              />
              <Select value={days} onChange={setDays} style={{ width: 130 }} options={windowOptions} />
            </div>
          }
          styles={{ body: { padding: '12px' } }}
        >
          {groupBy !== 'tool' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message={intl.formatMessage({ id: 'pages.callMetrics.detailHint', defaultMessage: 'The agent and session views read the detail table, so they only cover calls inside the retention window.' })}
            />
          )}
          <Table<API.CallMetricsRow>
            className="styled-pro-table"
            rowKey={(row) => `${row.kind}-${row.subjectKey}-${row.toolName}`}
            columns={subjectColumns}
            dataSource={summary?.rows ?? []}
            loading={loading}
            size="small"
            scroll={{ x: 'max-content' }}
            locale={{
              emptyText: <Empty description={intl.formatMessage({ id: 'pages.callMetrics.empty', defaultMessage: 'No call inside this window' })} />,
            }}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (t) => `${intl.formatMessage({ id: 'pages.common.total', defaultMessage: 'Total' })} ${t} ${intl.formatMessage({ id: 'pages.common.items', defaultMessage: 'items' })}`,
            }}
          />
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
            {intl.formatMessage({ id: 'pages.callMetrics.windowHint', defaultMessage: 'Counts cover the last {days} days. Success rate and P95 are window-wide figures, not per-day ones.' }, { days })}
          </Typography.Paragraph>
        </Card>
      </Spin>

      <Drawer
        width={720}
        open={!!subject}
        onClose={() => setSubject(null)}
        title={`${intl.formatMessage({ id: 'pages.callMetrics.detail.title', defaultMessage: 'Recent calls' })} — ${subject ? (groupBy === 'tool' ? subject.toolName : subject.subjectKey) : ''}`}
      >
        <Table<API.CallInvocationRow>
          className="styled-pro-table"
          rowKey="id"
          columns={detailColumns}
          dataSource={details}
          loading={detailLoading}
          size="small"
          pagination={{
            current: detailPage,
            pageSize: 20,
            total: detailTotal,
            showSizeChanger: false,
            onChange: (page) => subject && loadDetails(subject, page),
          }}
          expandable={{
            rowExpandable: (row) => !!(row.errorMessage || row.argsJson || row.resultExcerpt),
            expandedRowRender: (row) => (
              <Descriptions size="small" column={1} bordered>
                {row.errorMessage ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.errorMessage', defaultMessage: 'Failure reason' })}>
                    {row.errorMessage}
                  </Descriptions.Item>
                ) : null}
                {row.argsJson ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.args', defaultMessage: 'Input' })}>
                    <Typography.Paragraph copyable style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                      {row.argsJson}
                    </Typography.Paragraph>
                  </Descriptions.Item>
                ) : null}
                {row.resultExcerpt ? (
                  <Descriptions.Item label={intl.formatMessage({ id: 'pages.callMetrics.detail.result', defaultMessage: 'Result excerpt' })}>
                    <Typography.Paragraph style={{ marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                      {row.resultExcerpt}
                    </Typography.Paragraph>
                  </Descriptions.Item>
                ) : null}
              </Descriptions>
            ),
          }}
        />
      </Drawer>
    </PageContainer>
  );
};

export default CallMetrics;
```

三个形状上的取舍，改的人需要知道理由：

- **趋势固定三条线（调用量 / 成功 / 失败）而不是按主体分线**。接口一次返回整个窗口的 `points`，按 `subjectKey` 分线在一个工具有几十个的租户里会把图例挤成一团，而且 `time-series` 的补零是按 `(timePoint, kind, subject)` 全展开的，行数会盖过画布宽度。要看单个主体的分布，从主体表点进抽屉看明细。
- **`subjectId` 与 `subjectKey` 谁做谓词由 `kind` 和 `groupBy` 共同决定**，即 `loadDetails` 里那四行：`mcp` 用 `mcpId`、`cli` 用 `cliId`、按工具用 `toolName`、按智能体用 `agentId`、按会话用 `sessionId`。抽屉请求带 `kind: row.kind` 而不是当前 tab 的 kind，因为工具 tab 下的 `shell` 行仍要能查到 `kind=shell` 的明细。
- **明细表没有「按 agent 归属」那一列可点**。`agentId` 在行里是有的，但它指向的是归属智能体而非主体维度，与 `groupBy=agent` 的谓词等价，重复放一列只会让人以为是两个口径。

- [ ] **Step 7: 闸门**

两道闸门各跑一次并落日志，退出码单独看（`npm run lint` 会串 `tsc --noEmit`，本仓有一批既有噪声，不作闸门）：

```bash
cd harnax-webui
npm run build > /tmp/webui-build.log 2>&1; echo BUILD_EXIT=$?
npx @biomejs/biome lint src/pages src/locales > /tmp/webui-lint.log 2>&1; echo LINT_EXIT=$?
```

预期：两个 EXIT 都是 0。构建是这道题的真闸门——路由、组件路径、类型名任何一处拼错都在这里红，而 lint 只管风格。

再加一条文案自查：页面里引用的 key 必须两侧都定义过，缺一侧会在界面上渲染出裸 key。

```bash
used=$(grep -o "pages\.callMetrics\.[a-zA-Z0-9.]*" src/pages/call-metrics/index.tsx | sort -u)
for loc in zh-CN en-US; do
  defined=$(grep -o "'pages\.callMetrics\.[a-zA-Z0-9.]*'" src/locales/$loc/pages.ts | tr -d "'" | sort -u)
  comm -23 <(printf '%s\n' "$used") <(printf '%s\n' "$defined") | sed "s/^/MISSING[$loc] /"
done
grep -rho "'menu\.[a-z.]*call\.metrics'" src/locales/*/menu.ts
```

`MISSING[...]` 一律不该出现（模板串拼出来的 `outcome.${outcome}` 与 `window.${value}` 两类是动态 key，`grep -o` 取不到它们，靠上面两条 Select / Tag 里的字面 id 覆盖）。最后一行该打出两条——zh 与 en 各一——且 key 前缀与 Step 1 选的落点一致。

- [ ] **Step 8: 提交**

`git add` 只限这七个路径，一条都不许多（`src/pages/welcome/`、`Welcome.tsx`、`AvatarDropdown.tsx` 是同名的 Dashboard 轨现场，绝不在这一笔里）：

```bash
git add harnax-webui/config/routes.ts \
        harnax-webui/src/typings.d.ts \
        harnax-webui/src/services/ant-design-pro/toolMetrics.ts \
        harnax-webui/src/pages/call-metrics/index.tsx \
        harnax-webui/src/locales/zh-CN/menu.ts \
        harnax-webui/src/locales/en-US/menu.ts \
        harnax-webui/src/locales/zh-CN/pages.ts \
        harnax-webui/src/locales/en-US/pages.ts
git commit -m "feat(metrics): 调用监控页——工具/MCP/CLI 三个 tab 与明细抽屉"
```

---

### Task 13: 部署资产与存量文档收尾

**Files:**
- Modify: `harnax-deploy/docker-compose.yml`（admin 块 `:165` 之后、agent-service 块 `:556` 之后）
- Modify: `harnax-deploy/.env.example`（`:67` 的 `#HARNAX_MEMORY_TENANT_SCOPED=true` 之后）
- Modify: `docs/deploy-harnax-admin.md`（`## 环境变量说明` 的表 + `## 数据库初始化` 之末 + 新节）
- Modify: `docs/deploy-harnax-agent-service.md`（`### 其他开关` 的表，`:125`）
- Modify: 存量文档 10 份（清单见 Step 5）与 `prod_doc` 双语包 5 对（清单见 Step 6）

两份部署文档只有中文，没有 `.en-US` 孪生（`ls docs/*.en-US.md` 是空的），所以这一步只写中文；`prod_doc` 是中英成对的，Step 6 两侧都要动。

- [ ] **Step 1: compose 两处注入**

admin 块，在 `HARNAX_MEMORY_TENANT_SCOPED: ${HARNAX_MEMORY_TENANT_SCOPED:-true}`（`:165`）那一行之后插：

```yaml
      # Call-metrics retention: detail rows older than this are dropped once their day has been folded
      # into `tool_invocation_stats`, so the aggregate stays the answer for anything older. Lowering it does
      # not delete anything that has not been rolled up yet - the delete is conditioned on the day already
      # being in the aggregate.
      HARNAX_METRICS_RETENTION_DAYS: ${HARNAX_METRICS_RETENTION_DAYS:-90}
      # The hourly fold-up itself. Off means the aggregate stops growing while detail rows keep arriving,
      # so windows longer than the retention silently under-report; leave it on unless admin is not running.
      HARNAX_METRICS_ROLLUP_ENABLED: ${HARNAX_METRICS_ROLLUP_ENABLED:-true}
```

agent-service 块，在 `HARNAX_MCP_STDIO_ENABLED: ${HARNAX_MCP_STDIO_ENABLED:-false}`（`:556`）之后插：

```yaml
      # One row per tool call in `tool_invocation_log`, filed by the acting middleware. Off installs no
      # middleware at all. Bodies can carry literal secrets, so capture-payload off keeps every count and
      # duration while leaving args and result columns NULL.
      HARNESS_METRICS_INVOCATION_ENABLED: ${HARNESS_METRICS_INVOCATION_ENABLED:-true}
      HARNESS_METRICS_INVOCATION_CAPTURE_PAYLOAD: ${HARNESS_METRICS_INVOCATION_CAPTURE_PAYLOAD:-true}
```

队列容量、批量、刷新间隔、截断长度四项**不进 compose**：它们的默认值就写在 `application.yml` 里，属于调优参数而不是部署参数，`.env` 里也不给（给了会被当成必须配的东西）。

- [ ] **Step 2: `.env.example` 一节**

在 `:67` 的 `#HARNAX_MEMORY_TENANT_SCOPED=true` 之后插一节。`.env.example` 里注释用英文、正文用中文混排是这份文件现有的写法（`HARNAX_AES_SECRET_KEY` 一段全英文、记忆一段全中文），这里跟记忆段一致用中文：

```
# 调用指标（工具 / MCP / CLI 一次调用一行）。明细表只保留最近 N 天，更早的看日聚合，聚合永久保留。
# 改小这一项不会删掉还没折算的记录：删除语句以「那一天已在聚合里」为条件。
#HARNAX_METRICS_RETENTION_DAYS=90
# 每小时把过完的那天折进聚合表。关掉之后聚合表不再增长，超过保留窗口的口径会静默少报；
# 只有在 admin 根本不跑的部署里才需要关。
#HARNAX_METRICS_ROLLUP_ENABLED=true
# 每次工具调用落一行明细，由执行中间件记录；关掉等于不装这个中间件。
#HARNESS_METRICS_INVOCATION_ENABLED=true
# 入参和结果可能带明文凭据。只想要计数与耗时就关这一项：两列留 NULL，其余指标一个不少。
#HARNESS_METRICS_INVOCATION_CAPTURE_PAYLOAD=true
```

- [ ] **Step 3: `docs/deploy-harnax-admin.md`**

`## 环境变量说明` 的表里，在 `MINIO_CLI_PACKAGE_BUCKET` 那一行之后插两行：

```
| `HARNAX_METRICS_RETENTION_DAYS` | `90` | 调用明细（`tool_invocation_log`）的保留天数（配置项 `harnax.metrics.retention-days`）。**改小它不会立刻删掉任何东西**：清理以「那一天已折算进聚合」为前提，还没折的日子的行一律留着。只作用于明细，日聚合 `tool_invocation_stats` 永久保留，所以 90 天以外的口径仍然答得出（按工具维度） |
| `HARNAX_METRICS_ROLLUP_ENABLED` | `true` | 每小时折算任务的开关（配置项 `harnax.metrics.rollup-enabled`，`0 5 * * * ?`）。关掉聚合表就不再增长：明细满 90 天被清掉之后，长窗口的数字会静默偏小。**多副本同跑无害**，重算按天全量覆盖，见下文「调用指标折算」 |
```

在 `## CLI 插件包投放与升级` 一节之后（即 `## 健康检查` 之前）插入新节：

```markdown
## 调用指标折算

`harnax-agent-service` 把每一次工具调用（含 MCP 工具与经 shell 执行的已下发 CLI）写一行明细进 `tool_invocation_log`，本服务每小时把它折进日聚合 `tool_invocation_stats`：

- 任务在 `ToolInvocationRollupService`，`@Scheduled(cron = "0 5 * * * ?")`，由 `HarnaxAdminApplication` 上的 `@EnableScheduling` 启用。**这是本服务第一个定时任务**，之前全仓 admin 侧零 `@Scheduled`。
- 一次运行做三件事：把窗口内所有「聚合表里还没有的那天」整日重算（`upsertDay` 是全量覆盖，不是累加）；把今天重算一遍（今天的明细还在长）；清掉超出 `harnax.metrics.retention-days` 且**已折算**的明细。
- 待折算日期取自聚合表已有的 `stat_date`，起始下界写死 `1970-01-01` 而不由保留天数推：**用保留期当下界会让一个漏折的日子永久逃逸**——清理语句只删已折过的行，于是那些明细既删不掉也不再有人补它。
- 多副本同时跑是无害的：重算幂等（聚合 = 那一天明细的求和），`upsertDay` 走唯一键 `(stat_date, tenant_id, kind, subject_id, tool_name)`，两个副本写进同一批值。没有分布式锁，也不需要。
- 一次折算抛异常不影响下一次：`rollUpHourly()` 捕获并记 error，Spring 的调度线程不会因此退出。

页面在 `harnax-webui` 的「调用监控」（路径按 `config/routes.ts` 里的实际落点，与技能用量同一分组），三个 tab 对应工具 / MCP / CLI。

```

在 `## 数据库初始化` 一节末尾（「Flyway 会在服务启动时自动执行……无需手动导入 SQL。」之后）插一段：

```markdown
> **升级既有库时有一张表要手工处理**：调用记录换到 `tool_invocation_log` / `tool_invocation_stats` 两张新表之后，旧的 `tool_call_log` 不再有任何读写方。改基线只会让**新建的库**里没有它，已经在跑的库里那张表会留着占一个空壳——Flyway 基线被改过而不是叠加新版本，既有库不会因此重放。**要真清掉它得手工执行，这是一个不可回退的动作**：先确认不再需要那些历史行（`SELECT COUNT(*) FROM tool_call_log;`），再执行
>
> ```sql
> DROP TABLE tool_call_log;
> ```
>
> 留着不处理也没有副作用：没有代码引用它，它只是不再增长的一张空表。
```

- [ ] **Step 4: `docs/deploy-harnax-agent-service.md`**

`### 其他开关` 的表里，在 `HARNAX_MCP_STDIO_ENABLED` 那一行之后插四行（四项即可，队列容量等调优值写进末尾那句而不是单独成行）：

```
| `HARNESS_METRICS_INVOCATION_ENABLED` | `true` | 工具调用指标总开关（配置项 `harness.metrics.invocation.enabled`）。关掉**不装中间件**，`tool_invocation_log` 停止增长，页面三个 tab 从此没有新数据；不影响工具本身执行 |
| `HARNESS_METRICS_INVOCATION_CAPTURE_PAYLOAD` | `true` | 是否把入参与结果正文写进明细的 `args_json` / `result_excerpt`（配置项 `harness.metrics.invocation.capture-payload`）。这两列可能含明文凭据，只想要计数与耗时就关掉：两列留 NULL，成功率、P95、时长一个不少 |
| `HARNESS_METRICS_INVOCATION_CAPTURE_MAX_CHARS` | `2000` | 上述两列的截断长度。超限部分丢弃并带截断标记 |
| `HARNESS_METRICS_INVOCATION_BATCH_SIZE` / `_FLUSH_INTERVAL_MS` / `_QUEUE_CAPACITY` | `64` / `200` / `512` | 后台写线程的批量、刷新间隔与队列上限。队列满时丢事件并计数，不阻塞回合——指标记录不许拖慢对话 |
```

并在该表之后补一句归属说明：

```
这四项目前只在 `application.yml` 里有默认值，compose 只注入前两枚开关。明细行由 `harnax-admin` 侧的每小时任务折进日聚合，保留窗口与折算开关都在对面，见 `docs/deploy-harnax-admin.md` 的「调用指标折算」。
```

- [ ] **Step 5: 存量文档逐处替换（10 份）**

规则两条，先立住再动手：

1. **只写现状**，不许出现「原先写 `tool_call_log`，现改为……」「不再」「以前没有页面」这类演进对照——这些文档是现状说明，不是变更日志。
2. **按内容定位而不是按行号**：上一步之后同一文件里的行号会漂移。每条都用「命中句」那一列的原文去搜，搜不到就是已经改过或那份文档不在这一版里。

`grep -rn "tool_call_log\|ToolCallLog\|toolCallLog" --include=*.md docs prod_doc harnax-agent harnax-admin harnax-entity harnax-tools-external` 之外，还要扫符号名，因为有几处只写了类名：

```bash
grep -rn "SessionMetaContext\|ToolCallInfo\|ToolBox.execute" --include=*.md docs harnax-agent harnax-admin prod_doc
```

| 文件 | 命中句 | 改成 |
|---|---|---|
| `docs/architecture.md` | `- \`ToolCallLogAdaptorImpl\`: 工具调用日志` | `- \`ToolInvocationAdaptorImpl\`: 工具调用指标写入` |
| `docs/architecture.md` | `- ToolCallLogEntity: 工具调用日志实体` | 换成两行 `- ToolInvocationLog: 工具调用明细实体` 与 `- ToolInvocationStats: 工具调用日聚合实体`——这份清单列的就是 `harnax-entity` 的实体（同段还有 `ProcessLogEntity`、`TokenStats`），两个新实体归这里而不是删掉 |
| `docs/tools-sdk-architecture.md` | `\| \`ToolCallLogAdaptor\` \| \`sdk.adaptor\` \| 工具调用日志适配器接口 \|` | `\| \`ToolInvocationAdaptor\` \| \`sdk.adaptor\` \| 工具调用指标写入接口（\`fun interface\`，\`emit(ToolInvocationEvent)\`） \|` |
| `docs/harnax-harness-core.md` | `│   │   ├── ToolCallLogAdaptor.kt             # 工具调用日志适配器接口` | 同一棵树里换成 `ToolInvocationAdaptor.kt`，注释「工具调用指标写入接口」 |
| `docs/database-design-conventions.md` | 两处 `` `process_log`、`tool_call_log`、`token_stats` `` | 把 `tool_call_log` 换成 `tool_invocation_log`，并在同一条的 `_stats` 例子里补 `tool_invocation_stats`（那句正是在讲「日志/统计表用 `_log` / `_stats` 后缀」） |
| `docs/session-classification-design.md` | 讲 `harnax-agent-service` 主代码不出现某些 Mapper 的那一段里的 `tool_call_log` | 该表名换成 `tool_invocation_log`；论证本身不动 |
| `docs/backend-code-conventions.md` | 租户谓词豁免清单里的 `tool_call_log` | 换成 `tool_invocation_log`，并**加一句**：`tool_invocation_stats` 的 `tenant_id` 是 `NOT NULL`，`tenant_id IS NULL` 的明细行不进聚合，因此聚合侧不需要豁免（这是两张表唯一一处形状不同带来的后果） |
| `docs/specs/harnax-it-spec.md` | `AGT-02 | agent-service | chat 请求经 Router 转发后由 agent-service 调 Mock LLM，ToolCallLog/ProcessLog 落库 | P1 |` | 把 `ToolCallLog` 换成 `ToolInvocationLog`，这条验收项的判据本身不变 |
| `harnax-agent/HARNESS_CORE_DOC.md` | 五处：目录树 `ToolCallLogAdaptor.kt`、参数表 `toolCallLogAdaptor`、SPI 表 `ToolCallLogAdaptor \| emit(info)`、`ToolBox` 小节的两条（持有 `init()` 注入的运行时上下文 / `execute()` 包裹并按成败写 `tool_call_log`） | 前两处换成 `ToolInvocationAdaptor`；SPI 表那行换成 `\| \`ToolInvocationAdaptor\` \| \`emit(event)\` \| 提交一次工具调用指标 \|`；`ToolBox` 小节那两条整段删掉——`ToolBox` 现在只剩 `abstract fun name(): String`，运行时上下文与包裹执行都不存在了，记录源是中间件，改指向本模块的 `ToolInvocationMiddleware` 小节 |
| `harnax-agent/harnax-tools-sdk/TOOL-DEV-GUIDE.md` | 目录树 `ToolCallLogAdaptor.kt   # 调用日志出口（含 ToolCallInfo）` 与任何 `execute { }` 示例 | 树里换成 `ToolInvocationAdaptor.kt`；示例改成**直接 return** 的写法，与 Task 9 Step 7 替换后的 `ToolBox` 一致。这份文档是给写新工具的人照抄的，留一个 `execute { }` 示例就会造出编译不过的样板 |
| `harnax-admin/TOOL_INTEGRATION_DESIGN.md` | §2.4 整节、表里 `tool_call_log` 那一行、`未初始化的实例跳过记录…` 段、`tool_call_log 只有写入：Mapper 接口声明唯一方法 insert` 段、`ToolCallLogMapper.kt:13-` 那处引用、末尾清单里 `\`tool_call_log\` 保留不删 \| Mapper 只有 \`insert\`…` 一行 | §2.4 整节替换成下面给出的正文；表行换成两张新表；「未初始化的实例跳过记录」那一段连同它引用的 `:90-93`、`:127-130` 一起删（那是 `ToolBox` 内部的事，现在没有这个类分支）；末尾清单那一行删掉——「保留不删」这条裁定已经被推翻了 |

`harnax-admin/TOOL_INTEGRATION_DESIGN.md` §2.4 的正文（中英两份内容相同，这份文档只有中文）：

```markdown
### 2.4 `tool_invocation_log` / `tool_invocation_stats`：一次工具调用

`harnax-agent-service` 的执行中间件 `ToolInvocationMiddleware` 在一次工具调用收尾时写一行明细进 `tool_invocation_log`：`kind` 标出来源（`builtin` 下发的工具 / `mcp` MCP 服务的工具 / `cli` 经 shell 执行的已下发 CLI 包 / `shell` 裸 shell 命令 / `framework` 运行时自带），`tool_name` 是模型看到的那个名字（`kind=cli` 时是命中的命令名），`mcp_id` / `cli_id` 只在对应来源上填。`outcome` 只取四个终态 `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`；`start_time` 与 `ts` 是 `datetime(3)`，秒级精度会把同一秒里的两次调用塌成一瞬。

写入经 `ToolInvocationAdaptor`（tools-sdk 的 `fun interface`）到 `harnax-agent-service` 的 `ToolInvocationAdaptorImpl`，那是一个带界队列的后台批量写线程：队列满即丢弃并计数，不阻塞回合。

`harnax-admin` 每小时把过完的那天折进日聚合 `tool_invocation_stats`（唯一键 `(stat_date, tenant_id, kind, subject_id, tool_name)`，六个耗时桶 + 四终态计数 + `sum_duration_ms`），明细按保留天数清理，聚合永久保留。

读侧是三个 GET：`/api/admin/tool-metrics/summary`、`/time-series`、`/invocations`，租户一律取自令牌而不是查询参数；按工具维度读聚合表，按智能体 / 会话维度读明细表，后者受保留窗口限制。`mcp_call_log` 不在这条链上，它是 MCP 按用户授权的账本，不是指标源。
```

- [ ] **Step 6: `prod_doc` 双语包（5 对）**

中英成对，**两份都要改**，改完两份的命中数必须一样。逐对处理：

**`prod_doc/tool-capability.{zh-CN,en-US}.md`**（15 处，两份各 15）：

| 命中句 | 改成 |
|---|---|
| 模块表 `harnax-agent-service` 行里的 `ToolCallLogAdaptorImpl` | `ToolInvocationAdaptorImpl` |
| `abstract fun name(): String`：组名，用作 `tool_call_log.tool_name` 的前缀，以及 `ToolMetaDescriptor.toolName` | 去掉前半个用途：组名，用作 `ToolMetaDescriptor.toolName` |
| `init(toolCallLogAdaptor, sessionMetaContext, userIdentifier)` 那条 | `init(userIdentifier)`：装配侧在把实例交给 `Toolkit` 前调用 |
| §3.6 SPI 表 `ToolCallLogAdaptor`（`fun interface`，`emit(ToolCallInfo)`）那整行 | `ToolInvocationAdaptor`（`fun interface`，`emit(ToolInvocationEvent)`）\| tools-sdk `adaptor` 包 \| `harnax-agent-service` 的 `ToolInvocationAdaptorImpl`，带界队列 + 批量落 `tool_invocation_log` \| 把一次调用记成指标行 |
| §3.6 之后那句「harness 侧的 `ToolCallLogAdaptor` 由 `HarnessAutoConfiguration` 通过 `ObjectProvider` 注入，缺省时用空实现——纯嵌入式跑 harness 时调用记录自然消失」 | 换成：`ToolInvocationAdaptor` 同样由 `HarnessAutoConfiguration` 经 `ObjectProvider` 注入，且受 `harness.metrics.invocation.enabled` 控制；两者任一无值都不装中间件，纯嵌入式跑 harness 时调用记录自然消失 |
| §3.7 `SessionMetaContext(agentId: Long?, sessionId, tenantId: Long? = null)`、`UserIdentifier(...)` 那条 | 只留 `UserIdentifier(userId: Long? = null)`，它实现空标记接口 `ToolCallContext`；`SessionMetaContext` 这个类型不存在了 |
| §6.7 调用日志整节 | 换成：调用记录不由 `ToolBox` 产出。`ToolInvocationMiddleware` 在 `TOOL_RESULT_END` 收尾时组一行 `ToolInvocationEvent`（来源 `kind`、`tool_name`、终态 `outcome`、起止毫秒、截断后的入参与结果），交 `ToolInvocationAdaptor`；正文两列可由 `capture-payload` 整体关闭，队列满则丢弃并计数 |
| §7.4 `tool_call_log` 小节 | 换成下面给出的两小节正文（合在一个编号下，避免 §7.5 重编号） |
| 步骤 8「看 `tool_call_log` 是否出现 `组名::方法名` 的行」 | 看「调用监控」页的工具 tab 是否出现这个工具名 |
| 常见陷阱「调用日志只写不读：平台没有工具调用统计页面，`tool_call_log` 需要直连数据库查」 | 整条删除（这一条已经不成立） |
| 落点表 SPI / SPI 实现 / 实体与 Mapper / DDL 四行里的旧名 | 分别换成 `ToolInvocationAdaptor.kt`、`ToolInvocationAdaptorImpl.kt`、`ToolInvocationLog.kt` 与 `ToolInvocationStats.kt` 及其 mapper、DDL 括号里的表名换成两张新表 |

§7.4 的新正文（中文，英文那份直译同结构）：

```markdown
### 7.4 tool_invocation_log 与 tool_invocation_stats

明细表一次调用一行，保留 `harnax.metrics.retention-days` 天：`tenant_id`（可空，spec 未命名归属即 NULL）、`agent_id`（可空，团队主管无 `agent` 行）、`session_id`、`user_id`、`kind`、`tool_name`、`mcp_id` / `cli_id`（只在对应来源上填）、`outcome`、`error_message`、`args_json` / `result_excerpt`（正文可整体关闭）、`duration_ms`、`start_time` / `end_time` / `ts`（三者都是 `datetime(3)`）。索引按「租户 + 时间」「租户 + 来源 + 时间」「mcp_id + 时间」「cli_id + 时间」「session」「tool_name」六条铺。

日聚合永久保留，唯一键 `(stat_date, tenant_id, kind, subject_id, tool_name)`：`subject_id` 在 `kind=mcp` 时是 MCP 服务行、`kind=cli` 时是 CLI 包行、其余为 `0`（不能留 NULL，唯一索引不把 NULL 视为相等，NULL 会让同一天插进两行）；计数列 `calls` / 四终态 / `sum_duration_ms` / `max_duration_ms`；六个**半开区间**的耗时桶（`le_100ms`、`le_500ms` = `(100,500]`、`le_2s`、`le_10s`、`le_30s`、`gt_30s`），闭右开左是为了让「正好 500ms」只被一个桶认领，桶和与 `calls` 才恒等。

一条不变量决定了两张表的读法：`tenant_id` 在聚合表上是 `NOT NULL`，所以无归属的明细行不进任何聚合，它们只看保留窗口。按工具统计读聚合（可答超过保留期），按智能体 / 会话统计读明细（受保留窗口限制）。
```

**`prod_doc/tool-integration-design.{zh-CN,en-US}.md`**（11 处，两份各 11）：同一份表格逐条套用——数据流表首行（`tool_call_log` / 一次工具调用 / `ToolCallLogAdaptorImpl` / 无读 / 只保留）换成两张新表并写明「读侧三个 GET」；`agent_id` 与 `tenant_id` 都可空那条保留但表名换；契约层那行删掉 `SessionMetaContext` 与 `ToolCallLogAdaptor`、加上 `ToolInvocationAdaptor`；存储层那行 `ToolCallLogEntity` 换成两个新实体；`ToolCallLogMapper 只有 insert` 那段整段换成新链路的写入与折算描述；落点表三行同 Step 5 的换法。

**`prod_doc/product-overview.{zh-CN,en-US}.md`**（4 处）：能力清单里「工具调用有日志但无统计」这类描述改成「调用指标页：工具 / MCP / CLI 的调用量、成功率、P95 与明细」；落库形状那行的表名换成新表；其余两处按命中句就地换名，不动论述。

**`prod_doc/skill-management.{zh-CN,en-US}.md`**（各 1 处）：那句讲 `VIEW` 是唯一有写入方的事件——USE 现在有写入方了，改成「`VIEW` 有 60 秒冷却（每次组装系统提示都会重读仓库），`USE` 没有冷却（一次装载即一次 USE）」，并说明 `USE` 由 `ToolInvocationMiddleware` 在 `load_skill_through_path` 成功装载 `SKILL.md` 时上报。

**`prod_doc/multi-agent-team-design.{zh-CN,en-US}.md`**（各 1 处）：命中句讲团队运行归属取自 `attributableAgentId` 并提到旧表名，就地换成 `tool_invocation_log`；归属论证一字不动。

**不动的三份**（有意不改，不是遗漏）：`prod_doc/self-improving-implementation.zh-CN.md`（另一条在途轨道的未跟踪文件）、`docs/superpowers/specs/2026-09-20-team-own-lead-config-design.md`（历史设计稿，定稿即冻结）、`prod_doc/tool-mcp-cli-call-metrics-design.zh-CN.md` 与 `docs/superpowers/{specs,plans}/2026-10-05-*`（本方案自身，删除清单必须留着才说得清删了什么）。

- [ ] **Step 7: 残余闸门**

```bash
grep -rn "tool_call_log\|ToolCallLog\|toolCallLog" --include=*.md docs prod_doc harnax-agent harnax-admin harnax-entity harnax-tools-external \
  | grep -v "prod_doc/tool-mcp-cli-call-metrics-design.zh-CN.md\|docs/superpowers/specs/2026-10-05\|docs/superpowers/plans/2026-10-05\|prod_doc/self-improving-implementation\|docs/superpowers/specs/2026-09-20"
grep -rn "SessionMetaContext\|ToolCallInfo" --include=*.md --include=*.kt . 2>/dev/null \
  | grep -v "docs/superpowers\|prod_doc/tool-mcp-cli-call-metrics-design"
grep -rn "tool_call_log" --include=*.sql --include=*.xml --include=*.kt . 2>/dev/null \
  | grep -v "docs/superpowers\|prod_doc/tool-mcp-cli-call-metrics-design"
```

三条都必须**零输出**。前两条管文档还有没有旧名，第三条管代码与 schema 里是不是真清干净了（Task 9 若漏了一处，这条会在跑门禁之前就红，比编译报错更早）。

代码侧的补扫（`ToolBox.execute` 的残留调用会编不过，但先把文档里的示例改掉能省一轮反查）：

```bash
grep -rn "execute {" --include=*.kt harnax-tools-external harnax-agent | grep -i toolbox
```

预期零命中：`ToolBox` 已没有 `execute`。

- [ ] **Step 8: 提交**

```bash
git add harnax-deploy/docker-compose.yml harnax-deploy/.env.example \
        docs/deploy-harnax-admin.md docs/deploy-harnax-agent-service.md \
        docs/architecture.md docs/tools-sdk-architecture.md docs/harnax-harness-core.md \
        docs/database-design-conventions.md docs/backend-code-conventions.md \
        docs/session-classification-design.md docs/specs/harnax-it-spec.md \
        harnax-agent/HARNESS_CORE_DOC.md harnax-agent/harnax-tools-sdk/TOOL-DEV-GUIDE.md \
        harnax-admin/TOOL_INTEGRATION_DESIGN.md harnax-agent/harnax-agent-service/docs/conversation-flow.md \
        prod_doc/tool-capability.zh-CN.md prod_doc/tool-capability.en-US.md \
        prod_doc/tool-integration-design.zh-CN.md prod_doc/tool-integration-design.en-US.md \
        prod_doc/product-overview.zh-CN.md prod_doc/product-overview.en-US.md \
        prod_doc/skill-management.zh-CN.md prod_doc/skill-management.en-US.md \
        prod_doc/multi-agent-team-design.zh-CN.md prod_doc/multi-agent-team-design.en-US.md
git commit -m "docs(metrics): 部署文档、compose 环境变量与存量文档的调用指标改口"
```

`harnax-deploy/.env`（真值文件，非 `.example`）不在 `git add` 里，`data/` 目录同样不提交。

---
