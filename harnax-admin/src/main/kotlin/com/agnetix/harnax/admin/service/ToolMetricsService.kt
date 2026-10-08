package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolInvocationRow
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse

/**
 * Read-only views over the call metrics.
 *
 * No method names a tenant: the implementation resolves it from the caller's own credentials, so a request
 * cannot carry one. `start` and `end` arrive raw because the clamps and their log lines belong to the
 * implementation, and what comes back in the response is the range it actually answered. Both are inclusive
 * `yyyy-MM-dd` days; either may be absent, and a value that does not parse counts as absent.
 */
interface ToolMetricsService {

    fun getSummary(
        start: String?,
        end: String?,
        kind: String?,
        groupBy: String,
    ): ToolMetricsSummaryResponse

    fun getTimeSeries(
        start: String?,
        end: String?,
        kind: String?,
        subjectId: Long?,
        granularity: String,
    ): ToolMetricsTimeSeriesResponse

    fun getInvocations(query: InvocationQuery): Page<ToolInvocationRow>
}

/**
 * The detail page's filters. Eleven request values, which is why they travel as an object rather than as
 * parameters (the repo's shape for more than four).
 */
data class InvocationQuery(
    val start: String? = null,
    val end: String? = null,
    val kind: String? = null,
    val toolName: String? = null,
    val mcpId: Long? = null,
    val cliId: Long? = null,
    val agentId: Long? = null,
    val sessionId: String? = null,
    val outcome: String? = null,
    val pageNum: Int = 1,
    val pageSize: Int = 20,
)
