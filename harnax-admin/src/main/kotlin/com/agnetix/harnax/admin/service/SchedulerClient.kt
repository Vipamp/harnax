package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.dto.AgentTaskOwner

/**
 * Client that talks to the harnax-scheduler service
 *
 * All five write methods are one call to one instance: start/pause/reload land in the shared Quartz JDBC
 * store and a trigger is a one-shot the shared execution guard dedups, so there is no per-node state left
 * to notify and posting to any reachable scheduling-enabled instance is posting to the whole cluster (an
 * inert one refuses the write with 40903 and changes nothing). [stopTask] is single on purpose instead.
 * [taskOwner] is the one read, and it rides the same single instance and the same internal bearer.
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
