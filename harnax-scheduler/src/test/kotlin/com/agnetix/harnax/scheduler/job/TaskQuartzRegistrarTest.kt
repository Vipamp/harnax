package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.quartz.TriggerKey
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

    /**
     * A bare number is not a task job. The reconcile diff deletes every id the table no longer asks for, so
     * a hand-made job that merely *parses* after stripping a prefix would be somebody else's schedule, and
     * reading it back as task 7 would have the cluster delete a job nobody here registered.
     */
    @Test
    fun `a name without the task prefix never reads back as a task id`() {
        assertNull(TaskQuartzRegistrar.taskIdOf(JobKey("7", TaskQuartzRegistrar.GROUP_AGENT_TASK)))
        assertNull(TaskQuartzRegistrar.taskIdOf(JobKey("AgentTask_7_ONCE_a1b2c3d4", TaskQuartzRegistrar.GROUP_ONCE)))
    }

    /**
     * The identity the diff reads back has to be the identity it deletes: `unregister` reaching any other
     * key would leave the task's job in the shared store while the table said it was gone (and the next
     * fire would find no row and self-delete it — one round later, on whichever node claimed it).
     */
    @Test
    fun `unregistering deletes exactly the job that registering wrote`() {
        registrar.register(task(7L))
        registrar.unregister(7L)

        val captor = argumentCaptor<JobKey>()
        verify(quartz).deleteJob(captor.capture())
        assertEquals(registrar.jobKeyOf(7L), captor.firstValue)
        assertEquals(JobKey("AgentTask_7", TaskQuartzRegistrar.GROUP_AGENT_TASK), captor.firstValue)
    }

    /**
     * Same key shape the `_ONCE` path uses (`_trigger` suffix, same group as its job): the store's trigger
     * rows are what a cluster node acquires, and a second group here would put a task's two halves in two
     * different places for a reader to guess between.
     */
    @Test
    fun `the trigger key is the job key plus the trigger suffix in the same group`() {
        assertEquals(
            TriggerKey("AgentTask_7_trigger", TaskQuartzRegistrar.GROUP_AGENT_TASK),
            registrar.triggerKeyOf(7L),
        )
        assertEquals(registrar.jobKeyOf(7L).group, registrar.triggerKeyOf(7L).group)
    }

    /** Two groups, on purpose: one is the schedule reconcile owns, the other is a user's pending click. */
    @Test
    fun `the one-shot group is not the group reconcile edits`() {
        assertEquals("AgentTaskGroup", TaskQuartzRegistrar.GROUP_AGENT_TASK)
        assertEquals("AgentTaskGroup_ONCE", TaskQuartzRegistrar.GROUP_ONCE)
        assertNotEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK, TaskQuartzRegistrar.GROUP_ONCE)
    }
}
