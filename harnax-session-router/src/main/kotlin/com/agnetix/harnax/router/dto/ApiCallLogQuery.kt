package com.agnetix.harnax.router.dto

/**
 * Filter parameters for the call log monitor endpoint.
 *
 * All string fields use null/blank to skip that filter.
 * Pagination is offset/limit based.
 *
 * One field here is not a filter the caller chose: [tenantId] is the caller's *scope*, derived
 * server-side from the authenticated identity. See its KDoc for why binding it from a request
 * parameter would put the whole table back on the table.
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
    /**
     * Rows to confine the caller to, or null for no confinement.
     *
     * Set by the server from [com.agnetix.harnax.auth.AuthContext.tenantId], never from the request: a
     * caller that could name a tenant would just name the one it wanted to read. Null means an internal
     * service token or a SYSTEM key, which is the same "no tenant means internal" pass-through
     * [com.agnetix.harnax.router.service.SessionAccessGuard] runs on and what the router's own operator
     * UI relies on to see the cluster at all.
     */
    val tenantId: Long? = null,
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
