package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Agent Task Execution Guard entity")
class AgentTaskExecution : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Task ID")
    var taskId: Long = 0

    @Schema(description = "Trigger time (for deduplication)")
    var triggerTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Instance ID")
    var instanceId: String = ""

    @Schema(description = "Actual start time")
    var startTime: LocalDateTime? = null

    @Schema(description = "End time")
    var endTime: LocalDateTime? = null

    @Schema(description = "Status (0:running, 1:success, 2:failed)")
    var status: Int = 0

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}
