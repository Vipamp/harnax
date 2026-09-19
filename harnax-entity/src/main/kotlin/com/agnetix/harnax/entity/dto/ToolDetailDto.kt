package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full tool configuration returned by admin internal API.
 * Eliminates the need for agent-service to query agent_tool table directly.
 */
@Schema(description = "Tool detail configuration")
data class ToolDetailDto(
    @Schema(description = "Tool ID")
    val id: Long,

    @Schema(description = "Tool identifier name")
    val name: String,

    @Schema(description = "Display name (English)")
    val displayName: String? = null,

    @Schema(description = "Display name (Chinese)")
    val displayNameZh: String? = null,

    @Schema(description = "Tool description (sent to LLM)")
    val description: String = "",

    @Schema(description = "Spring Bean name")
    val beanName: String? = null,

    @Schema(description = "Java method name")
    val methodName: String? = null,

    @Schema(description = "Is read-only tool (0: No, 1: Yes)")
    val readOnly: Int = 0,

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    val needConfirm: Int = 0,

    @Schema(description = "Required environment parameter keys, JSON array format")
    val requiredEnvParamKeys: String? = null,

    @Schema(description = "Timeout in seconds")
    val timeoutSeconds: Int = 30,

    @Schema(description = "Tool status (0: disabled, 1: enabled). The sync is the only writer and always writes 1.")
    val status: Int = 1,

    // ── Binding-level fields (from agent_tool_binding) ──

    @Schema(description = "Binding-level need_confirm override")
    val bindingNeedConfirm: Boolean = false,
)
