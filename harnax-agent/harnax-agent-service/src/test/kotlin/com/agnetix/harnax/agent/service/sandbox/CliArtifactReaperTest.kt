package com.agnetix.harnax.agent.service.sandbox

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.agent.CliSpec
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.entity.dto.AgentCliSetDto
import com.agnetix.harnax.entity.dto.CliDetailDto
import com.agnetix.harnax.entity.dto.CliPackageInventoryResponse
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.harness.sandbox.CliImageBuilder
import com.agnetix.harnax.harness.sandbox.CliPackageStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.scheduling.config.IntervalTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * Unit tests for [CliArtifactReaper]. The one thing worth pinning here is that every deletion decision is
 * made from admin's answer, unchanged: the sweeps themselves are covered by `CliPackageStoreTest` and
 * `CliImageBuilderTest`, and what this host adds on top of them is the fetch — which must go wrong without
 * deleting anything when admin does not answer.
 *
 * The second half is the two bounds this class puts on its own sweep: the interval it registers, which has
 * to stay below the grace window it is judging against, and the budget one round may spend on the sweep
 * that shells out to `docker`. Neither has a return value to assert on — a cadence is only visible in what
 * gets registered, and a timeout only in what does not happen — so both are read back where they land.
 */
class CliArtifactReaperTest {

    private val adminApiClient = mock<AdminApiClient>()
    private val launcher = mock<HarnessAgentLauncher>()
    private val packageStore = mock<CliPackageStore>()
    private val imageBuilder = mock<CliImageBuilder>()

    private val kubectl = CliDetailDto(
        id = 21L,
        name = "kubectl",
        version = "1.30.0",
        packageDigest = "a".repeat(64),
        payloadDigest = "b".repeat(64),
    )

    private val gh = CliDetailDto(
        id = 22L,
        name = "gh",
        version = "2.50.0",
        packageDigest = "c".repeat(64),
        payloadDigest = "d".repeat(64),
    )

    private lateinit var reaper: CliArtifactReaper

    @BeforeEach
    fun setUp() {
        whenever(launcher.cliImageBuilder).thenReturn(imageBuilder)
        whenever(launcher.harnessConfig).thenReturn(HarnessConfig(sandbox = SandboxConfig(cliReclaimGraceMinutes = 42)))
        whenever(imageBuilder.packageStore).thenReturn(packageStore)
        reaper = newReaper()
    }

    @AfterEach
    fun tearDown() {
        reaper.shutdown()
    }

    /** The cadence keys as `application.yml` ships them, so a test only ever states the ratio it is about. */
    private fun newReaper(
        intervalMs: Long = Duration.ofHours(1).toMillis(),
        sweepTimeoutMs: Long = Duration.ofMinutes(10).toMillis(),
    ) = CliArtifactReaper(
        launcher,
        adminApiClient,
        intervalMs,
        Duration.ofMinutes(10).toMillis(),
        sweepTimeoutMs,
    )

    private fun serveInventory() {
        whenever(adminApiClient.getCliPackageInventory()).thenReturn(
            CliPackageInventoryResponse(
                packageDigests = listOf("a".repeat(64), "c".repeat(64)),
                agentCliSets = listOf(AgentCliSetDto(agentId = 100L, clis = listOf(kubectl, gh))),
            ),
        )
    }

    /**
     * Registers one reaper's sweep and hands back the task it asked the registrar for, together with every
     * line at [level] it logged while doing so. The registrar is never started, so nothing runs — this reads
     * the cadence rather than waiting on it.
     */
    private fun register(
        graceMinutes: Long,
        intervalMs: Long,
        level: Level,
    ): Pair<IntervalTask, List<ILoggingEvent>> {
        whenever(launcher.harnessConfig).thenReturn(HarnessConfig(sandbox = SandboxConfig(cliReclaimGraceMinutes = graceMinutes)))
        val target = newReaper(intervalMs = intervalMs)
        val registrar = ScheduledTaskRegistrar()
        return try {
            val events = logging(level) { target.configureTasks(registrar) }
            registrar.fixedDelayTaskList.single() to events
        } finally {
            target.shutdown()
        }
    }

    /**
     * [block] with every event this class logs at [level] collected. Both the clamped cadence and an
     * abandoned round report themselves only as a log line, and a log line is the only return value they have.
     */
    private fun logging(
        level: Level,
        block: () -> Unit,
    ): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(CliArtifactReaper::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == level }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a package admin no longer registers is the only thing the cache is told to drop`() {
        serveInventory()

        reaper.reclaim()

        verify(packageStore).evictUnused(setOf("a".repeat(64), "c".repeat(64)), Duration.ofMinutes(42))
    }

    /**
     * The whitelist protects a tag, and a tag is a hash of one CLI set — so the sets handed over have to
     * carry the same values the spec path builds an image from, not a reconstruction of them.
     */
    @Test
    fun `each agent's live CLI set reaches the image sweep in spec form`() {
        serveInventory()

        reaper.reclaim()

        val captured = argumentCaptor<List<List<CliSpec>>>()
        verify(imageBuilder).evictUnusedImages(captured.capture(), any())
        val cliSet = captured.firstValue.single()

