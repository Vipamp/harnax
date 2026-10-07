package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.config.Memory
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.memory.MemoryConfig
import io.agentscope.harness.agent.memory.MemoryFlushManager

/**
 * Turns [Memory] into the pipeline configuration the harness consumes.
 *
 * The defaults pinned here are the plan's, stated as values rather than inherited silently: a
 * deployment that reads `MemoryConfig.defaults()` gets whatever the upstream constant is next time
 * the dependency moves.
 */
object MemoryConfigFactory {

    /**
     * Two prohibitions on every prompt that writes memory: the extractor's and the promoter's. Both write a
     * file that is injected back into this owner's later conversations, so a stray name from another user or
     * a key pasted into chat would both become durable context.
     */
    internal val PROHIBITIONS = """
        Two prohibitions apply to everything you write:
        - Never record facts, names, contacts or documents belonging to another user or another tenant.
        - Never record credentials or secrets: no API keys, tokens, passwords, cookies or private endpoints.
    """.trimIndent()

    /** The budget one whole rewrite of `MEMORY.md` is asked to stay inside, shared by curation and promotion. */
    internal const val CONSOLIDATION_MAX_TOKENS = 4_000

    private const val DAILY_FILE_RETENTION_DAYS = 90
    private const val SESSION_RETENTION_DAYS = 180

    fun build(
        memory: Memory,
        memoryModel: Model?,
    ): MemoryConfig = MemoryConfig.builder()
        .model(memoryModel)
        .flushTrigger(triggerOf(memory))
        .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT.trim() + "\n\n" + PROHIBITIONS)
        .consolidationMinGap(memory.consolidationMinGap)
        .consolidationMaxTokens(CONSOLIDATION_MAX_TOKENS)
        .dailyFileRetentionDays(DAILY_FILE_RETENTION_DAYS)
        .sessionRetentionDays(SESSION_RETENTION_DAYS)
        .build()

    private fun triggerOf(memory: Memory): MemoryConfig.FlushTrigger = when (memory.flushTrigger.lowercase()) {
        "always" -> MemoryConfig.FlushTrigger.always()
        "never" -> MemoryConfig.FlushTrigger.never()
        "throttled" -> MemoryConfig.FlushTrigger.throttled(memory.flushMinGap)
        else -> throw IllegalArgumentException(
            "harness.memory.flush-trigger must be one of always|throttled|never, got '${memory.flushTrigger}'",
        )
    }
}
