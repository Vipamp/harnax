import Foundation

/// A bound resource on an agent row. Only the id is read: the list card shows counts, and the detail
/// screens that need names go through their own domain DTOs.
public struct AgentBinding: Decodable, Equatable, Sendable {
    public let id: Int64?
}

/// One row of `GET /api/admin/agents/page`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:11-67`
/// declares every field `var x: T? = null`, so all of them may arrive either as `null` or absent.
/// `Identifiable.ID` is therefore `Int64?` — optional IDs still hash, and a row with a missing id
/// renders instead of dropping out of the list.
///
/// The four binding lists come back populated on the page endpoint (`convertToResponse` fills them),
/// which is why the card can show counts without a detail call.
public struct AgentSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let modelName: String?
    public let status: Int?
    public let isPublic: Int?
    public let sessionCount: Int?
    public let creator: String?
    public let createTime: String?
    public let mcpList: [AgentBinding]?
    public let skillList: [AgentBinding]?
    public let toolList: [AgentBinding]?
    public let cliList: [AgentBinding]?

    public var isEnabled: Bool { status == 1 }
    public var isShared: Bool { isPublic == 1 }
    public var mcpCount: Int { mcpList?.count ?? 0 }
    public var skillCount: Int { skillList?.count ?? 0 }
    public var toolCount: Int { toolList?.count ?? 0 }
    public var cliCount: Int { cliList?.count ?? 0 }
}
