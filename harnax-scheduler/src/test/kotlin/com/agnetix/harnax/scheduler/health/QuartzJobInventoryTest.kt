package com.agnetix.harnax.scheduler.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * The single implementation of "what is in the Quartz store right now", shared by the service, the health
 * detail and the `scheduler.jobs.scheduled` gauge. Those three used to be two copies plus a proxy hop, so
 * the read itself is what this suite pins down.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuartzJobInventoryTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: Scheduler

    @Test
    fun `the ids come back parsed from the AgentTask job names of the task group`() {
        givenJobs("AgentTask_1", "AgentTask_2", "AgentTask_42")

        assertEquals(setOf(1L, 2L, 42L), inventory().scheduledTaskIds())
    }

    /**
     * A one-off run is an execution, not a schedule: it lives in `AgentTaskGroup_ONCE`, and counting it
     * would make the gauge move every time somebody pressed "run now". The mock answers through the
     * matcher the inventory passes, so this fails if that read ever stops being group-scoped.
     */
    @Test
    fun `the read is scoped to the recurring group so one-off runs are not counted`() {
        val store = setOf(
            JobKey("AgentTask_7", "AgentTaskGroup"),
            JobKey("AgentTask_8_ONCE_a1b2c3d4", "AgentTaskGroup_ONCE"),
        )
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.getJobKeys(any())).thenAnswer { call ->
            val matcher = call.getArgument<GroupMatcher<JobKey>>(0)
            store.filter { matcher.isMatch(it) }.toSet()
        }

        assertEquals(setOf(7L), inventory().scheduledTaskIds())
    }

    @Test
    fun `a name that is not a task job is skipped instead of failing the whole read`() {
        givenJobs("AgentTask_3", "somebody_manual_job")

        assertEquals(setOf(3L), inventory().scheduledTaskIds())
    }

    @Test
    fun `an empty store reads as an empty set, not as a failure`() {
        givenJobs()

        assertTrue(inventory().scheduledTaskIds().isEmpty())
    }

    /**
     * The callers decide what an unreadable store means — -1 for the health detail, NaN for the gauge —
     * so swallowing it in here would hand both of them a plausible zero.
     */
    @Test
    fun `an unreadable store propagates so each caller picks its own unavailable marker`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.getJobKeys(any<GroupMatcher<JobKey>>())).thenThrow(RuntimeException("job store down"))

        assertThrows(RuntimeException::class.java) { inventory().scheduledTaskIds() }
    }

    private fun inventory() = QuartzJobInventory(schedulerFactory)

    private fun givenJobs(vararg names: String) {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.getJobKeys(any())).thenReturn(names.map { JobKey(it, "AgentTaskGroup") }.toSet())
    }
}
