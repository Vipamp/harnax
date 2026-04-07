package com.vipamp.vipclaw.agent.provider.tool

import io.agentscope.core.tool.Tool
import java.text.SimpleDateFormat
import java.util.*

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: InnerToolBoxes
 * @Project: vipclaw
 */
class TimeToolBox : ToolBox {

    @Tool(description = "获取当前日期")
    fun getDate(): String = SimpleDateFormat(YYYY_MM_DD).format(Date())

    @Tool(description = "获取当前时间")
    fun getDatetime(): String = SimpleDateFormat(YYYY_MM_DD_HH_MM_SS).format(Date())

    override fun name(): String = NAME

    companion object {
        const val NAME = "datetime-tool-box"
        const val YYYY_MM_DD = "yyyy-MM-dd"
        const val YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss"
    }
}
