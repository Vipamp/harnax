package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.SkillUsageSummaryResponse
import com.agnetix.harnax.admin.service.SkillUsageService
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
 * Skill usage analytics, read-only.
 *
 * Mounted as its own path rather than under `/api/admin/skills/{id}`, whose `{id}` is typed as a
 * `Long`: a `skills/usage` segment would be matched by that route and fail to parse.
 */
@RestController
@RequestMapping("/api/admin/skill-usage")
@Tag(name = "Skill Usage", description = "How much the skills of this tenant actually get loaded and used")
class SkillUsageController(
    private val skillUsageService: SkillUsageService,
) {

    private val log = LoggerFactory.getLogger(SkillUsageController::class.java)

    @GetMapping("/summary")
    @Operation(
        summary = "Get skill usage summary",
        description = "Per-skill load and use counts for the current tenant inside the requested window, skills with no events included",
    )
    fun summary(
        @Parameter(description = "Window in days", example = "30") @RequestParam(name = "days", defaultValue = "30") days: Int?,
    ): ResultVo<SkillUsageSummaryResponse> = try {
        ResultVo.success(skillUsageService.summary(days ?: DEFAULT_WINDOW_DAYS))
    } catch (e: Exception) {
        log.error("Failed to get skill usage summary", e)
        ResultVo.error(ApiErrors.message(e, "Failed to get skill usage summary"))
    }

    companion object {
        private const val DEFAULT_WINDOW_DAYS = 30
    }
}
