//package com.vipamp.vipclaw.channel.demo
//
//import com.vipamp.vipclaw.channel.ChannelSpec
//import com.vipamp.vipclaw.channel.ChannelType
//import com.vipamp.vipclaw.channel.message.ChannelMessage
//import com.vipamp.vipclaw.channel.message.MessageRole
//import com.vipamp.vipclaw.channel.session.AgentMessage
//import com.vipamp.vipclaw.channel.session.InMemoryChannelSessionManager
//import kotlinx.coroutines.runBlocking
//
///**
// * 完整集成示例 - 模拟真实的 Channel 使用场景
// *
// * 这个示例展示如何将 Channel 与 Agent 结合使用
// */
//fun main() = runBlocking {
//    println("=".repeat(60))
//    println("Channel 完整集成示例")
//    println("=".repeat(60))
//
//    // 初始化组件
//    val sessionManager = InMemoryChannelSessionManager()
//
//    // 创建多个 Channel（模拟不同场景）
//    val channels = mapOf(
//        "http-api" to ChannelSpec.builder()
//            .id(1L)
//            .name("HTTP API 通道")
//            .type(ChannelType.HTTP)
//            .agentId(100L)
//            .callbackKey("http-api-key")
//            .token("api-token-123")
//            .build(),
//
//        "wecom-bot" to ChannelSpec.builder()
//            .id(2L)
//            .name("企业微信客服")
//            .type(ChannelType.WECOM)
//            .agentId(101L)
//            .callbackKey("wecom-cs-key")
//            .token("wecom-token")
//            .encodingAesKey("wecom-aes-key-32-characters")
//            .build(),
//
//        "feishu-bot" to ChannelSpec.builder()
//            .id(3L)
//            .name("飞书助手")
//            .type(ChannelType.FEISHU)
//            .agentId(102L)
//            .callbackKey("feishu-helper-key")
//            .appId("cli_xxx")
//            .appSecret("feishu-secret")
//            .build()
//    )
//
//    println("\n已创建 ${channels.size} 个 Channel:")
//    channels.forEach { (key, channel) ->
//        println("  - $key: ${channel.name} (${channel.type.displayName})")
//    }
//
//    // ========== 场景1: HTTP API 多用户对话 ==========
//    println("\n" + "=".repeat(60))
//    println("场景1: HTTP API 多用户对话")
//    println("=".repeat(60))
//
//    val httpChannel = channels["http-api"]!!
//
//    // 模拟多个用户同时对话
//    val users = listOf(
//        "user-001" to "张三",
//        "user-002" to "李四",
//        "user-003" to "王五"
//    )
//
//    users.forEach { (userId, userName) ->
//        println("\n--- 用户: $userName ($userId) ---")
//
//        // 每个用户发送一条消息
//        val userMsg = ChannelMessage.builder()
//            .sessionId("session-$userId")
//            .content("你好，我是$userName，请帮我制定工作计划")
//            .channelType(ChannelType.HTTP)
//            .senderId(userId)
//            .senderName(userName)
//            .build()
//
//        // 处理消息
//        val reply = processWithAgent(httpChannel, userMsg, sessionManager)
//
//        println("用户: ${userMsg.content}")
//        println("AI: $reply")
//    }
//
//    // ========== 场景2: 企业微信群聊 ==========
//    println("\n" + "=".repeat(60))
//    println("场景2: 企业微信群聊对话")
//    println("=".repeat(60))
//
//    val wecomChannel = channels["wecom-bot"]!!
//    val groupId = "group-001"
//
//    println("\n群组ID: $groupId")
//
//    // 模拟群聊中多个成员发言
//    val groupMessages = listOf(
//        Triple("张三", "user-001", "@机器人 今天有什么任务？"),
//        Triple("李四", "user-002", "还有我的任务呢？"),
//        Triple("王五", "user-003", "帮忙总结一下今天的任务")
//    )
//
//    groupMessages.forEach { (name, userId, content) ->
//        val groupMsg = ChannelMessage.builder()
//            .sessionId(groupId)
//            .content(content)
//            .channelType(ChannelType.WECOM)
//            .senderId(userId)
//            .senderName(name)
//            .isGroupMessage(true)
//            .groupId(groupId)
//            .build()
//
//        val reply = processWithAgent(wecomChannel, groupMsg, sessionManager)
//
//        println("\n$name: $content")
//        println("机器人: $reply")
//    }
//
//    // ========== 场景3: 查看会话统计 ==========
//    println("\n" + "=".repeat(60))
//    println("场景3: 会话统计和历史查询")
//    println("=".repeat(60))
//
//    println("\n所有 Channel 会话统计:")
//    println(sessionManager.getStats())
//
//    println("\nHTTP Channel 会话历史 (user-001):")
//    val history = sessionManager.getHistory(httpChannel.id, "session-user-001")
//    history.forEach { msg ->
//        println("  [${msg.role}] ${msg.senderName ?: "未知"}: ${msg.content}")
//    }
//
//    println("\n企业微信群聊历史 ($groupId):")
//    val groupHistory = sessionManager.getHistory(wecomChannel.id, groupId)
//    groupHistory.forEach { msg ->
//        println("  [${msg.role}] ${msg.senderName ?: "机器人"}: ${msg.content}")
//    }
//
//    println("\n" + "=".repeat(60))
//    println("完整集成示例运行完成！")
//    println("=".repeat(60))
//}
//
///**
// * 模拟 Agent 处理消息
// * 实际使用时替换为真实的 AgentService 调用
// */
//private suspend fun processWithAgent(
//    channel: ChannelSpec,
//    message: ChannelMessage,
//    sessionManager: InMemoryChannelSessionManager
//): String {
//    // 1. 保存用户消息
//    sessionManager.addMessage(channel.id, message)
//
//    // 2. 获取历史消息
//    val history = sessionManager.getHistory(channel.id, message.sessionId)
//    val agentMessages = sessionManager.toAgentMessages(history)
//
//    // 3. 模拟 AI 处理（这里应该是调用真实的 Agent）
//    val reply = mockAgentProcess(channel.agentId, agentMessages)
//
//    // 4. 保存 AI 回复
//    val assistantMessage = ChannelMessage.builder()
//        .sessionId(message.sessionId)
//        .role(MessageRole.ASSISTANT)
//        .content(reply)
//        .channelType(message.channelType)
//        .build()
//    sessionManager.addMessage(channel.id, assistantMessage)
//
//    return reply
//}
//
///**
// * 模拟 Agent 处理逻辑
// * 实际使用时替换为真实的 Agent 调用
// */
//private fun mockAgentProcess(agentId: Long, messages: List<AgentMessage>): String {
//    val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""
//
//    // 根据 agentId 返回不同的模拟回复
//    return when (agentId) {
//        100L -> "HTTP Agent 回复: 我收到了您的消息「$lastUserMessage」，正在为您处理..."
//        101L -> "企业微信客服: 收到您的请求「$lastUserMessage」，已记录，稍后回复。"
//        102L -> "飞书助手: 好的，我已收到「$lastUserMessage」，正在查询相关信息..."
//        else -> "收到: $lastUserMessage"
//    }
//}
