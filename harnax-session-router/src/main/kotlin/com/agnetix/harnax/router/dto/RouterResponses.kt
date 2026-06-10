package com.agnetix.harnax.router.dto

/**
 * Response for instance management operations (register/heartbeat/unregister).
 */
data class InstanceOperationResponse(
    val status: String,
    val instanceId: String,
)

/**
 * DTO representing a registered agent-service instance.
 */
data class InstanceInfo(
    val instanceId: String,
    val host: String,
    val port: Int,
    val status: String,
    val lastHeartbeat: String,
)

/**
 * Response for the router health check endpoint.
 */
data class RouterHealthResponse(
    val status: String,
    val healthyInstances: Int,
)
