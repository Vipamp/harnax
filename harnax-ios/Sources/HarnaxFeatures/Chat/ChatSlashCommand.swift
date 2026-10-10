import Foundation
import HarnaxCore

/// A slash command the composer recognised.
///
/// The parse is the console's own (`harnax-webui/src/pages/session/components/ChatWindow.tsx:966-988`) and the
/// backend has an isomorphic one (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:98-118`),
/// so the two agree on what a line of text means. Kept as a pure type with no view model in sight: three
/// rules — the leading slash, the earlier of the first space or colon, the lower-cased keyword — are exactly
/// the kind of thing that silently drifts once they live inside a send path.
public struct ChatSlashCommand: Equatable, Sendable {
    /// The keyword and the command type it maps to.
    public struct Keyword: Equatable, Sendable {
        public let keyword: String
        public let command: AgentCommandType

        public init(_ keyword: String, _ command: AgentCommandType) {
            self.keyword = keyword
            self.command = command
        }
    }

    /// The nine keywords the web console exposes (`ChatWindow.tsx:951-961`), in its own order.
    ///
    /// `deny`, `reject` and `refresh` exist on the server's ten-value enum
    /// (`AgentRequest.kt:196-207`) and are deliberately absent here: the console never routes them through a
    /// slash, and exposing a third entry point for them would be a feature the reference build does not have.
    /// `stop` is a second spelling of `interrupt`, which is why nine keywords answer to eight command types.
    public static let keywords: [Keyword] = [
        Keyword("interrupt", .interrupt),
        Keyword("stop", .interrupt),
        Keyword("clear", .clear),
        Keyword("compact", .compact),
        Keyword("approve", .approve),
        Keyword("stop-sandbox", .stopSandbox),
        Keyword("enable", .enable),
        Keyword("disable", .disable),
        Keyword("permission", .permission),
    ]

    public let command: AgentCommandType
    /// Everything after the separator, trimmed. Empty when the line was just a keyword.
    public let args: String

    public init(command: AgentCommandType, args: String = "") {
        self.command = command
        self.args = args
    }

    /// The command a line of composer text asks for, or nil when it is an ordinary message.
    ///
    /// A keyword the table does not know answers nil rather than an error — typing `/hello there` at a chat
    /// box is saying hello, not running a command (`ChatWindow.tsx:985-987` falls through to `doSend`'s
    /// streaming leg, and the backend's `parse` returns null the same way at `AgentRequest.kt:117`).
    public static func parse(_ text: String) -> ChatSlashCommand? {
        guard text.hasPrefix("/") else { return nil }
        let afterSlash = String(text.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !afterSlash.isEmpty else { return nil }

        // The earlier of the first space and the first colon, which is what makes `/permission:bypass` and
        // `/permission bypass` the same command (`ChatWindow.tsx:973-982`, `AgentRequest.kt:107-114`). A line
        // with neither is one bare keyword.
        let separator = [afterSlash.firstIndex(of: " "), afterSlash.firstIndex(of: ":")]
            .compactMap { $0 }
            .min()

        let keyword: String
        let args: String
        if let separator {
            keyword = String(afterSlash[..<separator])
            args = String(afterSlash[afterSlash.index(after: separator)...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            keyword = afterSlash
            args = ""
        }

        guard let match = keywords.first(where: { $0.keyword == keyword.lowercased() }) else { return nil }
        return ChatSlashCommand(command: match.command, args: args)
    }
}

/// A command the screen has to ask about before it sends.
///
/// The console puts a `Popconfirm` on clear and a `Modal.confirm` behind a live status read on stop-sandbox
/// (`ChatWindow.tsx:3654-3692`); every other command goes straight through. The raw text travels with it so a
/// confirmed command still draws the bubble the user typed (`:992-998`), and a refused one draws nothing.
public struct ChatPendingCommand: Equatable, Sendable {
    public let command: ChatSlashCommand
    public let rawText: String

    public init(command: ChatSlashCommand, rawText: String) {
        self.command = command
        self.rawText = rawText
    }

    /// The two confirmations, spelled once so the view and the model cannot drift apart.
    public enum Kind: Equatable, Sendable {
        case clear
        case stopSandbox
    }

    public var kind: Kind? {
        switch command.command {
        case .clear: return .clear
        case .stopSandbox: return .stopSandbox
        default: return nil
        }
    }
}

/// What the composer has to say about a tap that did nothing.
///
/// The console's three `message.warning` / `message.info` calls (`ChatWindow.tsx:3508-3587`) are the whole of
/// its feedback for a refused switch, and a banner is this screen's equivalent — a toast has no home in a
/// navigation stack that is already holding a keyboard.
public struct ChatComposerNotice: Equatable, Sendable {
    public enum Tone: Equatable, Sendable {
        case info
        case warning
        case error
    }

    public let tone: Tone
    public let text: String

    public init(tone: Tone, text: String) {
        self.tone = tone
        self.text = text
    }

    public static func info(_ text: String) -> ChatComposerNotice { ChatComposerNotice(tone: .info, text: text) }
    public static func warning(_ text: String) -> ChatComposerNotice { ChatComposerNotice(tone: .warning, text: text) }
    public static func error(_ text: String) -> ChatComposerNotice { ChatComposerNotice(tone: .error, text: text) }
}
