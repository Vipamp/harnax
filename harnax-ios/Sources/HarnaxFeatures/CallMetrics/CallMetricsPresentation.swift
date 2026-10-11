import Foundation
import HarnaxCore
import HarnaxKit

/// The numbers and the labels the call-metrics screens draw, in one place.
///
/// The same reason `TokenMonitorPresentation` exists: the view model compares these strings to decide what a
/// block says, and a test has to assert `"98.7%"` or `"<=30000 ms"` without a rendering pass. The formats are the
/// console's own (`harnax-webui/src/pages/call-metrics/index.tsx:39,164,276-282`), including the one decimal on
/// a rate and the bare ` ms` suffix on a duration.
public enum CallMetricsFigures {
    /// A call count, printed in full.
    ///
    /// Not abbreviated the way the token screen's numbers are: these counts are per subject inside one window
    /// and a `1.20K` would hide the exact number an operator compares between two rows. The console prints them
    /// raw too (`index.tsx:152-173`, `:246-262`).
    public static func count(_ value: Int64) -> String { String(value) }

    /// Successes over calls as a percentage, one decimal (`index.tsx:39` — `(rate * 100).toFixed(1)`).
    ///
    /// The fraction arrives already divided by the server (`ToolMetricsServiceImpl.rateOf`), so nothing here
    /// divides again, and a zero-call window answers `0.0` rather than an error.
    public static func rate(_ ratio: Double) -> String {
        decimal(ratio * 100, fractionDigits: 1) + "%"
    }

    /// A duration in milliseconds, as the console's two duration columns print it (`index.tsx:276,320`).
    public static func duration(_ ms: Int64) -> String { "\(ms) ms" }

    /// The window's or the row's P95, with the server's comparison operator in front of it (`index.tsx:164,282`).
    ///
    /// The operator is the whole point of the string. A percentile on the aggregate dimensions is a bucket
    /// boundary counted from six duration columns, so `<=2000 ms` means "the 95th percentile lands in the ≤2s
    /// bucket" and `>30000 ms` means "it landed in the open-ended one" — neither is a measured millisecond, and
    /// dropping the sign would turn a boundary into a number the server never observed
    /// (`ToolMetricsServiceImpl.kt:20-32`, `p95`).
    ///
    /// An operator that did not arrive, or arrived as something other than the two the service writes, is shown
    /// exactly as it came: a sign this screen cannot read is still a claim the row made about its own percentile.
    public static func p95(sign: String, ms: Int64) -> String {
        guard !sign.isEmpty else { return duration(ms) }
        return "\(sign)\(ms) ms"
    }

    /// Fixed decimals, a `.` separator, no grouping — the deliberate non-localisation `TokenFigures.decimal`
    /// explains, and the reason a figure test cannot pass on one machine and fail on another.
    private static func decimal(_ value: Double, fractionDigits: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.usesGroupingSeparator = false
        formatter.minimumFractionDigits = fractionDigits
        formatter.maximumFractionDigits = fractionDigits
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: value)) ?? String(format: "%.\(fractionDigits)f", value)
    }
}

/// The two instant columns of this page family, cut out of the server's text rather than re-read from a date.
///
/// `lastSeenAt` and `ts` are `yyyy-MM-dd HH:mm:ss` strings the service formatted
/// (`ToolMetricsResponse.kt:13-16`, `ToolMetrics.swift:10-12`), and they carry no time zone. The console parses
/// them with dayjs and re-formats them, which shifts them into the browser's zone; here the text is sliced, so a
/// handset in another zone shows the stamp the server wrote instead of a moved one — the same choice
/// `TokenAxisLabel` documents.
public enum CallMetricsStamp {
    /// The row's last call. Precision follows the table the row was read from, which is the console's rule and
    /// the reason `readsAggregate` has to be consulted first (`harnax-webui/src/pages/call-metrics/lastSeen.ts:9-13`):
    /// the three aggregate dimensions answer at the hour, whose seconds never carry information, and the two
    /// detail dimensions answer at the call's own instant.
    public static func lastSeen(_ raw: String?, group: CallMetricsGroup) -> String {
        cut(raw, to: group.readsAggregate ? 16 : 19)
    }

    /// A recorded call's instant, seconds always (`index.tsx:295-302`).
    public static func instant(_ raw: String?) -> String { cut(raw, to: 19) }

    /// The console prints `-` for a stamp it has no answer for (`lastSeen.ts:10`); a card byline says so in words
    /// instead, because a dash under a heading that reads as a fact looks like the data was lost.
    public static let missingKey = "monitor.calls.stamp.none"

