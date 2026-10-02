import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The token monitor's stand-in: five queues, one per route, on the discipline the other fakes use — a request
/// nobody queued answers as a decoding failure and still shows up in the call log, so a sixth read reads as a
/// wrong count rather than as a silent pass.
///
/// The lock is not decoration. Only two screens ask their routes *concurrently*: this one
/// (`TokenMonitorViewModel.load` fans out through a task group) and the provider cards' counts
/// (`ModelProviderListViewModel.readStats`, `FakeModelCatalog` locks the same way), so the logs and the queues
/// are written by several tasks at once. Every other fake here answers one request at a time and stays unlocked.
///
/// `gateAggregation` is the dial the superseded-window behaviour needs: the case worth testing is an old
/// window's reply landing after a new window's five requests have gone out, and that only exists if one reply
/// can be held back. The four trend routes stay ungated — the generation guard they share is the same code path,
/// and the aggregation read is the one that also carries the cards.
final class FakeTokenStats: TokenStatsCataloging, @unchecked Sendable {
    typealias Block = TokenMonitorViewModel.Block

    /// The four `/time-series*` routes, in the order the screen draws them.
    static let trendBlocks: [Block] = [.trend, .modelTrend, .agentTrend, .sessionTrend]

    var aggregationReplies: [Result<TokenStatsPayload, APIError>] {
        get { sync { aggregationQueue } }
        set { sync { aggregationQueue = newValue } }
    }

    var trendReplies: [Block: [Result<[TokenTimePoint], APIError>]] {
        get { sync { trendQueues } }
        set { sync { trendQueues = newValue } }
    }

    /// Both window ends as the screen sent them. Flattened to non-optional because this page always names both
    /// ends — `TokenMonitorViewModel.window` returns `String`, not `String?`.
    var aggregationRequests: [TokenRead] { sync { aggregationLog } }

    func trendRequests(for block: Block) -> [TokenRead] { sync { trendLog[block] ?? [] } }

    func requestCount(for block: Block) -> Int {
        block == .aggregate ? aggregationRequests.count : trendRequests(for: block).count
    }

    /// The whole page's traffic, which is what a "this filter costs no request" assertion reads.
    var totalRequestCount: Int {
        sync {
            aggregationLog.count + trendLog.values.reduce(0) { running, entries in running + entries.count }
        }
    }

    var gateAggregation = false

    private let lock = NSLock()
    private var aggregationQueue: [Result<TokenStatsPayload, APIError>] = []
    private var aggregationLog: [TokenRead] = []
    private var trendQueues: [Block: [Result<[TokenTimePoint], APIError>]] = [:]
    private var trendLog: [Block: [TokenRead]] = [:]
    private var parked: [() -> Void] = []

    func tokenAggregation(
        startTime: String?,
        endTime: String?
    ) async -> Result<TokenStatsPayload, APIError> {
        let held = sync {
            aggregationLog.append(TokenRead(start: startTime ?? "", end: endTime ?? "", granularity: nil))
            return gateAggregation
        }
        guard held else { return nextAggregation() }
        return await withCheckedContinuation { continuation in
            sync { parked.append { continuation.resume(returning: self.nextAggregation()) } }
        }
    }

    func tokenTimeSeries(
        startTime: String?, endTime: String?, granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await read(.trend, startTime: startTime, endTime: endTime, granularity: granularity)
    }

    func tokenModelTimeSeries(
        startTime: String?, endTime: String?, granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await read(.modelTrend, startTime: startTime, endTime: endTime, granularity: granularity)
    }

    func tokenAgentTimeSeries(
        startTime: String?, endTime: String?, granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await read(.agentTrend, startTime: startTime, endTime: endTime, granularity: granularity)
    }

    func tokenSessionTimeSeries(
        startTime: String?, endTime: String?, granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await read(.sessionTrend, startTime: startTime, endTime: endTime, granularity: granularity)
    }

    /// Runs every parked aggregation read in the order it went out, each pulling its own next reply.
    func releaseAggregation() {
        let waiting = sync {
            let waiting = parked
            parked = []
            return waiting
        }
        for resume in waiting { resume() }
    }

    private func read(
        _ block: Block, startTime: String?, endTime: String?, granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        sync { trendLog[block, default: []].append(
            TokenRead(start: startTime ?? "", end: endTime ?? "", granularity: granularity)
        ) }
        return sync {
            guard var queue = trendQueues[block], !queue.isEmpty else { return .failure(.decoding) }
            let reply = queue.removeFirst()
            trendQueues[block] = queue
            return reply
        }
    }

