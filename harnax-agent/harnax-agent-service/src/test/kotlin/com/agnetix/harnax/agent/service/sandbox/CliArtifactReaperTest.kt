package com.agnetix.harnax.agent.service.sandbox

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
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.Duration

/**
 * Unit tests for [CliArtifactReaper]. The one thing worth pinning here is that every deletion decision is
 * made from admin's answer, unchanged: the sweeps themselves are covered by `CliPackageStoreTest` and
 * `CliImageBuilderTest`, and what this host adds on top of them is the fetch — which must go wrong
 * without deleting anything when admin does not answer.
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
        reaper = CliArtifactReaper(launcher, adminApiClient)
    }

    private fun serveInventory() {
        whenever(adminApiClient.getCliPackageInventory()).thenReturn(
            CliPackageInventoryResponse(
                packageDigests = listOf("a".repeat(64), "c".repeat(64)),
                agentCliSets = listOf(AgentCliSetDto(agentId = 100L, clis = listOf(kubectl, gh))),
            ),
        )
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

        verify(packageStore).evictUnused(eq(emptySet()), any())
        verify(imageBuilder).evictUnusedImages(eq(emptyList()), any())
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
}
