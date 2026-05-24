package com.agnetix.harnax.channel.wechat

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
 * WeChat Bot AI Conversation Demo
 *
 * Features:
 * 1. Demonstrates how to use WechatAdaptor to start WeChat channel
 * 2. Receive WeChat user messages
 * 3. Process messages via AgentAdaptor (AI conversation)
 * 4. Send AI replies back through WeChat
 *
 * Two processing modes:
 * - Method A: startChannelWithAgent() (recommended) - one-line setup with automatic orchestration
 * - Method B: startChannel() + manual ChannelChatService.chat() - fine-grained control
 *
 * Usage:
 * 1. Run this Demo
 * 2. Terminal will display QR code content
 * 3. Use WeChat to scan the QR code to login
 * 4. After successful login, send messages to the bot to see AI replies
 *
 * Advantages:
 * - No public IP or domain required
 * - No intranet penetration tools needed
 * - Based on long polling mode, suitable for local development
 */
object WechatBotDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(70))
        println("WeChat Bot AI Conversation Demo")
        println("=".repeat(70))
        println()

        // 1. Configure channel
        val channel = ChannelSpec.builder()
            .id(1L)
            .name("WeChat AI Assistant")
            .type(ChannelType.WECHAT)
            .agentId(1L)
            .callbackKey("wechat-demo")
            .communicationMode("long-polling")
            .build()

        // 2. Create WeChat adaptor
        val wechatAdaptor = WechatAdaptor()

        // 3. Create AI Agent processor
        val agentAdaptor = SimpleAiAgent()

        // 4. Create session manager for conversation history
        val sessionManager = InMemorySessionManager()

        println("Starting WeChat channel...")
        println("After login, user messages will be automatically replied by AI")
        println("Type 'quit' to exit the program")
        println()

        // Start WeChat channel with AI Agent integration (recommended)
        // This convenience method automatically creates ChannelChatService internally,
        // handling the complete flow: receive -> AgentAdaptor -> send reply
        wechatAdaptor.startChannelWithAgent(channel, agentAdaptor, sessionManager)

        // Wait for user input to exit
        val scanner = Scanner(System.`in`)
        while (true) {
            val input = scanner.nextLine()
            if (input.equals("quit", ignoreCase = true)) {
                println("Closing WeChat channel...")
                wechatAdaptor.stopChannel(channel)
                println("Exited")
                break
            }
        }
    }
}

/**
 * Simple AI Agent Example
 *
 * This is the simplest AgentAdaptor implementation,
 * should be replaced with LLM API integration in actual projects.
 *
 * Supports both batch and streaming modes:
 * - process(): Returns a complete response (batch mode)
 * - streamProcess(): Returns Flow of AgentStreamEvent (streaming mode)
 *
 * In actual projects, streamProcess() should call LLM API with streaming output
 * (e.g., OpenAI streaming, Claude streaming) and emit text fragments incrementally.
 */
class SimpleAiAgent : AgentAdaptor() {

    override fun getName(): String = "simple-ai-agent"

    override fun supportsStreaming(): Boolean = true

    override suspend fun process(context: AgentContext): AgentResponse {
        val userMessage = context.message.content
        val reply = generateReply(userMessage)
        return AgentResponse(
            content = reply,
            shouldReply = true,
        )
    }

    /**
     * Streaming process - simulates AI text generation with incremental output
     *
     * In actual projects, this should call LLM streaming API,
     * emitting TextStreamEvent for each text fragment received.
     */
    override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val userMessage = context.message.content
        val reply = generateReply(userMessage)

        // Simulate streaming output by splitting reply into fragments
        val words = reply.chunked(5) // Split into 5-char fragments
        for ((index, word) in words.withIndex()) {
            emit(
                AgentStreamEvent.TextStreamEvent(
                    content = word,
                    isLast = index == words.lastIndex,
                ),
            )
        }
        emit(AgentStreamEvent.EndStreamEvent(fullContent = reply))
    }

    /**
     * Generate reply
     *
     * This is an example implementation, in actual project should:
     * 1. Call LLM API (e.g., OpenAI, Claude, etc.)
     * 2. Manage conversation context and memory
     * 3. Handle streaming responses
     */
    private fun generateReply(userMessage: String): String = when {
        userMessage.contains("你好") || userMessage.contains("hello", ignoreCase = true) ->
            "Hello! I'm an AI assistant, how can I help you?"
        userMessage.contains("再见") || userMessage.contains("bye", ignoreCase = true) ->
            "Goodbye! Have a wonderful day!"
        userMessage.contains("帮助") || userMessage.contains("help", ignoreCase = true) ->
            "I can answer your questions and have conversations with you. Feel free to ask me anything!"
        userMessage.contains("天气") ->
            "Sorry, I cannot retrieve real-time weather information at the moment. Please check a weather forecast app."
        else ->
            "I received your message: \"$userMessage\"\nThis is a sample AI Agent, please integrate with LLM API for intelligent conversation capabilities in actual use."
    }
}

/**
 * In-memory Session Manager for Demo
 *
 * Simple implementation that stores session history in memory.
 * In actual projects, this should be replaced with database-backed implementation.
 */
class InMemorySessionManager : ChannelSessionManager {
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
