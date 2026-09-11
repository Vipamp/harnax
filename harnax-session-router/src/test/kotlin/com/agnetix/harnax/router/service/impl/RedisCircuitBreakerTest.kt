package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.config.RedisConfig
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

/**
 * The breaker's state machine lives in Lua and is specified against a real Redis by
 * [com.agnetix.harnax.router.integration.RedisCircuitBreakerIntegrationTest]. What belongs here is
 * the one behaviour that test cannot show: routing calls these methods on every request, so an
 * unreachable Redis must degrade the breaker to permissive instead of failing the request it was
 * meant to protect.
 *
 * A dead port is used rather than a mock: the point is that whatever Redis throws, the breaker
 * swallows it.
 */
class RedisCircuitBreakerTest {

    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var breaker: InstanceCircuitBreaker

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", DEAD_PORT)).apply {
            afterPropertiesSet()
        }
        breaker = RedisCircuitBreaker(RedisConfig().redisTemplate(connectionFactory), failureThreshold = 3, openDurationMs = 30000)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `an unreadable circuit reads as closed instead of failing the request`() {
        assertFalse(breaker.isOpen(INSTANCE_ID))
        assertTrue(breaker.allowRequest(INSTANCE_ID))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState(INSTANCE_ID))
        assertEquals(0, breaker.getFailureCount(INSTANCE_ID))
    }

    @Test
    fun `a Redis outage does not report every instance as tripped`() {
        val tripped = breaker.trippedInstances(listOf(INSTANCE_ID, "inst-2", "inst-3"))

        assertTrue(tripped.isEmpty(), "a breaker that cannot read must not exclude healthy instances: $tripped")
    }

    @Test
    fun `writes to an unreachable Redis do not surface to the caller`() {
        breaker.recordFailure(INSTANCE_ID)
        breaker.recordSuccess(INSTANCE_ID)
        breaker.reset(INSTANCE_ID)
    }

    private companion object {
        const val DEAD_PORT = 1
        const val INSTANCE_ID = "inst-1"
    }
}
