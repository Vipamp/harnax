import Foundation

/// One row of `GET /api/admin/agents/page`, and the same shape `GET /api/admin/agents/{id}` answers.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:11-67`
/// declares every field `var x: T? = null`, so all of them may arrive either as `null` or absent.
/// `Identifiable.ID` is therefore `Int64?` — optional IDs still hash, and a row with a missing id
/// renders instead of dropping out of the list.
///
/// The page endpoint fills the four binding lists and the session list per row
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:265-395`),
/// which is why the card and its drill-down sheets need no second call — and why the edit form is
/// prefilled from the row the list already returned.
public struct AgentSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let systemPrompt: String?
    public let modelId: Int64?
    public let modelName: String?
    public let modelPrice: Double?
    public let status: Int?
    public let isPublic: Int?
    public let owner: String?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?
    public let sessionCount: Int?
    public let sessionList: [AgentSession]?
    public let mcpList: [AgentMcpBinding]?
    public let skillList: [AgentSkillBinding]?
    public let toolList: [AgentToolBinding]?
    public let cliList: [AgentCliBinding]?

    /// A row that carries no status column reads as enabled, the way the web card treats `status ?? 1`
    /// (`harnax-webui/src/pages/agent/index.tsx:421-424`).
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var mcpCount: Int { mcpList?.count ?? 0 }
    public var skillCount: Int { skillList?.count ?? 0 }
    public var toolCount: Int { toolList?.count ?? 0 }
    public var cliCount: Int { cliList?.count ?? 0 }
    public var sessionTotal: Int { sessionCount ?? sessionList?.count ?? 0 }
}
