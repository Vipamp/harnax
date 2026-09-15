package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.service.AgentTaskCrudService
import com.agnetix.harnax.scheduler.service.AgentTaskLogQueryService
import com.agnetix.harnax.scheduler.support.CallerContext
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * IT-3: the owner and visibility rules of this domain, against a real MySQL.
 *
 * Every rule here is a `WHERE` clause — `is_public = 1 OR creator = ?` on the reads, `creator = ?` on the
 * writes — and a unit test can only prove that the service *passed* a username to the mapper. This file is
 * the one that proves the SQL does something with it: a mock cannot tell a correct join from a clause that
 * never matches.
 *
 * The assertions therefore land on the **rows**, not on response codes. A gate that returns a polite refusal
 * while the UPDATE it refused to authorise still went through is the failure this domain has been bitten by
 * twice, and reading the answer would never show it. Each case therefore reads the table back through
 * [jdbc] — a second connection, seeing what another instance would see.
 *
 * The identity is what contract C4 hands this service (`X-Forwarded-User` in, [CallerContext] out), so the
 * cases set it directly instead of going through an HTTP layer; the interceptor that fills it has its own
 * suite.
 *
 * Everything seeded here stays paused (`task_status = 0`): an active task would be picked up by the reconcile
 * round the write path runs after its commit, and a schedule this test never asked for would be left behind
 * for the other IT classes of this JVM.
 */
class AgentTaskOwnerScopeIT : BaseSchedulerIT() {

    @Autowired
    private lateinit var crudService: AgentTaskCrudService

    @Autowired
    private lateinit var logQueryService: AgentTaskLogQueryService

    @Autowired
    private lateinit var agentTaskMapper: AgentTaskMapper

    @BeforeEach
    fun seed() {
        // Alice owns a private task and a public one, plus a live execution on the private task; bob owns a
        // task of his own. Two owners because "not yours" has to be shown both ways: invisible, and visible
        // but untouchable.
        insertTask(ALICE_PRIVATE, "it3-alice-private", "alice", isPublic = 0)
        insertTask(ALICE_PUBLIC, "it3-alice-public", "alice", isPublic = 1)
        insertTask(BOWNS, "it3-bob-own", "bob", isPublic = 0)
        insertLog(RUN_OF_PRIVATE, ALICE_PRIVATE)
    }

    @AfterEach
    fun removeSeeded() {
        CallerContext.clear()
        jdbc.update("DELETE FROM agent_task_log WHERE id = ?", RUN_OF_PRIVATE)
        listOf(ALICE_PRIVATE, ALICE_PUBLIC, BOWNS).forEach { id ->
            jdbc.update("DELETE FROM agent_task WHERE id = ?", id)
        }
    }

