package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One skill an agent wrote in a session, submitted for a human to decide on.
 *
 * [sessionId] is the authority in the request, exactly as in
 * [com.agnetix.harnax.admin.dto.SkillUsageReportRequest]: the tenant and the proposing agent are resolved
 * from it here rather than read off the caller, so a runtime cannot file a proposal inside somebody else's
 * review queue. No draft id is sent either — the queue merges by (tenant, name), which is the identity the
 * agent itself knows.
 *
 * [resources] carries the full text of every support file. The upstream promotion candidate carries only
 * path names and a 40-line head of each script, and a queue that stored the head would show a reviewer a
 * preview of a file whose tail they were approving anyway; the runtime therefore reads the draft directory
 * and sends the bytes. [scanVerdict] and [scanFindings] are the upstream scan's own conclusions, kept for
 * display: promotion rescans with harnax's rules and decides disable-or-live from that second opinion.
 */
@Schema(description = "A skill proposed by an agent and awaiting human review")
data class SkillDraftSubmitRequest(
    @Schema(description = "Runtime session that wrote the draft", example = "web-0f2a…")
    val sessionId: String? = null,

    @Schema(description = "Proposed skill name, trimmed and matched against the tenant's open drafts", example = "invoice-pdf-fill")
    val name: String? = null,

    @Schema(description = "Proposed one-line description", example = "Fill an invoice PDF from a table")
    val description: String? = null,

    @Schema(description = "Full SKILL.md body, frontmatter included")
    val skillmd: String? = null,

    @Schema(description = "Support files as path -> content, the same shape as skill.resources")
    val resources: Map<String, String>? = null,

    @Schema(description = "Upstream security scan verdict", example = "CAUTION")
    val scanVerdict: String? = null,

    @Schema(description = "Upstream scan findings, one line each")
    val scanFindings: List<String>? = null,
)
