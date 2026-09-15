package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.dto.AgentTaskCreateRequest
import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.service.ReconcileReport
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import com.agnetix.harnax.scheduler.support.CallerContext
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import com.github.pagehelper.PageHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * The rules this service inherited from `harnax-admin`'s `AgentTaskServiceImpl`, pinned on this side of the
 * move. Release 2 promised the clients of the admin task endpoints that nothing they see would change, so
 * the assertions below are deliberately about the exact values the old service produced: the defaults it
 * wrote, the words it refused with, the row it scoped a write to, and the one property that a schedule
 * notification is only ever sent for a write that actually reached the database.
 *
 * The caller comes from [CallerContext] instead of a request JWT, so each case states who is asking by
 * setting that context — the same two lines are what the C4 interceptor does for a forwarded request.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskCrudServiceImplTest {

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var reconciler: TaskScheduleReconciler

    private lateinit var testTask: AgentTask

    private lateinit var service: AgentTaskCrudServiceImpl

    @BeforeEach
    fun setUp() {
        service = AgentTaskCrudServiceImpl(agentTaskMapper, agentTaskLogMapper, reconciler)
        testTask = AgentTask().apply {
            id = 1L
            tenantId = 1L
            name = "Daily News"
            agentId = 100L
            agentName = "News Agent"
            prompt = "Summarize today's news"
            cronExpression = "0 0 9 * * ?"
            taskStatus = 0
            concurrent = 0
            timeoutSeconds = 300
            description = "Daily news summary task"
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        asUser("admin")
        // The write paths act on the reconcile result, so a bare mock would fail every case.
        whenever(reconciler.reconcile()).thenReturn(ReconcileReport(0, 0, 0, 1, emptyList()))
    }

    @AfterEach
    fun tearDown() {
        CallerContext.clear()
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        // startPage() with a mocked mapper never runs a query, so the thread-local would otherwise be read
        // back by whichever test runs next on this thread.
        PageHelper.clearPage()
    }

    /** Stands in for what [com.agnetix.harnax.scheduler.support.InternalCallerInterceptor] does on a call. */
    private fun asUser(username: String?, tenantId: Long? = null) {
        CallerContext.set(CallerContext.Caller(callerId = "harnax-admin", username = username, tenantId = tenantId))
    }

    // ==================== Read ====================

    @Test
    @DisplayName("getById 以转发来的属主身份读取")
    fun `getAgentTask reads through the caller identity`() {
        whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)

        val result = service.getAgentTask(1L)

        assertNotNull(result)
        assertEquals("Daily News", result?.name)
        verify(agentTaskMapper).selectById(1L, "admin")
    }

    @Test
    @DisplayName("getById 查不到即返回 null，不做任何补读")
    fun `getAgentTask returns null for a row the visibility rule does not hand back`() {
        whenever(agentTaskMapper.selectById(9L, "admin")).thenReturn(null)

        assertNull(service.getAgentTask(9L))
    }

    @Test
    @DisplayName("列表分页沿用 admin 的两端钳制：pageNum>=1、pageSize 1..1000")
    fun `page clamps the requested window the way the moved list did`() {
        whenever(agentTaskMapper.selectTaskList(any(), any(), any(), any())).thenReturn(listOf(testTask))

        service.page(null, null, null, -5, 99_999)

        val page = PageHelper.getLocalPage<AgentTask>()
        assertNotNull(page, "the page has to be requested before the query runs, or the list returns unpaged")
        assertEquals(1, page!!.pageNum, "a negative page number used to reach the driver as itself")
        assertEquals(1000, page.pageSize, "pageSize is capped at 1000, the same ceiling admin's list carried")
    }

    @Test
    @DisplayName("列表按属主可见性取数，且完全不看租户")
    fun `page scopes the list to the caller and never to the tenant`() {
        whenever(agentTaskMapper.selectTaskList(any(), any(), any(), any())).thenReturn(emptyList())
        asUser("alice", tenantId = 7L)

        val page = service.page("Daily", 100L, 1, 2, 20)

        verify(agentTaskMapper).selectTaskList("Daily", 100L, 1, "alice")
        // The envelope's own numbers are reported by the driver, which a mocked mapper never reaches, so the
        // window this service asked for is read back off PageHelper — that is the half it controls.
        val requested = PageHelper.getLocalPage<AgentTask>()
        assertEquals(2, requested!!.pageNum)
        assertEquals(20, requested.pageSize)
        assertTrue(page.records.isEmpty())
        // Nothing in the call carries a tenant: the tenant interceptor of this repository is a no-op and the
        // task list never filtered by tenant, so adding one here would have tightened a rule while moving it.
        assertTrue(
            AgentTaskMapper::class.java.methods.first { it.name == "selectTaskList" }.parameterCount == 4,
            "selectTaskList must keep its four arguments — name/agentId/taskStatus/currentUsername",
        )
    }

    @Test
    @DisplayName("没有属主的调用直接拒绝，而不是退化成只看公开任务")
    fun `an identity-less call is refused instead of quietly reading the public subset`() {
        asUser(null)

        val error = assertThrows(RuntimeException::class.java) { service.page(null, null, null, 1, 10) }

        assertEquals("Not logged in", error.message, "the message admin's UserContextUtil used")
        verify(agentTaskMapper, never()).selectTaskList(any(), any(), any(), any())
    }

    // ==================== Create ====================

    @Nested
    @DisplayName("Create")
    inner class CreateTests {

        private fun request(name: String = "New Task", cron: String = "0 0 9 * * ?", agentName: String? = "News Agent") = AgentTaskCreateRequest(
            name = name,
            agentId = 100L,
            agentName = agentName,
            prompt = "Do something",
            cronExpression = cron,
        )

        @Test
        fun `create writes the defaults the moved service wrote`() {
            whenever(agentTaskMapper.selectByName("New Task")).thenReturn(null)
            whenever(agentTaskMapper.insert(any())).thenReturn(1)

            assertTrue(service.createAgentTask(request()))

            val inserted = argumentCaptor<AgentTask>().apply { verify(agentTaskMapper).insert(capture()) }
            val task = inserted.firstValue
            assertEquals(0, task.taskStatus, "a new task is paused until somebody starts it")
            assertEquals(1, task.active)
            assertEquals("admin", task.creator)
            assertEquals(1L, task.tenantId, "no tenant forwarded means tenant 1, as before")
            assertEquals("News Agent", task.agentName)
            assertEquals(100L, task.agentId)
            assertEquals("Do something", task.prompt)
            assertEquals(300, task.timeoutSeconds)
            assertNotNull(task.createTime)
            assertNotNull(task.updateTime)
        }

        @Test
        fun `create stores the tenant the caller forwarded`() {
            whenever(agentTaskMapper.selectByName(any())).thenReturn(null)
            whenever(agentTaskMapper.insert(any())).thenReturn(1)
            asUser("admin", tenantId = 9L)

            service.createAgentTask(request())

            val inserted = argumentCaptor<AgentTask>().apply { verify(agentTaskMapper).insert(capture()) }
            assertEquals(9L, inserted.firstValue.tenantId, "X-Tenant-Id is the snapshot of the creation-time tenant")
        }

        @Test
        fun `create refuses a name that is taken, in the same words`() {
            whenever(agentTaskMapper.selectByName("Daily News")).thenReturn(testTask)

            val error = assertThrows(SchedulerBizException::class.java) { service.createAgentTask(request(name = "Daily News")) }

            assertEquals("Task name already exists", error.message)
            assertEquals(400, error.code)
            verify(agentTaskMapper, never()).insert(any())
        }

        @Test
        fun `create refuses a cron outside Quartz's field count`() {
            whenever(agentTaskMapper.selectByName(any())).thenReturn(null)

            val error = assertThrows(SchedulerBizException::class.java) {
                service.createAgentTask(request(cron = "0 9 * * 1"))
            }

            assertEquals("Invalid cron expression", error.message)
            verify(agentTaskMapper, never()).insert(any())
        }

        @Test
        fun `create accepts the seven-field form with a year`() {
            whenever(agentTaskMapper.selectByName(any())).thenReturn(null)
            whenever(agentTaskMapper.insert(any())).thenReturn(1)

            assertTrue(service.createAgentTask(request(cron = "0 0 9 * * ? 2027")))
        }

        /**
         * `agent_task.agent_name` is a snapshot of a row this service cannot read (contract C4's domain split),
         * so the caller resolves it and hands it over. An empty one means the caller had no agent for the id,
         * which is the answer the in-process lookup used to give — and storing "" would be a task row whose
         * agent column can never be recovered.
         */
        @Test
        fun `create refuses an unresolved agent instead of storing an empty name`() {
            whenever(agentTaskMapper.selectByName(any())).thenReturn(null)

            for (blank in listOf(null, "", "   ")) {
                val error = assertThrows(SchedulerBizException::class.java) {
                    service.createAgentTask(request(agentName = blank))
                }
                assertEquals("Agent not found", error.message, "agentName=$blank must not become an empty snapshot")
            }
            verify(agentTaskMapper, never()).insert(any())
        }

        @Test
        fun `create fails loudly when the insert matched no row`() {
            whenever(agentTaskMapper.selectByName(any())).thenReturn(null)
            whenever(agentTaskMapper.insert(any())).thenReturn(0)

            assertFalse(service.createAgentTask(request()))
        }
    }

    // ==================== Update ====================

    @Nested
    @DisplayName("Update")
    inner class UpdateTests {

        private fun givenFound(task: AgentTask = testTask) {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(task)
            whenever(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)
        }

        @Test
        fun `update refuses a task the caller may not see`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(null)

            val error = assertThrows(SchedulerBizException::class.java) {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "x"))
            }

            assertEquals("Agent task not found", error.message)
            verify(agentTaskMapper, never()).updateById(any(), any())
        }

        @Test
        fun `update patches only the fields the request names`() {
            givenFound()

            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt")))

            val saved = argumentCaptor<AgentTask>().apply { verify(agentTaskMapper).updateById(capture(), eq("admin")) }
            val task = saved.firstValue
            assertEquals("New prompt", task.prompt)
            assertEquals("Daily News", task.name, "an unnamed field is not overwritten")
            assertEquals(300, task.timeoutSeconds)
            assertEquals("Daily news summary task", task.description)
            assertEquals(100L, task.agentId)
            assertEquals("News Agent", task.agentName)
        }

        @Test
        fun `update resets a running task to paused`() {
            givenFound(testTask.apply { taskStatus = 1 })

            service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt"))

            val saved = argumentCaptor<AgentTask>().apply { verify(agentTaskMapper).updateById(capture(), eq("admin")) }
            assertEquals(0, saved.firstValue.taskStatus, "an edit to a running task parks it, as it always did")
        }

        @Test
        fun `update checks uniqueness only when the name actually moves`() {
            givenFound()

            service.updateAgentTask(1L, AgentTaskUpdateRequest(name = "Daily News"))
            verify(agentTaskMapper, never()).selectByName(any())

            whenever(agentTaskMapper.selectByName("Renamed")).thenReturn(testTask)
            val error = assertThrows(SchedulerBizException::class.java) {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(name = "Renamed"))
            }
            assertEquals("Task name already exists", error.message)
        }

        @Test
        fun `update validates a changed cron and skips the check when it is absent`() {
            givenFound()

            val error = assertThrows(SchedulerBizException::class.java) {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(cronExpression = "0 0 9"))
            }
            assertEquals("Invalid cron expression", error.message)

            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt")))
        }

        @Test
        fun `update re-snapshots the agent name only when the agent changes`() {
            givenFound()

            // Same agent id: the stored snapshot stays, and the missing name is not treated as a refusal.
            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(agentId = 100L)))
            assertEquals(
                "News Agent",
                argumentCaptor<AgentTask>().apply { verify(agentTaskMapper).updateById(capture(), eq("admin")) }.firstValue.agentName,
            )

            // A new agent has to come named, or it is the same "Agent not found" the old lookup answered.
            whenever(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)
            val error = assertThrows(SchedulerBizException::class.java) {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(agentId = 200L))
            }
            assertEquals("Agent not found", error.message)

            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(agentId = 200L, agentName = "Other Agent")))
            val moved = argumentCaptor<AgentTask>().apply { verify(agentTaskMapper, times(2)).updateById(capture(), eq("admin")) }
            assertEquals(200L, moved.lastValue.agentId)
            assertEquals("Other Agent", moved.lastValue.agentName)
        }

        /**
         * `selectById` answers a public task to anybody, so the creator condition lives in the UPDATE itself.
         * Zero rows out of that statement is the only way this service learns the caller was not the owner,
         * and it has to say so rather than report a save that never happened.
         */
        @Test
        fun `update refuses to rewrite somebody else's public task`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask.apply { isPublic = 1 })
            whenever(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(0)

            val error = assertThrows(SchedulerBizException::class.java) {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "hijacked"))
            }

            assertEquals("Only the task creator can modify this task", error.message)
            assertTrue(error.message!!.contains("creator"))
        }

        @Test
        fun `update does not reconcile when the write was refused`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            whenever(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(0)

            assertThrows(SchedulerBizException::class.java) { service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "x")) }

            verify(reconciler, never()).reconcile()
        }
    }

    // ==================== Delete ====================

    @Nested
    @DisplayName("Delete")
    inner class DeleteTests {

        @Test
        fun `delete refuses a task the caller may not see`() {
            whenever(agentTaskMapper.selectById(999L, "admin")).thenReturn(null)

            val error = assertThrows(SchedulerBizException::class.java) { service.deleteAgentTask(999L) }

            assertEquals("Agent task not found", error.message)
            verify(agentTaskMapper, never()).deleteById(any(), any())
        }

        @Test
        fun `delete is a soft delete by the same mapper statement the moved service used`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            whenever(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)

            assertTrue(service.deleteAgentTask(1L))

            verify(agentTaskMapper).deleteById(1L, "admin")
        }

        @Test
        fun `delete of a running task goes through the same soft delete`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask.apply { taskStatus = 1 })
            whenever(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)

            assertTrue(service.deleteAgentTask(1L))
        }

        @Test
        fun `delete refuses to remove somebody else's public task`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask.apply { isPublic = 1 })
            whenever(agentTaskMapper.deleteById(1L, "admin")).thenReturn(0)

            val error = assertThrows(SchedulerBizException::class.java) { service.deleteAgentTask(1L) }

            assertEquals("Only the task creator can delete this task", error.message)
            verify(reconciler, never()).reconcile()
        }
    }

    // ==================== Gates the scheduling surface needs ====================

    @Nested
    @DisplayName("Write gates")
    inner class GateTests {

        @Test
        fun `the toggle gate is the visibility rule and nothing tighter`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)

            assertEquals(testTask, service.requireVisibleTask(1L))

            whenever(agentTaskMapper.selectById(2L, "admin")).thenReturn(null)
            val error = assertThrows(SchedulerBizException::class.java) { service.requireVisibleTask(2L) }
            assertEquals("Agent task not found", error.message)
            assertEquals(400, error.code)
        }

        /**
         * The stop gate is the creator of the owning task, which is narrower than what a read allows and
         * wider than nothing: a public task's execution is visible to everybody but stoppable by one person.
         * It is a *write* authorisation, so it has to be checked before the stop runs.
         */
        @Test
        fun `the stop gate takes only the caller, never the tenant`() {
            val log = AgentTaskLog().apply {
                id = 55L
                taskId = 1L
                status = 3
                creator = "admin"
            }
            whenever(agentTaskLogMapper.selectOwnedById(55L, "admin")).thenReturn(log)
            asUser("admin", tenantId = 7L)

            assertEquals(log, service.requireOwnedLog(55L))

            verify(agentTaskLogMapper).selectOwnedById(55L, "admin")
            // "Exists but is not yours" answers exactly like "does not exist": a distinct forbidden text would
            // turn this endpoint into a probe for log ids.
            whenever(agentTaskLogMapper.selectOwnedById(99L, "admin")).thenReturn(null)
            val error = assertThrows(SchedulerBizException::class.java) { service.requireOwnedLog(99L) }
            assertEquals("Agent task log not found", error.message)
            assertFalse(error.message!!.contains("permission"), "the answer must not leak that the row exists")
            assertEquals(400, error.code, "the same shape as every other not-found in this domain")
        }

        @Test
        fun `the stop gate is the only read this service performs on the log table`() {
            assertTrue(
                AgentTaskLogMapper::class.java.methods.first { it.name == "selectOwnedById" }.parameterCount == 2,
                "selectOwnedById stays id/currentUsername — a tenant slot would be stricter than the task writes it guards",
            )
        }
    }

    // ==================== Response mapping ====================

    /**
     * The eighteen keys are the task JSON the webui's table and detail form read; a field this mapping
     * forgets is a key that disappears from every client at once, silently. Asserted one by one rather than
     * counted, because the names are the contract, not their number.
     */
    @Test
    @DisplayName("convertToResponse 输出与 admin 相同的 18 个字段")
    fun `convertToResponse carries every field the client reads`() {
        testTask.lastRunStatus = 1
        testTask.lastRunTime = LocalDateTime.now()

        val response = service.convertToResponse(testTask)

        assertEquals(testTask.id, response.id)
        assertEquals(testTask.tenantId, response.tenantId)
        assertEquals(testTask.name, response.name)
        assertEquals(testTask.agentId, response.agentId)
        assertEquals(testTask.agentName, response.agentName)
        assertEquals(testTask.prompt, response.prompt)
        assertEquals(testTask.cronExpression, response.cronExpression)
        assertEquals(testTask.taskStatus, response.taskStatus)
        assertEquals(testTask.concurrent, response.concurrent)
        assertEquals(testTask.timeoutSeconds, response.timeoutSeconds)
        assertEquals(testTask.description, response.description)
        assertEquals(testTask.isPublic, response.isPublic)
        assertEquals(testTask.creator, response.creator)
        assertEquals(testTask.active, response.active)
        assertEquals(testTask.createTime, response.createTime)
        assertEquals(testTask.updateTime, response.updateTime)
        assertEquals(testTask.lastRunStatus, response.lastRunStatus)
        assertEquals(testTask.lastRunTime, response.lastRunTime)
    }

    @Test
    fun `convertToResponse leaves the two last-run keys null for a task that never ran`() {
        val response = service.convertToResponse(testTask)

        assertNull(response.lastRunStatus, "never run is not the same answer as failed")
        assertNull(response.lastRunTime)
    }

    // ==================== Reconcile after commit ====================

    /**
     * The reconcile must run only for a write that committed. It reads `agent_task` through its own
     * connection, so a round started inside the open transaction registers the row as it was *before* the
     * write — and a rolled-back write must register nothing at all. Admin enforced this across an HTTP call;
     * the property is the same now that the call is local, and so is the reporting: a round that leaves drift
     * after the commit says "stored but not scheduled" ([AgentTaskCrudServiceImpl.CODE_SCHEDULER_SYNC_FAILED])
     * rather than pretending the edit was lost.
     */
    @Nested
    @DisplayName("Reconcile after commit")
    inner class ReconcileTests {

        @AfterEach
        fun clearTransactionSynchronization() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }

        private fun givenUpdateSucceeds() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            whenever(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)
        }

        @Test
        fun `update reconciles nothing while the transaction is open and nothing at all when it rolls back`() {
            givenUpdateSucceeds()
            TransactionSynchronizationManager.initSynchronization()

            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt")))

            verify(reconciler, never()).reconcile()
            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            assertEquals(1, callbacks.size, "the reconcile has to be registered as an after-commit callback")

            callbacks.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            verify(reconciler, never()).reconcile()
        }

        @Test
        fun `update reconciles once the transaction commits`() {
            givenUpdateSucceeds()
            TransactionSynchronizationManager.initSynchronization()

            service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt"))
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }

            verify(reconciler, times(1)).reconcile()
        }

        @Test
        fun `delete reconciles only on commit and never on rollback`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            whenever(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)
            TransactionSynchronizationManager.initSynchronization()

            assertTrue(service.deleteAgentTask(1L))
            verify(reconciler, never()).reconcile()

            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            callbacks.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            verify(reconciler, never()).reconcile()

            callbacks.forEach { it.afterCommit() }
            verify(reconciler, times(1)).reconcile()
        }

        @Test
        fun `a round that leaves drift turns the committed update into an explicit sync failure`() {
            givenUpdateSucceeds()
            whenever(reconciler.reconcile()).thenReturn(ReconcileReport(1, 0, 0, 0, listOf(1L)))
            TransactionSynchronizationManager.initSynchronization()

            service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt"))

            val error = assertThrows(SchedulerBizException::class.java) {
                TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            }
            assertEquals(AgentTaskCrudServiceImpl.CODE_SCHEDULER_SYNC_FAILED, error.code)
            assertTrue(
                error.message!!.contains("saved"),
                "the caller has to learn the row itself was kept, got: ${error.message}",
            )
            assertTrue(
                error.message!!.contains("did not reload"),
                "the 40902 sentence clients have been shown, got: ${error.message}",
            )
            verify(agentTaskMapper).updateById(any(), eq("admin"))
        }

        @Test
        fun `without a transaction the failed round surfaces from the write itself`() {
            whenever(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            whenever(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)
            whenever(reconciler.reconcile()).thenThrow(RuntimeException("Quartz is down"))

            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
            val error = assertThrows(SchedulerBizException::class.java) { service.deleteAgentTask(1L) }

            assertEquals(AgentTaskCrudServiceImpl.CODE_SCHEDULER_SYNC_FAILED, error.code)
            assertTrue(error.message!!.contains("deleted"), "got: ${error.message}")
            assertTrue(error.message!!.contains("the reload call threw"), "got: ${error.message}")
        }
    }
}
