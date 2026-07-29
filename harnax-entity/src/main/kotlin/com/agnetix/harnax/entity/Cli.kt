package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * CLI tool entity.
 * Represents a command-line tool (e.g. kubectl, gh, awscli) that can be
 * installed into agent sandbox images via a Dockerfile install script.
 */
@Schema(description = "CLI tool entity")
class Cli : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    @Schema(description = "CLI name, e.g. kubectl")
    var name: String = ""

    @Schema(description = "CLI description")
    var description: String = ""

    @Schema(description = "CLI version, e.g. 1.30.0")
    var version: String = ""

    @Schema(description = "Dockerfile RUN fragment that installs this CLI")
    var installScript: String = ""

    @Schema(description = "Command to verify installation, e.g. kubectl version --client")
    var checkCommand: String = ""

    @Schema(description = "Environment variable declarations (JSON)")
    var envParams: String? = null

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 0

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
