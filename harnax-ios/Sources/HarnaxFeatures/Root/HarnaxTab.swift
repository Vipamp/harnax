import Foundation

/// The four slots of the bottom bar. The administration domains that had their own tab are rows on 「我的」
/// (`SystemRoute`), which is why there is no gear here.
public enum HarnaxTab: Int, CaseIterable, Identifiable, Sendable {
    case chat
    case agents
    case context
    case me

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .chat: return "tab.chat"
        case .agents: return "tab.agents"
        case .context: return "tab.context"
        case .me: return "tab.me"
        }
    }

    public var systemImage: String {
        switch self {
        case .chat: return "text.bubble"
        case .agents: return "circle.grid.2x2"
        case .context: return "square.stack.3d.up"
        case .me: return "person.crop.circle"
        }
    }
}
