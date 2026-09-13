package com.agnetix.harnax.scheduler.job

import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * Job for a task whose `concurrent` allows overlapping executions.
 *
 * Not an `InterruptableJob`: its `interrupt()` could only ever have been a no-op here, since
 * the run is a router session and stopping it means an INTERRUPT command to the router
 * (`SchedulerService.stopTask`). Advertising a Quartz-level interrupt would be a trap for the next reader.
 */
class AgentTaskJob :
    AbstractAgentTaskJob(),
    Job {

    override fun execute(context: JobExecutionContext) = run(context)
}
