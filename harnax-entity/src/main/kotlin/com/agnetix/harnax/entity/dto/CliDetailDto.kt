package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full CLI configuration for agent-service, as registered from a plugin package.
 *
 * [packageObject] with the two digests is what the runtime needs to fetch and cache the archive:
 * `packageDigest` names the stored object and decides whether it has to be downloaded again, while
 * `payloadDigest` is the sandbox image fingerprint — so a package whose only change was its `SKILL.md`
 * updates the prompt without rebuilding a container that is already running.
 *
 * [skill] is the `SKILL.md` shipped inside the package, delivered inline rather than as an ID for the
 * runtime to look up: a CLI's skill has no existence apart from the CLI, and a second round trip would
 * only be another way for the two halves of one answer to disagree.
 */
@Schema(description = "Full CLI configuration detail")
data class CliDetailDto(
    @Schema(description = "CLI ID")
    val id: Long,

    @Schema(description = "CLI name, e.g. harnax-cli")
    val name: String,

    @Schema(description = "CLI version")
    val version: String = "",

    @Schema(description = "Command run inside the built image to verify the payload")
    val checkCommand: String = "",

    @Schema(description = "MinIO object key of the stored package")
    val packageObject: String = "",

    @Schema(description = "sha256 of the package archive, and its MinIO object key")
    val packageDigest: String = "",

    @Schema(description = "Canonical sha256 of payload plus deps: the sandbox image fingerprint")
    val payloadDigest: String = "",

    @Schema(description = "apt packages to install alongside the payload")
    val depsApt: List<String> = emptyList(),

    @Schema(description = "Env slots to resolve and inject at container creation")
    val runtimeEnv: Map<String, String> = emptyMap(),

    @Schema(description = "Resolved env bindings ([{envKey, envValue}])")
    val envBindings: List<Map<String, String>> = emptyList(),

    @Schema(description = "The skill shipped inside the package; null when it is not delivering one")
    val skill: SkillDetailDto? = null,
)
