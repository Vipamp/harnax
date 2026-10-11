import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The two call-metrics screens' stand-in, on the reply-queue discipline `SkillUsageFakes.swift:22-39` sets: a
/// request nobody queued answers as a decoding failure and still shows up in its route's log, so a third read on
/// entry — or a re-fetch behind a filter that is not a filter — reads as a wrong count rather than a silent pass.
///
/// One `gate` holds every route open and `release()` lets the held replies land in the order they went out, which
/// is what both races need: the summary screen's「an old window's trend lands after the new window's two requests
/// are out」and the detail screen's「a tail page lands after the outcome arm moved」. The lock is not decoration:
/// `CallMetricsViewModel.load` fans the two routes out through a task group (`CallMetricsViewModel.swift:265-276`),
/// so two tasks write the logs at once — `FakeTokenStats` locks for the same reason.
final class FakeToolMetrics: ToolMetricsReading, @unchecked Sendable {
    /// Every `/summary` read, in order.
    var summaryRequests: [SummaryRead] { sync { summaryLog } }

    /// Every `/time-series` read, in order.
    var trendRequests: [TrendRead] { sync { trendLog } }

    /// Every `/invocations` query, whole: the eleven filters are the claim under test, and `CallInvocationQuery`
    /// is `Equatable`, so a log can be compared in one line.
    var invocationRequests: [CallInvocationQuery] { sync { invocationLog } }

    /// The whole page family's traffic — what a「this switch costs no request」assertion reads.
    var totalRequestCount: Int {
        sync { summaryLog.count + trendLog.count + invocationLog.count }
    }

    var summaryReplies: [Result<CallMetricsSummary, APIError>] {
        get { sync { summaryQueue } }
        set { sync { summaryQueue = newValue } }
    }

    var trendReplies: [Result<CallMetricsTimeSeries, APIError>] {
        get { sync { trendQueue } }
        set { sync { trendQueue = newValue } }
    }

    var invocationReplies: [Result<Page<CallInvocationRow>, APIError>] {
        get { sync { invocationQueue } }
        set { sync { invocationQueue = newValue } }
    }

    /// Holds every route open.
    var gate = false

    /// Holds the trend route alone. The pairing it exists for is the one a full gate can never catch: the summary
    /// already answered while its trend is still on the wire (`CallMetricsViewModel.swift:362-366`).
    var gateTrend = false

    private let lock = NSLock()
    private var summaryQueue: [Result<CallMetricsSummary, APIError>] = []
    private var summaryLog: [SummaryRead] = []
    private var trendQueue: [Result<CallMetricsTimeSeries, APIError>] = []
    private var trendLog: [TrendRead] = []
    private var invocationQueue: [Result<Page<CallInvocationRow>, APIError>] = []
    private var invocationLog: [CallInvocationQuery] = []
    private var parked: [(route: Route, resume: () -> Void)] = []

    private enum Route {
        case summary, trend, invocations
    }

    func summary(
        window: CallMetricsWindow,
        group: CallMetricsGroup,
        kind: CallMetricsKind?
    ) async -> Result<CallMetricsSummary, APIError> {
        let held = sync {
            summaryLog.append(SummaryRead(days: window.days, group: group, kind: kind))
            return gate
        }
        guard held else { return nextSummary() }
        return await withCheckedContinuation { continuation in
            sync { parked.append((.summary, { continuation.resume(returning: self.nextSummary()) })) }
        }
    }

    func timeSeries(
        window: CallMetricsWindow,
        kind: CallMetricsKind?,
        subjectId: Int64?,
        granularity: CallMetricsGranularity
    ) async -> Result<CallMetricsTimeSeries, APIError> {
        let held = sync {
            trendLog.append(TrendRead(
                days: window.days,
                kind: kind,
                subjectId: subjectId,
                granularity: granularity
            ))
            return gate || gateTrend
        }
        guard held else { return nextTrend() }
        return await withCheckedContinuation { continuation in
            sync { parked.append((.trend, { continuation.resume(returning: self.nextTrend()) })) }
        }
    }

    func invocations(_ query: CallInvocationQuery) async -> Result<Page<CallInvocationRow>, APIError> {
        let held = sync {
            invocationLog.append(query)
            return gate
        }
        guard held else { return nextInvocations() }
        return await withCheckedContinuation { continuation in
            sync { parked.append((.invocations, { continuation.resume(returning: self.nextInvocations()) })) }
        }
    }

    /// Lets every held reply land, in the order its request went out — each pulling the next reply of its own
    /// route, so queue index N is answer N for that route whatever the two routes' interleaving turned out to be.
    func release() {
        drain { _ in true }
    }

    /// Lets only the held trend replies land, oldest first; the other routes stay on the wire.
    func releaseTrend() {
        drain { $0 == .trend }
    }

