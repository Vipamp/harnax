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
// * 基础使用示例 - 最简单的 Channel 消息处理
// *
// * 运行方式: 直接运行 main 函数
// */
//fun main() = runBlocking {
//    println("=" .repeat(60))
//    println("vipclaw-channel 基础使用示例")
//    println("=" .repeat(60))
//
//    // ========== 1. 创建会话管理器 ==========
//    println("\n【步骤1】创建会话管理器（内存版）")
//    val sessionManager = InMemoryChannelSessionManager()
//
//    // ========== 2. 创建 Channel 配置 ==========
//    println("\n【步骤2】创建 Channel 配置")
//    val channel = ChannelSpec.builder()
//        .id(1L)
//        .name("测试HTTP通道")
//        .type(ChannelType.HTTP)
//        .agentId(100L)  // 关联的智能体ID
//        .callbackKey("test-key-123")  // 回调标识，用于生成回调URL
//        .token("my-secret-token")  // 可选：验证token
//        .build()
//    println("Channel配置: id=${channel.id}, name=${channel.name}, type=${channel.type}")
//
//    // ========== 3. 模拟用户消息 ==========
//    println("\n【步骤3】创建用户消息")
//    val userMessage = ChannelMessage.builder()
//        .sessionId("session-001")  // 会话ID，同一用户/群的对话使用相同ID
//        .content("你好，请介绍一下自己")
//        .channelType(ChannelType.HTTP)
//        .senderId("user-001")
//        .senderName("测试用户")
//        .build()
//    println("用户消息: ${userMessage.content}")
//
//    // ========== 4. 保存用户消息 ==========
//    println("\n【步骤4】保存用户消息到会话历史")
//    sessionManager.addMessage(channel.id, userMessage)
//
//    // ========== 5. 获取历史消息 ==========
//    println("\n【步骤5】获取会话历史消息")
//    val history = sessionManager.getHistory(channel.id, userMessage.sessionId)
//    val agentMessages = sessionManager.toAgentMessages(history)
//    println("历史消息数量: ${agentMessages.size}")
//    agentMessages.forEach { msg ->
//        println("  [${msg.role}]: ${msg.content}")
//    }
//
//    // ========== 6. 模拟 AI 回复 ==========
//    println("\n【步骤6】模拟 AI 回复")
//    val aiReply = "你好！我是 AI 助手，很高兴为您服务。我可以回答问题、协助工作等。"
//    println("AI回复: $aiReply")
//
//    // ========== 7. 保存 AI 回复 ==========
//    println("\n【步骤7】保存 AI 回复到会话历史")
//    val assistantMessage = ChannelMessage.builder()
//        .sessionId(userMessage.sessionId)
//        .role(MessageRole.ASSISTANT)
//        .content(aiReply)
//        .channelType(ChannelType.HTTP)
//        .build()
//    sessionManager.addMessage(channel.id, assistantMessage)
//
//    // ========== 8. 多轮对话示例 ==========
//    println("\n【步骤8】多轮对话示例")
//
//    // 第二轮对话
//    val userMessage2 = ChannelMessage.builder()
//        .sessionId("session-001")
//        .content("你能做什么？")
//        .channelType(ChannelType.HTTP)
//        .senderId("user-001")
//        .build()
//
//    sessionManager.addMessage(channel.id, userMessage2)
//
//    // 获取完整历史
//    val history2 = sessionManager.getHistory(channel.id, userMessage.sessionId)
//    println("当前会话消息数: ${history2.size}")
//    history2.forEach { msg ->
//        println("  [${msg.role}]: ${msg.content}")
//    }
//
//    // ========== 9. 查看会话统计 ==========
//    println("\n【步骤9】会话统计")
//    println("统计信息: ${sessionManager.getStats()}")
//
//    println("\n" + "=".repeat(60))
//    println("示例运行完成！")
//    println("=".repeat(60))
//}
