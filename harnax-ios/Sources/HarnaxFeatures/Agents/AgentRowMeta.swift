import Foundation
import HarnaxCore
import HarnaxKit

/// The byline under a record card — `heqingsong · 09-12 · 3 个会话`.
///
/// `createTime` arrives as a serialised `LocalDateTime`, so it is either `2026-09-12 14:20:00` or the
/// `T`-joined ISO form. The year is dropped because a management list shows recent rows, and a date
/// this side cannot parse is left out rather than guessed at.
public enum AgentRowMeta {
    public static func monthDay(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        let datePart = raw.split(whereSeparator: { $0 == " " || $0 == "T" || $0 == "." }).first
        let parts = datePart?.split(separator: "-") ?? []
        guard parts.count == 3, parts[1].count == 2, parts[2].count == 2 else { return nil }
        return "\(parts[1])-\(parts[2])"
    }

    public static func byline(for agent: AgentSummary) -> String {
        var pieces: [String] = []
        if let creator = agent.creator, !creator.isEmpty { pieces.append(creator) }
        if let day = monthDay(agent.createTime) { pieces.append(day) }
        if let sessions = agent.sessionCount, sessions > 0 { pieces.append(hx("agent.sessionCount", sessions)) }
        return pieces.joined(separator: " · ")
    }
}
