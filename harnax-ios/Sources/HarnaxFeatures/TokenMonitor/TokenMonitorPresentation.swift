import Foundation
import HarnaxCore
import HarnaxKit

/// E5 — the numbers and the labels the token screen draws, in one place.
///
/// Two reasons this exists apart from the view: the view model has to *compare* these strings to decide
/// whether a block changed, and a test has to assert them ("1.50K", not "1500") without a rendering pass. The
/// formats are the console's own (`harnax-webui/src/pages/token-monitor/index.tsx:158-174`), down to the two
/// decimals on a fee and the two decimals on a K abbreviation.
public enum TokenFigures {
    /// Token counts abbreviated the way the console abbreviates them: millions above a million, thousands
    /// above a thousand, plain below it, and `0` for zero (`index.tsx:158-169`).
    ///
    /// Every one of the seven KPI numbers goes through this, including the three counts — the console does the
    /// same thing, since its cards fall back to `formatToken` unless they are the fee card (`:932`).
    public static func token(_ value: Int64) -> String {
        guard value != 0 else { return "0" }
        if value >= 1_000_000 { return decimal(Double(value) / 1_000_000, fractionDigits: 2) + "M" }
        if value >= 1_000 { return decimal(Double(value) / 1_000, fractionDigits: 2) + "K" }
        return String(value)
    }

    /// Fee as `¥` plus exactly two decimals (`index.tsx:172-174`).
    ///
    /// The column is `decimal(10,0)`, so a sub-yuan fee is stored and summed as a whole number — the two
    /// decimals are usually `.00`, and that is what the console shows too. A zero fee is still a fee the server
    /// answered, so it renders as `¥0.00` rather than disappearing.
    public static func fee(_ value: Decimal) -> String {
        "¥" + decimal(NSDecimalNumber(decimal: value).doubleValue, fractionDigits: 2)
    }

    /// A share of a total, one decimal (`index.tsx:192-204`, `:275-281`).
    ///
    /// The denominator rule is the console's: a zero total is not divided into. Its cards show `'0'` and its
    /// legend divides by `|| 1` — both readings land on "no share to report", which this answers as `0.0%`.
    public static func share(_ part: Double, of total: Double) -> String {
        guard total != 0 else { return "0.0%" }
        return percent(part / total)
    }

    private static func percent(_ ratio: Double) -> String {
        decimal(ratio * 100, fractionDigits: 1) + "%"
    }

    /// Fixed decimals, a `.` separator, no grouping.
    ///
    /// Deliberately not the device locale: `toFixed()` on the console is always `.` and never grouped, so
    /// `¥12345.00` reads identically here and there, and a figure test does not pass or fail depending on the
    /// machine that ran it.
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

/// The X-axis text of a time-series chart.
///
/// `timePoint` is a *string* the server formatted from its own bucket (`TokenStatsAggregationResponse.kt:96-104`
/// with `yyyy-MM-dd HH:mm:ss`), carrying no time zone. The console parses it with dayjs and re-formats it by
/// granularity (`harnax-webui/src/pages/token-monitor/index.tsx:619-629`); this side gets the same visible
/// result by slicing the text it was given, which also means a handset in another zone shows the same label the
/// server wrote instead of a shifted one.
///
/// Anything that does not look like `yyyy-MM-dd HH:mm:ss` is shown exactly as it arrived: a label the screen
/// cannot format is still a fact about the row, and dropping it would silently merge two buckets into one blank.
public enum TokenAxisLabel {
    public static func make(_ raw: String?, granularity: TokenGranularity) -> String {
        guard let text = hxPresented(raw) else { return "" }
        let chars = Array(text)
        guard chars.count >= 10, chars[4] == "-", chars[7] == "-" else { return text }
        let monthDay = String(chars[5..<10])
        switch granularity {
        case .month:
            return String(chars[0..<7])
        case .hour:
            guard chars.count >= 16, chars[10] == " ", chars[13] == ":" else { return monthDay }
            return monthDay + " " + String(chars[11..<16])
        case .day, .week:
            return monthDay
        }
    }
}

/// The third filter, and the only one that never reaches the network: the console keeps it in component state
/// and reads either `grandTotalToken` or `totalFee` out of the same rows
/// (`harnax-ios/specs/03-system-domain.md:179`, `harnax-webui/src/pages/token-monitor/index.tsx:247`).
public enum TokenMeasure: String, CaseIterable, Identifiable, Equatable, Sendable {
    case token
    case fee

    public var id: String { rawValue }

