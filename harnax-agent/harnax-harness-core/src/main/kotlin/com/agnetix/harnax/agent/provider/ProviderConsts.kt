package com.agnetix.harnax.agent.provider

import com.agnetix.harnax.agent.provider.middleware.ConfirmToolsMiddleware
import com.agnetix.harnax.agent.provider.middleware.ProcessLogMiddleware
import com.agnetix.harnax.agent.provider.tool.TimeToolBox
import io.agentscope.core.middleware.MiddlewareBase

/**
 * Provider constants for agentscope 2.0.0.
 *
 * In 2.0.0, Hook → MiddlewareBase. HOOK_SET → MIDDLEWARE_SET.
 */

val MIDDLEWARE_SET: Set<MiddlewareBase> = setOf(
    ProcessLogMiddleware(),
    ConfirmToolsMiddleware(),
)

val TOOL_SET = setOf(
    TimeToolBox(),
)
