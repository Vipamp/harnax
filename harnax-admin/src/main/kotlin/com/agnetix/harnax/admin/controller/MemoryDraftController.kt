package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.MemoryDraftApproveRequest
import com.agnetix.harnax.admin.dto.MemoryDraftDecisionResponse
import com.agnetix.harnax.admin.dto.MemoryDraftDetailResponse
import com.agnetix.harnax.admin.dto.MemoryDraftRejectRequest
import com.agnetix.harnax.admin.dto.MemoryDraftResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.MemoryDraftService
import com.agnetix.harnax.admin.util.ApiErrors
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.MemoryDraft
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The owner's half of the memory merge queue: their candidates, one candidate, and the two decisions.
 *
 * It sits next to `/api/admin/memory` rather than under it because the two read different stores: that one
 * shows what the memory bucket holds now, this one shows rows that may never be written anywhere. A
 * candidate is not a memory yet.
 *
 * Nothing here takes a user id. Every read and write is scoped to the person on the token, so the queue a
 * reviewer sees is their own recollections and no other account's, and the memory this can change is the one
 * their own conversations wrote. Two refusal shapes, split on what the caller can do about it: a request
 * nobody can act on is an error envelope, and a refusal the screen has to respond to comes back inside a
 * successful envelope as an outcome, see [MemoryDraftDecisionResponse]. The one envelope refusal that carries
 * a race is the 409 from an approval whose store write lost — the candidate is still PENDING there, and
 * re-reading it is the fix.
 */
@RestController
@RequestMapping("/api/admin/memory-drafts")
@Tag(name = "Memory Draft Review", description = "Review queue for memory merges proposed by agent conversations")
class MemoryDraftController(
    private val memoryDraftService: MemoryDraftService,
) {

    private val log = LoggerFactory.getLogger(MemoryDraftController::class.java)

    @GetMapping
    @Operation(
        summary = "List the caller's memory candidates",
        description = "Only this account's own candidates, newest touched first; defaults to everything still waiting",
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
        @Parameter(description = "Agent whose layer the merge joins, partial match") @RequestParam(name = "agentName", required = false) agentName: String?,
        @Parameter(description = "Conversation the merge came out of, exact id") @RequestParam(name = "sessionId", required = false) sessionId: String?,
    ): ResultVo<Page<MemoryDraftResponse>> = try {
        ResultVo.success(
            memoryDraftService.page(
                status = status ?: MemoryDraft.STATUS_PENDING,
                agentName = agentName,
                sessionId = sessionId,
                pageNum = pageNum ?: 1,
                pageSize = pageSize ?: DEFAULT_PAGE_SIZE,
            ),
        )
    } catch (e: BizException) {
        log.warn("Memory queue refused: {}", e.message)
        ResultVo.error(e.code, e.message ?: "Failed to list memory drafts")
    } catch (e: Exception) {
        log.error("Failed to list memory drafts", e)
        ResultVo.error(ApiErrors.message(e, "Failed to list memory drafts"))
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Read one candidate for review",
        description = "Both texts, the source files the merge read, and the digest an approval must send back",
    )
    fun detail(
        @Parameter(description = "Candidate ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<MemoryDraftDetailResponse> = try {
        ResultVo.success(memoryDraftService.detail(id))
    } catch (e: BizException) {
        log.warn("Memory candidate {} could not be reviewed: {}", id, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to read memory draft")
    } catch (e: Exception) {
        log.error("Failed to read memory draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to read memory draft"))
    }

    @PostMapping("/{id}/approve")
    @Operation(
        summary = "Approve a merge and write it into the long-term layer",
        description = "Version-checked against the memory store; the conversation files it merged out of are cleared only after the write lands",
    )
    fun approve(
        @Parameter(description = "Candidate ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: MemoryDraftApproveRequest,
    ): ResultVo<MemoryDraftDecisionResponse> = try {
        ResultVo.success(memoryDraftService.approve(id, request))
    } catch (e: BizException) {
        log.warn("Approval of memory candidate {} refused: {}", id, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to approve memory draft")
    } catch (e: Exception) {
        log.error("Failed to approve memory draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to approve memory draft"))
    }

    @PostMapping("/{id}/reject")
    @Operation(
        summary = "Reject a merge with a reason",
        description = "Closes the candidate and leaves both layers alone; the conversation proposes again next window",
    )
    fun reject(
        @Parameter(description = "Candidate ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: MemoryDraftRejectRequest,
    ): ResultVo<MemoryDraftDecisionResponse> = try {
        ResultVo.success(memoryDraftService.reject(id, request))
    } catch (e: BizException) {
        log.warn("Rejection of memory candidate {} refused: {}", id, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to reject memory draft")
    } catch (e: Exception) {
        log.error("Failed to reject memory draft $id", e)
        ResultVo.error(ApiErrors.message(e, "Failed to reject memory draft"))
    }

    companion object {
        /** Queue rows carry no body, so a page can be wider than a memory listing without hurting the read. */
        private const val DEFAULT_PAGE_SIZE = 20
    }
}
