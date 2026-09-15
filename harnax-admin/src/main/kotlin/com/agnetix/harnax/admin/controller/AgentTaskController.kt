package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * What is left of the scheduled-task domain in this service: the eleven endpoints the webui, the
 * mini-program and the CLI call, now answered by forwarding each one to `harnax-scheduler` and relaying what
 * it says. Release 2 moved the domain there — the `agent_task`, `agent_task_log` and `agent_task_execution`
 * tables included — and this class kept only the two things that cannot move with them:
 *
 *  - **authentication.** The bearer is checked by `JwtAuthenticationFilter` before a request reaches here, and
 *    the person it named travels on [SchedulerClient]'s two contract-C4 headers, stamped once for every call
 *    out of that client. No endpoint below reads a username itself: the scheduler owns the owner-scoped rules
 *    now, and it reads the caller from the forwarded identity.
 *  - **the agent-name snapshot**, which belongs to admin's own `agent` table and is stamped on the two bodies
 *    that can name an agent (see [withAgentName]).
 *
 * Nothing here interprets the answer. The [ResultVo] the scheduler returned — its business code, its message,
 * the page envelope's seven keys, a task record's eighteen fields — is what the client gets, byte for byte,
 * because those shapes are the contract three clients parse and this service no longer knows what is inside
 * them. That is also why the return types carry a [JsonNode] instead of a task DTO: the DTOs went out with the
 * domain, and a mirror copy here would be a second definition of someone else's contract.
 *
 * `GET /agents` is the exception, and stays as it was: listing the caller's agents is admin's own domain, and
 * the scheduler has no answer for it.
 */
