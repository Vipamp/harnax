package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.AgentInstance

interface InstanceRegistry {

    fun registerInstance(instanceId: String, host: String, port: Int)

    fun unregisterInstance(instanceId: String)

    /**
     * Record a heartbeat for [instanceId].
     *
     * @return `true` if the instance is still registered; `false` when its registration has
     *   disappeared (TTL expiry, eviction, explicit unregister). Callers must surface `false` to
     *   the agent-service process so it can re-register — otherwise an instance that fell out of
     *   the registry keeps sending heartbeats it believes succeeded and never comes back.
     */
    fun refreshHeartbeat(instanceId: String): Boolean

    /** Instances eligible for *new* session placement: status UP and heartbeat fresh. */
    fun getHealthyInstances(): List<AgentInstance>

    fun getAllActiveInstances(): List<AgentInstance>

    fun getInstance(instanceId: String): AgentInstance?

    /**
     * Transition [instanceId] to DOWN, unless it is already DOWN.
     *
     * @return 1 if this call performed the transition, 0 if the instance was already DOWN or is
     *   unknown. Exactly one caller may get 1 for a given instance, otherwise several router
     *   nodes run session failover for the same instance at the same time.
     */
    fun markInstanceDown(instanceId: String): Int

    /**
     * Stop assigning new sessions to [instanceId] without declaring it DOWN.
     * A DRAINING instance keeps serving its bound sessions and must not be flipped back to UP
     * by a heartbeat.
     *
     * @return `true` if the instance exists and is now draining.
     */
    fun markAsDraining(instanceId: String): Boolean
}
