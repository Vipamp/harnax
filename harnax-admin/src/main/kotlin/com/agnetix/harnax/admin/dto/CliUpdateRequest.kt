package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * CLI update request DTO
 */
@Schema(description = "CLI update request object")
data class CliUpdateRequest(
    @Schema(description = "CLI name", example = "kubectl")
    @Size(min = 1, max = 128, message = "CLI name length must be between 1-128")
    val name: String? = null,

    @Schema(description = "CLI description")
    val description: String? = null,

    @Schema(description = "CLI version", example = "1.30.0")
    val version: String? = null,

    @Schema(description = "Dockerfile RUN fragment that installs this CLI")
    val installScript: String? = null,

    @Schema(description = "Command to verify installation", example = "kubectl version --client")
    val checkCommand: String? = null,

    @Schema(description = "Environment variable declarations")
    val envParams: List<ToolEnvParamEntry>? = null,

    @Schema(description = "Associated skill ID list")
    val skillIds: List<Long>? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
)
