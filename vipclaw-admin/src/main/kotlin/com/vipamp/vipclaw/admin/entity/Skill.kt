package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "技能实体类")
class Skill : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

        @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "技能名称")
    var name: String = ""

    @Schema(description = "仓库ID")
    var repositoryId: Long = 0

    @Schema(description = "技能描述")
    var description: String = ""

    @Schema(description = "skill.md 内容")
    var skillmd: String = ""

    @Schema(description = "资源信息")
    var resources: String = ""

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
