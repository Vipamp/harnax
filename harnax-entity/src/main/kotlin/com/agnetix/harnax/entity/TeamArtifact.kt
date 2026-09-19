package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Team artifact entity — one published file a member handed over through MinIO.
 *
 * Members run in independent sandboxes, so a file reaches another member only through this record: the
 * producer publishes and gets back [fileId], the lead passes that reference on, and the next member
 * fetches it into its own workspace.
 *
 * [fileId] is a reference, not a credential. Every read resolves [tenantId], [sessionId] and the
 * producing [childSessionId] from this row before touching the object store, so [objectKey] is never
 * supplied by a model or a client.
 */
@Schema(description = "Team artifact entity")
class TeamArtifact : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Opaque artifact reference (UUID)")
    var fileId: String = ""

    @Schema(description = "Owning tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Root team session this artifact belongs to")
    var sessionId: String = ""

    @Schema(description = "FK to team.id")
    var teamId: Long = 0

    @Schema(description = "FK to agent.id, the member that produced it")
    var memberAgentId: Long = 0

    @Schema(description = "Member child session that produced it")
    var childSessionId: String = ""

    @Schema(description = "Original file name")
    var fileName: String = ""

    @Schema(description = "MIME type")
    var mimeType: String = "application/octet-stream"

    @Schema(description = "Size in bytes")
    var sizeBytes: Long = 0

    @Schema(description = "Internal MinIO object key")
    var objectKey: String = ""

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}
