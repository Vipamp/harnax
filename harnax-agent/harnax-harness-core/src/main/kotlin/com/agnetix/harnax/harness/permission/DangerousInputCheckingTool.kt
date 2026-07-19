package com.agnetix.harnax.harness.permission

import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.permission.PermissionBehavior
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionDecision
import io.agentscope.core.tool.AgentTool
import io.agentscope.core.tool.ToolBase
import io.agentscope.core.tool.ToolCallParam
import io.agentscope.core.tool.ToolDangerousPathConstants
import reactor.core.publisher.Mono
import java.util.Locale

/**
 * DangerousInputCheckingTool — wraps an existing [AgentTool] to add runtime input scanning
 * for dangerous patterns (shell commands, sensitive file paths).
 *
 * Overrides [checkPermissions] to inspect all string-valued inputs before the tool executes.
 * If a dangerous pattern is detected, returns an ASK decision with a "safety" reason — this is
 * **bypass-immune** per the PermissionEngine contract (step ③ ASK with "safety" reason cannot
 * be skipped even in BYPASS mode).
 *
 * Detection logic:
 * 1. **Dangerous commands** — substring match against [ToolDangerousPathConstants.DANGEROUS_COMMANDS]
 *    (e.g. `rm -rf`, `sudo rm`, `chmod 777`, `kill -9`).
 * 2. **Dangerous paths** — delegates to [ToolBase.isDangerousPath] which checks filenames
 *    against [ToolDangerousPathConstants.DEFAULT_DANGEROUS_FILES] (e.g. `.env`, `.bashrc`,
 *    `.ssh/config`) and directory segments against [ToolDangerousPathConstants.DEFAULT_DANGEROUS_DIRECTORIES]
 *    (e.g. `.git`, `.ssh`). Also resolves symlinks to prevent bypass via symlink redirection.
 *
 * Usage — created by [HarnessAgentBuilder.wrapWithDangerousInputCheck] during tool registration:
 * ```
 * // In HarnessAgentLauncher, after addTool(toolBox):
 * agentBuilder.wrapWithDangerousInputCheck("execute_command")
 * ```
 *
 * @param delegate the original AgentTool to wrap (typically a ReflectiveFunctionTool)
 */
class DangerousInputCheckingTool(
    private val delegate: AgentTool,
) : ToolBase(
    builder()
        .name(delegate.name)
        .description(delegate.description)
        .inputSchema(delegate.parameters)
        .readOnly((delegate as? ToolBase)?.isReadOnly ?: false)
        .concurrencySafe(true),
) {

    companion object {
        /**
         * Minimum string length to bother scanning for dangerous patterns.
         * Very short strings are unlikely to be meaningful commands or paths.
         */
        private const val MIN_SCAN_LENGTH = 3

        /**
         * Cached lowercase dangerous command fragments for fast substring matching.
         */
        private val DANGEROUS_COMMANDS_LOWER: List<String> =
            ToolDangerousPathConstants.DANGEROUS_COMMANDS.map { it.lowercase(Locale.ROOT) }
    }

    override fun checkPermissions(
        toolInput: Map<String, Any>,
        context: PermissionContextState,
    ): Mono<PermissionDecision> {
        for ((key, value) in toolInput) {
            if (value is String && value.length >= MIN_SCAN_LENGTH) {
                // ① Check for dangerous command fragments
                val commandHit = findDangerousCommand(value)
                if (commandHit != null) {
                    return Mono.just(
                        PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message("Safety: dangerous command '$commandHit' detected in parameter '$key'")
                            .decisionReason("safety: dangerous command pattern in input")
                            .build(),
                    )
                }
                // ② Check for dangerous file paths (uses ToolBase's isDangerousPath with symlink resolution)
                if (isDangerousPath(value)) {
                    return Mono.just(
                        PermissionDecision.builder()
                            .behavior(PermissionBehavior.ASK)
                            .message("Safety: dangerous path detected in parameter '$key': $value")
                            .decisionReason("safety: dangerous path pattern in input")
                            .build(),
                    )
                }
            }
        }
        // No dangerous pattern found — defer to engine's rule tables and mode defaults
        return Mono.just(PermissionDecision.passthrough(name))
    }

    override fun callAsync(param: ToolCallParam): Mono<ToolResultBlock> = delegate.callAsync(param)

    /**
     * Scans [input] for any dangerous command fragment (case-insensitive).
     * @return the matched command fragment, or null if none found
     */
    private fun findDangerousCommand(input: String): String? {
        val lower = input.lowercase(Locale.ROOT)
        for (cmd in DANGEROUS_COMMANDS_LOWER) {
            if (lower.contains(cmd)) {
                return cmd
            }
        }
        return null
    }
}
