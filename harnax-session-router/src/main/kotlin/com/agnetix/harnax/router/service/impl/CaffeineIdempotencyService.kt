package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.IdempotencyService
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class CaffeineIdempotencyService : IdempotencyService {

    private val processedRequests = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<String, Boolean>()

    override fun tryAcquire(requestId: String): Boolean {
        if (processedRequests.getIfPresent(requestId) != null) return false
        processedRequests.put(requestId, true)
        return true
    }
}
