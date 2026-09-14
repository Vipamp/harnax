# 定时任务集群化（发布 1 / S2）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `harnax-scheduler` 的 Quartz 从每节点内存 store 换成真正的 JDBC 集群 store，并用 diff 收敛的对账取代"全删重建"，使 docker-compose 可以固定跑 2 个 scheduler 实例而不漂移。

**Architecture：** 三个新协作类把调度写面从 `SchedulerServiceImpl` 里剥出来——`TaskQuartzRegistrar`（一个任务如何进/出 store）、`TaskScheduleReconciler`（库里应有 vs store 现有的 diff）、`SchedulerReconcileJob`（60 秒集群单例对账）。job 触发时不再从 JobDataMap 取实体，只取 `taskId` 并回查数据库。admin 侧只剩一次转发（共享 store 后 broadcast 失去意义）。

**Tech Stack：** Kotlin 2.2.20、Spring Boot 4.0.1（`spring-boot-quartz`）、Quartz 2.5.2、MyBatis XML、MySQL 8、Flyway、Testcontainers 1.21.4。

## 里程碑修正（相对 spec §7，必须先读）

spec 把 S2 标为"可独立发布"，其中包含"6.1 建库 + D8 迁 `agent_task`"。这一条不成立：**只要 admin 还在写 `agent_task`，这张表就不能搬到另一个库**——两份副本会立刻分叉。所以本发布只做到"QRTZ 层独立"，业务表随发布 2（域搬迁）一起走。

因此本发布里 Flyway 建的历史表叫 `flyway_schema_history_scheduler`、但数据源仍是 `harnax_admin`，即 **`QRTZ_*` 临时落在 admin 库里**。这是有意的中间态：

- Quartz 的行是**可再生数据**（reconcile 按 `agent_task` 重建全部 job），所以发布 2 切库时不需要迁移 `QRTZ_*`，只需在新库重新建表 + 跑一次 reconcile；
- 发布 2 的上线文档负责写明"旧 `harnax_admin.QRTZ_*` 观察期后由运维 DROP"。

D3（scheduler 独占 schema）在发布 2 结束时完整成立，本发布不破坏它。

## Global Constraints

- 注释语言：本域（scheduler / admin / router）一律**英文注释**；文档中文。
- 不新增依赖版本：Quartz/Flyway/Testcontainers/PageHelper 全部沿用根 pom 与 `harnax-admin/pom.xml` 已有版本；`pagehelper-spring-boot-starter` 版本 `2.1.0`（含对 `mybatis-spring-boot-starter` 的 exclusion，抄 admin）。
- `org.quartz.jobStore.class` **不得显式配置**（Boot 会用 `LocalDataSourceJobStore` 覆盖并接自己的数据源；写死 `JobStoreTX` 会连不上 Spring 管理的数据源）。
- `waitForJobsToCompleteOnShutdown` 用 Boot 标准属性 `spring.quartz.wait-for-jobs-to-complete-on-shutdown`，保持现状 `${QUARTZ_WAIT_FOR_JOBS:true}`，不要改用 `SchedulerFactoryBeanCustomizer`。
- `threadCount` 保持 `${QUARTZ_THREAD_COUNT:10}`（spec 3.4 明确不因集群而放大下游压力）。
- JobDataMap 在 `useProperties: true` 下**只能放字符串**，放 Long 会抛 `ObjectNotSupportedException`。
- 每次改完 Kotlin/XML/yml 必须 `mvn -q spotless:apply` 再编译，否则以 format violations 失败。
- 本机验证命令（无全局 mvn、无 Docker）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
# 只跑 scheduler 及其依赖
$MVN -o -pl harnax-scheduler -am test -Dtest='!com.agnetix.harnax.mapper.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > s2.log 2>&1; echo "EXIT=$?"; grep -E "BUILD|Tests run" s2.log | tail -5
# 收尾必须全 reactor（注意：不要 `| tail`，会吞掉退出码）
$MVN -o clean test -Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**' \
  -Dsurefire.failIfNoSpecifiedTests=false > verify.log 2>&1; echo "EXIT=$?"
```

- **本机 Docker 不可用**（`docker info` 连不上 socket）。本发布的 `*IT` 必须写成 failsafe + `-Pintegration-test` 形态，默认 reactor 跑不到它们，因此"IT 全绿"不是本发布的验收条件；交付条件是**编译通过 + 单测通过 + IT 代码就位**。不得声称 IT 已运行。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `docker-new/sql/init-databases.sql` | Modify | 预建 `harnax_scheduler` 库并授权（发布 2 用） |
| `harnax-scheduler/src/main/resources/application.yml` | Modify | Flyway 自有历史表 + Quartz JDBC 集群属性 |
| `harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql` | Create | 官方 11 张 `QRTZ_*`，去 DROP、补注释与字符集 |
| `harnax-scheduler/src/main/kotlin/.../scheduler/job/TaskQuartzRegistrar.kt` | Create | 单个任务 ↔ store 的唯一写入口 |
| `.../scheduler/service/impl/SchedulerServiceImpl.kt` | Modify | `scheduleTask`/`unscheduleTask` 委托 registrar；启动加载改走 reconciler |
| `.../scheduler/service/TaskScheduleReconciler.kt` | Create | diff 收敛：应有 vs 现有 |
| `.../scheduler/job/SchedulerReconcileJob.kt` | Create | 60s 对账（集群单例） |
| `.../scheduler/job/AbstractAgentTaskJob.kt` | Modify | fire 时按 taskId 回查 + 消失自愈 |
| `.../scheduler/health/QuartzJobInventory.kt` | Modify | 暴露 store 现状快照供 diff；内存 store 语义改名 |
| `.../scheduler/health/SchedulerStatus.kt` | Modify | load 口径 → reconcile 口径 |
| `.../scheduler/health/SchedulerHealthIndicator.kt` | Modify | 健康改按对账结果 + 报 store 类型 |
| `.../scheduler/metrics/SchedulerMetrics.kt` | Modify | `scheduler.reconcile.drift{action}` |
| `harnax-admin/.../service/impl/SchedulerClientImpl.kt` | Modify | broadcast → 单点转发 |
| `docker-new/docker-compose.yml` | Modify | 去 `container_name`、双实例、scale 说明 |
| `docker-new/deploy-service.sh` | Modify | scheduler 逐台滚动 |
| `harnax-scheduler/pom.xml` | Modify | surefire 排除 `*IT` + failsafe + `-Pintegration-test` |
| `harnax-scheduler/src/test/.../it/**` | Create | 容器基类 + IT-2 + IT-5 |

---

### Task 1: 预建 `harnax_scheduler` 库与授权

**Files:**
- Modify: `docker-new/sql/init-databases.sql`

**Interfaces:**
- Produces: 数据库 `harnax_scheduler`（本发布不连，发布 2 的数据源目标）。

- [ ] **Step 1: 加库与授权**

在 `harnax_router` 段之后插入：

```sql
-- Scheduler service database (Quartz cluster store + agent task domain, Flyway managed by scheduler).
-- The Quartz tables land here in release 2: release 1 keeps them in harnax_admin because admin is still
-- the only writer of agent_task, and a table cannot live in two databases while two services write it.
CREATE DATABASE IF NOT EXISTS `harnax_scheduler`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
```

并在 GRANT 段追加一行（与其他四库同构）：

```sql
GRANT ALL PRIVILEGES ON `harnax_scheduler`.* TO 'harnax'@'%';
```

同时把文件头 "Database Architecture" 注释补一行：
`--   harnax_scheduler - Scheduler (Quartz cluster store + agent task domain, Flyway managed)`

- [ ] **Step 2: 校验语法自洽**

```bash
cd /Users/heqingsong/code/my_project/harnax/.worktrees/scheduler-cluster-cutover
grep -c "CREATE DATABASE" docker-new/sql/init-databases.sql   # 期望 5
grep -c "GRANT ALL PRIVILEGES" docker-new/sql/init-databases.sql  # 期望 5
```

- [ ] **Step 3: Commit**

```bash
git add docker-new/sql/init-databases.sql
git commit -m "chore(部署): 预建 harnax_scheduler 库并授权（scheduler 独占 schema）"
```

---

### Task 2: Flyway 自有历史表 + `QRTZ_*` 建表 + JDBC 集群配置

**Files:**
- Create: `harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql`
- Modify: `harnax-scheduler/src/main/resources/application.yml`
- Test: `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/config/SchedulerQuartzConfigTest.kt`（Create）

**Interfaces:**
- Produces: store 类型由 `spring.quartz.job-store-type` 决定；`SchedulerStatus.schedulerEnabled` 不变；`AbstractAgentTaskJob` 仍能用现有 JobDataMap 读到实体（Task 3 改）。

- [ ] **Step 1: 写失败的配置测试**

创建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/config/SchedulerQuartzConfigTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

/**
 * Pins the cluster wiring as data rather than as an argument in a review: `job-store-type`, the Flyway
 * history table name, and the absence of an explicit `jobStore.class` are each a failure at boot if they
 * drift, and none of them are visible in a unit test that mocks the scheduler.
 */
class SchedulerQuartzConfigTest {

