package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.config.Memory
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.memory.MemoryConfig
import io.agentscope.harness.agent.memory.MemoryFlushManager
import java.time.Duration

/**
 * Turns [Memory] into the pipeline configuration the harness consumes.
 *
 * The defaults pinned here are the plan's, stated as values rather than inherited silently: a
 * deployment that reads `MemoryConfig.defaults()` gets whatever the upstream constant is next time
 * the dependency moves.
 */
object MemoryConfigFactory {

    /**
     * Two prohibitions appended to the harness's own extraction prompt. The extractor writes a file
     * that is injected back into every later conversation of this owner, so a stray name from another
     * user or a key pasted into chat would both become durable context.
     */
    private val FLUSH_PROHIBITIONS = """
        Two prohibitions apply to everything you write:
        - Never record facts, names, contacts or documents belonging to another user or another tenant.
        - Never record credentials or secrets: no API keys, tokens, passwords, cookies or private endpoints.
    """.trimIndent()

    private val CONSOLIDATION_MIN_GAP: Duration = Duration.ofMinutes(30)
    private const val CONSOLIDATION_MAX_TOKENS = 4_000
    private const val DAILY_FILE_RETENTION_DAYS = 90
    private const val SESSION_RETENTION_DAYS = 180

    fun build(
        memory: Memory,
        memoryModel: Model?,
    ): MemoryConfig = MemoryConfig.builder()
        .model(memoryModel)
        .flushTrigger(triggerOf(memory))
        .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT.trim() + "\n\n" + FLUSH_PROHIBITIONS)
        .consolidationMinGap(CONSOLIDATION_MIN_GAP)
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
