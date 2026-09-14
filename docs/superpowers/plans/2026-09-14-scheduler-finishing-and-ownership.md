# 调度收口与会话归属（发布 3）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把手动"立即执行"并入 Quartz one-shot（让它和 cron 一样受优雅停机保护），并补上两处会话归属缺口：`chn-` 的跨租户可读、router 调用日志无租户过滤。

**Architecture：** 三条互不依赖的改动线。① `SchedulerServiceImpl.triggerManually` 与 `runTaskOnce` 合并为 one-shot 投递（删裸线程与 `taskExecutor` bean）；② admin 的 `getSessionInfo` 学会按 `chn-` 前缀回答归属租户，router 的 `SessionAccessGuard` 于是从"前缀规则"升级为"归属比较"；③ router 的 `/monitor/call-logs` 在查询层按调用方租户收口。

**Tech Stack：** Kotlin 2.2.20、Spring Boot 4.0.1、Quartz 2.5.2、MyBatis XML、MySQL 8 + SQLite（router 侧既有测试形态）、PageHelper 2.1.0、Testcontainers 1.21.4（不新增）。

## Global Constraints

- 注释语言：scheduler / admin / router 模块**英文**；`docs/`、`prod_doc/` 中文。
- JobDataMap 只能放字符串（`useProperties: true`）；组判定只能用 job/trigger group，不能用 data-map 标记。
- 一次性 job 的组是 `TaskQuartzRegistrar.GROUP_ONCE`，常驻 cron job 的组是 `TaskQuartzRegistrar.GROUP_AGENT_TASK`——两者语义区别是本发布的分叉点，不得混用字面量。
- `40901`（执行已在进行）/`40902`（已存库未被调度）/`40903`（本实例禁用调度）三个业务码语义不变；webui/CLI/小程序的 HTTP 契约不变。
- 不新增依赖版本；`harnax-scheduler` 的 `*IT` 已由发布 1 挂在 failsafe + `-Pintegration-test` 下，本发布沿用，**本机无 Docker，IT 只写不跑**。
- 每次改完必须 `mvn -q spotless:apply` 再编译；mvn 输出重定向到文件并自己 `echo $?`，不要 `| tail`。
- 本机命令模板：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
$MVN -o -pl harnax-scheduler test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > s3.log 2>&1; echo EXIT=$?
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > a3.log 2>&1; echo EXIT=$?
$MVN -o -pl harnax-session-router test > r3.log 2>&1; echo EXIT=$?
```

> 注意：`-am` 在本域无用——没有任何模块 Maven 依赖 `harnax-scheduler`，admin 是通过 HTTP 调它的。

---

### Task 1: 手动执行并入 Quartz one-shot

**Files:**
- Modify: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerServiceImpl.kt`（`triggerManually`、`runTaskOnce`）
- Modify: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/service/SchedulerService.kt`
- Delete: `harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/config/SchedulerConfig.kt` 里的 `taskExecutor()` bean
- Modify: `harnax-scheduler/src/main/resources/application.yml` 与 `docker-new/docker-compose.yml` 的停机注释（它们当前明确写着"grace 只覆盖 cron 路径"）
- Test: `.../service/impl/SchedulerScheduleTaskTest.kt`（改）、`.../service/impl/SchedulerManualTriggerTest.kt`（新建）

**Interfaces:**
- Consumes: `TaskQuartzRegistrar.GROUP_ONCE`、`TaskQuartzRegistrar.KEY_TASK_ID`、`blocksManualRun(task)`、`executionGuard.tryAcquireLock`（由 job 在 fire 时调，不在投递时调）。
- Produces: `SchedulerService.runTaskOnce(id: Long): Boolean` 成为唯一实现；`triggerManually` 从接口与实现中删除。`/tasks/{id}/trigger` 端点保留（admin 与 CLI 在用），语义变为"投递 one-shot"。

- [ ] **Step 1: 写失败的测试**

新建 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/service/impl/SchedulerManualTriggerTest.kt`：