    public var titleKey: String {
        switch self {
        case .token: return "monitor.measure.token"
        case .fee: return "monitor.measure.fee"
        }
    }
}

extension TokenMeasure {
    /// The column this measure plots. Every row type on this screen carries both, because they all share the
    /// `tokenMetrics` fragment, so one pair of accessors serves the three pies and the four lines
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:247` for a pie, `:687` for a dimension line).
    public func value(of row: any TokenMetricsReport) -> Double {
        switch self {
        case .token: return Double(row.grandTotalToken)
        case .fee: return (row.totalFee as NSDecimalNumber).doubleValue
        }
    }

    public func formatted(of row: any TokenMetricsReport) -> String {
        switch self {
        case .token: return TokenFigures.token(row.grandTotalToken)
        case .fee: return TokenFigures.fee(row.totalFee)
        }
    }

    /// The denominator the legend's percentage divides by — again both from `overall`, and again the console's
    /// own pairing (`index.tsx:275-281`).
    public func total(of overall: TokenOverallStats) -> Double {
        switch self {
        case .token: return Double(overall.grandTotalToken)
        case .fee: return (overall.totalFee as NSDecimalNumber).doubleValue
        }
    }

    /// One row's percentage of the window.
    ///
    /// The denominator is `overall`, never the sum of the rows on screen: the console divides the same way
    /// (`index.tsx:275-281`), and it is what lets the session pie — the one this screen truncates to ten rows —
    /// still show a real share instead of a share of the ten it happens to draw.
    public func share(of row: any TokenMetricsReport, against overall: TokenOverallStats) -> String {
        TokenFigures.share(value(of: row), of: total(of: overall))
    }
}

/// The window each load asks for.
///
/// The console offers a free date-range picker (`index.tsx:815-828`); on a handset the same two query
/// parameters are named by presets, and the default is the server's own default — `now-7d … now`
/// (`TokenStatsController.kt:52-53`). The arithmetic is a day offset and nothing else, so the window the five
/// concurrent requests share is one subtraction from a single instant rather than five "now"s.
public enum TokenWindowPreset: String, CaseIterable, Identifiable, Equatable, Sendable {
    case sevenDays
    case thirtyDays
    case ninetyDays

    public var id: String { rawValue }

    public var titleKey: String {
        switch self {
        case .sevenDays: return "monitor.range.sevenDays"
        case .thirtyDays: return "monitor.range.thirtyDays"
        case .ninetyDays: return "monitor.range.ninetyDays"
        }
    }

    /// `minusDays(7)` on the server, `subtract(7, 'day')` on the console — the same span.
    public var dayOffset: Int {
        switch self {
        case .sevenDays: return 7
        case .thirtyDays: return 30
        case .ninetyDays: return 90
        }
    }

    public static let `default`: TokenWindowPreset = .sevenDays
}

/// The eight colours a chart may use, in order, cycling the way the console's twelve-hue array cycles
/// (`harnax-webui/src/pages/token-monitor/index.tsx:237-241`).
///
/// Slots rather than hexes, because the app has no literal colours outside the palette table. The order is
/// fixed, and so is the row's place in it: a slice keeps its hue across a refresh instead of being re-sorted by
/// whatever value it landed on.
public enum TokenChartPalette {
    public static let slots: [PaletteSlot] = [
        .brand, .warning, .success, .indigo, .purple, .teal, .danger, .textSecondary,
    ]

    public static func slot(forIndex index: Int) -> PaletteSlot {
        slots[index % slots.count]
    }
}

/// One KPI card. `kind` carries the wording, the glyph and the tone; the view model only knows the number.
public struct TokenKpiCard: Identifiable, Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case fee, input, output, total, agents, sessions, models
    }

    public let kind: Kind
    /// Pre-formatted: `¥x.xx` for the fee card, K/M for the six others.
    public let value: String
    /// The `x.x%` badge the console puts on the input and output cards only
    /// (`index.tsx:192-194`, `:202-204`) — of the total consumed, how much went in.
    public let share: String?

    public var id: Kind { kind }

    public init(kind: Kind, value: String, share: String?) {
        self.kind = kind
        self.value = value
        self.share = share
    }
}

/// A row's own four figures, in the same texts the seven cards use.
///
/// The ring draws one column at a time, so these are what a tap on a wedge has to add: the split behind the
/// angle under the finger. Measure-independent by design — the fee column reads `¥x.xx` and the three token
/// columns read K/M whether or not the fee measure is currently plotted.
public struct TokenRowBreakdown: Equatable, Sendable {
    public let input: String
    public let output: String
    public let total: String
    public let fee: String

    public init(row: any TokenMetricsReport) {
        input = TokenFigures.token(row.totalInputToken)
        output = TokenFigures.token(row.totalOutputToken)
        total = TokenFigures.token(row.grandTotalToken)
        fee = TokenFigures.fee(row.totalFee)
    }
}

/// One pie slice, plus the legend line the console prints next to it.
public struct TokenShareSlice: Identifiable, Equatable, Sendable {
    /// Position in the reply — the server orders by `grandTotalToken DESC`, so this is the rank the legend and
    /// the hue cycle both follow.
    public let index: Int
    /// Index-qualified so two rows with the same — or with no — name stay two slices.
    public let id: String
    /// The server's own text; `nil` means the name column came back null or blank, and the view says so.
    public let title: String?
    /// Model rows carry their provider here, agent and session rows carry their id
    /// (`index.tsx:275-281` appends the provider to the legend line when it exists).
    public let detail: String?
    public let value: String
    /// The same number unformatted — a slice's angle has to be a value, not a label.
    public let plot: Double
    public let share: String
    public let slot: PaletteSlot
    public let breakdown: TokenRowBreakdown

    public init(
        index: Int,
        key: String,
        title: String?,
        detail: String?,
        value: String,
        plot: Double,
        share: String,
        slot: PaletteSlot,
        breakdown: TokenRowBreakdown
    ) {
        self.index = index
        id = "\(index)-\(key)"
        self.title = title
        self.detail = detail
        self.value = value
        self.plot = plot
        self.share = share
        self.slot = slot
        self.breakdown = breakdown
    }
}

/// One point on a line.
public struct TokenTrendPoint: Identifiable, Equatable, Sendable {
    public let id: String
    /// The raw server text, which sorts as text exactly as it sorts as time — `yyyy-MM-dd HH:mm:ss` was built
    /// to be read that way — so it is the plottable x value.
    public let timePoint: String
    /// The axis label derived from `timePoint` by granularity.
    public let label: String
    public let value: Double
    /// The same value in the measure's own text, for the tooltip and the legend.
    public let formatted: String

    public init(id: String, timePoint: String, label: String, value: Double, formatted: String) {
        self.id = id
        self.timePoint = timePoint
        self.label = label
        self.value = value
        self.formatted = formatted
    }
}

/// One series on a line chart.
public struct TokenTrendSeries: Identifiable, Equatable, Sendable {
    public let id: String
    /// A dimension series is named by the server's `dimensionName`; `nil` is the anonymous bucket.
    public let name: String?
    /// The overall chart's fixed series are the only ones with a key; a dimension series has server text.
    public let nameKey: String?
    public let slot: PaletteSlot
    public let points: [TokenTrendPoint]

    public init(id: String, name: String?, nameKey: String?, slot: PaletteSlot, points: [TokenTrendPoint]) {
        self.id = id
        self.name = name
        self.nameKey = nameKey
        self.slot = slot
        self.points = points
    }
}

/// One line's number at one bucket.
///
/// Two readers of it: the panel a tap opens under a line chart, and the numeric list a block switches to in
/// place of the chart. The console gets the first from a hover tooltip
/// (`harnax-webui/src/pages/token-monitor/index.tsx:642-650`); a finger has no hover, so the readout is a row
/// per line under the chart instead.
public struct TokenBucketReading: Identifiable, Equatable, Sendable {
    public let id: String
    public let slot: PaletteSlot
    /// Already resolved, so the row reads the same as the chip that names the line.
    public let name: String
    public let value: String

    public init(id: String, slot: PaletteSlot, name: String, value: String) {
        self.id = id
        self.slot = slot
        self.name = name
        self.value = value
    }
}

/// One bucket of a trend block read as numbers instead of as a line: the axis text the chart shows for that
/// bucket, and one cell per series the chart currently draws.
///
/// This is the numeric alternative the accessibility rule asks for (`DESIGN.md` §10) — a reader who cannot
/// see the line still gets every number it was drawn from, in the chart's own bucket order. Built by
/// `TokenMonitorViewModel.trendListRows(in:)`, which is why the cells are already-resolved strings rather than
/// raw values a view would have to format twice over.
public struct TokenTrendListRow: Identifiable, Equatable, Sendable {
    /// The raw server bucket text: unique among a block's rows, and the reason the rows sort the way the axis
    /// does.
    public let id: String
    /// The point's own `label`, sliced by granularity where the server text was first read. Never recomputed
    /// from a device date.
    public let label: String
    /// The visible series' numbers, in the order the legend and the hues run. A series with no point in this
    /// bucket is absent from it exactly as it is absent from the line.
    public let values: [TokenBucketReading]

    public init(id: String, label: String, values: [TokenBucketReading]) {
        self.id = id
        self.label = label
        self.values = values
    }
}

/// The overall trend's series: three under the token measure, one under the fee measure, in this order
/// (`harnax-webui/src/pages/token-monitor/index.tsx:603-611`).
///
/// These are the only chart names the app owns — every other series on this screen is named by a row the server
/// sent — which is why they are keys rather than text.
public enum TokenOverallSeries: String, CaseIterable, Sendable {
    case input
    case output
    case total
    case fee

    public var titleKey: String {
        switch self {
        case .input: return "monitor.series.input"
        case .output: return "monitor.series.output"
        case .total: return "monitor.series.total"
        case .fee: return "monitor.series.fee"
        }
    }

    public static func visible(in measure: TokenMeasure) -> [TokenOverallSeries] {
        switch measure {
        case .token: return [.input, .output, .total]
        case .fee: return [.fee]
        }
    }

    /// The column this series plots.
    public func value(of row: TokenTimePoint) -> Double {
        switch self {
        case .input: return Double(row.totalInputToken)
        case .output: return Double(row.totalOutputToken)
        case .total: return Double(row.grandTotalToken)
        case .fee: return (row.totalFee as NSDecimalNumber).doubleValue
        }
    }

    /// The text this series' value takes on, in its own column rather than the row's total.
    public func formatted(_ row: TokenTimePoint) -> String {
        switch self {
        case .input: return TokenFigures.token(row.totalInputToken)
        case .output: return TokenFigures.token(row.totalOutputToken)
        case .total: return TokenFigures.token(row.grandTotalToken)
        case .fee: return TokenFigures.fee(row.totalFee)
        }
    }
}

/// The two ways a dimension row can be named, in the order the screen uses them.
///
/// `dimensionName` is the series name on the console (`index.tsx:686`, which falls back to `'未知'`), and the id
/// is this side's fallback before "unnamed": the SQL reaches the name through a `LEFT JOIN`
/// (`harnax-entity/src/main/resources/mapper/TokenStatsMapper.xml:74-76`), so an unnamed model is usually still
/// a *distinguishable* model, and lumping all nameless rows into one anonymous series would invent a sum the
/// server never made.
func tokenDimensionKey(name: String?, identity: String?) -> String {
    if let name = hxPresented(name) { return name }
    if let identity = hxPresented(identity) { return identity }
    return ""
}

extension TokenShareSlice {
    /// A model, an agent and a session are all named by the server, and that name is not copy: it is the same
    /// string in both languages. A row whose name column came back through the `LEFT JOIN` empty says so,
    /// because "no name" is a fact about the row rather than a reason to show a blank line.
    public var legendText: String { title ?? hx("monitor.row.unnamed") }
}

extension TokenTrendSeries {
    /// The overall chart's three (or one) series are the app's own words; a dimension chart labels its lines
    /// with whatever the server called that dimension.
    public var legendText: String {
        if let nameKey { return hx(nameKey) }
        return name ?? hx("monitor.row.unnamed")
    }
}

/// The three filters, seen as what the view needs from them: a list of cases and one label per case.
///
/// One protocol rather than three hand-written segment controls, because the arithmetic — case to index and back
/// — is identical for all three and only the enum differs.
public protocol TokenFilterOption: CaseIterable, Equatable {
    var titleKey: String { get }
}

extension TokenWindowPreset: TokenFilterOption {}
extension TokenGranularity: TokenFilterOption {}
extension TokenMeasure: TokenFilterOption {}

extension TokenGranularity {
    /// The bucket names. These are the four codes the route accepts (`TokenStatsController.kt:76-77`), so a
    /// label here can never promise a bucket the server would answer differently.
    public var titleKey: String {
        switch self {
        case .hour: return "monitor.bucket.hour"
        case .day: return "monitor.bucket.day"
        case .week: return "monitor.bucket.week"
        case .month: return "monitor.bucket.month"
        }
    }
}

extension TokenMeasure {
    /// A plotted number back in text. The chart's y axis is numeric and the measure decides how a number on it
    /// reads — the same pair of formatters the console puts on its own axis
    /// (`harnax-webui/src/pages/token-monitor/index.tsx:633`).
    public func formatted(value: Double) -> String {
        switch self {
        case .token: return TokenFigures.token(Int64(value))
        case .fee: return TokenFigures.fee(Decimal(value))
        }
    }
}
