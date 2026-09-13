package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager

/**
 * What a channel wants the platform's HTTP callback answered with.
 *
 * The adaptor owns this because the platforms disagree about it: some want a bare `{"code":0}`,
 * a URL-verification request wants the challenge echoed back, and a rejected signature is worth
 * distinguishing from an accepted-but-unprocessed event. A generic controller cannot know any of
 * that, so it plays this result verbatim.
 */
data class ChannelCallbackResult(
    /** HTTP status to return to the platform. */
    val status: Int = 200,

    /** Response body, exactly as the platform expects to read it. */
    val body: String = "",
) {
    companion object {
        /** Acknowledge the callback without a body worth serialising. */
        @JvmStatic
        fun ok(body: String = "") = ChannelCallbackResult(status = 200, body = body)

        /** Refuse the callback: the payload was not verified or not understood. */
        @JvmStatic
        fun rejected(status: Int, reason: String) = ChannelCallbackResult(status = status, body = "{\"msg\":\"$reason\"}")
    }
}

/**
 * The agent-side pipeline a verified callback message has to run through.
 *
 * The controller hands these over instead of building the handler itself, because wiring an
 * inbound message to the agent is per-platform knowledge — Feishu has to strip the group-chat
 * `@_user_n` mention before a `/clear` command is recognisable, for example. A webhook channel and
 * a long-connection channel of the same type must not disagree about that, and they cannot drift
 * apart if both go through the adaptor's own wiring.
 */
class ChannelCallbackPipeline(
    val agentAdaptor: AgentAdaptor,
    val sessionManager: ChannelSessionManager,
    val chatService: ChannelChatService,
)
