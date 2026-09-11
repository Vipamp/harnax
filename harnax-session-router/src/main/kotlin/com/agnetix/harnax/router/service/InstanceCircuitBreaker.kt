package com.agnetix.harnax.router.service

/**
 * Per-instance circuit breaker.
 *
 * Purpose: share a single contract between the local implementation
 * ([com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker])
 * and the Redis implementation ([com.agnetix.harnax.router.service.impl.RedisCircuitBreaker]),
 * with [com.agnetix.harnax.router.config.RouterConfig] picking the implementation
 * based on `router.cache.type`. This keeps breaker state consistent across nodes in multi-node Redis deployments.
 *
 * Scope, and why it is narrow: the breaker describes the *request path* of an instance, which the
 * registry's heartbeat cannot see. It therefore influences where a session is **placed**
 * ([trippedInstances] feeding `rerouteSession(excludeInstanceIds)`) and whether a **failover send**
 * is worth attempting ([allowRequest]). It deliberately does not invalidate an existing binding:
 * a binding is dropped when the registry says the instance is gone or draining. Letting a transient
 * breaker trip re-home every bound session turned one slow agent into a cluster-wide rebinding storm.
 */
interface InstanceCircuitBreaker {

    enum class State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Whether this instance is currently refusing traffic.
     *
     * Pure read: no transition, no side effect. The old implementation flipped OPEN to HALF_OPEN in
     * here and answered `false` for every node at once, so "the window elapsed" degenerated into
     * "everyone floods the broken instance simultaneously".
     */
    fun isOpen(instanceId: String): Boolean

    /** The subset of [instanceIds] that must not receive new placements. */
    fun trippedInstances(instanceIds: Collection<String>): Set<String> = instanceIds.filterTo(mutableSetOf()) { isOpen(it) }

    /**
     * Gate for a request that is about to be sent to an instance which may be OPEN.
     *
     * A CLOSED breaker always allows. An OPEN breaker allows exactly one probe once its open window
     * has elapsed, holding the probe slot for the duration of the attempt, so recovery is tested by
     * a single request instead of by the whole traffic mix. HALF_OPEN allows only its current
     * probe holder's slot to expire before the next probe is granted.
     *
     * @return true when the caller may send.
     */
    fun allowRequest(instanceId: String): Boolean

    /**
     * Count a failure against the instance. Callers must only report failures that say something
     * about the *instance* (connectivity errors, 5xx, 429): a 400 from a bad request body would
     * otherwise open the breaker for an agent-service that is perfectly healthy.
     */
    fun recordFailure(instanceId: String)

    /**
     * A request completed, so the instance's request path works: close the breaker.
     */
    fun recordSuccess(instanceId: String)

    fun reset(instanceId: String)

    fun getState(instanceId: String): State

    fun getFailureCount(instanceId: String): Int
}
