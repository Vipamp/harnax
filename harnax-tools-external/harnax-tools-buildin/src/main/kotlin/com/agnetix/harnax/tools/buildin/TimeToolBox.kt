package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.NeedConfirmed
import com.agnetix.harnax.tools.sdk.ToolBox
import io.agentscope.core.tool.Tool
import org.springframework.stereotype.Component
import java.text.SimpleDateFormat
import java.util.*

/**
 * Built-in datetime tools for agent use.
 *
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: Built-in datetime tools (date / datetime)
 * @Project: harnax
 */
@Component("time-tool-box")
class TimeToolBox : ToolBox() {

    @Tool(description = "获取当前日期")
    @NeedConfirmed
    fun getDate(): String = execute { SimpleDateFormat(YYYY_MM_DD).format(Date()) }

    @Tool(description = "获取当前时间")
    @NeedConfirmed
    fun getDatetime(): String = execute { SimpleDateFormat(YYYY_MM_DD_HH_MM_SS).format(Date()) }

    override fun name(): String = NAME

    companion object {
        const val NAME = "datetime-tool-box"
        const val YYYY_MM_DD = "yyyy-MM-dd"
        const val YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss"
    }
}
