package com.agnetix.harnax.router.service

/**
 * Session mapping interface.
 * Manages the mapping between sessions and agent-service instances.
 */
interface SessionMappingService {

    /**
     * Bind a session to a specific instance.
     * If the session already has a binding, it will be updated.
     * @param sessionId Session identifier
     * @param instanceId Agent-service instance identifier
     * @param agentId Associated agent ID (optional)
     */
    fun bindSession(sessionId: String, instanceId: String, agentId: Long? = null)

    /**
     * Get the instance ID bound to a session.
     * @param sessionId Session identifier
     * @return Instance ID or null if no binding exists
     */
    fun getInstanceId(sessionId: String): String?

    /**
     * Unbind a session from its instance.
     * @param sessionId Session identifier
     */
    fun unbindSession(sessionId: String)

    /**
     * Refresh the last active time for a session.
     * @param sessionId Session identifier
     */
    fun refreshActiveTime(sessionId: String)

    /**
     * Reroute a session to a new instance (for failover).
     * @param sessionId Session identifier
     * @return New instance ID
     */
    fun rerouteSession(sessionId: String): String

    /**
     * Rebind all sessions from a down instance to a new instance.
     * @param oldInstanceId Down instance ID
     * @param newInstanceId New instance ID
     * @return Number of sessions rebinding
     */
    fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int

    /**
     * Unbind all sessions bound to a specific instance.
     * Used during instance unregistration.
     * @param instanceId Instance identifier
     * @return Number of sessions unbound
     */
    fun unbindInstanceSessions(instanceId: String): Int
}