```kotlin
package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.client.RouterClient
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.Trigger
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import java.time.LocalDateTime

/**
 * A manual run has to be a Quartz job, not a thread.
 *
 * The reason is not tidiness: `waitForJobsToCompleteOnShutdown` waits for Quartz worker threads, so a run
 * started on a bare thread is invisible to it and to the container's 400s grace — restarting the scheduler
 * mid-manual-run cut that execution into an `agent_task_log` row at 3 and a lock row at 0 for the sweeper
 * to reap up to 2× timeout later. Same shape as the cron path, same protection.
 */
class SchedulerManualTriggerTest {

    private val quartz: Scheduler = mock(Scheduler::class.java)
    private val factory: SchedulerFactoryBean = mock(SchedulerFactoryBean::class.java).apply {
        doReturn(quartz).`when`(this).scheduler
    }
    private val taskMapper: AgentTaskMapper = mock(AgentTaskMapper::class.java)
    private val logMapper: AgentTaskLogMapper = mock(AgentTaskLogMapper::class.java)
    private val routerClient: RouterClient = mock(RouterClient::class.java)
    private val guard: AgentTaskExecutionGuard = mock(AgentTaskExecutionGuard::class.java)
    private val status = SchedulerStatus(schedulerEnabled = true)
    private val metrics: SchedulerMetrics = mock(SchedulerMetrics::class.java)
    private val inventory: QuartzJobInventory = mock(QuartzJobInventory::class.java)
    private val registrar = TaskQuartzRegistrar(factory)

    private val service = SchedulerServiceImpl(
        schedulerFactory = factory,
        agentTaskMapper = taskMapper,
        agentTaskLogMapper = logMapper,
        routerClient = routerClient,
        executionGuard = guard,
        status = status,
        metrics = metrics,
        jobInventory = inventory,
        registrar = registrar,
        reconciler = mock(TaskScheduleReconciler::class.java),
        executionTimeoutSeconds = 300,
        schedulerEnabled = true,
        reconcileIntervalSeconds = 60,
    )

    @Test
    fun `a manual run is scheduled as a one-shot job carrying only its task id`() {
        doReturn(task(7L)).`when`(taskMapper).selectAnyById(7L)
        doReturn(emptyList<Any>()).`when`(logMapper).selectRunningByTaskId(7L)

        assertTrue(service.runTaskOnce(7L))

        val jobCaptor = org.mockito.kotlin.argumentCaptor<JobDetail>()
        val triggerCaptor = org.mockito.kotlin.argumentCaptor<Trigger>()
        verify(quartz).scheduleJob(jobCaptor.capture(), triggerCaptor.capture())
        val detail = jobCaptor.value
        assertEquals(TaskQuartzRegistrar.GROUP_ONCE, detail.key.group)
        assertEquals("7", detail.jobDataMap.getString(TaskQuartzRegistrar.KEY_TASK_ID))
        assertEquals(
            1,
            detail.jobDataMap.size(),
            "the data map must hold nothing but the id: useProperties forbids any other type",
        )
        assertEquals(
            JobKey("AgentTask_7", TaskQuartzRegistrar.GROUP_AGENT_TASK).name.substringBeforeLast("_"),
            detail.key.name.substringBeforeLast("_ONCE").substringBeforeLast("_"),
            "the one-shot keeps the task's identity in its job name so a fire can find it",
        )
        assertEquals(TaskQuartzRegistrar.GROUP_ONCE, triggerCaptor.value.key.group)
    }

    @Test
    fun `a manual run on a task that forbids overlap while one is live is refused`() {
        doReturn(task(7L, concurrent = 0)).`when`(taskMapper).selectAnyById(7L)
        doReturn(listOf(anyLogRow())).`when`(logMapper).selectRunningByTaskId(7L)

        assertEquals(false, service.runTaskOnce(7L))
        verify(quartz, org.mockito.Mockito.never()).scheduleJob(any(), any())
    }

    private fun task(id: Long, concurrent: Int = 0) = AgentTask().apply {
        this.id = id
        this.cronExpression = "0 0 4 * * ?"
        this.concurrent = concurrent
        this.prompt = "p"
        this.name = "task-$id"
    }

    private fun anyLogRow() = com.agnetix.harnax.entity.AgentTaskLog().apply { this.taskId = 7L }
}
```

