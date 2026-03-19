package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest;
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest;
import com.vipamp.vipclaw.admin.entity.SysJob;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.mapper.SysJobMapper;
import com.vipamp.vipclaw.admin.service.SysJobService;
import com.vipamp.vipclaw.admin.util.JwtUtil;
import com.vipamp.vipclaw.admin.util.UserContextUtil;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.quartz.JobDataMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 定时任务服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysJobServiceImpl extends ServiceImpl<SysJobMapper, SysJob> implements SysJobService {

    private final SchedulerFactoryBean schedulerFactoryBean;
    private final JwtUtil jwtUtil;

    /**
     * 获取 Scheduler 实例
     */
    private Scheduler getScheduler() {
        return schedulerFactoryBean.getScheduler();
    }

    @Override
    public Page<SysJob> getJobPage(@Nullable String keyword,
                                   @Nullable Integer jobStatus,
                                   Integer current,
                                   Integer size) {
        log.info("分页查询定时任务列表，current: {}, size: {}, keyword: {}, jobStatus: {}", current, size, keyword, jobStatus);

        Page<SysJob> page = new Page<>(current, size);
        LambdaQueryWrapper<SysJob> wrapper = new LambdaQueryWrapper<>();

        // 获取当前用户
        String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);
        
        // 权限过滤：只查询公开的或自己创建的
        wrapper.and(w -> w
                .eq(SysJob::getIsPublic, 1)
                .or()
                .eq(SysJob::getCreator, currentUsername)
        );

        // 模糊查询
        if (StringUtils.hasText(keyword)) {
            wrapper.like(SysJob::getJobName, keyword);
        }

        // 状态筛选
        if (jobStatus != null) {
            wrapper.eq(SysJob::getJobStatus, jobStatus);
        }

        // 强制校验 active 字段
        wrapper.eq(SysJob::getActive, 1);
        wrapper.orderByDesc(SysJob::getUpdateTime);
        return this.page(page, wrapper);
    }

    @Override
    public SysJob getJobById(Long id) {
        log.info("查询定时任务详情，id: {}", id);

        LambdaQueryWrapper<SysJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysJob::getId, id)
                .eq(SysJob::getActive, 1);
        wrapper.last("LIMIT 1");

        SysJob job = this.getOne(wrapper);
        if (job == null) {
            throw new BizException("定时任务不存在");
        }
        return job;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createJob(SysJobCreateRequest request) {
        log.info("创建定时任务，jobName: {}", request.getJobName());

        // 检查任务名称和组名是否已存在
        SysJob existJob = baseMapper.selectByNameAndGroup(request.getJobName(), 
                StringUtils.hasText(request.getJobGroup()) ? request.getJobGroup() : "DEFAULT");
        if (existJob != null) {
            throw new BizException("该任务名称和组名已存在");
        }

        // 验证 Cron 表达式
        if (!CronExpression.isValidExpression(request.getCronExpression())) {
            throw new BizException("Cron 表达式格式不正确");
        }

        SysJob job = new SysJob();
        job.setJobName(request.getJobName());
        job.setJobGroup(StringUtils.hasText(request.getJobGroup()) ? request.getJobGroup() : "DEFAULT");
        job.setJobClass(request.getJobClass());
        job.setCronExpression(request.getCronExpression());
        job.setJobStatus(0); // 默认暂停
        job.setConcurrent(request.getConcurrent() != null ? request.getConcurrent() : 1);
        job.setDescription(request.getDescription());
        job.setActive(1);
        
        // 设置创建人
        String currentUsername = UserContextUtil.getCurrentUsername(jwtUtil);
        job.setCreator(currentUsername);
        
        // 默认不公开
        if (job.getIsPublic() == null) {
            job.setIsPublic(0);
        }

        boolean success = this.save(job);
        log.info("定时任务创建{}，jobId: {}", success ? "成功" : "失败", job.getId());
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateJob(Long id, SysJobUpdateRequest request) {
        log.info("更新定时任务，id: {}", id);

        SysJob job = getJobById(id);

        // 如果修改了任务名称或组名，检查是否冲突
        if (!job.getJobName().equals(request.getJobName()) || 
            !job.getJobGroup().equals(StringUtils.hasText(request.getJobGroup()) ? request.getJobGroup() : "DEFAULT")) {
            SysJob existJob = baseMapper.selectByNameAndGroup(request.getJobName(),
                    StringUtils.hasText(request.getJobGroup()) ? request.getJobGroup() : "DEFAULT");
            if (existJob != null && !existJob.getId().equals(id)) {
                throw new BizException("该任务名称和组名已存在");
            }
        }

        // 验证 Cron 表达式
        if (!CronExpression.isValidExpression(request.getCronExpression())) {
            throw new BizException("Cron 表达式格式不正确");
        }

        // 如果任务正在运行，先停止
        if (job.getJobStatus() == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.getJobName(), job.getJobGroup()));
            } catch (SchedulerException e) {
                log.error("停止定时任务失败", e);
                throw new BizException("停止定时任务失败: " + e.getMessage());
            }
        }

        job.setJobName(request.getJobName());
        job.setJobGroup(StringUtils.hasText(request.getJobGroup()) ? request.getJobGroup() : "DEFAULT");
        job.setJobClass(request.getJobClass());
        job.setCronExpression(request.getCronExpression());
        job.setConcurrent(request.getConcurrent() != null ? request.getConcurrent() : 1);
        job.setDescription(request.getDescription());
        job.setJobStatus(0); // 更新后重置为暂停状态

        boolean success = this.updateById(job);
        log.info("定时任务更新{}，id: {}", success ? "成功" : "失败", id);
        return success;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteJob(Long id) {
        log.info("删除定时任务，id: {}", id);

        SysJob job = getJobById(id);

        // 如果任务正在运行，先停止
        if (job.getJobStatus() == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.getJobName(), job.getJobGroup()));
            } catch (SchedulerException e) {
                log.error("停止定时任务失败", e);
                throw new BizException("停止定时任务失败: " + e.getMessage());
            }
        }

        LambdaUpdateWrapper<SysJob> wrapper = new LambdaUpdateWrapper<>();
        wrapper.set(SysJob::getActive, 0)
                .eq(SysJob::getId, id)
                .eq(SysJob::getActive, 1);
        return this.update(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean startJob(Long id) {
        log.info("启动定时任务，id: {}", id);

        SysJob job = getJobById(id);

        if (job.getJobStatus() == 1) {
            throw new BizException("定时任务已经是运行状态");
        }

        try {
            scheduleJob(job);
            
            // 更新任务状态
            LambdaUpdateWrapper<SysJob> wrapper = new LambdaUpdateWrapper<>();
            wrapper.set(SysJob::getJobStatus, 1)
                    .eq(SysJob::getId, id)
                    .eq(SysJob::getActive, 1);
            return this.update(wrapper);
        } catch (Exception e) {
            log.error("启动定时任务失败", e);
            throw new BizException("启动定时任务失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean pauseJob(Long id) {
        log.info("暂停定时任务，id: {}", id);

        SysJob job = getJobById(id);

        if (job.getJobStatus() == 0) {
            throw new BizException("定时任务已经是暂停状态");
        }

        try {
            getScheduler().pauseJob(JobKey.jobKey(job.getJobName(), job.getJobGroup()));
            getScheduler().deleteJob(JobKey.jobKey(job.getJobName(), job.getJobGroup()));
            
            // 更新任务状态
            LambdaUpdateWrapper<SysJob> wrapper = new LambdaUpdateWrapper<>();
            wrapper.set(SysJob::getJobStatus, 0)
                    .eq(SysJob::getId, id)
                    .eq(SysJob::getActive, 1);
            return this.update(wrapper);
        } catch (SchedulerException e) {
            log.error("暂停定时任务失败", e);
            throw new BizException("暂停定时任务失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean runJobOnce(Long id) {
        log.info("立即执行定时任务，id: {}", id);

        SysJob job = getJobById(id);

        try {
            // 创建临时任务立即执行
            JobKey jobKey = JobKey.jobKey(job.getJobName() + "_ONCE", job.getJobGroup());
            
            // 先删除可能存在的旧任务
            if (getScheduler().checkExists(jobKey)) {
                getScheduler().deleteJob(jobKey);
            }
            
            // 创建 JobDetail
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put("sysJob", job);
            
            JobDetail jobDetail = JobBuilder.newJob(getJobClass(job.getJobClass()))
                    .withIdentity(jobKey)
                    .usingJobData(jobDataMap)
                    .build();

            // 创建立即执行的 Trigger
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(job.getJobName() + "_ONCE_TRIGGER", job.getJobGroup())
                    .startNow()
                    .build();

            getScheduler().scheduleJob(jobDetail, trigger);
            return true;
        } catch (Exception e) {
            log.error("立即执行定时任务失败", e);
            throw new BizException("立即执行定时任务失败: " + e.getMessage());
        }
    }

    @Override
    public void loadJobsToScheduler() {
        log.info("加载所有运行中的定时任务到调度器");
        
        List<SysJob> runningJobs = getRunningJobs();
        for (SysJob job : runningJobs) {
            try {
                scheduleJob(job);
                log.info("已加载定时任务: {}.{}", job.getJobGroup(), job.getJobName());
            } catch (Exception e) {
                log.error("加载定时任务失败: {}.{}", job.getJobGroup(), job.getJobName(), e);
            }
        }
        log.info("共加载 {} 个定时任务", runningJobs.size());
    }

    @Override
    public List<SysJob> getRunningJobs() {
        return baseMapper.selectRunningJobs();
    }

    /**
     * 调度定时任务
     *
     * @param job 定时任务实体
     * @throws Exception 调度异常
     */
    private void scheduleJob(SysJob job) throws Exception {
        // 获取任务类
        Class<? extends Job> jobClass = getJobClass(job.getJobClass());

        // 构建 JobDetail
        JobKey jobKey = JobKey.jobKey(job.getJobName(), job.getJobGroup());
        
        // 如果任务已存在，先删除
        if (getScheduler().checkExists(jobKey)) {
            getScheduler().deleteJob(jobKey);
        }

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("sysJob", job);
        
        JobDetail jobDetail = JobBuilder.newJob(jobClass)
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .build();

        // 构建 CronTrigger
        CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.getCronExpression());
        
        // 根据并发设置
        if (job.getConcurrent() != null && job.getConcurrent() == 0) {
            // 禁止并发执行
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionDoNothing();
        } else {
            // 允许并发执行
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionFireAndProceed();
        }

        CronTrigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(job.getJobName() + "_TRIGGER", job.getJobGroup())
                .withSchedule(cronScheduleBuilder)
                .build();

        getScheduler().scheduleJob(jobDetail, trigger);
    }

    /**
     * 获取任务类
     *
     * @param className 类全路径名
     * @return Job 类
     * @throws ClassNotFoundException 类不存在异常
     */
    @SuppressWarnings("unchecked")
    private Class<? extends Job> getJobClass(String className) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className);
        if (!Job.class.isAssignableFrom(clazz)) {
            throw new BizException("任务类必须实现 org.quartz.Job 接口");
        }
        return (Class<? extends Job>) clazz;
    }

    /**
     * 应用启动后加载所有运行中的任务
     */
    @PostConstruct
    public void init() {
        try {
            // 等待调度器初始化完成
            Thread.sleep(3000);
            loadJobsToScheduler();
        } catch (Exception e) {
            log.error("初始化定时任务失败", e);
        }
    }
}
