package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.DashboardOverviewResponse
import com.agnetix.harnax.admin.service.DashboardService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.TenantResolver
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * Landing-page overview: one read-only aggregation for the workspace the request stands in.
 *
 * Which tenant is never a query parameter, and neither is a window or a day count. A client that could
 * name a tenant in the URL could name any other tenant, and the page is meant to be configured by
 * nothing — the deep, filterable analysis lives in the token monitor, and this is one screen that needs
 * no settings to read.
 *
 * [TenantResolver] answers from the request's own credentials, the same chain every other admin read and
 * write goes through, so this endpoint cannot see further than the caller's own workspace.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@Tag(name = "Dashboard", description = "Tenant-scoped overview metrics for the landing page")
class DashboardController(
    private val dashboardService: DashboardService,
    private val jwtUtil: JwtUtil,
) {

    private val log = LoggerFactory.getLogger(DashboardController::class.java)

    @GetMapping("/overview")
    @Operation(
        summary = "Get the overview metrics for the caller's tenant",
        description = "Today and yesterday's matching span, the 14-day trend, both top-5 rankings and the asset stock-take",
    )
    fun overview(): ResultVo<DashboardOverviewResponse> = try {
        // One clock read for the whole page: the today window, its yesterday counterpart and the
        // 14-day trend must all hang off the same instant, or "up to this hour yesterday" drifts.
        ResultVo.success(dashboardService.overview(TenantResolver.resolve(jwtUtil), LocalDateTime.now()))
    } catch (e: Exception) {
        // All or nothing: a per-block fallback would need six states and six retries to be worth
        // naming, and a half-rendered overview is a page whose numbers nobody can trust.
        log.error("Failed to build dashboard overview", e)
        ResultVo.error("Failed to get overview data")
    }
}