    private static func cut(_ raw: String?, to length: Int) -> String {
        guard let text = hxPresented(raw) else { return hx(missingKey) }
        let chars = Array(text)
        guard isClock(chars) else { return text }
        return String(chars[0..<min(length, chars.count)])
    }

    /// `yyyy-MM-dd HH:mm…` — enough of the shape to slice safely, and nothing more.
    private static func isClock(_ chars: [Character]) -> Bool {
        chars.count >= 16
            && chars[4] == "-" && chars[7] == "-"
            && chars[10] == " " && chars[13] == ":"
    }
}

/// Labels for the four wire enums, and the rule that governs all of them: a token the app cannot name is shown
/// as the token, never as the case the parser settled on.
///
/// The console does the same by construction — `intl.formatMessage({ id: ..., defaultMessage: outcome })`
/// (`index.tsx:311`) prints the raw code when no copy exists for it. Here the fallback has to be written, and a
/// silently-mapped unknown would put a false word on a row the server described differently.
public enum CallMetricsLabels {
    /// The dimension a row was grouped by. All five cases have copy, so this is the one label with no fallback.
    public static func group(_ group: CallMetricsGroup) -> String { hx(group.titleKey) }

    /// One arm of the origin filter. `nil` is "no origin predicate at all", which is an arm the server answers
    /// rather than a control that does nothing (`ToolMetricsController.kt:45`), and `state.filter.all` is the
    /// app's existing word for it.
    public static func kindArm(_ kind: CallMetricsKind?) -> String {
        guard let kind else { return hx("state.filter.all") }
        return hx(kind.titleKey)
    }

    /// The origin bucket. `nil` for the empty string the agent and session rows carry
    /// (`ToolMetricsServiceImpl.kt:204-212` leaves the column unset), so a row can drop the chip rather than
    /// label a cross-origin row with one origin.
    public static func kind(_ raw: String) -> String? {
        guard let text = hxPresented(raw) else { return nil }
        guard let kind = CallMetricsKind(rawValue: text) else { return text }
        return hx(kind.titleKey)
    }

    /// The terminal state of a recorded call, keeping an unrecognised code's own text
    /// (`ToolMetrics.swift:181-191` gives no fallback for `CallInvocationOutcome`, and none is invented here).
    public static func outcome(_ raw: String) -> String {
        guard let outcome = CallInvocationOutcome(wire: raw) else { return raw }
        return hx(outcome.titleKey)
    }

    /// The four tag colours the console gives the outcomes (`index.tsx:32-37`), with a neutral slot for a fifth.
    public static func outcomeTone(_ raw: String) -> PaletteSlot {
        switch CallInvocationOutcome(wire: raw) {
        case .success: return .success
        case .error: return .danger
        case .denied: return .warning
        case .interrupted: return .textSecondary
        case nil: return .textTertiary
        }
    }

    /// The bucket size the trend answered with. `auto` never reaches this — the server folds it and reports the
    /// bucket it used (`ToolMetricsController.kt:70`, `ToolMetrics.swift:193-196`) — but an unknown token keeps
    /// its text like every other label here.
    public static func granularity(_ raw: String) -> String? {
        guard let text = hxPresented(raw) else { return nil }
        guard let granularity = CallMetricsGranularity(rawValue: text) else { return text }
        return hx(granularity.titleKey)
    }
}

/// The window arms, straight off the contract's own presets.
///
/// The console offers a free range picker plus four presets (`index.tsx:21,333-338`); on a handset the same two
/// query parameters are named by the presets only, and the default is the console's own default span
/// (`DEFAULT_RANGE_DAYS = 30`, `index.tsx:22`). `CallMetricsWindow.start` is what turns an arm into a query
/// parameter, so the arm list is the contract's rather than a second copy of the day counts.
public enum CallMetricsWindows {
    public static let presets: [CallMetricsWindow] = CallMetricsWindow.presets

    public static let `default`: CallMetricsWindow = .last30Days

    /// The preset's own words when the day count is one, and a plain span count when a caller built a window by
    /// hand. `monitor.range.*` is the token screen's existing arm copy, reused rather than re-worded.
    public static func titleKey(for days: Int) -> String {
        switch days {
        case 7: return "monitor.range.sevenDays"
        case 30: return "monitor.range.thirtyDays"
        case 90: return "monitor.range.ninetyDays"
        default: return "monitor.calls.window.days"
        }
    }

