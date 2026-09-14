package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.service.ReconcileReport
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * A conflict is a business outcome, not a string to pattern-match: the caller needs a code that
 * survives the admin proxy, because the proxy forwards the message verbatim and the message has
 * already drifted from what the frontend matches on.
 *
 * The codes are asserted as literals on purpose. `40901`/`40903` leave this module as numbers: admin
 * forwards them to the browser (and declares its own `40902` beside them), and
 * `harnax-webui/src/pages/agent-task/constants.ts` spells all three as literals. Comparing the response
 * against `SchedulerController`'s own constant would stay green while someone edited that constant, which
 * is precisely the change this test has to catch — the drift would only show up in the frontend.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerControllerTest {

    @Mock
    private lateinit var schedulerService: SchedulerService

    private fun controller(enabled: Boolean = true) = SchedulerController(schedulerService, SchedulerStatus(enabled))

    @Test
    fun `a rejected trigger because an execution is in flight answers with business code 40901`() {
        val controller = controller()
        whenever(schedulerService.triggerManually(7L)).thenReturn(false)

        val result = controller.trigger(7L)

        assertEquals(40901, result.code)
    }

    @Test
    fun `a rejected run-once because an execution is in flight answers with the same business code`() {
        val controller = controller()
        // false is only ever returned for a conflict here, so the endpoint must not report it as a
        // generic 500 the caller cannot distinguish from a real failure.
        whenever(schedulerService.runTaskOnce(10L)).thenReturn(false)

        val result = controller.runOnce(10L)

        assertEquals(40901, result.code)
    }

    @Test
    fun `a successful trigger still answers 200`() {
        val controller = controller()
        whenever(schedulerService.triggerManually(8L)).thenReturn(true)

        assertEquals(200, controller.trigger(8L).code)
    }

    @Test
    fun `an unexpected failure stays a generic error, not a conflict`() {
        val controller = controller()
        whenever(schedulerService.triggerManually(9L)).thenThrow(RuntimeException("db down"))

        assertEquals(500, controller.trigger(9L).code)
    }

    /**
     * `/reload` used to answer from a boolean that only said "some jobs got registered". It now answers
     * from what the round actually converged: a store left missing one active task is not a success, and
     * the operator needs to be pointed at the detail that names the id.
     */
    @Test
    fun `a reload that converged answers 200`() {
        whenever(schedulerService.reconcileTasks()).thenReturn(reconcileReport())

        assertEquals(200, controller().reload().code)
    }

    @Test
    fun `a reload that left drift answers with an error and names no false success`() {
        whenever(schedulerService.reconcileTasks()).thenReturn(reconcileReport(failedIds = listOf(3L)))

        val result = controller().reload()

        assertEquals(500, result.code)
        assertTrue(
            result.message.contains("/actuator/health"),
            "the caller has to be sent where the ids are, got: ${result.message}",
        )
    }

    private fun reconcileReport(failedIds: List<Long> = emptyList()) = ReconcileReport(
        added = 1,
        removed = 0,
        updated = 0,
        unchanged = 4,
        failedIds = failedIds,
    )

    /**
     * `scheduler.enabled=false` used to be a half switch: it skipped the startup load and the scheduler
     * context registration only, while the Quartz factory and these endpoints stayed open. A "disabled"
     * node therefore still registered jobs — which then died on `null as SchedulerService` inside
     * AgentTaskJob — and answered 200 to whoever asked. The switch has to close the write surface.
     */
    @Test
    fun `every write endpoint refuses the request while scheduling is disabled`() {
        val controller = controller(enabled = false)

        assertEquals(40903, controller.trigger(1L).code)
        assertEquals(40903, controller.start(1L).code)
        assertEquals(40903, controller.pause(1L).code)
        assertEquals(40903, controller.runOnce(1L).code)
        assertEquals(40903, controller.reload().code)

        // Refusing has to happen before the work, not as a report afterwards.
        verifyNoInteractions(schedulerService)
    }

    /**
     * Stopping an execution touches no Quartz object (it flips the log row and asks the router to
     * interrupt), so a node that will not schedule new work must still be able to stop live ones.
     */
    @Test
    fun `stop stays served while scheduling is disabled`() {
        val controller = controller(enabled = false)
        whenever(schedulerService.stopTask(5L)).thenReturn(true)

        assertEquals(200, controller.stopTask(5L).code)

        verify(schedulerService).stopTask(5L)
    }
}
