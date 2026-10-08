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

/// The server's own verdict on a command. `success` is optional only because an envelope that answers with no
/// body at all decodes into this empty value (`AgentCommandReply: HarnaxVoid`); a command body that *is* there
/// carries the flag every time, since the protocol declares it non-null
/// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/CommandResponse.kt:13-17`). `webui`
/// reads `data.message`, then `data.success`, then falls back to the envelope message.
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
/// (`harnax-webui/src/pages/session/components/contextUsage.ts:73-80`). Calling that a completed compaction
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
    /// The console's reading of one reply, kept as close to the transport as the payload it reads: the flag has
    /// to say true before anything is reported
    /// (`harnax-webui/src/pages/session/components/contextUsage.ts:73-80`).
    ///
    /// A body without it is a reply this side did not read rather than a compaction that ran, and the one thing
    /// this reading must not do is put 「已压缩上下文」 under a call that never said so. The counted no-op needs
    /// the same flag — the two counts describe the context, and the flag is what says they mean anything.
    public static func compact(_ result: Result<AgentCommandReply, APIError>) -> CompactionOutcome {
        guard case let .success(reply) = result, reply.success == true else { return .failed }
        guard let before = reply.result?.beforeMessages, let after = reply.result?.afterMessages else {
            return .done
        }
        return before == after ? .noop : .done
    }
}
