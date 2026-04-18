package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@TableName("skill_repository")
@Schema(description = "技能仓库实体类")
class SkillRepository : Serializable {
    companion object { private const val serialVersionUID = 1L }

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    var id: Long =0

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
    @TableLogic(value = "1", delval = "0")
    var active: Int = 1

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime = LocalDateTime.now()
}
