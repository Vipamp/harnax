import Foundation

/// E5 — the wire shapes of `/api/admin/token-stats/**`.
///
/// Backend: one response class serves all five routes
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/TokenStatsAggregationResponse.kt:14-25`), and
/// which of its five members is filled depends on which route answered:
/// - `/aggregation` fills `overall` plus the three aggregation lists and never fills `timeSeriesData`
///   (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TokenStatsServiceImpl.kt:30-61`);
/// - the four `/time-series*` routes fill *only* `timeSeriesData` (`:65-92`, `:97-111`, `:113-127`, `:129-146`),
///   so `overall` is absent on those replies and the seven KPI numbers can only come from the aggregation read.
///
/// All five members are declared nullable with a `null` default (`:16-24`) and the stack ships
/// `default-property-inclusion: non_null` (`harnax-admin/src/main/resources/application.yml:22-25`), so any of
/// the five keys can be missing. `TokenStatsPayload` therefore normalises them at decode time — an absent list
/// is `[]`, an absent `overall` is all-zero — rather than handing the screen five optionals to unwrap. The
/// zero-filled overall is also what the service itself answers when its SQL row is missing (`:41-45`).
///
/// The metric columns are the opposite case: Kotlin non-null with `0` defaults (`:142-154`), so they always
/// arrive. They are still decoded with a zero default, because that is literally what the server does when its
/// own result map lacks the key — `(map["totalInputToken"] as? Number)?.toLong() ?: 0L` (`:31`).
///
/// The name columns are nullable again, because the SQL reaches them through a `LEFT JOIN`
/// (`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:74-76`): a deleted model or an untitled
/// session still costs tokens and still shows as a row with no name.
public struct TokenStatsPayload: Decodable, Equatable, Sendable {
    public let overall: TokenOverallStats
    public let modelStats: [TokenModelStat]
    public let agentStats: [TokenAgentStat]
    public let sessionStats: [TokenSessionStat]
    /// `timeSeriesData` on the wire. One entry per bucket on `/time-series`, and one entry per
    /// *bucket × dimension* on the three dimension routes — `TokenStatsMapper.xml:234` groups by both — which
    /// is why a chart has to group by `dimensionName` before it can draw a line.
    public let timeSeries: [TokenTimePoint]

    public init(
        overall: TokenOverallStats = TokenOverallStats(),
        modelStats: [TokenModelStat] = [],
        agentStats: [TokenAgentStat] = [],
        sessionStats: [TokenSessionStat] = [],
        timeSeries: [TokenTimePoint] = []
    ) {
        self.overall = overall
        self.modelStats = modelStats
        self.agentStats = agentStats
        self.sessionStats = sessionStats
        self.timeSeries = timeSeries
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        overall = try box.decodeIfPresent(TokenOverallStats.self, forKey: .overall) ?? TokenOverallStats()
        modelStats = try box.decodeIfPresent([TokenModelStat].self, forKey: .modelStats) ?? []
        agentStats = try box.decodeIfPresent([TokenAgentStat].self, forKey: .agentStats) ?? []
        sessionStats = try box.decodeIfPresent([TokenSessionStat].self, forKey: .sessionStats) ?? []
        timeSeries = try box.decodeIfPresent([TokenTimePoint].self, forKey: .timeSeriesData) ?? []
    }

    private enum Key: String, CodingKey {
        case overall, modelStats, agentStats, sessionStats, timeSeriesData
    }
}

/// The four columns every statistics row carries — the `tokenMetrics` fragment
/// (`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:60-65`).
///
/// `totalFee` is a `decimal(10,0)` in `token_stats`, so a sub-yuan fee is stored, summed and returned as a
/// whole number. It is kept as `Decimal` rather than `Double` so that reading stays exact on this side too.
public protocol TokenMetricsReport {
    var totalInputToken: Int64 { get }
    var totalOutputToken: Int64 { get }
    var grandTotalToken: Int64 { get }
    var totalFee: Decimal { get }
}

/// `overall` — the seven numbers behind the KPI cards (`TokenStatsAggregationResponse.kt:140-160`).
///
/// There are exactly seven. The console's KPI row also draws a call count, a failure count and a
/// period-over-period figure, and none of those exist here — this screen has no card for them.
public struct TokenOverallStats: Decodable, Equatable, Sendable, TokenMetricsReport {
    public let totalInputToken: Int64
    public let totalOutputToken: Int64
    public let grandTotalToken: Int64
    public let totalFee: Decimal
    public let agentCount: Int64
    public let sessionCount: Int64
    public let modelCount: Int64

