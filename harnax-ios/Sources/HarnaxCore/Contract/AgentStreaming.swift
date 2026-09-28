import Foundation

/// The two SSE agent calls: a streamed answer and the continuation after a tool confirmation.
///
/// Confirmation matters twice over — the lead's own tools and each team member run answer on their own
/// stream, and the member answer has to carry back the `childRunId` that identifies the parked run.
public protocol AgentStreaming: Sendable {
    /// `POST /api/router/agent/chat/stream`.
    func chat(_ request: ChatAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error>

    /// `POST /api/router/agent/confirm`, which answers with a stream of its own.
    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error>
}

/// `POST /api/router/agent/command` — the non-streaming control path the slash commands and the capability
/// switches go down. Plain JSON, so the reply is an envelope rather than a stream.
public protocol AgentCommanding: Sendable {
    func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError>
}

/// The server's own verdict on a command. Both fields are optional because the reply is a loose map on the
/// Java side; `webui` reads `data.message`, then `data.success`, then falls back to the envelope message.
public struct AgentCommandReply: Decodable, Sendable, Equatable {
    public let success: Bool?
    public let message: String?

    public init(success: Bool? = nil, message: String? = nil) {
        self.success = success
        self.message = message
    }
}
