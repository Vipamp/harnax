package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One @Tool method of a builtin ToolBox. Every row is written by the startup-time
 * BuiltinToolAutoRegistrar, so no column here carries a tool type or an owner-scoped visibility flag.
 *
 * The table is platform-scoped (no `tenant_id`) and additive: [name] is the identity a declaration
 * owns, `beanName` / `methodName` only serve instantiation, and the registrar updates a row it finds
 * but never deletes one. `active` is therefore always 1 — keep it out of new write paths.
 */
@Schema(description = "Agent Tool entity")
class AgentTool : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Tool ID")
    var id: Long = 0

    @Schema(description = "Tool identifier name — the name declared by @Tool(name = ...)")
    var name: String = ""

    @Schema(description = "Display name (English)")
    var displayName: String? = null

    @Schema(description = "Display name (Chinese, for i18n zh-CN locale)")
    var displayNameZh: String? = null

    @Schema(description = "Tool description (sent to LLM)")
    var description: String = ""

    @Schema(description = "Spring Bean name (used to instantiate the toolbox, not part of the identity)")
    var beanName: String? = null

    @Schema(description = "Java method name (used to instantiate the toolbox, not part of the identity)")
    var methodName: String? = null

    @Schema(description = "Is read-only tool (0: No, 1: Yes)")
    var readOnly: Int = 0

    @Schema(description = "Requires human confirmation (0: No, 1: Yes)")
    var needConfirm: Int = 0

    @Schema(description = "Is mandatory tool (0: optional, 1: required — always included, hidden from UI)")
    var isRequired: Int = 0

    /**
     * Derived from `@ToolMeta(envParamDefs)`. Read by the admin UI and the save-time required-param
     * check only; the runtime resolves "required" through `ToolEnvContext.require()` instead.
     */
    @Schema(description = "Required environment parameter keys, JSON array format")
    var requiredEnvParamKeys: String? = null

    @Schema(description = "Status (0:disabled, 1:enabled). The sync is the only writer and writes 1.")
    var status: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status — always 1, the sync does not delete")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
