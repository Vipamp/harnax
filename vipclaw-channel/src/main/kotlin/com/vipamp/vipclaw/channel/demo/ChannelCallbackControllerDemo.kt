//package com.vipamp.vipclaw.channel.demo
//
//import com.vipamp.vipclaw.channel.ChannelSpec
//import com.vipamp.vipclaw.channel.ChannelType
//import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptor
//import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptorFactory
//import com.vipamp.vipclaw.channel.message.ChannelMessage
//import com.vipamp.vipclaw.channel.session.AgentMessage
//import com.vipamp.vipclaw.channel.session.ChannelSessionManager
//import org.slf4j.LoggerFactory
//import org.springframework.http.ResponseEntity
//import org.springframework.web.bind.annotation.*
//
///**
// * Channel 回调控制器 Demo
// * 演示如何在 Spring Boot 中集成 Channel 模块
// *
// * 将此 Controller 复制到 vipclaw-admin 模块即可使用
// *
// * 回调 URL 格式:
// * POST /api/channel/callback/{type}/{callbackKey}
// *
// * 示例:
// * - 企业微信: POST /api/channel/callback/wecom/abc123
// * - 飞书: POST /api/channel/callback/feishu/abc123
// * - 钉钉: POST /api/channel/callback/dingtalk/abc123
// * - HTTP: POST /api/channel/callback/http/abc123
// */
//// @RestController
//// @RequestMapping("/api/channel")
//class ChannelCallbackControllerDemo(
//    private val sessionManager: ChannelSessionManager,
//    // 注入 Agent 处理服务
//    // private val agentService: AgentService
//) {
//
//    private val logger = LoggerFactory.getLogger(ChannelCallbackControllerDemo::class.java)
//
//    /**
//     * 统一回调入口
//     *
//     * @param type 平台类型 (wecom/feishu/dingtalk/http)
//     * @param callbackKey 回调标识（用于查找 Channel 配置）
//     */
//    // @PostMapping("/callback/{type}/{callbackKey}")
//    suspend fun handleCallback(
//        @PathVariable type: String,
//        @PathVariable callbackKey: String,
//        request: jakarta.servlet.http.HttpServletRequest
//    ): ResponseEntity<Any> {
//        logger.info("Received callback: type={}, callbackKey={}", type, callbackKey)
//
//        // 1. 获取适配器
//        val adaptor = ChannelAdaptorFactory.getAdaptor(type)
//            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Unsupported channel type: $type"))
//
//        // 2. 查询 Channel 配置（需要从数据库查询）
//        // val channelSpec = channelService.findByCallbackKey(callbackKey)
//        //     ?: return ResponseEntity.notFound().build()
//
//        // Demo: 创建模拟配置
//        val channelSpec = ChannelSpec.builder()
//            .id(1L)
//            .name("Demo Channel")
//            .type(ChannelType.fromCode(type) ?: ChannelType.HTTP)
//            .agentId(1L)
//            .callbackKey(callbackKey)
//            .build()
//
//        // 3. 验证签名
//        if (!adaptor.verifySignature(request, channelSpec)) {
//            logger.warn("Signature verification failed for channel: {}", callbackKey)
//            return ResponseEntity.status(401).body(mapOf("error" to "Invalid signature"))
//        }
//
//        // 4. 处理 URL 验证请求（首次配置时）
//        val verificationResponse = adaptor.handleUrlVerification(request, channelSpec)
//        if (verificationResponse != null) {
//            logger.info("URL verification response: {}", verificationResponse)
//            return ResponseEntity.ok(verificationResponse)
//        }
//
//        // 5. 解析消息
//        val message = adaptor.parseMessage(request)
//        logger.info("Parsed message: sessionId={}, content={}", message.sessionId, message.content)
//
//        // 6. 处理消息
//        val reply = processMessage(channelSpec, adaptor, message)
//
//        // 7. 构建响应
//        val response = adaptor.buildResponse(reply, message)
//
//        return ResponseEntity.ok(response)
//    }
//
//    /**
//     * 处理消息核心逻辑
//     */
//    private suspend fun processMessage(
//        channelSpec: ChannelSpec,
//        adaptor: ChannelAdaptor,
//        message: ChannelMessage
//    ): String {
//        // 1. 保存用户消息到会话
//        sessionManager.addMessage(channelSpec.id, message)
//
//        // 2. 获取历史消息
//        val history = sessionManager.getHistory(channelSpec.id, message.sessionId, limit = 20)
//        val agentMessages = sessionManager.toAgentMessages(history)
//
//        // 3. 调用 Agent 处理
//        // val agent = agentService.getById(channelSpec.agentId)
//        // val reply = agentService.chat(agent, agentMessages)
//
//        // Demo: 模拟 AI 回复
//        val reply = "收到您的消息: ${message.content}。我是 AI 助手，正在为您处理..."
//
//        // 4. 保存 AI 回复
//        val assistantMessage = ChannelMessage.builder()
//            .sessionId(message.sessionId)
//            .role(com.vipamp.vipclaw.channel.message.MessageRole.ASSISTANT)
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
//     * HTTP 专用接口（简化版）
//     * 直接接收 JSON 请求
//     */
//    // @PostMapping("/chat/{callbackKey}")
//    suspend fun httpChat(
//        @PathVariable callbackKey: String,
//        @RequestHeader(value = "Authorization", required = false) authHeader: String?,
//        @RequestHeader(value = "X-Channel-Token", required = false) token: String?,
//        @RequestBody request: HttpRequest
//    ): ResponseEntity<Any> {
//        // 查询 Channel 配置
//        // val channelSpec = channelService.findByCallbackKey(callbackKey)
//        //     ?: return ResponseEntity.notFound().build()
//
//        val channelSpec = ChannelSpec.builder()
//            .id(1L)
//            .name("Demo HTTP Channel")
//            .type(ChannelType.HTTP)
//            .agentId(1L)
//            .callbackKey(callbackKey)
//            .build()
//
//        // 构建 ChannelMessage
//        val message = ChannelMessage.builder()
//            .sessionId(request.sessionId ?: "default")
//            .content(request.content)
//            .channelType(ChannelType.HTTP)
//            .senderId(request.userId)
//            .senderName(request.userName)
//            .build()
//
//        // 处理消息
//        val adaptor = ChannelAdaptorFactory.getAdaptor(ChannelType.HTTP)!!
//        val reply = processMessage(channelSpec, adaptor, message)
//        val response = adaptor.buildResponse(reply, message)
//
//        return ResponseEntity.ok(response)
//    }
//
//    /**
//     * 获取会话历史
//     */
//    // @GetMapping("/history/{callbackKey}/{sessionId}")
//    suspend fun getHistory(
//        @PathVariable callbackKey: String,
//        @PathVariable sessionId: String
//    ): ResponseEntity<List<AgentMessage>> {
//        // val channel = channelService.findByCallbackKey(callbackKey)
//        //     ?: return ResponseEntity.notFound().build()
//
//        val channelId = 1L
//        val history = sessionManager.getHistory(channelId, sessionId)
//        return ResponseEntity.ok(sessionManager.toAgentMessages(history))
//    }
//
//    /**
//     * 清除会话历史
//     */
//    // @DeleteMapping("/history/{callbackKey}/{sessionId}")
//    suspend fun clearHistory(
//        @PathVariable callbackKey: String,
//        @PathVariable sessionId: String
//    ): ResponseEntity<Map<String, Any>> {
//        // val channel = channelService.findByCallbackKey(callbackKey)
//        //     ?: return ResponseEntity.notFound().build()
//
//        val channelId = 1L
//        sessionManager.clearHistory(channelId, sessionId)
//        return ResponseEntity.ok(mapOf("success" to true, "message" to "History cleared"))
//    }
//}
//
///**
// * HTTP 请求格式
// */
//data class HttpRequest(
//    val sessionId: String? = null,
//    val userId: String? = null,
//    val userName: String? = null,
//    val content: String
//)
