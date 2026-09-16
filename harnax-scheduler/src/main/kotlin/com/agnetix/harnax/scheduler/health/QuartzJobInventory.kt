package com.agnetix.harnax.scheduler.health

import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import org.quartz.CronTrigger
import org.quartz.impl.matchers.GroupMatcher
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/** What the store holds for one agent task, read back for the diff. */
data class RegisteredJob(
    val cronExpression: String?,
    val jobClassName: String,
)

/**
 * What this instance has in the Quartz store *right now*.
 *
 * The read used to live on [com.agnetix.harnax.scheduler.service.SchedulerService] and the observation
 * layer called it through that bean — which is what tied the meter registry and the health indicator to
 * the service that, in turn, depends on the metrics bean to count its reconcile rounds. This class has one
 * collaborator, [SchedulerFactoryBean], so both of them can read the store without going through any
 * business service and without a lazy proxy to hold the cycle open.
 *
 * Failures propagate on purpose: every caller already decides what an unreadable store means for it
 * (the health detail publishes -1, the gauge publishes NaN), and a local catch here would turn that into
 * a plausible zero. The one exception is per-job rather than per-store — a single unreadable job is skipped
 * in [agentTaskJobs], because that is a rolling-deploy artifact and not a reason for a node to stop
 * converging everything else.
 */
@Component
class QuartzJobInventory(
    private val schedulerFactory: SchedulerFactoryBean,
) {

    private val log = LoggerFactory.getLogger(QuartzJobInventory::class.java)

    /**
     * Ids parsed back out of the `AgentTask_<id>` job names of the agent-task group.
     *
     * Both halves of that identity — the group and the name shape — come from [TaskQuartzRegistrar], the
     * bean that writes them, because a second copy here is how the read and the write drift apart.
     * [agentTaskJobs] shares that same source and is the one that also reads each job's content.
     *
     * It deliberately does *not* answer from [agentTaskJobs]: reading the cron and the class back costs one
     * `getJobDetail` and one `getTriggersOfJob` per job against a JDBC store, and this is the count behind
     * the `/actuator/health` detail and the `scheduler.jobs.scheduled` gauge, both of which a probe hits
     * every few seconds. The reconcile round pays that per minute; a scrape must not.
     *
     * `_ONCE` runs live in their own group, so they never show up here; a name that does not parse back
     * to a long (a job somebody created by hand in that group) is skipped rather than failing the read.
     */
    fun scheduledTaskIds(): Set<Long> {
        val jobKeys = schedulerFactory.scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK))
        return jobKeys.mapNotNull { key -> TaskQuartzRegistrar.taskIdOf(key) }.toSet()
    }

    /**
     * Every agent-task job in [TaskQuartzRegistrar.GROUP_AGENT_TASK] keyed by the task id encoded in its
     * name, with the cron and the registered class read off the store rather than off what this node
     * thinks it wrote: under a shared store the question "what is scheduled" has exactly one answer for
     * the whole cluster.
     *
     * A job with no cron trigger (someone registered one by hand, or a delete left a durable job behind)
     * comes back with `cronExpression = null`, which the reconciler treats as "not matching" and rewrites.
     *
     * One unreadable job is skipped and logged rather than thrown, because reading the class is part of
     * reading the detail: a rolling deploy that renames a job class while its row survives in the shared
     * store makes *this* call fail on every node, and a snapshot that throws takes the whole round with it —
     * the node then converges nothing at all, including the tasks it could have fixed. The skipped key is
     * usually self-correcting anyway: its task is absent from the snapshot, so the same round re-registers
     * it with the class this build has.
     */
    fun agentTaskJobs(): Map<Long, RegisteredJob> {
        val scheduler = schedulerFactory.scheduler
        return scheduler.getJobKeys(GroupMatcher.jobGroupEquals(TaskQuartzRegistrar.GROUP_AGENT_TASK))
            .mapNotNull { key ->
                val taskId = TaskQuartzRegistrar.taskIdOf(key) ?: return@mapNotNull null
                try {
                    val detail = scheduler.getJobDetail(key) ?: return@mapNotNull null
                    val cron = scheduler.getTriggersOfJob(detail.key)
                        .filterIsInstance<CronTrigger>()
                        .firstOrNull()
                        ?.cronExpression
                    taskId to RegisteredJob(cron, detail.jobClass.name)
                } catch (e: Exception) {
                    log.warn("Skipping {} in the reconcile snapshot, its job detail is unreadable: {}", key, e.message)
                    null
                }
            }
            .toMap()
    }
}
