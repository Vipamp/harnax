import Foundation

/// The two by-id reads a conversation's executor owns — the reads the console fires when its detail modal
/// opens, and the only ones that can name a conversation's tools, CLI packages and team members.
///
/// Why the conversation row is not enough: `SessionResponse` denormalises a *copy* of the executor's name,
/// model, prompt and two binding lists (`SessionServiceImpl.kt:126-158`), and that copy has three holes in
/// it compared with the executor's own row.
///
/// - It carries no tools, no CLI packages and no members at all: the DTO has no such columns.
/// - Its `mcpList` follows `agentId` only, so a team conversation answers it empty
///   (`SessionServiceImpl.kt:129-138`) — which is right, since a team binds no MCP server.
/// - A skill whose row has since been deleted is **dropped** from `skillList` by the loop's
///   `getSkill(skillId) ?: continue` (`SessionServiceImpl.kt:145`), while the team's own read keeps the
///   binding visible and flags it `skillAvailable = false` (`TeamServiceImpl.kt:181-192`). The console's
///   失效 badge is only reachable through the by-id row for exactly that reason, and the same holds for a
///   member's `agentAvailable` (`TeamServiceImpl.kt:193-203`).
///
/// A team conversation never carries an `agentId` at all — design D1 leaves the column NULL because a team
/// has no lead agent row to point at (`SessionServiceImpl.kt:199-207`) — so the two reads here address one
/// conversation each, and the sheet asks for whichever the row names.
///
/// The agent route answers the same `AgentResponse` shape the page row carries
/// (`AgentController.kt:57-69` → `AgentServiceImpl.kt:265-395`), so `AgentSummary` decodes both, and a row
/// of another tenant is refused with a plain `404` rather than being named.
public protocol ExecutorReading: Sendable {
    /// `GET /api/admin/agents/{id}` — the agent with its four binding lists.
    func agent(id: Int64) async -> Result<AgentSummary, APIError>

    /// `GET /api/admin/teams/{id}` — the team's own lead configuration and its membership, in the order
    /// the lead delegates to them.
    func team(id: Int64) async -> Result<TeamSummary, APIError>
}
