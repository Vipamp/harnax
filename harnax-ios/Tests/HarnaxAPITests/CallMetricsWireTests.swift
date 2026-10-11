import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The call-metrics reads: three routes, one window shape, and the two filters a screen must not send blank.
///
/// Three shapes are easy to copy wrong and all three fail quietly:
/// 1. the window is `start` / `end` (`ToolMetricsController.kt:42-44`), not the `startTime` / `endTime` pair
///    the token-statistics family uses (`TokenStatsEndpoint.swift:20-29`). The two families are one page in the
///    console and take different names, so a copied call sends a parameter the route ignores and silently reads
///    the server's own default window;
/// 2. `end` is left off, which asks for the current hour (`:44`, `:65`) rather than a guessed one;
/// 3. `groupBy` and `granularity` fall back to their defaults on an unrecognised value instead of failing
///    (`ToolMetricsResponse.kt:65-66`, `:71`), so the response's own field is what a screen labels itself
///    with — not the value it asked for.
final class CallMetricsWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func pairs(_ endpoint: Endpoint) -> [String] {
        endpoint.query.map { "\($0.name)=\($0.value ?? "")" }
    }

    // MARK: - the window

    /// The one parameter the whole family sends, and no upper bound: every screen here asks for「up to now」.
    func testEveryRouteSendsOnlyALowerBound() {
        for endpoint in [
            ToolMetricsEndpoint.summary(window: 7, group: .tool, kind: nil),
            ToolMetricsEndpoint.timeSeries(window: 7, kind: nil, subjectId: nil, granularity: .auto),
            ToolMetricsEndpoint.invocations(CallInvocationQuery(window: .last7Days)),
        ] {
            XCTAssertEqual(endpoint.query.first?.name, "start")
            XCTAssertFalse(endpoint.query.contains { $0.name == "end" }, "\(endpoint.path) would pin an hour")
            XCTAssertFalse(endpoint.query.contains { $0.name == "startTime" }, "that is the token-stats family")
        }
    }

    /// `yyyy-MM-dd` of the first day inside the window, in the device calendar — the form the route accepts
    /// (`:41`), and the window the console's picker means by the same words.
    func testTheWindowOpensTheRequestedNumberOfWholeDaysBack() throws {
        for days in [1, 7, 30, 90] {
            let value = try XCTUnwrap(
                ToolMetricsEndpoint.summary(window: days, group: .tool, kind: nil).query.first?.value
            )
            let parsed = try XCTUnwrap(Self.day(value), "\(days) -> \(value) is not a device date")
            let back = Calendar.current.dateComponents([.day], from: parsed, to: Date()).day
            XCTAssertEqual(back, days - 1, "\(days)-day window opened at \(value)")
        }
    }

    /// A window of zero or fewer days would open in the future, so the smallest legal answer is today — clamped
    /// once, at the type, rather than in each of the three routes.
    func testAnEmptyWindowIsOneDayNotZero() {
        XCTAssertEqual(CallMetricsWindow(days: 0).days, 1)
        XCTAssertEqual(
            ToolMetricsEndpoint.summary(window: -3, group: .tool, kind: nil).query.first?.value,
            CallMetricsWindow(days: 1).start
        )
    }

    // MARK: - the three reads

    func testTheSummaryNamesItsDimensionAndLeavesOutAnUnpickedOrigin() {
        let grouped = ToolMetricsEndpoint.summary(window: 30, group: .session, kind: nil)
        XCTAssertEqual(grouped.path, "/api/admin/tool-metrics/summary")
        XCTAssertEqual(grouped.query.map(\.name), ["start", "groupBy"])
        XCTAssertEqual(grouped.query.last?.value, "session", "the dimension is the one thing this table is about")

        let filtered = ToolMetricsEndpoint.summary(window: 30, group: .mcp, kind: .mcp)
        XCTAssertEqual(Array(pairs(filtered).dropFirst()), ["kind=mcp", "groupBy=mcp"])
    }

    func testTheTrendSendsAutoAndNarrowsOnlyWhenASubjectIsNamed() {
        let open = ToolMetricsEndpoint.timeSeries(window: 90, kind: nil, subjectId: nil, granularity: .auto)
        XCTAssertEqual(open.path, "/api/admin/tool-metrics/time-series")
        XCTAssertEqual(open.query.map(\.name), ["start", "granularity"])
        XCTAssertEqual(open.query.last?.value, "auto", "the client does not re-derive the server's fold rule")

        let narrowed = ToolMetricsEndpoint.timeSeries(window: 90, kind: .cli, subjectId: 12, granularity: .day)
        XCTAssertEqual(narrowed.query.map(\.name), ["start", "kind", "subjectId", "granularity"])
        XCTAssertEqual(narrowed.query[2].value, "12")
    }

    /// Every filter the detail route binds (`:84-106`), in the order the controller declares them, and only the
    /// ones the caller actually picked.
    func testTheDetailSendsEveryPickedFilterInDeclaredOrder() {
        let bare = ToolMetricsEndpoint.invocations(CallInvocationQuery(window: .last7Days, num: 1, size: 20))
        XCTAssertEqual(
            pairs(bare),
            ["start=\(CallMetricsWindow(days: 7).start)", "pageNum=1", "pageSize=20"]
        )

        let picked = ToolMetricsEndpoint.invocations(
            CallInvocationQuery(
                window: .last30Days, kind: .mcp, toolName: "web_search", mcpId: 7, cliId: nil,
                agentId: 3, sessionId: "sess-7", outcome: .denied, num: 2, size: 50
            )
        )
        XCTAssertEqual(
            picked.query.map(\.name),
            ["start", "kind", "toolName", "mcpId", "agentId", "sessionId", "outcome", "pageNum", "pageSize"]
        )
        XCTAssertEqual(
            Array(picked.query.dropFirst()).map { $0.value ?? "" },
            ["mcp", "web_search", "7", "3", "sess-7", "DENIED", "2", "50"]
        )
        XCTAssertEqual(picked.path, "/api/admin/tool-metrics/invocations")
        XCTAssertTrue(
            picked.url(baseURL: admin)!.absoluteString.hasPrefix("\(admin)/api/admin/tool-metrics/invocations?start=")
        )
    }

    func testABlankTextFilterIsLeftOffWhileAZeroIdIsStillAnId() {
        let blank = ToolMetricsEndpoint.invocations(
            CallInvocationQuery(window: .last7Days, toolName: "   ", mcpId: 0, sessionId: "")
        )
        XCTAssertFalse(blank.query.contains { $0.name == "toolName" }, "a padded blank must not become a filter")
        XCTAssertFalse(blank.query.contains { $0.name == "sessionId" })
        XCTAssertTrue(
            blank.query.contains { $0.name == "mcpId" && $0.value == "0" },
            "0 is a row id like any other; only absence means「no filter」"
        )
    }

    /// 1..200 rows per page server-side (`:105-106`); clamping here means a screen cannot ask for a page the
    /// route refuses and then render the refusal as an empty table.
    func testThePageSizeStaysInsideWhatTheRouteAccepts() {
        for (asked, sent) in [(0, 1), (-4, 1), (500, 200), (20, 20)] {
            let size = ToolMetricsEndpoint.invocations(CallInvocationQuery(window: .last7Days, size: asked))
                .query.first { $0.name == "pageSize" }?.value
            XCTAssertEqual(size, String(sent), "asked \(asked)")
        }
    }

    /// The append path a list screen uses keeps every filter and moves only the page number.
    func testANextPageKeepsEveryFilterTheFirstPageHad() {
        let first = CallInvocationQuery(
            window: .last90Days, kind: .shell, toolName: "run", agentId: 4, outcome: .error, num: 1, size: 40
        )
        let next = first.page(3)
        XCTAssertEqual(
            pairs(ToolMetricsEndpoint.invocations(next)),
            pairs(ToolMetricsEndpoint.invocations(first)).map { $0 == "pageNum=1" ? "pageNum=3" : $0 }
        )
        XCTAssertEqual(next.window, first.window)
        XCTAssertEqual(next.size, 40)
    }

    // MARK: - the vocabulary the response uses back

    /// The dimension split is not cosmetic: three of the five read the hourly aggregate and two read the detail
    /// table, and only the detail-backed ones are bounded by the retention window
    /// (`harnax-webui/src/pages/call-metrics/dimensions.ts:1-19`).
    func testTheFiveDimensionsSplitByWhichStoreTheyRead() {
        XCTAssertEqual(CallMetricsGroup.allCases.map(\.rawValue), ["tool", "mcp", "cli", "agent", "session"])
        XCTAssertEqual(CallMetricsGroup.allCases.filter(\.readsAggregate).map(\.rawValue), ["tool", "mcp", "cli"])
    }

    /// An unknown dimension answers as `tool`, the same fallback the server applies (`ToolMetricsResponse.kt:65-66`).
    func testAnUnknownDimensionFromTheWireBecomesTheDefault() {
        XCTAssertEqual(CallMetricsGroup(wire: "tenant"), .tool)
        XCTAssertEqual(CallMetricsGroup(wire: "").id, "tool")
        XCTAssertFalse(CallMetricsGroup(wire: "session").readsAggregate)
    }

    /// The origin buckets are the server's five words (`:45-46`) and the terminal states the four the detail
    /// table stores (`:101-102`).
    func testTheFilterWordsAreTheServersOwn() {
        XCTAssertEqual(CallMetricsKind.allCases.map(\.rawValue), ["builtin", "mcp", "cli", "shell", "framework"])
        XCTAssertEqual(
            CallInvocationOutcome.allCases.map(\.rawValue), ["SUCCESS", "ERROR", "DENIED", "INTERRUPTED"]
        )
        XCTAssertNil(CallInvocationOutcome(wire: "TIMEOUT"), "a state the table does not store is not a filter")
        XCTAssertEqual(CallMetricsGranularity.auto.rawValue, "auto")
    }

    func testEveryRouteIsAnAuthenticatedAdminReadWithNoTenantParameter() {
        for endpoint in [
            ToolMetricsEndpoint.summary(window: 7, group: .tool, kind: nil),
            ToolMetricsEndpoint.timeSeries(window: 7, kind: nil, subjectId: nil, granularity: .auto),
            ToolMetricsEndpoint.invocations(CallInvocationQuery()),
        ] {
            if case .admin = endpoint.base {} else { XCTFail("\(endpoint.path) would go to the router host") }
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path)")
            XCTAssertTrue(endpoint.sendsTenantHeader, "\(endpoint.path) is tenant-scoped (:20-25)")
            XCTAssertFalse(endpoint.path.lowercased().contains("tenant"))
            XCTAssertFalse(endpoint.query.contains { $0.name.lowercased().contains("tenant") })
        }
    }

    // MARK: - what the answers decode into

    /// A row without a `subjectId` has nothing to drill into: a builtin tool, an agent or a session dimension
    /// all have a name but no row to filter the detail page by (`ToolMetricsResponse.kt:30`).
    func testOnlyRowsWithAnIdCanBeDrilledInto() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"from":"2026-10-03 00:00:00","to":"2026-10-10 20:00:00","groupBy":"tool","totalCalls":120,\
        "totalSuccesses":100,"successRate":0.8333,"failingCalls":20,"p95Operator":">","p95Ms":4200,"rows":[\
        {"kind":"builtin","subjectKey":"read_file","toolName":"read_file","subjectName":"read_file",\
        "parentName":"","calls":70,"successes":60,"errors":6,"denials":3,"interruptions":1,"successRate":0.857,\
        "avgDurationMs":120,"p95Operator":"<=","p95Ms":900,"lastSeenAt":"2026-10-10 19:00:00"},\
        {"kind":"mcp","subjectKey":"7","subjectId":7,"toolName":"","subjectName":"browser-use",\
        "parentName":"browser-use","calls":50,"successes":40,"errors":8,"denials":2,"interruptions":0,\
        "successRate":0.8,"avgDurationMs":800,"p95Operator":"<=","p95Ms":3000}]},\
        "timestamp":1,"isSuccess":true}
        """
        let summary = try JSONDecoder().decode(
            Envelope<CallMetricsSummary>.self, from: Data(envelope.utf8)
        ).data!

        XCTAssertEqual(summary.group, .tool)
        XCTAssertEqual(summary.p95Operator, ">", "the open-ended bucket is part of the number")
        XCTAssertEqual(summary.totalCalls, 120)

        let builtin = summary.rows[0]
        XCTAssertNil(builtin.subjectId)
        XCTAssertFalse(builtin.isDrillable)
        XCTAssertEqual(builtin.failingCalls, 10, "errors, denials and interruptions together (:70-71)")

        let server = summary.rows[1]
        XCTAssertEqual(server.subjectId, 7)
        XCTAssertTrue(server.isDrillable)
        XCTAssertEqual(server.parentName, "browser-use")
        XCTAssertNil(server.lastSeenAt, "admin drops a null key rather than sending one")
    }

    /// The trend comes back **zero-filled** (`ToolMetricsController.kt:56-60`), so a quiet bucket is a row with no
    /// calls rather than a hole the screen has to invent; and the bucket folded server-side is read back off the
    /// answer (`ToolMetricsResponse.kt:101`).
    func testTheTrendCarriesItsOwnBucketSize() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"from":"2026-10-08 00:00:00","to":"2026-10-10 00:00:00","granularity":"day","points":[\
        {"timePoint":"2026-10-08 00:00:00","kind":"mcp","dimensionId":7,"dimensionName":"web","calls":0,\
        "successes":0,"errors":0,"denials":0,"interruptions":0,"avgDurationMs":0},\
        {"timePoint":"2026-10-09 00:00:00","kind":"mcp","dimensionId":7,"dimensionName":"web","calls":4,\
        "successes":3,"errors":1,"denials":0,"interruptions":0,"avgDurationMs":310}]},\
        "timestamp":1,"isSuccess":true}
        """
        let series = try JSONDecoder().decode(
            Envelope<CallMetricsTimeSeries>.self, from: Data(envelope.utf8)
        ).data!
        XCTAssertEqual(series.granularity, "day")
        XCTAssertEqual(series.points.count, 2)
        XCTAssertEqual(series.points[0].calls, 0, "a silent day is a zero bucket, not a missing one")
        XCTAssertEqual(series.points[1].calls, 4)
        XCTAssertEqual(series.points[1].avgDurationMs, 310)
    }

    /// The detail row's two payload columns are absent when capture is switched off deployment-side
    /// (`:118-121`), so a screen that always renders an arguments pane shows an empty one on a healthy install.
    func testADetailRowKeepsTheSessionItHappenedInAndLosesThePayloadsWhenCaptureIsOff() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"pageNum":1,"pageSize":20,"total":2,"records":[\
        {"id":91,"kind":"mcp","toolName":"web","sessionId":"sess-7","sessionName":"周报整理","agentId":3,\
        "mcpId":7,"outcome":"DENIED","errorMessage":"用户拒绝","durationMs":12,"ts":"2026-10-10 19:02:11"},\
        {"id":92,"kind":"builtin","toolName":"read_file","sessionId":"sess-gone","outcome":"SUCCESS",\
        "durationMs":3,"ts":"2026-10-10 19:03:02"}]},"timestamp":1,"isSuccess":true}
        """
        let page = try JSONDecoder().decode(
            Envelope<Page<CallInvocationRow>>.self, from: Data(envelope.utf8)
        ).data!

        XCTAssertEqual(page.total, 2)
        let first = page.records[0]
        XCTAssertEqual(first.sessionLabel, "周报整理")
        XCTAssertEqual(first.outcome, CallInvocationOutcome.denied.rawValue)
        XCTAssertNil(first.argsJson, "capture off leaves the key out entirely")
        XCTAssertNil(first.cliId)

        let second = page.records[1]
        XCTAssertEqual(
            second.sessionLabel, "sess-gone",
            "a call whose session row is gone is named by its id (:111-112)"
        )
        XCTAssertNil(second.sessionName)
    }

    // MARK: - parsing helper

    /// `yyyy-MM-dd` in the device calendar, which is what the route's own accepted form means (`:41`).
    private static func day(_ text: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: text)
    }
}
