import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The five token reads, checked against the routes the controller declares
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:39`, `:65`, `:94`,
/// `:122`, `:150`).
///
/// Three things here have no neighbour to copy from: only the four trend routes take `granularity`, the
/// aggregation route takes the window and nothing else, and *none* of the five takes a tenant — the controller
/// resolves it from the caller's own credentials precisely so that a client cannot name one (`:20-24`, `:37`).
final class TokenStatsEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func absolute(_ endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.absoluteString ?? "<no url>"
    }

    func testAggregationIsAGetOnTheAdminSurfaceWithOnlyItsWindow() {
        XCTAssertEqual(
            absolute(TokenStatsEndpoint.aggregation(startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00")),
            "\(admin)/api/admin/token-stats/aggregation"
                + "?startTime=2026-09-21%2000:00:00&endTime=2026-09-28%2000:00:00"
        )
    }

    /// `granularity` is not a parameter this route has (`TokenStatsController.kt:41-49`); sending one would
    /// imply the seven numbers depend on the bucket, and they do not.
    func testAggregationSendsNoGranularity() {
        let query = TokenStatsEndpoint.aggregation(startTime: "2026-09-21 00:00:00", endTime: nil).query
        XCTAssertEqual(query.map(\.name), ["startTime"])
    }

    /// The plain trend route and the three dimension routes are four paths, not one path with a dimension
    /// parameter — and `/time-series` is a prefix of the other three, so an off-by-one in the suffix silently
    /// asks for the wrong chart.
    func testTheFourTrendRoutesAreFourPaths() {
        let window = (startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00")
        for (route, suffix) in [
            (TokenStatsEndpoint.timeSeries(startTime: window.startTime, endTime: window.endTime, granularity: .hour),
             "/time-series"),
            (TokenStatsEndpoint.modelTimeSeries(startTime: window.startTime, endTime: window.endTime, granularity: .hour),
             "/time-series/model"),
            (TokenStatsEndpoint.agentTimeSeries(startTime: window.startTime, endTime: window.endTime, granularity: .hour),
             "/time-series/agent"),
            (TokenStatsEndpoint.sessionTimeSeries(startTime: window.startTime, endTime: window.endTime, granularity: .hour),
             "/time-series/session"),
        ] {
            XCTAssertTrue(route.path.hasSuffix(suffix), "\(route.path) is not the \(suffix) route")
            XCTAssertEqual(route.method, .get)
        }
    }

    /// The bucket code goes out verbatim: `hour`/`day`/`week`/`month` are the four the service dispatches on,
    /// and anything else it downgrades to day (`TokenStatsServiceImpl.kt:81-86`).
    func testGranularityIsSentAsTheCodeAndAlwaysPresentOnATrend() {
        for (granularity, code) in [
            (TokenGranularity.hour, "hour"), (.day, "day"), (.week, "week"), (.month, "month"),
        ] {
            let query = TokenStatsEndpoint.timeSeries(
                startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00", granularity: granularity
            ).query
            XCTAssertEqual(query.last?.value, code)
        }
    }

    /// Both ends are optional server-side and default to `now-7d … now`, but a blank is not a window: an empty
    /// or whitespace string would be parsed by `@DateTimeFormat` and fail the request as a 400 rather than
    /// falling back to the default.
    func testABlankWindowEndIsLeftOffRatherThanSentEmpty() {
        let endpoint = TokenStatsEndpoint.aggregation(startTime: "   ", endTime: "")
        XCTAssertEqual(endpoint.query, [])

        let half = TokenStatsEndpoint.aggregation(startTime: nil, endTime: "2026-09-28 00:00:00")
        XCTAssertEqual(half.query.map { "\($0.name)=\($0.value ?? "")" }, ["endTime=2026-09-28 00:00:00"])
    }

    /// The whole parameter surface is two optional window ends plus, on a trend, one bucket — the window has no
    /// sixth argument and a test that spells the set out cannot drift with it.
    func testTheOnlyParametersTheseRoutesHave() {
        let aggregation = TokenStatsEndpoint.aggregation(
            startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00"
        ).query.map(\.name)
        XCTAssertEqual(aggregation, ["startTime", "endTime"])

        for endpoint in [
            TokenStatsEndpoint.timeSeries(startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00", granularity: .day),
            TokenStatsEndpoint.modelTimeSeries(startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00", granularity: .day),
            TokenStatsEndpoint.agentTimeSeries(startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00", granularity: .day),
            TokenStatsEndpoint.sessionTimeSeries(startTime: "2026-09-21 00:00:00", endTime: "2026-09-28 00:00:00", granularity: .day),
        ] {
            XCTAssertEqual(endpoint.query.map(\.name), ["startTime", "endTime", "granularity"], endpoint.path)
        }
    }

    /// No route here accepts a tenant, so none may name one: the controller resolves it from the caller's own
    /// credentials precisely so a client cannot ask for someone else's (`TokenStatsController.kt:20-24`, `:37`).
    /// The only tenant lever this app has is the `X-Tenant-ID` header the shared transport attaches.
    func testNoRouteNamesATenant() {
        let endpoints = [
            TokenStatsEndpoint.aggregation(startTime: nil, endTime: nil),
            TokenStatsEndpoint.timeSeries(startTime: nil, endTime: nil, granularity: .day),
            TokenStatsEndpoint.modelTimeSeries(startTime: nil, endTime: nil, granularity: .day),
            TokenStatsEndpoint.agentTimeSeries(startTime: nil, endTime: nil, granularity: .day),
            TokenStatsEndpoint.sessionTimeSeries(startTime: nil, endTime: nil, granularity: .day),
        ]
        for endpoint in endpoints {
            XCTAssertFalse(endpoint.path.lowercased().contains("tenant"), endpoint.path)
            for name in endpoint.query.map(\.name) {
                XCTAssertFalse(name.lowercased().contains("tenant"), "\(endpoint.path) sends \(name)")
            }
        }
    }
}
