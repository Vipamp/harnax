package com.agnetix.harnax.router.dto

/**
 * Filter parameters for the call log monitor endpoint.
 *
 * All string fields use null/blank to skip that filter.
 * Pagination is offset/limit based.
 */
data class ApiCallLogQuery(
    val sessionId: String? = null,
    val instanceId: String? = null,
    val agentName: String? = null,
    val statusCode: Int? = null,
    /**
     * Filter on the `success` flag: 1 = successful only, 0 = failed only.
     * Null means "both".
     */
    val success: Int? = null,
    val minDurationMs: Long? = null,
    val limit: Int = 100,
    val offset: Int = 0,
) {
    init {
        require(limit in 1..1000) { "limit must be between 1 and 1000, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
    }
}

/**
 * Paginated response for call log queries.
 */
data class ApiCallLogPage(
    val items: List<com.agnetix.harnax.router.entity.ApiCallLog>,
    val total: Long,
    val limit: Int,
    val offset: Int,
)