    private fun asUser(username: String) {
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = username, tenantId = 1L))
    }

    private fun insertTask(
        id: Long,
        name: String,
        creator: String,
        isPublic: Int,
    ) {
        assertEquals(
            1,
            jdbc.update(
                "INSERT INTO agent_task (id, tenant_id, name, agent_id, agent_name, prompt, cron_expression," +
                    " task_status, concurrent, timeout_seconds, description, is_public, creator, active, create_time, update_time)" +
                    " VALUES (?, 1, ?, 700, 'IT Agent', 'original prompt', '0 0 9 * * ?', 0, 0, 300, '', ?, ?, 1, NOW(), NOW())",
                id,
                name,
                isPublic,
                creator,
            ),
            "the fixture row $name has to exist for a gate to have anything to guard",
        )
    }

    private fun insertLog(id: Long, taskId: Long) {
        assertEquals(
            1,
            jdbc.update(
                "INSERT INTO agent_task_log (id, task_id, task_name, prompt, response, session_id, status, error_info," +
                    " token_usage, start_time, duration_ms, creator, create_time)" +
                    " VALUES (?, ?, 'it3-alice-private', 'p', '', ?, 3, '', '', NOW(), 0, 'alice', NOW())",
                id,
                taskId,
                "task-$taskId-700-it01",
            ),
            "a live execution row is the thing the stop gate has to find",
        )
    }

    private fun promptOf(id: Long): String? = jdbc.queryForObject("SELECT prompt FROM agent_task WHERE id = ?", String::class.java, id)

    private fun activeOf(id: Long): Int? = jdbc.queryForObject("SELECT active FROM agent_task WHERE id = ?", Integer::class.java, id)?.toInt()

    private fun statusOf(id: Long): Int? = jdbc.queryForObject("SELECT status FROM agent_task_log WHERE id = ?", Integer::class.java, id)?.toInt()

    @Test
    @DisplayName("非属主读不到他人的私有任务，数据也未被动过")
    fun `a private task is invisible to a non-owner and untouched by their writes`() {
        asUser("bob")

        assertNull(crudService.getAgentTask(ALICE_PRIVATE), "a private row of somebody else must not read back")
        assertTrue(
            crudService.page("it3-", null, null, 1, 50).records.none { it.id == ALICE_PRIVATE },
            "and it must not slip into the list either",
        )
        assertTrue(
            crudService.page("it3-", null, null, 1, 50).records.any { it.id == BOWNS },
            "the same caller still sees his own row, so the empty answer above is the rule and not a broken read",
        )

        val updateError = assertThrows(SchedulerBizException::class.java) {
            crudService.updateAgentTask(ALICE_PRIVATE, AgentTaskUpdateRequest(prompt = "hijacked"))
        }
        assertEquals("Agent task not found", updateError.message)
        val deleteError = assertThrows(SchedulerBizException::class.java) { crudService.deleteAgentTask(ALICE_PRIVATE) }
        assertEquals("Agent task not found", deleteError.message)

        assertEquals("original prompt", promptOf(ALICE_PRIVATE), "a refused update must have written nothing")
        assertEquals(1, activeOf(ALICE_PRIVATE), "a refused delete must not have soft-deleted the row")
    }

    /**
     * The asymmetry the whole rule rests on: public means *readable*, never writable. `selectById` hands the
     * row over and the creator condition inside the UPDATE is what stops the rewrite — which is why this case
     * asserts on the stored prompt and not on an exception alone.
     */
    @Test
    @DisplayName("公开任务可读不可写")
    fun `a public task is readable by a non-owner but writable only by its creator`() {
        asUser("bob")

        val visible = crudService.getAgentTask(ALICE_PUBLIC)
        assertNotNull(visible, "is_public = 1 is all the read rule asks for, and the row satisfies it")
        assertEquals(ALICE_PUBLIC, visible?.id)

        val error = assertThrows(SchedulerBizException::class.java) {
            crudService.updateAgentTask(ALICE_PUBLIC, AgentTaskUpdateRequest(prompt = "hijacked"))
        }
        assertTrue(error.message!!.contains("creator"), "got: ${error.message}")
        assertEquals("original prompt", promptOf(ALICE_PUBLIC), "the public row is byte for byte what it was")

        val deleteError = assertThrows(SchedulerBizException::class.java) { crudService.deleteAgentTask(ALICE_PUBLIC) }
        assertTrue(deleteError.message!!.contains("creator"), "got: ${deleteError.message}")
        assertEquals(1, activeOf(ALICE_PUBLIC))
    }

    @Test
    @DisplayName("属主仍然读得到、改得动、删得掉自己的任务")
    fun `the owner reaches all three, so the two cases above are the gate and not a broken mapper`() {
        asUser("alice")

        assertNotNull(crudService.getAgentTask(ALICE_PRIVATE))
        assertTrue(crudService.updateAgentTask(ALICE_PRIVATE, AgentTaskUpdateRequest(prompt = "edited by alice")))
        assertEquals("edited by alice", promptOf(ALICE_PRIVATE))

        assertTrue(crudService.deleteAgentTask(ALICE_PRIVATE))
        assertEquals(0, activeOf(ALICE_PRIVATE), "the delete is a soft one: the row stays, marked inactive")
    }

    /**
     * The log list is gated through the owning task, so a non-owner's answer is an empty page while the row
     * is physically there, and the owner's answer has it. Both halves are what makes this a test of the join
     * rather than of the fixture.
     */
    @Test
    @DisplayName("执行日志按属主任务可见性返回")
    fun `execution logs follow the visibility of the task that owns them`() {
        assertEquals(3, statusOf(RUN_OF_PRIVATE), "the log row exists, so an empty page has to mean the gate")

        asUser("bob")
        val foreignPage = logQueryService.page(ALICE_PRIVATE, null, null, null, null, null, 1, 10)
        assertEquals(0L, foreignPage.total, "another user's private task has no readable execution history")
        assertTrue(foreignPage.records.isEmpty())

        asUser("alice")
        val ownPage = logQueryService.page(ALICE_PRIVATE, null, null, null, null, null, 1, 10)
        assertEquals(1L, ownPage.total, "the owner sees her own run")
        assertEquals(RUN_OF_PRIVATE, ownPage.records.first().id)
    }

    /**
     * [AgentTaskCrudService.requireOwnedLog] is the write authorisation of the stop path, deliberately
     * narrower than the read above: bob may watch the runs of a public task, which does not make alice's live
     * execution his to interrupt. The row has to still be at 3 afterwards — the damage this gate prevents is
     * an interruption that already happened by the time anybody noticed who asked for it.
     */
    @Test
    @DisplayName("停止门禁挡住他人的执行且不改写状态")
    fun `the stop gate blocks another user and leaves the running row running`() {
        asUser("bob")

        val error = assertThrows(SchedulerBizException::class.java) { crudService.requireOwnedLog(RUN_OF_PRIVATE) }
        assertEquals("Agent task log not found", error.message, "the answer must not leak that the row exists")
        assertEquals(3, statusOf(RUN_OF_PRIVATE), "a refused stop must not have moved the execution out of running")

        asUser("alice")
        assertEquals(RUN_OF_PRIVATE, crudService.requireOwnedLog(RUN_OF_PRIVATE).id)
        assertEquals(3, statusOf(RUN_OF_PRIVATE), "and the authorised read changes nothing by itself either")
    }

    /**
     * The mapper-level half of the same promise, on a real database: a row read through the *visibility* rule
     * cannot be written back by a non-owner, because `updateById` carries the creator in its WHERE clause and
     * matches zero rows. That is the last line of defence behind the service check, and it is SQL, so it is
     * only observable here.
     */
    @Test
    @DisplayName("写语句自身的属主条件是最后一道")
    fun `the write statement carries its own creator condition`() {
        asUser("bob")
        val row: AgentTask = crudService.getAgentTask(ALICE_PUBLIC)!!
        row.prompt = "crossed"

        assertEquals(
            0,
            agentTaskMapper.updateById(row, "bob"),
            "the UPDATE has to match no row for a caller who is not the creator",
        )
        assertEquals("original prompt", promptOf(ALICE_PUBLIC))

        assertEquals(1, agentTaskMapper.updateById(row, "alice"), "and it does match for the owner")
        assertEquals("crossed", promptOf(ALICE_PUBLIC))
    }

    companion object {
        private const val ALICE_PRIVATE = 9001L
        private const val ALICE_PUBLIC = 9002L
        private const val BOWNS = 9003L
        private const val RUN_OF_PRIVATE = 9101L
    }
}
