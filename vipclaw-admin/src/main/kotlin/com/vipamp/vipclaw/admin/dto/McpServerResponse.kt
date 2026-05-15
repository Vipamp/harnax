package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.McpServer
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * MCP server response object
 */
@Schema(description = "MCP server response object")
data class McpServerResponse(
    @Schema(description = "MCP ID", example = "1")
    val id: Long? = null,
    @Schema(description = "MCP name", example = "my-mcp-server")
    val name: String? = null,
    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String? = null,
    @Schema(description = "MCP type (stdio/sse/streamablehttp)", example = "stdio")
    val type: String? = null,
    @Schema(description = "Execute command (only for stdio type)")
    val command: String? = null,
    @Schema(description = "Service URL (for sse/streamablehttp type)")
    val url: String? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-12 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-12 12:00:00")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        @JvmStatic
        fun fromEntity(entity: McpServer): McpServerResponse = McpServerResponse(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            type = entity.type,
            command = entity.command,
            url = entity.url,
            status = entity.status,
            isPublic = entity.isPublic,
            creator = entity.creator,
            createTime = entity.createTime,
            updateTime = entity.updateTime,
        )
    }
}