    public init(
        totalInputToken: Int64 = 0,
        totalOutputToken: Int64 = 0,
        grandTotalToken: Int64 = 0,
        totalFee: Decimal = 0,
        agentCount: Int64 = 0,
        sessionCount: Int64 = 0,
        modelCount: Int64 = 0
    ) {
        self.totalInputToken = totalInputToken
        self.totalOutputToken = totalOutputToken
        self.grandTotalToken = grandTotalToken
        self.totalFee = totalFee
        self.agentCount = agentCount
        self.sessionCount = sessionCount
        self.modelCount = modelCount
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        totalInputToken = try box.decodeToken(forKey: .totalInputToken)
        totalOutputToken = try box.decodeToken(forKey: .totalOutputToken)
        grandTotalToken = try box.decodeToken(forKey: .grandTotalToken)
        totalFee = try box.decodeFee(forKey: .totalFee)
        agentCount = try box.decodeToken(forKey: .agentCount)
        sessionCount = try box.decodeToken(forKey: .sessionCount)
        modelCount = try box.decodeToken(forKey: .modelCount)
    }

    /// The empty judgement the whole page hangs on: the console renders nothing at all when this is zero,
    /// which is the server's own "no row in this window" reading rather than a client-side invention
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:878`).
    public var hasConsumption: Bool { grandTotalToken != 0 }

    private enum Key: String, CodingKey {
        case totalInputToken, totalOutputToken, grandTotalToken, totalFee, agentCount, sessionCount, modelCount
    }
}

/// `modelStats[]` — one row per model (`TokenStatsAggregationResponse.kt:166-186`), ordered by
/// `grandTotalToken DESC` server-side and *not* truncated: every model the window saw gets a slice.
public struct TokenModelStat: Decodable, Equatable, Sendable, TokenMetricsReport {
    public let modelId: Int64?
    public let modelName: String?
    /// Absent when the provider row is gone; the console only appends it to the legend when it is there
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:275-281`).
    public let providerName: String?
    public let totalInputToken: Int64
    public let totalOutputToken: Int64
    public let grandTotalToken: Int64
    public let totalFee: Decimal

    public init(
        modelId: Int64? = nil,
        modelName: String? = nil,
        providerName: String? = nil,
        totalInputToken: Int64 = 0,
        totalOutputToken: Int64 = 0,
        grandTotalToken: Int64 = 0,
        totalFee: Decimal = 0
    ) {
        self.modelId = modelId
        self.modelName = modelName
        self.providerName = providerName
        self.totalInputToken = totalInputToken
        self.totalOutputToken = totalOutputToken
        self.grandTotalToken = grandTotalToken
        self.totalFee = totalFee
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        modelId = try box.decodeIfPresent(Int64.self, forKey: .modelId)
        modelName = try box.decodeIfPresent(String.self, forKey: .modelName)
        providerName = try box.decodeIfPresent(String.self, forKey: .providerName)
        totalInputToken = try box.decodeToken(forKey: .totalInputToken)
        totalOutputToken = try box.decodeToken(forKey: .totalOutputToken)
        grandTotalToken = try box.decodeToken(forKey: .grandTotalToken)
        totalFee = try box.decodeFee(forKey: .totalFee)
    }

    public var title: String? { hxPresented(modelName) }
    public var provider: String? { hxPresented(providerName) }
    public var identity: String? { modelId.map { String($0) } }

    private enum Key: String, CodingKey {
        case modelId, modelName, providerName
        case totalInputToken, totalOutputToken, grandTotalToken, totalFee
    }
}

/// `agentStats[]` — one row per agent (`TokenStatsAggregationResponse.kt:216-234`).
public struct TokenAgentStat: Decodable, Equatable, Sendable, TokenMetricsReport {
    public let agentId: Int64?
    public let agentName: String?
    public let totalInputToken: Int64
    public let totalOutputToken: Int64
    public let grandTotalToken: Int64
    public let totalFee: Decimal

