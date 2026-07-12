package com.agnetix.harnax.tools.sdk

/**
 * Holds resolved environment variable bindings for an agent's tools.
 *
 * Registered in agentscope's ToolExecutionContext at agent build time, and
 * automatically injected into tool methods that declare this type as a parameter.
 *
 * agentscope's ToolMethodInvoker treats non-primitive, non-java.*, non-framework
 * parameters as user-context POJOs and resolves them from ToolExecutionContext.
 *
 * Usage in a tool method (the envContext parameter is auto-injected):
 * ```
 * @Tool(name = "sendEmail", description = "Send an email")
 * fun sendEmail(to: String, subject: String, body: String, envContext: ToolEnvContext): String {
 *     val host = envContext.require("SMTP_HOST")
 *     // ...
 * }
 * ```
 */
data class ToolEnvContext(
    /**
     * Flat env variable map: envKey -> envValue.
     * Merged from all tool envBindings configured for this agent.
     */
    val bindings: Map<String, String> = emptyMap(),
) {

    /**
     * Get env variable value by key, or null if not found.
     */
    fun get(key: String): String? = bindings[key]

    /**
     * Get required env variable value. Throws IllegalArgumentException if missing or blank.
     */
    fun require(key: String): String {
        val value = bindings[key]
        require(!value.isNullOrBlank()) {
            "Environment parameter '$key' is required but not configured"
        }
        return value
    }
}
