//package com.vipamp.vipclaw.channel.demo
//
//import com.fasterxml.jackson.databind.ObjectMapper
//import com.fasterxml.jackson.module.kotlin.registerKotlinModule
//import com.vipamp.vipclaw.channel.ChannelSpec
//import com.vipamp.vipclaw.channel.ChannelType
//import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptorFactory
//import com.vipamp.vipclaw.channel.adaptor.http.HttpChannelRequest
//import com.vipamp.vipclaw.channel.message.ChannelMessage
//import com.vipamp.vipclaw.channel.message.MessageRole
//import com.vipamp.vipclaw.channel.session.InMemoryChannelSessionManager
//
///**
// * HTTP 通道详细使用示例
// * 演示如何构建 HTTP API 接口
// */
//object HttpChannelDemo {
//
//    private val objectMapper = ObjectMapper().registerKotlinModule()
//    private val sessionManager = InMemoryChannelSessionManager()
//
//    @JvmStatic
//    fun main() {
//        println("=".repeat(60))
//        println("HTTP Channel 接口使用示例")
//        println("=".repeat(60))
//
//        // 创建 Channel 配置
//        val channel = ChannelSpec.builder()
//            .id(1L)
//            .name("HTTP API Channel")
//            .type(ChannelType.HTTP)
//            .agentId(1L)
//            .callbackKey("http-demo-key")
//            .token("demo-token-123")  // 验证token
//            .webhookUrl("https://your-server.com/webhook")  // 可选：用于主动推送
//            .build()
//
//        // 获取适配器
//        val adaptor = ChannelAdaptorFactory.getAdaptor(ChannelType.HTTP)!!
//
//        // ========== 示例1: 接收并处理请求 ==========
//        println("\n【示例1】处理 HTTP 请求")
//
//        // 模拟客户端请求体
//        val requestJson = """
//            {
//                "sessionId": "user-session-001",
//                "userId": "user-123",
//                "userName": "张三",
//                "content": "今天天气怎么样？"
//            }
//        """.trimIndent()
//
//        println("请求体:\n$requestJson")
//
//        // 解析请求
//        val httpRequest = objectMapper.readValue(requestJson, HttpChannelRequest::class.java)
//
//        // 构建统一消息对象
//        val message = ChannelMessage.builder()
//            .sessionId(httpRequest.sessionId ?: "default")
//            .content(httpRequest.content ?: "")
//            .channelType(ChannelType.HTTP)
//            .senderId(httpRequest.userId)
//            .senderName(httpRequest.userName)
//            .build()
//
//        println("\n解析后的消息:")
//        println("  sessionId: ${message.sessionId}")
//        println("  content: ${message.content}")
//        println("  senderId: ${message.senderId}")
//        println("  senderName: ${message.senderName}")
//
//        // ========== 示例2: Token 验证说明 ==========
//        println("\n【示例2】Token 验证方式")
//        println("""
//            |HTTP 接口支持三种 Token 验证方式：
//            |
//            |方式1 - Authorization Header:
//            |  curl -X POST http://localhost:8080/api/channel/chat/http-demo-key \
//            |    -H "Authorization: Bearer demo-token-123" \
//            |    -H "Content-Type: application/json" \
//            |    -d '{"content": "你好"}'
//            |
//            |方式2 - X-Channel-Token Header:
//            |  curl -X POST http://localhost:8080/api/channel/chat/http-demo-key \
//            |    -H "X-Channel-Token: demo-token-123" \
//            |    -H "Content-Type: application/json" \
//            |    -d '{"content": "你好"}'
//            |
//            |方式3 - Query Parameter:
//            |  curl -X POST "http://localhost:8080/api/channel/chat/http-demo-key?token=demo-token-123" \
//            |    -H "Content-Type: application/json" \
//            |    -d '{"content": "你好"}'
//        """.trimMargin())
//
//        // ========== 示例3: 多轮对话处理 ==========
//        println("\n【示例3】多轮对话处理")
//
//        // 模拟多轮对话
//        val conversations = listOf(
//            "你好" to "你好！有什么可以帮助你的吗？",
//            "我想查天气" to "请问您想查询哪个城市的天气？",
//            "北京" to "北京今天晴，温度18-25℃，适合出行。"
//        )
//
//        conversations.forEach { (userInput, aiReply) ->
//            // 保存用户消息
//            val userMsg = ChannelMessage.builder()
//                .sessionId("multi-turn-session")
//                .content(userInput)
//                .channelType(ChannelType.HTTP)
//                .build()
//            sessionManager.addMessage(channel.id, userMsg)
//
//            // 模拟AI处理并保存回复
//            val aiMsg = ChannelMessage.builder()
//                .sessionId("multi-turn-session")
//                .role(MessageRole.ASSISTANT)
//                .content(aiReply)
//                .channelType(ChannelType.HTTP)
//                .build()
//            sessionManager.addMessage(channel.id, aiMsg)
//
//            println("  用户: $userInput")
//            println("  AI: $aiReply")
//        }
//
//        // 查看完整历史
//        println("\n完整对话历史:")
//        val history = sessionManager.getHistory(channel.id, "multi-turn-session")
//        history.forEach { msg ->
//            println("  [${msg.role}]: ${msg.content}")
//        }
//
//        // ========== 示例4: 构建响应 ==========
//        println("\n【示例4】构建 HTTP 响应")
//
//        val reply = "这是AI的回复内容"
//        val response = adaptor.buildResponse(reply, message)
//        val responseJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)
//
//        println("响应格式:")
//        println(responseJson)
//
//        // ========== 示例5: 回调 URL 说明 ==========
//        println("\n【示例5】回调 URL 格式")
//
//        val baseUrl = "http://localhost:8080/admin"
//        val callbackUrl = "$baseUrl/api/channel/callback/${channel.type.code}/${channel.callbackKey}"
//        val chatUrl = "$baseUrl/api/channel/chat/${channel.callbackKey}"
//
//        println("""
//            |统一回调入口:
//            |  POST $callbackUrl
//            |
//            |HTTP 专用接口:
//            |  POST $chatUrl
//            |
//            |获取历史:
//            |  GET $baseUrl/api/channel/history/${channel.callbackKey}/{sessionId}
//            |
//            |清除历史:
//            |  DELETE $baseUrl/api/channel/history/${channel.callbackKey}/{sessionId}
//        """.trimMargin())
//
//        println("\n" + "=".repeat(60))
//        println("HTTP Channel 示例完成！")
//        println("=".repeat(60))
//    }
//}
