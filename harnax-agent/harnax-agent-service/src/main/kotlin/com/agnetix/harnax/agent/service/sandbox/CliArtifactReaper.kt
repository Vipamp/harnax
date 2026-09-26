package com.agnetix.harnax.agent.service.sandbox

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.toCliSpec
import com.agnetix.harnax.harness.HarnessAgentLauncher
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Periodically reclaims the CLI artifacts — payload trees and sandbox images — nothing names any more.
 *
 * Both accumulate in one direction only. Installing a new CLI selection caches a tree and builds an image,
 * and nothing reversed that: removing a package, changing a version, or re-pointing an agent at a different
 * set left the old artifacts on disk forever. `CliPackageStore.evictUnused` and
 * `CliImageBuilder.evictUnusedImages` are the deletions; this is the caller that supplies what is still in
 * use, which only admin knows.
 *
 * The inventory is fetched rather than inferred from this host's own sessions: an image is shared by every
 * agent that happens to select the same CLI set, so a host that only saw one of them would otherwise delete
 * the image another is about to start from.
 *
 * # Cadence, and what a slow sweep costs
 *
 * The sweep is a fixed-delay task on the container's shared scheduler, registered in [configureTasks]
 * rather than declared with `@Scheduled` — the period it runs at is not simply the configured number,
 * because [grace] has to stay above it (see [effectiveIntervalMs]). Two sweeps can never overlap: a
 * fixed-delay run is only armed again once the previous one has returned, so a sweep that overruns the
 * interval starts the following one late instead of running alongside it, and [sweeps] holds that line for
 * the work itself even after [withinBudget] has stopped waiting on a sweep. This module configures no
 * scheduler pool of its own — no `spring.task.scheduling.*` key, no `TaskScheduler` bean — so the sweep
 * shares the container's scheduler thread with the service heartbeat and the keep-alive reaper, and the
 * round budget is what keeps a `docker` call that never returns from holding that thread indefinitely.
 */
