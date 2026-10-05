package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SkillDraftApproveRequest
import com.agnetix.harnax.admin.dto.SkillDraftDecisionResponse
import com.agnetix.harnax.admin.dto.SkillDraftDetailResponse
import com.agnetix.harnax.admin.dto.SkillDraftRejectRequest
import com.agnetix.harnax.admin.dto.SkillDraftResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SkillDraftService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.SkillDraft
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The reviewer's half of the agent-proposal queue: the list, one draft, and the two decisions.
 *
 * It sits on its own path rather than under `skills/{id}` for the same reason the draft table is its own
 * table: a proposal is not a skill yet, and every read here answers about a row that may never become one.
 *
 * Two refusal shapes, split on what the caller can do about it. A request nobody can act on is an error
 * envelope — an unknown draft, a status filter that names nothing, an approval with no digest. A refusal the
 * screen has to respond to comes back inside a successful envelope as an outcome carrying the information the
 * next attempt needs, see [SkillDraftDecisionResponse]. The one exception is the race where another publisher
 * wins the name between the conflict probe and the write: the whole approval rolls back, and it answers 409
 * with the schema-level detail stripped, because the draft is still PENDING and re-reading it is the fix.
 */
@RestController
@RequestMapping("/api/admin/skill-drafts")
@Tag(name = "Skill Draft Review", description = "Review queue for skills proposed by agents")
class SkillDraftController(
    private val skillDraftService: SkillDraftService,
) {

    private val log = LoggerFactory.getLogger(SkillDraftController::class.java)

    @GetMapping
    @Operation(
        summary = "List draft proposals",
        description = "The caller's tenant, newest touched first; defaults to everything still awaiting a decision",
    )
    fun page(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "20") @RequestParam(
            name = "pageSize",
            defaultValue = "20",
        ) pageSize: Int?,
        @Parameter(description = "PENDING / APPROVED / REJECTED; PENDING when omitted") @RequestParam(
            name = "status",
            required = false,
        ) status: String?,
        @Parameter(description = "Skill name, partial match") @RequestParam(name = "name", required = false) name: String?,
    ): ResultVo<Page<SkillDraftResponse>> = try {
        // The open queue is the only thing a reviewer has a reason to ask for without naming a status, so
        // the default is the useful list rather than a page of decisions already made.
        ResultVo.success(
            skillDraftService.page(
                status = status ?: SkillDraft.STATUS_PENDING,
                name = name,
                pageNum = pageNum ?: 1,
                pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
            ),
        )
    } catch (e: BizException) {
        log.warn("Draft queue refused: {}", e.message)
        ResultVo.error(e.code, e.message ?: "Failed to list skill drafts")
    } catch (e: Exception) {
        log.error("Failed to list skill drafts", e)
        ResultVo.error(ApiErrors.message(e, "Failed to list skill drafts"))
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Read one draft for review",
        description = "Full body, support files, per-script hashes, both scans, and the digest an approval must send back",
    )
    fun detail(
        @Parameter(description = "Draft ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SkillDraftDetailResponse> = try {
        ResultVo.success(skillDraftService.detail(id))
    } catch (e: BizException) {
        log.warn("Draft $id could not be reviewed: {}", e.message)
        ResultVo.error(e.code, e.message ?: "Failed to read skill draft")
    } catch (e: Exception) {
        log.error("Failed to read skill draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to read skill draft"))
    }

    @PostMapping("/{id}/approve")
    @Operation(
        summary = "Approve a draft and promote it into the skill table",
        description = "Promotion always rescans the content; a scan hit stores the skill disabled rather than refusing it",
    )
    fun approve(
        @Parameter(description = "Draft ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillDraftApproveRequest,
    ): ResultVo<SkillDraftDecisionResponse> = try {
        ResultVo.success(skillDraftService.approve(id, request))
    } catch (e: DuplicateKeyException) {
        log.warn("Draft $id lost the race on its skill name", e)
        ResultVo.error(409, ApiErrors.message(e, "That name was taken while the approval ran; the draft is still pending"))
    } catch (e: BizException) {
        log.warn("Approval of draft $id refused: {}", e.message)
        ResultVo.error(e.code, e.message ?: "Failed to approve skill draft")
    } catch (e: Exception) {
        log.error("Failed to approve skill draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to approve skill draft"))
    }

    @PostMapping("/{id}/reject")
    @Operation(
        summary = "Reject a draft with a reason",
        description = "Closes the proposal; the reason is what the agent is told when it re-offers the same skill",
    )
    fun reject(
        @Parameter(description = "Draft ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: SkillDraftRejectRequest,
    ): ResultVo<SkillDraftDecisionResponse> = try {
        ResultVo.success(skillDraftService.reject(id, request))
    } catch (e: BizException) {
        log.warn("Rejection of draft $id refused: {}", e.message)
        ResultVo.error(e.code, e.message ?: "Failed to reject skill draft")
    } catch (e: Exception) {
        log.error("Failed to reject skill draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to reject skill draft"))
    }

    companion object {
        /** Queue rows carry no body, so a page can be wider than a skill list without hurting the read. */
        private const val DEFAULT_PAGE_SIZE = 20
    }
}
