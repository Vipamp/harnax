import Foundation
import HarnaxCore

/// The one status filter both entity lists offer, shared so the two screens cannot drift apart and the
/// copy is written once.
///
/// `all` leaves `status` off the request instead of sending a sentinel the server would have to ignore.
public enum StatusFilter: Int, CaseIterable, Identifiable, Sendable {
    case all
    case enabled
    case disabled

    public var id: Int { rawValue }

    public var titleKey: String {
        switch self {
        case .all: return "state.filter.all"
        case .enabled: return "state.badge.enabled"
        case .disabled: return "state.badge.disabled"
        }
    }

    /// `status` on the wire is the 0/1 column, not a name
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:60-70`).
    public var queryValue: Int? {
        switch self {
        case .all: nil
        case .enabled: 1
        case .disabled: 0
        }
    }
}
