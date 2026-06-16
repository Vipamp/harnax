package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.entity.AgentInstance

interface InstanceRegistry {

    fun registerInstance(instanceId: String, host: String, port: Int)

    fun unregisterInstance(instanceId: String)

    fun refreshHeartbeat(instanceId: String)

    fun getHealthyInstances(): List<AgentInstance>

    fun getAllActiveInstances(): List<AgentInstance>

    fun getInstance(instanceId: String): AgentInstance?

    fun markInstanceDown(instanceId: String): Int

    fun markAsDraining(instanceId: String)
}