    public static func title(_ window: CallMetricsWindow) -> String {
        let key = titleKey(for: window.days)
        return key == "monitor.calls.window.days" ? hx(key, window.days) : hx(key)
    }
}

/// One bucket of the trend, folded the way the console folds it.
///
/// The read answers one point per (bucket × kind × subject × tool), so a day with thirty tools is thirty points
/// sharing one `timePoint`, and summing them per bucket is the only way the three lines mean what their labels
/// say (`index.tsx:176-194`). Nothing else is done to them: the reply is zero-filled by the service
/// (`ToolMetricsServiceImpl.kt:110-127`), so a quiet bucket is already a row of zeroes and this side neither
/// adds a bucket nor drops one.
public struct CallMetricsBucket: Identifiable, Equatable, Sendable {
    /// The server's bucket text, which is also the plot's x value.
    public let timePoint: String
    /// The axis label cut out of `timePoint` by the answered granularity.
    public let label: String
    public let calls: Int64
    public let successes: Int64
    /// Errors, denials and interruptions together — the same three columns the console adds up
    /// (`index.tsx:186`, `ToolMetrics.swift:66-67`).
    public let failures: Int64

    public var id: String { timePoint }

    public init(timePoint: String, label: String, calls: Int64, successes: Int64, failures: Int64) {
        self.timePoint = timePoint
        self.label = label
        self.calls = calls
        self.successes = successes
        self.failures = failures
    }
}

/// The three lines, in the console's order and its colours (`index.tsx:15-17,190-192,214`).
public enum CallMetricsSeries: String, CaseIterable, Identifiable, Sendable {
    case calls
    case successes
    case failures

    public var id: String { rawValue }
    public var titleKey: String { "monitor.calls.series.\(rawValue)" }

    public var slot: PaletteSlot {
        switch self {
        case .calls: return .brand
        case .successes: return .success
        case .failures: return .danger
        }
    }

    public func value(of bucket: CallMetricsBucket) -> Double {
        switch self {
        case .calls: return Double(bucket.calls)
        case .successes: return Double(bucket.successes)
        case .failures: return Double(bucket.failures)
        }
    }

    public func formatted(of bucket: CallMetricsBucket) -> String {
        switch self {
        case .calls: return CallMetricsFigures.count(bucket.calls)
        case .successes: return CallMetricsFigures.count(bucket.successes)
        case .failures: return CallMetricsFigures.count(bucket.failures)
        }
    }
}

/// Folds a reply's points into one bucket per `timePoint`.
public enum CallMetricsTrend {
    /// Order is the reply's: the first time it meets a `timePoint` fixes that bucket's place, and later points
    /// of the same bucket join it there. A sort here would re-order a zero-filled series the server already
    /// laid out forwards (`ToolMetricsServiceImpl.kt:111`), and a gap filled here would invent a bucket the
    /// window did not have.
    ///
    /// The only rows dropped are the ones with no bucket text at all: a point that says no time cannot be
    /// placed on a time axis, and plotting it at position zero would draw a spike no server made — the same
    /// judgement as `TokenMonitorViewModel.plottable(_:)`.
    public static func buckets(of series: CallMetricsTimeSeries) -> [CallMetricsBucket] {
        var order: [String] = []
        var folded: [String: (calls: Int64, successes: Int64, failures: Int64)] = [:]
        for point in series.points {
            guard let timePoint = hxPresented(point.timePoint) else { continue }
            if folded[timePoint] == nil {
                order.append(timePoint)
                folded[timePoint] = (0, 0, 0)
            }
            guard var held = folded[timePoint] else { continue }
            held.calls += point.calls
            held.successes += point.successes
            held.failures += point.errors + point.denials + point.interruptions
            folded[timePoint] = held
        }
        return order.compactMap { timePoint in
            guard let held = folded[timePoint] else { return nil }
            return CallMetricsBucket(
                timePoint: timePoint,
                label: CallMetricsAxis.make(timePoint, granularity: series.granularity),
                calls: held.calls,
                successes: held.successes,
                failures: held.failures
            )
        }
    }
}

