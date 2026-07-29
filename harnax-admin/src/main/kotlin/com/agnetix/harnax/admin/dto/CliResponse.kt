package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.Cli
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * CLI response object
 */
@Schema(description = "CLI response object")
data class CliResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "CLI name", example = "kubectl")
    val name: String? = null,
    @Schema(description = "CLI description")
    val description: String? = null,
    @Schema(description = "CLI version", example = "1.30.0")
    val version: String? = null,
    @Schema(description = "Dockerfile RUN fragment that installs this CLI")
    val installScript: String? = null,
    @Schema(description = "Command to verify installation")
    val checkCommand: String? = null,
    @Schema(description = "Environment variable declarations (JSON)")
    val envParams: String? = null,
    @Schema(description = "Associated skills")
    var skillList: List<SkillItem>? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "0")
    val isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    val creator: String? = null,
    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,
    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
) {
    @Schema(description = "Associated skill item")
    data class SkillItem(
        @Schema(description = "Skill ID", example = "1")
        val skillId: Long? = null,
        @Schema(description = "Skill name", example = "kubectl-usage")
        val skillName: String? = null,
        @Schema(description = "Skill description")
        val skillDescription: String? = null,
    )

    companion object {
        @JvmStatic
        fun fromEntity(cli: Cli): CliResponse = CliResponse(
            id = cli.id,
            name = cli.name,
            description = cli.description,
            version = cli.version,
            installScript = cli.installScript,
            checkCommand = cli.checkCommand,
            envParams = cli.envParams,
            status = cli.status,
            isPublic = cli.isPublic,
            creator = cli.creator,
            createTime = cli.createTime,
            updateTime = cli.updateTime,
        )
    }
}
