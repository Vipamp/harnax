package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Agent Tool entity")
class AgentTool : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Tool ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Tool identifier name")
    var name: String = ""

    @Schema(description = "Display name (English)")
    var displayName: String? = null

    @Schema(description = "Display name (Chinese, for i18n zh-CN locale)")
    var displayNameZh: String? = null

    @Schema(description = "Tool description (sent to LLM)")
    var description: String = ""

    @Schema(description = "Tool type: BUILTIN / CUSTOM / HTTP")
    var type: String = "BUILTIN"

    @Schema(description = "Spring Bean name (for BUILTIN/CUSTOM type)")
    var beanName: String? = null

    @Schema(description = "Java method name (for BUILTIN/CUSTOM type, one record per @Tool method)")
    var methodName: String? = null

    @Schema(description = "HTTP request URL (for HTTP type)")
    var httpUrl: String? = null

    @Schema(description = "HTTP method (for HTTP type)")
    var httpMethod: String = "POST"

    @Schema(description = "HTTP headers JSON (for HTTP type)")
    var httpHeaders: String? = null

    @Schema(description = "Environment parameters configuration JSON")
    var envParams: String? = null

    @Schema(description = "Input parameter JSON Schema (for HTTP type)")
    var inputSchema: String? = null

    @Schema(description = "Output result JSON Schema (for HTTP type)")
    var outputSchema: String? = null

    @Schema(description = "Is read-only tool (0: No, 1: Yes)")
    var readOnly: Int = 0

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    var needConfirm: Int = 0

    @Schema(description = "Is mandatory tool (0: optional, 1: required — always included, hidden from UI)")
    var isRequired: Int = 0

    @Schema(description = "Required environment parameter keys, JSON array format")
    var requiredEnvParamKeys: String? = null

    @Schema(description = "Timeout in seconds")
    var timeoutSeconds: Int = 30

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