    public init(
        agentId: Int64? = nil,
        agentName: String? = nil,
        totalInputToken: Int64 = 0,
        totalOutputToken: Int64 = 0,
        grandTotalToken: Int64 = 0,
        totalFee: Decimal = 0
    ) {
        self.agentId = agentId
        self.agentName = agentName
        self.totalInputToken = totalInputToken
        self.totalOutputToken = totalOutputToken
        self.grandTotalToken = grandTotalToken
        self.totalFee = totalFee
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        agentId = try box.decodeIfPresent(Int64.self, forKey: .agentId)
        agentName = try box.decodeIfPresent(String.self, forKey: .agentName)
        totalInputToken = try box.decodeToken(forKey: .totalInputToken)
        totalOutputToken = try box.decodeToken(forKey: .totalOutputToken)
        grandTotalToken = try box.decodeToken(forKey: .grandTotalToken)
        totalFee = try box.decodeFee(forKey: .totalFee)
    }

    public var title: String? { hxPresented(agentName) }
    public var identity: String? { agentId.map { String($0) } }

    private enum Key: String, CodingKey {
        case agentId, agentName
        case totalInputToken, totalOutputToken, grandTotalToken, totalFee
    }
}

/// `sessionStats[]` — one row per session (`TokenStatsAggregationResponse.kt:192-210`).
///
/// `sessionId` is a *string* (`t.session_id`, the same `web-…`/`chn-…` address the session lists use), where
/// the model and agent rows key on a numeric id — `:193-194` against `TokenStatsMapper.xml:76`.
public struct TokenSessionStat: Decodable, Equatable, Sendable, TokenMetricsReport {
    public let sessionId: String?
    public let sessionTitle: String?
    public let totalInputToken: Int64
    public let totalOutputToken: Int64
    public let grandTotalToken: Int64
    public let totalFee: Decimal

    public init(
        sessionId: String? = nil,
        sessionTitle: String? = nil,
        totalInputToken: Int64 = 0,
        totalOutputToken: Int64 = 0,
        grandTotalToken: Int64 = 0,
        totalFee: Decimal = 0
    ) {
        self.sessionId = sessionId
        self.sessionTitle = sessionTitle
        self.totalInputToken = totalInputToken
        self.totalOutputToken = totalOutputToken
        self.grandTotalToken = grandTotalToken
        self.totalFee = totalFee
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        sessionId = try box.decodeLooseString(forKey: .sessionId)
        sessionTitle = try box.decodeIfPresent(String.self, forKey: .sessionTitle)
        totalInputToken = try box.decodeToken(forKey: .totalInputToken)
        totalOutputToken = try box.decodeToken(forKey: .totalOutputToken)
        grandTotalToken = try box.decodeToken(forKey: .grandTotalToken)
        totalFee = try box.decodeFee(forKey: .totalFee)
    }

    public var title: String? { hxPresented(sessionTitle) }
    public var identity: String? { hxPresented(sessionId) }

    private enum Key: String, CodingKey {
        case sessionId, sessionTitle
        case totalInputToken, totalOutputToken, grandTotalToken, totalFee
    }
}

/// `timeSeriesData[]` — the row shape all four time-series routes share
/// (`TokenStatsAggregationResponse.kt:240-255`).
///
/// The three dimension routes normalise their own columns into `dimensionId`/`dimensionName`
/// (`mapToDimensionTimeSeriesData` at `:93-132` — `modelId`/`modelName`, `agentId`/`agentName`,
/// `sessionId`/`sessionTitle`), which is what lets four charts be drawn from one type. On the plain
/// `/time-series` route both are absent, because `mapToTimeSeriesData` (`:80-88`) reads keys that SQL never
/// selects: that is the one-series case.
///
/// `timePoint` is a *string*: the bucket is `CAST(… AS DATETIME)` and the service formats it as
/// `yyyy-MM-dd HH:mm:ss` (`:96-104`). It carries no zone, so the screen reads its label by slicing this text
/// rather than by parsing a `Date` and re-rendering it in the handset's own zone.
public struct TokenTimePoint: Decodable, Equatable, Sendable, TokenMetricsReport {
    public let timePoint: String?
    public let dimensionId: String?
    public let dimensionName: String?
    public let totalInputToken: Int64
    public let totalOutputToken: Int64
    public let grandTotalToken: Int64
    public let totalFee: Decimal

