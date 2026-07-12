package com.agnetix.harnax.tools.sdk

/**
 * Annotation to declare a single environment parameter for a tool method.
 * Used inside @ToolMeta.envParamDefs to provide rich metadata (description, secret flag, etc.)
 * so that the Admin UI can display meaningful information to the operator configuring the tool.
 *
 * Usage:
 * ```
 * @Tool(name = "sendEmail", description = "Send email")
 * @ToolMeta(
 *     envParamDefs = [
 *         ToolEnvParamDef(key = "SMTP_HOST", description = "SMTP server hostname"),
 *         ToolEnvParamDef(key = "SMTP_PASSWORD", description = "SMTP login password", secret = true),
 *     ]
 * )
 * fun sendEmail(...) { ... }
 * ```
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolEnvParamDef(
    /** Environment parameter key name, e.g. "API_KEY" */
    val key: String,
    /** Human-readable description shown in Admin UI */
    val description: String = "",
    /** Whether this env parameter is required for the tool to function */
    val required: Boolean = true,
    /** Whether this env parameter contains a secret (API key, password) and should be masked in UI */
    val secret: Boolean = false,
    /** Default value (non-secret only); operator can override in Admin UI */
    val defaultValue: String = "",
)
