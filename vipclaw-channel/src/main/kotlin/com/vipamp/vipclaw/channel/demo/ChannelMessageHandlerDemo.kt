//package com.vipamp.vipclaw.channel.demo
//
//import com.vipamp.vipclaw.channel.ChannelSpec
//import com.vipamp.vipclaw.channel.ChannelType
//import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptorFactory
//import com.vipamp.vipclaw.channel.message.ChannelMessage
//import com.vipamp.vipclaw.channel.message.MessageRole
//import com.vipamp.vipclaw.channel.session.AgentMessage
//import com.vipamp.vipclaw.channel.session.ChannelSessionManager
//import com.vipamp.vipclaw.channel.session.InMemoryChannelSessionManager
//import kotlinx.coroutines.runBlocking
//
///**
// * Channel 消息处理器 Demo
// * 演示如何使用 Channel 模块处理各平台消息
// */
//class ChannelMessageHandlerDemo(
//    private val sessionManager: ChannelSessionManager = InMemoryChannelSessionManager()
//) {
//
//    /**
//     * 处理消息入口
//     * @param channelSpec Channel 配置
//     * @param message 收到的消息
//     * @param agentProcessor Agent 处理函数（接收历史消息，返回 AI 回复）
//     * @return AI 回复
//     */
//    suspend fun handleMessage(
//        channelSpec: ChannelSpec,
//        message: ChannelMessage,
//        agentProcessor: suspend (List<AgentMessage>, String) -> String
//    ): String {
//        // 1. 保存用户消息
//        sessionManager.addMessage(channelSpec.id, message)
//
//        // 2. 获取历史消息
//        val history = sessionManager.getHistory(channelSpec.id, message.sessionId, limit = 20)
//        val agentMessages = sessionManager.toAgentMessages(history)
//
//        // 3. 调用 Agent 处理
//        val reply = agentProcessor(agentMessages, message.content)
//
//        // 4. 保存 AI 回复
//        val assistantMessage = ChannelMessage.builder()
//            .sessionId(message.sessionId)
//            .role(MessageRole.ASSISTANT)
//            .content(reply)
//            .channelType(message.channelType)
//            .timestamp(System.currentTimeMillis())
//            .build()
//        sessionManager.addMessage(channelSpec.id, assistantMessage)
//
//        return reply
//    }
//
//    /**
//     * 构建响应
//     */
//    fun buildResponse(channelType: ChannelType, reply: String, originalMessage: ChannelMessage): Any {
//        val adaptor = ChannelAdaptorFactory.getAdaptor(channelType)
//            ?: throw IllegalArgumentException("Unsupported channel type: $channelType")
//        return adaptor.buildResponse(reply, originalMessage)
//    }
//}
//
///**
// * Demo 使用示例
// */
//fun main() = runBlocking {
//    // 创建消息处理器
//    val handler = ChannelMessageHandlerDemo()
//
//    // 创建一个 HTTP Channel 配置
//    val httpChannel = ChannelSpec.builder()
//        .id(1L)
//        .name("Demo HTTP Channel")
//        .type(ChannelType.HTTP)
//        .agentId(1L)
//        .callbackKey("demo-key-123")
//        .token("my-secret-token")
//        .build()
//
//    // 模拟用户消息
//    val userMessage = ChannelMessage.builder()
//        .sessionId("session-001")
//        .content("你好，请介绍一下自己")
//        .channelType(ChannelType.HTTP)
//        .senderId("user-001")
//        .build()
//
//    // 定义 Agent 处理函数（这里用简单的模拟）
//    val agentProcessor: suspend (List<AgentMessage>, String) -> String = { history, query ->
//        println("历史消息数: ${history.size}")
//        "你好！我是 AI 助手，有什么可以帮助你的吗？"
//    }
//
//    // 处理消息
//    val reply = handler.handleMessage(httpChannel, userMessage, agentProcessor)
//    println("AI 回复: $reply")
//
//    // 构建响应
//    val response = handler.buildResponse(ChannelType.HTTP, reply, userMessage)
//    println("响应: $response")
//
//    // 模拟多轮对话
//    val followUpMessage = ChannelMessage.builder()
//        .sessionId("session-001")
//        .content("你能做什么？")
//        .channelType(ChannelType.HTTP)
//        .senderId("user-001")
//        .build()
//
//    val reply2 = handler.handleMessage(httpChannel, followUpMessage, agentProcessor)
//    println("AI 回复 2: $reply2")
//}
