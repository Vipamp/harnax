package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "PlanNote实体类")
class PlanNoteEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

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
    var finishedAt: String? = null

    @Schema(description = "耗时（秒）")
    var costTimeseconds: Long = 0

    @Schema(description = "状态")
    var status: String = ""

    @Schema(description = "创建时间")
        var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
        var updateTime: LocalDateTime? = null
}
