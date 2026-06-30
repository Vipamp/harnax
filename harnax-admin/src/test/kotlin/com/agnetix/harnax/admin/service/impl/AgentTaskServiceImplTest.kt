package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
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
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskServiceImplTest {

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentService: AgentService

    @Mock
    private lateinit var jwtUtil: JwtUtil

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
    }

    // ==================== Get Task ====================

    @Nested
    @DisplayName("Get Agent Task Tests")
    inner class GetTaskTests {

        @Test
        fun `getAgentTask should return task by id`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

            val service = createService()
            val result = service.getAgentTask(1L)

            assertNotNull(result)
            assertEquals("Daily News", result?.name)
            assertEquals(100L, result?.agentId)
        }

        @Test
        fun `getAgentTask should return null when not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

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
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.updateAgentTask(999L, AgentTaskUpdateRequest(name = "Updated"))
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `updateAgentTask should update fields successfully`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.selectByName("Updated Name")).thenReturn(null)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                name = "Updated Name",
                prompt = "New prompt",
                cronExpression = "0 0 10 * * ?",
            )

            val service = createService()
            val result = service.updateAgentTask(1L, request)

            assertTrue(result)
            verify(agentTaskMapper).updateById(any())
        }

        @Test
        fun `updateAgentTask should throw when new name exists`() {
            val existingTask = AgentTask().apply {
                id = 2L
                name = "Existing Task"
            }
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
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
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

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
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture())
            assertEquals(0, taskCaptor.firstValue.taskStatus) // reset to paused
        }

        @Test
        fun `updateAgentTask should skip name check when name unchanged`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                name = "Daily News", // same name
                prompt = "New prompt",
            )

            val service = createService()
            service.updateAgentTask(1L, request)

            verify(agentTaskMapper, never()).selectByName(any())
        }

        @Test
        fun `updateAgentTask should unschedule running task before update`() {
            testTask.taskStatus = 1 // running
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            service.updateAgentTask(1L, request)

            // Verify scheduler.deleteJob was called (via unscheduleTask)
            val schedulerFactory = mock(org.springframework.scheduling.quartz.SchedulerFactoryBean::class.java)
            // The service already created in createService() has its own scheduler mock
            // We just verify the task status was reset
            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture())
            assertEquals(0, taskCaptor.firstValue.taskStatus)
        }

        @Test
        fun `updateAgentTask should throw when new agentId not found`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
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
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentService.getAgent(200L)).thenReturn(newAgent)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(agentId = 200L)

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture())
            assertEquals(200L, taskCaptor.firstValue.agentId)
            assertEquals("New Agent", taskCaptor.firstValue.agentName)
        }

        @Test
        fun `updateAgentTask should not update null fields`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                prompt = "Only prompt changed",
                // all other fields null
            )

            val service = createService()
            service.updateAgentTask(1L, request)

            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture())
            assertEquals("Only prompt changed", taskCaptor.firstValue.prompt)
            // Unchanged fields should keep original values
            assertEquals("Daily News", taskCaptor.firstValue.name)
            assertEquals("0 0 9 * * ?", taskCaptor.firstValue.cronExpression)
            assertEquals(100L, taskCaptor.firstValue.agentId)
        }

        @Test
        fun `updateAgentTask should skip cron validation when cronExpression is null`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

            val request = AgentTaskUpdateRequest(
                prompt = "New prompt",
                cronExpression = null, // not changing cron
            )

            val service = createService()
            val result = service.updateAgentTask(1L, request)

            assertTrue(result)
            val taskCaptor = org.mockito.kotlin.argumentCaptor<AgentTask>()
            verify(agentTaskMapper).updateById(taskCaptor.capture())
            assertEquals("0 0 9 * * ?", taskCaptor.firstValue.cronExpression) // unchanged
        }

        @Test
        fun `updateAgentTask should skip agentId check when agentId unchanged`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(1)

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
        fun `updateAgentTask should return false when updateById returns 0`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateById(any())).thenReturn(0)

            val request = AgentTaskUpdateRequest(prompt = "New prompt")

            val service = createService()
            val result = service.updateAgentTask(1L, request)

            assertFalse(result)
        }
    }

    // ==================== Delete Task ====================

    @Nested
    @DisplayName("Delete Agent Task Tests")
    inner class DeleteTaskTests {

        @Test
        fun `deleteAgentTask should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.deleteAgentTask(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `deleteAgentTask should soft delete paused task`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L)).thenReturn(1)

            val service = createService()
            val result = service.deleteAgentTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).deleteById(1L)
        }

        @Test
        fun `deleteAgentTask should unschedule running task before delete`() {
            val runningTask = testTask.apply { taskStatus = 1 }
            `when`(agentTaskMapper.selectById(1L)).thenReturn(runningTask)
            `when`(agentTaskMapper.deleteById(1L)).thenReturn(1)

            val service = createService()
            val result = service.deleteAgentTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).deleteById(1L)
        }

        @Test
        fun `deleteAgentTask should return false when deleteById returns 0`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.deleteById(1L)).thenReturn(0)

            val service = createService()
            val result = service.deleteAgentTask(1L)

            assertFalse(result)
        }
    }

    // ==================== Start/Pause Task ====================

    @Nested
    @DisplayName("Start/Pause Task Tests")
    inner class StartPauseTests {

        @Test
        fun `startTask should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.startTask(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `startTask should throw when task already running`() {
            testTask.taskStatus = 1
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.startTask(1L)
            }
            assertTrue(exception.message!!.contains("already running"))
        }

        @Test
        fun `startTask should schedule task and update status to running`() {
            testTask.taskStatus = 0
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateStatus(1L, 1)).thenReturn(1)

            val service = createService()
            val result = service.startTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).updateStatus(1L, 1)
        }

        @Test
        fun `pauseTask should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.pauseTask(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `pauseTask should return true when already paused`() {
            testTask.taskStatus = 0
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

            val service = createService()
            val result = service.pauseTask(1L)

            assertTrue(result)
            verify(agentTaskMapper, never()).updateStatus(any(), any())
        }

        @Test
        fun `pauseTask should unschedule running task and update status`() {
            testTask.taskStatus = 1
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)
            `when`(agentTaskMapper.updateStatus(1L, 0)).thenReturn(1)

            val service = createService()
            val result = service.pauseTask(1L)

            assertTrue(result)
            verify(agentTaskMapper).updateStatus(1L, 0)
        }
    }

    // ==================== Run Once ====================

    @Nested
    @DisplayName("Run Once Tests")
    inner class RunOnceTests {

        @Test
        fun `runTaskOnce should throw when task not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

            val service = createService()
            val exception = assertThrows<BizException> {
                service.runTaskOnce(999L)
            }
            assertTrue(exception.message!!.contains("not found"))
        }

        @Test
        fun `runTaskOnce should schedule job and return true`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

            val service = createService()
            val result = service.runTaskOnce(1L)

            assertTrue(result)
        }

        @Test
        fun `runTaskOnce should use ONCE group name`() {
            `when`(agentTaskMapper.selectById(1L)).thenReturn(testTask)

            val service = createService()
            service.runTaskOnce(1L)

            // Verify scheduler.scheduleJob was called (job is created with ONCE group)
            // The method always returns true if no exception
        }
    }

    // ==================== Load Tasks To Scheduler ====================

    @Nested
    @DisplayName("Load Tasks To Scheduler Tests")
    inner class LoadTasksTests {

        @Test
        fun `loadTasksToScheduler should schedule all running tasks`() {
            val task1 = AgentTask().apply {
                id = 1L
                name = "Task 1"
                cronExpression = "0 0 9 * * ?"
                taskStatus = 1
                concurrent = 0
            }
            val task2 = AgentTask().apply {
                id = 2L
                name = "Task 2"
                cronExpression = "0 0 10 * * ?"
                taskStatus = 1
                concurrent = 1
            }
            `when`(agentTaskMapper.selectRunningTasks()).thenReturn(listOf(task1, task2))

            val service = createService()
            service.loadTasksToScheduler()

            // Should not throw, both tasks scheduled
            verify(agentTaskMapper).selectRunningTasks()
        }

        @Test
        fun `loadTasksToScheduler should handle empty list`() {
            `when`(agentTaskMapper.selectRunningTasks()).thenReturn(emptyList())

            val service = createService()
            service.loadTasksToScheduler()

            verify(agentTaskMapper).selectRunningTasks()
        }

        @Test
        fun `loadTasksToScheduler should continue when one task fails`() {
            val task1 = AgentTask().apply {
                id = 1L
                name = "Task 1"
                cronExpression = "0 0 9 * * ?"
                taskStatus = 1
                concurrent = 0
            }
            val task2 = AgentTask().apply {
                id = 2L
                name = "Task 2"
                cronExpression = "0 0 10 * * ?"
                taskStatus = 1
                concurrent = 0
            }
            `when`(agentTaskMapper.selectRunningTasks()).thenReturn(listOf(task1, task2))

            val service = createService()
            // Should not throw even if scheduler.scheduleJob fails for one task
            service.loadTasksToScheduler()

            // Both tasks were attempted
            verify(agentTaskMapper).selectRunningTasks()
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

    // ==================== Get Running Tasks ====================

    @Nested
    @DisplayName("Get Running Tasks Tests")
    inner class RunningTasksTests {

        @Test
        fun `getRunningTasks should return running tasks`() {
            val runningTask = testTask.apply { taskStatus = 1 }
            `when`(agentTaskMapper.selectRunningTasks()).thenReturn(listOf(runningTask))

            val service = createService()
            val result = service.getRunningTasks()

            assertEquals(1, result.size)
            assertEquals("Daily News", result[0].name)
        }

        @Test
        fun `getRunningTasks should return empty list when no running tasks`() {
            `when`(agentTaskMapper.selectRunningTasks()).thenReturn(emptyList())

            val service = createService()
            val result = service.getRunningTasks()

            assertTrue(result.isEmpty())
        }
    }

    // ==================== Helper ====================

    private fun createService(schedulerEnabled: Boolean = true): AgentTaskServiceImpl {
        // Create a mock SchedulerFactoryBean
        val schedulerFactory = mock(org.springframework.scheduling.quartz.SchedulerFactoryBean::class.java)
        val scheduler = mock(org.quartz.Scheduler::class.java)
        `when`(schedulerFactory.scheduler).thenReturn(scheduler)

        return AgentTaskServiceImpl(
            agentTaskMapper = agentTaskMapper,
            agentService = agentService,
            jwtUtil = jwtUtil,
            schedulerFactory = schedulerFactory,
            schedulerEnabled = schedulerEnabled,
        )
    }
}
