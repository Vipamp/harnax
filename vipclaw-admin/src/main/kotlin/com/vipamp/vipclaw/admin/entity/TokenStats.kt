package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime

@Schema(description = "Token 消耗统计实体类")
class TokenStats : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "智能体 ID")
    var agentId: Long = 0

    @Schema(description = "会话 ID")
    var sessionId: String = ""

    @Schema(description = "对话模型 ID")
    var chatModelId: Long = 0

    @Schema(description = "输入 token 数量")
    var inputToken: Long = 0

    @Schema(description = "输出 token 数量")
    var outputToken: Long = 0

    @Schema(description = "总 token 数量")
    var totalToken: Long = 0

    @Schema(description = "模型费用（单位：元）")
    var fee: BigDecimal = BigDecimal.ZERO

    @Schema(description = "时间戳")
    var ts: LocalDateTime = LocalDateTime.now()
}
