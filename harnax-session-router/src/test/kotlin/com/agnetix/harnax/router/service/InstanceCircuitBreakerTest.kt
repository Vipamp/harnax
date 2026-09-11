package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Runs the shared breaker contract against the in-memory implementation, plus the bookkeeping that
 * only exists there: a circuit that healed must leave no trace behind in the map.
 */
class InstanceCircuitBreakerTest {

    @TestFactory
    fun `breaker contract`(): List<DynamicTest> = InstanceCircuitBreakerContract(factory = ::LocalInstanceCircuitBreaker).tests()

    @Test
    fun `a recovered circuit holds no state`() {
        val local = LocalInstanceCircuitBreaker(failureThreshold = 2, openDurationMs = 50)
        local.recordFailure("inst-a")
        local.recordFailure("inst-a")
        assertTrue(local.isOpen("inst-a"))

        local.recordSuccess("inst-a")

        assertFalse(local.isOpen("inst-a"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, local.getState("inst-a"))
        assertEquals(0, local.getFailureCount("inst-a"))

        local.reset("inst-a")
        assertFalse(local.isOpen("inst-a"))
    }
}
