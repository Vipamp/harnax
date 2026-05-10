package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "技能仓库实体类")
class SkillRepository : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "所属租户ID")
    var tenantId: Long = 1

    @Schema(description = "仓库名称")
    var name: String = ""

    @Schema(description = "仓库地址")
    var url: String = ""

    @Schema(description = "分支名称", example = "main")
    var branch: String = ""

    @Schema(description = "仓库描述")
    var description: String = ""

    @Schema(description = "是否启用（0:禁用，1:启用）")
    var status: Int = 1

    @Schema(description = "是否公开（0:否，1:是）")
    var isPublic: Int = 1

    @Schema(description = "创建人")
    var creator: String = ""

    @Schema(description = "是否可用（0:被删除，1:可用）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
