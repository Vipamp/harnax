package com.vipamp.vipclaw.agent.provider

import com.vipamp.vipclaw.agent.provider.tool.TimeToolBox
import com.vipamp.vipclaw.agent.provider.tool.ToolBox

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ToolboxProvider
 * @Project: vipclaw
 */
class ToolboxProvider {

    val toolboxMap = mapOf<String, ToolBox>(
        Pair(TimeToolBox.NAME, TimeToolBox()),
    )

    fun getToolbox(toolboxName: String): ToolBox? = toolboxMap[toolboxName]

    companion object {
        val INSTANCE = ToolboxProvider()
    }
}