/// The X-axis text of the trend, cut from the server's own bucket string.
///
/// The console re-formats the parsed stamp by the granularity the trend answered with: hour buckets all share
/// a date, so a date-only label would print the same day across a two-day window
/// (`index.tsx:204-206`). Same rule here, same reason — and an unrecognised granularity token leaves the stamp
/// whole rather than guessing a precision for it.
public enum CallMetricsAxis {
    public static func make(_ raw: String, granularity: String) -> String {
        let chars = Array(raw)
        guard chars.count >= 13, chars[4] == "-", chars[7] == "-", chars[10] == " ", chars[13] == ":" else {
            return raw
        }
        let monthDay = String(chars[5..<10])
        guard granularity != CallMetricsGranularity.hour.rawValue else {
            return monthDay + " " + String(chars[11..<13]) + ":00"
        }
        return monthDay
    }
}

/// One of the four numbers above the table.
///
/// The set is the console's KPI row (`index.tsx:150-174`) and each one is read from the summary's own window
/// fields, never computed out of the rows below: the service takes them from one ungrouped aggregate
/// (`ToolMetricsServiceImpl.kt:55-66`), so an average over the visible rows would be a different number than
/// the card's label claims.
public struct CallMetricsSummaryCard: Identifiable, Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case calls, rate, p95, failures

        public var titleKey: String { "monitor.calls.card.\(rawValue)" }

        /// The tooltip the console hangs on three of the four cards (`index.tsx:160,166,172`).
        public var hintKey: String? {
            switch self {
            case .calls: return nil
            case .rate: return "monitor.calls.card.rate.hint"
            case .p95: return "monitor.calls.card.p95.hint"
            case .failures: return "monitor.calls.card.failures.hint"
            }
        }

        public var slot: PaletteSlot {
            switch self {
            case .calls: return .brand
            case .rate: return .success
            case .p95: return .teal
            case .failures: return .danger
            }
        }
    }

    public let kind: Kind
    /// Pre-formatted: a plain count, an `x.x%`, the operator plus `N ms`.
    public let value: String

    public var id: Kind { kind }

    public init(kind: Kind, value: String) {
        self.kind = kind
        self.value = value
    }
}

/// A pill on a row: the origin, the owner, or the failure count.
///
/// Resolved text rather than a key, because two of the three are the server's own words — a name cannot become
/// copy, and an origin the app does not know has to read as the token it is (`CallMetricsLabels`).
public struct CallMetricsChip: Identifiable, Equatable, Sendable {
    public let id: Int
    public let label: String
    public let tone: PaletteSlot
    /// Copy that says what the pill means, for a reader who cannot see the colour.
    public let voiceKey: String

    public init(index: Int, label: String, tone: PaletteSlot, voiceKey: String) {
        id = index
        self.label = label
        self.tone = tone
        self.voiceKey = voiceKey
    }
}

/// One subject of the grouped table, as a card.
///
/// The console's six columns (`index.tsx:218-291`) fold the way `SkillDraftRowCard` folds six: name as the
/// title, the owner as a byline, the origin and the failures as chips, and the four numbers as a grid.
public struct CallMetricsSubjectRow: Identifiable, Equatable, Sendable {
    /// The contract's own row identity — kind plus subject key (`ToolMetrics.swift:69`).
    public let id: String
    /// `subjectName`, which the server already fell back to `subjectKey` for (`ToolMetrics.swift:48-51`), and
    /// only a row with neither reads as unnamed.
    public let title: String
    /// The owning server or package, on the tool dimension only — which is where it tells two same-named tools
    /// of two servers apart (`index.tsx:234-241`, `ToolInvocationStatsMapper.xml:133-140`).
    public let parent: String?
    public let chips: [CallMetricsChip]
    public let calls: String
    public let successes: String
    /// The row's own failures, as the count it is read as. Never a share this side divided out: the row's
    /// `errors + denials + interruptions` is the answer (`ToolMetrics.swift:70`).
    public let failures: String
    public let rate: String
    public let average: String
    public let p95: String
    public let lastSeen: String
    /// Whether anything in this row failed. A row with failures reads differently — the count and the dot, not a
    /// colour alone (`ToolMetrics.swift:56-58`).
    public let hasFailures: Bool
    /// Whether the row can be opened. The gate is the contract's own `isDrillable`
    /// (`ToolMetrics.swift:71-73`): a row whose detail predicate would be empty offers no push.
    public let isDrillable: Bool

