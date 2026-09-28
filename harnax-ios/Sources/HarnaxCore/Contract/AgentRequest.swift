import Foundation

/// What the agent may be told to do outside of a message. The raw values are the server's enum names
/// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:196-207`); the
/// slash aliases live in `harnax-webui` and are re-derived from these, not sent.
public enum AgentCommandType: String, Codable, Sendable, CaseIterable {
    case interrupt = "INTERRUPT"
    case clear = "CLEAR"
    case compact = "COMPACT"
    case approve = "APPROVE"
    case deny = "DENY"
    case stopSandbox = "STOP_SANDBOX"
    case enable = "ENABLE"
    case disable = "DISABLE"
    case permission = "PERMISSION"
    case refresh = "REFRESH"
}

/// A message for the agent to answer on the streaming endpoint.
///
/// `type` is an `EXISTING_PROPERTY` discriminator on the server (`AgentRequest.kt:17-26`), so the client
/// has to write it out — the request is not polymorphic on the wire by class name.
public struct ChatAgentRequest: Encodable, Sendable {
    public let type = "CHAT"
    public let sessionId: String
    public let message: String
    /// `data:image/<mime>;base64,<b64>` strings. Anything else the backend reads as a sandbox file path
    /// and forces `image/png` (`HarnessAgentWrapper.kt:814-829`), so a remote URL is never valid here.
    public let imageUrls: [String]
    public let requestId: String
    public let userId: Int64?

    public init(
        sessionId: String,
        message: String,
        imageUrls: [String] = [],
        requestId: String = "",
        userId: Int64? = nil
    ) {
        self.sessionId = sessionId
        self.message = message
        self.imageUrls = imageUrls
        self.requestId = requestId
        self.userId = userId
    }
}

/// A slash command or a capability switch. `args` is free text: the token after `ENABLE`/`DISABLE` names a
/// capability, the one after `PERMISSION` a mode.
public struct CommandAgentRequest: Encodable, Sendable {
    public let type = "COMMAND"
    public let sessionId: String
    public let command: AgentCommandType
    public let args: String
    public let userId: Int64?

    public init(sessionId: String, command: AgentCommandType, args: String = "", userId: Int64? = nil) {
        self.sessionId = sessionId
        self.command = command
        self.args = args
        self.userId = userId
    }
}

/// The answer to a `toolConfirm` frame. Bulk mode sets `isConfirmed` for every pending tool; per-tool mode
/// fills `toolResults` instead, which the server prefers when it is non-empty.
public struct ConfirmAgentRequest: Encodable, Sendable {
    public struct ToolInfo: Encodable, Sendable {
        public let toolId: String
        public let toolName: String

        public init(toolId: String, toolName: String) {
            self.toolId = toolId
            self.toolName = toolName
        }
    }

    public struct Decision: Encodable, Sendable {
        public let toolId: String
        public let toolName: String
        public let confirmed: Bool
        /// Adds a permission rule for future calls, so the same tool stops asking.
        public let alwaysAllow: Bool

        public init(toolId: String, toolName: String, confirmed: Bool, alwaysAllow: Bool = false) {
            self.toolId = toolId
            self.toolName = toolName
            self.confirmed = confirmed
            self.alwaysAllow = alwaysAllow
        }
    }

    public let type = "CONFIRM"
    public let sessionId: String
    public let isConfirmed: Bool
    public let toolInfoList: [ToolInfo]
    public let toolResults: [Decision]
    public let userId: Int64?
    /// Set when the answer belongs to a team member run rather than this session's own agent.
    public let childRunId: String?

    public init(
        sessionId: String,
        isConfirmed: Bool,
        toolInfoList: [ToolInfo] = [],
        toolResults: [Decision] = [],
        userId: Int64? = nil,
        childRunId: String? = nil
    ) {
        self.sessionId = sessionId
        self.isConfirmed = isConfirmed
        self.toolInfoList = toolInfoList
        self.toolResults = toolResults
        self.userId = userId
        self.childRunId = childRunId
    }
}