    private val props: java.util.Properties = org.springframework.beans.factory.config
        .YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }
        .also { it.afterPropertiesSet() }
        .getObject()!!

    private fun value(key: String): String? = props.getProperty("spring.$key")

    @Test
    fun `the job store is jdbc by default so a cluster shares one store`() {
        // The placeholder form is `${QUARTZ_JOB_STORE:jdbc}` — the default after the colon is the claim.
        assertTrue(value("quartz.job-store-type")!!.endsWith(":jdbc"), value("quartz.job-store-type"))
    }

    @Test
    fun `Spring is not told which jobStore class to use`() {
        // Boot must get to install LocalDataSourceJobStore itself; a hard-coded JobStoreTX disconnects
        // the Spring-managed DataSource and the cluster fails to check in.
        assertNull(
            props.propertyNames().toList().filterIsInstance<String>()
                .firstOrNull { it.endsWith("quartz.jobStore.class") },
        )
    }

    @Test
    fun `clustering is on with a check-in well inside the takeover window`() {
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.isClustered"))
        assertEquals("15000", value("quartz.properties.org.quartz.jobStore.clusterCheckinInterval"))
        assertEquals("60000", value("quartz.properties.org.quartz.jobStore.misfireThreshold"))
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.acquireTriggersWithinLock"))
    }

    @Test
    fun `job data maps are restricted to strings, which is why they carry ids not entities`() {
        assertEquals("true", value("quartz.properties.org.quartz.jobStore.useProperties"))
    }

    @Test
    fun `flyway owns the quartz schema and never lets Spring run its script on a restart`() {
        // YamlPropertiesFactoryBean does not resolve placeholders, so these assertions read the default
        // after the colon rather than a bound value.
        assertTrue(value("flyway.enabled")!!.endsWith(":true}"), value("flyway.enabled"))
        assertEquals("flyway_schema_history_scheduler", value("flyway.table"))
        assertEquals("never", value("quartz.jdbc.initialize-schema"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-scheduler -am test -Dtest=SchedulerQuartzConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
期望：编译失败或断言 FAIL（`application.yml` 当前是 `:memory`，且 `spring.flyway.enabled` 默认 `false`）。

- [ ] **Step 3: 生成 V1 Quartz DDL**

以 jar 内的官方脚本为唯一来源，避免手抄出错：

```bash
QJ=$(find ~/.m2/repository/org/quartz-scheduler -name "quartz-2.5.2.jar" | head -1)
unzip -p "$QJ" org/quartz/impl/jdbcjobstore/tables_mysql_innodb.sql > /tmp/qz.sql
```

以 `/tmp/qz.sql` 为底本写入 `harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql`，并**只做以下四处改动**：

1. 删掉文件头到第一个 `CREATE TABLE` 之前的全部内容（含 11 行 `DROP TABLE IF EXISTS` 与注释）——Flyway 迁移永不 DROP；
2. 每个 `ENGINE=InnoDB;` 改为 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='…';`，11 张表的 COMMENT 依次为：
   - `QRTZ_JOB_DETAILS` = 'Quartz job definitions (cluster-shared store)'
   - `QRTZ_TRIGGERS` = 'Quartz triggers (cluster-shared store)'
   - `QRTZ_SIMPLE_TRIGGERS` = 'Quartz simple trigger extensions'
   - `QRTZ_CRON_TRIGGERS` = 'Quartz cron trigger extensions'
   - `QRTZ_SIMPROP_TRIGGERS` = 'Quartz calendar-interval trigger extensions'
   - `QRTZ_BLOB_TRIGGERS` = 'Quartz blob trigger extensions'
   - `QRTZ_CALENDARS` = 'Quartz calendars'
   - `QRTZ_PAUSED_TRIGGER_GRPS` = 'Quartz paused trigger groups'
   - `QRTZ_FIRED_TRIGGERS` = 'Quartz fired triggers; also the cluster liveness evidence'
   - `QRTZ_SCHEDULER_STATE` = 'Quartz scheduler state; one row per cluster node'
   - `QRTZ_LOCKS` = 'Quartz row locks used for clustered trigger acquisition'
3. 文件最前面加迁移说明注释（英文），写明：为什么 `never` + Flyway（Boot 的 `initialize-schema: always` 会每次启动跑官方脚本，而那个脚本以 `DROP TABLE IF EXISTS` 开头，在集群上等于每次重启清空调度状态），以及 `SCHED_NAME` 必须两实例一致（`instanceName: HarnaxScheduler`）；
4. 删掉文件末尾的 `commit;`（Flyway 自己管事务）。

不改列定义、不改索引、不改表名大小写。改完跑一次校验：

```bash
grep -c "^CREATE TABLE" harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql  # 期望 11
grep -c "DROP TABLE" harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql     # 期望 0
grep -c "DEFAULT CHARSET=utf8mb4" harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql  # 期望 11
grep -c "^CREATE INDEX" harnax-scheduler/src/main/resources/db/migration/V1__quartz_tables.sql  # 期望 18
```

- [ ] **Step 4: 改 `application.yml`**

`spring.flyway` 段整段替换：

```yaml
  # Flyway owns the Quartz cluster schema. A dedicated history table because this service's migrations
  # currently land in harnax_admin, whose own history belongs to admin's Flyway: two tools, two ledgers,
  # one database. `baseline-on-migrate` stays off on purpose — the history table is new, so V1 applies
  # clean, and baselining would let a half-migrated schema pass as complete.
  flyway:
    enabled: ${FLYWAY_ENABLED:true}
    locations: classpath:db/migration
    table: flyway_schema_history_scheduler
    validate-on-migrate: true
    clean-disabled: true
```

`spring.quartz` 段替换（保留原有那段关于 `wait-for-jobs-to-complete-on-shutdown` 的注释，它仍然正确，只需把"store 还是 RAM"的措辞改掉）：

```yaml
  quartz:
    job-store-type: ${QUARTZ_JOB_STORE:jdbc}
    # `never`, and there is no setting to argue with: Boot's `always` runs the bundled Quartz script on
    # every start, and that script begins with DROP TABLE IF EXISTS — on a cluster that deletes the
    # schedule state every node shares each time one node restarts. V1__quartz_tables.sql creates it once.
    jdbc:
      initialize-schema: never
    wait-for-jobs-to-complete-on-shutdown: ${QUARTZ_WAIT_FOR_JOBS:true}
    properties:
      org:
        quartz:
          scheduler:
            instanceName: HarnaxScheduler
            instanceId: AUTO
          jobStore:
            # No `class` here on purpose: Boot installs LocalDataSourceJobStore so the store uses the
            # Spring-managed DataSource. Pinning org.quartz.impl.jdbcjobstore.JobStoreTX disconnects it.
            driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
            tablePrefix: QRTZ_
            isClustered: "true"
            # A dead node's triggers go to the survivors one check-in window late: 15s here, so the
            # takeover lands inside the 15~75s the runbook promises.
            clusterCheckinInterval: 15000
            misfireThreshold: 60000
            # With clustering there is no local lock protecting trigger acquisition; without this two
            # nodes can each claim the same fire and one of them loses after doing the work.
            acquireTriggersWithinLock: "true"
            # JobDataMap values must be strings — which is exactly why it carries a taskId and the job
            # re-reads agent_task when it fires. A serialized AgentTask would also make every stored job
            # carry a stale copy of the prompt.
            useProperties: "true"
          threadPool:
            class: org.quartz.simpl.SimpleThreadPool
            threadCount: ${QUARTZ_THREAD_COUNT:10}
            threadPriority: 5
```

`spring.datasource.hikari.maximum-pool-size` 改为 `${DB_POOL_SIZE:30}`，并在其上方注释里写明算式：`30 ≥ threadCount(10) 个 Quartz worker 各占一条连接 + 业务查询与集群 check-in；QUARTZ_THREAD_COUNT 上调时必须同步上调`。

- [ ] **Step 5: 跑测试确认通过**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler -am test -Dtest=SchedulerQuartzConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
期望：PASS（5 个用例）。若 `YamlPropertiesFactoryBean` 不解析带 `${}` 的值，改用断言"字符串以 `:jdbc` 结尾"的形式（已按此写）。

- [ ] **Step 6: 全模块编译 + 既有测试不回归**

```bash
$MVN -o -pl harnax-scheduler -am test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > t2.log 2>&1; echo EXIT=$?; grep -E "BUILD|Tests run:" t2.log | tail -3
```
期望：EXIT=0。

- [ ] **Step 7: Commit**

```bash
git add harnax-scheduler/src/main/resources harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/config
git commit -m "feat(调度): Quartz 切 JDBC 集群 store，Flyway 建 QRTZ 表"
```

---

### Task 3: `TaskQuartzRegistrar` + JobDataMap 只放 taskId + fire 时回查

**Files:**
- Create: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/job/TaskQuartzRegistrar.kt`
- Modify: `.../service/impl/SchedulerServiceImpl.kt:200-243`（`scheduleTask`/`unscheduleTask`）、`:318-344`（`runTaskOnce`）、`:89-91`（context 注册）
- Modify: `.../job/AbstractAgentTaskJob.kt:35-41`
- Test: `.../job/TaskQuartzRegistrarTest.kt`、Modify `.../job/AgentTaskJobExecutionTest.kt`

**Interfaces:**
- Produces:
  - `TaskQuartzRegistrar.register(task: AgentTask)`、`.unregister(taskId: Long)`、`.jobKeyOf(taskId: Long): JobKey`、`.triggerKeyOf(taskId: Long): TriggerKey`、`.GROUP_AGENT_TASK: String`、`.KEY_TASK_ID: String`、`.taskIdOf(jobKey: JobKey): Long?`
  - JobDataMap 契约：`register` 与 `runTaskOnce` 写入的 map 只含 `KEY_TASK_ID`（String）。
- Consumes: `SchedulerFactoryBean`（既有注入方式）。

- [ ] **Step 1: 写失败的测试（store 里不再有实体，只有 id）**

`harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/job/TaskQuartzRegistrarTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import com.agnetix.harnax.scheduler.service.impl.SchedulerServiceImpl

/**
 * `useProperties: true` makes a non-string JobDataMap value a hard Quartz error, so the store can only
 * ever hold a taskId — and the corollary that matters is that a task's prompt/cron changes take effect on
 * the next fire without re-registering, because the job re-reads the row.
 */
class TaskQuartzRegistrarTest {

    private val quartz: Scheduler = mock(Scheduler::class.java)
    private val factory: SchedulerFactoryBean = mock(SchedulerFactoryBean::class.java).apply {
        doReturn(quartz).`when`(this).scheduler
    }
    private val registrar = TaskQuartzRegistrar(factory)

    private fun task(id: Long) = AgentTask().apply { this.id = id }

    @Test
    fun `registering a task stores only its id as a string`() {
        registrar.register(task(7L))

        val captor = argumentCaptor<org.quartz.JobDetail>()
        verify(quartz).scheduleJob(captor.capture(), any(), eq(true))
        val data = captor.firstValue.jobDataMap
        assertEquals("7", data.getString(TaskQuartzRegistrar.KEY_TASK_ID))
        assertNull(data["agentTask"], "the serialized entity must be gone: useProperties rejects it")
    }

    @Test
    fun `a task id survives the round trip through the job name`() {
        assertEquals(7L, TaskQuartzRegistrar.taskIdOf(JobKey("AgentTask_7", TaskQuartzRegistrar.GROUP_AGENT_TASK)))
        assertNull(TaskQuartzRegistrar.taskIdOf(JobKey("somethingElse", TaskQuartzRegistrar.GROUP_AGENT_TASK)))
        assertEquals(JobKey("AgentTask_7", "AgentTaskGroup"), registrar.jobKeyOf(7L))
    }
}
```

> `AgentTask` 是无参构造 + `var` 字段的类（`harnax-entity/.../entity/AgentTask.kt:8`），`apply { id = 7L }` 直接可用。`assertInstanceOf`/`GroupMatcher`/`SchedulerServiceImpl` 三个 import 若最终没用到就删掉，spotless 不会替你删。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-scheduler -am test -Dtest=TaskQuartzRegistrarTest -Dsurefire.failIfNoSpecifiedTests=false
```
期望：编译失败（`TaskQuartzRegistrar` 不存在）。

- [ ] **Step 3: 写 registrar**

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/**
 * The only place that writes an agent task into the Quartz store.
 *
 * Extracted from `SchedulerServiceImpl` because the reconciler has to register and unregister tasks the
 * same way the CRUD paths do, and a second copy of the job-key/cron/misfire rules is how the two drift
 * apart. It also fixes what the old shape could not: the JobDataMap used to carry the whole `AgentTask`,
 * so a job registered before an edit kept firing with the pre-edit prompt. With `useProperties: true` on
 * a JDBC store a non-string value is a hard error anyway, so the map holds a taskId and the job re-reads
 * the row (see [AbstractAgentTaskJob.run]).
 */
@Component
class TaskQuartzRegistrar(
    private val schedulerFactory: SchedulerFactoryBean,
) {

    private val log = LoggerFactory.getLogger(TaskQuartzRegistrar::class.java)

    private val scheduler: Scheduler get() = schedulerFactory.scheduler

    fun jobKeyOf(taskId: Long): JobKey = JobKey(jobName(taskId), GROUP_AGENT_TASK)

    fun triggerKeyOf(taskId: Long): TriggerKey = TriggerKey(jobName(taskId) + "_trigger", GROUP_AGENT_TASK)

    fun register(task: AgentTask) {
        val taskId = requireNotNull(task.id) { "Cannot schedule an unsaved agent task" }
        val jobData = JobDataMap().apply { put(KEY_TASK_ID, taskId.toString()) }
        val jobDetail = JobBuilder.newJob(jobClassFor(task))
            .withIdentity(jobKeyOf(taskId))
            .usingJobData(jobData)
            .storeDurably()
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKeyOf(taskId))
            .forJob(jobKeyOf(taskId))
            .withSchedule(
                CronScheduleBuilder.cronSchedule(task.cronExpression).apply {
                    if (task.concurrent == 0) {
                        withMisfireHandlingInstructionDoNothing()
                    } else {
                        withMisfireHandlingInstructionFireAndProceed()
                    }
                },
            )
            .build()

        // One store call, replace=true: the cron is validated by Quartz during the build above, and
        // scheduleJob(.., true) swaps job + trigger atomically and recovers a job row left without a
        // trigger. checkExists -> deleteJob -> scheduleJob had a window where the task was scheduled by
        // nothing while agent_task still read task_status=1.
        scheduler.scheduleJob(jobDetail, setOf(trigger), true)
        log.info("Registered agent task in the Quartz store: id={}, cron={}", taskId, task.cronExpression)
    }

    fun unregister(taskId: Long) {
        scheduler.deleteJob(jobKeyOf(taskId))
        log.info("Removed agent task from the Quartz store: id={}", taskId)
    }

    /** The registered class is the only channel that carries `concurrent` into Quartz. */
    private fun jobClassFor(task: AgentTask): Class<out Job> = if (task.concurrent == 0) {
        AgentTaskNonConcurrentJob::class.java
    } else {
        AgentTaskJob::class.java
    }

    companion object {
        const val GROUP_AGENT_TASK = "AgentTaskGroup"

        /** The only JobDataMap key an agent-task job carries; the value is the task id as a string. */
        const val KEY_TASK_ID = "taskId"

        private fun jobName(taskId: Long): String = "AgentTask_$taskId"

        /** Null for a name that is not of the `AgentTask_<id>` shape (a hand-made job, say). */
        fun taskIdOf(jobKey: JobKey): Long? = jobKey.name.removePrefix("AgentTask_").toLongOrNull()
    }
}
```

- [ ] **Step 4: `SchedulerServiceImpl` 改为委托**

- 构造函数增加 `private val registrar: TaskQuartzRegistrar`，删除不再使用的 `import ...job.AgentTaskJob` / `AgentTaskNonConcurrentJob`（`jobClassFor` 随之下线）。
- `scheduleTask(task)` 改为 `registrar.register(task)`；`unscheduleTask(task)` 改为 `registrar.unregister(task.id)`。保留接口方法与日志语义。
- `init()` 里在既有两行 context 注册后追加一行，让 job 能回查：

```kotlin
schedulerContext["agentTaskMapper"] = agentTaskMapper
```

  并把该方法上"with the in-memory job store an inert node holds no user job at all … When the JDBC store lands (S2) the enabled check has to move into the fire path as well"这段注释改为事实描述：store 现在是共享的，**禁用节点确实可能被派到一次它没注册过的 fire**，因此 fire 路径必须检查 `schedulerEnabled`（见 Step 5）。
- `runTaskOnce(id)` 的 JobDataMap 同样只放 `KEY_TASK_ID`：`JobDataMap().apply { put(TaskQuartzRegistrar.KEY_TASK_ID, task.id.toString()) }`。

- [ ] **Step 5: `AbstractAgentTaskJob.run()` 回查 + 三态前置检查**

替换 `:35-45` 取实体的部分：

```kotlin
    protected fun run(context: JobExecutionContext) {
        val task = taskToRun(context) ?: return

        // A shared store hands this node a fire for a job it never registered — including on an instance
        // that was started with scheduler.enabled=false. Refusing here is the only place that can tell
        // the difference: the row will be picked up by whichever node is scheduling.
        if (!schedulerService(context).schedulingEnabled) {
            log.info("Task {} fired on an instance with scheduling disabled, leaving it to another node", task.id)
            return
        }

        // The cron is read off the store at fire time, so a task edited without a re-register (or a job
        // left behind by a delete that never reached the store) cannot run on stale configuration.
        if (task.taskStatus != 1 || task.active != 1) {
            log.info("Task {} is no longer an active running task (status={}, active={}), skipping", task.id, task.taskStatus, task.active)
            return
        }
        // ... 以下原有 triggerTime / hasActiveRunningExecution / tryAcquireLock / executeTaskOnce 不变
    }

    /**
     * The task this fire belongs to, or null when it must not run.
     *
     * The JobDataMap holds only an id ([TaskQuartzRegistrar.KEY_TASK_ID]) — `useProperties: true` forbids
     * anything else, and an entity in the store would be a snapshot of a prompt somebody has since edited.
     * A row that has since disappeared means the task was deleted while its job survived in the store, and
     * the job itself is the stale thing: deleting it here converges whatever left the two apart, and the
     * reconciler would do the same on its next round.
     */
    private fun taskToRun(context: JobExecutionContext): AgentTask? {
        val taskId = context.jobDetail.jobDataMap.getString(TaskQuartzRegistrar.KEY_TASK_ID)?.toLongOrNull()
        if (taskId == null) {
            log.error("Job {} carries no usable {} in its data map", context.jobDetail.key, TaskQuartzRegistrar.KEY_TASK_ID)
            return null
        }
        val mapper = context.scheduler.context["agentTaskMapper"] as? AgentTaskMapper
        if (mapper == null) {
            log.error("No agentTaskMapper in the scheduler context, so task {} cannot be loaded", taskId)
            return null
        }
        val task = mapper.selectAnyById(taskId)
        if (task == null) {
            log.warn("Task {} no longer exists; deleting its orphaned job from the store", taskId)
            runCatching { context.scheduler.deleteJob(context.jobDetail.key) }
                .onFailure { log.warn("Could not delete orphaned job for task {}: {}", taskId, it.message) }
        }
        return task
    }
```

`SchedulerService` 接口加 `val schedulingEnabled: Boolean`（`SchedulerServiceImpl` 里返回构造参数 `schedulerEnabled`），并把 `AgentTaskMapper` 的 import 加到 job 文件。

- [ ] **Step 6: 更新受影响测试**

- `AgentTaskJobExecutionTest.kt`：原来往 JobDataMap 塞实体的地方全部改为 `put("taskId", "<id>")`，并 stub `agentTaskMapper.selectAnyById(...)`；新增 3 个用例：
  - `a task deleted since registration takes its job out of the store`（`selectAnyById` 返回 null → `verify(scheduler).deleteJob(...)`，且 `executeTaskOnce` 从未调用）；
  - `a fire handed to a node with scheduling disabled does nothing`（`schedulingEnabled = false` → 不执行）；
  - `a task that has since been paused does not run`（`taskStatus = 0` → 不执行、不删 job）。
- `SchedulerServiceImplTest.kt`：构造参数新增 registrar；`scheduleTask`/`unscheduleTask` 用例改为断言委托（`verify(registrar).register(task)`）。
- `SchedulerStartupLoadTest.kt` 留到 Task 4 一并改。

- [ ] **Step 7: 跑测试**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler -am test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > t3.log 2>&1; echo EXIT=$?; grep -E "BUILD|ERROR.*Test" t3.log | tail -5
```
期望：EXIT=0。

- [ ] **Step 8: Commit**

```bash
git add harnax-scheduler/src
git commit -m "refactor(调度): JobDataMap 只携带 taskId，job 触发时回查 agent_task"
```

---

### Task 4: reconcile 取代全删重建（含 60s 集群对账与漂移指标）

**Files:**
- Create: `.../service/TaskScheduleReconciler.kt`、`.../job/SchedulerReconcileJob.kt`
- Modify: `.../service/SchedulerService.kt`、`.../service/impl/SchedulerServiceImpl.kt:157-297`、`.../health/QuartzJobInventory.kt`、`.../health/SchedulerStatus.kt`、`.../health/SchedulerHealthIndicator.kt`、`.../metrics/SchedulerMetrics.kt`、`.../controller/SchedulerController.kt:107-121`
- Test: Create `.../service/TaskScheduleReconcilerTest.kt`；Modify `SchedulerStartupLoadTest.kt`、`SchedulerHealthIndicatorTest.kt`、`SchedulerMetricsTest.kt`、`SchedulerControllerTest.kt`、`QuartzJobInventoryTest.kt`

**Interfaces:**
- Consumes: `TaskQuartzRegistrar.register/unregister`、`AgentTaskMapper.selectRunningTasks()`。
- Produces:
  - `data class ReconcileReport(added: Int, removed: Int, updated: Int, unchanged: Int, failedIds: List<Long>)`，`val converged: Boolean`
  - `TaskScheduleReconciler.reconcile(): ReconcileReport`
  - `QuartzJobInventory.agentTaskJobs(): Map<Long, RegisteredJob>`；`data class RegisteredJob(val cronExpression: String?, val jobClassName: String)`
  - `SchedulerService.reconcile(): ReconcileReport`（取代 `loadTasksToScheduler(): Boolean`）
  - `SchedulerStatus`：`lastReconcileAt: Instant?`、`lastReconcileError: String?`、`recordReconcile(jobCount: Int, error: String?)`
  - `SchedulerMetrics.recordReconcileDrift(action: String, count: Int)`、`recordLoadAttempt` 保留

- [ ] **Step 1: 写失败的 reconciler 测试**

`TaskScheduleReconcilerTest.kt`（Mockito，不需要容器）：

```kotlin
package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The whole point of a shared store is that a restart must not unregister what another node is running.
 * These cases are that promise as a table: register what is missing, drop what is gone, reschedule what
 * changed, and — the one an "easier" implementation gets wrong — leave a matching job completely alone,
 * because touching it resets its prev/next fire history.
 */
class TaskScheduleReconcilerTest {

    private val mapper: AgentTaskMapper = mock()
    private val registrar: TaskQuartzRegistrar = mock()
    private val inventory: QuartzJobInventory = mock()
    private val status = SchedulerStatus(schedulerEnabled = true)
    private val metrics: SchedulerMetrics = mock()
    private val reconciler = TaskScheduleReconciler(mapper, registrar, inventory, status, metrics)

    private val nonConcurrent = AgentTaskNonConcurrentJob::class.java.name

    private fun task(id: Long, cron: String = CRON, concurrent: Int = 0) = AgentTask().apply {
        this.id = id
        this.cronExpression = cron
        this.concurrent = concurrent
    }

    @Test
    fun `a matching job is left completely alone`() {
        stubTasks(task(1L))
        doReturn(mapOf(1L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.unchanged)
        assertEquals(0, report.added + report.removed + report.updated)
        verifyNoInteractionsOnRegistrar()
        assertTrue(report.converged)
        // a round that changed nothing must not publish drift samples
        verify(metrics, never()).recordReconcileDrift(any(), anyInt())
    }

    @Test
    fun `missing jobs are added extra ones removed and changed crons rescheduled`() {
        stubTasks(task(1L, "0 0 1 * * ?"), task(2L))
        doReturn(
            mapOf(
                1L to RegisteredJob("0 0 2 * * ?", nonConcurrent),
                9L to RegisteredJob("0 0 1 * * ?", nonConcurrent),
            ),
        ).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.added)
        assertEquals(1, report.removed)
        assertEquals(1, report.updated)
        verify(registrar).register(argThat { id == 2L })
        verify(registrar).register(argThat { id == 1L })
        verify(registrar).unregister(9L)
        verify(metrics).recordReconcileDrift("add", 1)
        verify(metrics).recordReconcileDrift("remove", 1)
        verify(metrics).recordReconcileDrift("update", 1)
        assertTrue(report.converged)
    }

    @Test
    fun `a task whose job class no longer matches its concurrent flag counts as a change`() {
        stubTasks(task(3L, concurrent = 1))
        doReturn(mapOf(3L to RegisteredJob(CRON, nonConcurrent))).`when`(inventory).agentTaskJobs()

        val report = reconciler.reconcile()

        assertEquals(1, report.updated)
        verify(registrar).register(argThat { id == 3L && concurrent == 1 })
    }

    @Test
    fun `one failing task does not stop the others and is reported as drift`() {
        stubTasks(task(1L), task(2L))
        doReturn(emptyMap<Long, RegisteredJob>()).`when`(inventory).agentTaskJobs()
        doThrow(RuntimeException("bad cron")).doNothing().`when`(registrar).register(any())

        val report = reconciler.reconcile()

        assertEquals(2, report.added)
        assertEquals(listOf(1L), report.failedIds)
        assertFalse(report.converged)
        assertEquals("1 of 2 active tasks could not be registered: ids=[1]", status.lastReconcileError)
    }

    private fun stubTasks(vararg tasks: AgentTask) {
        doReturn(tasks.toList()).`when`(mapper).selectRunningTasks()
    }

    private fun verifyNoInteractionsOnRegistrar() {
        verify(registrar, never()).register(any())
        verify(registrar, never()).unregister(any())
    }

    companion object {
        private const val CRON = "0 0 * * * ?"
    }
}
```

`anyInt()` 从 `org.mockito.kotlin` 引入；`AgentTaskJob` 只在 `expectedJobClassName` 的另一侧用到，测试里不引用就删掉那行 import。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-scheduler -am test -Dtest=TaskScheduleReconcilerTest -Dsurefire.failIfNoSpecifiedTests=false
```
期望：编译失败（`TaskScheduleReconciler` / `RegisteredJob` 不存在）。

- [ ] **Step 3: `QuartzJobInventory` 增加快照读取**

```kotlin
/** What the store holds for one agent task, read back for the diff. */
data class RegisteredJob(
    val cronExpression: String?,
    val jobClassName: String,
)

/**
 * Every agent-task job in [JOB_GROUP] keyed by the task id encoded in its name, with the cron and the
 * registered class read off the store rather than off what this node thinks it wrote: under a shared
 * store the question "what is scheduled" has exactly one answer for the whole cluster.
 *
 * A job with no cron trigger (someone registered one by hand, or a delete left a durable job behind)
 * comes back with `cronExpression = null`, which the reconciler treats as "not matching" and rewrites.
 */
fun agentTaskJobs(): Map<Long, RegisteredJob> {
    val scheduler = schedulerFactory.scheduler
    return scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP)).associateWith { key ->
        scheduler.getJobDetail(key)
    }.mapNotNull { (key, detail) ->
        val taskId = key.name.removePrefix(JOB_NAME_PREFIX).toLongOrNull() ?: return@mapNotNull null
        val detailNotNull = detail ?: return@mapNotNull null
        val cron = scheduler.getTriggersOfJob(detailNotNull.key)
            .filterIsInstance<org.quartz.CronTrigger>()
            .firstOrNull()
            ?.cronExpression
        taskId to RegisteredJob(cron, detailNotNull.jobClass.name)
    }.toMap()
}
```

`JOB_GROUP` / `JOB_NAME_PREFIX` 改为引用 `TaskQuartzRegistrar.GROUP_AGENT_TASK` / `"AgentTask_"`（消除重复字面量），`scheduledTaskIds()` 保留并实现为 `agentTaskJobs().keys`。

- [ ] **Step 4: 写 reconciler**

```kotlin
package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What one reconcile round did. `failedIds` is the drift an operator has to see. */
data class ReconcileReport(
    val added: Int,
    val removed: Int,
    val updated: Int,
    val unchanged: Int,
    val failedIds: List<Long>,
) {
    val converged: Boolean get() = failedIds.isEmpty()
}

/**
 * Converge the Quartz store with `agent_task`, without ever unregistering a schedule that is already right.
 *
 * This replaces "delete every job in AgentTaskGroup, then re-register everything". Under a shared store
 * that is a cluster-wide unchedule on every node start, and every fire the surviving nodes claimed inside
 * that window is gone; it also wiped PREV_FIRE_TIME/NEXT_FIRE_TIME on jobs that had not changed. The diff
 * is what makes the shared store safe: a restart touches only the tasks that actually moved.
 *
 * The same call serves three triggers, and that is the reason it is one function rather than three:
 * node startup, after a CRUD (admin's forward), and a 60-second cluster-singleton sweep. The third is what
 * bounds the damage of a lost CRUD notification — the write and the schedule are not one transaction, so a
 * node that was down during an edit would otherwise stay wrong forever.
 */
@Component
class TaskScheduleReconciler(
    private val agentTaskMapper: AgentTaskMapper,
    private val registrar: TaskQuartzRegistrar,
    private val inventory: QuartzJobInventory,
    private val status: SchedulerStatus,
    private val metrics: SchedulerMetrics,
) {

    private val log = LoggerFactory.getLogger(TaskScheduleReconciler::class.java)

    fun reconcile(): ReconcileReport {
        val expected = agentTaskMapper.selectRunningTasks().associateBy { it.id }
        val actual = inventory.agentTaskJobs()

        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0
        val failed = mutableListOf<Long>()

        for ((taskId, task) in expected) {
            val registered = actual[taskId]
            when {
                registered == null ->
                    if (apply(failed, taskId, { registrar.register(task) })) added++

                !matches(task, registered) ->
                    if (apply(failed, taskId, { registrar.register(task) })) updated++

                else -> unchanged++
            }
        }
        for (taskId in actual.keys - expected.keys) {
            if (apply(failed, taskId, { registrar.unregister(taskId) })) removed++
        }

        reportDrift(added, removed, updated)
        val drift = describeDrift(failed, expected.size)
        status.recordReconcile(unchanged + updated + added, drift)
        metrics.recordLoadAttempt(success = drift == null)
        return ReconcileReport(added, removed, updated, unchanged, failed)
    }

    /** An old job that predates the concurrent flag, or a task whose flag moved, must be re-registered. */
    private fun matches(task: AgentTask, registered: RegisteredJob): Boolean =
        registered.cronExpression == task.cronExpression &&
            registered.jobClassName == expectedJobClassName(task)

    private fun expectedJobClassName(task: AgentTask): String =
        if (task.concurrent == 0) AgentTaskNonConcurrentJob::class.java.name else AgentTaskJob::class.java.name

    /**
     * One task's store write, with its failure recorded as drift instead of thrown: a single unusable cron
     * must not stop the other tasks in the round, and the 60-second sweep gets another attempt at it.
     */
    private inline fun apply(failed: MutableList<Long>, taskId: Long, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: Exception) {
            failed += taskId
            log.error("Reconcile could not apply task {}: {}", taskId, e.message, e)
            false
        }

    private fun reportDrift(added: Int, removed: Int, updated: Int) {
        metrics.recordReconcileDrift("add", added)
        metrics.recordReconcileDrift("remove", removed)
        metrics.recordReconcileDrift("update", updated)
    }

    /** Null means the store now matches the table. The text doubles as the /actuator/health detail. */
    private fun describeDrift(failedIds: List<Long>, total: Int): String? {
        if (failedIds.isEmpty()) return null
        val shown = failedIds.take(MAX_DRIFT_IDS).joinToString(",")
        val hidden = if (failedIds.size > MAX_DRIFT_IDS) ",+${failedIds.size - MAX_DRIFT_IDS} more" else ""
        return "${failedIds.size} of $total active tasks could not be registered: ids=[$shown$hidden]"
    }

    companion object {
        private const val MAX_DRIFT_IDS = 20
    }
}
```

- [ ] **Step 5: 接线**

- `SchedulerService`：删 `fun loadTasksToScheduler(): Boolean`，加 `fun reconcileTasks(): ReconcileReport`（委托给 `TaskScheduleReconciler`，注入字段 `private val reconciler`）。
- `SchedulerServiceImpl.loadTasksToScheduler()` 删除；`loadTasksWithRetry()` 内部改调 `reconciler.reconcile()` 并按 `report.converged` 决定继续退避与否；`registerHousekeepingJob()` 旁新增 `registerReconcileJob()`：

```kotlin
    /**
     * The convergence sweep. Cluster-singleton for free: with a JDBC store a Quartz job is registered in
     * the shared store, so exactly one node fires it — which is what makes a periodic reconcile safe on
     * two instances where the same registration on a memory store ran twice.
     *
     * The interval is configurable because the integration tests have to freeze it: a sweep landing
     * between "hand the store some drift" and "reconcile it" would repair the drift first and the
     * assertion about what one round did would then be about the wrong round.
     */
    private fun registerReconcileJob() {
        try {
            val jobKey = JobKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP)
            if (scheduler.checkExists(jobKey)) return
            val jobDetail = JobBuilder.newJob(SchedulerReconcileJob::class.java)
                .withIdentity(jobKey).storeDurably().build()
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(TriggerKey(SchedulerReconcileJob.JOB_NAME, SchedulerReconcileJob.GROUP))
                .forJob(jobKey).startNow()
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(reconcileIntervalSeconds)
                        .repeatForever(),
                )
                .build()
            scheduler.scheduleJob(jobDetail, trigger)
        } catch (e: Exception) {
            log.warn("Reconcile job could not be registered: {}", e.message)
        }
    }
```

构造函数参数（放在 `executionTimeoutSeconds` 之后）：

```kotlin
    /** How often the cluster re-checks the store against the table. 60s is the CRUD-loss window bound. */
    @Value("\${scheduler.reconcile-interval-seconds:60}") private val reconcileIntervalSeconds: Int,
```

并在 `application.yml` 的 `scheduler:` 段补：

```yaml
  # Upper bound on how long a lost reload notification stays invisible: the store and agent_task are not
  # one transaction, so a CRUD that reached no node is repaired by this sweep instead. Raising it trades
  # a longer blind window for fewer store reads; below ~15s the reads stop being negligible.
  reconcile-interval-seconds: ${SCHEDULER_RECONCILE_INTERVAL:60}
```

  在 `init()` 里注册进 schedulerContext：`schedulerContext["taskScheduleReconciler"] = reconciler`；`onApplicationReady()` 里在 `registerHousekeepingJob()` 之后、`schedulerEnabled` 门禁**之内**调用 `registerReconcileJob()`（对账即调度写操作，禁用节点不该抢它）。
- 新建 `job/SchedulerReconcileJob.kt`：

```kotlin
package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory

/**
 * Periodic convergence of the Quartz store with `agent_task`.
 *
 * `@DisallowConcurrentExecution` because a round that outruns its 60-second period would otherwise stack,
 * and two reconciles reading the same diff can each decide to register the same job — harmless under
 * replace=true, but a second one deleting while the first is adding is a fight over the store.
 */
@DisallowConcurrentExecution
class SchedulerReconcileJob : Job {

    override fun execute(context: JobExecutionContext) {
        val reconciler = context.scheduler.context["taskScheduleReconciler"] as? TaskScheduleReconciler
        if (reconciler == null) {
            log.debug("No reconciler registered in the scheduler context yet, skipping")
            return
        }
        val report = reconciler.reconcile()
        if (!report.converged) {
            log.warn("Scheduled reconcile left drift: {}", report.failedIds)
        }
    }

    companion object {
        /** Same system group as the housekeeping sweep: `AgentTaskGroup` is the group reconcile edits. */
        const val GROUP = "SchedulerSystemGroup"
        const val JOB_NAME = "AgentTaskScheduleReconcile"

        private val log = LoggerFactory.getLogger(SchedulerReconcileJob::class.java)
    }
}
```

- `SchedulerStatus`：把 `loadSuccessAt` / `loadError` / `recordLoadSuccess(jobCount, drift)` / `recordLoadFailure` 改名为 reconcile 口径，保留 `@Volatile` 私有字段 + open getter 的既有写法（Kotlin spring 插件把类开成 open，open 属性不能有 private setter，这条注释要跟着搬）。新增 `val lastReconcileJobCount`。
- `SchedulerHealthIndicator`：detail 键 `lastLoadSuccessAt` → `lastReconcileAt`、`lastLoadError` → `lastReconcileError`，新增 `storeType`（`schedulerFactory.scheduler.metaData.jobStoreClassSimpleName` 不可用时用 `"unknown"`；Quartz 提供 `MetaData.getJobStoreClass()`，取其 `simpleName`），DOWN 判据文案 `No task load has succeeded since startup` → `No reconcile has succeeded since startup`。
- `SchedulerMetrics`：

```kotlin
    /**
     * Divergence the reconcile had to repair, counted per action. A steady non-zero stream here means CRUD
     * notifications and the store are脱节 — the failure mode a broadcast-per-node design hid.
     * Registered lazily so a round that changed nothing costs no samples.
     */
    fun recordReconcileDrift(action: String, count: Int) {
        if (count <= 0) return
        registry.counter("scheduler.reconcile.drift", "action", action).increment(count.toDouble())
    }
```

  并把 `scheduler.jobs.scheduled` 的 description 改为 `'Agent tasks registered in the shared Quartz store (cluster view when job-store-type=jdbc)'`（spec F9 要求换 store 时同批改口径）。
- `SchedulerController.reload()`：`if (schedulerService.reconcileTasks().converged) ResultVo.success("Tasks reconciled") else ResultVo.error("Some active tasks could not be scheduled, see /actuator/health for details")`。
- `SchedulerServiceImpl` 里 `hasActiveRunningExecution` 等其余逻辑不变；`describeDrift`/`MAX_DRIFT_IDS` 从 service 迁到 reconciler 后删除 service 内的私有副本。

- [ ] **Step 6: 更新既有测试并跑全绿**

- `SchedulerStartupLoadTest.kt`：类名与断言改为 reconcile 语义（mock `TaskScheduleReconciler`），保留"退避重试直到收敛""中断即停""5 次后 error 日志"三条断言。
- `SchedulerHealthIndicatorTest.kt`：detail 键名 + `storeType` 断言。
- `QuartzJobInventoryTest.kt`：新增 `agentTaskJobs()` 的 cron/class 读取用例。
- `SchedulerMetricsTest.kt`：`recordReconcileDrift` 的 0 不注册、正数按 action 打点。
- `SchedulerControllerTest.kt`：`/reload` 两个分支。

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler -am test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > t4.log 2>&1; echo EXIT=$?; grep -E "BUILD|Tests run:.*Fail" t4.log | tail -5
```
期望：EXIT=0。

- [ ] **Step 7: Commit**

```bash
git add harnax-scheduler/src
git commit -m "feat(调度): diff 对账取代全删重建，60s 集群单例 reconcile + 漂移指标"
```

---

### Task 5: admin 的 broadcast 折叠为单点转发

**Files:**
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImpl.kt`（`:17-101`）、`.../service/SchedulerClient.kt`（注释）
- Test: Modify `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/service/impl/SchedulerClientImplTest.kt`

**Interfaces:**
- Consumes: `HARNAX_SCHEDULER_URL`（compose 已是 `http://scheduler:8084`，Docker DNS 在两个 task 间轮询）。
- Produces: 5 个方法签名不变，全部为**单点** POST；`CODE_SCHEDULER_SYNC_FAILED = 40902` 语义与文案不变（定义已存库、未被调度重载）。

- [ ] **Step 1: 写失败的测试**

在 `SchedulerClientImplTest` 里新增（并把原"两个实例都收到"的用例改成断言只发一次）：

```kotlin
    @Test
    fun `a reload goes to one instance because the store is shared`() {
        // Two instances used to need two calls: each held its own in-memory schedule. With a JDBC store
        // any node's write lands in the store every node reads, so a broadcast would only multiply the
        // number of places the same reconcile can fail.
        val client = clientWithUrls(listOf("http://one", "http://two"))
        val response = client.reloadTasks()

        assertTrue(response.isSuccess())
        assertEquals(1, server.requestCount)
        assertEquals("/api/scheduler/reload", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
    }
```

> `clientWithUrls` / `server` 按该测试文件已有的 MockWebServer 装配方式取现成形态（文件里已有两实例用例，复用它的构造）。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-admin -am test -Dtest=SchedulerClientImplTest -Dsurefire.failIfNoSpecifiedTests=false
```
期望：FAIL（当前 broadcast 会打到两个 URL）。

- [ ] **Step 3: 改实现**

`SchedulerClientImpl`：`triggerTask` 与其他四个方法统一走 `postToInstance(urls.firstOrNull() ?: throw ..., path)`；删除 `broadcast(...)` 与 `:55-82` 那段 DEBT 注释（它描述的正是"什么时候可以不再 broadcast"，现在到了），换成 3 行说明：

```kotlin
    /**
     * One call, one instance. Every scheduling write lands in the shared Quartz store, so there is no
     * per-node state left to notify — and no `anySuccess`-style folding to invent business codes for.
     * A single instance failing therefore means exactly one thing: the definition is saved and nothing
     * has scheduled it, which is what 40902 tells the caller.
     */
```

`harnax.scheduler.url` 配置项注释同步改为"单个地址（集群由 store 共享，不由客户端扇出）"，保留逗号分隔解析不报错（运维若配了两个地址，取第一个）。

- [ ] **Step 4: 跑测试**

```bash
$MVN -q spotless:apply -pl harnax-admin
$MVN -o -pl harnax-admin -am test -Dtest='SchedulerClientImplTest,AgentTaskServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false > t5.log 2>&1; echo EXIT=$?; grep -E "BUILD|Tests run:.*Fail" t5.log | tail -3
```
期望：EXIT=0。

- [ ] **Step 5: Commit**

```bash
git add harnax-admin/src
git commit -m "refactor(调度): 共享 store 后 admin 的调度写转发折叠为单点"
```

---

### Task 6: 两实例部署形态与逐台滚动

**Files:**
- Modify: `docker-new/docker-compose.yml`（scheduler 服务：`:286` `container_name`、grace 注释段）
- Modify: `docker-new/deploy-service.sh:119-135`
- Create: `docker-new/roll-scheduler.sh`

**Interfaces:**
- Produces: `docker-new/roll-scheduler.sh` —— 部署 2 个 scheduler 实例且任何时刻至少 1 个在跑。

- [ ] **Step 1: compose 去掉 container_name**

删除 `scheduler:` 服务下的 `container_name: harnax-scheduler`（`--scale` 与固定容器名互斥，Docker 会直接报错），并在该服务顶部注释补：

```yaml
  # Two instances, and exactly two places decide that number: `--scale scheduler=2` at the command line
  # and roll-scheduler.sh below. No `deploy.replicas` here on purpose — a declarative replica count
  # fights the stop-one/replace-one dance the rolling update has to do.
  # The cluster itself lives in the shared QRTZ_* tables: same instanceName, instanceId=AUTO per node,
  # and a 15s check-in, so a dead node's triggers move to its peer inside 15~75s.
```

- [ ] **Step 2: 写逐台滚动脚本**

`docker-new/roll-scheduler.sh`（`chmod +x`）：

```bash
#!/usr/bin/env bash
# Rolling restart of the 2-instance scheduler cluster: never both at once.
#
# `docker-compose up -d --force-recreate scheduler` recreates every replica of the service in one go, and
# 400s of no scheduler means fires pile up in QRTZ_TRIGGERS. A concurrent=0 task carries
# withMisfireHandlingInstructionDoNothing, so those piled-up fires are *discarded* — the deploy would
# silently skip scheduled runs, which is the opposite of the "never lose an execution" the 400s grace is
# there for.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-new/docker-compose.yml}"
REPLICAS="${SCHEDULER_REPLICAS:-2}"
GRACE="${SCHEDULER_STOP_GRACE:-400}"

cd "$(dirname "$0")/.."

containers=$(docker-compose -f "$COMPOSE_FILE" ps --quiet scheduler | tr '\n' ' ')
# shellcheck disable=SC2206
containers=($containers)
if [ "${#containers[@]}" -eq 0 ]; then
  echo "no scheduler container running; bringing the cluster up at ${REPLICAS} replicas"
  docker-compose -f "$COMPOSE_FILE" up -d --no-deps --scale "scheduler=${REPLICAS}" --no-recreate scheduler
  exit 0
fi

for container in "${containers[@]}"; do
  echo "stopping ${container} (waiting up to ${GRACE}s for its in-flight execution)"
  docker stop -t "${GRACE}" "${container}" >/dev/null
  docker-compose -f "$COMPOSE_FILE" up -d --no-deps --scale "scheduler=${REPLICAS}" --no-recreate scheduler
  echo "waiting for the replacement to report healthy"
  for _ in $(seq 1 60); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker-compose -f "$COMPOSE_FILE" ps --quiet scheduler | tr '\n' ' ' | awk '{print $NF}')" 2>/dev/null || echo starting)
    [ "${status}" = "healthy" ] && break
    sleep 5
  done
  [ "${status}" = "healthy" ] || { echo "replacement never went healthy"; exit 1; }
done

echo "cluster rolled; QRTZ_SCHEDULER_STATE rows:"
docker-compose -f "$COMPOSE_FILE" exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:?}" \
  -e "SELECT INSTANCE_NAME, CHECKIN_INTERVAL FROM harnax_admin.QRTZ_SCHEDULER_STATE;" || \
  echo "  (run that query by hand if mysql is not reachable here)"
```

- [ ] **Step 3: 部署脚本改为调用它**

`docker-new/deploy-service.sh` 的 `scheduler)` 分支第 4 步替换：

```bash
    echo "📦 步骤 4/4: 逐台滚动 scheduler（保持至少 1 个实例在跑）..."
    bash docker-new/roll-scheduler.sh
