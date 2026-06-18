package com.agnetix.harnax.auth

interface RateLimitChecker {
    fun tryAcquire(key: String, limitPerMinute: Int): Boolean
}
