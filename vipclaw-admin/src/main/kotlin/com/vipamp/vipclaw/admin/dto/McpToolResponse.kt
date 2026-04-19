package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * MCP 工具响应对象
 *
 * @author vipamp
 * @since 2026-04-10
 */
@Schema(description = "MCP 工具响应对象")
data class McpToolResponse(
    @Schema(description = "工具名称", example = "read_file")
    var name: String = "",

    @Schema(description = "参数列表")
    var parameters: List<McpToolParameter> = emptyList()
) {
    /**
     * MCP 工具参数
     */
    @Schema(description = "MCP 工具参数")
    data class McpToolParameter(
        @Schema(description = "参数名", example = "file_path")
        var name: String = "",

        @Schema(description = "参数类型", example = "string")
        var type: String = "",

        @Schema(description = "参数注释", example = "文件路径")
        var description: String = "",
    )
}
