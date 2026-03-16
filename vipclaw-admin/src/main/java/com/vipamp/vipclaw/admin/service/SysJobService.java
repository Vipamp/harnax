package com.vipamp.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest;
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest;
import com.vipamp.vipclaw.admin.entity.SysJob;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 定时任务服务接口
 *
 * @author vipamp
 * @since 2026-03-16
 */
public interface SysJobService extends IService<SysJob> {

    /**
     * 分页查询定时任务列表
     *
     * @param keyword    模糊查询字段（任务名称）
     * @param jobStatus  状态筛选字段
     * @param current    当前页码
     * @param size       每页大小
     * @return 分页结果
     */
    Page<SysJob> getJobPage(@Nullable String keyword, @Nullable Integer jobStatus, Integer current, Integer size);

    /**
     * 获取单个定时任务详情
     *
     * @param id 任务 ID
     * @return 任务实体
     */
    SysJob getJobById(Long id);

    /**
     * 创建定时任务
     *
     * @param request 任务创建请求对象
     * @return 创建结果
     */
    boolean createJob(SysJobCreateRequest request);

    /**
     * 更新定时任务
     *
     * @param id      任务 ID
     * @param request 任务更新请求对象
     * @return 更新结果
     */
    boolean updateJob(Long id, SysJobUpdateRequest request);

    /**
     * 删除定时任务
     *
     * @param id 任务 ID
     * @return 删除结果
     */
    boolean deleteJob(Long id);

    /**
     * 启动定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    boolean startJob(Long id);

    /**
     * 暂停定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    boolean pauseJob(Long id);

    /**
     * 立即执行一次定时任务
     *
     * @param id 任务 ID
     * @return 操作结果
     */
    boolean runJobOnce(Long id);

    /**
     * 从数据库加载所有运行中的任务到调度器
     */
    void loadJobsToScheduler();

    /**
     * 获取所有运行中的任务
     *
     * @return 任务列表
     */
    List<SysJob> getRunningJobs();
}
