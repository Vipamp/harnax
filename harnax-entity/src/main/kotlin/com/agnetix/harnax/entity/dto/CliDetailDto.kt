package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full CLI configuration for agent-service.
 * Includes install script for sandbox image building, resolved env bindings,
 * and skill IDs to merge into the agent's skill set.
 */
@Schema(description = "Full CLI configuration detail")
data class CliDetailDto(
    @Schema(description = "CLI ID")
    val id: Long,

    @Schema(description = "CLI name, e.g. kubectl")
    val name: String,

    @Schema(description = "CLI description")
    val description: String = "",

    @Schema(description = "CLI version")
    val version: String = "",

    @Schema(description = "Dockerfile RUN fragment that installs this CLI")
    val installScript: String = "",

    @Schema(description = "Command to verify installation")
    val checkCommand: String = "",

    @Schema(description = "Resolved env bindings ([{envKey, envValue}])")
    val envBindings: List<Map<String, String>> = emptyList(),

    @Schema(description = "Skill IDs associated with this CLI")
    val skillIds: List<Long> = emptyList(),
)
