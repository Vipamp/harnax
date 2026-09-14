package com.agnetix.harnax.scheduler.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobExecutionContext
import org.quartz.JobKey
import org.quartz.SimpleScheduleBuilder
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Why `SchedulerServiceImpl.onApplicationReady()` may register the housekeeping sweep on a node that never
 * starts its scheduler, which is what `spring.quartz.auto-startup` now does for `scheduler.enabled=false`.
 *
 * The whole G3 guarantee rests on this: a disabled node must still reclaim the status-4 row its own `/stop`
 * leaves behind, and the only thing that reclaims it is this job. Boot's `auto-startup=false` leaves the
 * scheduler created-but-standby rather than un-created, so the question "does Quartz refuse a store write
 * from a node that has not started?" has to be answered by the library, not by a reading of it — a `false`
 * answer would mean a disabled node silently loses its sweep and needs the registration gated off instead.
 */
class SchedulerStandbyRegistrationTest {

    @Test
    fun `a scheduler left in standby still accepts the registration and fires it once started`() {
        val properties = java.util.Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", "StandbyRegistrationProbe")
            setProperty("org.quartz.threadPool.threadCount", "1")
            setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
        }
        val scheduler = StdSchedulerFactory(properties).getScheduler()
        // The state Boot puts the scheduler in with auto-startup=false: created, and never started.
        assertFalse(scheduler.isStarted, "the probe has to run against a scheduler in standby")

        val jobKey = JobKey(JOB_NAME, GROUP)
        val jobDetail = JobBuilder.newJob(ProbeJob::class.java).withIdentity(jobKey).storeDurably().build()
        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(TriggerKey(JOB_NAME, GROUP))
            .forJob(jobKey)
            .startNow()
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever())
            .build()
        try {
            scheduler.scheduleJob(jobDetail, trigger)

            assertTrue(scheduler.checkExists(jobKey), "a registration made in standby was thrown away")
            assertEquals(
                1,
                scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)).size,
                "a registration made in standby is not in the store",
            )

            // Registering is only half the claim: a job nobody ever fires would be a worse answer than one
            // that throws. Standby defers the schedule, it does not disarm it.
            scheduler.start()
            assertTrue(
                ProbeJob.FIRED.await(10, TimeUnit.SECONDS),
                "registered while in standby, then never fired once the scheduler started",
            )
        } finally {
            scheduler.shutdown(true)
        }
    }

    /** The fire is what the test waits on, so the job body has nothing else to do. */
    class ProbeJob : Job {
        override fun execute(context: JobExecutionContext) {
            FIRED.countDown()
        }

        companion object {
            val FIRED = CountDownLatch(1)
        }
    }

    companion object {
        private const val GROUP = "StandbyProbeGroup"
        private const val JOB_NAME = "StandbyProbeJob"
    }
}
