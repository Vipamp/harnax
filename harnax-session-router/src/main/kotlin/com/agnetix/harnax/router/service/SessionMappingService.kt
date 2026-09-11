package com.agnetix.harnax.router.service

interface SessionMappingService {

    /**
     * Bind [sessionId] to [instanceId], replacing any previous binding and keeping the per-instance
     * reverse index consistent.
     *
     * Prefer [rerouteSession] when the target instance still has to be chosen; this method assumes
     * the caller already validated [instanceId].
     */
    fun bindSession(sessionId: String, instanceId: String, agentId: Long? = null)

    fun getInstanceId(sessionId: String): String?

    fun unbindSession(sessionId: String)

    /** Extend the binding's lifetime. Must not resurrect a binding that already expired. */
    fun refreshActiveTime(sessionId: String)

    /**
     * Choose an instance for [sessionId] and make the binding point at it.
     *
     * Also used to place a session that has no binding yet; concurrent callers must not end up
     * with two different owners for the same session.
     *
     * @param excludeInstanceIds instances the caller has just seen fail — the session must not be
     *   placed back onto them.
     */
    fun rerouteSession(sessionId: String, excludeInstanceIds: Set<String> = emptySet()): String

    /**
     * Move every session currently bound to [oldInstanceId] onto [newInstanceId].
     *
     * @return the number of sessions this call actually moved. A session that another router node
     *   already moved elsewhere must not be counted, and must not be dragged back.
     */
    fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int

    /**
     * Drop the bindings of every session still pointing at [instanceId].
     * Sessions that have since been rerouted to another instance are left alone.
     *
     * @return the number of bindings removed.
     */
    fun unbindInstanceSessions(instanceId: String): Int

    fun getSessionCountByInstance(instanceId: String): Int

    fun getSessionCountsByInstances(instanceIds: List<String>): Map<String, Int>
}
