package com.agnetix.harnax.scheduler.job

import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext

/**
 * Job for a task with `concurrent = 0`: Quartz will not fire it again while a previous fire is still
 * running, which is only possible at all because [AbstractAgentTaskJob.run] stays on the Quartz thread
 * until the execution is over.
 *
 * The annotation has to sit on the registered class. It is not `@Inherited`, so putting it on
 * [AbstractAgentTaskJob] — where it would look like it covers both subclasses — is silently ignored by
 * Quartz and would leave `concurrent = 0` unenforced.
 */
@DisallowConcurrentExecution
class AgentTaskNonConcurrentJob :
    AbstractAgentTaskJob(),
    Job {

    override fun execute(context: JobExecutionContext) = run(context)
}