```

并在文件顶部"支持的服务"说明下方加一行提示：scheduler 需要 `SCHEDULER_REPLICAS` / `MYSQL_ROOT_PASSWORD` 环境变量。

- [ ] **Step 4: 校验**

```bash
bash -n docker-new/roll-scheduler.sh && bash -n docker-new/deploy-service.sh && echo SYNTAX_OK
grep -n "container_name: harnax-scheduler" docker-new/docker-compose.yml   # 期望无输出
```

- [ ] **Step 5: Commit**

```bash
git add docker-new
git commit -m "feat(部署): scheduler 双实例逐台滚动，避免 force-recreate 丢弃 misfire"
```

---

### Task 7: IT 脚手架 + IT-1（集群单触发）+ IT-2（对账收敛）+ IT-5（guard 清理）

**Files:**
- Modify: `harnax-scheduler/pom.xml`
- Create: `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/BaseSchedulerIT.kt`
- Create: `.../it/ReconcileConvergenceIT.kt`（IT-2）、`.../it/HousekeepingGuardIT.kt`（IT-5）
- Create: `harnax-scheduler/src/test/resources/application-it.yml`

**Interfaces:**
- Produces: `mvn verify -Pintegration-test -pl harnax-scheduler` 可跑；`BaseSchedulerIT` 提供容器、Flyway、真 Quartz、mock 掉的 `RouterClient`。

- [ ] **Step 1: pom 加测试阶段切分**

`harnax-scheduler/pom.xml` 的 `<build><plugins>` 内，`maven-compiler-plugin` 之后插入（形态照 `harnax-admin/pom.xml:342-373`）：

```xml
            <!-- Unit test phase: integration tests (*IT) run under failsafe with -Pintegration-test -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>**/*IT.class</exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>3.2.5</version>
                <configuration>
                    <skipITs>${skipITs}</skipITs>
                    <includes>
                        <include>**/*IT.class</include>
                    </includes>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

