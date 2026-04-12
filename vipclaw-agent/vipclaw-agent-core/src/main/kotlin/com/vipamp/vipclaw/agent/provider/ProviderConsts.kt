package com.vipamp.vipclaw.agent.provider

import com.vipamp.vipclaw.agent.provider.hook.ConfirmToolsHook
import com.vipamp.vipclaw.agent.provider.hook.ProcessLogHook
import com.vipamp.vipclaw.agent.provider.tool.TimeToolBox

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ProviderConsts
 * @Project: vipclaw
 */

val HOOK_SET = setOf(
    ProcessLogHook(),
    ConfirmToolsHook()
)

val TOOL_SET = setOf(
    TimeToolBox()
)
