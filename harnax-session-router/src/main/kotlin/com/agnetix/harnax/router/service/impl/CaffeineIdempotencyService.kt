package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.IdempotencyService
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Single-node counterpart of [RedisIdempotencyService]: it can only refuse a duplicate that reaches
 * this node, which is all local mode ever has.
 */
class CaffeineIdempotencyService(
    private val ttlSeconds: Long = 60,
) : IdempotencyService {

    private val log = LoggerFactory.getLogger(CaffeineIdempotencyService::class.java)

    private val processedRequests: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
        .build()

    override fun tryAcquire(requestId: String): Boolean {
        val now = System.currentTimeMillis()
        // Use atomic putIfAbsent to avoid race condition
        val previousValue = processedRequests.asMap().putIfAbsent(requestId, now)
        val isFirstRequest = previousValue == null

        if (!isFirstRequest) {
            log.debug("Duplicate request detected: $requestId (original timestamp: $previousValue)")
        }

        return isFirstRequest
    }

    override fun release(requestId: String) {
        processedRequests.invalidate(requestId)
    }
}
