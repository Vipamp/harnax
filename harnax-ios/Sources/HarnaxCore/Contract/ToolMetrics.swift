import Foundation

/// Call counts for tools, MCP servers and CLI packages: `GET /api/admin/tool-metrics/summary`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/ToolMetricsResponse.kt:60-75`).
///
/// Which tenant is being read is resolved server-side from the caller's own credentials, and there is
/// deliberately no `tenantId` parameter: a client that could name one in a URL could name any other one
/// (`ToolMetricsController.kt:20-25`).
///
/// Every time column on this page family is a `yyyy-MM-dd HH:mm:ss` **string** the service formats, not a typed
/// instant (`ToolMetricsResponse.kt:13-16`) — Jackson serialises `java.time` in ISO form and ignores the
/// configured date format, so a typed field would arrive shaped differently from every other label here.
public struct CallMetricsSummary: Decodable, Equatable, Sendable {
    /// First hour answered, after the server's clamps.
    public let from: String
    /// Last hour answered, inclusive, after the clamps.
    public let to: String
    /// The dimension actually answered — an unrecognised `groupBy` falls back to `tool` rather than failing
    /// (`ToolMetricsResponse.kt:65-66`), so a screen that asked for `session` and gets `tool` back has to read
    /// this field rather than assume its own request.
    public let groupBy: String
    public let totalCalls: Int64
    public let totalSuccesses: Int64
    /// Successes over calls as a fraction; the page formats the percentage.
    public let successRate: Double
    /// Errors, denials and interruptions together (`:70-71`).
    public let failingCalls: Int64
    public let p95Operator: String
    public let p95Ms: Int64
    public let rows: [CallMetricsRow]

    public var group: CallMetricsGroup { CallMetricsGroup(wire: groupBy) }
}

/// One subject of the summary (`ToolMetricsResponse.kt:18-58`).
public struct CallMetricsRow: Decodable, Identifiable, Equatable, Sendable {
    /// Origin bucket: `builtin` / `mcp` / `cli` / `shell` / `framework`. Empty when grouped by agent or
    /// session, because a row of those dimensions spans every origin at once (`:19-20`).
    public let kind: String
    /// The row's key: the tool name by tool, the session id by session, the id as text by mcp or cli, the
    /// agent id as text otherwise (`:21-25`).
    public let subjectKey: String
    /// MCP server id / CLI package id / agent id where the dimension has one, absent otherwise. This is the
    /// value a drill-down predicate is built from (`:26-30`), which is why it is the only id a row may not have.
    public let subjectId: Int64?
    /// Tool name; empty when grouped by mcp, cli, agent or session.
    public let toolName: String
    /// This row's own subject as its registered name — the MCP server, CLI package or agent name, the session
    /// title, or the tool name when grouped by tool. Falls back to `subjectKey` when nothing is registered
    /// behind the id (`:31-38`), so a screen can show this one field and still name every row.
    public let subjectName: String
    /// Owning MCP server or CLI package name, present only on a row whose kind is `mcp` or `cli` (`:39-43`).
    public let parentName: String
    public let calls: Int64
    public let successes: Int64
    public let errors: Int64
    public let denials: Int64
    public let interruptions: Int64
    public let successRate: Double
    public let avgDurationMs: Int64
    /// `<=` when the 95th percentile lands inside a bucket, `>` for the open-ended one; on the agent and
    /// session dimensions this row's longest call instead (`:52-55`).
    public let p95Operator: String
    public let p95Ms: Int64
    /// `yyyy-MM-dd HH:mm:ss`. Hour precision on the aggregate dimensions, second precision on the detail ones
    /// (`:56-57`) — the two precisions are why `readsAggregate` has to be consulted before labelling this.
    public let lastSeenAt: String?

    public var id: String { "\(kind)-\(subjectKey)" }
    public var failingCalls: Int64 { errors + denials + interruptions }
    /// Whether this row can be drilled into invocations: the detail predicate is built from `subjectId`, and a
    /// row without one — a builtin tool, an agent or session dimension — has nothing to filter by.
    public var isDrillable: Bool { subjectId != nil }
}

