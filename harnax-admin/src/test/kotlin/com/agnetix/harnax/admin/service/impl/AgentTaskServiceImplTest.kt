package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskServiceImplTest {

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentTaskLogMapper: AgentTaskLogMapper

    @Mock
    private lateinit var agentService: AgentService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var schedulerClient: SchedulerClient

    private lateinit var testTask: AgentTask
    private lateinit var testAgent: Agent

    @BeforeEach
    fun setUp() {
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

        testAgent = Agent().apply {
            id = 100L
            name = "News Agent"
            description = "Agent for news"
            systemPrompt = "You are a news agent"
            modelId = 1L
            status = 1
            active = 1
        }

        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")
        // The write paths now act on the broadcast result, so a bare mock would fail every case.
        `when`(schedulerClient.reloadTasks()).thenReturn(ResultVo.success<Void>())
    }

    // ==================== Get Task ====================

    @Nested
    @DisplayName("Get Agent Task Tests")
    inner class GetTaskTests {

        @Test
        fun `getAgentTask should return task by id`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)

            val service = createService()
            val result = service.getAgentTask(1L)

            assertNotNull(result)
            assertEquals("Daily News", result?.name)
            assertEquals(100L, result?.agentId)
        }

        @Test
        fun `getAgentTask should return null when not found`() {
            `when`(agentTaskMapper.selectById(999L, "admin")).thenReturn(null)

            val service = createService()
            val result = service.getAgentTask(999L)

            assertNull(result)
        }
    }

    // ==================== Create Task ====================

    @Nested
    @DisplayName("Create Agent Task Tests")
    inner class CreateTaskTests {

        @Test
        fun `createAgentTask should create task successfully`() {
            `when`(agentTaskMapper.selectByName("New Task")).thenReturn(null)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(agentTaskMapper.insert(any())).thenReturn(1)

            val request = AgentTaskCreateRequest(
                name = "New Task",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )

            val service = createService()
            val result = service.createAgentTask(request)

            assertTrue(result)
            verify(agentTaskMapper).insert(any())
        }

        @Test
        fun `createAgentTask should throw when name exists`() {
            `when`(agentTaskMapper.selectByName("Daily News")).thenReturn(testTask)

            val request = AgentTaskCreateRequest(
                name = "Daily News",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )

            val service = createService()
            val exception = assertThrows<BizException> {
                service.createAgentTask(request)
            }
            assertTrue(exception.message!!.contains("already exists"))
            verify(agentTaskMapper, never()).insert(any())
        }

        @Test
        fun `createAgentTask should throw on invalid cron expression`() {
            `when`(agentTaskMapper.selectByName("New Task")).thenReturn(null)

            val request = AgentTaskCreateRequest(
                name = "New Task",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "invalid-cron",
            )

            val service = createService()
            val exception = assertThrows<BizException> {
                service.createAgentTask(request)
            }
            assertTrue(exception.message!!.contains("Invalid cron"))
        }

        @Test
        fun `createAgentTask should throw when agent not found`() {
            `when`(agentTaskMapper.selectByName("New Task")).thenReturn(null)
            `when`(agentService.getAgent(999L)).thenReturn(null)

            val request = AgentTaskCreateRequest(
                name = "New Task",
                agentId = 999L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )

            val service = createService()
            val exception = assertThrows<BizException> {
                service.createAgentTask(request)
            }
            assertTrue(exception.message!!.contains("Agent not found"))
        }

        @Test
        fun `createAgentTask should set task status to paused by default`() {
            `when`(agentTaskMapper.selectByName("New Task")).thenReturn(null)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(agentTaskMapper.insert(any())).thenReturn(1)

            val request = AgentTaskCreateRequest(
                name = "New Task",
                agentId = 100L,
                prompt = "Do something",
                cronExpression = "0 0 9 * * ?",
            )

            val service = createService()
            service.createAgentTask(request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).insert(taskCaptor.capture())
            assertEquals(0, taskCaptor.firstValue.taskStatus) // paused
        }
    }

    // ==================== Update Task ====================

    @Nested
    @DisplayName("Update Agent Task Tests")
    inner class UpdateTaskTests {

        @Test
        fun `updateAgentTask should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L, "admin")).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(999L, AgentTaskUpdateRequest(name = "Updated"))
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `updateAgentTask should update fields successfully`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.selectByName("Updated Name")).thenReturn(null)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                name = "Updated Name",
                prompt = "New prompt",
                cronExpression = "0 0 10 * * ?",
            )

            val service = createService()
            val result = service.updateAgentTask(1L, request)

            assertTrue(result)
            verify(agentTaskMapper).updateById(any(), eq("admin"))
        }

        @Test
        fun `updateAgentTask should throw when new name exists`() {
            val existingTask = AgentTask().apply {
                id = 2L
                name = "Existing Task"
            }
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.selectByName("Existing Task")).thenReturn(existingTask)

            val request = AgentTaskUpdateRequest(name = "Existing Task")

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(1L, request)
            }
            assertTrue(exception.message!!.contains("already exists"))
        }

        @Test
        fun `updateAgentTask should throw on invalid cron`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)

            val request = AgentTaskUpdateRequest(cronExpression = "bad-cron")

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(1L, request)
            }
            assertTrue(exception.message!!.contains("Invalid cron"))
        }

        @Test
        fun `updateAgentTask should reset status to paused`() {
            testTask.taskStatus = 1 // running
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture(), eq("admin"))
            assertEquals(0, taskCaptor.firstValue.taskStatus) // reset to paused
        }

        @Test
        fun `updateAgentTask should skip name check when name unchanged`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                name = "Daily News", // same name
                prompt = "New prompt",
            )

            val service = createService()
            service.updateAgentTask(1L, request)

            verify(agentTaskMapper, never()).selectByName(any())
        }

        @Test
        fun `updateAgentTask should reset status to paused when running`() {
            testTask.taskStatus = 1 // running
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture(), eq("admin"))
            assertEquals(0, taskCaptor.firstValue.taskStatus) // reset to paused
        }

        @Test
        fun `updateAgentTask should throw when new agentId not found`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentService.getAgent(999L)).thenReturn(null)

            val request = AgentTaskUpdateRequest(agentId = 999L)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(1L, request)
            }
            assertTrue(exception.message!!.contains("Agent not found"))
        }

        @Test
        fun `updateAgentTask should update agentName when agentId changes`() {
            val newAgent = Agent().apply {
                id = 200L
                name = "New Agent"
            }
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentService.getAgent(200L)).thenReturn(newAgent)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(agentId = 200L)

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture(), eq("admin"))
            assertEquals(200L, taskCaptor.firstValue.agentId)
            assertEquals("New Agent", taskCaptor.firstValue.agentName)
        }

        @Test
        fun `updateAgentTask should not update null fields`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                prompt = "Only prompt changed",
                // all other fields null
            )

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture(), eq("admin"))
            assertEquals("Only prompt changed", taskCaptor.firstValue.prompt)
            // Unchanged fields should keep original values
            assertEquals("Daily News", taskCaptor.firstValue.name)
            assertEquals("0 0 9 * * ?", taskCaptor.firstValue.cronExpression)
            assertEquals(100L, taskCaptor.firstValue.agentId)
        }

        @Test
        fun `updateAgentTask should skip cron validation when cronExpression is null`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                prompt = "New prompt",
                cronExpression = null, // not changing cron
            )

            val service = createService()
            val result = service.updateAgentTask(1L, request)

            assertTrue(result)
            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture(), eq("admin"))
            assertEquals("0 0 9 * * ?", taskCaptor.firstValue.cronExpression) // unchanged
        }

        @Test
        fun `updateAgentTask should skip agentId check when agentId unchanged`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                agentId = 100L, // same agentId
                prompt = "New prompt",
            )

            val service = createService()
            service.updateAgentTask(1L, request)

            // Should not call getAgent since agentId is same
            verify(agentService, never()).getAgent(any())
        }

        @Test
        fun `updateAgentTask should throw when updateById matches no row`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(0)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            assertThrows<BizException> { service.updateAgentTask(1L, request) }
        }

        @Test
        fun `updateAgentTask should reject rewriting another user public task`() {
            // selectById 按可见性放行公开任务，updateById 的属主条件才是拦截点
            val foreignPublic = testTask.apply {
                creator = "alice"
                isPublic = 1
            }
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(foreignPublic)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(0)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "hijacked"))
            }
            assertTrue(exception.message!!.contains("creator"))
        }
    }

    // ==================== Delete Task ====================

    @Nested
    @DisplayName("Delete Agent Task Tests")
    inner class DeleteTaskTests {

        @Test
        fun `deleteAgentTask should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L, "admin")).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.deleteAgentTask(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `deleteAgentTask should soft delete paused task`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)

            val service = createService()
            val result = service.deleteAgentTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).deleteById(1L, "admin")
        }

        @Test
        fun `deleteAgentTask should unschedule running task before delete`() {
            val runningTask = testTask.apply { taskStatus = 1 }
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(runningTask)
            `when`(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)

            val service = createService()
            val result = service.deleteAgentTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).deleteById(1L, "admin")
        }

        @Test
        fun `deleteAgentTask should throw when deleteById matches no row`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L, "admin")).thenReturn(0)

            val service = createService()
            assertThrows<BizException> { service.deleteAgentTask(1L) }
        }
    }

    // ==================== Convert To Response ====================

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        fun `convertToResponse should map all fields correctly`() {
            val service = createService()
            val response = service.convertToResponse(testTask)

            assertEquals(1L, response.id)
            assertEquals("Daily News", response.name)
            assertEquals(100L, response.agentId)
            assertEquals("News Agent", response.agentName)
            assertEquals("Summarize today's news", response.prompt)
            assertEquals("0 0 9 * * ?", response.cronExpression)
            assertEquals(0, response.taskStatus)
            assertEquals(0, response.concurrent)
            assertEquals(300, response.timeoutSeconds)
        }

        @Test
        fun `convertToResponse should handle empty fields`() {
            val emptyTask = AgentTask().apply {
                id = 2L
                name = ""
                agentName = ""
                prompt = ""
                cronExpression = ""
            }

            val service = createService()
            val response = service.convertToResponse(emptyTask)

            assertEquals("", response.name)
            assertEquals("", response.agentName)
        }
    }

    // ==================== Scheduler Proxy Tests ====================

    @Nested
    @DisplayName("Scheduler Proxy Methods")
    inner class SchedulerProxyTests {

        @Test
        fun `toggleTaskStatus should delegate the switch to the scheduler`() {
            val service = createService()
            val task = AgentTask().apply { id = 1L }
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(task)
            `when`(schedulerClient.startTask(1L)).thenReturn(ResultVo.success<Void>())

            val result = service.toggleTaskStatus(1L, 1)

            assertTrue(result)
            verify(schedulerClient).startTask(1L)
            // 状态由 scheduler 侧持有并回写，本地不抢着改
            verify(agentTaskMapper, never()).updateStatus(anyLong(), anyInt())
        }

        /**
         * Only the scheduler can call a cron expression invalid — harnax-admin has no Quartz dependency
         * and must not gain one for a string check — so its reason is the caller's only feedback.
         * Returning `false` here answered "Failed to toggle task status" to someone who had typed an
         * expression Quartz rejects.
         */
        @Test
        fun `toggleTaskStatus should forward the scheduler reason instead of a bare failure`() {
            val service = createService()
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(schedulerClient.startTask(1L)).thenReturn(
                ResultVo.error("Failed to start task: CronExpression '0 0 0 * * *' is invalid."),
            )

            val error = assertThrows<BizException> { service.toggleTaskStatus(1L, 1) }

            assertTrue(
                error.message!!.contains("CronExpression '0 0 0 * * *' is invalid"),
                "the scheduler's own reason has to reach the caller, got: ${error.message}",
            )
        }

        @Test
        fun `startTask should delegate to schedulerClient`() {
            val service = createService()
            val expected = ResultVo.success<Void>()
            `when`(schedulerClient.startTask(1L)).thenReturn(expected)

            val result = service.startTask(1L)

            assertEquals(200, result.code)
            verify(schedulerClient).startTask(1L)
        }

        @Test
        fun `pauseTask should delegate to schedulerClient`() {
            val service = createService()
            val expected = ResultVo.success<Void>()
            `when`(schedulerClient.pauseTask(1L)).thenReturn(expected)

            val result = service.pauseTask(1L)

            assertEquals(200, result.code)
            verify(schedulerClient).pauseTask(1L)
        }

        @Test
        fun `toggleTaskStatus should throw when task not found`() {
            val service = createService()
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(null)

            assertThrows<Exception> {
                service.toggleTaskStatus(1L, 1)
            }
        }

        /**
         * The stop path is a *write* against someone else's running execution, and the scheduler cannot
         * police it because it has no end-user context. So the gate has to be here, and it has to run
         * before the forward: once the id is on the wire the interruption already happened.
         */
        @Test
        fun `stopTask refuses a log whose task the caller cannot see and never forwards it`() {
            val service = createService()
            `when`(agentTaskLogMapper.selectVisibleById(eq(99L), any())).thenReturn(null)

            val error = assertThrows<BizException> { service.stopTask(99L) }

            assertEquals(400, error.code, "same shape as any other not-found in this domain")
            // "Exists but is not yours" must not be distinguishable from "does not exist".
            assertFalse(
                error.message!!.contains("permission"),
                "the answer must not leak that the row exists: ${error.message}",
            )
            verify(schedulerClient, never()).stopTask(any())
        }

        @Test
        fun `stopTask forwards a log the caller is allowed to see`() {
            val service = createService()
            `when`(agentTaskLogMapper.selectVisibleById(55L, "admin")).thenReturn(testLog(55L))
            `when`(schedulerClient.stopTask(55L)).thenReturn(ResultVo.success<Void>())

            val result = service.stopTask(55L)

            assertEquals(200, result.code)
            verify(schedulerClient).stopTask(55L)
        }

        /**
         * The gate is only as good as the identity it queries with, so pin that the caller reaches the
         * mapper. R2 is the other half of that: the request tenant must *not* participate, otherwise an
         * owner who switched tenants gets a not-found for their own running execution and can no longer
         * stop it — while the task itself is still listed for them.
         */
        @Test
        fun `stopTask gates on the caller and not on the request tenant`() {
            TenantContext.setTenantId(7L)
            val service = createService()
            `when`(agentTaskLogMapper.selectVisibleById(55L, "admin")).thenReturn(testLog(55L))
            `when`(schedulerClient.stopTask(55L)).thenReturn(ResultVo.success<Void>())

            val result = service.stopTask(55L)

            assertEquals(200, result.code, "带着租户上下文的属主必须仍能停止自己的执行")
            verify(agentTaskLogMapper).selectVisibleById(55L, "admin")
            verify(schedulerClient).stopTask(55L)
        }

        @AfterEach
        fun clearContext() {
            TenantContext.clear()
            RequestContextHolder.resetRequestAttributes()
        }

        private fun testLog(id: Long) = AgentTaskLog().apply {
            this.id = id
            taskId = 1L
            status = 3
            creator = "admin"
        }
    }

    // ==================== Scheduler Reload Notification ====================

    /**
     * The reload broadcast must leave only from a *committed* transaction: harnax-scheduler is a
     * separate process on its own connection pool, so a reload issued before the commit reads the
     * old row and re-registers the old definition — while rolled-back work must not broadcast at all.
     *
     * Written against [TransactionSynchronizationManager] directly instead of a Spring context: the
     * unit test cannot drive a real commit, so it fires the registered callback itself. That is
     * enough to pin the two properties that matter (defer, and never on rollback) without paying for
     * a @SpringBootTest per case.
     */
    @Nested
    @DisplayName("Scheduler Reload Notification")
    inner class SchedulerReloadTests {

        @AfterEach
        fun clearTransactionSynchronization() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }

        @Test
        fun `update broadcasts nothing while the transaction is open and nothing at all when it rolls back`() {
            givenUpdateSucceeds()
            TransactionSynchronizationManager.initSynchronization()

            val service = createService()
            assertTrue(service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt")))

            // Still inside the transaction: broadcasting here is the bug this closes.
            verify(schedulerClient, never()).reloadTasks()

            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            assertEquals(1, callbacks.size, "the reload has to be registered as an after-commit callback")

            // Rollback: afterCommit never runs, so the broadcast never runs.
            callbacks.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            verify(schedulerClient, never()).reloadTasks()
        }

        @Test
        fun `update broadcasts the reload once the transaction commits`() {
            givenUpdateSucceeds()
            TransactionSynchronizationManager.initSynchronization()

            val service = createService()
            service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt"))
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }

            verify(schedulerClient, times(1)).reloadTasks()
        }

        @Test
        fun `delete broadcasts the reload only on commit and never on rollback`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)
            TransactionSynchronizationManager.initSynchronization()

            val service = createService()
            assertTrue(service.deleteAgentTask(1L))
            verify(schedulerClient, never()).reloadTasks()

            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            callbacks.forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            verify(schedulerClient, never()).reloadTasks()

            callbacks.forEach { it.afterCommit() }
            verify(schedulerClient, times(1)).reloadTasks()
        }

        /**
         * A failed reload after the commit is a *partial* failure: the row is already durable, so the
         * caller must not be told the edit was lost — but it must not hear "success" either, because
         * the old schedule is still live. Hence a dedicated business code plus a message that says so.
         */
        @Test
        fun `a reload answered with a non-200 turns the committed update into an explicit sync failure`() {
            givenUpdateSucceeds()
            `when`(schedulerClient.reloadTasks()).thenReturn(ResultVo.error(500, "scheduler boom"))
            TransactionSynchronizationManager.initSynchronization()

            val service = createService()
            service.updateAgentTask(1L, AgentTaskUpdateRequest(prompt = "New prompt"))

            val exception = assertThrows<BizException> {
                TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            }
            assertEquals(AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED, exception.code)
            assertTrue(
                exception.message!!.contains("saved"),
                "the caller has to learn the row itself was kept, got: ${exception.message}",
            )
            // The write is not undone by the failed broadcast.
            verify(agentTaskMapper).updateById(any(), eq("admin"))
        }

        @Test
        fun `without a transaction the failed reload surfaces from the write itself`() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L, "admin")).thenReturn(1)
            `when`(schedulerClient.reloadTasks()).thenReturn(ResultVo.error(500, "scheduler boom"))

            val service = createService()
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
            val exception = assertThrows<BizException> {
                service.deleteAgentTask(1L)
            }
            assertEquals(AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED, exception.code)
            assertTrue(exception.message!!.contains("deleted"), "got: ${exception.message}")
        }

        private fun givenUpdateSucceeds() {
            `when`(agentTaskMapper.selectById(1L, "admin")).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any(), eq("admin"))).thenReturn(1)
        }
    }

    // ==================== Helper ====================

    private fun createService(): AgentTaskServiceImpl = AgentTaskServiceImpl(
        agentTaskMapper = agentTaskMapper,
        agentTaskLogMapper = agentTaskLogMapper,
        agentService = agentService,
        jwtUtil = jwtUtil,
        schedulerClient = schedulerClient,
    )
}
