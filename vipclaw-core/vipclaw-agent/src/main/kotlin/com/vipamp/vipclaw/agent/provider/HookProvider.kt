package com.vipamp.vipclaw.agent.provider

import com.vipamp.vipclaw.agent.provider.hook.ProcessLogHook
import io.agentscope.core.hook.Hook

/**
 * @Author: heqingsong
 * @Date: 2026/3/31
 * @Description: HookProvider
 * @Project: vipclaw
 */
class HookProvider {

    val hookMap = mapOf<String, AgentHook>(
        Pair(ProcessLogHook.NAME, ProcessLogHook())
    )

    fun getHook(hoolName: String): AgentHook? {
        return hookMap[hoolName]
    }

    fun necessaryHooks(): List<AgentHook> {
        return hookMap.values.filter { it.isNecessary() }
    }

    interface AgentHook : Hook {
        fun name(): String
        fun isNecessary(): Boolean
    }
}
