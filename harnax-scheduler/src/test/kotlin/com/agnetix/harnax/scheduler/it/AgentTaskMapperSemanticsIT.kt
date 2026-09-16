package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskExecution
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.mapper.AgentTaskExecutionMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

/**
 * The three task mappers, one by one, against a real MySQL.
 *
 * This file is the merge of `AgentTaskMapperTest`, `AgentTaskLogMapperTest` and `AgentTaskExecutionMapperTest`
 * from `harnax-entity`, which came with the domain when release 2 moved `agent_task`, `agent_task_log` and
 * `agent_task_execution` here. Every case survived the move; what changed is where the rows come from, and the
 * three reasons for that are worth stating because they are the reason a moved test is not a copied one:
 *
 * **The schema is Flyway's.** Those suites built their database from `harnax-entity`'s `schema-test.sql`, which
 * is a hand-maintained copy of admin's DDL. Here the container is migrated by this module's own
 * `V1`/`V2`, so what is under test is the schema the service actually ships — including the 128-character
 * `session_id` of contract C1, which the old copy still had at 64. The case named
 * `the schema is owned by the migration and not by the IT init script` pins that, and it is the only one here
 * that is not a copy of something.
 *
 * **There are no seed rows anywhere in this module.** The old suite could ask for log id 1 and get the row
 * `schema-test.sql` had inserted. The other ITs of this package count rows — IT-1 counts what one reconcile
 * round wrote, IT-5 asserts exact lock counts — so a shared seed would break them, and every case below
 * therefore inserts what it measures and deletes it again in [removeSeededRows]. Fixed names became per-run
 * ones, because `uk_name` is a global unique key on a shared container. The two cases that asserted an exact
 * number of swept rows (`deleteOldLogs`, `deleteStaleRunning`) now compare the sweep's return value with the
 * eligible population read back under the sweep's own predicate immediately before it ran, and still assert
 * that population is the one row this class wrote: same strength, and no longer movable by a row this class
 * did not write. The `expireStale` cases keep their original "at least N reclaimed" plus every per-row
 * assertion they had.
 *
 * **No case was dropped or loosened.** Where a seed row carried an expectation ("the finished row id=1 stays
 * finished"), the same row is now inserted by the case that needs it.
 *
 * NEVER EXECUTED ON THE AUTHORING MACHINE: it has no Docker daemon, so not one case below has run against a
 * real MySQL here. It is `@Tag("integration")` through [BaseSchedulerIT] and named `*IT`, so surefire skips it
 * and only `mvn -Pintegration-test verify` on a Docker host will ever colour it green or red. Acceptance
 * criterion 3 of the release plan is that run; nothing in this repo claims it happened.
 */
class AgentTaskMapperSemanticsIT : BaseSchedulerIT() {

    @Autowired
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Autowired
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Autowired
    private lateinit var executionMapper: AgentTaskExecutionMapper

    /** Rows this class wrote, deleted again after every case: the container is shared with the other ITs. */
    private val seededTaskIds = mutableListOf<Long>()
    private val seededLogIds = mutableListOf<Long>()

    /** Distinct per case, so a keyword search over the whole table can only ever answer with my own rows. */
    private val runTag = "it8" + System.nanoTime()

    @AfterEach
    fun removeSeededRows() {
        if (seededLogIds.isNotEmpty()) {
            jdbc.update("DELETE FROM agent_task_log WHERE id IN (${seededLogIds.joinToString(",")})")
            seededLogIds.clear()
        }
        if (seededTaskIds.isNotEmpty()) {
            // Hard delete: `deleteById` is a soft one, and a row that outlives its case would still be
            // counted by whoever measures this table next.
            jdbc.update("DELETE FROM agent_task WHERE id IN (${seededTaskIds.joinToString(",")})")
            jdbc.update("DELETE FROM agent_task_execution WHERE task_id IN (${seededTaskIds.joinToString(",")})")
            seededTaskIds.clear()
        }
    }

    // ==================== Who owns the schema ====================

