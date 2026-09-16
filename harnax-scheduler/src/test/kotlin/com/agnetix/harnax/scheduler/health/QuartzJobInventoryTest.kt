package com.agnetix.harnax.scheduler.health

import com.agnetix.harnax.scheduler.job.AgentTaskJob
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SchedulerException
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * The single implementation of "what is in the Quartz store right now", shared by the reconcile diff, the
 * health detail and the `scheduler.jobs.scheduled` gauge. Those three used to be two copies plus a proxy
 * hop, so the read itself is what this suite pins down.
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
     * would make the gauge move every time somebody pressed "run now" — and reconcile would delete a click
     * that has not fired yet. The mock answers through the matcher the inventory passes, so this fails if
     * that read ever stops being group-scoped.
     */
    @Test
    fun `the read is scoped to the recurring group so one-off runs are not counted`() {
        val store = setOf(
            JobKey("AgentTask_7", TaskQuartzRegistrar.GROUP_AGENT_TASK),
            JobKey("AgentTask_8_ONCE_a1b2c3d4", TaskQuartzRegistrar.GROUP_ONCE),
        )
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        stubStore(store)

        assertEquals(setOf(7L), inventory().scheduledTaskIds())
    }

    /**
     * A name that only *looks* like a task is not one: reconcile deletes what the table no longer asks
     * for, so mistaking a hand-made job for task 7 would take away a schedule nobody registered here. The
     * bare "7" is the case a plain "strip the prefix and parse the rest" reader gets wrong.
     */
    @Test
    fun `a name that is not a task job is skipped instead of failing the whole read`() {
        givenJobs("AgentTask_3", "somebody_manual_job", "7")

        assertEquals(setOf(3L), inventory().scheduledTaskIds())
    }

    @Test
    fun `an empty store reads as an empty set, not as a failure`() {
        givenJobs()

        assertTrue(inventory().scheduledTaskIds().isEmpty())
    }

    /**
     * The diff compares *this* against the table, so both halves of what a job is have to come off the
     * store: the cron the trigger really carries and the class that really got registered. A node that
     * asked its own memory instead would conclude "nothing changed" after another node re-registered a
     * task with a different cron, and would then never repair it.
     */
    @Test
    fun `the snapshot carries the cron and the registered class back off the store`() {
        givenJobs("AgentTask_7")
        withCron(7L, "0 0 5 * * ?")
        withJobClass(7L, AgentTaskJob::class.java)

        assertEquals(
            mapOf(7L to RegisteredJob("0 0 5 * * ?", AgentTaskJob::class.java.name)),
            inventory().agentTaskJobs(),
        )
    }

    /**
     * What the diff actually compares against: `CronExpression`'s constructor uppercases its argument
     * (`cronExpression.toUpperCase(Locale.US)`), the trigger hands that string back and a JDBC store persists
     * and re-reads exactly it, so `0 0 9 ? * mon-fri` comes back as `0 0 9 ? * MON-FRI`. The webui presets ship
     * the lettered form, so a reconciler that compared against the raw column would rewrite such a job's
     * trigger on every round. Pinned on data rather than on a mock's string, because it is Quartz's own
     * normalization the compare has to follow.
     */
    @Test
    fun `the cron comes back in the normalized form quartz stores`() {
        givenJobs("AgentTask_7")
        withCron(7L, "0 0 9 ? * mon-fri")

        assertEquals("0 0 9 ? * MON-FRI", inventory().agentTaskJobs()[7L]?.cronExpression)
    }

    /**
     * One unreadable job must not wedge every round. `getJobDetail` loads the class, so a rolling deploy
     * that renames a job class while its row survives in the *shared* store makes this read throw for that
     * key on every node — and a snapshot that throws takes the whole round with it, leaving this node
     * converging nothing at all, including the tasks it could have fixed. The unparseable key is skipped and
     * logged; its task is either re-registered by the same round (absent from the snapshot, wanted by the
     * table) or left for a build that has the class.
     */
    @Test
    fun `one job the store cannot load is skipped instead of failing the whole snapshot`() {
        givenJobs("AgentTask_7", "AgentTask_8")
        withCron(7L, "0 0 5 * * ?")
        whenever(quartz.getJobDetail(keyOf(8L))).thenThrow(
            SchedulerException(
                "Couldn't load job class name: com.agnetix.harnax.scheduler.job.RenamedJob",
                ClassNotFoundException("com.agnetix.harnax.scheduler.job.RenamedJob"),
            ),
        )

        val snapshot = inventory().agentTaskJobs()

        assertEquals(setOf(7L), snapshot.keys, "the readable half of the store is still a usable diff")
        assertEquals("0 0 5 * * ?", snapshot[7L]?.cronExpression)
    }

    /**
     * A durable job left behind by a delete, or one somebody registered by hand, has no cron to compare.
     * `null` is what tells the reconciler "not matching, rewrite it" instead of quietly treating it as a
     * schedule that is already right.
     */
    @Test
    fun `a job with no trigger snapshots as a missing cron`() {
        givenJobs("AgentTask_7")

        val snapshot = inventory().agentTaskJobs()

        assertEquals(AgentTaskNonConcurrentJob::class.java.name, snapshot[7L]?.jobClassName)
        assertNull(snapshot[7L]?.cronExpression)
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
        stubStore(names.map { JobKey(it, TaskQuartzRegistrar.GROUP_AGENT_TASK) }.toSet())
    }

    /**
     * Answer the three reads the snapshot makes through the store's own keys, so a test that asks for a
     * different group gets a different answer rather than the whole table.
     */
    private fun stubStore(keys: Set<JobKey>) {
        whenever(quartz.getJobKeys(any<GroupMatcher<JobKey>>())).thenAnswer { call ->
            val matcher = call.getArgument<GroupMatcher<JobKey>>(0)
            keys.filter { matcher.isMatch(it) }.toSet()
        }
        keys.forEach { key ->
            whenever(quartz.getJobDetail(key)).thenReturn(
                JobBuilder.newJob(AgentTaskNonConcurrentJob::class.java).withIdentity(key).storeDurably().build(),
            )
            whenever(quartz.getTriggersOfJob(key)).thenReturn(emptyList<Trigger>())
        }
    }

    /**
     * A real cron trigger rather than a mock of the interface: the snapshot picks the cron out with a type
     * filter, and only Quartz's own trigger implementation proves that filter matches what the store hands
     * back. A mock would keep the test green while the read went through the wrong class.
     */
    private fun withCron(
        taskId: Long,
        cron: String,
    ) {
        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey("AgentTask_${taskId}_trigger", TaskQuartzRegistrar.GROUP_AGENT_TASK))
            .withSchedule(CronScheduleBuilder.cronSchedule(cron))
            .build()
        whenever(quartz.getTriggersOfJob(keyOf(taskId))).thenReturn(listOf(trigger))
    }

    private fun withJobClass(
        taskId: Long,
        jobClass: Class<out org.quartz.Job>,
    ) {
        whenever(quartz.getJobDetail(keyOf(taskId))).thenReturn(
            JobBuilder.newJob(jobClass).withIdentity(keyOf(taskId)).storeDurably().build(),
        )
    }

    private fun keyOf(taskId: Long) = JobKey("AgentTask_$taskId", TaskQuartzRegistrar.GROUP_AGENT_TASK)
}
