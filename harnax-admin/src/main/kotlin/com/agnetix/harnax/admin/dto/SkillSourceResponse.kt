package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.SkillRepository
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.ObjectMapper

@Schema(description = "Skill source response")
data class SkillSourceResponse(
    @Schema(description = "ID")
    val id: Long = 0,

    @Schema(description = "Source name")
    val name: String = "",

    @Schema(description = "Source type: GIT | NPM | ZIP")
    val sourceType: String = "GIT",

    @Schema(description = "Source configuration")
    val sourceConfig: Map<String, Any>? = null,

    @Schema(description = "Version")
    val version: String = "",

    @Schema(description = "Repository URL (for GIT type)")
    val url: String = "",

    @Schema(description = "Branch (for GIT type)")
    val branch: String = "",

    @Schema(description = "Description")
    val description: String = "",

    @Schema(description = "Status")
    val status: Int = 1,

    @Schema(description = "Public status")
    val isPublic: Int = 0,

    @Schema(description = "Creator")
    val creator: String = "",

    @Schema(description = "Creation time")
    val createTime: String = "",

    @Schema(description = "Update time")
    val updateTime: String = "",
) {
    companion object {
        private val objectMapper = ObjectMapper()

        fun fromEntity(entity: SkillRepository): SkillSourceResponse {
            val configMap: Map<String, Any>? = try {
                if (entity.sourceConfig.isNotBlank()) {
                    objectMapper.readValue(entity.sourceConfig, object : tools.jackson.core.type.TypeReference<Map<String, Any>>() {})
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }

            return SkillSourceResponse(
                id = entity.id,
                name = entity.name,
                sourceType = entity.sourceType,
                sourceConfig = configMap,
                version = entity.version,
                url = entity.url,
                branch = entity.branch,
                description = entity.description,
                status = entity.status,
                isPublic = entity.isPublic,
                creator = entity.creator,
                createTime = entity.createTime.toString(),
                updateTime = entity.updateTime.toString(),
            )
        }
    }
}
