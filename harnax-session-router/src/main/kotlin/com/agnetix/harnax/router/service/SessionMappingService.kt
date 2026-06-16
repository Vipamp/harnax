package com.agnetix.harnax.router.service

interface SessionMappingService {

    fun bindSession(sessionId: String, instanceId: String, agentId: Long? = null)

    fun getInstanceId(sessionId: String): String?

    fun unbindSession(sessionId: String)

    fun refreshActiveTime(sessionId: String)

    fun rerouteSession(sessionId: String): String

    fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int

    fun unbindInstanceSessions(instanceId: String): Int
}
