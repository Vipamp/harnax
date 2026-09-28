import Foundation

/// The five slots of the bottom bar, in mockup order.
public enum HarnaxTab: Int, CaseIterable, Identifiable, Sendable {
    case chat
    case agents
    case context
    case system
    case me

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .chat: return "tab.chat"
        case .agents: return "tab.agents"
        case .context: return "tab.context"
        case .system: return "tab.system"
        case .me: return "tab.me"
        }
    }

    public var systemImage: String {
        switch self {
        case .chat: return "text.bubble"
        case .agents: return "circle.grid.2x2"
        case .context: return "square.stack.3d.up"
        case .system: return "gearshape"
        case .me: return "person.crop.circle"
        }
    }
}
