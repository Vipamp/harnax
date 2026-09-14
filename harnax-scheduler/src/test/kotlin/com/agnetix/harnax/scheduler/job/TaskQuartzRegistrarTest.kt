package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.scheduling.quartz.SchedulerFactoryBean

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

    // The cron has to be a real one: Quartz validates it while the trigger is built, which is the
    // behaviour `SchedulerServiceImplTest` relies on for a bad cron — an empty one throws here first.
    private fun task(id: Long) = AgentTask().apply {
        this.id = id
        cronExpression = "0 0/5 * * * ?"
    }

    @Test
    fun `registering a task stores only its id as a string`() {
        registrar.register(task(7L))

        val captor = argumentCaptor<org.quartz.JobDetail>()
        verify(quartz).scheduleJob(captor.capture(), any(), eq(true))
        val data = captor.firstValue.jobDataMap
        assertEquals("7", data.getString(TaskQuartzRegistrar.KEY_TASK_ID))
        assertEquals(1, data.size, "only the taskId is in the map an entity could ever be a store error")
        assertNull(data["agentTask"], "the serialized entity must be gone: useProperties rejects it")
    }

    @Test
    fun `a task id survives the round trip through the job name`() {
        assertEquals(7L, TaskQuartzRegistrar.taskIdOf(JobKey("AgentTask_7", TaskQuartzRegistrar.GROUP_AGENT_TASK)))
        assertNull(TaskQuartzRegistrar.taskIdOf(JobKey("somethingElse", TaskQuartzRegistrar.GROUP_AGENT_TASK)))
        assertEquals(JobKey("AgentTask_7", "AgentTaskGroup"), registrar.jobKeyOf(7L))
    }
}