> 构造函数参数以 `SchedulerServiceImpl` 当前真实签名为准（发布 1 之后已含 `registrar`、`reconciler`、`reconcileIntervalSeconds`）。实现时先读那个类，把不匹配的参数删掉或补齐；`assertSame`/`eq`/`anyBoolean`/`anyOrNull`/`JobDataMap`/`LocalDateTime` 这些 import 用不到就删。

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-scheduler test-compile > s3a.log 2>&1; echo EXIT=$?
```
期望：非 0——`triggerManually` 仍在接口上，且 `runTaskOnce` 走的是"实体进 data map"的旧形态（若发布 1 已改成 id，则失败点是断言 `scheduleJob(detail, trigger)` 两参重载未被调用）。

- [ ] **Step 3: 合并实现**

`SchedulerServiceImpl`：

- `runTaskOnce(id)` 成为唯一投递路径，保持现状结构（先 `blocksManualRun` 再投递），但 job 与 trigger 的 identity 与组改为共用 registrar 常量，且 **不加 `storeDurably()`**（一次性 job 在触发完成后应由 Quartz 自己清掉）：

```kotlin
    /**
     * Run a task once, now, on a Quartz worker thread.
     *
     * Both manual endpoints land here. It is a persisted one-shot rather than a thread on purpose:
     * `waitForJobsToCompleteOnShutdown` and the container's stop_grace_period can only protect work the
     * scheduler knows about, and with a JDBC store the trigger also survives a crash before the fire —
     * the run is delivered by another node instead of vanishing.
     *
     * No `storeDurably()`: a one-shot job whose trigger has fired and completed is garbage, and Quartz
     * removes it; a durable one would leave a row for reconcile to puzzle over forever.
     */
    override fun runTaskOnce(id: Long): Boolean {
        val task = agentTaskMapper.selectAnyById(id)
            ?: throw RuntimeException("Agent task not found: $id")

        if (blocksManualRun(task)) {
            log.warn("Task {} has an active running execution and allows no overlap, rejecting runOnce", task.id)
            return false
        }

        val uniqueId = UUID.randomUUID().toString().substring(0, 8)
        val jobKey = JobKey("AgentTask_${task.id}_ONCE_$uniqueId", TaskQuartzRegistrar.GROUP_ONCE)
        val jobDetail = JobBuilder.newJob(TaskQuartzRegistrar.jobClassFor(task))
            .withIdentity(jobKey)
            .usingJobData(JobDataMap().apply { put(TaskQuartzRegistrar.KEY_TASK_ID, task.id.toString()) })
            .build()
        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("${jobKey.name}_trigger", TaskQuartzRegistrar.GROUP_ONCE))
            .forJob(jobKey)
            .startNow()
            .build()

        scheduler.scheduleJob(jobDetail, trigger)
        log.info("Scheduled a one-shot execution of task {} ({})", task.id, jobKey.name)
        return true
    }
```

- 删除 `triggerManually` 整个方法与 `Thread { ... }` 裸线程块，删除文件顶部 `java.util.concurrent.Executors` 之外的 `Thread` 相关注释；保留 `loadExecutor`（那是启动加载线程，不是执行线程）。
- `SchedulerService` 删 `triggerManually`，并把 `runTaskOnce` 的 KDoc 改成"两个手动端点共用"。
- `SchedulerController`：`/tasks/{id}/trigger` 与 `/tasks/{id}/run-once` 都调 `runTaskOnce`，都保留 `requireEnabled` 前置与 `CODE_EXECUTION_IN_PROGRESS`(40901) 冲突分支；两个成功文案可保持不同（"Task triggered" / "Task run once scheduled"）以免动到 webui 断言。
- `TaskQuartzRegistrar`：把 `jobClassFor` 移到 companion object（`runTaskOnce` 需要在实例方法外复用），并保持 `register()` 用同一个函数：

```kotlin
    companion object {
        const val GROUP_AGENT_TASK = "AgentTaskGroup"

        /** One-shot runs live here so reconcile — which converges [GROUP_AGENT_TASK] — never touches them. */
        const val GROUP_ONCE = "AgentTaskGroup_ONCE"

        const val KEY_TASK_ID = "taskId"

        private const val JOB_NAME_PREFIX = "AgentTask_"

        /** The registered class is the only channel that carries `concurrent` into Quartz. */
        fun jobClassFor(task: AgentTask): Class<out Job> = if (task.concurrent == 0) {
            AgentTaskNonConcurrentJob::class.java
        } else {
            AgentTaskJob::class.java
        }
    }
```

- `SchedulerConfig.kt`：删除 `taskExecutor()` bean 与 `ThreadPoolTaskExecutor`/`Duration` 相关 import（`Duration` 若 `restClient()` 仍在用则保留），并删掉那句 "Async thread pool for manual task execution" 注释——它描述的正是"没人用"的假象。
- `application.yml` 里 `wait-for-jobs-to-complete-on-shutdown` 上方的注释：把"What it waits for, though, is only what Quartz knows about: the cron path and the /run-once one-shots. A manual /trigger runs on a bare daemon thread … plan S4 closes that gap." 改成事实：手动执行现在也走 Quartz worker，因此与 cron 同等受保护；保留 `stop_grace_period` 的算式说明（数值不变，因为算的是"一次执行最坏占多久"，与路径无关）。
- `docker-new/docker-compose.yml` 的 `# THIS GRACE COVERS THE CRON PATH ONLY …` 段落整段替换为：grace 覆盖所有执行路径（cron 与手动 one-shot 都在 Quartz worker 上），并把"不要在执行中重启 scheduler"的告诫删掉——那正是本任务消灭的前提。保留算式注释。