    public init(row: CallMetricsRow, group: CallMetricsGroup) {
        id = row.id
        title = hxPresented(row.subjectName) ?? hxPresented(row.subjectKey) ?? hx("monitor.row.unnamed")
        parent = group == .tool ? hxPresented(row.parentName) : nil
        var chips: [CallMetricsChip] = []
        if let kind = CallMetricsLabels.kind(row.kind) {
            chips.append(
                CallMetricsChip(index: chips.count, label: kind, tone: .indigo, voiceKey: "monitor.calls.column.kind")
            )
        }
        if row.failingCalls > 0 {
            chips.append(
                CallMetricsChip(
                    index: chips.count,
                    label: CallMetricsFigures.count(row.failingCalls),
                    tone: .danger,
                    voiceKey: "monitor.calls.column.failures"
                )
            )
        }
        self.chips = chips
        calls = CallMetricsFigures.count(row.calls)
        successes = CallMetricsFigures.count(row.successes)
        failures = CallMetricsFigures.count(row.failingCalls)
        rate = CallMetricsFigures.rate(row.successRate)
        average = CallMetricsFigures.duration(row.avgDurationMs)
        p95 = CallMetricsFigures.p95(sign: row.p95Operator, ms: row.p95Ms)
        lastSeen = CallMetricsStamp.lastSeen(row.lastSeenAt, group: group)
        hasFailures = row.failingCalls > 0
        isDrillable = row.isDrillable
    }
}

/// One recorded call, as a row of the drill-down.
///
/// The console's four detail columns plus the expandable payload (`index.tsx:293-331,534-559`). The payload
/// pane is where the honest difference from the console sits: it hides the expander when all three optional
/// columns came back empty (`index.tsx:535`), which leaves "capture is off on this deployment"
/// (`ToolMetrics.swift:126-127`) indistinguishable from "this call carried nothing". The row says which.
public struct CallInvocationDisplayRow: Identifiable, Equatable, Sendable {
    public let id: Int64
    /// The row's own instant, falling back to the start time the log keeps beside it
    /// (`ToolMetrics.swift:131-132`).
    public let stamp: String
    public let outcome: String
    public let outcomeTone: PaletteSlot
    public let duration: String
    /// `sessionLabel`, which already encodes the fallback rule — the session's registered title, or the id when
    /// no session row answers it (`ToolMetrics.swift:134-139`, `index.tsx:327-329`).
    public let session: String
    public let tool: String?
    public let origin: String?
    public let agent: String?
    public let failure: String?
    public let arguments: String?
    public let result: String?
    /// Whether either payload column came back. False is not "empty payload": it is capture being off.
    public let carriesPayload: Bool

    public init(row: CallInvocationRow) {
        id = row.id
        stamp = CallMetricsStamp.instant(row.ts ?? row.startTime)
        outcome = CallMetricsLabels.outcome(row.outcome)
        outcomeTone = CallMetricsLabels.outcomeTone(row.outcome)
        duration = CallMetricsFigures.duration(row.durationMs)
        session = row.sessionLabel
        tool = hxPresented(row.toolName)
        origin = CallMetricsLabels.kind(row.kind)
        agent = row.agentId.map(String.init)
        failure = hxPresented(row.errorMessage)
        arguments = hxPresented(row.argsJson)
        result = hxPresented(row.resultExcerpt)
        carriesPayload = arguments != nil || result != nil
    }

    /// The two payload columns that came back, in the console's order (`index.tsx:543-556`).
    ///
    /// Empty is not "this call carried nothing": it is capture being off deployment-side
    /// (`ToolMetrics.swift:126-127`), which is what `carriesPayload` says out loud rather than leaving as two
    /// blank fields. The failure reason is not part of this list — it is a fact about the outcome, printed above
    /// it whatever the capture setting turned out to be.
    public var payloadLines: [CallInvocationPayloadLine] {
        var lines: [CallInvocationPayloadLine] = []
        if let arguments {
            lines.append(
                CallInvocationPayloadLine(
                    index: lines.count, key: "monitor.calls.detail.args", value: arguments, isCode: true
                )
            )
        }
        if let result {
            lines.append(
                CallInvocationPayloadLine(
                    index: lines.count, key: "monitor.calls.detail.result", value: result, isCode: false
                )
            )
        }
        return lines
    }
}

/// One line of an opened call record — the console's three `Descriptions` items
/// (`index.tsx:538-556`), with the copy key rather than the resolved title so the view can voice it.
public struct CallInvocationPayloadLine: Identifiable, Equatable, Sendable {
    public let id: Int
    public let titleKey: String
    public let value: String
    /// Arguments are JSON, and the console keeps them monospaced and copyable (`index.tsx:544-548`).
    public let isCode: Bool

    public init(index: Int, key: String, value: String, isCode: Bool) {
        id = index
        titleKey = key
        self.value = value
        self.isCode = isCode
    }
}
