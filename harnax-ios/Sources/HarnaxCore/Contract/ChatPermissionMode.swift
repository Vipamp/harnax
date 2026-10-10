import Foundation

/// How much the agent may do before it asks. The five raw values are the runtime's own whitelist
/// (`harnax-agent/harnax-agent-service/src/main/kotlin/com/agnetix/harnax/agent/service/runner/impl/DefaultAgentRunner.kt:1052-1053`,
/// checked at `:1017`), and one of them is what a `PERMISSION` command carries in `args`
/// (`harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:74-119`).
///
/// The case order is the console's dropdown order (`harnax-webui/src/pages/session/components/ChatWindow.tsx:3616-3653`),
/// which is the order the screen lists them in.
///
/// A sixth value would not be a bug on this side: the runtime owns the set, and it refuses anything outside
/// it, so `init(wireValue:)` answers nil rather than guessing and the screen falls back to `defaultMode` —
/// which is also what the console does for a conversation whose column is null
/// (`permissionMode = config.permissionMode || 'DEFAULT'`, `ChatWindow.tsx:725-743`).
public enum ChatPermissionMode: String, CaseIterable, Sendable {
    /// Ask before anything that writes. The runtime's own default.
    case defaultMode = "DEFAULT"
    /// Every tool runs without asking.
    case bypass = "BYPASS"
    /// File edits go through; other tools still ask.
    case acceptEdits = "ACCEPT_EDITS"
    /// Read-only: the agent may look but not change.
    case explore = "EXPLORE"
    /// Never ask, never act on an approval prompt — the unattended shape.
    case dontAsk = "DONT_ASK"

    /// The `args` of the `PERMISSION` command that sets this mode. The value is sent verbatim and upper-case,
    /// because the server compares against that exact set (`DefaultAgentRunner.kt:1017`).
    public var wireValue: String { rawValue }

    /// The row label, which carries its own explanation — `默认（危险工具需确认）` — because the console's
    /// dropdown is a flat list of five sentences with no second line under the selection
    /// (`harnax-webui/src/locales/zh-CN/pages.ts:871-875`). Spelled as literals rather than built from
    /// `rawValue` so the copy gate can see that all five exist in both catalogues.
    public var titleKey: String {
        switch self {
        case .defaultMode: return "chat.permission.default"
        case .bypass: return "chat.permission.bypass"
        case .acceptEdits: return "chat.permission.acceptEdits"
        case .explore: return "chat.permission.explore"
        case .dontAsk: return "chat.permission.dontAsk"
        }
    }

    /// Anything the build does not recognise, and anything absent, reads as the default. A conversation with
    /// no `permissionMode` column is a conversation on the runtime's default, not a conversation in an unknown
    /// state.
    public init(wireValue: String?) {
        guard let value = hxPresented(wireValue)?.uppercased() else {
            self = .defaultMode
            return
        }
        self = ChatPermissionMode(rawValue: value) ?? .defaultMode
    }
}
