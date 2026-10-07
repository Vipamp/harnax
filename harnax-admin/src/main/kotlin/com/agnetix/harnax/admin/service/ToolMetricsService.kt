package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolInvocationRow
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse

/**
 * Read-only views over the call metrics.
 *
 * No method names a tenant: the implementation resolves it from the caller's own credentials, so a request
 * cannot carry one. `days` arrives raw because the clamp and its log line belong here (the same shape as
 * `SkillUsageServiceImpl.summary`), and the value that comes back in the response is the clamped one.
 */
interface ToolMetricsService {

    fun getSummary(
        days: Int,
        kind: String?,
        groupBy: String,
    ): ToolMetricsSummaryResponse

    fun getTimeSeries(
        days: Int,
        kind: String?,
        subjectId: Long?,
        granularity: String,
    ): ToolMetricsTimeSeriesResponse

    fun getInvocations(query: InvocationQuery): Page<ToolInvocationRow>
}

/**
 * The detail page's filters. Nine request values, which is why they travel as an object rather than as
 * parameters (the repo's shape for more than four).
 */
data class InvocationQuery(
    val days: Int = 30,
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
