package com.agnetix.harnax.tools.builtin

import com.agnetix.harnax.tools.sdk.ToolBox
import com.agnetix.harnax.tools.sdk.ToolMeta
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

    @Tool(name = "getDate", description = "获取当前日期", readOnly = true)
    @ToolMeta(displayName = "Get Date", displayNameZh = "获取日期", needConfirm = true)
    fun getDate(): String = execute { SimpleDateFormat(YYYY_MM_DD).format(Date()) }

    @Tool(name = "getDatetime", description = "获取当前时间", readOnly = true)
    @ToolMeta(displayName = "Get DateTime", displayNameZh = "获取时间", needConfirm = true)
    fun getDatetime(): String = execute { SimpleDateFormat(YYYY_MM_DD_HH_MM_SS).format(Date()) }

    override fun name(): String = NAME

    companion object {
        const val NAME = "time-tool-box"
        const val YYYY_MM_DD = "yyyy-MM-dd"
        const val YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss"
    }
}
