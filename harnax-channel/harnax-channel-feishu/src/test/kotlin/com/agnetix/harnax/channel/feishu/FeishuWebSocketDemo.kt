package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Scanner

/**
 * Feishu WebSocket Long Connection Mode Demo
 *
 * Features:
 * 1. Demonstrates how to configure Feishu WebSocket mode
 * 2. Explains use cases and advantages of WebSocket mode
 * 3. Provides complete configuration examples and usage guide
 * 4. Shows ChannelChatService integration with startChannelWithAgent()
 *
 * Two processing modes:
 * - Method A: startChannelWithAgent() (recommended) - one-line setup with automatic orchestration
 * - Method B: startChannel() + manual message handling - fine-grained control
 *
 * Usage:
 * 1. Create an enterprise self-built application on Feishu Open Platform
 * 2. Get App ID and App Secret
 * 3. Run this Demo, enter credentials to view configuration examples
 *
 * Advantages:
 * - No public IP or domain required
 * - No intranet penetration tools needed
 * - Built-in encryption and authentication
 * - Suitable for local development and intranet deployment
 */
object FeishuWebSocketDemo {

    private val scanner = Scanner(System.`in`)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(70))
        println("Feishu WebSocket Long Connection Mode Demo")
        println("=".repeat(70))

        println(
            """
            
            [Usage Instructions]
            1. Login to Feishu Open Platform: https://open.feishu.cn
            2. Create an enterprise self-built application
            3. Get the application's App ID and App Secret
            4. Enable bot functionality in the application
            5. Add event subscription: im.message.receive_v1
            6. Run this demo for verification testing
            
            [WebSocket Mode Advantages]
            - No public IP or domain required
            - No intranet penetration (e.g., ngrok) needed
            - Built-in encrypted transmission, no signature handling needed
            - Suitable for local development environment and intranet deployment
            
            """.trimIndent(),
        )

        // Get user input
        println("\n[Step 1] Please enter Feishu application configuration")
        println("-".repeat(70))

        print("Please enter App ID (e.g., cli_xxxxxxxxxxxxx): ")
        val appId = scanner.nextLine().trim()

        print("Please enter App Secret: ")
        val appSecret = scanner.nextLine().trim()

        if (appId.isBlank() || appSecret.isBlank()) {
            println("\n❌ Error: App ID and App Secret cannot be empty")
            return
        }

        println("\n✅ Configuration information received")
        println("  - App ID: ${maskSecret(appId, 4, 4)}")
        println("  - App Secret: ${maskSecret(appSecret)}")

        // Create Channel configuration
        println("\n[Step 2] Create Feishu Channel configuration (WebSocket mode)")
        println("-".repeat(70))

        val channel = ChannelSpec.builder()
            .id(1L)
            .name("Feishu WebSocket Demo")
            .type(ChannelType.FEISHU)
            .agentId(1L)
            .callbackKey("feishu-ws-demo")
            .appId(appId)
            .appSecret(appSecret)
            .communicationMode("websocket")
            .build()

        println("✅ Channel configuration created successfully:")
        println("  - 名称: ${channel.name}")
        println("  - 类型: ${channel.type.displayName}")
        println("  - 通信模式: ${channel.communicationMode}")
        println("  - App ID: ${maskSecret(channel.appId ?: "", 4, 4)}")
        println("  - Agent ID: ${channel.agentId}")

        // Start WebSocket connection
        println("\n[Step 3] Start WebSocket connection")
        println("-".repeat(70))

        val adaptor = FeishuAdaptor()
        val agentAdaptor = DemoFeishuAgent()
        val sessionManager = DemoSessionManager()

        // Method A: Recommended - use startChannelWithAgent() for automatic orchestration
        // This automatically handles: session management, AI processing, message sending
        adaptor.startChannelWithAgent(channel, agentAdaptor, sessionManager)

        println("✅ WebSocket connection started")

        // Block main thread to keep running
        println("\nType 'quit' to exit...")
        while (true) {
            val input = scanner.nextLine()
            if (input.lowercase() == "quit") {
                adaptor.stopChannel(channel)
                println("✅ WebSocket connection closed")
                break
            }
        }

        // Display configuration instructions
        println("\n[Step 4] WebSocket Mode Configuration Guide")
        println("-".repeat(70))

        println(
            """
            📋 WebSocket Mode Configuration Checklist:
            
            ✅ Completed:
            1. Created ChannelSpec with communicationMode="websocket"
            2. Configured appId and appSecret (required)
            3. Set callbackKey to identify the channel
            
            📝 Remaining tasks to complete on Feishu Open Platform:
            1. Enable bot functionality
               Path: App Details -> Bot -> Enable Bot
            
            2. Add event subscription
               Path: App Details -> Event Subscription -> Add Event
               Event type: im.message.receive_v1 (Receive Message v2.0)
            
            3. Publish application version
               Path: App Version Management & Publishing -> Create Version -> Apply for Publishing
            
            4. Add bot to group chat
               Add application bot in group chat settings
            
            """.trimIndent(),
        )

        // Display code example
        println("\n[Step 5] Code Usage Example")
        println("-".repeat(70))

        println(
            """
            💻 Kotlin Code Example (Recommended - startChannelWithAgent):
            
            // 1. Create Feishu adaptor and AI agent
            val adaptor = FeishuAdaptor()
            val agentAdaptor = YourAgentAdaptor()  // Implement AgentAdaptor
            val sessionManager = YourSessionManager()  // Implement ChannelSessionManager
            
            // 2. Create Channel configuration
            val channel = ChannelSpec.builder()
                .id(1L)
                .name("Feishu Bot")
                .type(ChannelType.FEISHU)
                .agentId(1L)
                .appId("your_app_id")
                .appSecret("your_app_secret")
                .communicationMode("websocket")  // ← Key configuration
                .callbackKey("feishu-bot")
                .build()
            
            // 3. Start WebSocket channel with AI Agent (one-line setup)
            //    This automatically handles: receive → AgentAdaptor → send reply
            adaptor.startChannelWithAgent(channel, agentAdaptor, sessionManager)
            
            --- Alternative: Manual message handling ---
            
            adaptor.startChannel(channel) { message ->
                val reply = callAIModel(message.content)
                adaptor.sendMessage(channel, message.sessionId, reply)
            }
            
            """.trimIndent(),
        )

        // Display Webhook vs WebSocket comparison
        println("\n[Step 6] Webhook vs WebSocket Comparison")
        println("-".repeat(70))

        println(
            """
            📊 Two Communication Modes Comparison:
            
            ┌──────────────────┬──────────────────┬──────────────────┐
            │ Feature          │ Webhook Mode     │ WebSocket Mode   │
            ├──────────────────┼──────────────────┼──────────────────┤
            │ Public IP        │ ✅ Required      │ ❌ Not required  │
            │ Domain           │ ✅ Required      │ ❌ Not required  │
            │ Intranet Penetr. │ ✅ Required (Dev)│ ❌ Not required  │
            │ Signature Verify │ ✅ Manual handle │ ❌ SDK automatic │
            │ Message Latency  │ Low              │ Low              │
            │ Use Case         │ Production       │ Dev/Intranet     │
            │ Config Complexity│ Medium           │ Simple           │
            │ Network Require. │ Receive external │ Access public    │
            └──────────────────┴──────────────────┴──────────────────┘
            
            💡 Recommendations:
            - Local development: Use WebSocket mode
            - Intranet deployment: Use WebSocket mode
            - Public production: Use Webhook mode (more stable)
            
            """.trimIndent(),
        )

        // Complete
        println("\n" + "=".repeat(70))
        println("Feishu WebSocket Demo completed!")
        println("=".repeat(70))
    }

    /**
     * Mask sensitive information
     */
    private fun maskSecret(
        secret: String,
        showStart: Int = 4,
        showEnd: Int = 0,
    ): String {
        if (secret.length <= showStart + showEnd) {
            return "*".repeat(secret.length)
        }
        val start = secret.take(showStart)
        val end = if (showEnd > 0) secret.takeLast(showEnd) else ""
        val middle = "*".repeat(secret.length - showStart - showEnd)
        return "$start$middle$end"
    }
}

