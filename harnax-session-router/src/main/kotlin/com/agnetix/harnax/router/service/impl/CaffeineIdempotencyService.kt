package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.IdempotencyService
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class CaffeineIdempotencyService : IdempotencyService {

    private val log = LoggerFactory.getLogger(CaffeineIdempotencyService::class.java)

    private val processedRequests = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<String, Long>()

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
}
