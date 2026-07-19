package com.agnetix.harnax.agent.provider

import com.agnetix.harnax.agent.provider.middleware.ProcessLogMiddleware
import io.agentscope.core.middleware.MiddlewareBase

/**
 * Provider constants for agentscope 2.0.0.
 *
 * In 2.0.0, Hook → MiddlewareBase. HOOK_SET → MIDDLEWARE_SET.
 * Built-in tools (e.g. TimeToolBox) moved to harnax-tools-buildin,
 * discovered via ToolRegistry at runtime.
 *
 * Dangerous-tool interception is fully delegated to the built-in
 * PermissionEngine (PermissionContextState ASK/ALLOW/DENY rules +
 * ReActAgent.evaluatePermissions() + RequireUserConfirmEvent).
 * No custom middleware is needed for permission gating.
 */

val MIDDLEWARE_SET: Set<MiddlewareBase> = setOf(
    ProcessLogMiddleware(),
)
