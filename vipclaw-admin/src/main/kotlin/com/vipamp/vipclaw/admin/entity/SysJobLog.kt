package com.vipamp.vipclaw.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Scheduled job log entity")
class SysJobLog : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Log ID")
    var id: Long = 0

    @Schema(description = "Job ID")
    var jobId: Long = 0

    @Schema(description = "Job name")
    var jobName: String = ""

    @Schema(description = "Job group")
    var jobGroup: String = ""

    @Schema(description = "Invocation target")
    var invokeTarget: String = ""

    @Schema(description = "Execution message")
    var jobMessage: String = ""

    @Schema(description = "Execution status (0-failure, 1-success)")
    var status: Int = 1

    @Schema(description = "Exception information")
    var exceptionInfo = ""

    @Schema(description = "Start time")
    var startTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "End time")
    var endTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()
}
