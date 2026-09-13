package com.agnetix.harnax.scheduler.metrics

import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * `scheduler.jobs.scheduled` had no scrape assertion at all, which is how it could report a startup
 * number for months: nothing ever looked at the value the registry would actually publish.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerMetricsTest {

    @Mock
    private lateinit var jobInventory: QuartzJobInventory

    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        SchedulerMetrics(registry, jobInventory).initMeters()
    }

    @Test
    fun `the scheduled-jobs gauge publishes what the store holds`() {
        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(11L, 12L, 13L))

        assertEquals(3.0, registry.get("scheduler.jobs.scheduled").gauge().value())
    }

    /**
     * A gauge is a live supplier, not a captured number: this is the difference between this meter and
     * the load-time count it replaced.
     */
    @Test
    fun `the gauge follows the store instead of freezing at the first reading`() {
        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(1L))
        assertEquals(1.0, registry.get("scheduler.jobs.scheduled").gauge().value())

        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(1L, 2L, 3L, 4L))
        assertEquals(4.0, registry.get("scheduler.jobs.scheduled").gauge().value(), "a re-scrape must see the new content")

        whenever(jobInventory.scheduledTaskIds()).thenReturn(emptySet())
        assertEquals(0.0, registry.get("scheduler.jobs.scheduled").gauge().value())
    }

    /**
     * An unreadable store must not look like an idle node: NaN drops the sample rather than publishing a
     * zero that would read as "this instance schedules nothing" — which is a different alarm.
     */
    @Test
    fun `an unreadable store publishes no sample rather than a plausible zero`() {
        whenever(jobInventory.scheduledTaskIds()).thenThrow(RuntimeException("job store down"))

        assertTrue(
            registry.get("scheduler.jobs.scheduled").gauge().value().isNaN(),
            "a broken store has to show up as NaN",
        )
    }

    @Test
    fun `load attempts are counted per outcome so a retrying node is visible`() {
        val metrics = SchedulerMetrics(registry, jobInventory)

        metrics.recordLoadAttempt(success = true)
        metrics.recordLoadAttempt(success = true)
        metrics.recordLoadAttempt(success = false)

        assertEquals(2.0, registry.get("scheduler.load.attempts").tag("outcome", "success").counter().count())
        assertEquals(1.0, registry.get("scheduler.load.attempts").tag("outcome", "failure").counter().count())
    }
}