        assertEquals(listOf(21L, 22L), cliSet.map { it.cliId })
        assertEquals(listOf("1.30.0", "2.50.0"), cliSet.map { it.version })
        assertEquals(listOf("b".repeat(64), "d".repeat(64)), cliSet.map { it.payloadDigest })
    }

    /** A fetch that fails says nothing about what is in use, so it must not cost a single byte. */
    @Test
    fun `an unanswered inventory deletes nothing`() {
        whenever(adminApiClient.getCliPackageInventory()).thenThrow(RuntimeException("admin unreachable"))

        reaper.reclaim()

        verifyNoInteractions(packageStore)
        verify(imageBuilder, never()).evictUnusedImages(any(), any())
    }

    @Test
    fun `an empty inventory still reaches both sweeps`() {
        whenever(adminApiClient.getCliPackageInventory()).thenReturn(CliPackageInventoryResponse())

        reaper.reclaim()

        verify(imageBuilder).evictUnusedImages(eq(emptyList()), any())
        verify(packageStore).evictUnused(eq(emptySet()), any())
    }

    /**
     * Without a builder this host holds no CLI artifacts of its own, so the sweep would ask admin for an
     * inventory it cannot act on.
     */
    @Test
    fun `a host with no CLI image builder never asks for the inventory`() {
        whenever(launcher.cliImageBuilder).thenReturn(null)

        reaper.reclaim()

        verifyNoInteractions(adminApiClient)
    }

    /** The grace budget is an operator setting, read when the sweep runs rather than baked into a caller. */
    @Test
    fun `the configured grace is what the sweeps honour`() {
        serveInventory()
        whenever(launcher.harnessConfig).thenReturn(HarnessConfig(sandbox = SandboxConfig(cliReclaimGraceMinutes = 60 * 24 * 7)))

        reaper.reclaim()

        val grace = argumentCaptor<Duration>()
        verify(packageStore).evictUnused(any(), grace.capture())
        verify(imageBuilder).evictUnusedImages(any(), grace.capture())
        assertEquals(listOf(Duration.ofDays(7), Duration.ofDays(7)), grace.allValues)
    }

    /** The shipped ratio — 60 min interval under a 360 min window — needs no clamping, and says no more. */
    @Test
    fun `a grace window above the interval leaves the configured cadence and the initial delay alone`() {
        val (task, errors) = register(
            graceMinutes = Duration.ofHours(6).toMinutes(),
            intervalMs = Duration.ofHours(1).toMillis(),
            level = Level.ERROR,
        )

        assertEquals(Duration.ofHours(1), task.intervalDuration)
        assertEquals(Duration.ofMinutes(10), task.initialDelayDuration)
        assertEquals(emptyList<ILoggingEvent>(), errors)
    }

    /**
     * Assumption 1, closed. Past this ratio the interval, not the grace window, is what decides how long an
     * artifact is kept — so the sweep is clamped to the window and the operator is told in one ERROR line
     * which number to move, rather than the service refusing to start.
     */
    @Test
    fun `an interval above the grace window is clamped to the window and reported`() {
        val (task, errors) = register(
            graceMinutes = Duration.ofHours(6).toMinutes(),
            intervalMs = Duration.ofDays(1).toMillis(),
            level = Level.ERROR,
        )

        assertEquals(Duration.ofHours(6), task.intervalDuration)
        assertEquals(1, errors.size)
        assertTrue(
            errors.single().formattedMessage.contains("clamped", ignoreCase = true),
            errors.single().formattedMessage,
        )
    }

    /** The boundary itself: an interval equal to the window is already the confusing configuration. */
    @Test
    fun `an interval equal to the grace window is reported too`() {
        val (task, errors) = register(
            graceMinutes = Duration.ofHours(1).toMinutes(),
            intervalMs = Duration.ofHours(1).toMillis(),
            level = Level.ERROR,
        )

        assertEquals(Duration.ofHours(1), task.intervalDuration)
        assertEquals(1, errors.size)
    }

    /** A grace window nobody meant — zero, or a minute — must not turn the sweep into a loop on the daemon. */
    @Test
    fun `the clamped cadence never drops below one minute`() {
        val (task, errors) = register(graceMinutes = 0, intervalMs = Duration.ofHours(1).toMillis(), level = Level.ERROR)

        assertEquals(Duration.ofMinutes(1), task.intervalDuration)
        assertEquals(1, errors.size)
    }

    /**
     * Assumption 3, closed. The image sweep is the round's only unbounded wait — `docker` goes out to a
     * process the daemon may never answer — and giving up on it must never read as "delete anyway": the
     * cache sweep, the round's other deletion, is not reached at all and both are retried next sweep.
     */
    @Test
    fun `an image sweep that outlives its budget never reaches the cache sweep`() {
        serveInventory()
        val started = CountDownLatch(1)
        whenever(imageBuilder.evictUnusedImages(any(), any())).thenAnswer {
            started.countDown()
            // Longer than any budget this test configures: the reaper releases it by cancelling.
            Thread.sleep(Duration.ofMinutes(5).toMillis())
            0
        }
        // Comfortably above the worker's own start-up, so the round gives up on a sweep that had begun.
        reaper = newReaper(sweepTimeoutMs = 500)

        val warnings = logging(Level.WARN) { reaper.reclaim() }

        assertTrue(started.await(5, TimeUnit.SECONDS), "the image sweep never reached the worker")
        verifyNoInteractions(packageStore)
        assertEquals(1, warnings.size)
        assertTrue(
            warnings.single().formattedMessage.contains("next sweep", ignoreCase = true),
            warnings.single().formattedMessage,
        )
    }

    /** The budget is only what a round may spend, not a reason to skip a sweep that does answer in time. */
    @Test
    fun `a sweep that answers inside its budget still reaches the cache sweep`() {
        serveInventory()
        whenever(imageBuilder.evictUnusedImages(any(), any())).thenAnswer {
            Thread.sleep(20L)
            0
        }
        reaper = newReaper(sweepTimeoutMs = Duration.ofSeconds(10).toMillis())

        reaper.reclaim()

        verify(packageStore).evictUnused(any(), any())
    }
}