- [ ] **Step 4: 跑测试**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > s3b.log 2>&1; echo EXIT=$?; grep -E "Tests run:.*(Fail|Err)|BUILD" s3b.log | tail -3
```
期望：EXIT=0。`SchedulerScheduleTaskTest` 若断言的是 `triggerManually`，改为断言 `runTaskOnce`（同一形状），不要删用例。

- [ ] **Step 5: admin 侧确认无感**

```bash
$MVN -o -pl harnax-admin test -Dtest='!com.agnetix.harnax.admin.it.**' -Dsurefire.failIfNoSpecifiedTests=false > s3c.log 2>&1; echo EXIT=$?
grep -rn "triggerManually" harnax-admin harnax-webui/src harnax-cli --include="*.kt" --include="*.ts" --include="*.tsx" --include="*.go"
```
期望：EXIT=0，且 grep 只命中 `/trigger` 路径字符串（admin 走 HTTP，方法名不在它那里）。若 webui/CLI 断言了成功文案，保持不变即通过。

- [ ] **Step 6: Commit**

```bash
git add harnax-scheduler harnax-admin docker-new/docker-compose.yml
git commit -m "feat(调度): 手动执行并入 Quartz one-shot，停机保护覆盖手动路径"
```

---

### Task 2: one-shot 落库与补火语义的集成测试（IT-4）

**Files:**
- Create: `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/ManualRunPersistenceIT.kt`
- Test: 复用 `harnax-scheduler/src/test/kotlin/com/agnetix/harnax/scheduler/it/BaseSchedulerIT.kt`

**Interfaces:**
- Consumes: `BaseSchedulerIT`（真库 + 真 Quartz + Flyway 建好的 `QRTZ_*`）、`SchedulerService.runTaskOnce`、`TaskQuartzRegistrar.GROUP_ONCE`。
- Produces: 无生产接口，只有证据。

- [ ] **Step 1: 写 IT**

```kotlin
package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * IT-4: a manual run is a *persisted* one-shot, which is the whole reason Task 1 moved it into Quartz.
 *
 * The scheduler is put in standby first: the投递 must be visible as a `QRTZ_TRIGGERS` row rather than as a
 * finished execution. Then starting it delivers exactly one execution row for that task. Together those
 * two are the claim "a crash between the click and the fire costs the user nothing": the work exists in
 * shared state before any thread runs it, and exactly one node picks it up.
 *
 * Unexecuted on the machine that wrote it (no Docker); the first real run belongs to the acceptance host:
 * `mvn -o -pl harnax-scheduler verify -Pintegration-test`.
 */
class ManualRunPersistenceIT : BaseSchedulerIT() {

