package com.agnetix.harnax.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "Scheduled job entity")
class SysJob : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Job ID")
    var id: Long = 0

    @Schema(description = "Job name")
    var jobName: String = ""

    @Schema(description = "Job group")
    var jobGroup: String = ""

    @Schema(description = "Fully qualified class name")
    var jobClass: String = ""

    @Schema(description = "Cron expression")
    var cronExpression: String = ""

    @Schema(description = "Status (0-paused, 1-running)")
    var jobStatus: Int = 1

    @Schema(description = "Concurrent execution allowed (0-no, 1-yes)")
    var concurrent: Int = 1

    @Schema(description = "Job description")
    var description: String = ""

    @Schema(description = "Public status (0:no, 1:yes)")
    var isPublic: Int = 1

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0-deleted, 1-active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
