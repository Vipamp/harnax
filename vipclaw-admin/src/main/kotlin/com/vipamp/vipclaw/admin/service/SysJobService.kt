package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysJob

/**
 * 定时任务服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
interface SysJobService {

    /**
     * 分页查询定时任务列表
     *
     * @param keyword    模糊查询字段（任务名称）
     * @param jobStatus  状态筛选字段
     * @param current    当前页码
     * @param size       每页大小
     * @return 分页结果
     */
    fun getJobPage(keyword: String?, jobStatus: Int?, current: Int, size: Int): Page<SysJob>

    /**
     * 获取单个定时任务详情
     *
     * @param id 任务 ID
     * @return 任务实体
     */
    fun getJobById(id: Long): SysJob

    /**
     * 创建定时任务
     *
     * @param request 任务创建请求对象
     * @return 创建结果
     */
    fun createJob(request: SysJobCreateRequest): Boolean

    /**
     * 更新定时任务
     *
     * @param id      任务 ID
     * @param request 任务更新请求对象
     * @return 更新结果
     */
    fun updateJob(id: Long, request: SysJobUpdateRequest): Boolean

    /**
     * 删除定时任务
     *
     * @param id 任务 ID
     * @return 删除结果
     */
    fun deleteJob(id: Long): Boolean

    /**
     * 启动定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    fun startJob(id: Long): Boolean

    /**
     * 暂停定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    fun pauseJob(id: Long): Boolean

    /**
     * 立即执行一次定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    fun runJobOnce(id: Long): Boolean

    /**
     * 从数据库加载所有运行中的任务到调度器
     */
    fun loadJobsToScheduler(): Unit

    /**
     * 获取所有运行中的任务
     *
     * @return 任务列表
     */
    fun getRunningJobs(): List<SysJob>
}
