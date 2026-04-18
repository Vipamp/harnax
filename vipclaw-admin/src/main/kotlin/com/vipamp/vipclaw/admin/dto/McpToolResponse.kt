package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.List

/**
 * MCP 工具响应对象
 *
 * @author vipamp
 * @since 2026-04-10
 */
@Schema(description = "MCP 工具响应对象")
data class McpToolResponse(
    @Schema(description = "工具名称", example = "read_file")
    val name: String? = null,

    @Schema(description = "参数列表")
    val parameters: List<McpToolParameter>? = null
) {
    /**
     * MCP 工具参数
     */
    @Schema(description = "MCP 工具参数")
    data class McpToolParameter(
        @Schema(description = "参数名", example = "file_path")
        val name: String? = null,

        @Schema(description = "参数类型", example = "string")
        val type: String? = null,

        @Schema(description = "参数注释", example = "文件路径")
        val description: String? = null
    )
}
