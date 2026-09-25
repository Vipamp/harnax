package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What this platform's CLI artifacts are still for, as admin sees it.
 *
 * The runtime holds three kinds of derived artifact — a payload tree per package digest, one sandbox
 * image per CLI set, one stored archive per registered package — and none of them can be judged safe to
 * delete from the host that holds it: a tree whose archive has gone cannot be rebuilt, and an image
 * belongs to a CLI selection only some agent makes. This is the one answer both reclaimers work from, so
 * a package admin still registers is never read as garbage by a host that has it cached.
 *
 * Both halves are deliberately broader than "in use right now": anything missing here becomes deletable,
 * so an under-reported list costs live artifacts while an over-reported one only costs disk.
 */
@Schema(description = "CLI packages and CLI sets admin still registers, for the runtime's reclaim sweep")
data class CliPackageInventoryResponse(
    @Schema(description = "Package digest of every registered package, including a disabled one")
    val packageDigests: List<String> = emptyList(),

    @Schema(description = "Each agent's deliverable CLI set — the same rows its agent spec resolves to")
    val agentCliSets: List<AgentCliSetDto> = emptyList(),
)

/**
 * One agent's CLI set as its spec delivers it.
 *
 * The rows carry [CliDetailDto] because that is the shape the build path consumes: the runtime recomputes
 * each set's image tag with the same fields it builds an image from, and a narrower shape would be a
 * second, independent description of the same selection — which is exactly how a live tag could end up
 * unlisted. `skill`, `runtimeEnv` and `envBindings` stay empty: they decide a prompt, never an image.
 */
@Schema(description = "The CLIs one agent selects")
data class AgentCliSetDto(
    @Schema(description = "Agent the set belongs to, for the log line of a sweep")
    val agentId: Long = 0,

    @Schema(description = "Enabled packages of this agent, in binding order")
    val clis: List<CliDetailDto> = emptyList(),
)
