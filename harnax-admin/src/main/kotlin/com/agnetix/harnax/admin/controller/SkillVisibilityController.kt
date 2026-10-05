package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.SkillVisibilityResponse
import com.agnetix.harnax.admin.dto.SkillVisibilityUpdateRequest
import com.agnetix.harnax.admin.service.SkillVisibilityService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Who is allowed to load a skill at runtime.
 *
 * Its own path for the same reason `/api/admin/skill-usage` is: `skills/{id}` types its segment as a
 * `Long`, so a `skills/visibility` route would be matched by it and fail to parse.
 *
 * PUT rather than PATCH because the form posts the whole rule and the service clears whatever the chosen
 * mode does not use; a partial update would let a stale percentage survive a switch back to ALL.
 */
@RestController
@RequestMapping("/api/admin/skill-visibility")
@Tag(name = "Skill Visibility", description = "Rollout control over which users and environments may load a skill")
class SkillVisibilityController(
    private val skillVisibilityService: SkillVisibilityService,
) {

    private val log = LoggerFactory.getLogger(SkillVisibilityController::class.java)

    @GetMapping("/{skillId}")
    @Operation(
        summary = "Get a skill visibility policy",
        description = "The stored policy, or mode ALL when the skill has never had one set",
    )
    fun get(
        @Parameter(description = "Skill ID") @PathVariable(name = "skillId") skillId: Long,
    ): ResultVo<SkillVisibilityResponse> = try {
        ResultVo.success(skillVisibilityService.get(skillId))
    } catch (e: Exception) {
        log.error("Failed to get visibility of skill {}", skillId, e)
        ResultVo.error(ApiErrors.message(e, "Failed to get skill visibility"))
    }

    @PutMapping("/{skillId}")
    @Operation(
        summary = "Set a skill visibility policy",
        description = "Replaces the policy of one skill; fields the chosen mode does not use are cleared",
    )
    fun update(
        @Parameter(description = "Skill ID") @PathVariable(name = "skillId") skillId: Long,
        @Valid @RequestBody request: SkillVisibilityUpdateRequest,
    ): ResultVo<SkillVisibilityResponse> = try {
        ResultVo.success(skillVisibilityService.update(skillId, request))
    } catch (e: Exception) {
        log.error("Failed to set visibility of skill {}", skillId, e)
        ResultVo.error(ApiErrors.message(e, "Failed to set skill visibility"))
    }
}
