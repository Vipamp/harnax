import Foundation

/// The body the two agent save routes accept, in the shape that keeps the server's null semantics.
///
/// The backend reads a missing field as 「keep what is there」 and an empty list as 「clear it」
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:185-197`), so an
/// edit form cannot send one uniform payload: every binding list here is optional, and a form that did not
/// touch a group leaves it `nil` rather than sending the list it read.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentCreateRequest.kt:12-49`,
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentUpdateRequest.kt:10-40`
public struct AgentSaveDraft: Encodable, Equatable, Sendable {
    public var name: String?
    public var description: String?
    public var systemPrompt: String?
    public var modelId: Int64?
    /// Skill ids in display order. `skillList` is a comma-joined **string** on the wire, not an array, so the
    /// three states have to be encoded by hand: `nil` sends nothing and keeps the current set, `[]` sends the
    /// empty string and clears it (`AgentServiceImpl.kt:534-536` deletes before the blank check returns).
    public var skillIDs: [Int64]?
    public var tools: [AgentToolDraft]?
    public var mcps: [AgentMcpDraft]?
    public var clis: [AgentCliDraft]?
    /// Create only — `AgentUpdateRequest` has no such field, and the owner column is not editable afterwards.
    public var owner: String?
    public var status: Int?
    public var isPublic: Int?
    /// The self-evolution switch. `nil` sends nothing and keeps what the row holds; the update route only
    /// writes it when the key is present (`AgentServiceImpl.kt:180`).
    public var skillSelfWrite: Int?

    public init(
        name: String? = nil,
        description: String? = nil,
        systemPrompt: String? = nil,
        modelId: Int64? = nil,
        skillIDs: [Int64]? = nil,
        tools: [AgentToolDraft]? = nil,
        mcps: [AgentMcpDraft]? = nil,
        clis: [AgentCliDraft]? = nil,
        owner: String? = nil,
        status: Int? = nil,
        isPublic: Int? = nil,
        skillSelfWrite: Int? = nil
    ) {
        self.name = name
        self.description = description
        self.systemPrompt = systemPrompt
        self.modelId = modelId
        self.skillIDs = skillIDs
        self.tools = tools
        self.mcps = mcps
        self.clis = clis
        self.owner = owner
        self.status = status
        self.isPublic = isPublic
        self.skillSelfWrite = skillSelfWrite
    }

    enum CodingKeys: String, CodingKey {
        case name, description, systemPrompt, modelId, skillList, toolList, mcpList, cliList, owner, status,
            isPublic, skillSelfWrite
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encodeIfPresent(name, forKey: .name)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(systemPrompt, forKey: .systemPrompt)
        try box.encodeIfPresent(modelId, forKey: .modelId)
        if let skillIDs {
            try box.encode(skillIDs.map(String.init).joined(separator: ","), forKey: .skillList)
        }
        try box.encodeIfPresent(tools, forKey: .toolList)
        try box.encodeIfPresent(mcps, forKey: .mcpList)
        try box.encodeIfPresent(clis, forKey: .cliList)
        try box.encodeIfPresent(owner, forKey: .owner)
        try box.encodeIfPresent(status, forKey: .status)
        try box.encodeIfPresent(isPublic, forKey: .isPublic)
        try box.encodeIfPresent(skillSelfWrite, forKey: .skillSelfWrite)
    }
}

/// One environment parameter as the save route wants it.
///
/// A row that references a variable carries the reference and nothing else: the read side fills `envValue`
/// with the live value, `******` for a sensitive variable, and snapshotting that back would turn the mask
/// into the fallback value the tool receives once the variable is deleted
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:612-626`).
public struct AgentEnvBindingDraft: Encodable, Equatable, Sendable {
    public let envKey: String
    /// Set when the value comes from an environment variable rather than from the operator.
    public let envVarID: Int64?
    /// What the operator typed, sent only when `envVarID` is nil.
    public let customValue: String?

    public init(envKey: String, envVarID: Int64? = nil, customValue: String? = nil) {
        self.envKey = envKey
        self.envVarID = envVarID
        self.customValue = customValue
    }

    /// A reference wins outright, so a row that points at a variable never also carries a typed value.
    public static func referenced(envKey: String, envVarID: Int64) -> AgentEnvBindingDraft {
        AgentEnvBindingDraft(envKey: envKey, envVarID: envVarID)
    }

