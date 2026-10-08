package com.agnetix.harnax.harness.compaction

import io.agentscope.harness.agent.memory.compaction.CompactionConfig

/**
 * The compaction tier this runtime builds with, as harnax's own literals.
 *
 * Until now the automatic path inherited every default from agentscope 2.0.4 and harnax wrote none of them,
 * which left two things in the air: an upstream change to [RESERVED_TOKENS] would move both the live trigger
 * and the number `/context` reports with no call site to notice it, and the read side had to reconstruct a
 * default config just to subtract its margin. The values below are upstream's current numbers, spelled out;
 * `AutoCompactionTierTest` keeps them honest with a drift sentinel that goes red the day upstream moves one.
 *
 * [offloadBeforeCompact][CompactionConfig.isOffloadBeforeCompact] is the one field pinned to something other
 * than the default. Upstream sends the trimmed prefix through the same `SessionTranscriptWriter` that
 * `disableTranscript()` turns off at assembly — it writes `agents/<agentId>/sessions/<sessionId>` as a second
 * copy of the history, which has no reader here (`session_search` is removed from the toolkit), no read-back
 * path and no delete in `clearSession`. Harnax reads user-visible history from the `session_message` archive.
 */
internal object AutoCompactionTier {
    const val TRIGGER_MESSAGES = 50

    /** 0 means "derive from the model's window", which is per-model; an absolute number would put small windows out of reach. */
    const val TRIGGER_TOKENS = 0
    const val RESERVED_TOKENS = 20_000
    const val KEEP_MESSAGES = 20

    /** -1 means "the dynamic tail" pinned just below. */
    const val KEEP_TOKENS = -1
    const val KEEP_TOKENS_MIN = 2_000
    const val KEEP_TOKENS_MAX = 8_000
    const val KEEP_TOKENS_RATIO = 0.25
    const val PRUNE_PROTECT_TOKENS = 40_000
    const val PRUNE_MINIMUM_TOKENS = 20_000
    const val PRUNE_MAX_OUTPUT_CHARS = 2_000

    /** The last three are read-only or removed tools here; pruning their results buys no margin. */
    val PRUNE_EXCLUDED_TOOLS = setOf("read_file", "memory_search", "memory_get", "session_search")

    /** The tier the automatic path runs on. */
    fun auto(): CompactionConfig = base().build()

    /**
     * The tier a `/compact` command runs on: the same numbers, a threshold that cannot answer a user who
     * asked for this, and neither file-writing step. [keepTokens] alone moves where the tail starts; null
     * leaves the dynamic tier in charge.
     */
    fun command(keepTokens: Int?): CompactionConfig = base()
        .triggerMessages(1)
        .flushBeforeCompact(false)
        .offloadBeforeCompact(false)
        .apply { keepTokens?.let { keepTokens(it) } }
        .build()

    private fun base(): CompactionConfig.Builder = CompactionConfig.builder()
        .triggerMessages(TRIGGER_MESSAGES)
        .triggerTokens(TRIGGER_TOKENS)
        .reserved(RESERVED_TOKENS)
        .keepMessages(KEEP_MESSAGES)
        .keepTokens(KEEP_TOKENS)
        .keepTokensMin(KEEP_TOKENS_MIN)
        .keepTokensMax(KEEP_TOKENS_MAX)
        .keepTokensRatio(KEEP_TOKENS_RATIO)
        // Referenced rather than copied, so a better summary prompt reaches this runtime through upstream.
        .summaryPrompt(CompactionConfig.DEFAULT_SUMMARY_PROMPT)
        .flushBeforeCompact(true)
        .offloadBeforeCompact(false)
        // truncateArgs and model stay unset: null keeps the agent's own model for the summary and keeps arg
        // truncation off — that is the fixed 500/1000-char behaviour the transcript channel was rejected for.
        .prune(
            CompactionConfig.PruneConfig.builder()
                .protectTokens(PRUNE_PROTECT_TOKENS)
                .minimumTokens(PRUNE_MINIMUM_TOKENS)
                .maxOutputChars(PRUNE_MAX_OUTPUT_CHARS)
                .excludedTools(PRUNE_EXCLUDED_TOOLS)
                .build(),
        )
}
