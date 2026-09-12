package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.scheduler.service.SchedulerService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * A conflict is a business outcome, not a string to pattern-match: the caller needs a code that
 * survives the admin proxy, because the proxy forwards the message verbatim and the message has
 * already drifted from what the frontend matches on.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerControllerTest {

    @Mock
    private lateinit var schedulerService: SchedulerService

    @Test
    fun `a rejected trigger because an execution is in flight answers with business code 40901`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(7L)).thenReturn(false)

        val result = controller.trigger(7L)

        assertEquals(40901, result.code)
    }

    @Test
    fun `a rejected run-once because an execution is in flight answers with the same business code`() {
        val controller = SchedulerController(schedulerService)
        // false is only ever returned for a conflict here, so the endpoint must not report it as a
        // generic 500 the caller cannot distinguish from a real failure.
        whenever(schedulerService.runTaskOnce(10L)).thenReturn(false)

        val result = controller.runOnce(10L)

        assertEquals(40901, result.code)
    }

    @Test
    fun `a successful trigger still answers 200`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(8L)).thenReturn(true)

        assertEquals(200, controller.trigger(8L).code)
    }

    @Test
    fun `an unexpected failure stays a generic error, not a conflict`() {
        val controller = SchedulerController(schedulerService)
        whenever(schedulerService.triggerManually(9L)).thenThrow(RuntimeException("db down"))

        assertEquals(500, controller.trigger(9L).code)
    }
}
