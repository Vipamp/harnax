package com.agnetix.harnax.scheduler.health

import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Scheduler
import org.springframework.boot.health.contributor.Status
import org.springframework.scheduling.quartz.SchedulerFactoryBean

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHealthIndicatorTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: Scheduler

    @Mock
    private lateinit var schedulerService: SchedulerService

    private fun indicator(
        status: SchedulerStatus = SchedulerStatus(true),
    ): SchedulerHealthIndicator {
        // The live count is read through the service now; give the fixture a harmless empty store so a
        // test about the status rules can never be reacting to that read instead.
        whenever(schedulerService.getScheduledTaskIds()).thenReturn(emptySet())
        return SchedulerHealthIndicator(status, schedulerFactory, schedulerService)
    }

    @Test
    fun `health is down before any load has succeeded`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        val health = indicator().health()

        assertEquals(Status.DOWN, health.status)
        assertEquals("No task load has succeeded since startup", health.details["reason"])
        assertEquals("never", health.details["lastLoadSuccessAt"])
    }

    @Test
    fun `health is up once a load has succeeded`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(schedulerService.getScheduledTaskIds()).thenReturn(setOf(11L, 12L, 13L))

        val status = SchedulerStatus(true)
        status.recordLoadSuccess(jobCount = 3)

        val health = SchedulerHealthIndicator(status, schedulerFactory, schedulerService).health()

        assertEquals(Status.UP, health.status)
        assertEquals(3, health.details["scheduledJobCount"])
        assertEquals(null, health.details["lastLoadError"])
    }

    /**
     * The count used to be whatever the last load remembered, so it went stale on the first
     * start/pause/CRUD after startup. It is read off the Quartz store now, and the status rules are
     * untouched: how many jobs are live is a detail, never a verdict.
     */
    @Test
    fun `the job count is the live scheduler content, not what the last load registered`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(schedulerService.getScheduledTaskIds()).thenReturn(setOf(1L, 2L))

        val status = SchedulerStatus(true)
        // Load-time bookkeeping deliberately disagrees with the store.
        status.recordLoadSuccess(jobCount = 7)

        val health = SchedulerHealthIndicator(status, schedulerFactory, schedulerService).health()

        assertEquals(2, health.details["scheduledJobCount"], "the live store wins over the load-time number")
        assertEquals(Status.UP, health.status, "the count must not decide UP/DOWN")
    }

    /**
     * A store that cannot be read must not flip the verdict — `quartzStarted` already covers that case —
     * and must not publish a plausible zero either, so -1 is what the detail says.
     */
    @Test
    fun `an unreadable job store reports the count as unavailable instead of guessing`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(schedulerService.getScheduledTaskIds()).thenThrow(RuntimeException("job store down"))

        val status = SchedulerStatus(true)
        status.recordLoadSuccess(jobCount = 1)

        val health = SchedulerHealthIndicator(status, schedulerFactory, schedulerService).health()

        assertEquals(-1, health.details["scheduledJobCount"])
        assertEquals(Status.UP, health.status)
    }

    @Test
    fun `health is down while quartz is not started`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(false)
        whenever(schedulerService.getScheduledTaskIds()).thenReturn(emptySet())
        val status = SchedulerStatus(true)
        status.recordLoadSuccess(jobCount = 1)

        val health = SchedulerHealthIndicator(status, schedulerFactory, schedulerService).health()

        assertEquals(Status.DOWN, health.status)
        assertEquals("Quartz scheduler is not started", health.details["reason"])
    }

    @Test
    fun `health is down when the job store cannot be reached`() {
        whenever(schedulerFactory.scheduler).thenThrow(IllegalStateException("JobStore failure"))

        val health = indicator().health()

        assertEquals(Status.DOWN, health.status)
        assertEquals("Quartz scheduler is not started", health.details["reason"])
        assertEquals("unknown", health.details["instanceId"])
    }

    @Test
    fun `a standby node with scheduling disabled reports up`() {
        val health = indicator(SchedulerStatus(false)).health()

        assertEquals(Status.UP, health.status)
        assertEquals(false, health.details["enabled"])
    }
}
