import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The by-id executor read the detail sheet fires.
///
/// Both answers are settable because the sheet has two answers to distinguish: a row that lands, which puts
/// four panels on screen, and a refusal, which must leave them off and say so on one line instead. The call
/// lists are the evidence the phase machine tests read — "one read per open" and "a retry puts a second on
/// the wire" are both about how often a route was hit, not about what came back.
final class FakeExecutors: ExecutorReading, @unchecked Sendable {
    private(set) var agentCalls: [Int64] = []
    private(set) var teamCalls: [Int64] = []

    var agentReply: Result<AgentSummary, APIError> = .success(ExecutorRows.emptyAgent)
    var teamReply: Result<TeamSummary, APIError> = .success(ExecutorRows.emptyTeam)
    /// Hold the answer on the wire this long, for the tests that act while the read is still out. Zero means
    /// it comes back at once, which is what every other test in the target expects.
    var delayNanoseconds: UInt64 = 0

    func agent(id: Int64) async -> Result<AgentSummary, APIError> {
        agentCalls.append(id)
        await hang()
        return agentReply
    }

    func team(id: Int64) async -> Result<TeamSummary, APIError> {
        teamCalls.append(id)
        await hang()
        return teamReply
    }

    private func hang() async {
        guard delayNanoseconds > 0 else { return }
        try? await Task.sleep(nanoseconds: delayNanoseconds)
    }
}

/// Executor rows for the detail sheet's tests, built the way the server's JSON arrives.
///
/// `AgentSummary.stub`/`TeamSummary.stub` (`Fakes.swift:240-250`) decode a wire object rather than taking a
/// initializer, so a fixture cannot quietly hold a value the DTO does not have — and `TeamSummary` in
/// particular has four non-optional columns whose defaults the panels read as "no bindings".
enum ExecutorRows {
    /// An `AgentSummary` with nothing bound, for the replies a test never inspects.
    static let emptyAgent = try! agent(mcp: [], skills: [], tools: [], cli: [])
    /// A `TeamSummary` with nothing bound, for the replies a test never inspects.
    static let emptyTeam = try! team(id: 12)

    static func agent(
        id: Int64 = 92,
        mcp: [[String: Any]] = [],
        skills: [[String: Any]] = [],
        tools: [[String: Any]] = [],
        cli: [[String: Any]] = []
    ) throws -> AgentSummary {
        try AgentSummary.stub([
            "id": id,
            "name": "估值助手",
            "mcpList": mcp,
            "skillList": skills,
            "toolList": tools,
            "cliList": cli,
        ])
    }

    static func team(
        id: Int64 = 12,
        skills: [[String: Any]] = [],
        members: [[String: Any]] = []
    ) throws -> TeamSummary {
        try TeamSummary.stub([
            "id": id,
            "name": "估值小组",
            "systemPrompt": "把报表分给成员，最后汇总。",
            "modelId": 7,
            "modelName": "gpt-4o",
            "skillList": skills,
            "memberList": members,
        ])
    }

    /// One member of the lead's team. A gone agent still arrives — `TeamServiceImpl.kt:193-203` names it
    /// `#<id>` and flags it — so the fixture keeps the name column overridable to that shape.
    static func member(
        agentId: Int64 = 92,
        agentName: String = "研报检索",
        delegation: String? = "拉取近三年的公告",
        available: Bool = true
    ) -> [String: Any] {
        var row: [String: Any] = [
            "agentId": agentId,
            "agentName": agentName,
            "agentAvailable": available,
        ]
        if let delegation { row["delegationDescription"] = delegation }
        return row
    }

    /// A skill on either row. Only the team's own DTO has the availability column, so the flag is spelled out
    /// here and the agent-side fixture simply never passes one.
    static func teamSkill(
        skillId: Int64 = 21,
        skillName: String = "公告解析",
        repository: String? = "qoder-skills",
        available: Bool = true
    ) -> [String: Any] {
        var row: [String: Any] = [
            "skillId": skillId,
            "skillName": skillName,
            "skillAvailable": available,
        ]
        if let repository { row["repositoryName"] = repository }
        return row
    }

    static func agentSkill(
        skillId: Int64 = 21,
        skillName: String = "公告解析",
        repository: String? = "qoder-skills"
    ) -> [String: Any] {
        var row: [String: Any] = ["skillId": skillId, "skillName": skillName]
        if let repository { row["repositoryName"] = repository }
        return row
    }

    /// A bound tool, in the shape `AgentResponse.ToolItem` answers: three name columns, a confirmation flag
    /// and the environment rows whose count rides as a chip.
    static func tool(
        toolId: Int64 = 5,
        name: String? = "web_search",
        displayName: String? = "Web Search",
        displayNameZh: String? = "联网搜索",
        description: String? = "按关键词检索网页",
        needConfirm: Bool? = nil,
        envCount: Int = 0
    ) -> [String: Any] {
        var row: [String: Any] = [:]
        row["toolId"] = toolId
        if let name { row["toolName"] = name }
        if let displayName { row["toolDisplayName"] = displayName }
        if let displayNameZh { row["toolDisplayNameZh"] = displayNameZh }
        if let description { row["toolDescription"] = description }
        if let needConfirm { row["needConfirm"] = needConfirm }
        row["envBindings"] = envs(envCount)
        return row
    }

    /// A bound CLI package, with the one skill the package ships riding in its own `skillList`.
    static func cli(
        cliId: Int64 = 3,
        name: String? = "harnax-cli",
        version: String? = "1.4.2",
        description: String? = "报表导出",
        envCount: Int = 0,
        skillNames: [String] = []
    ) -> [String: Any] {
        var row: [String: Any] = ["cliId": cliId]
        if let name { row["cliName"] = name }
        if let version { row["version"] = version }
        if let description { row["cliDescription"] = description }
        row["envBindings"] = envs(envCount)
        row["skillList"] = skillNames.map { ["skillName": $0] }
        return row
    }

    static func mcp(
        mcpId: Int64 = 12,
        name: String = "行情源",
        description: String? = "提供收盘行情",
        envCount: Int = 0
    ) -> [String: Any] {
        var row: [String: Any] = ["mcpId": mcpId, "mcpName": name]
        if let description { row["mcpDescription"] = description }
        row["envBindings"] = envs(envCount)
        return row
    }

    /// `count` environment rows. The panels only ever read the count, so the keys are placeholders.
    private static func envs(_ count: Int) -> [[String: Any]] {
        (0..<count).map { ["envKey": "KEY_\($0 + 1)", "envValue": "v"] }
    }
}
