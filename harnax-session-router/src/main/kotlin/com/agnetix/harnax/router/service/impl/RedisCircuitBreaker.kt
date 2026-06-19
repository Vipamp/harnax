package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import java.util.concurrent.TimeUnit

/**
 * Redis-backed circuit breaker that synchronizes breaker state across nodes.
 *
 * Wired in by [com.agnetix.harnax.router.config.RouterConfig] when `router.cache.type=redis`.
 */
class RedisCircuitBreaker(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30000,
) : InstanceCircuitBreaker {

    private val log = LoggerFactory.getLogger(RedisCircuitBreaker::class.java)

    companion object {
        private const val CIRCUIT_KEY_PREFIX = "router:circuit:"
        private const val STATE_SUFFIX = ":state"
        private const val FAILURES_SUFFIX = ":failures"
        private const val LAST_FAILURE_SUFFIX = ":last_failure"
    }

    override fun isOpen(instanceId: String): Boolean {
        val stateKey = "$CIRCUIT_KEY_PREFIX$instanceId$STATE_SUFFIX"
        val lastFailureKey = "$CIRCUIT_KEY_PREFIX$instanceId$LAST_FAILURE_SUFFIX"

        val state = redisTemplate.opsForValue().get(stateKey) as? String ?: return false
        val lastFailureTime = redisTemplate.opsForValue().get(lastFailureKey) as? Long ?: return false

        return when (state) {
            "OPEN" -> {
                if (System.currentTimeMillis() - lastFailureTime > openDurationMs) {
                    // Transition to HALF_OPEN
                    val transitioned = redisTemplate.opsForValue().setIfAbsent(
                        stateKey,
                        "HALF_OPEN",
                        openDurationMs,
                        TimeUnit.MILLISECONDS,
                    )
                    if (transitioned == true || redisTemplate.opsForValue().get(stateKey) == "HALF_OPEN") {
                        log.info("Circuit breaker HALF_OPEN for instance: $instanceId")
                    }
                    false
                } else {
                    true
                }
            }
            "HALF_OPEN" -> false
            else -> false // CLOSED or null
        }
    }

    override fun recordFailure(instanceId: String) {
        val failuresKey = "$CIRCUIT_KEY_PREFIX$instanceId$FAILURES_SUFFIX"
        val lastFailureKey = "$CIRCUIT_KEY_PREFIX$instanceId$LAST_FAILURE_SUFFIX"
        val stateKey = "$CIRCUIT_KEY_PREFIX$instanceId$STATE_SUFFIX"

        // Set last failure time
        redisTemplate.opsForValue().set(lastFailureKey, System.currentTimeMillis())

        // Increment failure count
        val currentFailures = redisTemplate.opsForValue().increment(failuresKey) ?: 1

        // Check current state
        val currentState = redisTemplate.opsForValue().get(stateKey) as? String ?: "CLOSED"

        when (currentState) {
            "HALF_OPEN" -> {
                // Immediately go back to OPEN
                redisTemplate.opsForValue().set(stateKey, "OPEN", openDurationMs, TimeUnit.MILLISECONDS)
                log.warn("Circuit breaker OPEN for instance: $instanceId (failed in HALF_OPEN)")
            }
            "CLOSED" -> {
                if (currentFailures >= failureThreshold) {
                    redisTemplate.opsForValue().set(stateKey, "OPEN", openDurationMs, TimeUnit.MILLISECONDS)
                    log.warn("Circuit breaker OPEN for instance: $instanceId (failures=$currentFailures)")
                }
            }
            "OPEN" -> {
                // Already open, just update timestamp
                redisTemplate.expire(lastFailureKey, openDurationMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun recordSuccess(instanceId: String) {
        val stateKey = "$CIRCUIT_KEY_PREFIX$instanceId$STATE_SUFFIX"
        val failuresKey = "$CIRCUIT_KEY_PREFIX$instanceId$FAILURES_SUFFIX"

        val previousState = redisTemplate.opsForValue().getAndSet(stateKey, "CLOSED") as? String

        // Clear failure count
        redisTemplate.delete(failuresKey)

        if (previousState == "HALF_OPEN") {
            log.info("Circuit breaker CLOSED for instance: $instanceId (recovered)")
        }
    }

    override fun reset(instanceId: String) {
        val stateKey = "$CIRCUIT_KEY_PREFIX$instanceId$STATE_SUFFIX"
        val failuresKey = "$CIRCUIT_KEY_PREFIX$instanceId$FAILURES_SUFFIX"
        val lastFailureKey = "$CIRCUIT_KEY_PREFIX$instanceId$LAST_FAILURE_SUFFIX"

        redisTemplate.delete(stateKey)
        redisTemplate.delete(failuresKey)
        redisTemplate.delete(lastFailureKey)
    }

    override fun getState(instanceId: String): InstanceCircuitBreaker.State {
        val stateKey = "$CIRCUIT_KEY_PREFIX$instanceId$STATE_SUFFIX"
        val stateStr = redisTemplate.opsForValue().get(stateKey) as? String ?: return InstanceCircuitBreaker.State.CLOSED

        return when (stateStr) {
            "OPEN" -> InstanceCircuitBreaker.State.OPEN
            "HALF_OPEN" -> InstanceCircuitBreaker.State.HALF_OPEN
            else -> InstanceCircuitBreaker.State.CLOSED
        }
    }

    override fun getFailureCount(instanceId: String): Int {
        val failuresKey = "$CIRCUIT_KEY_PREFIX$instanceId$FAILURES_SUFFIX"
        val count = redisTemplate.opsForValue().get(failuresKey) as? Number
        return count?.toInt() ?: 0
    }
}