`<properties>` 段（无则新建 `<properties><skipITs>true</skipITs></properties>`），并在 `</dependencies>` 前加：

```xml
        <!-- Testcontainers: real MySQL for the cluster/reconcile/guard integration tests -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <version>1.21.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mysql</artifactId>
            <version>1.21.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.21.4</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: `application-it.yml`**

`harnax-scheduler/src/test/resources/application-it.yml`：

```yaml
# Integration profile: the datasource is injected by the Testcontainers container through
# @DynamicPropertySource, so nothing here is a connection. The router is mocked out, which is why no
# api-key bootstrap call is configured: SchedulerClient never reaches the network.
scheduler:
  enabled: true
  timeout-seconds: 5
  clear-session-timeout-seconds: 1
  command-timeout-seconds: 1
  # Any non-blank value: RouterClient.init() only reaches for admin's SYSTEM key when this is empty.
  api-key: it-key
  # Frozen: ReconcileConvergenceIT asserts what *one* round did, and a background sweep landing between
  # the drift and the call would have repaired it first. The startup round still runs.
  reconcile-interval-seconds: 3600
spring:
  flyway:
    enabled: true
    table: flyway_schema_history_scheduler
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: never
    overwrite-existing-jobs: true
    properties:
      org:
        quartz:
          scheduler:
            instanceName: HarnaxSchedulerIT
            instanceId: AUTO
          jobStore:
            isClustered: "true"
            clusterCheckinInterval: 5000
            useProperties: "true"
            driverDelegateClass: org.quartz.impl.jdbcjobstore.StdJDBCDelegate
          threadPool:
            threadCount: 3
