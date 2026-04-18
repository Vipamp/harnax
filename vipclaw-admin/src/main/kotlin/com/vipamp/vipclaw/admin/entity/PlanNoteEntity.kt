package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.*
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@TableName("plan_note")
@Schema(description = "PlanNote实体类")
class PlanNoteEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "会话ID")
    var sessionId: String = ""

    @Schema(description = "计划ID")
    var planId: String = ""

    @Schema(description = "计划名称")
    var name: String = ""

    @Schema(description = "计划描述")
    var description: String = ""

    @Schema(description = "预期结果")
    var expectedOutcome: String = ""

    @Schema(description = "子任务列表（JSON格式）")
    var subtasks: String = ""

    @Schema(description = "创建时间")
    var createdAt: String = ""

    @Schema(description = "完成时间")
    var finishedAt: String = ""

    @Schema(description = "耗时（秒）")
    var costTimeseconds: Long = 0

    @Schema(description = "状态")
    var status: String = ""

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    var updateTime: LocalDateTime? = null
}
