package com.agnetix.harnax.scheduler.it

import com.agnetix.harnax.scheduler.SchedulerApplication
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.SchedulerHousekeepingJob
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.quartz.impl.matchers.GroupMatcher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer

/**
 * Base class for the harnax-scheduler integration tests: the real application, the real Flyway migration, a
 * real clustered JDBC Quartz store and a real MyBatis read path, all against one Testcontainers MySQL 8.
 * Mocking any of those four would delete the thing these tests exist to prove.
 *
 * Nothing reaches the network, and nothing is mocked. `scheduler.api-key` is set to a non-blank value in
 * `application-it.yml`, which is the only branch of `RouterClient.init()` that does not fetch a SYSTEM key
 * from admin (Boot 4 has no `@MockBean`, and this repo has no `@MockitoBean` either — by design). That in
 * turn means no seeded task may *fire* inside these tests: every cron in this package is daily or weekly at
 * an off hour, and an IT that executes a task has to give `RouterClient` a stub first.
 *
 * [freezeBackgroundSweeps] is the other half of that promise. The store is shared and the sweeps in it are
 * cluster work, so a round landing inside a test would repair the very drift the test is about to assert on
 * — which the 3600s `scheduler.reconcile-interval-seconds` of the IT profile only *nearly* prevents: the
 * sweep is registered with `startNow()`, so the boot round is immediate and the next one an hour later.
 * Pausing the group plus waiting for the boot round to finish is what makes it deterministic.
 *
 * These classes are named `*IT` and run under failsafe behind `-Pintegration-test`, so a machine without a
 * Docker daemon skips them instead of failing red when the shared container is started. The `integration` tag
 * is what holds that promise: surefire's `<excludes>` are dropped the moment anything passes `-Dtest`, and
 * `<excludedGroups>` is not — and `@Tag` is `@Inherited`, so stating it once here covers this package.
 */
@SpringBootTest(
    classes = [SchedulerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ActiveProfiles("it")
@Tag("integration")
abstract class BaseSchedulerIT {

    @Autowired
    protected lateinit var schedulerFactory: SchedulerFactoryBean

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var status: SchedulerStatus

    /** The scheduler of the application context, which is a cluster member of its own. */
    protected val scheduler get() = schedulerFactory.scheduler

    /** Quartz's own scheduler name, i.e. the `SCHED_NAME` column every row of the store is keyed by. */
    protected val schedulerName: String get() = scheduler.metaData.schedulerName

    /**
     * Take the cluster's background writes out of the test's way, and prove they are out.
     *
     * Both sweeps (the 5-minute housekeeping one and the reconcile one) are registered by
     * `SchedulerServiceImpl.registerSweep` with the same group, so one group pause covers everything this
     * JVM's own node would otherwise write while a test is measuring one round. They are deliberately never
     * resumed: on resume Quartz's misfire handling fires a paused-behind trigger at an arbitrary moment
     * afterwards, which is the race this is here to remove. The container goes with the JVM.
     */
    @BeforeEach
    protected fun freezeBackgroundSweeps() {
        scheduler.pauseTriggers(GroupMatcher.triggerGroupEquals(SchedulerHousekeepingJob.GROUP))
        await("no system sweep is still executing") {
            scheduler.currentlyExecutingJobs.none { it.jobDetail.key.group == SchedulerHousekeepingJob.GROUP }
        }
        // The startup load is a direct `reconcile()` call on the service's own thread, not a Quartz fire, so
        // pausing triggers cannot reach it. `SchedulerStatus` is stamped at the end of a round that reached
        // the store, and the load loop exits on the first such round — so this is "the boot round is over".
        await("the startup reconcile round to have converged") {
            status.lastReconcileAt != null && status.lastReconcileError == null
        }
    }

    /** Polls [probe] until it holds, or fails with [reason] once the grace period is spent. */
    protected fun await(
        reason: String,
        probe: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (probe()) {
                return
            }
            Thread.sleep(AWAIT_POLL_MS)
        }
        error("Timed out after ${AWAIT_TIMEOUT_MS}ms waiting for $reason: these tests measure one round, and only one")
    }

    companion object {
        /**
         * One shared container for every IT class of this package: started once when the class is loaded and
         * never stopped, with Ryuk reaping it after the JVM exits (the shape `BaseAdminIT` uses).
         */
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_scheduler_it")
            .withUsername("root")
            .withPassword("it_test")
            // Release 1 owns only the Quartz schema: agent_task and its two companions still live in
            // harnax_admin, so this container gets them from a test resource until release 2 moves them.
            // Flyway's own V1 runs afterwards, against the same container, and creates QRTZ_* there.
            .withInitScript("schema-it.sql")

        init {
            mysql.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
        }

        private const val AWAIT_TIMEOUT_MS = 30_000L
        private const val AWAIT_POLL_MS = 50L
    }
}