mybatis:
  mapper-locations: classpath*:mapper/*.xml
  type-aliases-package: com.agnetix.harnax.entity
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 3: 容器基类**

`.../it/BaseSchedulerIT.kt`（形态照 `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/BaseAdminIT.kt`）：

```kotlin
package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.SchedulerApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

/**
 * Boots the real application against a Testcontainers MySQL 8: Flyway creates the `QRTZ_*` tables, Quartz
 * runs clustered on them for real, and MyBatis reads the task table for real. Mocking any of those three
 * would delete the thing these tests exist to prove.
 *
 * Nothing reaches the network: `scheduler.api-key` is set in `application-it.yml`, which is what stops
 * `RouterClient.init()` from fetching a SYSTEM key from admin, and neither test in this package executes a
 * task. A future IT that does needs `@MockitoBean` on `RouterClient` — Boot 4 removed `@MockBean`.
 *
 * These classes are named `*IT` and live behind failsafe (`-Pintegration-test`), so a machine without
 * Docker never runs them rather than failing red: `MySQLContainer` would throw on start.
 */
@SpringBootTest(classes = [SchedulerApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
abstract class BaseSchedulerIT {

    companion object {
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_scheduler_it")
            .withUsername("root")
            .withPassword("it_test")
            // Release 1 owns only the Quartz schema: agent_task and its two companions still live in
            // harnax_admin, so this container gets them from a test resource until release 2 moves them.
            .withInitScript("schema-it.sql")

        init {
            mysql.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }
    }
}
```

- `harnax-scheduler/src/test/resources/schema-it.sql`：从 `harnax-entity/src/test/resources/schema-test.sql:463-548` 复制三张业务表的建表段（**只复制 DDL，不复制种子数据**）。
- `any()` 需要 `org.mockito.ArgumentMatchers.any`；`ChatResponse` 的 FQCN 以 `harnax-protocol` 中定义为准（`SchedulerServiceImpl.executeTaskOnce` 用的那个类型），实现时按实际 import 修正。

- [ ] **Step 4: IT-2 对账收敛**

```kotlin
package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.quartz.CronTrigger
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * IT-2: hand the store a drift the CRUD path could not have produced, and prove the sweep converges it —
 * without clearing the fire history of the jobs that never changed. That second assertion is the one that
 * matters: it is what "reconcile" means here as opposed to "delete everything and re-register", and an
 * implementation that re-registers matching jobs passes every other assertion in this file.
 */
class ReconcileConvergenceIT : BaseSchedulerIT() {

    @Autowired private lateinit var reconciler: TaskScheduleReconciler
    @Autowired private lateinit var registrar: TaskQuartzRegistrar
    @Autowired private lateinit var schedulerFactory: SchedulerFactoryBean
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `drift converges and untouched jobs keep their fire history`() {
        val scheduler = schedulerFactory.scheduler
        insertTask(id = 101L, cron = "0 0/5 * * * ?")
        insertTask(id = 102L, cron = "0 0/7 * * * ?")
        insertTask(id = 103L, cron = "0 0/9 * * * ?")
        reconciler.reconcile()

        // Three ways to drift, and the one job that must not be touched: 101's stored cron is rewritten
        // behind the scheduler's back (no CRUD path produces that), 102's row disappears from the table,
        // and 103 stays exactly as registered.
        val untouchedNextFire = (scheduler.getTrigger(registrar.triggerKeyOf(103L)) as CronTrigger).nextFireTime.time
        jdbc.update(
            "UPDATE QRTZ_CRON_TRIGGERS SET CRON_EXPRESSION = ? WHERE TRIGGER_NAME = ?",
            "0 0/1 * * * ?",
            "AgentTask_101_trigger",
        )
        jdbc.update("DELETE FROM agent_task WHERE id = 102")

        val report = reconciler.reconcile()

        assertEquals(emptyList<Long>(), report.failedIds)
        assertEquals(1, report.updated)
        assertEquals(1, report.removed)
        assertEquals(1, report.unchanged)
        assertNull(scheduler.getJobDetail(registrar.jobKeyOf(102L)), "a task deleted from the table must lose its job")
        assertEquals(
            "0 0/5 * * * ?",
            (scheduler.getTrigger(registrar.triggerKeyOf(101L)) as CronTrigger).cronExpression,
            "a cron edited only in the store must be pulled back to the table's value",
        )
        // The proof that this is a diff and not a re-register: the job nobody had a reason to touch kept
        // the schedule it already had. An implementation that rebuilds every job passes all four
        // assertions above and fails this one.
        val untouched = scheduler.getTrigger(registrar.triggerKeyOf(103L)) as CronTrigger
        assertEquals(untouchedNextFire, untouched.nextFireTime.time)
    }

    private fun insertTask(id: Long, cron: String) {
        jdbc.update(
            "INSERT INTO agent_task (id, tenant_id, name, agent_id, prompt, cron_expression, task_status, concurrent, timeout_seconds, active, creator) " +
                "VALUES (?, 1, ?, 1, 'p', ?, 1, 0, 300, 1, 'admin')",
            id,
            "it-$id",
            cron,
        )
    }
}
```

- [ ] **Step 5: IT-5 guard 清理**

```kotlin
package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * IT-5: the guard table had no removal path at all before the housekeeping sweep existed, and it grows
 * with every trigger. Expired-but-terminal rows go, live rows stay, and a lock whose holder never came
 * back stops blocking that (task, trigger_time) forever.
 */
class HousekeepingGuardIT : BaseSchedulerIT() {

    @Autowired private lateinit var guard: AgentTaskExecutionGuard
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `retention and leaked-lock sweeps only touch what they may`() {
        val old = LocalDateTime.now().minusDays(30)
        val recent = LocalDateTime.now().minusHours(1)
        insert(201L, old, status = 1)   // terminal, past retention -> deleted
        insert(202L, recent, status = 1) // terminal, inside retention -> stays
        insert(203L, old, status = 0)   // lock whose holder is gone -> released

        guard.cleanupOldExecutions(7)
        guard.cleanupLeakedLocks()

        assertEquals(1, countWhere("id = 201"))
        assertEquals(1, countWhere("id = 202"))
        assertEquals(0, countWhere("id = 203"))
        assertTrue(countWhere("1=1") >= 2)
    }

    private fun insert(taskId: Long, createTime: LocalDateTime, status: Int) {
        jdbc.update(
            "INSERT INTO agent_task_execution (task_id, trigger_time, instance_id, status, create_time) VALUES (?, ?, ?, ?, ?)",
            taskId,
            createTime,
            "it-node",
            status,
            createTime,
        )
    }

    private fun countWhere(where: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_execution WHERE $where", Int::class.java) ?: 0
}
```

> `deleteOldExecutions`/`deleteStaleRunning` 以 `create_time` 判定（`AgentTaskExecutionMapper.xml`），201/203 的 `create_time` 都是 30 天前，所以 201 由 `cleanupOldExecutions(7)` 删、203 由 `cleanupLeakedLocks()` 删；`cleanupLeakedLocks()` 只删 `status = 0` 的行，所以 201/202 不受它影响。断言顺序按此写。

- [ ] **Step 6: IT-1 集群单触发**

`.../it/ClusterSingleFireIT.kt`。先在 `schema-it.sql` 末尾追加记账表：

```sql
-- Written by ClusterSingleFireIT's job: the aggregate row count is the evidence that a clustered
-- scheduler fires each trigger once for the whole cluster rather than once per node.
CREATE TABLE IF NOT EXISTS `it_cluster_fire` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `instance_name` VARCHAR(190) NOT NULL,
    `fire_time`     BIGINT NOT NULL
) COMMENT='Cluster single-fire evidence';
```

```kotlin
package com.agnetix.harnax.scheduler.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.impl.StdSchedulerFactory
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * IT-1: the one hard piece of evidence that this is a cluster rather than two schedulers that happen to
 * share a table. Two real Quartz schedulers in one JVM against one MySQL, a 1-second cron, eight seconds.
 *
 * - the row count tracks *fires*, not fires × nodes: two independent in-memory stores produce roughly
 *   twice the rows, and that is the failure this assertion exists to catch;
 * - the per-node counters sum to the row count, so the work really was split — a second member that never
 *   acquires a trigger is not doing what its row in `QRTZ_SCHEDULER_STATE` advertises;
 * - exactly two nodes are checked in under one `SCHED_NAME`, which is what makes takeover checkable.
 *
 * It extends the base so Flyway has built `QRTZ_*` before the hand-made schedulers connect; those two are
 * extra members, deliberately under their own `instanceName` so they cannot disturb the application's own
 * scheduler, which joins the cluster `HarnaxSchedulerIT`.
 */
class ClusterSingleFireIT : BaseSchedulerIT() {

    @Test
    fun `one clustered trigger fires once across two nodes`() {
        update("DELETE FROM it_cluster_fire")
        val first = scheduler(NODE_ONE)
        val second = scheduler(NODE_TWO)
        try {
            first.start()
            second.start()
            first.scheduleJob(
                JobBuilder.newJob(CountingJob::class.java).withIdentity("cluster-fire", "ItGroup").build(),
                TriggerBuilder.newTrigger()
                    .withIdentity("cluster-fire", "ItGroup")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0/1 * * * * ?"))
                    .build(),
            )

            Thread.sleep(8_000)

            val rows = queryCount("SELECT COUNT(*) FROM it_cluster_fire")
            val nodes = queryCount("SELECT COUNT(*) FROM QRTZ_SCHEDULER_STATE WHERE SCHED_NAME = ?", CLUSTER_NAME)
            val counted = CountingJob.fires.values.sumOf { it.get() }

            assertTrue(
                rows in 5..12,
                "8 seconds of a 1s cron should cost roughly 8 rows, got $rows — around 16 means each node fired for itself",
            )
            assertEquals(rows, counted, "every persisted fire must be counted by exactly one node")
            assertEquals(2, nodes, "both members must be checked in to the shared cluster state")
        } finally {
            first.shutdown(true)
            second.shutdown(true)
            CountingJob.fires.clear()
        }
    }

    /**
     * Built by hand rather than through Spring, because the claim is about two schedulers and the context
     * holds one. Everything but the identity is the set of values `application.yml` ships.
     *
     * `instanceId` is a fixed string instead of AUTO: AUTO derives host+timestamp, which is unique but
     * unreadable from an assertion. Sharing `instanceName` is what joins a cluster; the id only has to be
     * distinct.
     */
    private fun scheduler(instanceId: String): Scheduler {
        val props = Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", CLUSTER_NAME)
            setProperty("org.quartz.scheduler.instanceId", instanceId)
            setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool")
            setProperty("org.quartz.threadPool.threadCount", "2")
            setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
            setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate")
            setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_")
            setProperty("org.quartz.jobStore.isClustered", "true")
            setProperty("org.quartz.jobStore.clusterCheckinInterval", "5000")
            setProperty("org.quartz.jobStore.useProperties", "true")
            setProperty("org.quartz.jobStore.misfireThreshold", "60000")
            setProperty("org.quartz.jobStore.acquireTriggersWithinLock", "true")
            setProperty("org.quartz.jobStore.dataSource", "itDs")
            setProperty("org.quartz.dataSource.itDs.driver", "com.mysql.cj.jdbc.Driver")
            setProperty("org.quartz.dataSource.itDs.URL", jdbcUrl)
            setProperty("org.quartz.dataSource.itDs.user", jdbcUser)
            setProperty("org.quartz.dataSource.itDs.password", jdbcPassword)
            setProperty("org.quartz.dataSource.itDs.maxConnections", "5")
        }
        return StdSchedulerFactory(props).scheduler
    }

    private fun queryCount(sql: String, vararg args: Any): Int =
        connection().use { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { index, arg -> ps.setObject(index + 1, arg) }
                ps.executeQuery().let { rs -> rs.next(); rs.getInt(1) }
            }
        }

    private fun update(sql: String) {
        connection().use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    /** Counts on the node that ran it, then records the same fact in the shared table. */
    class CountingJob : Job {
        override fun execute(context: JobExecutionContext) {
            val instance = context.scheduler.metaData.schedulerInstanceId
            fires.computeIfAbsent(instance) { AtomicInteger() }.incrementAndGet()
            connection().use { c ->
                c.prepareStatement("INSERT INTO it_cluster_fire (instance_name, fire_time) VALUES (?, ?)").use { ps ->
                    ps.setString(1, instance)
                    ps.setLong(2, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
        }

        companion object {
            val fires = ConcurrentHashMap<String, AtomicInteger>()
        }
    }

    companion object {
        private const val CLUSTER_NAME = "HarnaxClusterIT"
        private const val NODE_ONE = "cluster-node-one"
        private const val NODE_TWO = "cluster-node-two"

        private val jdbcUrl: String get() = mysql.jdbcUrl
        private val jdbcUser: String get() = mysql.username
        private val jdbcPassword: String get() = mysql.password

        private fun connection() = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)
    }
}
```

- [ ] **Step 7: 本地跑不了就证明它被干净跳过**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler -am test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > t7.log 2>&1; echo EXIT=$?
grep -c "Running com.agnetix.harnax.scheduler.it" t7.log   # 期望 0：*IT 不在 surefire 阶段
```
期望：EXIT=0 且 0。有 Docker 的机器上再跑：
```bash
$MVN -o -pl harnax-scheduler -am verify -Pintegration-test
```

- [ ] **Step 8: Commit**

```bash
git add harnax-scheduler/pom.xml harnax-scheduler/src/test
git commit -m "test(调度): 集成测试脚手架与集群单触发/对账收敛/guard 清理用例"
```

---

### Task 8: 文档与状态同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md`（§7 里程碑表 S2 行、§6.1、§9 F9）
- Modify: `prod_doc/agent-task-scheduler.zh-CN.md`（部署形态、对账语义、QRTZ 临时落 admin 库的说明）

- [ ] **Step 1: 更新 spec 状态**

§7 表 S2 行状态改为「✅ 已完成（QRTZ 集群 + reconcile + 部署形态；业务表迁移随 S3）」，并在表下方"里程碑修正"处补第三条：

> **修正 C**：D8 的 `agent_task` 迁移不能与域搬迁分开——admin 还在写这张表时，它只能在 `harnax_admin` 里有一份真相。本发布只做到 QRTZ 层，`QRTZ_*` 临时建在 `harnax_admin`（自有历史表 `flyway_schema_history_scheduler`），S3 切库时由新库 V1 重建 + 一次 reconcile 再生，旧表交运维 DROP。

§9 F9 改为「已随 S2 落地：指标描述与健康 `storeType` detail 已按集群语义改口径；看板阈值需运维重设」。

- [ ] **Step 2: prod_doc 补运维段**

新增小节「双实例部署与逐台滚动」，写清：
1. `docker-compose -f docker-new/docker-compose.yml up -d --scale scheduler=2 --no-recreate scheduler`；
2. 核对集群：`SELECT INSTANCE_NAME, LAST_CHECKIN_TIME FROM harnax_admin.QRTZ_SCHEDULER_STATE;` 期望 2 行，且每 15s 更新；
3. 故障接管：`docker kill` 其一，另一台在 15~75s 内接管未完成 trigger；
4. **禁止**再用 `deploy-service.sh` 的 `--force-recreate` 路径（已改为 `roll-scheduler.sh`）；
5. 宿主机 NTP 时钟偏差必须 < 1s；
6. 观察一个完整 cron 周期后，`harnax_admin.QRTZ_*` 才能在 S3 之后 DROP（S3 之前它们是活的）。

- [ ] **Step 3: Commit**

```bash
git add docs prod_doc
git commit -m "docs(调度): S2 完成状态与双实例运维说明"
```

---

## 发布 1 完成后的验证清单（交给人 / 有 Docker 的机器）

1. `$MVN -o clean test -Dtest='!com.agnetix.harnax.mapper.**,!com.agnetix.harnax.admin.it.**,!com.agnetix.harnax.channel.service.it.**' -Dsurefire.failIfNoSpecifiedTests=false > verify.log 2>&1; echo EXIT=$?` → **EXIT=0**（本发布必须自己验）。
2. `mvn -o -pl harnax-scheduler -am verify -Pintegration-test` → IT-2/IT-5 绿（**本机 Docker 不可用，此项未验证**）。
3. 真实环境：建库 → `up -d --scale scheduler=2` → `QRTZ_SCHEDULER_STATE` 两行 → kill 一台看接管 → 编辑任务 cron 后 `QRTZ_CRON_TRIGGERS.CRON_EXPRESSION` 变、未编辑任务的 `PREV_FIRE_TIME` 不清零。
4. 明确不在本发布范围：`harnax_scheduler` 数据源切换、三张业务表迁移、C1 sessionId、域搬迁、one-shot 合并、F3-A、call-logs 过滤。
