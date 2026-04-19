package com.vipamp.vipclaw.ascopagent.adaptor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.vipamp.vipclaw.admin.entity.PlanNoteEntity
import com.vipamp.vipclaw.admin.mapper.PlanNoteMapper
import com.vipamp.vipclaw.agent.adaptor.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils

/**
 * PlanNoteAdaptor 实现类
 * 从数据库加载和保存 PlanNote
 *
 * @author vipamp
 * @since 2026-04-16
 */
@Component
class PlanNoteAdaptorImpl(
    private val planNoteMapper: PlanNoteMapper,
    private val objectMapper: ObjectMapper
) : PlanNoteAdaptor {

    private val log = LoggerFactory.getLogger(PlanNoteAdaptorImpl::class.java)

    // 创建支持 Kotlin 的 ObjectMapper
    private val kotlinObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    override fun save(planNote: PlanNote) {
        try {
            // 转换为实体
            val entity = convertToEntity(planNote)

            // 保存到数据库
            val result = planNoteMapper.insert(entity)

            if (result > 0) {
                log.info("PlanNote saved successfully: sessionId={}, planId={}, name={}",
                    planNote.sessionId, planNote.planId, planNote.name)
            } else {
                log.warn("Failed to save PlanNote: sessionId={}, planId={}",
                    planNote.sessionId, planNote.planId)
            }
        } catch (e: Exception) {
            log.error("Error saving PlanNote: sessionId={}, planId={}",
                planNote.sessionId, planNote.planId, e)
        }
    }

    override fun getPlanNote(sessionId: String, planId: String): PlanNote? {
        return try {
            val entity = planNoteMapper.selectBySessionIdAndPlanId(sessionId, planId)

            if (entity == null) {
                log.warn("PlanNote not found: sessionId=$sessionId, planId=$planId")
                return null
            }

            convertToDomain(entity)
        } catch (e: Exception) {
            log.error("Error getting PlanNote: sessionId=$sessionId, planId=$planId", e)
            null
        }
    }

    override fun getPlanNotes(sessionId: String): List<PlanNote> {
        return try {
            val entities = planNoteMapper.selectBySessionId(sessionId)

            entities.map { convertToDomain(it) }
        } catch (e: Exception) {
            log.error("Error getting PlanNotes: sessionId=$sessionId", e)
            emptyList()
        }
    }

    override fun deletePlan(sessionId: String) {
        try {
            val result = planNoteMapper.deleteBySessionId(sessionId)

            if (result > 0) {
                log.info("PlanNotes deleted successfully: sessionId=$sessionId, count=$result")
            } else {
                log.warn("No PlanNotes found to delete: sessionId=$sessionId")
            }
        } catch (e: Exception) {
            log.error("Error deleting PlanNotes: sessionId=$sessionId", e)
            throw e
        }
    }

    /**
     * 将 PlanNote 领域对象转换为实体
     */
    @Throws(JsonProcessingException::class)
    private fun convertToEntity(planNote: PlanNote): PlanNoteEntity {
        val entity = PlanNoteEntity()
        entity.sessionId = planNote.sessionId
        entity.planId = planNote.planId
        entity.name = planNote.name
        entity.description = planNote.description
        entity.expectedOutcome = planNote.expectedOutcome

        // 将子任务列表转换为 JSON 字符串
        if (planNote.subtasks.isNotEmpty()) {
            entity.subtasks = kotlinObjectMapper.writeValueAsString(planNote.subtasks)
        }

        entity.createdAt = planNote.createdAt
        entity.finishedAt = planNote.finishedAt
        entity.costTimeseconds = planNote.costTimeSeconds

        // 设置状态
        entity.status = planNote.status.name

        return entity
    }

    /**
     * 将实体转换为 PlanNote 领域对象
     */
    private fun convertToDomain(entity: PlanNoteEntity): PlanNote {
        var subtasks: List<PlanSubTask>? = null

        if (StringUtils.hasText(entity.subtasks)) {
            try {
                subtasks = kotlinObjectMapper.readValue(
                    entity.subtasks,
                    object : TypeReference<List<PlanSubTask>>() {}
                )
            } catch (e: JsonProcessingException) {
                log.error("Failed to parse subtasks JSON for planId: ${entity.planId}", e)
                subtasks = emptyList()
            }
        }

        return PlanNote(
            entity.sessionId,
            entity.planId,
            entity.name,
            entity.description,
            entity.expectedOutcome,
            subtasks?.toList() ?: emptyList(),
            entity.createdAt,
            entity.finishedAt,
            entity.costTimeseconds ?: 0L,
            if (entity.status != null) TaskState.valueOf(entity.status) else TaskState.TODO
        )
    }
}
