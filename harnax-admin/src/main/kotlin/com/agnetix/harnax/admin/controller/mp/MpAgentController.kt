package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpAgentDetailResponse
import com.agnetix.harnax.admin.dto.mp.MpAgentResponse
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpAgentService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@Tag(name = "MP Agents", description = "Mobile agent browsing APIs")
@RestController
@RequestMapping("/api/mp/agents")
class MpAgentController(
    private val mpAgentService: MpAgentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    @Operation(summary = "List agents", description = "Get all enabled agents for the current user")
    fun listAgents(): ResultVo<List<MpAgentResponse>> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpAgentService.listAgents(user.id))
    }

    @GetMapping("/{agentId}")
    @Operation(summary = "Agent detail", description = "Get full agent details including MCP and skill info")
    fun getAgentDetail(
        @Parameter(description = "Agent ID") @PathVariable("agentId") agentId: Long,
    ): ResultVo<MpAgentDetailResponse> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpAgentService.getAgentDetail(agentId))
    }
}
