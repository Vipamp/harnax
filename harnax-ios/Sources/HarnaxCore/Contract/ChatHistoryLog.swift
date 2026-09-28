import Foundation

/// One row of a conversation's stored transcript, as `GET /api/router/agent/chat/history/{sessionId}`
/// answers it.
///
/// Backend: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:11-71`.
/// The discriminator is the plain `role` enum serialised by name — `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` —
/// with no `@JsonTypeInfo`, so this is a hand-rolled read of one flat object rather than a tagged union.
/// Every field is read tolerantly: the endpoint returns the whole session in one list, and a row this build
/// cannot read has to drop out rather than blank the screen.
///
/// `timestamp` is epoch milliseconds (`MessageLog.kt:13`, a Kotlin `Long`), not the
/// `yyyy-MM-dd HH:mm:ss.SSS` string the inbound `Msg` carries. Several rows can share one value, and the
/// team merge interleaves rows so the list is not time-ordered (`TeamHistoryReplay.kt:101-122`) — replay
/// consumes the array index-wise and never sorts by this field.
public enum ChatHistoryLog: Decodable, Sendable, Equatable {
    case user(message: String, timestamp: Int64?, source: ChatEventSource?)
    case assistant(thinking: String, text: String, calls: [Call], timestamp: Int64?, source: ChatEventSource?)
    case system(message: String, timestamp: Int64?, source: ChatEventSource?)
    case tool(name: String, result: String, timestamp: Int64?, source: ChatEventSource?)
    /// A role or shape this build does not recognise. The console draws nothing for a log that is not USER,
    /// ASSISTANT or TOOL (`harnax-webui/src/pages/session/components/ChatWindow.tsx:774-929`), which is the
    /// behaviour this case folds into.
    case unknown

    /// One tool the model asked for. `input`'s values are model-controlled — the same field arrives as the
    /// string `'7'` in one run and the number `9` in another (`MessageLog.kt:52-55`) — so it stays a JSON
    /// tree rather than a `[String: String]`.
    public struct Call: Decodable, Sendable, Equatable {
        public let name: String
        public let input: [String: JSONValue]

        public init(name: String, input: [String: JSONValue] = [:]) {
            self.name = name
            self.input = input
        }

        private enum Field: String, CodingKey {
            case name
            case input
        }

        /// Both fields are read on their own: the console replays a call whose input it cannot read as
        /// `tool.input || {}` (`ChatWindow.tsx:879`), so an unreadable argument tree costs the payload, not
        /// the card.
        public init(from decoder: Decoder) throws {
            let row = try decoder.container(keyedBy: Field.self)
            name = ((try? row.decodeIfPresent(String.self, forKey: .name)) ?? nil) ?? ""
            input = (try? row.decodeIfPresent([String: JSONValue].self, forKey: .input)) ?? nil ?? [:]
        }
    }

    /// The row's own stamp in epoch milliseconds, or nil when the server sent none.
    public var timestamp: Int64? {
        switch self {
        case let .user(_, timestamp, _),
             let .assistant(_, _, _, timestamp, _),
             let .system(_, timestamp, _),
             let .tool(_, _, timestamp, _):
            return timestamp
        case .unknown:
            return nil
        }
    }

    /// The role lower-cased, which is how the console spells a replayed message id
    /// (`ChatWindow.tsx:764`).
    public var roleKey: String? {
        switch self {
        case .user: return "user"
        case .assistant: return "assistant"
        case .system: return "system"
        case .tool: return "tool"
        case .unknown: return nil
        }
    }

    /// Which team member run produced this row, or nil for the session's own agent. A row that carries one
    /// is member output merged in server-side (`TeamHistoryReplay.kt:65-89`).
    public var source: ChatEventSource? {
        switch self {
        case let .user(_, _, source),
             let .assistant(_, _, _, _, source),
             let .system(_, _, source),
             let .tool(_, _, _, source):
            return source
        case .unknown:
            return nil
        }
    }

    /// Whether this row was produced by a team member rather than by the agent the user is talking to.
    public var isMemberOutput: Bool { source != nil }

    private enum Field: String, CodingKey {
        case role
        case message
        case timestamp
        case source
        case thinking
        case text
        case toolUseLog
        case name
        case result
    }

    private enum Kind: String {
        case user = "USER"
        case assistant = "ASSISTANT"
        case system = "SYSTEM"
        case tool = "TOOL"
        /// The sentinel a role this build has no branch for decodes to — including an absent role.
        case other = ""
    }

    public init(from decoder: Decoder) throws {
        let row = try decoder.container(keyedBy: Field.self)
        // Uppercased so a role that arrives in another case still lands in its branch; the enum name is the
        // contract and nothing on the wire is locale-sensitive here.
        let role = ((try? row.decodeIfPresent(String.self, forKey: .role)) ?? nil)?.uppercased()
        switch Kind(rawValue: role ?? "") ?? .other {
        case .user:
            self = .user(
                message: Self.text(.message, in: row),
                timestamp: Self.stamp(in: row),
                source: Self.member(in: row)
            )
        case .assistant:
            self = .assistant(
                thinking: Self.text(.thinking, in: row),
                text: Self.text(.text, in: row),
                calls: Self.calls(in: row),
                timestamp: Self.stamp(in: row),
                source: Self.member(in: row)
            )
        case .system:
            self = .system(
                message: Self.text(.message, in: row),
                timestamp: Self.stamp(in: row),
                source: Self.member(in: row)
            )
        case .tool:
            self = .tool(
                name: Self.text(.name, in: row),
                result: Self.text(.result, in: row),
                timestamp: Self.stamp(in: row),
                source: Self.member(in: row)
            )
        case .other:
            self = .unknown
        }
    }

    /// Absent, explicitly null and unreadable all arrive as the empty string, which is what the console
    /// renders for a row whose content column is blank (`ChatWindow.tsx:782`).
    private static func text(_ key: Field, in row: KeyedDecodingContainer<Field>) -> String {
        ((try? row.decodeIfPresent(String.self, forKey: key)) ?? nil) ?? ""
    }

    /// A stamp this side cannot read is the same as no stamp: the console's `log.timestamp || Date.now()`
    /// (`ChatWindow.tsx:764`) treats both alike.
    private static func stamp(in row: KeyedDecodingContainer<Field>) -> Int64? {
        (try? row.decodeIfPresent(Int64.self, forKey: .timestamp)) ?? nil
    }

    /// A source row that half-decodes is dropped rather than taken as the lead's own speech: an unreadable
    /// member marker only costs this row its bubble, while a wrong one would file member text under the lead.
    private static func member(in row: KeyedDecodingContainer<Field>) -> ChatEventSource? {
        (try? row.decodeIfPresent(ChatEventSource.self, forKey: .source)) ?? nil
    }

    private static func calls(in row: KeyedDecodingContainer<Field>) -> [Call] {
        (try? row.decodeIfPresent([Call].self, forKey: .toolUseLog)) ?? nil ?? []
    }
}

/// The conversation's stored transcript, the read that feeds the screen on open.
///
/// Backend: `harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:144-152`,
/// which passes the agent-service list through as `ResultVo<List<Any>>` — one flat array, no paging and no
/// limit, so the whole session arrives in one call.
public protocol ChatHistoryReading: Sendable {
    /// `GET /api/router/agent/chat/history/{sessionId}`, keyed by the string business id rather than the
    /// numeric row id — the same key the stream takes.
    func history(sessionId: String) async -> Result<[ChatHistoryLog], APIError>
}
