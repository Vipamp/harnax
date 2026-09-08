package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "Agent tool creation request")
data class AgentToolCreateRequest(
    @Schema(description = "Tool identifier name", example = "send_email", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Tool name cannot be empty")
    @Size(max = 100, message = "Tool name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "Display name (English)", example = "Send Email")
    val displayName: String? = null,

    @Schema(description = "Display name (Chinese, for i18n zh-CN locale)", example = "发送邮件")
    val displayNameZh: String? = null,

    @Schema(description = "Tool description (sent to LLM)")
    val description: String? = null,

    @Schema(
        description = "Tool type: CUSTOM / HTTP. BUILTIN is rejected - builtin tools are created by the code sync only",
        example = "HTTP",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @NotBlank(message = "Tool type cannot be empty")
    val type: String = "CUSTOM",

    @Schema(description = "Spring Bean name (for CUSTOM type)")
    val beanName: String? = null,

    @Schema(description = "Java method name (for CUSTOM type)")
    val methodName: String? = null,

    @Schema(description = "HTTP request URL (for HTTP type)")
    val httpUrl: String? = null,

    @Schema(description = "HTTP method (for HTTP type)", example = "POST")
    val httpMethod: String? = null,

    @Schema(description = "HTTP headers JSON string (for HTTP type)")
    val httpHeaders: List<McpConfigEntry>? = null,

    @Schema(description = "Environment parameters configuration")
    val envParams: List<ToolEnvParamEntry>? = null,

    @Schema(description = "Input parameter JSON Schema (for HTTP type)")
    val inputSchema: String? = null,

    @Schema(description = "Output result JSON Schema (for HTTP type)")
    val outputSchema: String? = null,

    @Schema(description = "Is read-only", example = "false")
    val readOnly: Boolean? = null,

    @Schema(description = "Requires human confirmation", example = "false")
    val needConfirm: Boolean? = null,

    @Schema(description = "Required environment parameter keys")
    val requiredEnvParamKeys: List<String>? = null,

    @Schema(description = "Timeout in seconds", example = "30")
    val timeoutSeconds: Int? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
)
