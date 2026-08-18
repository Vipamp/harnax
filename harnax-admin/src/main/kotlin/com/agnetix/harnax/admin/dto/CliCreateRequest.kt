package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * CLI creation request DTO
 */
@Schema(description = "CLI creation request object")
data class CliCreateRequest(
    @Schema(description = "CLI name", example = "kubectl", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "CLI name cannot be empty")
    @Size(min = 1, max = 128, message = "CLI name length must be between 1-128")
    val name: String? = null,

    @Schema(description = "CLI description")
    val description: String? = null,

    @Schema(description = "CLI version", example = "1.30.0")
    val version: String? = null,

    @Schema(description = "Dockerfile RUN fragment that installs this CLI", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Install script cannot be empty")
    val installScript: String? = null,

    @Schema(description = "Command to verify installation", example = "kubectl version --client")
    val checkCommand: String? = null,

    @Schema(description = "Environment variable declarations")
    val envParams: List<ToolEnvParamEntry>? = null,

    @Schema(description = "Associated skill ID list")
    val skillIds: List<Long>? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
)