    /**
     * The trap this release could have walked into: `BaseSchedulerIT` runs its init script *before* Flyway, so
     * a duplicate `CREATE TABLE IF NOT EXISTS` of these three in `schema-it.sql` would have won and V2 would
     * have no-oped over it — leaving the ITs on the pre-C1 64-character `session_id` while production ran on
     * 128. Asserting the width is asserting that the migration, not a test resource, built this database.
     */
    @Test
    @DisplayName("表结构来自 V2 迁移而不是 IT 初始化脚本")
    fun `the schema is owned by the migration and not by the IT init script`() {
        assertEquals(
            128,
            columnWidth("agent_task_log", "session_id"),
            "`session_id` has to be the C1 width; anything else means a second DDL definition won the race",
        )
        listOf("agent_task", "agent_task_log", "agent_task_execution").forEach { table ->
            assertEquals(
                1,
                requireNotNull(
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        Int::class.java,
                        table,
                    ),
                ) { "no count for $table" },
                "$table has to exist in the migrated schema",
            )
        }
        // And the four-segment id has to actually fit, which a 64-wide column silently refused to do.
        val task = insertTask("it8-c1-width")
        val longSessionId = "task-${task.id}-${task.agentId}-${"0".repeat(64)}"
        assertTrue(longSessionId.length > 64, "the fixture id has to be longer than the pre-C1 column")
        val log = insertLog(task, sessionId = longSessionId)
        assertEquals(longSessionId, agentTaskLogMapper.selectById(log.id)?.sessionId)
    }

    // ==================== AgentTaskMapper: single-row reads ====================

    @Test
    @DisplayName("selectById - 属主能读自己的私有任务")
    fun `selectById should return own private task`() {
        val task = insertTask(TASK_PREFIX + "own-private", creator = ALICE)
        assertNotNull(agentTaskMapper.selectById(task.id, ALICE))
    }

    @Test
    @DisplayName("selectById - 他人能读公开任务")
    fun `selectById should return another user public task`() {
        val task = insertTask(TASK_PREFIX + "shared-public", creator = ALICE, isPublic = 1)
        assertNotNull(agentTaskMapper.selectById(task.id, BOB))
    }

    @Test
    @DisplayName("selectById - 他人读不到私有任务")
    fun `selectById should hide another user private task`() {
        val task = insertTask(TASK_PREFIX + "other-private", creator = ALICE)
        assertNull(agentTaskMapper.selectById(task.id, BOB))
    }

    @Test
    @DisplayName("selectById - 读不到已软删除的任务")
    fun `selectById should skip logically deleted task`() {
        val task = insertTask(TASK_PREFIX + "deleted-task", creator = ALICE)
        assertEquals(1, agentTaskMapper.deleteById(task.id, ALICE))
        assertNull(agentTaskMapper.selectById(task.id, ALICE))
    }

    @Test
    @DisplayName("selectAnyById - 无用户上下文的服务端路径不受可见性限制")
    fun `selectAnyById should ignore visibility`() {
        val task = insertTask(TASK_PREFIX + "engine-lookup", creator = ALICE)
        assertEquals("it8-engine-lookup", agentTaskMapper.selectAnyById(task.id)?.name)
    }

    // ==================== AgentTaskMapper: single-row writes ====================

    @Test
    @DisplayName("updateById - 属主可改")
    fun `updateById should apply for the owner`() {
        val task = insertTask(TASK_PREFIX + "upd-owner", creator = ALICE)
        task.description = "changed"
        assertEquals(1, agentTaskMapper.updateById(task, ALICE))
        assertEquals("changed", agentTaskMapper.selectAnyById(task.id)?.description)
    }

    @Test
    @DisplayName("updateById - 公开任务也不能被他人改写")
    fun `updateById should not apply for a non-owner`() {
        val task = insertTask(TASK_PREFIX + "upd-public", creator = ALICE, isPublic = 1)
        task.description = "hijacked"
        task.prompt = "exfiltrate the creator's data"
        assertEquals(0, agentTaskMapper.updateById(task, BOB))

        val stored = agentTaskMapper.selectAnyById(task.id)
        assertNotNull(stored)
        assertEquals("seed", stored!!.description)
        assertEquals("Summarize today's news", stored.prompt)
    }

    @Test
    @DisplayName("deleteById - 公开任务也不能被他人删除")
    fun `deleteById should not apply for a non-owner`() {
        val task = insertTask(TASK_PREFIX + "del-public", creator = ALICE, isPublic = 1)
        assertEquals(0, agentTaskMapper.deleteById(task.id, BOB))
        assertEquals(1, agentTaskMapper.selectAnyById(task.id)?.active)

        assertEquals(1, agentTaskMapper.deleteById(task.id, ALICE))
        assertNull(agentTaskMapper.selectAnyById(task.id))
    }

    // ==================== AgentTaskLogMapper: basic CRUD and the status machine ====================

    @Test
    @DisplayName("selectById - 根据 ID 查询日志")
    fun `selectById should return log by id`() {
        val task = insertTask(TASK_PREFIX + "log-read")
        val log = insertLog(
            task,
            status = 1,
            prompt = "Summarize today's news",
            response = "Here is the summary...",
        )

        val found = agentTaskLogMapper.selectById(log.id)
        assertNotNull(found)
        assertEquals(log.id, found!!.id)
        assertEquals(task.id, found.taskId)
        assertEquals(task.name, found.taskName)
        assertEquals("Summarize today's news", found.prompt)
        assertEquals(1, found.status)
    }

    @Test
    @DisplayName("selectById - 查询不存在的日志返回 null")
    fun `selectById should return null when not exists`() {
        assertNull(agentTaskLogMapper.selectById(ABSENT_LOG_ID))
    }

    @Test
    @DisplayName("insert - 插入新日志记录")
    fun `insert should create new log`() {
        val task = insertTask(TASK_PREFIX + "log-insert")
        val newLog = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            prompt = "Test prompt"
            response = "Test response"
            sessionId = "sess-test"
            status = 1
            errorInfo = ""
            tokenUsage = """{"input":50}"""
            startTime = LocalDateTime.now()
            endTime = LocalDateTime.now().plusSeconds(10)
            durationMs = 10000
            creator = "tester"
            createTime = LocalDateTime.now()
        }

        val result = agentTaskLogMapper.insert(newLog)
        assertEquals(1, result)
        assertTrue(newLog.id > 0)
        seededLogIds += newLog.id

        val inserted = agentTaskLogMapper.selectById(newLog.id)
        assertNotNull(inserted)
        assertEquals("Test prompt", inserted!!.prompt)
    }

    @Test
    @DisplayName("markStopping - 只有运行中的日志能进入停止中")
    fun `markStopping should only claim a running log`() {
        val task = insertTask(TASK_PREFIX + "mark-stopping")
        val running = insertLog(task, status = 3)

        assertEquals(1, agentTaskLogMapper.markStopping(running.id, "Stopping..."))
        assertEquals(4, agentTaskLogMapper.selectById(running.id)?.status)

        // A second click on the same run: the status is no longer 3, and the caller handles it as idempotent.
        assertEquals(0, agentTaskLogMapper.markStopping(running.id, "Stopping..."))

        // A row that already reached a terminal status may not be pulled back into stopping. The old suite
        // used the seed row for that; this one writes its own.
        val finished = insertLog(task, status = 1)
        assertEquals(0, agentTaskLogMapper.markStopping(finished.id, "Stopping..."))
        assertEquals(1, agentTaskLogMapper.selectById(finished.id)?.status)
    }

    @Test
    @DisplayName("finishExecution - 只在运行中回写，被请求停止后写入 0 行")
    fun `finishExecution should not overwrite a row that was asked to stop`() {
        val task = insertTask(TASK_PREFIX + "finish")
        val running = insertLog(task, status = 3)
        running.status = 1
        running.response = "Done"
        running.endTime = LocalDateTime.now()
        running.durationMs = 1000L
        assertEquals(1, agentTaskLogMapper.finishExecution(running))
        assertEquals(1, agentTaskLogMapper.selectById(running.id)?.status)

        // The user pressed stop before the execution thread got to its own write-up: the result may not
        // overwrite the stop signal.
        val stopping = insertLog(task, status = 3)
        agentTaskLogMapper.markStopping(stopping.id, "Stopping...")
        stopping.status = 1
        stopping.response = "Late result"
        assertEquals(0, agentTaskLogMapper.finishExecution(stopping))
        assertEquals(4, agentTaskLogMapper.selectById(stopping.id)?.status)
    }

    @Test
    @DisplayName("finalizeStopped - 停止中收尾为已停止(5)")
    fun `finalizeStopped should close a stopping row as stopped`() {
        val task = insertTask(TASK_PREFIX + "finalize")
        val stopping = insertLog(task, status = 3)
        agentTaskLogMapper.markStopping(stopping.id, "Stopping...")

        stopping.status = 5
        stopping.endTime = LocalDateTime.now()
        stopping.durationMs = 2000L
        stopping.errorInfo = "Task stopped by user"
        assertEquals(1, agentTaskLogMapper.finalizeStopped(stopping))

        val updated = agentTaskLogMapper.selectById(stopping.id)
        assertNotNull(updated)
        assertEquals(5, updated!!.status)
        assertEquals(2000L, updated.durationMs)

        // A row still running(3) is out of reach of the `4 -> 5` statement.
        val running = insertLog(task, status = 3)
        assertEquals(0, agentTaskLogMapper.finalizeStopped(running))
    }

    @Test
    @DisplayName("reclaimExpired - 拥有真实结果的执行可以回收被误判超时的行")
    fun `reclaimExpired should only take back a row the reaper marked timeout`() {
        val task = insertTask(TASK_PREFIX + "reclaim")
        val expired = insertLog(task, status = 2)
        val running = insertLog(task, status = 3)
        val stopped = insertLog(task, status = 5)

        val reclaim = AgentTaskLog().apply {
            id = expired.id
            status = 1
            response = "real result"
            errorInfo = ""
            endTime = LocalDateTime.now()
            durationMs = 480_000L
        }
        assertEquals(1, agentTaskLogMapper.reclaimExpired(reclaim))
        assertEquals(1, agentTaskLogMapper.selectById(expired.id)?.status)
        assertEquals("real result", agentTaskLogMapper.selectById(expired.id)?.response)

        // Neither a live row nor a stopped one may be taken back.
        assertEquals(0, agentTaskLogMapper.reclaimExpired(running.apply { status = 1 }))
        assertEquals(3, agentTaskLogMapper.selectById(running.id)?.status)
        assertEquals(0, agentTaskLogMapper.reclaimExpired(stopped.apply { status = 1 }))
        assertEquals(5, agentTaskLogMapper.selectById(stopped.id)?.status)
    }

    /**
     * The grace window is 1.5x the task's own `timeout_seconds`, and the timeout below is this case's inserted
     * task — the seed task carried the same 300s.
     */
    @Test
    @DisplayName("expireStale - 宽限窗口内的行不回收，超出才回收")
    fun `expireStale should keep a grace window before reclaiming`() {
        val task = insertTask(TASK_PREFIX + "grace", timeoutSeconds = 300)
        val insideGrace = insertLog(task, status = 3, startedSecondsAgo = 400L)
        val outsideGrace = insertLog(task, status = 3, startedSecondsAgo = 700L)

        val expired = agentTaskLogMapper.expireStale(300)
        assertTrue(expired >= 1, "at least the row past the grace window must be reclaimed, got $expired")

        assertEquals(3, agentTaskLogMapper.selectById(insideGrace.id)?.status)
        assertEquals(2, agentTaskLogMapper.selectById(outsideGrace.id)?.status)
    }

    @Test
    @DisplayName("expireStale - 按各任务自己的 timeout_seconds 回收残留")
    fun `expireStale should expire only rows past their own task timeout`() {
        // One task with a 300s timeout (window 450s), one with 600s (window 900s): the seed pair had the same
        // two values, and the point is that the sweep judges each row against *its own* task.
        val shortTask = insertTask(TASK_PREFIX + "short-timeout", timeoutSeconds = 300)
        val longTask = insertTask(TASK_PREFIX + "long-timeout", timeoutSeconds = 600)
        val stale = insertLog(shortTask, status = 3, startedSecondsAgo = 700L)
        val staleStopping = insertLog(shortTask, status = 4, startedSecondsAgo = 700L)
        val fresh = insertLog(shortTask, status = 3)
        // A 700s-old row of the 600s task is still inside its own window and has to stay at 3.
        val withinLongerTimeout = insertLog(longTask, status = 3, startedSecondsAgo = 700L)

        val expired = agentTaskLogMapper.expireStale(300)
        assertTrue(expired >= 2, "at least two stale rows should be reclaimed, got $expired")

        assertEquals(2, agentTaskLogMapper.selectById(stale.id)?.status)
        assertEquals(2, agentTaskLogMapper.selectById(staleStopping.id)?.status)
        assertEquals(3, agentTaskLogMapper.selectById(fresh.id)?.status)
        assertEquals(3, agentTaskLogMapper.selectById(withinLongerTimeout.id)?.status)
    }

    // ==================== AgentTaskLogMapper: selectLogList filters ====================

    @Test
    @DisplayName("仅按 taskId 筛选")
    fun `selectLogList should filter by taskId`() {
        val task = insertTask(TASK_PREFIX + "list-task")
        insertListRuns(task)

        val logs = listByTask(task.id, ALICE)
        assertTrue(logs.isNotEmpty())
        assertEquals(listOf(task.id), logs.map { it.taskId }.distinct())
    }

    @Test
    @DisplayName("按 taskId + status 组合筛选")
    fun `selectLogList should filter by taskId and status`() {
        val task = insertTask(TASK_PREFIX + "list-status")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(task.id, null, 1, null, null, null, ALICE)
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertEquals(task.id, it.taskId)
            assertEquals(1, it.status)
        }
        // The fixture has non-1 rows, so the filter is what removed them rather than the absence of any.
        assertTrue(
            agentTaskLogMapper.selectLogList(task.id, null, null, null, null, null, ALICE).any { it.status != 1 },
        )
    }

    @Test
    @DisplayName("按 taskName 模糊搜索")
    fun `selectLogList should filter by taskName fuzzy`() {
        val task = insertTask(TASK_PREFIX + "Daily-name")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(null, "it8-Daily-name", null, null, null, null, ALICE)
        assertTrue(logs.isNotEmpty())
        logs.forEach { assertTrue(it.taskName.contains("it8-Daily-name"), "got: ${it.taskName}") }
        assertEquals(listOf(task.id), logs.map { it.taskId }.distinct())
    }

    @Test
    @DisplayName("按时间范围筛选 startTimeFrom")
    fun `selectLogList should filter by startTimeFrom`() {
        val task = insertTask(TASK_PREFIX + "list-from")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(task.id, null, null, "2026-07-02 00:00:00", null, null, ALICE)
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertNotNull(it.startTime)
            // Compare LocalDateTime, not toString() against a date-only string: "2026-07-01T09:00" sorts
            // after "2026-07-01" on a prefix, which is how this assertion used to lie.
            assertFalse(it.startTime!!.isBefore(LocalDateTime.of(2026, 7, 2, 0, 0)))
        }
    }

    @Test
    @DisplayName("按时间范围筛选 startTimeTo")
    fun `selectLogList should filter by startTimeTo`() {
        val task = insertTask(TASK_PREFIX + "list-to")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(task.id, null, null, null, "2026-07-01 23:59:59", null, ALICE)
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertNotNull(it.startTime)
            assertFalse(it.startTime!!.isAfter(LocalDateTime.of(2026, 7, 1, 23, 59, 59)))
        }
    }

    @Test
    @DisplayName("按完整时间范围筛选 startTimeFrom + startTimeTo")
    fun `selectLogList should filter by time range`() {
        val task = insertTask(TASK_PREFIX + "list-range")
        val runs = insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(
            task.id,
            null,
            null,
            "2026-07-01 00:00:00",
            "2026-07-02 23:59:59",
            null,
            ALICE,
        )
        // Exactly the 07-01 and 07-02 runs, which is the pair the old seed produced by table-wide count.
        assertEquals(2, logs.size)
        assertEquals(setOf(runs.firstRunId, runs.secondRunId), logs.map { it.id }.toSet())
    }

    @Test
    @DisplayName("按关键词搜索 prompt 字段")
    fun `selectLogList should search keyword in prompt`() {
        val task = insertTask(TASK_PREFIX + "list-keyword")
        val runs = insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(null, null, null, null, null, "$runTag-breaking-prompt", ALICE)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.prompt.contains("$runTag-breaking-prompt", ignoreCase = true) })
        // The token is this case's own, so the answer is exactly the row that carries it.
        assertEquals(listOf(runs.timedOutRunId), logs.map { it.id })
    }

    @Test
    @DisplayName("按关键词搜索 response 字段")
    fun `selectLogList should search keyword in response`() {
        val task = insertTask(TASK_PREFIX + "list-response")
        val runs = insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(null, null, null, null, null, "$runTag-report-response", ALICE)
        assertTrue(logs.isNotEmpty())
        assertEquals(runs.otherTaskRunId, logs[0].id)
    }

    @Test
    @DisplayName("按关键词搜索 error_info 字段")
    fun `selectLogList should search keyword in error_info`() {
        val task = insertTask(TASK_PREFIX + "list-error")
        val runs = insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(null, null, null, null, null, "$runTag-Connection-timeout", ALICE)
        assertTrue(logs.isNotEmpty())
        assertEquals(runs.failedRunId, logs[0].id)
    }

    @Test
    @DisplayName("组合筛选: taskId + 时间范围 + 关键词")
    fun `selectLogList should handle combined filters`() {
        val task = insertTask(TASK_PREFIX + "list-combined")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(
            task.id,
            null,
            null,
            "2026-06-01 00:00:00",
            "2026-07-31 23:59:59",
            "summary",
            ALICE,
        )
        assertTrue(logs.isNotEmpty())
        logs.forEach {
            assertEquals(task.id, it.taskId)
            // The keyword matched either of the two free-text columns, and only one of them may.
            val matchPrompt = it.prompt.contains("summary", ignoreCase = true)
            val matchResponse = it.response.contains("summary", ignoreCase = true)
            assertTrue(matchPrompt || matchResponse, "neither prompt nor response carries the keyword")
        }
    }

    @Test
    @DisplayName("无匹配结果返回空列表")
    fun `selectLogList should return empty when no match`() {
        val task = insertTask(TASK_PREFIX + "list-empty")
        insertListRuns(task)

        val logs = agentTaskLogMapper.selectLogList(
            task.id,
            null,
            null,
            "2030-01-01 00:00:00",
            "2030-12-31 23:59:59",
            null,
            ALICE,
        )
        assertTrue(logs.isEmpty())
    }

    @Test
    @DisplayName("关键词无匹配返回空列表")
    fun `selectLogList should return empty when keyword no match`() {
        assertTrue(
            agentTaskLogMapper
                .selectLogList(null, null, null, null, null, "zzzznonexistentkeyword$runTag", ALICE)
                .isEmpty(),
        )
    }

    @Test
    @DisplayName("结果按 create_time DESC 排序")
    fun `selectLogList should order by create_time desc`() {
        val task = insertTask(TASK_PREFIX + "list-order")
        val runs = insertListRuns(task)

        val logs = listByTask(task.id, ALICE)
        assertTrue(logs.size >= 2)
        for (i in 0 until logs.size - 1) {
            assertTrue(logs[i].createTime >= logs[i + 1].createTime, "create_time is not descending at index $i")
        }
        // Stronger than the loop the seed version could only manage: the fixture's own timestamps are known,
        // so the expected order is assertable row by row.
        assertEquals(runs.expectedDescOrder, logs.map { it.id })
    }

    /**
     * The log row repeats the task prompt and the agent response, so this is the case that used to be the
     * hole: knowing a taskId was enough to read it.
     */
    @Test
    @DisplayName("selectLogList - 非属主读不到他人私有任务的执行日志")
    fun `selectLogList should hide another user private task logs`() {
        val task = insertTask(TASK_PREFIX + "hidden", creator = ALICE, isPublic = 0)
        val log = insertLogOf(task)

        val asStranger = listByTask(task.id, BOB)
        assertTrue(asStranger.isEmpty(), "log ${log.id} must not be readable by a stranger")

        // The same rows are readable by the owner: visibility, and nothing else, is what blocks bob.
        val asOwner = listByTask(task.id, ALICE)
        assertEquals(listOf(log.id), asOwner.map { it.id })
    }

    @Test
    @DisplayName("selectLogList - 公开任务的日志对他人可读")
    fun `selectLogList should return another user public task logs`() {
        val task = insertTask(TASK_PREFIX + "listed", creator = ALICE, isPublic = 1)
        val log = insertLogOf(task)

        val asStranger = listByTask(task.id, BOB)
        assertEquals(listOf(log.id), asStranger.map { it.id })
    }

    /**
     * R2: `agent_task.tenant_id` is only the snapshot of the tenant that happened to be active when the task
     * was created, and the task reads carry no tenant condition at all (the automatic tenant interceptor is
     * disabled). A tenant filter on the log read was therefore *stricter* than the task list: the task stayed
     * visible while its own logs came back empty. The owner must read across tenants, exactly like the task
     * list lets them see the task.
     */
    @Test
    @DisplayName("selectLogList - 属主跨租户仍读到自己任务的日志（与任务列表同口径）")
    fun `selectLogList should still return the owner logs of a task created under another tenant`() {
        val task = insertTask(TASK_PREFIX + "tenant", creator = ALICE, tenantId = OTHER_TENANT)
        val log = insertLogOf(task)

        val logs = listByTask(task.id, ALICE)

        assertEquals(listOf(log.id), logs.map { it.id }, "the owner must not lose their own logs to the tenant the task was created under")
        // The prompt/response still only reaches the owner: dropping the tenant filter must not have dropped
        // the creator gate.
        assertTrue(
            listByTask(task.id, BOB).isEmpty(),
            "a stranger still cannot read it",
        )
    }

    // ==================== AgentTaskLogMapper: the stop gate ====================

    /**
     * The gate the stop path runs before it forwards anything: a stop is a *write* against someone else's
     * running execution, so only the task's creator may make it, and "not yours" has to answer exactly like
     * "does not exist".
     *
     * Narrower than `selectLogList` on purpose — that one also shows a caller the executions of other people's
     * public tasks. Seeing is not stopping; the public branch is what this gate drops, and the first two cases
     * below are the two halves of that asymmetry. The SQL text itself is pinned without a database by
     * [AgentTaskLogStopGateSqlTest]; this is the half that proves MySQL agrees with it.
     */
    @Test
    @DisplayName("selectOwnedById - 非属主拿不到他人私有任务的日志（与不存在无差别）")
    fun `selectOwnedById should hide another user private task log`() {
        val task = insertTask(TASK_PREFIX + "gate-private", creator = ALICE, isPublic = 0)
        val log = insertLogOf(task)

        assertNull(
            agentTaskLogMapper.selectOwnedById(log.id, BOB),
            "a log a stranger could take to the stop path must not be handed over",
        )
        // Same row, same call, only the caller differs: ownership is what blocks bob.
        assertEquals(log.id, agentTaskLogMapper.selectOwnedById(log.id, ALICE)?.id)
    }

    /**
     * The bug this case exists for: while the stop gate reused the read rule, `is_public = 1` made every
     * execution of somebody else's public task interruptable by anyone logged in. Public still means readable
     * — `selectLogList` keeps listing these rows to bob — it just no longer means stoppable.
     */
    @Test
    @DisplayName("selectOwnedById - 公开任务的日志对非属主不再放行：可读不等于可停")
    fun `selectOwnedById should refuse a public task log to a non owner`() {
        val task = insertTask(TASK_PREFIX + "gate-public", creator = ALICE, isPublic = 1)
        val log = insertLogOf(task)

        assertNull(agentTaskLogMapper.selectOwnedById(log.id, BOB), "public grants a read, and stopping is a write")
        // The owner is unaffected by the public flag.
        assertEquals(log.id, agentTaskLogMapper.selectOwnedById(log.id, ALICE)?.id)
        // And the read side really is still open, which is what makes the pair an asymmetry rather than a
        // plain restriction.
        assertTrue(
            listByTask(task.id, BOB).any { it.id == log.id },
            "the read side must still show this run of a public task to bob",
        )
    }

    /**
     * This gate guards a write, so a tenant condition here would have made an owner's own stop a silent "not
     * yours": create the task under another tenant, come back as its creator and the row is gone. The creator
     * must still get it.
     */
    @Test
    @DisplayName("selectOwnedById - 属主跨租户仍拿得到，因而仍停得掉自己的执行")
    fun `selectOwnedById should still return the owner log of a task created under another tenant`() {
        val task = insertTask(TASK_PREFIX + "gate-tenant", creator = ALICE, tenantId = OTHER_TENANT)
        val log = insertLogOf(task)

        assertEquals(
            log.id,
            agentTaskLogMapper.selectOwnedById(log.id, ALICE)?.id,
            "an owner across tenants must still be able to stop their own execution",
        )
        // The creator gate is untouched: bob still cannot reach it.
        assertNull(agentTaskLogMapper.selectOwnedById(log.id, BOB))
    }

    /**
     * A task that is gone (`active = 0`) leaves no execution to stop: its logs must not stay reachable through
     * their own ids even for its creator.
     */
    @Test
    @DisplayName("selectOwnedById - 已软删任务的日志拿不到")
    fun `selectOwnedById should drop logs of a deleted task`() {
        val task = insertTask(TASK_PREFIX + "gate-deleted", creator = ALICE)
        val log = insertLogOf(task)
        assertEquals(1, agentTaskMapper.deleteById(task.id, ALICE))

        assertNull(agentTaskLogMapper.selectOwnedById(log.id, ALICE))
    }

    // ==================== AgentTaskLogMapper: retention ====================

    /**
     * Retention for the log table. Housekeeping is the only writer that ever removes a row here, and before it
     * there was none: `agent_task_log` carried prompt/response for every run forever.
     */
    @Test
    @DisplayName("deleteOldLogs - 只删保留期外的终态行")
    fun `deleteOldLogs should drop only terminal rows past the cutoff`() {
        val task = insertTask(TASK_PREFIX + "retention")
        val cutoff = LocalDateTime.now().minusDays(90)
        val purged = insertLogWithAge(task, status = 1, ageDays = 100)
        val recent = insertLogWithAge(task, status = 1, ageDays = 10)
        // A row that still looks live may never be deleted for being old: that is precisely the row both the
        // stop path and the reaper still have to judge.
        val ancientRunning = insertLogWithAge(task, status = 3, ageDays = 100)
        val ancientStopping = insertLogWithAge(task, status = 4, ageDays = 100)

        // The old suite could assert `1` because its container held nothing but the seed. On a shared one the
        // honest version of "exactly the rows it was allowed to delete" is the eligible population counted
        // with the sweep's own predicate, immediately before the sweep — and that population asserted to be
        // this case's single aged-out row, which is what the original `assertEquals(1, deleted)` really meant.
        val eligibleBefore = requireNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_log WHERE create_time < ? AND status NOT IN (3, 4)",
                Int::class.java,
                java.sql.Timestamp.valueOf(cutoff),
            ),
        ) { "no eligible-row count for the retention cutoff" }
        assertEquals(1, eligibleBefore, "this case aged out exactly one terminal row and nothing else should be one")

        val deleted = agentTaskLogMapper.deleteOldLogs(cutoff)
        assertEquals(eligibleBefore, deleted, "the sweep deleted a different number of rows than its own predicate matches")

        assertNull(agentTaskLogMapper.selectById(purged.id))
        assertNotNull(agentTaskLogMapper.selectById(recent.id))
        assertNotNull(agentTaskLogMapper.selectById(ancientRunning.id))
        assertNotNull(agentTaskLogMapper.selectById(ancientStopping.id))
    }

    // ==================== AgentTaskExecutionMapper: the lock table ====================

    /**
     * A guard row left at status 0 is not just stale data: it is a lock nobody holds.
     *
     * `agent_task_execution` has no foreign key, but the rows still hang off this case's own task so
     * [removeSeededRows] can find them again by task id.
     */
    @Test
    @DisplayName("deleteStaleRunning - 只删除超时仍未定态的抢锁行")
    fun `deleteStaleRunning should only remove locked rows past the deadline`() {
        val leakedTask = insertTask(TASK_PREFIX + "lock-leaked")
        val heldTask = insertTask(TASK_PREFIX + "lock-held")
        val doneTask = insertTask(TASK_PREFIX + "lock-done")
        val deadline = LocalDateTime.now().minusSeconds(600)

        val leaked = insertExecution(leakedTask, status = 0, ageSeconds = 900L)
        val held = insertExecution(heldTask, status = 0, ageSeconds = 30L)
        val done = insertExecution(doneTask, status = 1, ageSeconds = 900L)

        // Same reasoning as the retention case above: the sweep returns the number of rows it deleted, and on
        // a shared container the only exact reading of that number is the eligible population it started from.
        val eligibleBefore = requireNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_execution WHERE status = 0 AND create_time < ?",
                Int::class.java,
                java.sql.Timestamp.valueOf(deadline),
            ),
        ) { "no eligible-lock count for the leaked-lock deadline" }
        assertEquals(1, eligibleBefore, "this case wrote exactly one leaked lock and nothing else should be one")

        val deleted = executionMapper.deleteStaleRunning(deadline)
        assertEquals(eligibleBefore, deleted, "the sweep deleted a different number of rows than its own predicate matches")

        assertNull(executionMapper.selectByTaskIdAndTriggerTime(leaked.taskId, leaked.triggerTime))
        // A held lock has to survive — deleting it is what would let the same trigger fire on two nodes.
        assertEquals(held.id, executionMapper.selectByTaskIdAndTriggerTime(held.taskId, held.triggerTime)?.id)
        // An old row that did reach a terminal status is not this sweep's either: that is the retention job's.
        assertEquals(done.id, executionMapper.selectByTaskIdAndTriggerTime(done.taskId, done.triggerTime)?.id)
    }

    // ==================== Fixtures ====================

    /** The four runs the list-filter cases were written against, with the timestamps the seed rows carried. */
    private class ListRuns(
        val firstRunId: Long,
        val secondRunId: Long,
        val failedRunId: Long,
        val timedOutRunId: Long,
        val otherTaskRunId: Long,
    ) {
        /** create_time DESC: 07-03, 07-02, 07-01, 06-30. */
        val expectedDescOrder: List<Long> = listOf(failedRunId, secondRunId, firstRunId, timedOutRunId)
    }

    private fun insertListRuns(task: AgentTask): ListRuns {
        // Its own unrelated name on purpose: a `task_name` LIKE search has to be able to tell the two tasks
        // apart, and a name built as "<primary>-sibling" would answer a search for the primary with both.
        val otherTask = insertTask("it8-sibling-$runTag", creator = ALICE)
        val first = insertLog(
            task,
            status = 1,
            prompt = "Summarize today's news",
            response = "Here is the summary...",
            startedAt = LocalDateTime.of(2026, 7, 1, 9, 0),
            createAt = LocalDateTime.of(2026, 7, 1, 9, 0, 30),
        )
        val second = insertLog(
            task,
            status = 1,
            prompt = "Summarize today's news",
            response = "Another summary",
            startedAt = LocalDateTime.of(2026, 7, 2, 9, 0),
            createAt = LocalDateTime.of(2026, 7, 2, 9, 0, 25),
        )
        val failed = insertLog(
            task,
            status = 0,
            prompt = "Summarize today's news",
            response = "",
            errorInfo = "$runTag-Connection-timeout",
            startedAt = LocalDateTime.of(2026, 7, 3, 9, 0),
            createAt = LocalDateTime.of(2026, 7, 3, 9, 5),
        )
        val timedOut = insertLog(
            task,
            status = 2,
            prompt = "Summarize $runTag-breaking-prompt news",
            response = "Breaking news response",
            errorInfo = "Execution timed out",
            startedAt = LocalDateTime.of(2026, 6, 30, 9, 0),
            createAt = LocalDateTime.of(2026, 6, 30, 9, 10),
        )
        // The seed's fourth row belonged to the *other* task; the keyword-in-response case is written against
        // it, so it has to exist and it has to be distinguishable from the rows above.
        val siblingRun = insertLog(
            otherTask,
            status = 1,
            prompt = "Generate weekly report",
            response = "$runTag-report-response content",
            startedAt = LocalDateTime.of(2026, 7, 1, 9, 0),
            createAt = LocalDateTime.of(2026, 7, 1, 9, 1),
        )
        return ListRuns(first.id, second.id, failed.id, timedOut.id, siblingRun.id)
    }

    private fun listByTask(
        taskId: Long,
        username: String,
    ): List<AgentTaskLog> = agentTaskLogMapper.selectLogList(taskId, null, null, null, null, null, username)

    private fun insertTask(
        name: String,
        creator: String = ALICE,
        isPublic: Int = 0,
        tenantId: Long = TENANT,
        timeoutSeconds: Int = 300,
    ): AgentTask {
        val task = AgentTask().apply {
            this.name = name
            this.tenantId = tenantId
            agentId = 100
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            // Paused on purpose, as in the other ITs of this package: an active task would be picked up by a
            // reconcile round this class never asked for and would show up in their counts.
            taskStatus = 0
            concurrent = 0
            this.timeoutSeconds = timeoutSeconds
            description = "seed"
            this.isPublic = isPublic
            this.creator = creator
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        assertEquals(1, agentTaskMapper.insert(task), "the fixture task $name has to exist for a rule to have anything to judge")
        assertTrue(task.id > 0)
        seededTaskIds += task.id
        return task
    }

    private fun insertLog(
        task: AgentTask,
        status: Int = 1,
        prompt: String = "Running prompt",
        response: String = "",
        errorInfo: String = "",
        sessionId: String = c1SessionId(task, status),
        startedAt: LocalDateTime? = null,
        startedSecondsAgo: Long? = null,
        createAt: LocalDateTime = LocalDateTime.now(),
    ): AgentTaskLog {
        val log = AgentTaskLog().apply {
            taskId = task.id
            taskName = task.name
            this.prompt = prompt
            this.response = response
            this.sessionId = sessionId
            this.status = status
            this.errorInfo = errorInfo
            this.startTime = startedAt ?: startedSecondsAgo?.let { LocalDateTime.now().minusSeconds(it) } ?: LocalDateTime.now()
            creator = task.creator
            createTime = createAt
        }
        assertEquals(1, agentTaskLogMapper.insert(log), "a fixture log row has to exist for a statement to judge")
        assertTrue(log.id > 0)
        seededLogIds += log.id
        return log
    }

    /** A log of [task] written the way the visibility cases need it: prompt and response carry content. */
    private fun insertLogOf(task: AgentTask): AgentTaskLog = insertLog(
        task,
        status = 1,
        prompt = "Private prompt of the task owner",
        response = "Private response",
        startedAt = LocalDateTime.now(),
    )

    private fun insertLogWithAge(
        task: AgentTask,
        status: Int,
        ageDays: Long,
    ): AgentTaskLog {
        val aged = LocalDateTime.now().minusDays(ageDays)
        return insertLog(
            task,
            status = status,
            prompt = "retention probe $runTag",
            response = "ok",
            startedAt = aged,
            createAt = aged,
        )
    }

    private fun insertExecution(
        task: AgentTask,
        status: Int,
        ageSeconds: Long,
    ): AgentTaskExecution {
        val execution = AgentTaskExecution().apply {
            taskId = task.id
            triggerTime = LocalDateTime.now().withNano(0)
            instanceId = "it-node"
            this.status = status
            createTime = LocalDateTime.now().minusSeconds(ageSeconds)
        }
        assertEquals(1, executionMapper.insert(execution), "a lock row is the thing the sweep has to find")
        // `trigger_time` is a second-precision DATETIME and the read back below matches on it exactly, which is
        // why it was written with `withNano(0)`. How old the row is — and so which side of the deadline the
        // case puts it on — is the caller's `ageSeconds`, and nothing here re-judges that.
        return execution
    }

    /** The C1 shape: `task-{taskId}-{agentId}-{uuid}`. */
    private fun c1SessionId(
        task: AgentTask,
        status: Int,
    ): String = "task-${task.id}-${task.agentId}-$runTag-$status"

    private fun columnWidth(
        table: String,
        column: String,
    ): Int = requireNotNull(
        jdbc.queryForObject(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS" +
                " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            Int::class.java,
            table,
            column,
        ),
    ) { "$table.$column has no character width, so the column is not there at all" }

    companion object {
        private const val ALICE = "alice"
        private const val BOB = "bob"
        private const val TENANT = 1L
        private const val OTHER_TENANT = 2L

        /** Every task name this class writes starts with it, so `uk_name` stays uncontested. */
        private const val TASK_PREFIX = "it8-"

        /** No IT of this module gets anywhere near this id, so the missing-row case has a stable answer. */
        private const val ABSENT_LOG_ID = 9_999_999L
    }
}
