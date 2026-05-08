package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "定时任务实体类")
class SysJob : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "任务ID")
    var id: Long = 0

    @Schema(description = "任务名称")
    var jobName: String = ""

    @Schema(description = "任务组名")
    var jobGroup: String = ""

    @Schema(description = "执行类全路径")
    var jobClass: String = ""

    @Schema(description = "Cron执行表达式")
    var cronExpression: String = ""

    @Schema(description = "状态（0-暂停，1-运行）")
    var jobStatus: Int = 1

    @Schema(description = "是否允许并发（0-禁止，1-允许）")
    var concurrent: Int = 1

    @Schema(description = "任务描述")
    var description: String = ""

    @Schema(description = "是否公开（0:否，1:是）")
    var isPublic: Int = 1

    @Schema(description = "创建人")
    var creator: String = ""

    @Schema(description = "是否可用（0-已删除，1-未删除）")
    var active: Int = 1

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "更新时间")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
