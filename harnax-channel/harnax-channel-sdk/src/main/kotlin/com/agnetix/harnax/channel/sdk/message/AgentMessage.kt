package com.agnetix.harnax.channel.sdk.message

/**
 * Agent 消息格式
 * 用于 ChannelMessage 与 Agent 之间的消息转换
 *
 * 该格式与具体平台无关，只保留 role 和 content 两个核心字段，
 * 供 Agent 进行对话处理时使用。
 */
data class AgentMessage(
    /**
     * 消息角色: "user" / "assistant" / "system"
     */
    val role: String,

    /**
     * 消息内容
     */
    val content: String,
)
