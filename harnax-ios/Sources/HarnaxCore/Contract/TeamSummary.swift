import Foundation

/// One row of `GET /api/admin/teams/page`, and the same shape `GET /api/admin/teams/{id}` answers.
///
/// A team *is* its lead: `systemPrompt` and `modelId` belong to the team row itself and there is no
/// separate lead agent (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:8-12`).
///
/// Where `AgentResponse` makes every field nullable, this DTO declares `systemPrompt`, `modelId`,
/// `skillList` and `memberList` with non-null defaults, so those keys always arrive and are modelled
/// non-optional.
public struct TeamSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let description: String?
    public let systemPrompt: String
    public let modelId: Int64
    public let modelName: String?
    public let skillList: [TeamLeadSkill]
    public let memberList: [TeamMember]
    public let status: Int?
    public let isPublic: Int?
    public let tenantId: Int64?
    public let creator: String?
    public let createTime: String?
    public let updateTime: String?

    /// Same default as the team table's own switch: a row with no status column is enabled
    /// (`harnax-webui/src/pages/team/index.tsx:250-257`).
    public var isEnabled: Bool { status != 0 }
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }

    /// The table shows `#<modelId>` when the model row is gone but the reference stays
    /// (`harnax-webui/src/pages/team/index.tsx:210-219`). A `modelId` of 0 is this DTO's "no lead model"
    /// default, which is why it yields no name rather than a chip that points at nothing.
    public var leadModelName: String? {
        if let name = hxPresented(modelName) { return name }
        return modelId == 0 ? nil : "#\(modelId)"
    }
}

/// `memberList[]` — `agentAvailable` is false when the referenced agent has since been disabled or
/// deleted. The row must stay visible and say so; hiding it would leave the operator wondering where a
/// member went (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:49-63`).
public struct TeamMember: Decodable, Equatable, Sendable {
    public let agentId: Int64
    public let agentName: String
    public let agentDescription: String?
    public let delegationDescription: String?
    public let agentStatus: Int?
    public let agentAvailable: Bool

    public var isUnavailable: Bool { !agentAvailable }
    /// Delegation text wins, otherwise the member's own description
    /// (`harnax-webui/src/pages/team/index.tsx:238-244`).
    public var detail: String? { hxPresented(delegationDescription) ?? hxPresented(agentDescription) }
    public var displayName: String? { hxPresented(agentName) }
}

/// `skillList[]` of the lead — same "broken reference stays visible" rule as a member
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamResponse.kt:70-84`).
public struct TeamLeadSkill: Decodable, Equatable, Sendable {
    public let skillId: Int64
    public let skillName: String
    public let skillDescription: String?
    public let repositoryId: Int64?
    public let repositoryName: String?
    public let skillAvailable: Bool

    public var isUnavailable: Bool { !skillAvailable }
    public var displayName: String? { hxPresented(skillName) }
    public var repository: String? { hxPresented(repositoryName) }
    public var detail: String? { hxPresented(skillDescription) }
}