/**
 * Demo Feishu AI Agent
 *
 * Simple AgentAdaptor implementation for Feishu demo.
 * In actual projects, replace with LLM API integration.
 */
class DemoFeishuAgent : AgentAdaptor() {

    override fun getName(): String = "demo-feishu-agent"

    override fun supportsStreaming(): Boolean = true

    override suspend fun process(context: AgentContext): AgentResponse {
        val userMessage = context.message.content
        val reply = "Hello! I received your message: \"$userMessage\""
        return AgentResponse(content = reply, shouldReply = true)
    }

    override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val userMessage = context.message.content
        val reply = "Hello! I received your message: \"$userMessage\""
        emit(AgentStreamEvent.TextStreamEvent(reply, true))
        emit(AgentStreamEvent.EndStreamEvent(fullContent = reply))
    }
}

/**
 * Demo Session Manager
 *
 * In-memory implementation for demo purposes.
 * In actual projects, replace with database-backed implementation.
 */
class DemoSessionManager : ChannelSessionManager {
    private val sessions = mutableMapOf<String, MutableList<com.agnetix.harnax.channel.sdk.message.ChannelMessage>>()

    override suspend fun getHistory(channelId: Long, sessionId: String, limit: Int): List<com.agnetix.harnax.channel.sdk.message.ChannelMessage> {
        val key = "$channelId-$sessionId"
        return sessions[key]?.takeLast(limit) ?: emptyList()
    }

    override suspend fun addMessage(channelId: Long, message: com.agnetix.harnax.channel.sdk.message.ChannelMessage) {
        val key = "$channelId-${message.sessionId}"
        sessions.getOrPut(key) { mutableListOf() }.add(message)
    }

    override suspend fun clearHistory(channelId: Long, sessionId: String) {
        val key = "$channelId-$sessionId"
        sessions.remove(key)
    }
}
