package com.vipamp.vipclaw.admin.controller

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
 * @Description: HealthController
 * @Project: vipclaw
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "健康监测", description = "Health 监测")
class HealthController {

    @GetMapping("/health")
    @Schema(description = "健康检查")
    fun health(): ResultVo<String?> {
        return success<String?>("OK")
    }
}