/// One bucket of the trend: `GET /api/admin/tool-metrics/time-series` (`ToolMetricsResponse.kt:77-90`).
public struct CallMetricsPoint: Decodable, Equatable, Sendable {
    /// Bucket start, formatted by the service.
    public let timePoint: String
    public let kind: String
    /// MCP server or CLI package id; absent for builtin, shell and framework.
    public let dimensionId: Int64?
    /// Tool name as the model sees it; the matched command name when `kind` is `cli`.
    public let dimensionName: String
    public let calls: Int64
    public let successes: Int64
    public let errors: Int64
    public let denials: Int64
    public let interruptions: Int64
    public let avgDurationMs: Int64

    public var id: String { "\(timePoint)-\(kind)-\(dimensionId ?? 0)" }
}

/// The trend itself (`ToolMetricsResponse.kt:92-103`).
///
/// `points` is **zero-filled**, so a quiet hour does not make the line skip (`ToolMetricsController.kt:56-60`);
/// a screen must not add its own gap logic on top of that. Week and month buckets align backwards, so the
/// first point can open before `from` (`:93-97`).
public struct CallMetricsTimeSeries: Decodable, Equatable, Sendable {
    public let from: String
    public let to: String
    /// The bucket the trend was folded to: `hour` / `day` / `week` / `month`.
    public let granularity: String
    public let points: [CallMetricsPoint]
}

/// One recorded call: `GET /api/admin/tool-metrics/invocations` (`ToolMetricsResponse.kt:105-126`).
///
/// Only inside the retention window (`ToolMetricsController.kt:79-83`) — the aggregate dimensions keep their
/// counts longer than the detail table keeps rows, so a trend that still has a bar can page to an empty list.
public struct CallInvocationRow: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64
    public let kind: String
    public let toolName: String
    public let agentId: Int64?
    public let sessionId: String
    /// Registered title of that session; absent when the id has no session row (`:111-112`).
    public let sessionName: String?
    public let userId: Int64?
    public let mcpId: Int64?
    public let cliId: Int64?
    /// `SUCCESS` / `ERROR` / `DENIED` / `INTERRUPTED`.
    public let outcome: String
    public let errorMessage: String?
    /// Both payload columns are absent when capture is switched off deployment-side (`:118-121`), so a screen
    /// that always renders an arguments pane would show an empty one on a healthy install.
    public let argsJson: String?
    public let resultExcerpt: String?
    public let durationMs: Int64
    public let startTime: String?
    public let ts: String?

    /// The name this row should be read under: the session it happened in, or the id when the session row is
    /// gone.
    public var sessionLabel: String {
        guard let sessionName, !sessionName.isEmpty else { return sessionId }
        return sessionName
    }
}

/// The subject dimension the summary groups by (`ToolMetricsController.kt:46-48`).
///
/// The split is not cosmetic: the first three read the hourly aggregate and the last two the detail table, so
/// only the detail-backed two are bounded by the retention window and answer at the call's own instant
/// (`harnax-webui/src/pages/call-metrics/dimensions.ts:1-19`).
public enum CallMetricsGroup: String, CaseIterable, Identifiable, Sendable {
    case tool
    case mcp
    case cli
    case agent
    case session

    public var id: String { rawValue }
    public var titleKey: String { "monitor.calls.group.\(rawValue)" }

    /// Whether this dimension reads the hourly aggregate rather than the detail table.
    public var readsAggregate: Bool { self == .tool || self == .mcp || self == .cli }

    /// Unknown wire values fall back to `tool`, which is what the server does with them rather than failing
    /// (`ToolMetricsResponse.kt:65-66`).
    public init(wire: String) { self = CallMetricsGroup(rawValue: wire) ?? .tool }
}

/// The origin filter (`ToolMetricsController.kt:45`). Sent only when the caller picks one.
///
/// On the `mcp` and `cli` dimensions the dimension *is* the origin bucket, so this cannot widen those rows
/// (`:47`) — a screen that shows both controls has to say so rather than offer a combination that does nothing.
public enum CallMetricsKind: String, CaseIterable, Identifiable, Sendable {
    case builtin
    case mcp
    case cli
    case shell
    case framework

    public var id: String { rawValue }
    public var titleKey: String { "monitor.calls.kind.\(rawValue)" }
}

/// Terminal state filter for the detail page (`ToolMetricsController.kt:101`).
public enum CallInvocationOutcome: String, CaseIterable, Identifiable, Sendable {
    case success = "SUCCESS"
    case error = "ERROR"
    case denied = "DENIED"
    case interrupted = "INTERRUPTED"

    public var id: String { rawValue }
    public var titleKey: String { "monitor.calls.outcome.\(rawValue)" }

