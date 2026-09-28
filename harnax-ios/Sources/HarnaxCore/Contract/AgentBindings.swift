import Foundation

/// Presentation name for anything the server may answer with `null`, an empty string or a run of
/// spaces. All three read as "this row carries no name" on a card.
public func hxPresented(_ text: String?) -> String? {
    guard let text else { return nil }
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

/// One environment parameter on a bound tool, MCP or CLI.
///
/// The server resolves a referenced variable into `envValue` — the latest value, `******` when the
/// variable is sensitive — and keeps what the operator typed in `customValue`. Both are display-only:
/// echoing a mask back through a save would turn the asterisks into a real value.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolConfig.kt:18-33`, resolved on
/// read at `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:748-778`
public struct AgentEnvBinding: Decodable, Equatable, Sendable {
    public let envKey: String
    public let envValue: String?
    public let envVarId: Int64?
    public let envVarName: String?
    public let customValue: String?

    /// A row that references a variable has its `envValue` filled and its `customValue` absent, and the
    /// other way round — so the two never both compete.
    public var displayValue: String? {
        hxPresented(customValue) ?? hxPresented(envValue)
    }

    public var referencesVariable: Bool { envVarId != nil }
}

/// `toolList[]` — the tool's own identity travels with the binding, which is why the card can name a tool
/// without asking the tool domain.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:124-145`
public struct AgentToolBinding: Decodable, Equatable, Sendable {
    public let toolId: Int64?
    public let toolName: String?
    public let toolDisplayName: String?
    public let toolDisplayNameZh: String?
    public let toolDescription: String?
    public let needConfirm: Bool?
    public let envBindings: [AgentEnvBinding]?

    /// Chinese reads `中文名 → 显示名 → 代码名`, English skips the Chinese column
    /// (`harnax-webui/src/pages/agent/index.tsx:107-109`).
    public func name(chinese: Bool) -> String? {
        let candidates = chinese ? [toolDisplayNameZh, toolDisplayName, toolName] : [toolDisplayName, toolName]
        return candidates.lazy.compactMap(hxPresented).first
    }

    /// Per-binding tightening. The backend only ever lets this be switched on, never off
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:425-427`).
    public var requiresConfirmation: Bool { needConfirm == true }
    public var description: String? { hxPresented(toolDescription) }
    public var envCount: Int { envBindings?.count ?? 0 }
}

/// `mcpList[]` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:91-103`).
public struct AgentMcpBinding: Decodable, Equatable, Sendable {
    public let mcpId: Int64?
    public let mcpName: String?
    public let mcpDescription: String?
    public let envBindings: [AgentEnvBinding]?

    public var name: String? { hxPresented(mcpName) }
    public var description: String? { hxPresented(mcpDescription) }
    public var envCount: Int { envBindings?.count ?? 0 }
}

/// `skillList[]` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:106-121`).
/// Skills carry no environment parameters — the binding table has no column for them.
public struct AgentSkillBinding: Decodable, Equatable, Sendable {
    public let repositoryId: Int64?
    public let repositoryName: String?
    public let skillId: Int64?
    public let skillName: String?
    public let skillDescription: String?

    public var name: String? { hxPresented(skillName) }
    public var repository: String? { hxPresented(repositoryName) }
    public var description: String? { hxPresented(skillDescription) }
}

/// `cliList[]` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:124-166`).
/// The `skillList` here is the one skill shipped inside the package, not a second binding dimension.
public struct AgentCliBinding: Decodable, Equatable, Sendable {
    public let cliId: Int64?
    public let cliName: String?
    public let cliDescription: String?
    public let version: String?
    public let envBindings: [AgentEnvBinding]?
    public let skillList: [AgentSkillBinding]?

    public var name: String? { hxPresented(cliName) }
    public var description: String? { hxPresented(cliDescription) }
    public var packageVersion: String? { hxPresented(version) }
    public var skillNames: [String] { skillList?.compactMap { $0.name } ?? [] }
    public var envCount: Int { envBindings?.count ?? 0 }
}

/// One entry of `sessionList[]` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:168-181`).
public struct AgentSession: Decodable, Equatable, Sendable {
    public let id: Int64?
    public let title: String?
    public let sessionDescription: String?
    public let sessionId: String?

    public var name: String? { hxPresented(title) }
    public var description: String? { hxPresented(sessionDescription) }
}
