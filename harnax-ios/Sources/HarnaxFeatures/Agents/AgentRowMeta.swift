import Foundation
import HarnaxCore
import HarnaxKit

/// The byline under an agent card — `heqingsong · 09-12 · 3 个会话`.
///
/// The creator and date come from the shared `RowMeta` rule; only the session count is agent-specific,
/// since a team row carries no such column.
public enum AgentRowMeta {
    public static func monthDay(_ raw: String?) -> String? { hxMonthDay(raw) }

    public static func byline(for agent: AgentSummary) -> String {
        var pieces: [String] = []
        let base = RowMeta.byline(creator: agent.creator, createTime: agent.createTime)
        if !base.isEmpty { pieces.append(base) }
        if let sessions = agent.sessionCount, sessions > 0 { pieces.append(hxCount("agent.sessionCount", sessions)) }
        return pieces.joined(separator: " · ")
    }
}
