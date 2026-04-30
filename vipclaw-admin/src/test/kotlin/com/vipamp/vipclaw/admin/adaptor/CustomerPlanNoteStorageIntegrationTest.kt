package com.vipamp.vipclaw.admin.adaptor

import com.vipamp.vipclaw.admin.mapper.PlanNoteMapper
import com.vipamp.vipclaw.agent.CustomerPlanNoteStorage
import com.vipamp.vipclaw.agent.adaptor.PlanNoteAdaptor
import com.vipamp.vipclaw.agent.adaptor.TaskState
import io.agentscope.core.plan.model.Plan
import io.agentscope.core.plan.model.PlanState
import io.agentscope.core.plan.model.SubTask
import io.agentscope.core.plan.model.SubTaskState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@SpringBootTest
class CustomerPlanNoteStorageIntegrationTest {

    @Autowired
    private lateinit var planNoteAdaptor: PlanNoteAdaptor

    @Autowired
    private lateinit var planNoteMapper: PlanNoteMapper

    private lateinit var storage: CustomerPlanNoteStorage
    private val testSessionId = "test-session-123"
    private val testPlanId = "test-plan-456"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @BeforeEach
    fun setUp() {
        // 清理测试数据
        planNoteMapper.deleteBySessionId(testSessionId)
        storage = CustomerPlanNoteStorage(testSessionId, planNoteAdaptor)
    }

    @Test
    fun `test addPlan should correctly save finishAt and costTimeSeconds`() {
        // 创建一个包含 finishedAt 的 Plan
        val createdAt = LocalDateTime.now().minusMinutes(10).format(dateTimeFormatter)
        val finishedAt = LocalDateTime.now().format(dateTimeFormatter)

        val subTask = SubTask("Test SubTask", "Description", "Expected outcome")
        subTask.state = SubTaskState.DONE
        subTask.createdAt = createdAt
        subTask.finishedAt = finishedAt

        val plan = Plan("Test Plan", "Plan description", "Expected outcome", listOf(subTask))
        plan.id = testPlanId
        plan.state = PlanState.DONE
        plan.createdAt = createdAt
        plan.finishedAt = finishedAt

        // 调用 addPlan 方法
        val result: Mono<Void> = storage.addPlan(plan)
        result.block() // 等待异步操作完成

        // 验证数据已保存到数据库
        val savedEntity = planNoteMapper.selectBySessionIdAndPlanId(testSessionId, testPlanId)
        assertNotNull(savedEntity)
        assertEquals(testPlanId, savedEntity?.planId)
        assertEquals("Test Plan", savedEntity?.name)

        // 验证 finishedAt 字段是否正确保存
        assertEquals(finishedAt, savedEntity?.finishedAt)

        // 验证 costTimeSeconds 是否正确计算和保存
        // 10分钟 = 600秒
        assertEquals(600L, savedEntity?.costTimeseconds)

        // 验证状态
        assertEquals(TaskState.DONE.name, savedEntity?.status)
    }

    @Test
    fun `test addPlan should handle null finishedAt correctly`() {
        // 创建一个没有 finishedAt 的 Plan（进行中）
        val createdAt = LocalDateTime.now().format(dateTimeFormatter)

        val subTask = SubTask("Test SubTask", "Description", "Expected outcome")
        subTask.state = SubTaskState.IN_PROGRESS
        subTask.createdAt = createdAt
        // finishedAt 为 null

        val plan = Plan("Test Plan In Progress", "Plan description", "Expected outcome", listOf(subTask))
        plan.id = testPlanId
        plan.state = PlanState.IN_PROGRESS
        plan.createdAt = createdAt
        plan.finishedAt = null

        // 调用 addPlan 方法
        val result: Mono<Void> = storage.addPlan(plan)
        result.block()

        // 验证数据已保存到数据库
        val savedEntity = planNoteMapper.selectBySessionIdAndPlanId(testSessionId, testPlanId)
        assertNotNull(savedEntity)

        // 验证 finishedAt 为 null
        assertNull(savedEntity?.finishedAt)

        // 验证 costTimeSeconds 为 0（因为 finishedAt 为 null）
        assertEquals(0L, savedEntity?.costTimeseconds)

        // 验证状态
        assertEquals(TaskState.IN_PROGRESS.name, savedEntity?.status)
    }