    @Autowired private lateinit var service: SchedulerService
    @Autowired private lateinit var schedulerFactory: SchedulerFactoryBean
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a manual run is stored before it fires and fires exactly once`() {
        val scheduler = schedulerFactory.scheduler
        jdbc.update(
            "INSERT INTO agent_task (id, tenant_id, name, agent_id, prompt, cron_expression, task_status, concurrent, timeout_seconds, active, creator) " +
                "VALUES (?, 1, ?, 1, 'p', '0 0 4 * * ?', 1, 0, 300, 1, 'admin')",
            4001L,
            "it-manual-4001",
        )
        try {
            // Standby, not pause: pause would move the trigger to a PAUSED_* state and the point of the
            // read below is the state a *waiting* persisted one-shot has while no node is acquiring.
            scheduler.standby()
            assertTrue(service.runTaskOnce(4001L), "the run must be accepted")

            val states = jdbc.queryForList(
                "SELECT TRIGGER_STATE FROM QRTZ_TRIGGERS WHERE SCHED_NAME = ? AND TRIGGER_GROUP = ?",
                String::class.java,
                scheduler.metaData.schedulerName,
                TaskQuartzRegistrar.GROUP_ONCE,
            )
            assertEquals(1, states.size, "the one-shot must exist in the shared store")
            assertEquals("WAITING", states.single(), "a not-yet-fired stored trigger waits")

            scheduler.start()
            val started = awaitUntil(30_000) {
                count("SELECT COUNT(*) FROM agent_task_log WHERE task_id = 4001") >= 1
            }
            assertTrue(started, "starting the scheduler must deliver the waiting one-shot")
            // threadCount is 3 in the IT profile and this task forbids overlap, so a second delivery of the
            // same trigger would show up here as 2 rows, not as a lost run.
            assertEquals(1, count("SELECT COUNT(*) FROM agent_task_log WHERE task_id = 4001"))
            assertEquals(
                0,
                count("SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE SCHED_NAME = ? AND TRIGGER_GROUP = ?", scheduler.metaData.schedulerName, TaskQuartzRegistrar.GROUP_ONCE),
                "a completed one-shot must not linger in the store",
            )
            assertNotNull(scheduler)
        } finally {
            scheduler.shutdown(false)
            jdbc.update("DELETE FROM agent_task_log WHERE task_id = 4001")
            jdbc.update("DELETE FROM agent_task WHERE id = 4001")
        }
    }

    private fun count(sql: String, vararg args: Any): Int =
        jdbc.queryForObject(sql, Int::class.java, *args) ?: 0

    private fun awaitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(200)
        }
        return false
    }
}
```

> `finally` 里的 `scheduler.shutdown(false)` 会把 Spring 管理的调度器关掉，后续 IT 类若复用同一上下文会受影响——**实现时改为** `scheduler.start()` 之前不 shutdown、用例末尾 `schedulerFactory.scheduler.clear()` 不需要；正确收尾是把 `standby()`/`start()` 之外的一切留给 Spring 生命周期：只删自己插的两张表的行。请在实现时验证这一点并把注释改对（不要留下一个会污染兄弟用例的 `shutdown`）。同时 `assertNotNull(scheduler)` 是无意义断言，删掉。

- [ ] **Step 2: 编译 + 默认阶段不跑到它**

```bash
$MVN -q spotless:apply -pl harnax-scheduler
$MVN -o -pl harnax-scheduler test-compile > s3d.log 2>&1; echo EXIT=$?
$MVN -o -pl harnax-scheduler test -Dtest='!com.agnetix.harnax.mapper.**' -Dsurefire.failIfNoSpecifiedTests=false > s3e.log 2>&1; echo EXIT=$?
grep -c "Running com.agnetix.harnax.scheduler.it" s3e.log   # 期望 0
```

- [ ] **Step 3: 把"未执行"写进报告而不是假装通过**

在任务报告里明确：本 IT 从未执行；它首轮在 Docker 机器上的输出即为验收数据。

- [ ] **Step 4: Commit**

```bash
git add harnax-scheduler/src/test
git commit -m "test(调度): one-shot 落库与单次投递的集成测试"
```

---

### Task 3: `chn-` 的归属可判定（F3-A）

**Files:**
- Create: `harnax-admin/src/main/resources/db/migration/V28__add_channel_session_id_index.sql`
- Modify: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/InternalApiController.kt`（`getSessionInfo`，`:153-165`）
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixes.kt`（注释与残留清单说明）
- Test: `harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/controller/InternalApiControllerTest.kt`（新增 `getSessionInfo` 用例）、`harnax-admin/src/test/kotlin/com/agnetix/harnax/admin/it/InternalApiIT.kt`（新增 chn 归属用例）、`harnax-session-router/src/test/kotlin/com/agnetix/harnax/router/support/PrivilegedSessionPrefixesTest.kt`（残留清单转空）

**Interfaces:**
- Consumes: `ChannelMapper.selectBySessionId(@Param("sessionId") sessionId: String): Channel?`（`harnax-entity/.../mapper/ChannelMapper.kt:35`，SQL 带 `active = 1`），`Channel.tenantId: Long`（`harnax-entity/.../entity/Channel.kt:25`）、`Channel.agentId`（`:34`）。
- Produces: `GET /api/admin/internal/sessions/{id}/info` 对 `chn-` 返回 `Found(tenantId=channel.tenantId, agentId=channel.agentId)`；`SessionInfoResponse` 字段不变（router 侧 `SessionInfo` 镜像同名字段，无需改 router 代码即生效）。

- [ ] **Step 1: 写失败的 admin 测试**

`InternalApiControllerTest.kt` 追加（该文件已 mock `channelMapper`，见 `:92`）：

```kotlin
    @Test
    fun `a channel session reports the tenant that owns the channel`() {
        val sessionId = "chn-11111111-2222-3333-4444-555555555555"
        doReturn(Channel().apply { this.sessionId = sessionId; this.tenantId = 7L; this.agentId = 3L })
            .`when`(channelMapper).selectBySessionId(sessionId)

        val response = controller.getSessionInfo(sessionId).data

        assertNotNull(response)
        assertEquals(7L, response!!.tenantId)
        assertEquals(3L, response.agentId)
        assertEquals(sessionId, response.sessionId)
    }

    @Test
    fun `an unknown channel session is still answered as unknown rather than guessed`() {
        doReturn(null).`when`(channelMapper).selectBySessionId("chn-does-not-exist")

        assertNull(controller.getSessionInfo("chn-does-not-exist").data)
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -o -pl harnax-admin test -Dtest=InternalApiControllerTest -Dsurefire.failIfNoSpecifiedTests=false > f3a.log 2>&1; echo EXIT=$?
```
期望：FAIL（`getSessionInfo` 目前只查 `session` 表，返回 null）。

- [ ] **Step 3: 实现归属回答**

`InternalApiController.getSessionInfo` 在现有 `sessionMapper` 查询之前按前缀分流（保持"未知即返回 null data"的既有语义，不要改成异常）：

```kotlin
    /**
     * Which tenant owns this session — the answer the router compares its caller's tenant against.
     *
     * Only the `session` table used to be consulted, so a `chn-` id (which lives in `channel`, by design,
     * and is stamped at channel creation) came back "unknown" and the router's guard let it through:
     * the guard could not protect a grant it could not look up. `task-` is deliberately not answered here
     * either — it is refused before the lookup by the router's prefix rule for any caller with an end user
     * behind it, and its owner will come from the scheduler's owner endpoint once this domain moves.
     */
    @GetMapping("/sessions/{sessionId}/info")
    fun getSessionInfo(@PathVariable sessionId: String): ResultVo<SessionInfoResponse?> {
        if (sessionId.startsWith("chn-")) {
            val channel = channelMapper.selectBySessionId(sessionId)
                ?: return ResultVo.success(null)
            return ResultVo.success(
                SessionInfoResponse(
                    sessionId = sessionId,
                    agentId = channel.agentId,
                    agentName = null,
                    modelId = null,
                    modelName = null,
                    tenantId = channel.tenantId,
                ),
            )
        }
        // ... existing sessionMapper lookup unchanged
    }
```

> `SessionInfoResponse` 是 `InternalApiController.kt:86-93` 的嵌套 data class，字段顺序按它来；`Channel.agentId` 类型以实体为准（`Long`）。若 webui 频道页此前依赖"`chn-` 查不到租户"从而一直走放行分支，本改动不改变它的结果——同租户仍然放行。

- [ ] **Step 4: 给 `channel.session_id` 加索引**

这个查询是 router 每次会话级代理调用都可能打到的（前面有 5 分钟 Caffeine，但缓存 miss 就是它）。`channel` 表当前只有 `callback_key`/`agent_id`/`(type,enabled,status,active)` 索引：

`harnax-admin/src/main/resources/db/migration/V28__add_channel_session_id_index.sql`：

```sql
-- The router asks "which tenant owns this session" for every session-scoped proxy call, and for a `chn-`
-- id that question is now answered from channel.session_id. Without this index the lookup is a full scan of
-- a table every channel message hits. Created IF NOT EXISTS-style is not available for MySQL indexes, so
-- the migration is guarded by the dictionary instead.
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel' AND INDEX_NAME = 'idx_session_id'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE `channel` ADD INDEX `idx_session_id` (`session_id`)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

并在 `harnax-entity/src/test/resources/schema-test.sql` 的 `channel` 建表段同步加 `INDEX idx_session_id (session_id)`（若该文件里有 channel 表；没有则跳过并在报告说明）。

- [ ] **Step 5: router 侧把"残留清单"改空**

`PrivilegedSessionPrefixesTest.kt` 的 `ADMIN_RESOLVED_BUT_NOT_REFUSED = listOf("chn-")`（`:166`）改为 `emptyList()`，并把 `PrivilegedSessionPrefixes.kt` 里"admin's `/sessions/{id}/info` reads only the `session` table, so it answers Unknown"那两段（`:31-39`）改为事实：`chn-` 的归属现在由 admin 从 `channel` 表回答，因此跨租户的 `chn-` 读会被既有租户比较拒绝；`task-` 仍是"前缀规则 + 归属待发布 2 补"。

```bash
$MVN -o -pl harnax-session-router test > f3b.log 2>&1; echo EXIT=$?; grep -E "Tests run:.*(Fail|Err)|BUILD" f3b.log | tail -3
```
期望：EXIT=0（`SessionAccessGuardTest:157` 那条"once admin can answer chn-, cross-tenant is refused"本来就是绿的对 `Found` 的断言，本任务让它第一次真正可达）。

- [ ] **Step 6: 记录一个已知窗口**

`SessionInfoClient`（`harnax-session-router/.../service/SessionInfoClient.kt:32-47`）会缓存 `Unknown` 5 分钟：发布后最多 5 分钟内，已缓存 `Unknown` 的 `chn-` 仍按放行处理。把这一句写进代码注释与 prod_doc，不要假装不存在。

- [ ] **Step 7: Commit**

```bash
git add harnax-admin harnax-session-router harnax-entity/src/test/resources/schema-test.sql
git commit -m "fix(安全): chn- 会话归属可由 admin 回答，跨租户读取被拒"
```

---

### Task 4: router 调用日志按租户收口

**Files:**
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/dto/ApiCallLogQuery.kt`
- Modify: `harnax-session-router/src/main/resources/mapper/ApiCallLogMapper.xml`（`queryWhere`，`:31-52`）
- Modify: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/RouterMonitorController.kt`（`queryCallLogs`，`:72-95`）
- Test: `.../controller/RouterMonitorControllerTest.kt`、`.../integration/SqliteMapperIntegrationTest.kt`

**Interfaces:**
- Consumes: `AuthContextHolder.get(): AuthContext?`（`tenantId: Long?`）、`ApiCallLogService.query(query)`。
- Produces: `ApiCallLogQuery.tenantId: Long? = null`；`queryWhere` 单点新增谓词（`query` 与 `count` 共用该片段）。

- [ ] **Step 1: 写失败的测试**

`SqliteMapperIntegrationTest.kt` 追加（该文件已在 `@TempDir` SQLite 上跑真 XML）：

```kotlin
    @Test
    fun `query filters by tenant and leaves unattributed rows to the operator view`() {
        insert(sessionId = "web-a", tenantId = 1, callerId = "k1")
        insert(sessionId = "web-b", tenantId = 2, callerId = "k2")
        insert(sessionId = "web-c", tenantId = null, callerId = "internal")

        val tenantOne = mapper.query(
            ApiCallLogQuery(tenantId = 1, limit = 10, offset = 0),
        )
        assertEquals(listOf("web-a"), tenantOne.map { it.sessionId })
        assertEquals(1, mapper.count(ApiCallLogQuery(tenantId = 1, limit = 10, offset = 0)))

        // The unattributed row is an internal caller's; only a caller with no tenant may see it, which is
        // the same "no tenant means internal" convention the session guard already runs on.
        val all = mapper.query(ApiCallLogQuery(limit = 10, offset = 0))
        assertEquals(3, all.size)
    }
```

`RouterMonitorControllerTest.kt` 追加（`AuthContextHolder` 需要 set/clear；该测试是 standalone MockMvc）：

```kotlin
    @Test
    fun `a tenant-scoped caller cannot read another tenant's logs`() {
        AuthContextHolder.set(AuthContext(callerId = "key", userId = 9L, tenantId = 3L))
        try {
            mockMvc.perform(get("/api/router/monitor/call-logs")).andExpect(status().isOk)
            val captor = argumentCaptor<ApiCallLogQuery>()
            verify(apiCallLogService).query(captor.capture())
            assertEquals(3L, captor.firstValue.tenantId)
        } finally {
            AuthContextHolder.clear()
        }
    }