    public init?(wire: String) { self.init(rawValue: wire) }
}

/// Bucket size for the trend (`ToolMetricsController.kt:70`).
///
/// `auto` is the one the screen uses: it picks hour up to 48 hours, day up to 92 days, then week — the client
/// does not re-derive that rule, it sends `auto` and reads back `granularity`.
public enum CallMetricsGranularity: String, CaseIterable, Identifiable, Sendable {
    case auto
    case hour
    case day
    case week
    case month

    public var id: String { rawValue }
    public var titleKey: String { "monitor.calls.granularity.\(rawValue)" }
}

/// Reporting window, in whole days counted back from the device's today.
///
/// The server takes `yyyy-MM-dd HH:mm` or `yyyy-MM-dd` for `start` and clamps what it cannot honour
/// (`ToolMetricsController.kt:41-44`); the console sends browser-local dates for the same window
/// (`harnax-webui/src/pages/call-metrics/index.tsx`), so a device-local date is the shape both clients agree
/// on. `end` is left off requests entirely, which asks for the current hour rather than guessing one.
public struct CallMetricsWindow: Equatable, Sendable {
    public let days: Int

    public init(days: Int) { self.days = max(1, days) }

    public static let today = CallMetricsWindow(days: 1)
    public static let last7Days = CallMetricsWindow(days: 7)
    public static let last30Days = CallMetricsWindow(days: 30)
    public static let last90Days = CallMetricsWindow(days: 90)

    /// The console's window picker, in the order it shows it.
    public static let presets: [CallMetricsWindow] = [.last7Days, .last30Days, .last90Days]

    /// `yyyy-MM-dd` of the first day inside the window, in the device calendar.
    public var start: String {
        let calendar = Calendar.current
        guard let first = calendar.date(byAdding: .day, value: -(days - 1), to: Date()) else { return "" }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: first)
    }
}

/// One page of recorded calls. Carries every filter the route binds so a screen can hold the whole query in one
/// value rather than in eleven parameters (`ToolMetricsController.kt:84-122`).
public struct CallInvocationQuery: Equatable, Sendable {
    public let window: CallMetricsWindow
    public let kind: CallMetricsKind?
    public let toolName: String?
    public let mcpId: Int64?
    public let cliId: Int64?
    public let agentId: Int64?
    public let sessionId: String?
    public let outcome: CallInvocationOutcome?
    public let num: Int
    public let size: Int

    public init(
        window: CallMetricsWindow = .last30Days,
        kind: CallMetricsKind? = nil,
        toolName: String? = nil,
        mcpId: Int64? = nil,
        cliId: Int64? = nil,
        agentId: Int64? = nil,
        sessionId: String? = nil,
        outcome: CallInvocationOutcome? = nil,
        num: Int = 1,
        size: Int = 20
    ) {
        self.window = window
        self.kind = kind
        self.toolName = toolName
        self.mcpId = mcpId
        self.cliId = cliId
        self.agentId = agentId
        self.sessionId = sessionId
        self.outcome = outcome
        self.num = num
        self.size = size
    }

    /// Same-page query with a different page number — the append path a list screen needs, and the one place
    /// the eleven filters are known to stay together.
    public func page(_ num: Int) -> CallInvocationQuery {
        CallInvocationQuery(
            window: window,
            kind: kind,
            toolName: toolName,
            mcpId: mcpId,
            cliId: cliId,
            agentId: agentId,
            sessionId: sessionId,
            outcome: outcome,
            num: num,
            size: size
        )
    }
}

/// The three reads behind the call-metrics page: counts per subject, the trend, the detail page.
public protocol ToolMetricsReading: Sendable {
    /// Totals per subject over the window.
    func summary(
        window: CallMetricsWindow,
        group: CallMetricsGroup,
        kind: CallMetricsKind?
    ) async -> Result<CallMetricsSummary, APIError>

    /// Zero-filled buckets of the same window. `subjectId` narrows to one MCP server or CLI package.
    func timeSeries(
        window: CallMetricsWindow,
        kind: CallMetricsKind?,
        subjectId: Int64?,
        granularity: CallMetricsGranularity
    ) async -> Result<CallMetricsTimeSeries, APIError>

    /// Recorded calls, inside the retention window only.
    func invocations(_ query: CallInvocationQuery) async -> Result<Page<CallInvocationRow>, APIError>
}