    public static func custom(envKey: String, value: String?) -> AgentEnvBindingDraft {
        AgentEnvBindingDraft(envKey: envKey, customValue: value)
    }

    enum CodingKeys: String, CodingKey {
        case envKey, envVarId, envValue, envVarName, customValue
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encode(envKey, forKey: .envKey)
        if let envVarID {
            try box.encode(envVarID, forKey: .envVarId)
        } else {
            try box.encodeIfPresent(customValue, forKey: .customValue)
        }
    }
}

/// `toolList[]`. `needConfirm` may only be tightened, never widened
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:5-15`).
public struct AgentToolDraft: Encodable, Equatable, Sendable {
    public let id: Int64
    public let needConfirm: Bool?
    public let envBindings: [AgentEnvBindingDraft]?

    public init(id: Int64, needConfirm: Bool? = nil, envBindings: [AgentEnvBindingDraft]? = nil) {
        self.id = id
        self.needConfirm = needConfirm
        self.envBindings = envBindings
    }
}

/// `mcpList[]` (`AgentCreateRequest.kt:55-61`).
public struct AgentMcpDraft: Encodable, Equatable, Sendable {
    public let id: Int64
    public let envBindings: [AgentEnvBindingDraft]?

    public init(id: Int64, envBindings: [AgentEnvBindingDraft]? = nil) {
        self.id = id
        self.envBindings = envBindings
    }
}

/// `cliList[]` (`AgentCreateRequest.kt:67-73`).
public struct AgentCliDraft: Encodable, Equatable, Sendable {
    public let id: Int64
    public let envBindings: [AgentEnvBindingDraft]?

    public init(id: Int64, envBindings: [AgentEnvBindingDraft]? = nil) {
        self.id = id
        self.envBindings = envBindings
    }
}

/// The body the two team save routes accept, with the same keep-versus-replace reading of `nil`.
///
/// The team row *is* its lead: name, description, prompt and model are the lead's own fields, and skills are
/// the only capability a lead may carry — tools, MCPs and CLIs belong to the member agents on their own pages
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TeamCreateRequest.kt:10-16`).
public struct TeamSaveDraft: Encodable, Equatable, Sendable {
    public var name: String?
    public var description: String?
    public var systemPrompt: String?
    public var modelId: Int64?
    /// Non-nil replaces the lead's whole skill set, and `[]` means 「no skill for the lead」 — which `nil`
    /// explicitly does not (`TeamUpdateRequest.kt:8-13`).
    public var skillIDs: [Int64]?
    /// Non-nil replaces the whole membership. Create requires at least one member
    /// (`TeamCreateRequest.kt:41-44`).
    public var members: [TeamMemberDraft]?
    public var status: Int?
    public var isPublic: Int?

    public init(
        name: String? = nil,
        description: String? = nil,
        systemPrompt: String? = nil,
        modelId: Int64? = nil,
        skillIDs: [Int64]? = nil,
        members: [TeamMemberDraft]? = nil,
        status: Int? = nil,
        isPublic: Int? = nil
    ) {
        self.name = name
        self.description = description
        self.systemPrompt = systemPrompt
        self.modelId = modelId
        self.skillIDs = skillIDs
        self.members = members
        self.status = status
        self.isPublic = isPublic
    }

    enum CodingKeys: String, CodingKey {
        case name, description, systemPrompt, modelId, skillIds, members, status, isPublic
    }

    public func encode(to encoder: Encoder) throws {
        var box = encoder.container(keyedBy: CodingKeys.self)
        try box.encodeIfPresent(name, forKey: .name)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(systemPrompt, forKey: .systemPrompt)
        try box.encodeIfPresent(modelId, forKey: .modelId)
        try box.encodeIfPresent(skillIDs, forKey: .skillIds)
        try box.encodeIfPresent(members, forKey: .members)
        try box.encodeIfPresent(status, forKey: .status)
        try box.encodeIfPresent(isPublic, forKey: .isPublic)
    }
}

/// One member: an existing agent plus what this team asks of it.
public struct TeamMemberDraft: Encodable, Equatable, Sendable {
    public let agentId: Int64
    /// Free text, capped at 500 characters server-side (`TeamCreateRequest.kt:56-58`).
    public let delegationDescription: String?

    public init(agentId: Int64, delegationDescription: String? = nil) {
        self.agentId = agentId
        self.delegationDescription = delegationDescription
    }
}
