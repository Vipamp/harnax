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
    fun getPlanNotes(sessionId: String): List<PlanNote>
    fun deletePlan(sessionId: String)
}

data class PlanNote(
    val sessionId: String,
    val planId: String,
    val name: String,
    var description: String = "",
    var expectedOutcome: String = "",
    var subtasks: List<PlanSubTask> = emptyList(),
    var createdAt: String,
    var finishedAt: String?,
    var costTimeSeconds: Long,
    val status: TaskState
)

data class PlanSubTask(
    var name: String,
    var description: String,
    var expectedOutcome: String,
    val outcome: String,
    var state: TaskState = TaskState.TODO,
    var createdAt: String,
    var finishedAt: String?,
    var costTimeSeconds: Long
)

enum class TaskState {
    TODO, IN_PROGRESS, DONE, ABANDONED
}
