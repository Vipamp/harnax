package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.AgentTaskOwner
import com.agnetix.harnax.common.dto.ResultVo
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode

/**
 * Client that talks to the harnax-scheduler service
 *
 * All five write methods are one call to one instance: start/pause/reload land in the shared Quartz JDBC
 * store and a trigger is a one-shot the shared execution guard dedups, so there is no per-node state left
 * to notify and posting to any reachable scheduling-enabled instance is posting to the whole cluster (an
 * inert one refuses the write with 40903 and changes nothing). [stopTask] is single on purpose instead.
 * [taskOwner] is the one read, and it rides the same single instance and the same internal bearer.
 *
 * Since release 2 the scheduled-task domain itself crosses this boundary through the single generic
 * [forward]: admin authenticates the caller, names it, and relays. The five hand-written methods above stay
 * pointed at the older scheduling surface (the paths under `api/scheduler/tasks`), which still answers for
 * itself.
 */
interface SchedulerClient {

    /** Trigger a one-time task execution (one instance; the shared execution guard owns dedup) */
    fun triggerTask(id: Long): ResultVo<Void>

    /** Start scheduled task (one instance; the job lands in the shared store) */
    fun startTask(id: Long): ResultVo<Void>

    /** Pause scheduled task (one instance; the job leaves the shared store) */
    fun pauseTask(id: Long): ResultVo<Void>

    /** Reconcile the shared store with the task table (answers success only when that round converged) */
    fun reloadTasks(): ResultVo<Void>

    /** Stop a running task execution, by log id (one instance) */
    fun stopTask(logId: Long): ResultVo<Void>

    /**
     * Forward one scheduled-task request to the scheduler and hand the caller back exactly what it answered.
     *
     * This is the whole of what admin has left of that domain since release 2: authenticate, name the caller
     * in the two contract-C4 headers (stamped by this client, not by the caller — see
     * [com.agnetix.harnax.admin.service.impl.SchedulerClientImpl]), forward, relay.
     *
     * The body travels as a plain JSON value and the answer comes back as a [ResultVo] carrying a
     * [JsonNode], and that is deliberate: admin no longer owns the task DTOs, and a mirror copy of them here
     * would be a second definition of a contract the scheduler owns — the drift would stay invisible until a
     * client read a null. So the answer's tree goes out again untouched, which is what keeps the page
     * envelope's seven keys, a task record's eighteen fields, and a `lastRunStatus` that is present but null
     * exactly as the scheduler wrote them.
     *
     * A non-2xx from the scheduler is still an *answer*, not a transport failure: the bean-validation
     * refusal (400) and a contract-C4 refusal (401/403) both carry the `ResultVo` the client has to see, so
     * the body is read and relayed instead of being folded into "Scheduler service unavailable".
     *
     * @param path this service's own path, e.g. `/api/scheduler/agent-tasks/7/logs`
     * @param query query parameters; a null value is left out rather than sent empty, which is how an absent
     *   filter reaches the scheduler
     * @param body request body for the methods that have one
     */
    fun forward(
        method: HttpMethod,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: Any? = null,
    ): ResultVo<JsonNode>

    /**
     * Contract C5: whose task this is.
     *
     * The one read left on this boundary, and the only way admin can still name the human behind a
     * `task-…` session: `agent_task` is leaving its database with the rest of the scheduled-task domain,
     * and the agent id C1 encoded into the session id does not answer the question — a task's owner is
     * whoever created it, which is not an agent.
     *
     * Null [ResultVo.data] with code 200 means the scheduler has no such task, the same not-found shape
     * admin's own internal endpoints use. Anything else that goes wrong — scheduler down, a 5xx, a refused
     * business code — comes back as a non-200 [ResultVo] rather than an exception, because the caller is
     * [com.agnetix.harnax.admin.util.McpSessionOwnerResolver] resolving an OAuth MCP token in the middle
     * of somebody's execution, and the most that failure may cost that run is one tool.
     */
    fun taskOwner(id: Long): ResultVo<AgentTaskOwner?>
}
