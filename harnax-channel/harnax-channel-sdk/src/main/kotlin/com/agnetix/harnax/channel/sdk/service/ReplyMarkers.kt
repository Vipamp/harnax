package com.agnetix.harnax.channel.sdk.service

/**
 * Replies that the pipeline generated itself, rather than the agent.
 *
 * They are shown to the user (a failure must be visible), but they must never enter
 * the session history: the next turn would feed the error text back to the model as if
 * the assistant had really said it, and the model tends to repeat or apologise for it.
 */
object ReplyMarkers {

    const val ROUTER_ERROR_PREFIX = "[Router Error]"

    const val EMPTY_REPLY_PREFIX = "⚠️ AI processing completed but returned no content"

    const val FAILED_PREFIX = "⚠️ AI processing failed"

    /** True when [content] is an error/fallback notice produced by the channel pipeline. */
    fun isSyntheticReply(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith(ROUTER_ERROR_PREFIX) ||
            trimmed.startsWith(EMPTY_REPLY_PREFIX) ||
            trimmed.startsWith(FAILED_PREFIX)
    }
}
