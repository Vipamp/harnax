package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.common.SystemInfo
import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.ResultVo.Companion.success
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * @Author: heqingsong
 * @Date: 2026/4/19
 * @Description: Health check controller
 * @Project: vipclaw
 */
@RestController
@RequestMapping("/api/")
@Tag(name = "Health Check", description = "Health monitoring")
class HealthController(private val editionUtil: EditionUtil) {

    @GetMapping("/health")
    @Schema(description = "Health check")
    fun health(): ResultVo<String?> = success("OK")

    @GetMapping("/info")
    @Schema(description = "Get version information")
    fun version(): ResultVo<SystemInfo> = success(SystemInfo.from(editionUtil))
}
