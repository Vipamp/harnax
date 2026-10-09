package com.agnetix.harnax.agent.protocol

/**
 * Where [ContextUsageResponse.contextWindow] came from, so a caller can tell a configured
 * denominator from an inferred one.
 */
enum class ContextWindowSource {
    /** `model.context_window`, the value an operator entered for this model. */
    MODEL_FIELD,

    /** Upstream's table keyed on the model name; a guess, but a maintained one. */
    UPSTREAM_TABLE,

    /** Neither was available; the ratio is therefore only as good as that default. */
    FALLBACK,
}

/**
 * ContextUsageResponse - how full one session's model context is.
 *
 * Two token numbers are reported because they answer different questions: [estimatedTokens] is the same
 * estimate the automatic compaction triggers on and it moves on the turn a compaction runs, while
 * [lastCallInputTokens] is the billed input of the last model call — the real size of that request, system
 * prompt and tool list included, but it only moves on the turn *after* a compaction. The two are not
 * proportional: measured on one deployment the estimate came out between roughly a tenth of the billed
 * figure and slightly above it, because the estimate counts reasoning content that is not replayed into
 * later turns while the bill carries the prompt and tool list the estimate never sees. [ratio] therefore
 * takes the billed numerator whenever one exists — with one exception, [billIsCurrent].
 *
 * On-demand compaction rewrites the context without making a model call of its own, so the newest bill
 * row is then the price of a request that no longer exists. Serving it as the numerator would keep the
 * ratio at the pre-compaction figure until the next turn, which is exactly the reading the page cannot
 * explain. So such a bill is voided: [ratio] falls back to [estimatedTokens] and [billIsCurrent] says so,
 * while [lastCallInputTokens] keeps reporting the real number as history. The next model call writes a
 * newer row and the billed numerator comes back on its own.
 *
 * @property messageCount       messages currently in the model context; compare with [triggerMessages]
 * @property estimatedTokens    upstream's token estimate over the context
 * @property lastCallInputTokens billed input tokens of the latest model call for this session, null when none recorded
 * @property billIsCurrent      whether [lastCallInputTokens] still describes this context, i.e. was billed after
 *   the last on-demand compaction; false means [ratio] is on [estimatedTokens] instead
 * @property contextWindow      denominator, resolved from [windowSource]
 * @property windowSource       which of the three resolutions supplied [contextWindow]
 * @property ratio              [lastCallInputTokens] over [contextWindow] while [billIsCurrent], else
 *   [estimatedTokens] over it — which is also what it is until this session has a billed call to report
 * @property triggerTokens      where the automatic compaction fires for this model, from the same math
 * @property triggerMessages    the message-count trigger the automatic path uses
 */
data class ContextUsageResponse(
    val messageCount: Int = 0,
    val estimatedTokens: Int = 0,
    val lastCallInputTokens: Int? = null,
    val billIsCurrent: Boolean = true,
    val contextWindow: Int = 0,
    val windowSource: ContextWindowSource = ContextWindowSource.FALLBACK,
    val ratio: Double = 0.0,
    val triggerTokens: Int = 0,
    val triggerMessages: Int = 0,
)
