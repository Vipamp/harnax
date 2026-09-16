package com.agnetix.harnax.scheduler.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Body of the task create call — the same fields the webui, the mini-program and the CLI have posted to
 * `harnax-admin` since release 0, plus one.
 *
 * [agentName] is that extra field, and it exists because `agent_task.agent_name` is a snapshot of a row
 * `harnax-admin` still owns: the agent table is not part of this domain move, so the scheduler cannot
 * resolve an id into a name any more. The alternative — this service calling admin for one string on every
 * create — would add a cross-service hop to a write path that today has none, which is exactly what the
 * plan's D4 decision rules out, so the caller hands the resolved name over instead. Admin fills it from its
 * own `AgentService.getAgent` before forwarding; a request that arrives without one is refused by the
 * service with admin's own "Agent not found" rather than storing an empty snapshot (contract C4's forwarding
 * headers carry a username, not an agent name, so nothing here could recover it later).
 */
@Schema(description = "Agent task create request")
data class AgentTaskCreateRequest(
    @Schema(description = "Task name", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Task name is required")
    @field:Size(max = 128, message = "Task name must not exceed 128 characters")
    val name: String = "",

    @Schema(description = "Agent ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "Agent ID is required")
    val agentId: Long? = null,

    @Schema(description = "Agent name snapshot, resolved by the caller from its own agent table")
    val agentName: String? = null,

    @Schema(description = "Prompt content", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Prompt is required")
    val prompt: String = "",

    @Schema(description = "Cron expression", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Cron expression is required")
    val cronExpression: String = "",

    @Schema(description = "Concurrent mode (0:no, 1:yes)")
    val concurrent: Int = 0,

    @Schema(description = "Timeout seconds")
    val timeoutSeconds: Int = 300,

    @Schema(description = "Description")
    @field:Size(max = 512, message = "Description must not exceed 512 characters")
    val description: String = "",

    @Schema(description = "Public status (0:no, 1:yes)")
    val isPublic: Int = 0,
)
