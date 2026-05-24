package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

@Schema(description = "PlanNote entity")
class PlanNoteEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "Session ID")
    var sessionId: String = ""

    @Schema(description = "Plan ID")
    var planId: String = ""

    @Schema(description = "Plan name")
    var name: String = ""

    @Schema(description = "Plan description")
    var description: String = ""

    @Schema(description = "Expected outcome")
    var expectedOutcome: String = ""

    @Schema(description = "Subtasks list (JSON format)")
    var subtasks: String = ""

    @Schema(description = "Creation time")
    var createdAt: String = ""

    @Schema(description = "Completion time")
    var finishedAt: String? = null

    @Schema(description = "Cost time (seconds)")
    var costTimeseconds: Long = 0

    @Schema(description = "Status")
    var status: String = ""
}
