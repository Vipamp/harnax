package com.agnetix.harnax.router.service

interface IdempotencyService {
    fun tryAcquire(requestId: String): Boolean
}
