package com.agnetix.harnax.channel.sdk.message

/**
 * Agent Message Format
 * Used for message conversion between ChannelMessage and Agent
 *
 * This format is platform-independent and only retains the two core fields: role and content,
 * used by the Agent for dialogue processing.
 */
data class AgentMessage(
    /**
     * Message role: "user" / "assistant" / "system"
     */
    val role: String,

    /**
     * Message content
     */
    val content: String,
)
