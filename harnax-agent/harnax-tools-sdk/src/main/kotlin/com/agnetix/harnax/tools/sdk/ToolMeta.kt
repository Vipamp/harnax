package com.agnetix.harnax.tools.sdk

/**
 * Method-level annotation for @Tool methods within a ToolBox.
 * Each @Tool method is a separate tool; @ToolMeta provides per-tool metadata
 * that will be auto-synced to the database when the admin service starts up.
 *
 * Works alongside @Tool (from agentscope):
 * - @Tool provides: name, description, readOnly
 * - @ToolMeta provides: displayName, displayNameZh, envParamDefs, timeoutSeconds, isPublic, needConfirm
 *
 * Usage:
 * ```
 * @Tool(name = "sendEmail", description = "Send an email notification")
 * @ToolMeta(
 *     displayName = "Send Email",
 *     displayNameZh = "发送邮件",
 *     envParamDefs = [
 *         ToolEnvParamDef(key = "SMTP_HOST", description = "SMTP server hostname"),
 *         ToolEnvParamDef(key = "SMTP_PASSWORD", description = "SMTP login password", secret = true),
 *     ],
 *     needConfirm = true,
 * )
 * fun sendEmail(to: String, subject: String, body: String): String = execute { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolMeta(
    /** Display name shown in Admin UI (English, used as i18n fallback) */
    val displayName: String = "",
    /** Display name shown in Admin UI (Chinese, for i18n zh-CN locale) */
    val displayNameZh: String = "",
    /**
     * Rich environment parameter definitions with description/secret/required metadata.
     * Each tool method can declare its own set of env params.
     */
    val envParamDefs: Array<ToolEnvParamDef> = [],
    /** Execution timeout in seconds (0 = use system default) */
    val timeoutSeconds: Int = 0,
    /** Whether this tool is publicly available to all users */
    val isPublic: Boolean = true,
    /** Whether this tool requires user confirmation before execution */
    val needConfirm: Boolean = false,
    /**
     * Whether this tool's string inputs should be scanned for dangerous patterns
     * (shell commands like `rm -rf`, sensitive paths like `.env`, `.ssh/`).
     *
     * When true, the tool is wrapped with a [io.agentscope.core.tool.ToolBase] subclass
     * that overrides `checkPermissions()` to inspect runtime inputs. If a dangerous
     * pattern is detected, the engine returns an ASK decision with a "safety" reason —
     * this is bypass-immune (cannot be skipped even in BYPASS mode).
     *
     * Usage:
     * ```
     * @Tool(name = "execute_command", description = "Run a shell command")
     * @ToolMeta(dangerousInput = true)
     * fun executeCommand(@ToolParam(...) command: String): String = ...
     * ```
     */
    val dangerousInput: Boolean = false,
    /**
     * Whether this tool is mandatory (always available to all agents, not shown in UI tool selection).
     * Required tools are automatically included when an agent launches and cannot be deselected.
     */
    val isRequired: Boolean = false,
)
