package com.agnetix.harnax.agent.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * AgentRequest - unified request type for all agent interactions.
 *
 * Sealed class hierarchy with Jackson polymorphic deserialization.
 * Used across: channel-service -> session-router -> agent-service.
 *
 * Subclasses:
 * - [ChatAgentRequest]: chat message (type = CHAT)
 * - [CommandAgentRequest]: command execution (type = COMMAND)
 * - [ConfirmAgentRequest]: tool confirmation (type = CONFIRM)
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ChatAgentRequest::class, name = "CHAT"),
    JsonSubTypes.Type(value = CommandAgentRequest::class, name = "COMMAND"),
    JsonSubTypes.Type(value = ConfirmAgentRequest::class, name = "CONFIRM"),
)
sealed class AgentRequest {
    abstract val type: RequestType
    abstract val sessionId: String

    /**
     * Returns a new AgentRequest with the given sessionId replaced.
     */
    fun withSessionId(newSessionId: String): AgentRequest = when (this) {
        is ChatAgentRequest -> copy(sessionId = newSessionId)
        is CommandAgentRequest -> copy(sessionId = newSessionId)
        is ConfirmAgentRequest -> copy(sessionId = newSessionId)
    }
}

/**
 * Chat request - carries a user message for the agent to process.
 *
 * @property message User message content (text prompt)
 * @property imageUrls List of image URLs or base64 data URLs for multimodal input
 */
data class ChatAgentRequest(
    override val sessionId: String,
    val message: String,
    val imageUrls: List<String> = emptyList(),
    val requestId: String = "",
) : AgentRequest() {
    override val type: RequestType = RequestType.CHAT
}

/**
 * Command request - carries a command enum for agent control operations.
 *
 * @property command The command type to execute
 */
data class CommandAgentRequest(
    override val sessionId: String,
    val command: CommandType,
) : AgentRequest() {
    override val type: RequestType = RequestType.COMMAND
}

/**
 * Confirm request - carries tool confirmation decision from the client.
 *
 * @property isConfirmed Whether the user confirmed the tool execution
 * @property toolInfoList List of tools pending confirmation
 */
data class ConfirmAgentRequest(
    override val sessionId: String,
    val isConfirmed: Boolean,
    val toolInfoList: List<ToolInfo> = emptyList(),
) : AgentRequest() {
    override val type: RequestType = RequestType.CONFIRM
}

/**
 * Tool information for confirmation requests.
 */
data class ToolInfo(
    val toolId: String? = null,
    val toolName: String? = null,
)

/**
 * Request type discriminator.
 */
enum class RequestType {
    CHAT,
    COMMAND,
    CONFIRM,
}

/**
 * Command types for agent control operations.
 *
 * - INTERRUPT: Cancel the ongoing streaming response
 * - CLEAR: Clear session history and cached agent
 * - COMPACT: Compact/summarize conversation memory
 */
enum class CommandType {
    INTERRUPT,
    CLEAR,
    COMPACT,
    APPROVE,
}
