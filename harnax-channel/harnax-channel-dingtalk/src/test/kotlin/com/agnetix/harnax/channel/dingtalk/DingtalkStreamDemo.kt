package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import java.util.Scanner

/**
 * DingTalk Stream Long Connection Mode Demo.
 *
 * Manual integration harness (not a unit test): run main(), enter the DingTalk
 * robot AppKey (clientId) and AppSecret, then chat with the bot in DingTalk.
 *
 * Setup on DingTalk Open Platform (https://open-dev.dingtalk.com):
 * 1. Create an enterprise internal application.
 * 2. Get AppKey (clientId) and AppSecret (clientSecret).
 * 3. Add the "机器人" capability and set the message-receiving mode to "Stream".
 * 4. Publish the application version.
 *
 * Advantages (same as Feishu WebSocket): no public IP/domain, no callback URL,
 * SDK-managed auth/heartbeat/reconnect.
 */
object DingtalkStreamDemo {

    private val scanner = Scanner(System.`in`)

    @JvmStatic
    fun main(args: Array<String>) {
        println("=".repeat(70))
        println("DingTalk Stream Long Connection Mode Demo")
        println("=".repeat(70))

        print("Please enter AppKey (clientId): ")
        val clientId = scanner.nextLine().trim()
        print("Please enter AppSecret (clientSecret): ")
        val clientSecret = scanner.nextLine().trim()

        if (clientId.isBlank() || clientSecret.isBlank()) {
            println("\nError: AppKey and AppSecret cannot be empty")
            return
        }

        val channel = ChannelSpec.builder()
            .id(1L)
            .name("DingTalk Stream Demo")
            .type(ChannelType.DINGTALK)
            .agentId(1L)
            .callbackKey("dingtalk-stream-demo")
            .appId(clientId)
            .appSecret(clientSecret)
            .communicationMode("stream")
            .build()

        val adaptor = DingtalkAdaptor()
        adaptor.startChannelWithAgent(channel, DemoDingtalkAgent(), DemoDingtalkSessionManager())

        println("\nStream connection started. Send a message to the bot in DingTalk.")
        println("Type 'quit' here to exit...")
        while (true) {
            val input = scanner.nextLine()
            if (input.lowercase() == "quit") {
                adaptor.stopChannel(channel)
                println("Stream connection closed")
                break
            }
        }
    }
}

private class DemoDingtalkAgent : AgentAdaptor() {
    override fun getName(): String = "demo-dingtalk-agent"
    override suspend fun process(context: AgentContext): AgentResponse {
        val userMessage = context.message.content
        return AgentResponse(content = "Hello! I received your message: \"$userMessage\"", shouldReply = true)
    }
}

private class DemoDingtalkSessionManager : ChannelSessionManager {
    private val sessions = mutableMapOf<String, MutableList<ChannelMessage>>()

    override suspend fun getHistory(channelId: Long, sessionId: String, limit: Int): List<ChannelMessage> = sessions["$channelId-$sessionId"]?.takeLast(limit) ?: emptyList()

    override suspend fun addMessage(channelId: Long, message: ChannelMessage) {
        sessions.getOrPut("$channelId-${message.sessionId}") { mutableListOf() }.add(message)
    }

    override suspend fun clearHistory(channelId: Long, sessionId: String) {
        sessions.remove("$channelId-$sessionId")
    }
}
