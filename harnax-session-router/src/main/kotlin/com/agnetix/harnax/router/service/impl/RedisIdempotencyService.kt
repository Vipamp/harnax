package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.IdempotencyService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import java.util.concurrent.TimeUnit

/**
 * Redis-based distributed idempotency service.
 * Prevents duplicate request processing across multiple router nodes.
 */
class RedisIdempotencyService(
    private val redisTemplate: RedisTemplate<String, Any>,
    @Value("${router.idempotency.ttl-seconds:60}")
    private val ttlSeconds: Long,
) : IdempotencyService {

    private val log = LoggerFactory.getLogger(RedisIdempotencyService::class.java)

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
            log.error("Redis error in idempotency check for $requestId, allowing request: ${e.message}")
            // Fallback: allow request to proceed (better than rejecting valid requests)
            // This may allow duplicates during Redis outage, but prevents service disruption
            true
        }
    }
}
