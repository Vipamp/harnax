package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.common.SystemInfo
import com.agnetix.harnax.admin.config.EditionUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.dto.ResultVo.Companion.success
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * @Description: Health check controller
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
