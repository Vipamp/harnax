package com.vipamp.vipclaw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * MCP 工具响应对象
 *
 * @author vipamp
 * @since 2026-04-10
 */
@Data
@Schema(description = "MCP 工具响应对象")
public class McpToolResponse {

    /**
     * 工具名称
     */
    @Schema(description = "工具名称", example = "read_file")
    private String name;

    /**
     * 参数列表
     */
    @Schema(description = "参数列表")
    private List<McpToolParameter> parameters;

    /**
     * MCP 工具参数
     */
    @Data
    @Schema(description = "MCP 工具参数")
    public static class McpToolParameter {
        /**
         * 参数名
         */
        @Schema(description = "参数名", example = "file_path")
        private String name;

        /**
         * 参数类型
         */
        @Schema(description = "参数类型", example = "string")
        private String type;

        /**
         * 参数注释
         */
        @Schema(description = "参数注释", example = "文件路径")
        private String description;
    }
}
