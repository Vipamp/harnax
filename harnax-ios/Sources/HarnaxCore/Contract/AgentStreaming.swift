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
///
/// `result` is the command's own payload, and only one command's answer is legible through it: `COMPACT`
/// reports the message counts before and after, which is the difference between a context that got shorter and
/// a session that was too short to compact in the first place.
public struct AgentCommandReply: Decodable, Sendable, Equatable {
    /// The two counts `COMPACT` answers with —
    /// `harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:947-963`
    /// builds the map, and the no-op leg is
    /// `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt:162-171`,
    /// a success whose two counts are the same number. Both fields optional: the reply is a
    /// `Map<String, Any?>`, so a command with no payload arrives with no keys at all.
    public struct Payload: Decodable, Sendable, Equatable {
        public let beforeMessages: Int?
        public let afterMessages: Int?

        public init(beforeMessages: Int? = nil, afterMessages: Int? = nil) {
            self.beforeMessages = beforeMessages
            self.afterMessages = afterMessages
        }
    }

    public let success: Bool?
    public let message: String?
    public let result: Payload?

    public init(success: Bool? = nil, message: String? = nil, result: Payload? = nil) {
        self.success = success
        self.message = message
        self.result = result
    }
}

/// What a `COMPACT` reply actually did.
///
/// Three answers rather than two because the runtime has a documented third outcome: a session whose context
/// is too short to keep a tail comes back `success: true` with the same count on both sides
/// (`harnax-webui/src/pages/session/components/contextUsage.ts:61-68`). Calling that a completed compaction
/// would be a false report to the user, and calling it a failure would be a lie about the server.
public enum CompactionOutcome: Equatable, Sendable {
    /// The context got shorter, or the reply carried no counts to argue with.
    case done
    /// `success` with both counts present and equal: nothing to compact yet.
    case noop
    /// Refused or unreachable, which is what the reply's own sentence has to explain.
    case failed
}

extension CompactionOutcome {
    /// The console's reading of one reply, kept as close to the transport as the payload it reads.
    ///
    /// One deliberate fork from `compactionOutcome`, which calls a reply with no `success` key a failure: the
    /// envelope's `ResultVo.success(null)` is a command that reported nothing, and this side already declines
    /// to read that as a refusal (`ChatViewModel.commandSentence`). A *counted* no-op is still a no-op here,
    /// because that leg reads `result` rather than the missing key.
    public static func compact(_ result: Result<AgentCommandReply, APIError>) -> CompactionOutcome {
        guard case let .success(reply) = result, reply.success != false else { return .failed }
        guard let before = reply.result?.beforeMessages, let after = reply.result?.afterMessages else {
            return .done
        }
        return before == after ? .noop : .done
    }
}