@Component
@ConditionalOnProperty(prefix = "harness.sandbox", name = ["enabled"], havingValue = "true")
class CliArtifactReaper(
    private val launcher: HarnessAgentLauncher,
    private val adminApiClient: AdminApiClient,
    /** The interval as the operator set it, before [effectiveIntervalMs] clamps it to the grace window. */
    @Value("\${harness.sandbox.cli-reclaim-interval-ms:3600000}") private val configuredIntervalMs: Long,
    @Value("\${harness.sandbox.cli-reclaim-initial-delay-ms:600000}") private val initialDelayMs: Long,
    /**
     * How long one round may wait on the sweeps before it gives up on them. Bound above the longest honest
     * sweep — `docker rmi` of a large image is slow — and below what a wedged daemon would otherwise cost
     * the shared scheduler thread forever.
     */
    @Value("\${harness.sandbox.cli-reclaim-sweep-timeout-ms:600000}") private val sweepTimeoutMs: Long,
) : SchedulingConfigurer {

    private val log = LoggerFactory.getLogger(CliArtifactReaper::class.java)

    /**
     * The one worker both sweeps run on.
     *
     * One worker rather than a pool because a sweep must not overlap a sweep even after [withinBudget] has
     * given up waiting for one: the abandoned task is still on this thread, so the next round queues behind
     * it instead of starting a second deletion pass it cannot observe.
     */
    private val sweeps: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cli-artifact-reaper").apply { isDaemon = true }
    }

    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        val interval = effectiveIntervalMs()
        log.info(
            "[cliReclaim] Reclaiming CLI artifacts every {} min(s), first sweep in {} min(s)",
            Duration.ofMillis(interval).toMinutes(),
            Duration.ofMillis(initialDelayMs).toMinutes(),
        )
        registrar.addFixedDelayTask(
            FixedDelayTask(Runnable { reclaim() }, Duration.ofMillis(interval), Duration.ofMillis(initialDelayMs)),
        )
    }

    /**
     * Runs one reclaim sweep.
     *
     * A sweep that cannot ask admin what is in use deletes nothing at all, rather than working from a
     * partial answer: both sweeps treat "not on the whitelist" as "unused", so an empty answer would strip
     * this host's whole CLI cache and every image it built. Running out of budget (see [withinBudget]) keeps
     * everything for the same reason — a round that never saw the end of its docker sweep has no more
     * evidence than a round that got no inventory. What a wedged daemon costs is the reclaim, not the bytes.
     */
    fun reclaim() {
        val builder = launcher.cliImageBuilder ?: return
        val inventory = try {
            adminApiClient.getCliPackageInventory()
        } catch (e: Exception) {
            log.warn("[cliReclaim] Keeping every CLI artifact: admin did not answer the inventory ({})", e.message)
            return
        }
        val grace = grace()
        // The image sweep goes first because it is the one that shells out to `docker`, and a round that
        // cannot finish it keeps everything rather than half-reclaiming (see [withinBudget]).
        val images = withinBudget("image sweep") {
            builder.evictUnusedImages(
                inventory.agentCliSets.map { cliSet -> cliSet.clis.map { it.toCliSpec() } },
                grace,
            )
        } ?: return
        val trees = builder.packageStore.evictUnused(inventory.packageDigests.toSet(), grace)
        if (trees > 0 || images > 0) {
            log.info("[cliReclaim] Reclaimed {} CLI payload tree(s) and {} CLI image(s)", trees, images)
            // The other half of "why was this artifact kept" is which agent's set holds it.
            log.debug(
                "[cliReclaim] Kept the sets of {} agent(s): {}",
                inventory.agentCliSets.size,
                inventory.agentCliSets.joinToString { "agent ${it.agentId} (${it.clis.size} CLI(s))" },
            )
        }
    }

    @PreDestroy
    fun shutdown() {
        sweeps.shutdownNow()
    }

    /**
     * The period the sweep actually runs at: the configured interval, clamped so it never exceeds the grace
     * window.
     *
     * The grace window is only the retention while the sweep is at least as frequent as it is wide: a row
     * becomes reclaimable when it passes its grace age and then waits up to one interval to be noticed, so
     * with `grace <= interval` it is the interval that decides how long an artifact is kept and the operator's
     * grace number means nothing. That is a legibility and load problem rather than a safety hole, so the
     * service still starts: one ERROR line says so, and the sweep runs once per grace window instead.
     *
     * This is the single place the two values meet — [grace] itself is never adjusted.
     */
    private fun effectiveIntervalMs(): Long {
        val graceMs = grace().toMillis()
        val effective = minOf(configuredIntervalMs, graceMs).coerceAtLeast(MIN_INTERVAL_MS)
        if (graceMs > configuredIntervalMs) return effective
        log.error(
            "[cliReclaim] The reclaim interval ({} ms) is not below the grace window ({} ms), which makes the " +
                "interval the retention and the grace window a decoration: an artifact sits unreclaimed for up to " +
                "one interval after it ages past the grace window. The sweep interval is clamped to the grace " +
                "window ({} ms); raise `harness.sandbox.cli-reclaim-interval-ms` above the grace window, or lower " +
                "`harness.sandbox.cli-reclaim-grace-minutes`.",
            configuredIntervalMs,
            graceMs,
            effective,
        )
        return effective
    }

    /** The configured grace window, as both sweeps honour it. */
    private fun grace(): Duration = Duration.ofMinutes(launcher.harnessConfig.sandbox.cliReclaimGraceMinutes)

    /**
     * Runs [block] on [sweeps] and waits at most [sweepTimeoutMs] for it, returning null when that expires.
     *
     * A timeout is a reason to keep, never a licence to delete: a sweep that could not read the daemon has
     * no evidence about what is in use, so the caller drops the rest of the round and the next one starts
     * over. Cancelling the wait does not stop the sweep itself — `Process.waitFor` is not interruptible, so
     * an in-flight `docker` call only returns when `DefaultDockerCommandExecutor`'s own per-command budget
     * expires. The abandoned work therefore stays on [sweeps], which is what makes the next round queue
     * behind it instead of starting a second deletion pass it cannot observe.
     */
    private fun withinBudget(
        label: String,
        block: () -> Int,
    ): Int? {
        val future = sweeps.submit(Callable { block() })
        return try {
            future.get(sweepTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.warn(
                "[cliReclaim] Keeping every CLI artifact this round: the {} did not finish within {} ms. " +
                    "Retrying on the next sweep.",
                label,
                sweepTimeoutMs,
            )
            null
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (e: ExecutionException) {
            // Unwrapped, so a failing sweep reaches the scheduler's error handler exactly as it did before
            // the budget existed rather than being reported as a timeout.
            throw e.cause ?: e
        }
    }

    companion object {
        /**
         * Floor for the clamped interval, so a grace window of zero or a mis-typed small number cannot turn
         * the reclaim into a tight loop against the daemon.
         */
        private const val MIN_INTERVAL_MS = 60_000L
    }
}