    private func nextAggregation() -> Result<TokenStatsPayload, APIError> {
        sync {
            guard !aggregationQueue.isEmpty else { return .failure(.decoding) }
            return aggregationQueue.removeFirst()
        }
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// One logged request. A struct rather than a tuple because a whole log has to be comparable in one assertion,
/// and an array of tuples cannot conform to `Equatable`.
struct TokenRead: Equatable {
    let start: String
    let end: String
    /// `nil` on `/aggregation`, which does not accept the parameter at all (`TokenStatsController.kt:41-49`).
    let granularity: TokenGranularity?

    init(start: String, end: String, granularity: TokenGranularity? = nil) {
        self.start = start
        self.end = end
        self.granularity = granularity
    }
}

/// The wire shapes, built through their public initialisers — every one of them normalises an absent field the
/// same way the decoder does (`TokenStatsSummary.swift:50-57`), so a stub and a real reply are one type.
enum TokenStub {
    /// `overall` plus the three lists. All-zero is the real captured shape of an empty window, and the screen
    /// reads it as *empty* rather than as *failed* (`TokenMonitorViewModel.updatePhase`).
    static func payload(
        input: Int64 = 0,
        output: Int64 = 0,
        total: Int64 = 0,
        fee: Decimal = 0,
        agents: Int64 = 0,
        sessions: Int64 = 0,
        models: Int64 = 0,
        modelRows: [TokenModelStat] = [],
        agentRows: [TokenAgentStat] = [],
        sessionRows: [TokenSessionStat] = []
    ) -> TokenStatsPayload {
        TokenStatsPayload(
            overall: TokenOverallStats(
                totalInputToken: input,
                totalOutputToken: output,
                grandTotalToken: total,
                totalFee: fee,
                agentCount: agents,
                sessionCount: sessions,
                modelCount: models
            ),
            modelStats: modelRows,
            agentStats: agentRows,
            sessionStats: sessionRows
        )
    }

    static func model(
        _ name: String?, id: Int64? = nil, provider: String? = nil,
        input: Int64 = 0, output: Int64 = 0, total: Int64 = 0, fee: Decimal = 0
    ) -> TokenModelStat {
        TokenModelStat(
            modelId: id, modelName: name, providerName: provider,
            totalInputToken: input, totalOutputToken: output,
            grandTotalToken: total, totalFee: fee
        )
    }

    static func agent(
        _ name: String?, id: Int64? = nil, total: Int64 = 0, fee: Decimal = 0
    ) -> TokenAgentStat {
        TokenAgentStat(agentId: id, agentName: name, grandTotalToken: total, totalFee: fee)
    }

    static func session(
        _ title: String?, id: String? = nil, total: Int64 = 0, fee: Decimal = 0
    ) -> TokenSessionStat {
        TokenSessionStat(sessionId: id, sessionTitle: title, grandTotalToken: total, totalFee: fee)
    }

    /// One `timeSeriesData[]` row. `bucket` is the `yyyy-MM-dd HH:mm:ss` text the server writes for its own
    /// bucket, and `nil` is the nullable case that keeps a row off the chart entirely.
    static func point(
        _ bucket: String?,
        name: String? = nil,
        id: String? = nil,
        input: Int64 = 0,
        output: Int64 = 0,
        total: Int64 = 0,
        fee: Decimal = 0
    ) -> TokenTimePoint {
        TokenTimePoint(
            timePoint: bucket, dimensionId: id, dimensionName: name,
            totalInputToken: input, totalOutputToken: output, grandTotalToken: total, totalFee: fee
        )
    }

    /// One row per bucket with no dimension attached — the `/time-series` shape, on the days the axis test
    /// asserts. `fee` rides every bucket so the fee measure has something to plot.
    static func buckets(_ totals: [Int64], fee: Decimal = 0) -> [TokenTimePoint] {
        totals.enumerated().map { offset, total in
            point("2026-09-\(22 + offset) 00:00:00", total: total, fee: fee)
        }
    }

    /// One dimension's rows across the same buckets — the server sends buckets times dimensions in one flat
    /// list (`TokenStatsMapper.xml:234` groups by both), and grouping them is the view model's job.
    static func series(
        named name: String?, id: String? = nil, _ totals: [Int64], fee: Decimal = 0
    ) -> [TokenTimePoint] {
        totals.enumerated().map { offset, total in
            point("2026-09-\(22 + offset) 00:00:00", name: name, id: id, total: total, fee: fee)
        }
    }
}

extension TokenMonitorViewModel {
    /// The value on the card of a given kind, which is how a test reads one number out of seven without
    /// hardcoding their order twice.
    func card(_ kind: TokenKpiCard.Kind) -> TokenKpiCard? {
        cards.first { $0.kind == kind }
    }
}
