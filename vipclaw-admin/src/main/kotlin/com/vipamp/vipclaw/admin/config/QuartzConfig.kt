package com.vipamp.vipclaw.admin.config

import org.quartz.spi.TriggerFiredBundle
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.quartz.AdaptableJobFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * Quartz 配置类
 *
 * @author vipamp
 * @since 2026-03-16
 */
@Configuration
class QuartzConfig {

    /**
     * 自定义 JobFactory，支持 Spring 注入
     */
    @Bean
    fun jobFactory(capableBeanFactory: AutowireCapableBeanFactory): AdaptableJobFactory {
        return object : AdaptableJobFactory() {
            override fun createJobInstance(bundle: TriggerFiredBundle): Any {
                val jobInstance = super.createJobInstance(bundle)
                capableBeanFactory.autowireBean(jobInstance)
                return jobInstance
            }
        }
    }

    /**
     * 配置 SchedulerFactoryBean
     */
    @Bean
    fun schedulerFactoryBean(jobFactory: AdaptableJobFactory): SchedulerFactoryBean {
        val factory = SchedulerFactoryBean()
        factory.setJobFactory(jobFactory)
        // 延迟启动，等待 Spring 上下文初始化完成
        factory.setStartupDelay(5)
        // 自动启动
        factory.setAutoStartup(true)
        // 覆盖已存在的任务
        factory.setOverwriteExistingJobs(true)
        return factory
    }
}
