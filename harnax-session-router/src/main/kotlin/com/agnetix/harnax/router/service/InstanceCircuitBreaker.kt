package com.agnetix.harnax.router.service

/**
 * Circuit breaker interface.
 *
 * Purpose: share a single contract between the local implementation
 * ([com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker])
 * and the Redis implementation ([com.agnetix.harnax.router.service.impl.RedisCircuitBreaker]),
 * with [com.agnetix.harnax.router.config.RouterConfig] picking the implementation
 * based on `router.cache.type`. This keeps breaker state consistent across nodes in multi-node Redis deployments.
 */
interface InstanceCircuitBreaker {

    enum class State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Whether the breaker for this instance is currently OPEN.
     * Side effect: in OPEN state, if `openDurationMs` has elapsed, the breaker auto-transitions to HALF_OPEN.
     */
    fun isOpen(instanceId: String): Boolean

    fun recordFailure(instanceId: String)

    fun recordSuccess(instanceId: String)

    fun reset(instanceId: String)

    fun getState(instanceId: String): State

    fun getFailureCount(instanceId: String): Int
}
