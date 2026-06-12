package com.agnetix.harnax.agent.provider

import com.agnetix.harnax.agent.provider.hook.ConfirmToolsHook
import com.agnetix.harnax.agent.provider.hook.ProcessLogHook
import com.agnetix.harnax.agent.provider.tool.TimeToolBox

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ProviderConsts
 * @Project: harnax
 */

val HOOK_SET = setOf(
    ProcessLogHook(),
    ConfirmToolsHook(),
)

val TOOL_SET = setOf(
    TimeToolBox(),
)
