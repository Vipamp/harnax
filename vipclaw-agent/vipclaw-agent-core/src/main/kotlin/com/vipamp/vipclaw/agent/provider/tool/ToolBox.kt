package com.vipamp.vipclaw.agent.provider.tool

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ToolBox
 * @Project: vipclaw
 */
interface ToolBox {
    fun name(): String
    fun dangerousTools(): Set<String> = setOf()
}
