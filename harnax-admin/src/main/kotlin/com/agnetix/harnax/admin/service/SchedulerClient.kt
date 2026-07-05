package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo

/**
 * 与 harnax-scheduler 服务通信的客户端接口
 */
interface SchedulerClient {

    /** Trigger a one-time task execution (sent to one instance only) */
    fun triggerTask(id: Long): ResultVo<Void>

    /** Start scheduled task (broadcast to all instances) */
    fun startTask(id: Long): ResultVo<Void>

    /** Pause scheduled task (broadcast to all instances) */
    fun pauseTask(id: Long): ResultVo<Void>

    /** Reload all tasks from DB into Quartz (broadcast to all instances) */
    fun reloadTasks(): ResultVo<Void>

    /** Stop a running task execution (broadcast to all instances, only the running one will act) */
    fun stopTask(logId: Long): ResultVo<Void>
}
