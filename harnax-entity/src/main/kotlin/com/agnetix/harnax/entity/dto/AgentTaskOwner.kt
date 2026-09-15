package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Who one scheduled task belongs to, as contract C5 answers it.
 *
 * Lives here rather than in scheduler's own dto package because it is a wire contract between two
 * services: scheduler reads the `agent_task` row and mints it on
 * `GET /api/scheduler/agent-tasks/{id}/owner`, admin reads it back in its `McpSessionOwnerResolver` —
 * which cannot do the read itself for long, since release 2 is moving `agent_task` into the scheduler's
 * own database.
 * Same reason [AgentSpecInfoResponse] and
 * [McpAccessTokenResponse] sit in this package.
 *
 * The three fields are exactly what the resolution needs and nothing more. `agentId` is along for the
 * ride because the row carries it and a caller that already has a task session id may confirm it, but
 * the reason this endpoint exists is [creator] + [tenantId]: the task's *owner* is a different person
 * than the agent it runs, and C1 put only the agent id into the session id.
 *
 * Plain Kotlin properties with `@Schema`, like every other DTO on a service boundary here: each side
 * runs `jackson-module-kotlin`, so no `@JsonProperty` is needed and none is wanted — an annotation per
 * field is one more way for the two sides to say different things about the same payload.
 */
@Schema(description = "Ownership answer for one scheduled task (contract C5)")
data class AgentTaskOwner(
    @Schema(description = "Task creator, i.e. the person the task runs as: a username, or the numeric sys_user.id a mini-program wrote")
    val creator: String,

    @Schema(description = "Tenant the task row belongs to")
    val tenantId: Long,

    @Schema(description = "Agent the task runs")
    val agentId: Long,
)
