package com.agnetix.harnax.agent.service.sandbox

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.toCliSpec
import com.agnetix.harnax.harness.HarnessAgentLauncher
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

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
 */
@Component
@ConditionalOnProperty(prefix = "harness.sandbox", name = ["enabled"], havingValue = "true")
class CliArtifactReaper(
    private val launcher: HarnessAgentLauncher,
    private val adminApiClient: AdminApiClient,
) {

    private val log = LoggerFactory.getLogger(CliArtifactReaper::class.java)

    /**
     * Runs one reclaim sweep.
     *
     * A sweep that cannot ask admin what is in use deletes nothing at all, rather than working from a
     * partial answer: both sweeps treat "not on the whitelist" as "unused", so an empty answer would strip
     * this host's whole CLI cache and every image it built.
     */
    @Scheduled(
        fixedDelayString = "\${harness.sandbox.cli-reclaim-interval-ms:3600000}",
        initialDelayString = "\${harness.sandbox.cli-reclaim-initial-delay-ms:600000}",
    )
    fun reclaim() {
        val builder = launcher.cliImageBuilder ?: return
        val inventory = try {
            adminApiClient.getCliPackageInventory()
        } catch (e: Exception) {
            log.warn("[cliReclaim] Keeping every CLI artifact: admin did not answer the inventory ({})", e.message)
            return
        }
        val grace = Duration.ofMinutes(launcher.harnessConfig.sandbox.cliReclaimGraceMinutes)
        val trees = builder.packageStore.evictUnused(inventory.packageDigests.toSet(), grace)
        val images = builder.evictUnusedImages(
            inventory.agentCliSets.map { cliSet -> cliSet.clis.map { it.toCliSpec() } },
            grace,
        )
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
}