    @Test
    fun `test addPlan should handle multiple subtasks correctly`() {
        // 创建包含多个子任务的 Plan
        val createdAt = LocalDateTime.now().minusMinutes(30).format(dateTimeFormatter)
        val finishedAt1 = LocalDateTime.now().minusMinutes(20).format(dateTimeFormatter)
        val finishedAt2 = LocalDateTime.now().minusMinutes(5).format(dateTimeFormatter)

        val subTask1 = SubTask("SubTask 1", "Description 1", "Outcome 1")
        subTask1.state = SubTaskState.DONE
        subTask1.createdAt = createdAt
        subTask1.finishedAt = finishedAt1

        val subTask2 = SubTask("SubTask 2", "Description 2", "Outcome 2")
        subTask2.state = SubTaskState.DONE
        subTask2.createdAt = createdAt
        subTask2.finishedAt = finishedAt2

        val plan = Plan("Multi SubTask Plan", "Description", "Outcome", listOf(subTask1, subTask2))
        plan.id = testPlanId
        plan.state = PlanState.DONE
        plan.createdAt = createdAt
        plan.finishedAt = finishedAt2

        // 调用 addPlan 方法
        val result: Mono<Void> = storage.addPlan(plan)
        result.block()

        // 验证数据已保存到数据库
        val savedEntity = planNoteMapper.selectBySessionIdAndPlanId(testSessionId, testPlanId)
        assertNotNull(savedEntity)

        // 验证 finishedAt 字段
        assertEquals(finishedAt2, savedEntity?.finishedAt)

        // 验证 costTimeSeconds (30分钟 = 1800秒)
        assertEquals(1800L, savedEntity?.costTimeseconds)
    }

    @Test
    fun `test addPlan should correctly calculate costTimeSeconds with different time intervals`() {
        // 测试不同的时间间隔
        val testCases = listOf(
            Pair(5L, 5L),      // 5秒
            Pair(60L, 60L),    // 1分钟
            Pair(3600L, 3600L) // 1小时
        )

        for ((seconds, expectedSeconds) in testCases) {
            // 清理之前的数据
            planNoteMapper.deleteBySessionId(testSessionId)

            val createdAt = LocalDateTime.now().minusSeconds(seconds).format(dateTimeFormatter)
            val finishedAt = LocalDateTime.now().format(dateTimeFormatter)

            val plan = Plan("Time Test Plan", "Description", "Outcome", emptyList())
            plan.id = testPlanId
            plan.state = PlanState.DONE
            plan.createdAt = createdAt
            plan.finishedAt = finishedAt

            // 调用 addPlan 方法
            val result: Mono<Void> = storage.addPlan(plan)
            result.block()

            // 验证数据
            val savedEntity = planNoteMapper.selectBySessionIdAndPlanId(testSessionId, testPlanId)
            assertNotNull(savedEntity)

            // 验证 costTimeSeconds 应该接近预期值（允许1秒误差，因为时间计算可能有微小差异）
            val actualSeconds = savedEntity?.costTimeseconds
            if (actualSeconds != null) {
                assertTrue(
                    actualSeconds >= expectedSeconds - 1 && actualSeconds <= expectedSeconds + 1,
                    "Expected cost time to be around $expectedSeconds seconds, but was $actualSeconds"
                )
            }
        }
    }

    @Test
    fun `test addPlan should handle invalid time format gracefully`() {
        // 测试无效的时间格式
        val createdAt = "invalid-time-format"
        val finishedAt = "also-invalid"

        val plan = Plan("Invalid Time Plan", "Description", "Outcome", emptyList())
        plan.id = testPlanId
        plan.state = PlanState.DONE
        plan.createdAt = createdAt
        plan.finishedAt = finishedAt

        // 调用 addPlan 方法 - 应该不会抛出异常，而是记录错误日志
        val result: Mono<Void> = storage.addPlan(plan)
        result.block()

        // 验证数据已保存到数据库
        val savedEntity = planNoteMapper.selectBySessionIdAndPlanId(testSessionId, testPlanId)
        assertNotNull(savedEntity)

        // 验证 costTimeSeconds 为 0（因为时间格式无效）
        assertEquals(0L, savedEntity?.costTimeseconds)
    }

    @Test
    fun `test addPlan with null plan should not save anything`() {
        // 调用 addPlan 方法，传入 null
        val result: Mono<Void> = storage.addPlan(null)
        result.block()

        // 验证没有数据被保存
        val planNotes = planNoteMapper.selectBySessionId(testSessionId)
        assertTrue(planNotes.isEmpty())
    }
}