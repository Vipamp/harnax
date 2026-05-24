package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.AgentInstance

/**
 * Instance registry interface.
 * Manages agent-service instance registration and health tracking.
 */
interface InstanceRegistry {

    /**
     * Register a new agent-service instance.
     * @param instanceId Unique instance identifier
     * @param host Instance host address
     * @param port Instance port
     */
    fun registerInstance(instanceId: String, host: String, port: Int)

    /**
     * Unregister an agent-service instance.
     * @param instanceId Instance identifier to unregister
     */
    fun unregisterInstance(instanceId: String)

    /**
     * Refresh heartbeat for an instance.
     * @param instanceId Instance identifier
     */
    fun refreshHeartbeat(instanceId: String)

    /**
     * Get all healthy instances.
     * @return List of healthy instances
     */
    fun getHealthyInstances(): List<AgentInstance>

    /**
     * Get all active instances (regardless of health status).
     * Used by health checker to detect heartbeat timeouts.
     * @return List of all active instances
     */
    fun getAllActiveInstances(): List<AgentInstance>

    /**
     * Get an instance by its identifier.
     * @param instanceId Instance identifier
     * @return Instance info or null
     */
    fun getInstance(instanceId: String): AgentInstance?

    /**
     * Mark an instance as down.
     * @param instanceId Instance identifier
     */
    fun markInstanceDown(instanceId: String)
}
