package com.agnetix.harnax.scheduler.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.quartz.Scheduler
import org.quartz.SchedulerMetaData
import org.springframework.boot.health.contributor.Status
import org.springframework.scheduling.quartz.LocalDataSourceJobStore
import org.springframework.scheduling.quartz.SchedulerFactoryBean

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerHealthIndicatorTest {

    @Mock
    private lateinit var schedulerFactory: SchedulerFactoryBean

    @Mock
    private lateinit var quartz: Scheduler

    @Mock
    private lateinit var jobInventory: QuartzJobInventory

    private fun indicator(
        status: SchedulerStatus = SchedulerStatus(true),
    ): SchedulerHealthIndicator {
        // The live count is read through the job inventory now; give the fixture a harmless empty store so
        // a test about the status rules can never be reacting to that read instead.
        whenever(jobInventory.scheduledTaskIds()).thenReturn(emptySet())
        return SchedulerHealthIndicator(status, schedulerFactory, jobInventory)
    }

    @Test
    fun `health is down before any reconcile round has completed`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        val health = indicator().health()

        assertEquals(Status.DOWN, health.status)
        assertEquals("No reconcile has succeeded since startup", health.details["reason"])
        assertEquals("never", health.details["lastReconcileAt"])
    }

    @Test
    fun `health is up once a reconcile round has converged`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(11L, 12L, 13L))

        val status = SchedulerStatus(true)
        status.recordReconcile(jobCount = 3)

        val health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health()

        assertEquals(Status.UP, health.status)
        assertEquals(3, health.details["scheduledJobCount"])
        assertEquals(null, health.details["lastReconcileError"])
    }

    /**
     * A round that left drift stamps the timestamp (it did reach the store) and keeps the error, so the
     * verdict is DOWN on the *error* rule rather than on "never ran".
     *
     * What this case deliberately does **not** license is an alert on the age of `lastReconcileAt`: the
     * scheduled round is a cluster singleton, so the node that never won the sweep only ever stamps its own
     * startup round and the field goes arbitrarily old on a healthy cluster. Drift is the signal — the error
     * detail here and `scheduler.reconcile.drift` — because it moves only when the store could not be made
     * to match the table.
     */
    @Test
    fun `a round that left drift is down on the drift rule not on the timestamp rule`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(1L))

        val status = SchedulerStatus(true)
        status.recordReconcile(jobCount = 1, pendingError = "1 of 2 active tasks could not be registered: ids=[2]")

        val health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health()

        assertEquals(Status.DOWN, health.status)
        assertEquals("Most recent reconcile failed", health.details["reason"])
    }

    /**
     * The count used to be whatever the last round remembered, so it went stale on the first
     * start/pause/CRUD after startup. It is read off the Quartz store now, and the status rules are
     * untouched: how many jobs are live is a detail, never a verdict.
     */
    @Test
    fun `the job count is the live scheduler content, not what the last round registered`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(jobInventory.scheduledTaskIds()).thenReturn(setOf(1L, 2L))

        val status = SchedulerStatus(true)
        // Round-time bookkeeping deliberately disagrees with the store.
        status.recordReconcile(jobCount = 7)

        val health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health()

        assertEquals(2, health.details["scheduledJobCount"], "the live store wins over the round-time number")
        assertEquals(Status.UP, health.status, "the count must not decide UP/DOWN")
    }

    /**
     * The cluster only works if the store is a shared one, and `job-store-type` can be overridden by an
     * env var while the runbook still says JDBC. The running class is the only answer worth publishing.
     */
    @Test
    fun `the store type detail is the job store class quartz itself reports`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        val metaData = mockMetaData(LocalDataSourceJobStore::class.java)
        whenever(quartz.metaData).thenReturn(metaData)

        val health = indicator(SchedulerStatus(true).apply { recordReconcile(0) }).health()

        assertEquals("LocalDataSourceJobStore", health.details["storeType"])
    }

    /** An unreadable meta-data is a detail gap, not a verdict: `quartzStarted` already covers it. */
    @Test
    fun `an unreadable job store type reports unknown instead of failing the probe`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)

        val health = indicator(SchedulerStatus(true).apply { recordReconcile(0) }).health()

        assertEquals("unknown", health.details["storeType"])
    }

    /**
     * A store that cannot be read must not flip the verdict — `quartzStarted` already covers that case —
     * and must not publish a plausible zero either, so -1 is what the detail says.
     */
    @Test
    fun `an unreadable job store reports the count as unavailable instead of guessing`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(true)
        whenever(jobInventory.scheduledTaskIds()).thenThrow(RuntimeException("job store down"))

        val status = SchedulerStatus(true)
        status.recordReconcile(jobCount = 1)

        val health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health()

        assertEquals(-1, health.details["scheduledJobCount"])
        assertEquals(Status.UP, health.status)
    }

    @Test
    fun `health is down while quartz is not started`() {
        whenever(schedulerFactory.scheduler).thenReturn(quartz)
        whenever(quartz.isStarted).thenReturn(false)
        whenever(jobInventory.scheduledTaskIds()).thenReturn(emptySet())
        val status = SchedulerStatus(true)
        status.recordReconcile(jobCount = 1)

        val health = SchedulerHealthIndicator(status, schedulerFactory, jobInventory).health()

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
        assertEquals("unknown", health.details["storeType"])
    }

    @Test
    fun `a standby node with scheduling disabled reports up`() {
        val health = indicator(SchedulerStatus(false)).health()

        assertEquals(Status.UP, health.status)
        assertEquals(false, health.details["enabled"])
    }

    private fun mockMetaData(jobStoreClass: Class<*>): SchedulerMetaData {
        val metaData = mock<SchedulerMetaData>()
        whenever(metaData.jobStoreClass).thenReturn(jobStoreClass)
        return metaData
    }
}
