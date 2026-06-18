package com.agnetix.harnax.router.entity

import java.time.LocalDateTime

class ApiCallLog {
    var id: Long = 0
    var callerId: String = ""
    var callerType: String = ""
    var tenantId: Long? = null
    var sessionId: String? = null
    var agentId: Long? = null
    var agentName: String? = null
    var modelId: Long? = null
    var modelName: String? = null
    var endpoint: String = ""
    var method: String = ""
    var requestType: String? = null
    var statusCode: Int = 200
    var success: Int = 1
    var errorMessage: String? = null
    var startTime: LocalDateTime = LocalDateTime.now()
    var endTime: LocalDateTime = LocalDateTime.now()
    var durationMs: Long = 0
    var instanceId: String? = null
    var requestId: String? = null
    var createTime: LocalDateTime = LocalDateTime.now()
}
