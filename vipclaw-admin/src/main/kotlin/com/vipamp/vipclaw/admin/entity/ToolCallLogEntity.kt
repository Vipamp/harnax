package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "工具调用日志实体类")
class ToolCallLogEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "智能体 ID")
    var agentId: Long = 0

    @Schema(description = "会话 ID")
    var sessionId: String = ""

    @Schema(description = "工具名称")
    var toolName: String = ""

    @Schema(description = "工具参数（JSON 格式）")
    var args: String = ""

    @Schema(description = "工具执行结果")
    var result: String = ""

    @Schema(description = "是否成功（1-成功，0-失败）")
    var success: Int = 0

    @Schema(description = "开始时间戳")
    var startTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "结束时间戳")
    var endTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "执行耗时（毫秒）")
    var duration: Long = 0

    @Schema(description = "时间戳")
    var ts: LocalDateTime = LocalDateTime.now()
}
