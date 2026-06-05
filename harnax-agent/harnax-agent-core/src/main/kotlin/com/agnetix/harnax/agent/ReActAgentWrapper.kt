package com.agnetix.harnax.agent

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatEventConverter
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.message.*
import io.agentscope.core.session.SessionManager
import reactor.core.publisher.Flux
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ReActAgentWrapper
 * @Project: harnax
 */
class ReActAgentWrapper(
    val reActAgent: ReActAgent,
    val dangerousTools: Set<String>,
    val sessionManager: SessionManager? = null,
    val tokenStatBuilder: TokenStatBuilder,
    val tokenStatAdaptor: TokenStatAdaptor,
) {
    fun callStream(
        prompt: String,
        imageUrls: List<String> = listOf(),
        options: StreamOptions = StreamOptions.builder().build(),
    ): Flux<ChatEvent> {
        val list: MutableList<ContentBlock> = mutableListOf()
        prompt.let { list.add(textBlock(it)) }
        imageUrls.stream().forEach { list.add(imageBlock(it)) }
        return callStream(options = options, msg = Msg.builder().name("user").role(MsgRole.USER).content(list).build())
    }

    fun callStream(
        options: StreamOptions = StreamOptions.builder().build(),
        msg: Msg? = null,
    ): Flux<ChatEvent> = callStream(options = options, msg = msg?.let { arrayOf(msg) } ?: arrayOf())

    private fun callStream(
        options: StreamOptions,
        vararg msg: Msg = arrayOf(),
    ): Flux<ChatEvent> = reActAgent.stream(msg.toList(), options)
        .doOnNext { sessionManager?.saveSession() }
        .flatMap { ChatEventConverter.convert(it, dangerousTools) }
        .doOnNext { extracted(it) }
        .doOnComplete { /* 流完成时会自动发射 EndEvent */ }
        .concatWith(Flux.just(EndEventChatEvent()))

    private fun textBlock(prompt: String): TextBlock = TextBlock.builder().text(prompt).build()

    private fun imageBlock(url: String): ImageBlock {
        // 判断是 Base64 数据 URL 还是文件路径
        return if (url.startsWith("data:image")) {
            // Base64 数据 URL 格式: data:image/png;base64,iVBORw0KGgo...
            val parts = url.split(",")
            val mimeType = parts[0].substringAfter(":").substringBefore(";")
            val base64Data = parts[1]

            ImageBlock.builder().source(
                Base64Source.builder()
                    .data(base64Data)
                    .mediaType(mimeType)
                    .build(),
            ).build()
        } else {
            // 文件路径
            ImageBlock.builder().source(
                Base64Source.builder()
                    .data(Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get(url))))
                    .mediaType("image/png")
                    .build(),
            ).build()
        }
    }

    private fun extracted(it: ChatEvent) {
        if (it.tokenUsage != null) {
            tokenStatAdaptor.saveTokenStat(
                tokenStatBuilder.inputToken(it.tokenUsage!!.inputTokens)
                    .outputToken(it.tokenUsage!!.outputTokens)
                    .totalToken(it.tokenUsage!!.totalTokens)
                    .build(),
            )
        }
    }
}
