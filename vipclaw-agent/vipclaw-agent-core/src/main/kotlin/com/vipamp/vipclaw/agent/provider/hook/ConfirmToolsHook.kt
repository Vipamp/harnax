package com.vipamp.vipclaw.agent.provider.hook

import io.agentscope.core.hook.Hook
import io.agentscope.core.hook.HookEvent
import io.agentscope.core.hook.PostReasoningEvent
import io.agentscope.core.message.ToolUseBlock
import reactor.core.publisher.Mono

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ConfirmToolsHook
 * @Project: vipclaw
 */
class ConfirmToolsHook : Hook {

    private val dangerousTools: MutableSet<String> = mutableSetOf()

    fun setDangerousTools(dangerousTools: MutableSet<String>) {
        this.dangerousTools.addAll(dangerousTools)
    }

    override fun <T : HookEvent> onEvent(event: T): Mono<T?> {
        if (event is PostReasoningEvent) {
            val reasoningMsg = event.reasoningMessage ?: return Mono.just(event)
            if (reasoningMsg.getContentBlocks(ToolUseBlock::class.java)
                    .stream()
                    .anyMatch { dangerousTools.contains(it!!.name) }
            ) {
                event.stopAgent()
            }
        }
        return Mono.just(event)
    }

    override fun priority(): Int {
        return 0
    }
}