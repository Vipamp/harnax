package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.AgentTaskNonConcurrentJob
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

/**
 * The meter is a *round* counter now, and this is the seam that keeps it one: the service's startup loop and
 * [TaskScheduleReconciler] both report, and if the loop's `else` branch ever stopped being exclusive, or the
 * reconciler grew a second reporter, a healthy cluster would publish two samples per minute per node and the
 * renamed meter would lie in exactly the way its old name used to.
 *
 * Real reconciler, real registry: a stub of either side could not answer "how many samples did one round
 * leave behind".
 */
class ReconcileRoundMeterTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = SchedulerMetrics(registry, mock<QuartzJobInventory>())
    private val mapper: AgentTaskMapper = mock()
    private val registrar: TaskQuartzRegistrar = mock()
    private val inventory: QuartzJobInventory = mock()
    private val reconciler = TaskScheduleReconciler(
        mapper,
        registrar,
        inventory,
        SchedulerStatus(schedulerEnabled = true),
        metrics,
    )

    private fun count(outcome: String): Double = registry.find("scheduler.reconcile.rounds").tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `one converged round is one sample and only ever one`() {
        givenStoreMatchesTable()

        repeat(3) { reconciler.reconcile() }

        assertEquals(3.0, count("success"))
        assertEquals(0.0, count("failure"))
    }

    @Test
    fun `a round that left drift is one failure sample not two`() {
        doReturn(listOf(task(1L))).`when`(mapper).selectRunningTasks()
        doReturn(emptyMap<Long, RegisteredJob>()).`when`(inventory).agentTaskJobs()
        org.mockito.kotlin.doThrow(RuntimeException("bad cron")).`when`(registrar).register(any())

        reconciler.reconcile()

        assertEquals(1.0, count("failure"), "the verdict is reported by the round that produced it")
        assertEquals(0.0, count("success"))
    }

    private fun givenStoreMatchesTable() {
        doReturn(listOf(task(1L))).`when`(mapper).selectRunningTasks()
        doReturn(mapOf(1L to RegisteredJob(CRON, AgentTaskNonConcurrentJob::class.java.name)))
            .`when`(inventory).agentTaskJobs()
    }

    private fun task(id: Long) = AgentTask().apply {
        this.id = id
        cronExpression = CRON
        concurrent = 0
    }

    companion object {
        private const val CRON = "0 0 * * * ?"
    }
}
