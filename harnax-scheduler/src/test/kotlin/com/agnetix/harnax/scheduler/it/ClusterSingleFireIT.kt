package com.agnetix.harnax.scheduler.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobExecutionContext
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.impl.StdSchedulerFactory
import org.quartz.utils.PoolingConnectionProvider
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * IT-1: the one hard piece of evidence that this is a cluster rather than two schedulers that happen to
 * share a table. Two real Quartz schedulers, built by hand and not through Spring, against one MySQL, a
 * 1-second cron, eight seconds.
 *
 * - the row count tracks *fires*, not fires × nodes: two members that each fired every trigger for
 *   themselves — which is precisely what an unclustered store on the same table gives you — produce roughly
 *   twice the rows, and that is the failure this assertion exists to catch;
 * - the per-node counters add up to the row count, so every fire was executed and persisted once;
 * - exactly two nodes are checked in under one `SCHED_NAME`, which is what makes takeover checkable at all;
 * - and the fires were counted by nobody outside those two node ids, so the application's own scheduler —
 *   which is a member of a *different* cluster in this JVM — never ran the job.
 *
 * What it deliberately does not assert is that the work was *split* between the two. Nothing decides which
 * member wins a fire: the one that reaches the row lock first does, and a node that loses every round is not
 * a bug. That is why the counters are keyed by instance id and then summed rather than compared per node.
 *
 * It extends the base so Flyway has built `QRTZ_*` before the hand-made schedulers connect. Those two are
 * extra members under their own `instanceName`, which is what keeps them from disturbing the application's
 * own scheduler — SCHED_NAME keys every row of the store, including the fired-trigger and state rows.
 */
class ClusterSingleFireIT : BaseSchedulerIT() {

    @Test
    fun `one clustered trigger fires once across two nodes`() {
        CountingJob.fires.clear()
        update("DELETE FROM it_cluster_fire")
        val schedulers = mutableListOf<Scheduler>()
        try {
            // Registered for cleanup as it is created, not once both exist. `SimpleThreadPool.initialize()`
            // starts its workers and `makeThreadsDaemons` defaults to false
            // (quartz-2.5.2-sources `SimpleThreadPool.java:71` and `:238-278`, and this config does not set
            // the flag), so a scheduler built and then dropped holds two non-daemon threads: the forked JVM
            // would outlive the test instead of reporting it.
            val first = scheduler(NODE_ONE).also { schedulers += it }
            val second = scheduler(NODE_TWO).also { schedulers += it }
            first.start()
            second.start()
            first.scheduleJob(
                JobBuilder.newJob(CountingJob::class.java).withIdentity(JOB_IDENTITY, IT_GROUP).build(),
                TriggerBuilder.newTrigger()
                    .withIdentity(JOB_IDENTITY, IT_GROUP)
                    .withSchedule(CronScheduleBuilder.cronSchedule("0/1 * * * * ?"))
                    .build(),
            )

            Thread.sleep(FIRE_WINDOW_MS)

            // Read membership before stopping anything — but not because stopping would erase the rows: it
            // would not. `JobStoreSupport.shutdown()` (quartz-2.5.2-sources `:730-755`) stops the cluster and
            // misfire threads and closes the pool, and `ClusterManager.shutdown()` (`:3857-3860`) only flips a
            // flag and interrupts its thread; neither touches the owner's own `QRTZ_SCHEDULER_STATE` row. A row
            // is deleted by a *peer* that has judged the owner's check-in stale — `clusterCheckIn` (`:3390`) →
            // `findFailedInstances` (`:3304`) with `calcFailedIfAfter` (`:3383`), then the delete in
            // `clusterRecover` (`:3547-3549`). While both nodes are alive they check in every 5s, so this count
            // is stable; after the shutdowns below it depends on when a peer notices — the threshold is the
            // stale row's own last check-in plus ~12.5s, evaluated only on a peer's next 5s tick — which is a
            // race this assertion has no business taking. Worth the length: the release runbook must not say
            // "stale rows disappear on a clean restart" — only a peer's timeout clears them.
            val nodes = queryCount(
                "SELECT COUNT(*) FROM QRTZ_SCHEDULER_STATE WHERE SCHED_NAME = ?",
                CLUSTER_NAME,
            )
            // Shut both down before reading the evidence, and the evidence then agrees with itself: a fire
            // landing between the table read and the counter read would otherwise cost a correct cluster the
            // `rows == counted` assertion. `shutdown(true)` waits for the worker that is mid-fire.
            first.shutdown(true)
            second.shutdown(true)

            val rows = queryCount("SELECT COUNT(*) FROM it_cluster_fire")
            val counted = CountingJob.fires.values.sumOf { it.get() }
            val appClusterMembers = queryCount(
                "SELECT COUNT(*) FROM QRTZ_SCHEDULER_STATE WHERE SCHED_NAME = ?",
                schedulerName,
            )

            assertEquals(2, nodes, "both members must be checked in to the shared cluster state")
            assertTrue(
                rows in FIRES_MIN..FIRES_MAX,
                "${FIRE_WINDOW_MS / 1000}s of a 1s cron should cost roughly ${FIRE_WINDOW_MS / 1000} rows, got $rows — " +
                    "twice that is every node firing the trigger for itself",
            )
            assertEquals(rows, counted, "every persisted fire must be counted by exactly one node")
            assertTrue(
                CountingJob.fires.keys.all { it == NODE_ONE || it == NODE_TWO },
                "the job ran only on the two hand-built members, not on ${CountingJob.fires.keys}",
            )
            assertEquals(
                1,
                appClusterMembers,
                "the application's own cluster must have kept this job out of its store",
            )
        } finally {
            schedulers.forEach { if (!it.isShutdown) it.shutdown() }
            CountingJob.fires.clear()
        }
    }

