package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo

/**
 * 与 harnax-scheduler 服务通信的客户端接口
 *
 * 五个方法都是一次调用、一个实例：调度状态存在共享的 Quartz JDBC store 里，已经没有"某个节点自己持有、
 * 需要逐个通知"的状态，所以发给任意一个可达实例就等于发给全集群。
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
