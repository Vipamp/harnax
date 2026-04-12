package com.vipamp.vipclaw.agent

import com.vipamp.vipclaw.agent.adaptor.TokenStatAdaptor
import com.vipamp.vipclaw.agent.adaptor.token.TokenStatBuilder
import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.chat.ChatEventConverter
import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.StreamOptions
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.session.SessionManager
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ReActAgentWrapper
 * @Project: vipclaw
 */
class ReActAgentWrapper(
    val reActAgent: ReActAgent,
    val dangerousTools: Set<String>,
    val sessionManager: SessionManager? = null,
    val tokenStatBuilder: TokenStatBuilder,
    val tokenStatAdaptor: TokenStatAdaptor
) {
    fun streamTextAll(prompt: String): Flux<ChatEvent> {
        return reActAgent.stream(userMsg(prompt))
            .flatMap { ChatEventConverter.convert(it, dangerousTools) }
            .doOnNext { extracted(it) }
            .doOnNext { sessionManager?.saveSession() }
    }

    fun stream(msg: Msg): Flux<ChatEvent> {
        return reActAgent.stream(msg)
            .flatMap { ChatEventConverter.convert(it, dangerousTools) }
            .doOnNext { extracted(it) }
            .doOnNext { sessionManager?.saveSession() }
    }

    fun stream(options: StreamOptions): Flux<ChatEvent> {
        return reActAgent.stream(options)
            .flatMap { ChatEventConverter.convert(it, dangerousTools) }
            .doOnNext { extracted(it) }
            .doOnNext { sessionManager?.saveSession() }
    }

    private fun userMsg(prompt: String): Msg = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder().text(prompt).build())
        .build()

    private fun extracted(it: ChatEvent) {
        if (it.tokenUsage != null) {
            tokenStatAdaptor.saveTokenStat(
                tokenStatBuilder.inputToken(it.tokenUsage!!.inputTokens)
                    .outputToken(it.tokenUsage!!.outputTokens)
                    .totalToken(it.tokenUsage!!.totalTokens)
                    .build()
            )
        }
    }
}
