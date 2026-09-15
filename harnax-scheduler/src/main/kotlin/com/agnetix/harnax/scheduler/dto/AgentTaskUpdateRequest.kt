package com.agnetix.harnax.scheduler.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * Body of the task update call: every field optional, exactly as `harnax-admin`'s own update request had
 * it — a partial body must not null out the fields it does not name.
 *
 * [agentName] is the same cross-domain snapshot as on the create request (see
 * [AgentTaskCreateRequest.agentName] for why the caller resolves it): the scheduler has no agent table, and
 * adding a call to admin here would put a hop on a write path that has none today. It is only read when this
 * request actually moves the task to another agent; an update that keeps `agentId` keeps the stored name too.
 */
@Schema(description = "Agent task update request")
data class AgentTaskUpdateRequest(
    @Schema(description = "Task name")
    @field:Size(max = 128, message = "Task name must not exceed 128 characters")
    val name: String? = null,

    @Schema(description = "Agent ID")
    val agentId: Long? = null,

    @Schema(description = "Agent name snapshot, resolved by the caller from its own agent table")
    val agentName: String? = null,

    @Schema(description = "Prompt content")
    val prompt: String? = null,

    @Schema(description = "Cron expression")
    val cronExpression: String? = null,

    @Schema(description = "Concurrent mode (0:no, 1:yes)")
    val concurrent: Int? = null,

    @Schema(description = "Timeout seconds")
    val timeoutSeconds: Int? = null,

    @Schema(description = "Description")
    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String? = null,

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int? = null,
)