    private func drain(_ route: (Route) -> Bool) {
        let waiting = sync {
            let waiting = parked.filter { route($0.route) }
            parked.removeAll { held in route(held.route) }
            return waiting
        }
        for held in waiting { held.resume() }
    }

    private func nextSummary() -> Result<CallMetricsSummary, APIError> {
        sync { summaryQueue.isEmpty ? .failure(.decoding) : summaryQueue.removeFirst() }
    }

    private func nextTrend() -> Result<CallMetricsTimeSeries, APIError> {
        sync { trendQueue.isEmpty ? .failure(.decoding) : trendQueue.removeFirst() }
    }

    private func nextInvocations() -> Result<Page<CallInvocationRow>, APIError> {
        sync { invocationQueue.isEmpty ? .failure(.decoding) : invocationQueue.removeFirst() }
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// One logged `/summary` read. A struct rather than a tuple because a whole log has to be comparable in one
/// assertion (`TokenMonitorFakes.swift:136-137` gives the same reason) — and the window is logged as its day
/// count, which is the only part of a `CallMetricsWindow` a query can depend on.
struct SummaryRead: Equatable {
    let days: Int
    let group: CallMetricsGroup
    let kind: CallMetricsKind?

    init(days: Int, group: CallMetricsGroup, kind: CallMetricsKind? = nil) {
        self.days = days
        self.group = group
        self.kind = kind
    }
}

/// One logged `/time-series` read, including the two parameters this screen must always answer the same way: it
/// never narrows to a subject, and it never re-derives the server's fold rule.
struct TrendRead: Equatable {
    let days: Int
    let kind: CallMetricsKind?
    let subjectId: Int64?
    let granularity: CallMetricsGranularity

    init(
        days: Int,
        kind: CallMetricsKind? = nil,
        subjectId: Int64? = nil,
        granularity: CallMetricsGranularity = .auto
    ) {
        self.days = days
        self.kind = kind
        self.subjectId = subjectId
        self.granularity = granularity
    }
}

// MARK: - fixtures

/// The fixtures, all of them decoded.
///
/// Every contract type here is `Decodable` with no memberwise initialiser on purpose — a row is what the stack
/// answered and nothing else (`ToolMetrics.swift:13-141`) — so a fixture names the keys the DTO declares
/// (`ToolMetricsResponse.kt:18-126`) rather than inventing a field the server does not send, exactly the way
/// `SkillUsageFakes.swift:42-74` does for its own two types.

extension CallMetricsSummary {
    /// A window that answered nobody: the four cards read as zeros and the table is empty, which is this screen's
    /// 「no calls」rather than its「unreadable」(`CallMetricsViewModel.swift:354-372`).
    static func stub(
        from: String = "2026-10-11 00:00:00",
        to: String = "2026-10-11 20:00:00",
        groupBy: String = "tool",
        totalCalls: Int64 = 0,
        totalSuccesses: Int64 = 0,
        successRate: Double = 0,
        failingCalls: Int64 = 0,
        p95Operator: String = "<=",
        p95Ms: Int64 = 0,
        rows: [CallMetricsRow] = []
    ) throws -> CallMetricsSummary {
        try JSONDecoder().decode(CallMetricsSummary.self, from: jsonData([
            "from": from,
            "to": to,
            "groupBy": groupBy,
            "totalCalls": totalCalls,
            "totalSuccesses": totalSuccesses,
            "successRate": successRate,
            "failingCalls": failingCalls,
            "p95Operator": p95Operator,
            "p95Ms": p95Ms,
            "rows": rows.map { $0.stubPayload },
        ]))
    }
}

extension CallMetricsRow {
    /// The ordinary drillable row: an MCP server, which is the one shape that carries both a `subjectId` and a
    /// `parentName` (`ToolMetrics.swift:43-53`).
    static func stub(
        kind: String = "mcp",
        subjectKey: String = "7",
        subjectId: Int64? = 7,
        toolName: String = "",
        subjectName: String = "browser-use",
        parentName: String = "",
        calls: Int64 = 0,
        successes: Int64 = 0,
        errors: Int64 = 0,
        denials: Int64 = 0,
        interruptions: Int64 = 0,
        successRate: Double = 0,
        avgDurationMs: Int64 = 0,
        p95Operator: String = "<=",
        p95Ms: Int64 = 0,
        lastSeenAt: String? = "2026-10-11 19:00:00"
    ) throws -> CallMetricsRow {
        try JSONDecoder().decode(CallMetricsRow.self, from: jsonData(payload(
            kind: kind,
            subjectKey: subjectKey,
            subjectId: subjectId,
            toolName: toolName,
            subjectName: subjectName,
            parentName: parentName,
            calls: calls,
            successes: successes,
            errors: errors,
            denials: denials,
            interruptions: interruptions,
            successRate: successRate,
            avgDurationMs: avgDurationMs,
            p95Operator: p95Operator,
            p95Ms: p95Ms,
            lastSeenAt: lastSeenAt
        )))
    }

    /// A row with no subject id at all — a builtin tool, or any row of the session dimension, whose `subject_id`
    /// the SQL answers as `CAST(NULL AS SIGNED)` (`harnax-ios/specs/10-call-metrics.md` §2.4). The contract's only
    /// nullable id, and the reason `isDrillable` exists (`ToolMetrics.swift:71-73`).
    static func idless(
        kind: String = "builtin",
        subjectKey: String = "read_file",
        subjectName: String = "read_file"
    ) throws -> CallMetricsRow {
        try stub(kind: kind, subjectKey: subjectKey, subjectId: nil, toolName: subjectKey, subjectName: subjectName)
    }

    fileprivate var stubPayload: [String: Any] {
        Self.payload(
            kind: kind,
            subjectKey: subjectKey,
            subjectId: subjectId,
            toolName: toolName,
            subjectName: subjectName,
            parentName: parentName,
            calls: calls,
            successes: successes,
            errors: errors,
            denials: denials,
            interruptions: interruptions,
            successRate: successRate,
            avgDurationMs: avgDurationMs,
            p95Operator: p95Operator,
            p95Ms: p95Ms,
            lastSeenAt: lastSeenAt
        )
    }

    /// The sixteen columns of `ToolMetricsResponse.kt:18-58`, in one place so a stub and a real reply are built
    /// by the same dictionary.
    private static func payload(
        kind: String,
        subjectKey: String,
        subjectId: Int64?,
        toolName: String,
        subjectName: String,
        parentName: String,
        calls: Int64,
        successes: Int64,
        errors: Int64,
        denials: Int64,
        interruptions: Int64,
        successRate: Double,
        avgDurationMs: Int64,
        p95Operator: String,
        p95Ms: Int64,
        lastSeenAt: String?
    ) -> [String: Any] {
        [
            "kind": kind,
            "subjectKey": subjectKey,
            "subjectId": subjectId as Any,
            "toolName": toolName,
            "subjectName": subjectName,
            "parentName": parentName,
            "calls": calls,
            "successes": successes,
            "errors": errors,
            "denials": denials,
            "interruptions": interruptions,
            "successRate": successRate,
            "avgDurationMs": avgDurationMs,
            "p95Operator": p95Operator,
            "p95Ms": p95Ms,
            "lastSeenAt": lastSeenAt as Any,
        ]
    }
}

extension CallMetricsTimeSeries {
    /// A trend folded to `granularity` over the given points. Zero-fill is the service's job
    /// (`ToolMetrics.swift:96-99`), so a quiet bucket here is a point of zeroes rather than a hole.
    static func stub(
        from: String = "2026-10-11 00:00:00",
        to: String = "2026-10-11 20:00:00",
        granularity: String = "hour",
        points: [CallMetricsPoint] = []
    ) throws -> CallMetricsTimeSeries {
        try JSONDecoder().decode(CallMetricsTimeSeries.self, from: jsonData([
            "from": from,
            "to": to,
            "granularity": granularity,
            "points": points.map { $0.stubPayload },
        ]))
    }
}

extension CallMetricsPoint {
    /// Only `dimensionId` is nullable on this DTO (`ToolMetrics.swift:77-93`), so every other column is always
    /// spelled out.
    static func stub(
        timePoint: String = "2026-10-11 18:00:00",
        kind: String = "mcp",
        dimensionId: Int64? = 7,
        dimensionName: String = "browser-use",
        calls: Int64 = 0,
        successes: Int64 = 0,
        errors: Int64 = 0,
        denials: Int64 = 0,
        interruptions: Int64 = 0,
        avgDurationMs: Int64 = 0
    ) throws -> CallMetricsPoint {
        try JSONDecoder().decode(CallMetricsPoint.self, from: jsonData(payload(
            timePoint: timePoint,
            kind: kind,
            dimensionId: dimensionId,
            dimensionName: dimensionName,
            calls: calls,
            successes: successes,
            errors: errors,
            denials: denials,
            interruptions: interruptions,
            avgDurationMs: avgDurationMs
        )))
    }

    fileprivate var stubPayload: [String: Any] {
        Self.payload(
            timePoint: timePoint,
            kind: kind,
            dimensionId: dimensionId,
            dimensionName: dimensionName,
            calls: calls,
            successes: successes,
            errors: errors,
            denials: denials,
            interruptions: interruptions,
            avgDurationMs: avgDurationMs
        )
    }

    private static func payload(
        timePoint: String,
        kind: String,
        dimensionId: Int64?,
        dimensionName: String,
        calls: Int64,
        successes: Int64,
        errors: Int64,
        denials: Int64,
        interruptions: Int64,
        avgDurationMs: Int64
    ) -> [String: Any] {
        [
            "timePoint": timePoint,
            "kind": kind,
            "dimensionId": dimensionId as Any,
            "dimensionName": dimensionName,
            "calls": calls,
            "successes": successes,
            "errors": errors,
            "denials": denials,
            "interruptions": interruptions,
            "avgDurationMs": avgDurationMs,
        ]
    }
}

extension CallInvocationRow {
    /// One recorded call. Seven of the sixteen columns are nullable
    /// (`harnax-ios/specs/10-call-metrics.md` §3.3), so the defaults leave the payloads and the failure reason
    /// absent — the shape a deployment with capture switched off answers (`ToolMetrics.swift:126-127`).
    static func stub(
        id: Int64 = 1,
        kind: String = "mcp",
        toolName: String = "web_search",
        agentId: Int64? = 3,
        sessionId: String = "sess-7",
        sessionName: String? = "周报整理",
        userId: Int64? = 1,
        mcpId: Int64? = 7,
        cliId: Int64? = nil,
        outcome: String = "SUCCESS",
        errorMessage: String? = nil,
        argsJson: String? = nil,
        resultExcerpt: String? = nil,
        durationMs: Int64 = 120,
        startTime: String? = "2026-10-11 19:02:00",
        ts: String? = "2026-10-11 19:02:11"
    ) throws -> CallInvocationRow {
        try JSONDecoder().decode(CallInvocationRow.self, from: jsonData(payload(
            id: id,
            kind: kind,
            toolName: toolName,
            agentId: agentId,
            sessionId: sessionId,
            sessionName: sessionName,
            userId: userId,
            mcpId: mcpId,
            cliId: cliId,
            outcome: outcome,
            errorMessage: errorMessage,
            argsJson: argsJson,
            resultExcerpt: resultExcerpt,
            durationMs: durationMs,
            startTime: startTime,
            ts: ts
        )))
    }

    fileprivate var stubPayload: [String: Any] {
        Self.payload(
            id: id,
            kind: kind,
            toolName: toolName,
            agentId: agentId,
            sessionId: sessionId,
            sessionName: sessionName,
            userId: userId,
            mcpId: mcpId,
            cliId: cliId,
            outcome: outcome,
            errorMessage: errorMessage,
            argsJson: argsJson,
            resultExcerpt: resultExcerpt,
            durationMs: durationMs,
            startTime: startTime,
            ts: ts
        )
    }

    private static func payload(
        id: Int64,
        kind: String,
        toolName: String,
        agentId: Int64?,
        sessionId: String,
        sessionName: String?,
        userId: Int64?,
        mcpId: Int64?,
        cliId: Int64?,
        outcome: String,
        errorMessage: String?,
        argsJson: String?,
        resultExcerpt: String?,
        durationMs: Int64,
        startTime: String?,
        ts: String?
    ) -> [String: Any] {
        [
            "id": id,
            "kind": kind,
            "toolName": toolName,
            "agentId": agentId as Any,
            "sessionId": sessionId,
            "sessionName": sessionName as Any,
            "userId": userId as Any,
            "mcpId": mcpId as Any,
            "cliId": cliId as Any,
            "outcome": outcome,
            "errorMessage": errorMessage as Any,
            "argsJson": argsJson as Any,
            "resultExcerpt": resultExcerpt as Any,
            "durationMs": durationMs,
            "startTime": startTime as Any,
            "ts": ts as Any,
        ]
    }
}

extension Page where T == CallInvocationRow {
    /// `ResultVo<Page<T>>`'s data body (`Page.swift:9-14`): four keys, and `total` the paging claims read.
    static func stub(
        _ rows: [CallInvocationRow],
        pageNum: Int = 1,
        total: Int? = nil,
        pageSize: Int = 20
    ) throws -> Page<CallInvocationRow> {
        try JSONDecoder().decode(Page<CallInvocationRow>.self, from: jsonData([
            "pageNum": pageNum,
            "pageSize": pageSize,
            "total": total ?? rows.count,
            "records": rows.map { $0.stubPayload },
        ]))
    }
}

/// The one JSON writer the fixtures share, the same as `SkillUsageFakes.swift:147-150`.
private func jsonData(_ fields: [String: Any]) -> Data {
    try! JSONSerialization.data(withJSONObject: fields)
}

extension CallMetricsViewModel {
    /// The value of one card, which is how a test reads a number out of four without spelling their order twice.
    func card(_ kind: CallMetricsSummaryCard.Kind) -> CallMetricsSummaryCard? {
        cards.first { $0.kind == kind }
    }
}
