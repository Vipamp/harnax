package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.IdempotencyService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import java.util.concurrent.TimeUnit

/**
 * Redis-based distributed idempotency service.
 * Prevents duplicate request processing across multiple router nodes.
 *
 * The marker is a lease, not a record: [release] removes it as soon as the request is over, so the
 * TTL only matters for the node that died mid-request.
 */
class RedisIdempotencyService(
    private val redisTemplate: RedisTemplate<String, Any>,
    @param:Value("\${router.idempotency.ttl-seconds:60}")
    private val ttlSeconds: Long,
) : IdempotencyService {

    private val log = LoggerFactory.getLogger(RedisIdempotencyService::class.java)

    private val degrade = ThrottledWarn()

    companion object {
        private const val IDEMPOTENCY_KEY_PREFIX = "router:idempotency:"
    }

    override fun tryAcquire(requestId: String): Boolean {
        val key = "$IDEMPOTENCY_KEY_PREFIX$requestId"

        return try {
            // SET NX with expiry - atomic operation in Redis
            val acquired = redisTemplate.opsForValue().setIfAbsent(
                key,
                System.currentTimeMillis().toString(),
                ttlSeconds,
                TimeUnit.SECONDS,
            )

            val isFirstRequest = acquired == true

            if (!isFirstRequest) {
                log.debug("Duplicate request detected: $requestId")
            }

            isFirstRequest
        } catch (e: Exception) {
            // Fallback: allow the request (better than rejecting valid requests). This may admit a
            // duplicate during a Redis outage, but it does not stop the service.
            degrade.log(log, "Idempotency store unavailable, admitting requests without a duplicate check (last request: $requestId)", e)
            true
        }
    }

    override fun release(requestId: String) {
        try {
            redisTemplate.delete("$IDEMPOTENCY_KEY_PREFIX$requestId")
        } catch (e: Exception) {
            // The lease expires on its own; a Redis that is down again right after the request must
            // not turn a finished request into an error.
            degrade.log(log, "Idempotency lease for $requestId left to expire on its own", e)
        }
    }
}
