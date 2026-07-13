package com.agnetix.harnax.client.dto

/**
 * Command request - executes agent control operations (e.g. /clear, /stop, /compact).
 *
 * @property sessionId Session identifier
 * @property command   The command type to execute
 * @property args      Command arguments (text after command name), empty string if no arguments
 */
data class CommandRequest(
    val sessionId: String,
    val command: String,
    val args: String = "",
    /**
     * Discriminator field matching the Router's polymorphic type (Jackson).
     * Must be "COMMAND" for command requests.
     */
    val type: String = "COMMAND",
)

/**
 * Command types for agent control operations.
 */
object CommandTypes {
    const val INTERRUPT = "INTERRUPT"
    const val CLEAR = "CLEAR"
    const val COMPACT = "COMPACT"
    const val APPROVE = "APPROVE"
    const val DENY = "DENY"
    const val STOP_SANDBOX = "STOP_SANDBOX"
    const val ENABLE = "ENABLE"
    const val DISABLE = "DISABLE"
    const val REFRESH = "REFRESH"
}

/**
 * Command response - result of a command execution.
 *
 * @property sessionId Session identifier
 * @property success   Whether the command executed successfully
 * @property result    Command result payload
 * @property message   Human-readable status or error message
 */
data class CommandResponse(
    val sessionId: String,
    val success: Boolean,
    val result: Any? = null,
    val message: String? = null,
)
