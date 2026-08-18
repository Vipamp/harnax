package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Cli
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.ObjectMapper
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
    @Schema(description = "Environment variable declarations (masked for secret values)")
    val envParams: List<ToolEnvParamEntry>? = null,
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
        fun fromEntity(
            cli: Cli,
            objectMapper: ObjectMapper? = null,
            encryptor: SecretFieldEncryptor? = null,
        ): CliResponse = CliResponse(
            id = cli.id,
            name = cli.name,
            description = cli.description,
            version = cli.version,
            installScript = cli.installScript,
            checkCommand = cli.checkCommand,
            envParams = deserializeAndMaskToolEnvParams(cli.envParams, objectMapper, encryptor),
            status = cli.status,
            isPublic = cli.isPublic,
            creator = cli.creator,
            createTime = cli.createTime,
            updateTime = cli.updateTime,
        )

        /**
         * 反序列化 ToolEnvParamEntry JSON 并对敏感值做掩码处理
         */
        private fun deserializeAndMaskToolEnvParams(
            json: String?,
            objectMapper: ObjectMapper?,
            encryptor: SecretFieldEncryptor?,
        ): List<ToolEnvParamEntry>? {
            if (json.isNullOrBlank() || objectMapper == null) return null
            return try {
                val entries = objectMapper.readValue(
                    json,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, ToolEnvParamEntry::class.java),
                ) as List<ToolEnvParamEntry>
                entries.map { entry ->
                    if (entry.secret && entry.defaultValue != null) {
                        entry.copy(defaultValue = maskValue(entry.defaultValue!!, encryptor))
                    } else {
                        entry
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 对敏感值做掩码处理，保留前 3 后 4 字符
         */
        private fun maskValue(encryptedValue: String, encryptor: SecretFieldEncryptor?): String {
            if (encryptor == null) return "******"
            return try {
                val plain = encryptor.decrypt(encryptedValue)
                if (plain.length <= 7) {
                    "******"
                } else {
                    "${plain.take(3)}****${plain.takeLast(4)}"
                }
            } catch (e: Exception) {
                "******"
            }
        }
    }
}
