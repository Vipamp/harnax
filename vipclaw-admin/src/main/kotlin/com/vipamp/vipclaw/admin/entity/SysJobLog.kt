package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "定时任务日志实体类")
class SysJobLog : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "日志ID")
    var id: Long = 0

    @Schema(description = "任务ID")
    var jobId: Long = 0

    @Schema(description = "任务名称")
    var jobName: String = ""

    @Schema(description = "任务组名")
    var jobGroup: String = ""

    @Schema(description = "调用目标")
    var invokeTarget: String = ""

    @Schema(description = "执行信息")
    var jobMessage: String = ""

    @Schema(description = "执行状态（0-失败，1-成功）")
    var status: Int = 1

    @Schema(description = "异常信息")
    var exceptionInfo = ""

    @Schema(description = "开始时间")
    var startTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "结束时间")
    var endTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "创建人")
    var creator: String = ""

    @Schema(description = "创建时间")
    var createTime: LocalDateTime = LocalDateTime.now()
}