    public init(
        timePoint: String? = nil,
        dimensionId: String? = nil,
        dimensionName: String? = nil,
        totalInputToken: Int64 = 0,
        totalOutputToken: Int64 = 0,
        grandTotalToken: Int64 = 0,
        totalFee: Decimal = 0
    ) {
        self.timePoint = timePoint
        self.dimensionId = dimensionId
        self.dimensionName = dimensionName
        self.totalInputToken = totalInputToken
        self.totalOutputToken = totalOutputToken
        self.grandTotalToken = grandTotalToken
        self.totalFee = totalFee
    }

    public init(from decoder: any Decoder) throws {
        let box = try decoder.container(keyedBy: Key.self)
        timePoint = try box.decodeLooseString(forKey: .timePoint)
        dimensionId = try box.decodeLooseString(forKey: .dimensionId)
        dimensionName = try box.decodeIfPresent(String.self, forKey: .dimensionName)
        totalInputToken = try box.decodeToken(forKey: .totalInputToken)
        totalOutputToken = try box.decodeToken(forKey: .totalOutputToken)
        grandTotalToken = try box.decodeToken(forKey: .grandTotalToken)
        totalFee = try box.decodeFee(forKey: .totalFee)
    }

    public var label: String? { hxPresented(timePoint) }
    public var name: String? { hxPresented(dimensionName) }
    public var identity: String? { hxPresented(dimensionId) }

    private enum Key: String, CodingKey {
        case timePoint, dimensionId, dimensionName
        case totalInputToken, totalOutputToken, grandTotalToken, totalFee
    }
}

/// `dimensionId`, `sessionId` and `timePoint` are all declared `String?` and filled by `toString()` on whatever
/// the driver handed back (`TokenStatsAggregationResponse.kt:106-118`), so a numeric-looking column can arrive
/// as a JSON number. A number still reads as its own text instead of failing the whole reply.
private extension KeyedDecodingContainer {
    func decodeLooseString(forKey key: Key) -> String? {
        if let text = try? decodeIfPresent(String.self, forKey: key) { return text }
        if let number = try? decodeIfPresent(Int64.self, forKey: key) { return String(number) }
        return nil
    }

    /// `?: 0L`, on this side.
    func decodeToken(forKey key: Key) -> Int64 {
        if let value = try? decodeIfPresent(Int64.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return Int64(value) }
        return 0
    }

    func decodeFee(forKey key: Key) -> Decimal {
        if let value = try? decodeIfPresent(Decimal.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Int64.self, forKey: key) { return Decimal(value) }
        if let text = try? decodeIfPresent(String.self, forKey: key), let value = Decimal(string: text) {
            return value
        }
        return 0
    }
}

/// The four buckets the time-series routes accept: `granularity` is a plain string parameter on all four
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:77`, `:106`,
/// `:134`, `:162`) and the service dispatches on exactly these four codes, with anything unrecognised falling
/// through to the day bucket (`TokenStatsServiceImpl.kt:81-86`).
///
/// The raw values *are* the query values, so a filter cannot send a code the server would silently downgrade.
/// Only the codes live here — which text a bucket becomes on a chart axis is the screen's own decision.
public enum TokenGranularity: String, CaseIterable, Equatable, Sendable {
    case hour
    case day
    case week
    case month

    /// `defaultValue = "day"` on every one of the four routes.
    public static let serverDefault: TokenGranularity = .day
}

/// A window boundary in the one text these routes parse: `@DateTimeFormat`
/// `yyyy-MM-dd HH:mm:ss` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:44-49`),
/// the same pattern the controller re-formats its own `LocalDateTime` values with (`:35`).
///
/// Written in the handset's calendar, not in UTC: `token_stats.create_time` is a zoneless `DATETIME`, so the
/// console (`harnax-webui/src/pages/token-monitor/index.tsx:147-148`, dayjs on local time) already names its
/// window in wall-clock terms and a UTC shift here would ask for a different twelve hours than the console asks
/// for. `hxServerDateTime(_:)` deliberately refuses to guess a zone for the *read* side; the write side has to
/// pick one to send anything at all, and this is the pick the console made.
///
/// The agent-task log window sends its `startTimeFrom`/`startTimeTo` through this same function for the same
/// reason (`AgentTaskLogMapper.xml:151-156` binds a `LocalDateTime` from the identical pattern).
public func hxWallClockString(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter.string(from: date)
}
