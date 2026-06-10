package com.agnetix.harnax.agent.protocol

/**
 * CommandResponse - response for command-type requests.
 *
 * Used when AgentRequest.type = COMMAND.
 *
 * @property sessionId Session identifier
 * @property success   Whether the command executed successfully
 * @property result    Command result payload (type depends on the command)
 * @property message   Human-readable status or error message
 */
data class CommandResponse(
    val sessionId: String,
    val success: Boolean,
    val result: Any? = null,
    val message: String? = null,
) {

    companion object {
        @JvmStatic
        fun success(sessionId: String, result: Any? = null, message: String = "success"): CommandResponse = CommandResponse(sessionId = sessionId, success = true, result = result, message = message)

        @JvmStatic
        fun failure(sessionId: String, message: String): CommandResponse = CommandResponse(sessionId = sessionId, success = false, message = message)
    }
}
