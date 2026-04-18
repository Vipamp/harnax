package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.SysJobCreateRequest
import com.vipamp.vipclaw.admin.dto.SysJobUpdateRequest
import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.service.SysJobService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.quartz.*
import org.quartz.JobDataMap
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils.hasText
import jakarta.annotation.PostConstruct

/**
 * 定时任务服务实现类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Service
class SysJobServiceImpl(
    private val schedulerFactoryBean: SchedulerFactoryBean,
    private val jwtUtil: JwtUtil
) : ServiceImpl<com.vipamp.vipclaw.admin.mapper.SysJobMapper, SysJob>(), SysJobService {

    private val log = LoggerFactory.getLogger(SysJobServiceImpl::class.java)

    /**
     * 获取 Scheduler 实例
     */
    private fun getScheduler(): Scheduler {
        return schedulerFactoryBean.scheduler
    }

    override fun getJobPage(
        keyword: String?,
        jobStatus: Int?,
        current: Int,
        size: Int
    ): Page<SysJob> {
        log.info("分页查询定时任务列表，current: {}, size: {}, keyword: {}, jobStatus: {}", current, size, keyword, jobStatus)

        val page = Page<SysJob>(current.toLong(), size.toLong())
        val wrapper = LambdaQueryWrapper<SysJob>()

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 权限过滤：只查询公开的或自己创建的
        wrapper.and { w ->
            w.eq(SysJob::isPublic, 1)
                .or()
                .eq(SysJob::creator, currentUsername)
        }

        // 模糊查询
        if (hasText(keyword)) {
            wrapper.like(SysJob::jobName, keyword)
        }

        // 状态筛选
        jobStatus?.let { wrapper.eq(SysJob::jobStatus, it) }

        // 强制校验 active 字段
        wrapper.eq(SysJob::active, 1)
        wrapper.orderByDesc(SysJob::updateTime)
        return this.page(page, wrapper)
    }

    override fun getJobById(id: Long): SysJob {
        log.info("查询定时任务详情，id: {}", id)

        val wrapper = LambdaQueryWrapper<SysJob>()
        wrapper.eq(SysJob::id, id)
            .eq(SysJob::active, 1)
        wrapper.last("LIMIT 1")

        val job = this.getOne(wrapper)
            ?: throw BizException("定时任务不存在")
        return job
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun createJob(request: SysJobCreateRequest): Boolean {
        log.info("创建定时任务，jobName: {}", request.jobName)

        // 检查任务名称和组名是否已存在
        val existJob = baseMapper.selectByNameAndGroup(
            request.jobName,
            if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT"
        )
        if (existJob != null) {
            throw BizException("该任务名称和组名已存在")
        }

        // 验证 Cron 表达式
        if (!CronExpression.isValidExpression(request.cronExpression)) {
            throw BizException("Cron 表达式格式不正确")
        }

        val job = SysJob()
        job.jobName = request.jobName
        job.jobGroup = if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT"
        job.jobClass = request.jobClass
        job.cronExpression = request.cronExpression
        job.jobStatus = 0 // 默认暂停
        job.concurrent = request.concurrent ?: 1
        job.description = request.description
        job.active = 1

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        job.creator = currentUsername

        // 默认不公开
        if (job.isPublic == null) {
            job.isPublic = 0
        }

        val success = this.save(job)
        log.info("定时任务创建{}，jobId: {}", if (success) "成功" else "失败", job.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateJob(id: Long, request: SysJobUpdateRequest): Boolean {
        log.info("更新定时任务，id: {}", id)

        val job = getJobById(id)

        // 如果修改了任务名称或组名，检查是否冲突
        if (job.jobName != request.jobName ||
            job.jobGroup != (if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT")
        ) {
            val existJob = baseMapper.selectByNameAndGroup(
                request.jobName,
                if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT"
            )
            if (existJob != null && existJob.id != id) {
                throw BizException("该任务名称和组名已存在")
            }
        }

        // 验证 Cron 表达式
        if (!CronExpression.isValidExpression(request.cronExpression)) {
            throw BizException("Cron 表达式格式不正确")
        }

        // 如果任务正在运行，先停止
        if (job.jobStatus == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))
            } catch (e: SchedulerException) {
                log.error("停止定时任务失败", e)
                throw BizException("停止定时任务失败: ${e.message}")
            }
        }

        job.jobName = request.jobName
        job.jobGroup = if (hasText(request.jobGroup)) request.jobGroup else "DEFAULT"
        job.jobClass = request.jobClass
        job.cronExpression = request.cronExpression
        job.concurrent = request.concurrent ?: 1
        job.description = request.description
        job.jobStatus = 0 // 更新后重置为暂停状态

        val success = this.updateById(job)
        log.info("定时任务更新{}，id: {}", if (success) "成功" else "失败", id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteJob(id: Long): Boolean {
        log.info("删除定时任务，id: {}", id)

        val job = getJobById(id)

        // 如果任务正在运行，先停止
        if (job.jobStatus == 1) {
            try {
                getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))
            } catch (e: SchedulerException) {
                log.error("停止定时任务失败", e)
                throw BizException("停止定时任务失败: ${e.message}")
            }
        }

        val wrapper = LambdaUpdateWrapper<SysJob>()
        wrapper.set(SysJob::active, 0)
            .eq(SysJob::id, id)
            .eq(SysJob::active, 1)
        return this.update(wrapper)
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun startJob(id: Long): Boolean {
        log.info("启动定时任务，id: {}", id)

        val job = getJobById(id)

        if (job.jobStatus == 1) {
            throw BizException("定时任务已经是运行状态")
        }

        return try {
            scheduleJob(job)

            // 更新任务状态
            val wrapper = LambdaUpdateWrapper<SysJob>()
            wrapper.set(SysJob::jobStatus, 1)
                .eq(SysJob::id, id)
                .eq(SysJob::active, 1)
            this.update(wrapper)
        } catch (e: Exception) {
            log.error("启动定时任务失败", e)
            throw BizException("启动定时任务失败: ${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun pauseJob(id: Long): Boolean {
        log.info("暂停定时任务，id: {}", id)

        val job = getJobById(id)

        if (job.jobStatus == 0) {
            throw BizException("定时任务已经是暂停状态")
        }

        return try {
            getScheduler().pauseJob(JobKey.jobKey(job.jobName, job.jobGroup))
            getScheduler().deleteJob(JobKey.jobKey(job.jobName, job.jobGroup))

            // 更新任务状态
            val wrapper = LambdaUpdateWrapper<SysJob>()
            wrapper.set(SysJob::jobStatus, 0)
                .eq(SysJob::id, id)
                .eq(SysJob::active, 1)
            this.update(wrapper)
        } catch (e: SchedulerException) {
            log.error("暂停定时任务失败", e)
            throw BizException("暂停定时任务失败: ${e.message}")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun runJobOnce(id: Long): Boolean {
        log.info("立即执行定时任务，id: {}", id)

        val job = getJobById(id)

        return try {
            // 创建临时任务立即执行
            val jobKey = JobKey.jobKey(job.jobName + "_ONCE", job.jobGroup)

            // 先删除可能存在的旧任务
            if (getScheduler().checkExists(jobKey)) {
                getScheduler().deleteJob(jobKey)
            }

            // 创建 JobDetail
            val jobDataMap = JobDataMap()
            jobDataMap.put("sysJob", job)

            val jobDetail = JobBuilder.newJob(getJobClass(job.jobClass))
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .build()

            // 创建立即执行的 Trigger
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(job.jobName + "_ONCE_TRIGGER", job.jobGroup)
                .startNow()
                .build()

            getScheduler().scheduleJob(jobDetail, trigger)
            true
        } catch (e: Exception) {
            log.error("立即执行定时任务失败", e)
            throw BizException("立即执行定时任务失败: ${e.message}")
        }
    }

    override fun loadJobsToScheduler() {
        log.info("加载所有运行中的定时任务到调度器")

        val runningJobs = getRunningJobs()
        for (job in runningJobs) {
            try {
                scheduleJob(job)
                log.info("已加载定时任务: {}.{}", job.jobGroup, job.jobName)
            } catch (e: Exception) {
                log.error("加载定时任务失败: {}.{}", job.jobGroup, job.jobName, e)
            }
        }
        log.info("共加载 {} 个定时任务", runningJobs.size)
    }

    override fun getRunningJobs(): List<SysJob> {
        return baseMapper.selectRunningJobs()
    }

    /**
     * 调度定时任务
     *
     * @param job 定时任务实体
     * @throws Exception 调度异常
     */
    @Throws(Exception::class)
    private fun scheduleJob(job: SysJob) {
        // 获取任务类
        val jobClass = getJobClass(job.jobClass)

        // 构建 JobDetail
        val jobKey = JobKey.jobKey(job.jobName, job.jobGroup)

        // 如果任务已存在，先删除
        if (getScheduler().checkExists(jobKey)) {
            getScheduler().deleteJob(jobKey)
        }

        val jobDataMap = JobDataMap()
        jobDataMap.put("sysJob", job)

        val jobDetail = JobBuilder.newJob(jobClass)
            .withIdentity(jobKey)
            .usingJobData(jobDataMap)
            .build()

        // 构建 CronTrigger
        var cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.cronExpression)

        // 根据并发设置
        if (job.concurrent != null && job.concurrent == 0) {
            // 禁止并发执行
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionDoNothing()
        } else {
            // 允许并发执行
            cronScheduleBuilder = cronScheduleBuilder.withMisfireHandlingInstructionFireAndProceed()
        }

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(job.jobName + "_TRIGGER", job.jobGroup)
            .withSchedule(cronScheduleBuilder)
            .build()

        getScheduler().scheduleJob(jobDetail, trigger)
    }

    /**
     * 获取任务类
     *
     * @param className 类全路径名
     * @return Job 类
     * @throws ClassNotFoundException 类不存在异常
     */
    @Throws(ClassNotFoundException::class)
    @Suppress("UNCHECKED_CAST")
    private fun getJobClass(className: String): Class<out Job> {
        val clazz = Class.forName(className)
        if (!Job::class.java.isAssignableFrom(clazz)) {
            throw BizException("任务类必须实现 org.quartz.Job 接口")
        }
        return clazz as Class<out Job>
    }

    /**
     * 应用启动后加载所有运行中的任务
     */
    @PostConstruct
    fun init() {
        try {
            // 等待调度器初始化完成
            Thread.sleep(3000)
            loadJobsToScheduler()
        } catch (e: Exception) {
            log.error("初始化定时任务失败", e)
        }
    }
}
