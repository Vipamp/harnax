package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.RateLimitChecker
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Component
class RateLimiter : RateLimitChecker {

    private val log = LoggerFactory.getLogger(RateLimiter::class.java)

    private val windows = ConcurrentHashMap<String, SlidingWindow>()

    override fun tryAcquire(key: String, limitPerMinute: Int): Boolean {
        val window = windows.computeIfAbsent(key) { SlidingWindow() }
        return window.tryAcquire(limitPerMinute)
    }

    fun getCurrentCount(key: String): Int {
        val window = windows[key] ?: return 0
        window.evict()
        return window.timestamps.size
    }

    @Scheduled(fixedDelay = 60_000)
    fun cleanup() {
        val now = System.currentTimeMillis()
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.evict()
            if (entry.value.timestamps.isEmpty()) {
                iterator.remove()
            }
        }
        log.debug("RateLimiter cleanup: {} active keys remaining", windows.size)
    }

    private class SlidingWindow {
        val timestamps = ConcurrentLinkedDeque<Long>()

        fun tryAcquire(limit: Int): Boolean {
            evict()
            if (timestamps.size >= limit) {
                return false
            }
            timestamps.addLast(System.currentTimeMillis())
            return true
        }

        fun evict() {
            val cutoff = System.currentTimeMillis() - WINDOW_MS
            while (timestamps.peekFirst()?.let { it < cutoff } == true) {
                timestamps.pollFirst()
            }
        }

        companion object {
            private const val WINDOW_MS = 60_000L
        }
    }
}
