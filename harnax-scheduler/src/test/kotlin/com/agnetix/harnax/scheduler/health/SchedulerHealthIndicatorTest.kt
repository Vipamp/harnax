package com.agnetix.harnax.scheduler.health

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

    private fun indicator(enabled: Boolean = true) = SchedulerHealthIndicator(SchedulerStatus(enabled), schedulerFactory)

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

        val status = SchedulerStatus(true)
        status.recordLoadSuccess(jobCount = 3)

        val health = SchedulerHealthIndicator(status, schedulerFactory).health()

        assertEquals(Status.UP, health.status)
        assertEquals(3, health.details["scheduledJobCount"])
        assertEquals(null, health.details["lastLoadError"])
    }

    @Test
    fun `health is down while quartz is not started`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(false)
        val status = SchedulerStatus(true)
        status.recordLoadSuccess(jobCount = 1)

        val health = SchedulerHealthIndicator(status, schedulerFactory).health()

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
        val health = indicator(enabled = false).health()

        assertEquals(Status.UP, health.status)
        assertEquals(false, health.details["enabled"])
    }
}
