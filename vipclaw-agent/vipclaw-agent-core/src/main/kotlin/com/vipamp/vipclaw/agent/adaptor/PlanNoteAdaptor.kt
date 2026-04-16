package com.vipamp.vipclaw.agent.adaptor

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: PlanNoteAdaptor
 * @Project: vipclaw
 */
interface PlanNoteAdaptor {
    fun save(planNote: PlanNote)
    fun getPlanNote(sessionId: String, planId: String): PlanNote?
    fun getPlanNotes(sessionId: String): MutableList<PlanNote>
    fun deletePlan(sessionId: String)
}

data class PlanNote(
    val sessionId: String, val planId: String, val name: String,
    var description: String? = null,
    var expectedOutcome: String? = null,
    var subtasks: MutableList<PlanSubTask>? = null,
    var createdAt: String, var finishedAt: String?,
    var costTimeseconds: Long
)

data class PlanSubTask(
    var name: String,
    var description: String,
    var expectedOutcome: String, val outcome: String,
    var state: PlanSubTaskState = PlanSubTaskState.TODO,
    var createdAt: String,
    var finishedAt: String?,
    var costTimeSeconds: Long
)

enum class PlanSubTaskState {
    TODO, IN_PROGRESS, DONE, ABANDONED
}
