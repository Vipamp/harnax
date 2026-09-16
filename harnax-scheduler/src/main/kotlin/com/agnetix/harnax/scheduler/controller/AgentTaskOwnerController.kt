package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.common.dto.AgentTaskOwner
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Contract C5: who a scheduled task belongs to.
 *
 * Its own controller instead of a method on [SchedulerController], for two reasons. That class is the
 * scheduling write surface — every endpoint on it goes through `requireEnabled`, because registering a
 * job on a node configured to stay inert would start work running there while the caller had already been
 * answered 200. This is a plain read of one row, exactly as correct on an inert node as on a live one, so
 * putting it there would either put it behind a gate that has no business refusing it or add a second
 * exception to that gate's rule. And the task CRUD endpoints release 2 adds next are a `/agent-tasks`
 * surface of their own, which this path already belongs to.
 *
 * It is also the only thing release 2 could not take off admin. `agent_task` leaves admin's database with
 * the rest of this domain, but `McpSessionOwnerResolver.fromTask` still needs the task's `creator` and
 * `tenantId` to work out which human owns an OAuth MCP token for a task session — and the agent id C1 put
 * into the session id does not answer that, because the person who created a task is not the agent it
 * runs. That read is a cold path: it happens only when an agent builds an OAuth MCP client, never on the
 * spec lookup every message goes through.
 */
@Tag(name = "Agent Task Owner", description = "Service-to-service ownership read for scheduled tasks (contract C5)")
@RestController
@RequestMapping("/api/scheduler/agent-tasks")
class AgentTaskOwnerController(
    private val agentTaskMapper: AgentTaskMapper,
) {

    private val log = LoggerFactory.getLogger(AgentTaskOwnerController::class.java)

    /**
     * @param id the task id, i.e. the first segment of a `task-…` session id
     * @return the three values the row holds, or a successful answer with no data when there is no such
     *   row — the same shape admin's own internal lookups use for "not found", so the caller can tell a
     *   missing task from an unreachable service without parsing a message.
     */
    @Operation(summary = "Read a task's owner (creator, tenant, agent) for another service")
    @GetMapping("/{id}/owner")
    fun owner(@PathVariable id: Long): ResultVo<AgentTaskOwner?> {
        // Unscoped on purpose: the caller is a service with no end-user context, and `selectById` filters
        // by visibility to a named user, which would answer "no such task" for tasks that exist.
        val task = agentTaskMapper.selectAnyById(id)
            ?: run {
                log.debug("No agent_task row with id {}, so C5 has no owner to name", id)
                return ResultVo.success(null)
            }
        return ResultVo.success(
            AgentTaskOwner(
                creator = task.creator,
                tenantId = task.tenantId,
                agentId = task.agentId,
            ),
        )
    }
}
