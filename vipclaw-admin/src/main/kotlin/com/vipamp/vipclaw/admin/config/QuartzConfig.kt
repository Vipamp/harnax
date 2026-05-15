package com.vipamp.vipclaw.admin.config

import org.quartz.spi.TriggerFiredBundle
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.quartz.AdaptableJobFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean

/**
 * Quartz configuration
 */
@Configuration
class QuartzConfig {

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

    @Bean
    fun schedulerFactoryBean(jobFactory: AdaptableJobFactory): SchedulerFactoryBean {
        val factory = SchedulerFactoryBean()
        factory.setJobFactory(jobFactory)
        factory.setStartupDelay(5)
        factory.isAutoStartup = true
        factory.setOverwriteExistingJobs(true)
        return factory
    }
}