    /**
     * Built by hand rather than through Spring, because the claim is about two schedulers and the context
     * holds one. Everything but the identity is the set of values `application.yml` ships — with one addition,
     * the `dataSource` block, which the Spring path has no equivalent of: Boot hands `SchedulerFactoryBean` a
     * `DataSource` and Spring's `LocalDataSourceJobStore` registers its own connection providers, so
     * `application.yml` never has to name a pool. A hand-made scheduler goes through
     * `StdSchedulerFactory`'s own property branch, which does.
     *
     * `instanceId` is a fixed string instead of AUTO: AUTO derives host+timestamp, which is unique but
     * unreadable from an assertion. Sharing `instanceName` is what joins a cluster; the id only has to be
     * distinct.
     */
    private fun scheduler(instanceId: String): Scheduler {
        val props = Properties().apply {
            setProperty("org.quartz.scheduler.instanceName", CLUSTER_NAME)
            setProperty("org.quartz.scheduler.instanceId", instanceId)
            setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool")
            setProperty("org.quartz.threadPool.threadCount", "2")
            setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX")
            setProperty("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate")
            setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_")
            setProperty("org.quartz.jobStore.isClustered", "true")
            setProperty("org.quartz.jobStore.clusterCheckinInterval", "5000")
            setProperty("org.quartz.jobStore.useProperties", "true")
            setProperty("org.quartz.jobStore.misfireThreshold", "60000")
            setProperty("org.quartz.jobStore.acquireTriggersWithinLock", "true")
            setProperty("org.quartz.jobStore.dataSource", "itDs")
            // Mandatory, not cosmetic: `StdSchedulerFactory` only reaches HikariCP when this key equals
            // "hikaricp" (quartz-2.5.2-sources `StdSchedulerFactory.java:1031`, comparing
            // `PoolingConnectionProvider.POOLING_PROVIDER_HIKARICP` against `…dataSource.<name>.provider`),
            // and every other value — including absent — instantiates
            // `org.quartz.utils.C3p0PoolingConnectionProvider` instead (:1034). c3p0 is `provided` in
            // quartz-2.5.2.pom and declared by no pom of this reactor, so leaving the key out was measured to
            // fail on the very first `scheduler(..)` call — `SchedulerException: Could not initialize
            // DataSource: itDs` caused by `NoClassDefFoundError: com/mchange/v2/c3p0/ComboPooledDataSource` —
            // which is before any assertion in this file runs. Naming HikariCP gets past it; the pool is
            // `com.zaxxer:HikariCP`, which `spring-boot-starter-quartz` already ships.
            setProperty(
                "org.quartz.dataSource.itDs.${PoolingConnectionProvider.POOLING_PROVIDER}",
                PoolingConnectionProvider.POOLING_PROVIDER_HIKARICP,
            )
            setProperty("org.quartz.dataSource.itDs.driver", "com.mysql.cj.jdbc.Driver")
            setProperty("org.quartz.dataSource.itDs.URL", BaseSchedulerIT.mysql.jdbcUrl)
            setProperty("org.quartz.dataSource.itDs.user", BaseSchedulerIT.mysql.username)
            setProperty("org.quartz.dataSource.itDs.password", BaseSchedulerIT.mysql.password)
            setProperty("org.quartz.dataSource.itDs.maxConnections", "5")
        }
        return StdSchedulerFactory(props).scheduler
    }

    private fun queryCount(
        sql: String,
        vararg args: Any,
    ): Int = containerConnection().use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { index, arg -> ps.setObject(index + 1, arg) }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun update(sql: String) {
        containerConnection().use { it.createStatement().use { st -> st.executeUpdate(sql) } }
    }

    /**
     * Records the fire in the shared table, then counts it on the node that ran it — in that order, so a
     * counter can never be ahead of a row. The instance id comes from the scheduler that fired, so the map
     * keys are the two node names and the sum of the values is comparable against the table.
     */
    class CountingJob : Job {
        override fun execute(context: JobExecutionContext) {
            val instance = context.scheduler.metaData.schedulerInstanceId
            containerConnection().use { c ->
                c.prepareStatement("INSERT INTO it_cluster_fire (instance_name, fire_time) VALUES (?, ?)").use { ps ->
                    ps.setString(1, instance)
                    ps.setLong(2, System.currentTimeMillis())
                    ps.executeUpdate()
                }
            }
            fires.computeIfAbsent(instance) { AtomicInteger() }.incrementAndGet()
        }

        companion object {
            val fires = ConcurrentHashMap<String, AtomicInteger>()
        }
    }
}

private const val CLUSTER_NAME = "HarnaxClusterIT"
private const val NODE_ONE = "cluster-node-one"
private const val NODE_TWO = "cluster-node-two"
private const val JOB_IDENTITY = "cluster-fire"
private const val IT_GROUP = "ItGroup"
private const val FIRE_WINDOW_MS = 8_000L

/** A 1s cron over [FIRE_WINDOW_MS] is 8 fires; the band leaves a slow box room and still excludes 16. */
private const val FIRES_MIN = 5
private const val FIRES_MAX = 12

/**
 * Quartz's hand-built members are not wired to the Spring datasource, and neither is a job that has to
 * record what it did before the test can read it — so this is the container's own URL, opened per use.
 */
private fun containerConnection(): Connection = DriverManager.getConnection(
    BaseSchedulerIT.mysql.jdbcUrl,
    BaseSchedulerIT.mysql.username,
    BaseSchedulerIT.mysql.password,
)