    @Test
    fun `a query parameter cannot widen a caller's own tenant scope`() {
        AuthContextHolder.set(AuthContext(callerId = "key", userId = 9L, tenantId = 3L))
        try {
            mockMvc.perform(get("/api/router/monitor/call-logs").param("tenantId", "4"))
                .andExpect(status().isOk)
            val captor = argumentCaptor<ApiCallLogQuery>()
            verify(apiCallLogService).query(captor.capture())
            assertEquals(3L, captor.firstValue.tenantId)
        } finally {
            AuthContextHolder.clear()
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**
```bash
$MVN -o -pl harnax-session-router test-compile > cl1.log 2>&1; echo EXIT=$?
```
期望：非 0（`tenantId` 参数不存在）。

- [ ] **Step 3: 实现**

`ApiCallLogQuery` 增加 `val tenantId: Long? = null`，并在类注释里写明"这是服务端从调用方身份推导的，不是查询条件"。

`ApiCallLogMapper.xml` 的 `queryWhere`（`query` 与 `count` 共用，改一处即可）：

```xml
        <if test="tenantId != null">
            AND tenant_id = #{tenantId}
        </if>
```

`RouterMonitorController.queryCallLogs` 构造 `ApiCallLogQuery` 时注入服务端推导的租户，并**不接受**任何来自请求的租户参数：

```kotlin
        // Scope by the caller's own tenant, derived server-side from the credential the auth filter
        // already checked. A caller with no tenant is an internal service or a SYSTEM key and keeps the
        // full view — the same convention the session guard uses for its pass-through. Note the consequence
        // in data terms: rows written by internal callers carry tenant_id NULL and are therefore invisible
        // to a tenant caller. That is intended, not a bug to "fix" by widening the filter.
        val callerTenant = AuthContextHolder.get()?.tenantId
```

并把 `tenantId` 加进 `ApiCallLogQuery(...)` 构造。

- [ ] **Step 4: 跑测试**

```bash
$MVN -q spotless:apply -pl harnax-session-router
$MVN -o -pl harnax-session-router test > cl2.log 2>&1; echo EXIT=$?; grep -E "Tests run:.*(Fail|Err)|BUILD" cl2.log | tail -3
```
期望：EXIT=0。

- [ ] **Step 5: Commit**

```bash
git add harnax-session-router
git commit -m "fix(路由): 调用日志按调用方租户收口，内部无租户调用方保留全量视图"
```

---

### Task 5: 文档与验收清单同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-11-scheduler-cluster-design.md`（§4.2 状态、§9 F3-A、§7 里程碑、§11 验收）
- Modify: `prod_doc/agent-task-scheduler.zh-CN.md`（手动执行路径、`chn-` 归属、call-logs 作用域）
- Modify: `docs/deploy-harnax-scheduler.md`（发布 1 留下的两处：`reloadSchedulersAfterCommit`/`notifySchedulersNow` 命名说明；`roll-scheduler.sh` 措辞债）
- Modify: `harnax-session-router/README.md` 或 `docs/deploy-harnax-session-router.md`（call-logs 的可见范围）

- [ ] **Step 1: 改到与代码一致**

必须落地的条目：
1. spec §4.2 从"计划合并 one-shot"改为已完成，并把"删 `taskExecutor`"记为已做；§7 表 S4 行状态更新；§9 F3 的 A 项标注 `chn-` 已闭环、`task-` 归属待发布 2 的 owner 端点。
2. spec §11 验收清单第 3 条（"带执行中任务重启 scheduler"）**去掉"不要在手动执行中重启"的例外**，并把第 5 条 webui 流程加上"对已暂停任务点立即执行仍会跑"。
3. prod_doc 的停机保护段落改成"cron 与手动 one-shot 都在 Quartz worker 上，因此同受 400s grace 保护"，并保留算式；新增"一次手动执行现在会占用一个 Quartz worker，最坏并发 = `QUARTZ_THREAD_COUNT`"这条容量说明。
4. 新增 call-logs 作用域说明：**内部（无租户）调用方看全量，租户调用方只看自己**，且 `tenant_id IS NULL` 的行属于前者。
5. 明确写出仍未修的已知窗口：`stop` 意图只存在于 `status=4` 这一瞬态（F11）；`SessionInfoClient` 对 `Unknown` 的 5 分钟缓存导致 `chn-` 归属在发布后最多 5 分钟内仍按旧结果。

- [ ] **Step 2: 校验残留**

```bash
grep -rn "bare daemon thread\|裸线程\|taskExecutor\|15~75\|不要去开\|scheduler.load.attempts" docs prod_doc docker-new harnax-scheduler harnax-admin --include="*.md" --include="*.yml" --include="*.sh" --include="*.kt" | grep -v "docs/superpowers/plans" || echo CLEAN
```
期望：`CLEAN` 或每条都有明确的"已修/禁止回加"上下文。

- [ ] **Step 3: Commit**

```bash
git add docs prod_doc harnax-scheduler harnax-admin docker-new
git commit -m "docs: 调度收口与会话归属的文档同步"
```

---

## 发布 3 的验收标准

1. `mvn -o clean test`（排除三个容器测试包）EXIT=0；`harnax-scheduler`、`harnax-admin`、`harnax-session-router` 全绿。
2. `mvn -o -pl harnax-scheduler verify -Pintegration-test` 在有 Docker 的机器上 IT-1/IT-2/IT-4/IT-5 全绿（**本机未执行**）。
3. 真实环境：对一个**已暂停**的任务点"立即执行"→ 会跑（one-shot 不受 taskStatus 守卫管辖）；执行中 `docker restart` scheduler 容器 → 该行最终是真实结果而不是 2 timeout；`/api/router/monitor/call-logs` 用租户 A 的 key 看不到租户 B 的行。
4. 跨租户 `chn-`：用租户 A 的登录态请求租户 B 频道会话的历史/工作区 → 被 `SecurityException` 拒（此前恒放行）。
