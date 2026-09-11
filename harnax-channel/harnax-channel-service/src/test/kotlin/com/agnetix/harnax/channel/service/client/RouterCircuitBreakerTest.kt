package com.agnetix.harnax.channel.service.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * [RouterCircuitBreaker] 的测试。这里注入时钟，因为熔断器真正微妙的地方是它「什么时候」停止
 * 快速失败：依赖墙上时间会让断言不稳定，而 open/half-open 转换写错，正是把一次五分钟的 router
 * 故障变成永久熔断的那类 bug。
 */
class RouterCircuitBreakerTest {

    private var now = 1_000_000L
    private lateinit var breaker: RouterCircuitBreaker

    private fun openDuration(): Duration = Duration.ofSeconds(30)

    @BeforeEach
    fun setUp() {
        breaker = RouterCircuitBreaker(
            failureThreshold = 3,
            openDuration = openDuration(),
            clock = { now },
        )
    }

    private fun fail(times: Int) {
        repeat(times) { breaker.onFailure("router timeout") }
    }

    /** 连续三次失败，把熔断器推进 OPEN 状态。 */
    private fun openCircuit() {
        fail(3)
        assertFalse(breaker.tryAcquire())
    }

    @Nested
    inner class Closed {
        @Test
        fun `a fresh circuit lets calls through`() {
            assertTrue(breaker.tryAcquire())
            assertEquals("CLOSED", breaker.snapshot()["state"])
        }

        @Test
        fun `one failure short of the threshold still lets calls through`() {
            fail(2)

            assertTrue(breaker.tryAcquire())
            assertEquals("CLOSED", breaker.snapshot()["state"])
            assertEquals(2, breaker.snapshot()["consecutiveFailures"])
        }

        @Test
        fun `the configured number of consecutive failures opens the circuit`() {
            fail(3)

            assertFalse(breaker.tryAcquire())
            assertEquals("OPEN", breaker.snapshot()["state"])
        }

        @Test
        fun `a success clears the consecutive counter`() {
            fail(2)
            breaker.onSuccess()
            fail(2)

            assertTrue(breaker.tryAcquire())
            assertEquals("CLOSED", breaker.snapshot()["state"])
            assertEquals(2, breaker.snapshot()["consecutiveFailures"])
        }
    }

    @Nested
    inner class Open {
        @Test
        fun `calls fail fast for the whole open window`() {
            openCircuit()

            now += openDuration().toMillis() - 1
            assertFalse(breaker.tryAcquire())
        }

        @Test
        fun `a single probe is allowed once the open window has passed`() {
            openCircuit()

            now += openDuration().toMillis()
            assertTrue(breaker.tryAcquire(), "the circuit should have moved to HALF_OPEN")
            assertEquals("HALF_OPEN", breaker.snapshot()["state"])
        }

        @Test
        fun `only the first caller gets the probe`() {
            openCircuit()
            now += openDuration().toMillis()
            assertTrue(breaker.tryAcquire())

            assertFalse(breaker.tryAcquire(), "a second probe would defeat the point of the circuit")
        }

        @Test
        fun `a successful probe closes the circuit and resets the counters`() {
            openCircuit()
            now += openDuration().toMillis()
            breaker.tryAcquire()

            breaker.onSuccess()

            assertEquals("CLOSED", breaker.snapshot()["state"])
            assertEquals(0, breaker.snapshot()["consecutiveFailures"])
            assertEquals(0L, breaker.snapshot()["openedAt"])
            assertTrue(breaker.tryAcquire())
        }

        @Test
        fun `a failed probe reopens the circuit and restarts the whole window`() {
            openCircuit()
            now += openDuration().toMillis()
            breaker.tryAcquire()

            breaker.onFailure("still down")

            assertEquals("OPEN", breaker.snapshot()["state"])
            assertFalse(breaker.tryAcquire())
            now += openDuration().toMillis() - 1
            assertFalse(breaker.tryAcquire())
            now += 1
            assertTrue(breaker.tryAcquire())
        }

        @Test
        fun `a probe that never reports is re-armed after twice the open window`() {
            openCircuit()
            now += openDuration().toMillis()
            assertTrue(breaker.tryAcquire())

            now += openDuration().toMillis() / 2
            assertFalse(breaker.tryAcquire())

            // 发放探测调用时并没有刷新 openedAt，所以丢失探测的判定是相对最初那次熔断的。
            now += openDuration().toMillis()
            assertTrue(breaker.tryAcquire())
        }
    }

    @Nested
    inner class Disabled {
        @Test
        fun `a disabled breaker never rejects and never opens`() {
            val disabled = RouterCircuitBreaker(
                enabled = false,
                failureThreshold = 1,
                openDuration = openDuration(),
                clock = { now },
            )

            failOn(disabled, 10)

            assertTrue(disabled.tryAcquire())
            assertEquals("CLOSED", disabled.snapshot()["state"])
            assertEquals(false, disabled.snapshot()["enabled"])
        }

        private fun failOn(
            target: RouterCircuitBreaker,
            times: Int,
        ) {
            repeat(times) { target.onFailure("whatever") }
        }
    }

    @Nested
    inner class Snapshot {
        @Test
        fun `the snapshot carries the tuning knobs an operator needs`() {
            val snapshot = breaker.snapshot()

            assertEquals(3, snapshot["failureThreshold"])
            assertEquals(openDuration().toMillis(), snapshot["openDurationMs"])
            assertEquals(true, snapshot["enabled"])
        }
    }
}
