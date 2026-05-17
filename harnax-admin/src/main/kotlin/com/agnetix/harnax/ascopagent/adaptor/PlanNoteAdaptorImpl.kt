package com.agnetix.harnax.ascopagent.adaptor

import com.agnetix.harnax.admin.entity.PlanNoteEntity
import com.agnetix.harnax.admin.mapper.PlanNoteMapper
import com.agnetix.harnax.agent.adaptor.*
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils

/**
 * PlanNoteAdaptor Implementation
 * Loads and saves PlanNote from/to database
 */
@Component
class PlanNoteAdaptorImpl(
    private val planNoteMapper: PlanNoteMapper,
    private val objectMapper: ObjectMapper,
) : PlanNoteAdaptor {

    private val log = LoggerFactory.getLogger(PlanNoteAdaptorImpl::class.java)

    // Create ObjectMapper with Kotlin support
    private val kotlinObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())

    override fun save(planNote: PlanNote) {
        try {
            // Convert to entity
            val entity = convertToEntity(planNote)

            // Save to database
            val result = planNoteMapper.insert(entity)

            if (result > 0) {
                log.info(
                    "PlanNote saved successfully: sessionId={}, planId={}, name={}",
                    planNote.sessionId,
                    planNote.planId,
                    planNote.name,
                )
            } else {
                log.warn(
                    "Failed to save PlanNote: sessionId={}, planId={}",
                    planNote.sessionId,
                    planNote.planId,
                )
            }
        } catch (e: Exception) {
            log.error(
                "Error saving PlanNote: sessionId={}, planId={}",
                planNote.sessionId,
                planNote.planId,
                e,
            )
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

    override fun getPlanNotes(sessionId: String): List<PlanNote> = try {
        val entities = planNoteMapper.selectBySessionId(sessionId)

        entities.map { convertToDomain(it) }
    } catch (e: Exception) {
        log.error("Error getting PlanNotes: sessionId=$sessionId", e)
        emptyList()
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
     * Convert PlanNote domain object to entity
     */
    @Throws(JsonProcessingException::class)
    private fun convertToEntity(planNote: PlanNote): PlanNoteEntity {
        val entity = PlanNoteEntity()
        entity.sessionId = planNote.sessionId
        entity.planId = planNote.planId
        entity.name = planNote.name
        entity.description = planNote.description
        entity.expectedOutcome = planNote.expectedOutcome

        // Convert subtask list to JSON string
        if (planNote.subtasks.isNotEmpty()) {
            entity.subtasks = kotlinObjectMapper.writeValueAsString(planNote.subtasks)
        }

        entity.createdAt = planNote.createdAt
        entity.finishedAt = planNote.finishedAt
        entity.costTimeseconds = planNote.costTimeSeconds

        // Set status
        entity.status = planNote.status.name

        return entity
    }

    /**
     * Convert entity to PlanNote domain object
     */
    private fun convertToDomain(entity: PlanNoteEntity): PlanNote {
        var subtasks: List<PlanSubTask>? = null

        if (StringUtils.hasText(entity.subtasks)) {
            try {
                subtasks = kotlinObjectMapper.readValue(
                    entity.subtasks,
                    object : TypeReference<List<PlanSubTask>>() {},
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
            if (entity.status != null) TaskState.valueOf(entity.status) else TaskState.TODO,
        )
    }
}