@Tag(name = "Agent Task Management", description = "Scheduled agent task management APIs")
@RestController
@RequestMapping("/api/admin/agent-tasks")
class AgentTaskController(
    private val schedulerClient: SchedulerClient,
    private val agentService: AgentService,
) {

    private val log = LoggerFactory.getLogger(AgentTaskController::class.java)

    @Operation(summary = "Paginated agent task list")
    @GetMapping("/page")
    fun page(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) agentId: Long?,
        @RequestParam(required = false) taskStatus: Int?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<JsonNode> = try {
        schedulerClient.forward(
            HttpMethod.GET,
            "$TASK_API/page",
            queryOf("name" to name, "agentId" to agentId, "taskStatus" to taskStatus, "pageNum" to pageNum, "pageSize" to pageSize),
        )
    } catch (e: Exception) {
        log.error("Failed to query agent task list", e)
        ResultVo.error("Failed to query agent task list: ${e.message}")
    }

    @Operation(summary = "Get agent task by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<JsonNode> = try {
        // "Agent task not found" is the scheduler's answer now, not this class's: the row it looked for is
        // in that service's database.
        schedulerClient.forward(HttpMethod.GET, "$TASK_API/$id")
    } catch (e: Exception) {
        log.error("Failed to get agent task", e)
        ResultVo.error("Failed to get agent task: ${e.message}")
    }

    @Operation(summary = "Create agent task")
    @PostMapping
    fun create(@RequestBody request: JsonNode): ResultVo<JsonNode> = try {
        schedulerClient.forward(HttpMethod.POST, TASK_API, body = withAgentName(request))
    } catch (e: Exception) {
        log.error("Failed to create agent task", e)
        ResultVo.error("Failed to create agent task: ${e.message}")
    }

    @Operation(summary = "Update agent task")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody request: JsonNode,
    ): ResultVo<JsonNode> = try {
        schedulerClient.forward(HttpMethod.PUT, "$TASK_API/$id", body = withAgentName(request))
    } catch (e: BizException) {
        // Let the business code through: the generic handler below flattens everything to 500, and 40902
        // ("saved, but nothing reloaded") has to stay distinguishable from "not saved".
        log.warn("Failed to update agent task: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Update failed")
    } catch (e: Exception) {
        log.error("Failed to update agent task", e)
        ResultVo.error("Failed to update agent task: ${e.message}")
    }

    @Operation(summary = "Delete agent task")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<JsonNode> = try {
        schedulerClient.forward(HttpMethod.DELETE, "$TASK_API/$id")
    } catch (e: BizException) {
        log.warn("Failed to delete agent task: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Delete failed")
    } catch (e: Exception) {
        log.error("Failed to delete agent task", e)
        ResultVo.error("Failed to delete agent task: ${e.message}")
    }

    // ========================================
    // Scheduling endpoints
    // ========================================

    @Operation(summary = "Toggle task status (enable/disable)")
    @PostMapping("/toggle/{id}")
    fun toggle(
        @PathVariable id: Long,
        @RequestParam status: Int,
    ): ResultVo<JsonNode> = try {
        // The visibility gate that used to sit in front of this call moved with the data: the forwarded
        // endpoint reads the task through the caller identity in `X-Forwarded-User` and answers "Agent task
        // not found" itself, so relaying is the whole job here.
        schedulerClient.forward(HttpMethod.POST, "$TASK_API/toggle/$id", queryOf("status" to status))
    } catch (e: BizException) {
        // Business code through unchanged, same rule as update/delete: `toggle` is what the UI's status
        // switch calls, and flattening 40903 ("scheduling is disabled on this instance") into a 500 left the
        // operator with nothing to act on.
        log.warn("Failed to toggle task status: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to toggle task status")
    } catch (e: Exception) {
        log.error("Failed to toggle task status", e)
        ResultVo.error(e.message ?: "Failed to toggle task status")
    }

    @Operation(summary = "Start a scheduled task")
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<JsonNode> = schedulerClient.forward(HttpMethod.POST, "$TASK_API/$id/start")

    @Operation(summary = "Pause a scheduled task")
    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<JsonNode> = schedulerClient.forward(HttpMethod.POST, "$TASK_API/$id/pause")

    @Operation(summary = "Manually trigger a one-time task execution")
    @PostMapping("/{id}/trigger")
    fun trigger(@PathVariable id: Long): ResultVo<JsonNode> = schedulerClient.forward(HttpMethod.POST, "$TASK_API/$id/trigger")

    /**
     * The creator-only gate on a log id is the scheduler's now (it owns `agent_task_log`), which is also why
     * this no longer reads a table before forwarding. A stop stays on the task API rather than on the older
     * `api/scheduler/tasks` surface for the same reason as the four verbs above: that is the path whose
     * answer this endpoint has always handed out.
     */
    @Operation(summary = "Stop a running task execution")
    @PostMapping("/logs/{logId}/stop")
    fun stopTask(@PathVariable logId: Long): ResultVo<JsonNode> = schedulerClient.forward(HttpMethod.POST, "$TASK_API/logs/$logId/stop")

    @Operation(summary = "Get agent task execution logs")
    @GetMapping("/{id}/logs")
    fun logs(
        @PathVariable id: Long,
        @RequestParam(required = false) taskName: String?,
        @RequestParam(required = false) status: Int?,
        @RequestParam(required = false) startTimeFrom: String?,
        @RequestParam(required = false) startTimeTo: String?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<JsonNode> = try {
        // Seven filters in, seven filters out, unchanged: the path id is the task this list belongs to and the
        // rest are the log screen's own controls, which the scheduler applies to its own table.
        schedulerClient.forward(
            HttpMethod.GET,
            "$TASK_API/$id/logs",
            queryOf(
                "taskName" to taskName,
                "status" to status,
                "startTimeFrom" to startTimeFrom,
                "startTimeTo" to startTimeTo,
                "keyword" to keyword,
                "pageNum" to pageNum,
                "pageSize" to pageSize,
            ),
        )
    } catch (e: Exception) {
        log.error("Failed to query agent task logs", e)
        ResultVo.error("Failed to query agent task logs: ${e.message}")
    }

    @Operation(summary = "Get available agents list")
    @GetMapping("/agents")
    fun agents(): ResultVo<List<Map<String, Any?>>> = try {
        val agents = agentService.getActiveAgents()
        ResultVo.success(agents.map { mapOf("id" to it.id, "name" to it.name) })
    } catch (e: Exception) {
        log.error("Failed to query agents list", e)
        ResultVo.error("Failed to query agents list: ${e.message}")
    }

    /**
     * Stamp the agent name the task row carries, when the body names an agent at all.
     *
     * `agent_task.agent_name` is a snapshot of a row in *this* service's `agent` table, and that table did not
     * move to the scheduler with the task tables — so the id alone is no longer enough to fill the column. On
     * create, and on any update whose body carries an `agentId` (which is how a rename reaching a stored task
     * looks: the body names the agent it is moving to, and admin has to resolve it or a legal rename is
     * answered with "Agent not found"), the name is resolved here and handed over with the body.
     *
     * Two cases are forwarded as they came, deliberately:
     *
     *  - a body with no `agentId` (a prompt-only edit), where the stored snapshot stays stored;
     *  - an `agentId` this service has no agent for, where the scheduler's own refusal is the answer this
     *    endpoint has always given — repeating the sentence here would put a second copy of it in the stack.
     *
     * `agentId` is read leniently (`asLong` also takes a JSON string) because this method runs on the client's
     * raw body rather than on a typed DTO, and a numeric id sent as text used to reach the DTO as a number.
     */
    private fun withAgentName(request: JsonNode): JsonNode {
        val body = request as? ObjectNode ?: return request
        val agentId = body.path("agentId").asLong(0L)
        if (agentId == 0L) {
            return body
        }
        val agent = agentService.getAgent(agentId) ?: return body
        body.put("agentName", agent.name)
        return body
    }

    /**
     * Query parameters as [SchedulerClient.forward] wants them: stringified, and dropped when absent, which is
     * how "no filter chosen" reaches the other service (its own `@RequestParam(required = false)` binds the
     * missing key to null, exactly as this service's null did).
     */
    private fun queryOf(vararg pairs: Pair<String, Any?>): Map<String, String?> = pairs.associate { (name, value) -> name to value?.toString() }

    companion object {
        /** The scheduler's task surface; one path pattern, mirrored from the eleven endpoints below. */
        private const val TASK_API = "/api/scheduler/agent-tasks"
    }
}
