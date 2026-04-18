package com.vipamp.vipclaw.admin.entity

import com.baomidou.mybatisplus.annotation.IdType
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@TableName("process_log")
@Schema(description = "处理日志实体类")
class ProcessLogEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "智能体 ID")
    var agentId: Long = 0

    @Schema(description = "智能体名称")
    var agentName: String = ""

    @Schema(description = "会话 ID")
    var sessionId: String = ""

    @Schema(description = "日志消息")
    var message: String = ""

    @Schema(description = "日志类型 (INFO/WARN/ERROR)")
    var logType: String = "INFO"

    @Schema(description = "异常堆栈信息")
    var stackTrace: String = ""

    @Schema(description = "时间戳")
    var ts: LocalDateTime = LocalDateTime.now()
}
