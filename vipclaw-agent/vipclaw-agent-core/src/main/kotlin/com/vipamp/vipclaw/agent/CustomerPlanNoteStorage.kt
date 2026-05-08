package com.vipamp.vipclaw.agent

import com.vipamp.vipclaw.agent.adaptor.PlanNote
import com.vipamp.vipclaw.agent.adaptor.PlanNoteAdaptor
import com.vipamp.vipclaw.agent.adaptor.PlanSubTask
import com.vipamp.vipclaw.agent.adaptor.TaskState
import io.agentscope.core.plan.model.Plan
import io.agentscope.core.plan.model.PlanState
import io.agentscope.core.plan.model.SubTask
import io.agentscope.core.plan.model.SubTaskState
import io.agentscope.core.plan.storage.PlanStorage
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: CustomPlanNoteStorage
 * @Project: vipclaw
 */
class CustomerPlanNoteStorage(
    val sessionId: String,
    val planNoteAdaptor: PlanNoteAdaptor,
) : PlanStorage {

    private val log = LoggerFactory.getLogger(CustomerPlanNoteStorage::class.java)

    override fun addPlan(plan: Plan?): Mono<Void> {
        return Mono.fromRunnable {
            if (plan == null) {
                log.warn("Attempted to add null plan")
                return@fromRunnable
            }

            try {
                // 将 Plan 转换为 PlanNote
                val planNote = convertToPlanNote(sessionId, plan)
                planNoteAdaptor.save(planNote)
                log.info(
                    "Plan added successfully: sessionId={}, planId={}, name={}",
                    sessionId,
                    plan.id,
                    plan.name,
                )
            } catch (e: Exception) {
                log.error(
                    "Error adding plan: sessionId={}, planId={}",
                    sessionId,
                    plan.id,
                    e,
                )
                throw e
            }
        }
    }

    override fun getPlan(planId: String?): Mono<Plan> = Mono.justOrEmpty(
        if (planId == null) {
            log.warn("Attempted to get plan with null planId")
            null
        } else {
            try {
                val planNote = planNoteAdaptor.getPlanNote(sessionId, planId)
                if (planNote != null) {
                    convertToPlan(planNote)
                } else {
                    log.warn("Plan not found: sessionId={}, planId={}", sessionId, planId)
                    null
                }
            } catch (e: Exception) {
                log.error(
                    "Error getting plan: sessionId={}, planId={}",
                    sessionId,
                    planId,
                    e,
                )
                null
            }
        },
    )

    override fun getPlans(): Mono<List<Plan>> = Mono.fromCallable {
        try {
            val planNotes = planNoteAdaptor.getPlanNotes(sessionId)
            planNotes.map { planNote -> convertToPlan(planNote) }
        } catch (e: Exception) {
            log.error("Error getting plans: sessionId={}", sessionId, e)
            emptyList()
        }
    }

    companion object {

        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val log = LoggerFactory.getLogger(CustomerPlanNoteStorage::class.java)

        /**
         * 将 Plan 转换为 PlanNote
         */
        fun convertToPlanNote(sessionId: String, plan: Plan): PlanNote {
            val subtasks = plan.subtasks!!.map { toPlanSubTask(it) }.toMutableList()
            return PlanNote(
                sessionId = sessionId,
                planId = plan.id ?: "",
                name = plan.name ?: "",
                description = plan.description,
                expectedOutcome = plan.expectedOutcome,
                subtasks = subtasks,
                createdAt = plan.createdAt,
                finishedAt = plan.finishedAt,
                costTimeSeconds = compareTime(plan.createdAt, plan.finishedAt),
                status = when (plan.state) {
                    PlanState.TODO -> TaskState.TODO
                    PlanState.IN_PROGRESS -> TaskState.IN_PROGRESS
                    PlanState.DONE -> TaskState.DONE
                    PlanState.ABANDONED -> TaskState.ABANDONED
                    else -> TaskState.TODO
                },
            )
        }

        /**
         * 将 PlanNote 转换为 Plan
         */
        fun convertToPlan(planNote: PlanNote): Plan {
            val subtasks = planNote.subtasks.map { toSubTask(it) } ?: emptyList()
            return Plan(
                planNote.name,
                planNote.description,
                planNote.expectedOutcome,
                subtasks,
            )
        }

        private fun toPlanSubTask(subTask: SubTask): PlanSubTask = PlanSubTask(
            name = subTask.name ?: "",
            description = subTask.description ?: "",
            expectedOutcome = subTask.expectedOutcome ?: "",
            outcome = subTask.outcome ?: "",
            state = when (subTask.state) {
                SubTaskState.TODO -> TaskState.TODO
                SubTaskState.IN_PROGRESS -> TaskState.IN_PROGRESS
                SubTaskState.DONE -> TaskState.DONE
                SubTaskState.ABANDONED -> TaskState.ABANDONED
                else -> TaskState.TODO
            },
            createdAt = subTask.createdAt,
            finishedAt = subTask.finishedAt,
            costTimeSeconds = compareTime(subTask.createdAt, subTask.finishedAt),
        )

        fun toSubTask(planSubTask: PlanSubTask): SubTask {
            val subTask = SubTask(
                planSubTask.name,
                planSubTask.description,
                planSubTask.expectedOutcome,
            )
            subTask.state = when (planSubTask.state) {
                TaskState.TODO -> SubTaskState.TODO
                TaskState.IN_PROGRESS -> SubTaskState.IN_PROGRESS
                TaskState.DONE -> SubTaskState.DONE
                TaskState.ABANDONED -> SubTaskState.ABANDONED
            }
            subTask.outcome = planSubTask.outcome
            subTask.createdAt = planSubTask.createdAt
            subTask.finishedAt = planSubTask.finishedAt
            return subTask
        }

        private fun compareTime(time1: String?, time2: String?): Long = if (time1 == null || time2 == null) {
            0
        } else {
            try {
                val dateTime1 = LocalDateTime.parse(time1, dateTimeFormatter)
                val dateTime2 = LocalDateTime.parse(time2, dateTimeFormatter)
                java.time.Duration.between(dateTime1, dateTime2).seconds
            } catch (e: Exception) {
                log.warn("Failed to parse time strings: time1={}, time2={}", time1, time2, e)
                0
            }
        }
    }
}
