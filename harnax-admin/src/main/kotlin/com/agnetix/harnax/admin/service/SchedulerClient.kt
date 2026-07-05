package com.agnetix.harnax.admin.service

import com.agnetix.harnax.common.dto.ResultVo

/**
 * 与 harnax-scheduler 服务通信的客户端接口
 */
interface SchedulerClient {

    /** 触发一次性任务执行 */
    fun triggerTask(id: Long): ResultVo<Void>

    /** 启动定时任务调度 */
    fun startTask(id: Long): ResultVo<Void>

    /** 暂停定时任务调度 */
    fun pauseTask(id: Long): ResultVo<Void>
}
