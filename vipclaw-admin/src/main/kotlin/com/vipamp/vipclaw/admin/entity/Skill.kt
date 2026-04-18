package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@TableName("skill")
@Schema(description = "技能实体类")
class Skill : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @TableId(value = "id", type = IdType.AUTO)
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
    @TableLogic(value = "1", delval = "0")
    var active: Int = 1

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime = LocalDateTime.now()
}
