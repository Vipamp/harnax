package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * CLI Plugin entity.
 * Represents a CLI tool that can be injected into sandbox containers.
 */
@Schema(description = "CLI Plugin entity")
class CliPlugin : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Plugin ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "Plugin identifier name")
    var name: String = ""

    @Schema(description = "Display name (English)")
    var displayName: String? = null

    @Schema(description = "Display name (Chinese)")
    var displayNameZh: String? = null

    @Schema(description = "Plugin description")
    var description: String? = null

    @Schema(description = "CLI binary version")
    var version: String? = null

    @Schema(description = "Plugin type: SYSTEM / CUSTOM")
    var type: String = "SYSTEM"

    @Schema(description = "Container binary path")
    var binaryPath: String? = null

    @Schema(description = "Container init script path")
    var initScript: String? = null

    @Schema(description = "Container SKILL.md path")
    var skillDocPath: String? = null

    @Schema(description = "Health check command")
    var healthCheck: String? = null

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Creator")
    var creator: String = "SYSTEM"

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
