package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * CLI plugin package entity.
 *
 * One row is one `.harnaxcli.zip` package found in admin's package directory: [name] / [version] /
 * [checkCommand] / [envParams] / [depsApt] / [runtimeEnv] come from its `plugin.yaml`, [skillId]
 * points at the `skill/SKILL.md` it ships, and the three digest/object columns identify the
 * uploaded archive. Nothing here is hand-entered — see `CliPackageAutoRegistrar`.
 */
@Schema(description = "CLI plugin package entity")
class Cli : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Package name, unique platform-wide and the identity across versions")
    var name: String = ""

    @Schema(description = "CLI description")
    var description: String = ""

    @Schema(description = "Version declared by plugin.yaml")
    var version: String = ""

    @Schema(description = "Command run inside the built image to verify the payload")
    var checkCommand: String = ""

    @Schema(description = "FK to skill.id: the SKILL.md shipped inside the package")
    var skillId: Long? = null

    @Schema(description = "sha256 of the whole package zip — package identity and MinIO object key")
    var packageDigest: String = ""

    @Schema(description = "Canonical sha256 of payload plus deps — the sandbox image fingerprint")
    var payloadDigest: String = ""

    @Schema(description = "MinIO object key of the stored package")
    var packageObject: String = ""

    @Schema(description = "apt packages to install alongside the payload (JSON array)")
    var depsApt: String? = null

    @Schema(description = "Env slots the platform injects at container creation (JSON object)")
    var runtimeEnv: String? = null

    @Schema(description = "Environment variable declarations (JSON)")
    var envParams: String? = null

    @Schema(description = "Status (0:disabled, 1:enabled). The operator's kill switch: never overwritten by registration")
    var status: Int = 1

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
