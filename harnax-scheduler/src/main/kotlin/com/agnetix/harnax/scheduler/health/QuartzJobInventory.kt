package com.agnetix.harnax.scheduler.health

import org.quartz.impl.matchers.GroupMatcher
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/**
 * What this instance has in the Quartz store *right now*.
 *
 * The read used to live on [com.agnetix.harnax.scheduler.service.SchedulerService] and the observation
 * layer called it through that bean — which is what tied the meter registry and the health indicator to
 * the service that, in turn, depends on the metrics bean to count its load attempts. This class has one
 * collaborator, [SchedulerFactoryBean], so both of them can read the store without going through any
 * business service and without a lazy proxy to hold the cycle open.
 *
 * Failures propagate on purpose: every caller already decides what an unreadable store means for it
 * (the health detail publishes -1, the gauge publishes NaN), and a local catch here would turn that into
 * a plausible zero.
 */
@Component
class QuartzJobInventory(
    private val schedulerFactory: SchedulerFactoryBean,
) {

    /**
     * Ids parsed back out of the `AgentTask_<id>` job names of [JOB_GROUP].
     *
     * `_ONCE` runs live in their own group, so they never show up here; a name that does not parse back
     * to a long (a job somebody created by hand in that group) is skipped rather than failing the read.
     */
    fun scheduledTaskIds(): Set<Long> {
        val jobKeys = schedulerFactory.scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JOB_GROUP))
        return jobKeys.mapNotNull { key ->
            key.name.removePrefix(JOB_NAME_PREFIX).toLongOrNull()
        }.toSet()
    }

    companion object {
        private const val JOB_GROUP = "AgentTaskGroup"
        private const val JOB_NAME_PREFIX = "AgentTask_"
    }
}
