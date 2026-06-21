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
 * Command request - carries a command enum and optional arguments for agent control operations.
 *
 * Command format: "/<commandName> [args]"
 * Examples:
 *   "/clear"       → command=CLEAR, args=""
 *   "/stop xx"     → command=INTERRUPT, args="xx"
 *   "/compact 500" → command=COMPACT, args="500"
 *
 * @property command The command type to execute
 * @property args    Command arguments (text after command name), empty string if no arguments
 */
data class CommandAgentRequest(
    override val sessionId: String,
    val command: CommandType,
    val args: String = "",
) : AgentRequest() {
    override val type: RequestType = RequestType.COMMAND

    companion object {
        /**
         * Parse a slash-command text into a CommandAgentRequest.
         *
         * Format: "/<commandName> [args]"
         * Examples:
         *   "/clear"         → CommandAgentRequest(command=CLEAR, args="")
         *   "/stop xx"       → CommandAgentRequest(command=INTERRUPT, args="xx")
         *   "/compact 500"   → CommandAgentRequest(command=COMPACT, args="500")
         *
         * @param sessionId Session identifier
         * @param text      Raw text input (already trimmed, mention prefix stripped)
         * @return CommandAgentRequest if the text is a valid slash command, null otherwise
         */
        fun parse(sessionId: String, text: String): CommandAgentRequest? {
            if (!text.startsWith("/")) return null
            val afterSlash = text.substring(1).trim()
            if (afterSlash.isEmpty()) return null

            val spaceIdx = afterSlash.indexOf(' ')
            val keyword = if (spaceIdx >= 0) afterSlash.substring(0, spaceIdx) else afterSlash
            val args = if (spaceIdx >= 0) afterSlash.substring(spaceIdx + 1).trim() else ""

            val commandType = CommandType.fromKeyword(keyword) ?: return null
            return CommandAgentRequest(sessionId = sessionId, command = commandType, args = args)
        }
    }
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
 * - APPROVE: Approve pending operation
 * - STOP_SANDBOX: Stop and remove the sandbox container for the session
 *
 * Each command defines a set of slash-command aliases (case-insensitive).
 * Use [fromKeyword] to resolve a keyword to its CommandType.
 *
 * @property aliases Set of recognized slash-command names (all lowercase)
 */
enum class CommandType(vararg val aliases: String) {
    INTERRUPT("interrupt", "stop"),
    CLEAR("clear"),
    COMPACT("compact"),
    APPROVE("approve"),
    STOP_SANDBOX("stop-sandbox"),
    ;

    companion object {
        private val keywordMap: Map<String, CommandType> by lazy {
            entries.flatMap { cmd -> cmd.aliases.map { alias -> alias to cmd } }.toMap()
        }

        /**
         * Resolve a slash-command keyword to its CommandType (case-insensitive).
         *
         * @param keyword Command name from the text (e.g. "clear", "stop")
         * @return The matching CommandType, or null if no alias matches
         */
        fun fromKeyword(keyword: String): CommandType? = keywordMap[keyword.lowercase()]
    }
}
