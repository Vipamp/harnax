import Foundation

/// One row of `GET /api/admin/agents/page`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:11-31`
/// declares every field `var x: T? = null`, so all of them may arrive either as `null` or absent.
/// `Identifiable.ID` is therefore `Int64?` — optional IDs still hash, and a row with a missing id
/// renders instead of dropping out of the list.
public struct AgentSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let modelName: String?
    public let status: Int?
    public let isPublic: Int?
    public let sessionCount: Int?

    public var isEnabled: Bool { status == 1 }
    public var isShared: Bool { isPublic == 1 }
}
