package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

/**
 * Redis-backed circuit breaker that synchronizes breaker state across nodes.
 *
 * Wired in by [com.agnetix.harnax.router.config.RouterConfig] when `router.cache.type=redis`.
 *
 * State lives in one hash per instance (`router:circuit:{id}`) whose fields are only ever touched
 * by Lua, so every transition is one atomic round trip and no other node can interleave a
 * read-modify-write. Absent key means CLOSED, which is also why [recordSuccess] just deletes it.
 *
 * Values are read and written as plain numbers because the template serializes String values with
 * quotes (see [com.agnetix.harnax.router.config.RedisConfig]); keeping the fields numeric avoids
 * mixing two encodings in one key.
 */
class RedisCircuitBreaker(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 30000,
    private val probeLeaseMs: Long = openDurationMs,
) : InstanceCircuitBreaker {

    private val log = LoggerFactory.getLogger(RedisCircuitBreaker::class.java)

    private val degrade = ThrottledWarn()

    companion object {
        private const val CIRCUIT_KEY_PREFIX = "router:circuit:"

        private const val STATE_CLOSED = 0L
        private const val STATE_OPEN = 1L
        private const val STATE_HALF_OPEN = 2L

        /**
         * TTL that covers an open window plus the probe slot granted after it.
         * Arg semantics: ARGV[1]=now, ARGV[2]=openDurationMs, ARGV[3]=probeLeaseMs, ARGV[4]=keyTtlMs.
         */
        private val ALLOW_SCRIPT = DefaultRedisScript(
            """
            local state = redis.call('hget', KEYS[1], 'state')
            if state == false then
                return 1
            end
            state = tonumber(state)
            if state == 0 then
                return 1
            end
            local now = tonumber(ARGV[1])
            if state == 1 then
                if now - tonumber(redis.call('hget', KEYS[1], 'openedAt') or '0') < tonumber(ARGV[2]) then
                    return 0
                end
            elseif now < tonumber(redis.call('hget', KEYS[1], 'probeUntil') or '0') then
                return 0
            end
            redis.call('hset', KEYS[1], 'state', '2', 'probeUntil', tostring(now + tonumber(ARGV[3])))
            redis.call('pexpire', KEYS[1], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /** Same ARGV as [ALLOW_SCRIPT]; @return 1 when traffic must be refused. */
        private val IS_TRIPPED_SCRIPT = DefaultRedisScript(
            """
            local state = redis.call('hget', KEYS[1], 'state')
            if state == false then
                return 0
            end
            state = tonumber(state)
            if state == 0 then
                return 0
            end
            local now = tonumber(ARGV[1])
            if state == 1 then
                if now - tonumber(redis.call('hget', KEYS[1], 'openedAt') or '0') < tonumber(ARGV[2]) then
                    return 1
                end
            elseif now < tonumber(redis.call('hget', KEYS[1], 'probeUntil') or '0') then
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=circuit hash.
         * ARGV[1]=now, ARGV[2]=failureThreshold, ARGV[3]=openTtlMs, ARGV[4]=failureWindowTtlMs.
         * @return 1 when the instance ended up OPEN.
         *
         * A failure while OPEN only counts: moving `openedAt` forward would let a steady stream of
         * in-flight failures push the probe window out forever and the instance could never be
         * tested again.
         */
        private val RECORD_FAILURE_SCRIPT = DefaultRedisScript(
            """
            local state = tonumber(redis.call('hget', KEYS[1], 'state') or '0')
            if state == 2 then
                redis.call('hset', KEYS[1], 'state', '1', 'openedAt', ARGV[1], 'probeUntil', '0')
                redis.call('pexpire', KEYS[1], ARGV[3])
                return 1
            end
            local failures = tonumber(redis.call('hget', KEYS[1], 'failures') or '0') + 1
            redis.call('hset', KEYS[1], 'failures', tostring(failures))
            if state == 1 then
                redis.call('pexpire', KEYS[1], ARGV[3])
                return 1
            end
            if failures >= tonumber(ARGV[2]) then
                redis.call('hset', KEYS[1], 'state', '1', 'openedAt', ARGV[1], 'probeUntil', '0')
                redis.call('pexpire', KEYS[1], ARGV[3])
                return 1
            end
            redis.call('pexpire', KEYS[1], ARGV[4])
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * ARGV[1]=now, ARGV[2]=openDurationMs.
         * @return 0=CLOSED, 1=OPEN, 2=HALF_OPEN, with the open window already resolved.
         */
        private val GET_STATE_SCRIPT = DefaultRedisScript(
            """
            local state = tonumber(redis.call('hget', KEYS[1], 'state') or '0')
            if state == 1 then
                local openedAt = tonumber(redis.call('hget', KEYS[1], 'openedAt') or '0')
                if tonumber(ARGV[1]) - openedAt >= tonumber(ARGV[2]) then
                    return 2
                end
            end
            return state
            """.trimIndent(),
            Long::class.java,
        )

        private val GET_FAILURES_SCRIPT = DefaultRedisScript(
            "return tonumber(redis.call('hget', KEYS[1], 'failures') or '0')",
            Long::class.java,
        )
    }

    override fun isOpen(instanceId: String): Boolean = readOrDegrade(instanceId, false) {
        evaluate(IS_TRIPPED_SCRIPT, it, System.currentTimeMillis(), openDurationMs) == 1L
    }

    override fun allowRequest(instanceId: String): Boolean = readOrDegrade(instanceId, true) {
        val granted = evaluate(ALLOW_SCRIPT, it, System.currentTimeMillis(), openDurationMs, probeLeaseMs, openDurationMs + probeLeaseMs) == 1L
        if (!granted) {
            log.debug("Circuit breaker refuses a send to instance: $instanceId")
        }
        granted
    }

    override fun recordFailure(instanceId: String) {
        try {
            val opened = evaluate(
                RECORD_FAILURE_SCRIPT,
                circuitKey(instanceId),
                System.currentTimeMillis(),
                failureThreshold,
                openDurationMs + probeLeaseMs,
                openDurationMs,
            ) == 1L
            if (opened) {
                log.warn("Circuit breaker OPEN for instance: $instanceId (failures=${getFailureCount(instanceId)})")
            }
        } catch (e: Exception) {
            logDegrade(instanceId, e)
        }
    }

    override fun recordSuccess(instanceId: String) {
        try {
            if (redisTemplate.delete(circuitKey(instanceId)) == true) {
                log.info("Circuit breaker CLOSED for instance: $instanceId (recovered)")
            }
        } catch (e: Exception) {
            logDegrade(instanceId, e)
        }
    }

    override fun reset(instanceId: String) {
        try {
            redisTemplate.delete(circuitKey(instanceId))
        } catch (e: Exception) {
            logDegrade(instanceId, e)
        }
    }

    override fun getState(instanceId: String): InstanceCircuitBreaker.State {
        val code = readOrDegrade(instanceId, STATE_CLOSED) {
            evaluate(GET_STATE_SCRIPT, it, System.currentTimeMillis(), openDurationMs) ?: STATE_CLOSED
        }
        return when (code) {
            STATE_OPEN -> InstanceCircuitBreaker.State.OPEN
            STATE_HALF_OPEN -> InstanceCircuitBreaker.State.HALF_OPEN
            else -> InstanceCircuitBreaker.State.CLOSED
        }
    }

    override fun getFailureCount(instanceId: String): Int = readOrDegrade(instanceId, 0L) {
        evaluate(GET_FAILURES_SCRIPT, it) ?: 0L
    }.toInt()

    private fun circuitKey(instanceId: String) = "$CIRCUIT_KEY_PREFIX$instanceId"

    private fun evaluate(
        script: DefaultRedisScript<Long>,
        key: String,
        vararg args: Any,
    ): Long? = redisTemplate.execute(script, listOf(key), *args)

    /**
     * Breaker reads must never fail a request that is otherwise routable, so an unreachable Redis
     * degrades to [fallback] — CLOSED, which keeps routing working and simply loses the cross-node
     * trip.
     */
    private fun <T> readOrDegrade(
        instanceId: String,
        fallback: T,
        read: (String) -> T,
    ): T = try {
        read(circuitKey(instanceId))
    } catch (e: Exception) {
        logDegrade(instanceId, e)
        fallback
    }

    private fun logDegrade(
        instanceId: String,
        e: Exception,
    ) {
        degrade.log(log, "Circuit breaker unavailable, degrading to permissive (last instance: $instanceId)", e)
    }
}
