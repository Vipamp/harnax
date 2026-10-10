import Foundation
import HarnaxCore

/// The call-metrics routes: `/api/admin/tool-metrics` and its three reads
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/ToolMetricsController.kt:27,35,56,79`).
///
/// Three shapes worth naming before they get copied wrong:
/// 1. the window is `start` / `end` (`yyyy-MM-dd HH:mm` or `yyyy-MM-dd`), not the `startTime` / `endTime` pair
///    the token-statistics routes use (`TokenStatsEndpoint.swift:20-29`) — the two families are one page in the
///    console and take different parameter names;
/// 2. `end` is left off when the caller has no upper bound, which asks for the current hour rather than a
///    guessed one (`:43-44`);
/// 3. `groupBy` defaults to `tool` and an unrecognised value falls back to it silently
///    (`ToolMetricsResponse.kt:65-66`), so the response's own `groupBy` is what a screen labels itself with.
enum ToolMetricsEndpoint {
    static let root = "/api/admin/tool-metrics"

    /// `start` from the caller's window, and no `end`: every screen in this family asks for「up to now」.
    private static func window(_ days: Int) -> [URLQueryItem] {
        [URLQueryItem(name: "start", value: CallMetricsWindow(days: days).start)]
    }

    private static func kind(_ kind: CallMetricsKind?) -> [URLQueryItem] {
        guard let kind else { return [] }
        return [URLQueryItem(name: "kind", value: kind.rawValue)]
    }

    /// `GET /summary` — totals per subject.
    static func summary(window days: Int, group: CallMetricsGroup, kind: CallMetricsKind?) -> Endpoint {
        var query = window(days)
        query.append(contentsOf: ToolMetricsEndpoint.kind(kind))
        // Sent always rather than left to the default: the dimension is the one thing the table is about.
        query.append(URLQueryItem(name: "groupBy", value: group.rawValue))
        return Endpoint(.get, path: "\(root)/summary", query: query)
    }

    /// `GET /time-series` — zero-filled buckets. `subjectId` narrows to one MCP server or CLI package row
    /// (`:68-69`); a caller without one leaves it off.
    static func timeSeries(
        window days: Int,
        kind: CallMetricsKind?,
        subjectId: Int64?,
        granularity: CallMetricsGranularity
    ) -> Endpoint {
        var query = window(days)
        query.append(contentsOf: ToolMetricsEndpoint.kind(kind))
        if let subjectId {
            query.append(URLQueryItem(name: "subjectId", value: String(subjectId)))
        }
        // `auto` is sent explicitly: the server folds hour up to 48 hours, day up to 92 days, then week
        // (`:70`), and the client reads the bucket back out of the response rather than re-deriving it.
        query.append(URLQueryItem(name: "granularity", value: granularity.rawValue))
        return Endpoint(.get, path: "\(root)/time-series", query: query)
    }

    /// `GET /invocations` — the detail page. Every filter the route binds is sent only when the caller named
    /// one (`:84-106`); an empty `toolName` would ask for calls whose tool name is blank.
    static func invocations(_ query: CallInvocationQuery) -> Endpoint {
        var items = window(query.window.days)
        items.append(contentsOf: ToolMetricsEndpoint.kind(query.kind))
        if let toolName = hxPresented(query.toolName) {
            items.append(URLQueryItem(name: "toolName", value: toolName))
        }
        if let mcpId = query.mcpId {
            items.append(URLQueryItem(name: "mcpId", value: String(mcpId)))
        }
        if let cliId = query.cliId {
            items.append(URLQueryItem(name: "cliId", value: String(cliId)))
        }
        if let agentId = query.agentId {
            items.append(URLQueryItem(name: "agentId", value: String(agentId)))
        }
        if let sessionId = hxPresented(query.sessionId) {
            items.append(URLQueryItem(name: "sessionId", value: sessionId))
        }
        if let outcome = query.outcome {
            items.append(URLQueryItem(name: "outcome", value: outcome.rawValue))
        }
        items.append(URLQueryItem(name: "pageNum", value: String(query.num)))
        // 1..200 server-side (`:105`); the clamp lives here so a screen cannot ask for a page the route refuses.
        items.append(URLQueryItem(name: "pageSize", value: String(min(max(query.size, 1), 200))))
        return Endpoint(.get, path: "\(root)/invocations", query: items)
    }
}
