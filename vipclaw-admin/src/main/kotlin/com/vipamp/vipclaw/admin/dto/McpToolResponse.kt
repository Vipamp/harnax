package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * MCP tool response object
 */
@Schema(description = "MCP tool response object")
data class McpToolResponse(
    @Schema(description = "Tool name", example = "read_file")
    var name: String = "",

    @Schema(description = "Parameter list")
    var parameters: List<McpToolParameter> = emptyList(),
) {
    /**
     * MCP tool parameter
     */
    @Schema(description = "MCP tool parameter")
    data class McpToolParameter(
        @Schema(description = "Parameter name", example = "file_path")
        var name: String = "",

        @Schema(description = "Parameter type", example = "string")
        var type: String = "",

        @Schema(description = "Parameter description", example = "File path")
        var description: String = "",
    )
}
