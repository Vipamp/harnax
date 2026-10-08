package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.ToolInvocationRow
import com.agnetix.harnax.admin.dto.ToolMetricsSummaryResponse
import com.agnetix.harnax.admin.dto.ToolMetricsTimeSeriesResponse
import com.agnetix.harnax.admin.service.InvocationQuery
import com.agnetix.harnax.admin.service.ToolMetricsService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Call metrics for tools, MCP servers and CLI packages (design section 7).
 *
 * Which tenant is being read is resolved inside the service from the caller's own credentials, and there is
 * deliberately no `tenantId` parameter here: a client that could name one in a URL could name any other one.
 */
@RestController
@RequestMapping("/api/admin/tool-metrics")
@Tag(name = "Tool Call Metrics", description = "Tool, MCP and CLI invocation metrics")
class ToolMetricsController(
    private val toolMetricsService: ToolMetricsService,
) {

    private val log = LoggerFactory.getLogger(ToolMetricsController::class.java)

    @GetMapping("/summary")
    @Operation(
        summary = "Call counts per subject",
        description = "Totals over the window; the tool view reads the daily aggregate, agent and session read the detail table",
    )
    fun getSummary(
        @Parameter(description = "First day, yyyy-MM-dd (inclusive); defaults to 30 days before `end`")
        @RequestParam(name = "start", required = false) start: String?,
        @Parameter(description = "Last day, yyyy-MM-dd (inclusive); defaults to today")
        @RequestParam(name = "end", required = false) end: String?,
        @Parameter(description = "Origin filter: builtin / mcp / cli / shell / framework")
        @RequestParam(name = "kind", required = false) kind: String?,
        @Parameter(description = "Subject dimension: tool (default) / agent / session")
        @RequestParam(name = "groupBy", required = false, defaultValue = "tool") groupBy: String,
    ): ResultVo<ToolMetricsSummaryResponse> = try {
        ResultVo.success(toolMetricsService.getSummary(start, end, kind, groupBy))
    } catch (e: Exception) {
        log.error("Failed to get tool metrics summary", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call metrics"))
    }

    @GetMapping("/time-series")
    @Operation(
        summary = "Call counts per time bucket",
        description = "Zero-filled buckets so a quiet day does not make the trend line skip",
    )
    fun getTimeSeries(
        @Parameter(description = "First day, yyyy-MM-dd (inclusive); defaults to 30 days before `end`")
        @RequestParam(name = "start", required = false) start: String?,
        @Parameter(description = "Last day, yyyy-MM-dd (inclusive); defaults to today")
        @RequestParam(name = "end", required = false) end: String?,
        @Parameter(description = "Origin filter: builtin / mcp / cli / shell / framework")
        @RequestParam(name = "kind", required = false) kind: String?,
        @Parameter(description = "MCP server or CLI package id")
        @RequestParam(name = "subjectId", required = false) subjectId: Long?,
        @Parameter(description = "Bucket size: day (default) / week / month")
        @RequestParam(name = "granularity", required = false, defaultValue = "day") granularity: String,
    ): ResultVo<ToolMetricsTimeSeriesResponse> = try {
        ResultVo.success(toolMetricsService.getTimeSeries(start, end, kind, subjectId, granularity))
    } catch (e: Exception) {
        log.error("Failed to get tool metrics time series", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call trend"))
    }

    @GetMapping("/invocations")
    @Operation(
        summary = "Detail page of single calls",
        description = "Real durations and failure reasons, inside the retention window only",
    )
    fun getInvocations(
        @Parameter(description = "First day, yyyy-MM-dd (inclusive); defaults to 30 days before `end`")
        @RequestParam(name = "start", required = false) start: String?,
        @Parameter(description = "Last day, yyyy-MM-dd (inclusive); defaults to today")
        @RequestParam(name = "end", required = false) end: String?,
        @Parameter(description = "Origin filter: builtin / mcp / cli / shell / framework")
        @RequestParam(name = "kind", required = false) kind: String?,
        @Parameter(description = "Tool name as the model sees it")
        @RequestParam(name = "toolName", required = false) toolName: String?,
        @Parameter(description = "MCP server row")
        @RequestParam(name = "mcpId", required = false) mcpId: Long?,
        @Parameter(description = "CLI package row")
        @RequestParam(name = "cliId", required = false) cliId: Long?,
        @Parameter(description = "Agent row")
        @RequestParam(name = "agentId", required = false) agentId: Long?,
        @Parameter(description = "Session id")
        @RequestParam(name = "sessionId", required = false) sessionId: String?,
        @Parameter(description = "Terminal state: SUCCESS / ERROR / DENIED / INTERRUPTED")
        @RequestParam(name = "outcome", required = false) outcome: String?,
        @Parameter(description = "1-based page number")
        @RequestParam(name = "pageNum", required = false, defaultValue = "1") pageNum: Int,
        @Parameter(description = "Rows per page, 1..200")
        @RequestParam(name = "pageSize", required = false, defaultValue = "20") pageSize: Int,
    ): ResultVo<Page<ToolInvocationRow>> = try {
        ResultVo.success(
            toolMetricsService.getInvocations(
                InvocationQuery(
                    start = start,
                    end = end,
                    kind = kind,
                    toolName = toolName,
                    mcpId = mcpId,
                    cliId = cliId,
                    agentId = agentId,
                    sessionId = sessionId,
                    outcome = outcome,
                    pageNum = pageNum,
                    pageSize = pageSize,
                ),
            ),
        )
    } catch (e: Exception) {
        log.error("Failed to get tool invocation page", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get call details"))
    }
}
