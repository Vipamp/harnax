package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.skill.SkillSourceConfigs
import com.agnetix.harnax.admin.skill.SkillSyncRecorder
import com.agnetix.harnax.entity.SkillRepository
import io.swagger.v3.oas.annotations.media.Schema

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

    @Schema(description = "Last sync outcome: SUCCESS | PARTIAL | FAILED | EMPTY, null until the first sync")
    val lastSyncStatus: String? = null,

    @Schema(description = "When the last sync finished")
    val lastSyncTime: String? = null,

    @Schema(description = "Last sync report: saved/installed/updated/failed/flagged/error")
    val lastSyncDetail: Map<String, Any>? = null,

    @Schema(
        description = "Skills under this source that are still enabled; the source can be deleted only once this reaches 0. " +
            "Absent where the endpoint does not look them up, i.e. GET /skill-sources/active",
    )
    val enabledSkillCount: Int? = null,
) {
    companion object {
        fun fromEntity(
            entity: SkillRepository,
            enabledSkillCount: Int? = null,
        ): SkillSourceResponse = SkillSourceResponse(
            id = entity.id,
            name = entity.name,
            sourceType = entity.sourceType,
            sourceConfig = SkillSourceConfigs.forApi(entity),
            version = entity.version,
            url = entity.url,
            branch = entity.branch,
            description = entity.description,
            status = entity.status,
            isPublic = entity.isPublic,
            creator = entity.creator,
            createTime = entity.createTime.toString(),
            updateTime = entity.updateTime.toString(),
            lastSyncStatus = entity.lastSyncStatus,
            lastSyncTime = entity.lastSyncTime?.toString(),
            lastSyncDetail = SkillSyncRecorder.forApi(entity.lastSyncDetail),
            enabledSkillCount = enabledSkillCount,
        )
    }
}
