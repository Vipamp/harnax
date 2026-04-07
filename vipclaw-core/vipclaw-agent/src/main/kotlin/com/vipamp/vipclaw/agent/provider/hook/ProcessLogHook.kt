package com.vipamp.vipclaw.agent.provider.hook

import com.vipamp.vipclaw.agent.provider.HookProvider
import io.agentscope.core.hook.HookEvent
import reactor.core.publisher.Mono

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ProcessLogHook
 * @Project: vipclaw
 */
class ProcessLogHook : HookProvider.AgentHook {
    override fun <T : HookEvent?> onEvent(event: T?): Mono<T?>? {
        TODO("Not yet implemented")
    }

    override fun priority(): Int = 500

    override fun name(): String = NAME

    override fun isNecessary(): Boolean = true

    companion object {
        const val NAME = "ProcessLogHook"
    }
}
