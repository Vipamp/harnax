package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.TeamArtifact
import io.swagger.v3.oas.annotations.media.Schema

/**
 * One published team file, as the chat UI shows it.
 *
 * Deliberately without [TeamArtifact.objectKey]: the key is where the bytes live in the bucket, and a
 * client that has it can try to use it elsewhere. A client only ever needs [fileId], and the download
 * endpoint resolves the key from the row itself.
 */
@Schema(description = "Team artifact response")
data class TeamArtifactResponse(
    @Schema(description = "Artifact reference to pass to a member or to the download endpoint")
    val fileId: String = "",

    @Schema(description = "Original file name")
    val fileName: String = "",

    @Schema(description = "MIME type")
    val mimeType: String = "",

    @Schema(description = "Size in bytes")
    val sizeBytes: Long = 0,

    @Schema(description = "Member agent that produced it")
    val memberAgentId: Long = 0,

    @Schema(description = "Publication time")
    val createTime: String = "",
) {
    companion object {
        @JvmStatic
        fun of(artifact: TeamArtifact): TeamArtifactResponse = TeamArtifactResponse(
            fileId = artifact.fileId,
            fileName = artifact.fileName,
            mimeType = artifact.mimeType,
            sizeBytes = artifact.sizeBytes,
            memberAgentId = artifact.memberAgentId,
            createTime = artifact.createTime.toString(),
        )
    }
}
