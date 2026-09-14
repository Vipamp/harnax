package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo

/**
 * Client that talks to the harnax-scheduler service
 *
 * All five methods are one call to one instance: start/pause/reload land in the shared Quartz JDBC store
 * and a trigger is a one-shot the shared execution guard dedups, so there is no per-node state left to
 * notify and posting to any reachable scheduling-enabled instance is posting to the whole cluster (an
 * inert one refuses the write with 40903 and changes nothing). [stopTask] is single on purpose instead.
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
}
