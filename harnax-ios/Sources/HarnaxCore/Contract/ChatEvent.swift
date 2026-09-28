import Foundation

/// A JSON value, for the payload the backend leaves open: tool arguments are a `Map<String, Any>`
/// (`CallToolChatEvent`, `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/ChatEvent.kt:116-124`).
public enum JSONValue: Codable, Sendable, Equatable {
    case string(String)
    case number(Double)
    case boolean(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let flag = try? container.decode(Bool.self) {
            self = .boolean(flag)
        } else if let number = try? container.decode(Double.self) {
            self = .number(number)
        } else if let text = try? container.decode(String.self) {
            self = .string(text)
        } else if let array = try? container.decode([JSONValue].self) {
            self = .array(array)
        } else if let object = try? container.decode([String: JSONValue].self) {
            self = .object(object)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "value is not JSON")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case let .boolean(flag): try container.encode(flag)
        case let .number(number): try container.encode(number)
        case let .string(text): try container.encode(text)
        case let .array(values): try container.encode(values)
        case let .object(entries): try container.encode(entries)
        }
    }
}

/// Which team member run produced an event, or `nil` for an ordinary single-agent answer. Several runs of
/// one member share the session's channel, and only `childRunId` says which one a confirmation answers.
public struct ChatEventSource: Codable, Sendable, Equatable {
    public let teamId: Int64
    public let teamName: String
    public let memberAgentId: Int64
    public let memberAgentName: String
    public let childRunId: String
    public let childSessionId: String

    /// Decoding is the only way a marker arrives, but a fixture has to be able to name one too.
    public init(
        teamId: Int64,
        teamName: String,
        memberAgentId: Int64,
        memberAgentName: String,
        childRunId: String,
        childSessionId: String
    ) {
        self.teamId = teamId
        self.teamName = teamName
        self.memberAgentId = memberAgentId
        self.memberAgentName = memberAgentName
        self.childRunId = childRunId
        self.childSessionId = childSessionId
    }
}

/// A file the run produced in the sandbox workspace, carried by the end frame.
public struct ChatFileAttachment: Codable, Sendable, Equatable {
    public let fileId: String
    public let fileName: String
    public let filePath: String
    public let fileSize: Int64
    public let mimeType: String
    public let url: String
    public let objectKey: String?
}

/// One streamed frame of the agent's answer on `POST /api/router/agent/chat/stream`.
///
/// `eventType` is the Jackson subtype discriminator (`ChatEvent.kt:18-32`) and the eight branches are the
/// whole vocabulary a consumer can receive. Unlike admin, the router serialises with Jackson defaults, so
/// a nullable field arrives as explicit `null` rather than as a missing key.
public enum ChatEvent: Decodable, Sendable, Equatable {
    case text(TextDelta)
    case thinking(TextDelta)
    case toolCall(ToolCall)
    case toolResult(ToolResult)
    case toolConfirm(ToolConfirm)
    case end(StreamEnd)
    case failure(StreamFailure)
    /// A parked run stays silent and the streaming hops kill a silent stream, so the server pings instead.
    /// This frame carries no content: it must not be rendered and must not end the turn.
    case keepAlive(ChatSourceOnly)

    /// Text and thinking share one shape; the segment kind is the event case, not a field.
    public struct TextDelta: Codable, Sendable, Equatable {
        public let message: String
        /// The model's closing frame for this segment. The consumer drops its content and resets the
        /// segment accumulator when this is set.
        public let isLast: Bool
        public let source: ChatEventSource?
    }

    public struct ToolCall: Codable, Sendable, Equatable {
        public let toolId: String
        public let toolName: String
        public let arguments: [String: JSONValue]
        public let source: ChatEventSource?
    }

    public struct ToolResult: Codable, Sendable, Equatable {
        public let toolId: String
        public let toolName: String
        public let message: String
        public let success: Bool
        public let source: ChatEventSource?
    }

    /// The run is waiting for a human answer on these tools; the read loop is expected to block on the
    /// dialog, which is why the server keeps the stream alive with `keepAlive` frames meanwhile.
    public struct ToolConfirm: Codable, Sendable, Equatable {
        public struct Pending: Codable, Sendable, Equatable {
            public let toolId: String
            public let toolName: String
            public let arguments: [String: JSONValue]
            public let isDangerous: Bool
        }

        public let pendingCallTools: [Pending]
        public let source: ChatEventSource?
    }

    public struct StreamEnd: Codable, Sendable, Equatable {
        public let attachments: [ChatFileAttachment]
        public let source: ChatEventSource?
    }

    public struct StreamFailure: Codable, Sendable, Equatable {
        public let code: String
        public let message: String
        public let source: ChatEventSource?
    }

    public struct ChatSourceOnly: Codable, Sendable, Equatable {
        public let source: ChatEventSource?
    }

    private enum Kind: String, Decodable {
        case text = "TextEvent"
        case thinking = "ThinkingEvent"
        case toolCall = "CallToolEvent"
        case toolResult = "ToolResultEvent"
        case toolConfirm = "ToolConfirmEvent"
        case end = "EndEvent"
        case failure = "ErrorEvent"
        case keepAlive = "KeepAliveEvent"
    }

    private enum CodingKeys: String, CodingKey {
        case eventType
    }

    /// One `data:` payload → event.
    public static func decode(_ payload: String) throws -> ChatEvent {
        try JSONDecoder().decode(ChatEvent.self, from: Data(payload.utf8))
    }

    public init(from decoder: Decoder) throws {
        let envelope = try decoder.container(keyedBy: CodingKeys.self)
        switch try envelope.decode(Kind.self, forKey: .eventType) {
        case .text: self = .text(try TextDelta(from: decoder))
        case .thinking: self = .thinking(try TextDelta(from: decoder))
        case .toolCall: self = .toolCall(try ToolCall(from: decoder))
        case .toolResult: self = .toolResult(try ToolResult(from: decoder))
        case .toolConfirm: self = .toolConfirm(try ToolConfirm(from: decoder))
        case .end: self = .end(try StreamEnd(from: decoder))
        case .failure: self = .failure(try StreamFailure(from: decoder))
        case .keepAlive: self = .keepAlive(try ChatSourceOnly(from: decoder))
        }
    }
}
