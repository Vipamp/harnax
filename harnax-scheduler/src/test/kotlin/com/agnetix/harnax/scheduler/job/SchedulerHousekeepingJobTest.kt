package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.SchedulerContext

/**
 * Housekeeping is what bounds the two tables an execution writes on its way through: the cluster lock
 * row had no caller that ever removed it at all, and the log table never had a retention rule.
 *
 * Every sweep is asserted with its own retention number rather than through the constants: the values
 * are the contract this job exists to keep, and a silent edit to one of them should cost a test.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHousekeepingJobTest {

    @Mock
    private lateinit var context: JobExecutionContext

    @Mock
    private lateinit var scheduler: Scheduler

    @Mock
    private lateinit var guard: AgentTaskExecutionGuard

    @Mock
    private lateinit var schedulerService: SchedulerService

    @BeforeEach
    fun setUp() {
        whenever(context.scheduler).thenReturn(scheduler)
        whenever(scheduler.context).thenReturn(
            SchedulerContext().apply {
                put("executionGuard", guard)
                put("schedulerService", schedulerService)
            },
        )
    }

    @Test
    fun `the sweep drops guard rows past their retention`() {
        SchedulerHousekeepingJob().execute(context)

        verify(guard).cleanupOldExecutions(7)
    }

    @Test
    fun `the sweep releases locks whose holder is gone`() {
        SchedulerHousekeepingJob().execute(context)

        verify(guard).cleanupLeakedLocks()
    }

    /**
     * Without this one the zombie rows only self-heal when a task loads or a user triggers something:
     * a schedule that stops firing leaves its running rows running forever.
     */
    @Test
    fun `the sweep reclaims stale executions on its own`() {
        SchedulerHousekeepingJob().execute(context)

        verify(schedulerService).expireStaleExecutions()
    }

    @Test
    fun `the sweep applies the retention the log table never had`() {
        SchedulerHousekeepingJob().execute(context)

        verify(schedulerService).cleanupOldExecutionLogs(90)
    }

    /**
     * `scheduler.enabled = false` skips the context registration, so a fire that reaches this job with
     * nothing in the context has to come back quietly instead of throwing on every five minutes.
     */
    @Test
    fun `a fire with no collaborators registered is not an error`() {
        whenever(scheduler.context).thenReturn(SchedulerContext())

        assertDoesNotThrow { SchedulerHousekeepingJob().execute(context) }
    }

    /**
     * A sweep slower than its own five-minute period must not put a second multi-row DELETE on the same
     * ranges: the two statements block each other's rows and a deadlock costs both. Same rule as
     * [AgentTaskNonConcurrentJob] — the annotation only counts on the registered class.
     */
    @Test
    fun `two sweeps never run at once`() {
        assertTrue(
            SchedulerHousekeepingJob::class.java.isAnnotationPresent(DisallowConcurrentExecution::class.java),
            "the sweep has to be registered as non-concurrent",
        )
    }
}
