import Foundation
import HarnaxCore

/// The five token-statistics routes (`TokenStatsController.kt:27`, prefix `/api/admin/token-stats`).
///
/// All five take the *same* two optional window arguments, and the four trend routes add one
/// `granularity` on top (`TokenStatsController.kt:43-49`, `:71-78`, `:99-107`, `:127-135`, `:155-163`). None of
/// them takes a tenant: `currentTenantId()` resolves it from the caller's own credentials (`:37`, and the
/// comment at `:20-24` spells out why a client must not be able to name one), so the only tenant lever this
/// app has is the `X-Tenant-ID` header `APIClient` already attaches.
///
/// All five also answer the *same* envelope payload type — `ResultVo<TokenStatsAggregationResponse>` — and the
/// caller picks the member it came for: `overall` + the three aggregation lists here, `timeSeries` there.
enum TokenStatsEndpoint {
    static let root = "/api/admin/token-stats"

    /// `startTime`/`endTime` are sent only when the caller has a window to name; left off, the server substitutes
    /// its own `now-7d … now` (`:52-53`). The screen always names both, so the five concurrent requests of one
    /// page load are guaranteed to describe one identical window rather than five slightly different "now"s.
    private static func window(startTime: String?, endTime: String?) -> [URLQueryItem] {
        var items: [URLQueryItem] = []
        if let startTime = hxPresented(startTime) {
            items.append(URLQueryItem(name: "startTime", value: startTime))
        }
        if let endTime = hxPresented(endTime) {
            items.append(URLQueryItem(name: "endTime", value: endTime))
        }
        return items
    }

    /// Window plus `granularity`, which is never omitted: `defaultValue = "day"` only covers a *missing* key,
    /// and an explicit code keeps the four trend charts from disagreeing about their buckets.
    private static func trend(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) -> [URLQueryItem] {
        var items = window(startTime: startTime, endTime: endTime)
        items.append(URLQueryItem(name: "granularity", value: granularity.rawValue))
        return items
    }

    /// `GET /aggregation` — the seven KPI numbers and the three pie sources.
    static func aggregation(startTime: String?, endTime: String?) -> Endpoint {
        Endpoint(.get, path: "\(root)/aggregation", query: window(startTime: startTime, endTime: endTime))
    }

    /// `GET /time-series` — the overall trend, one row per bucket and no dimension.
    static func timeSeries(startTime: String?, endTime: String?, granularity: TokenGranularity) -> Endpoint {
        Endpoint(
            .get,
            path: "\(root)/time-series",
            query: trend(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    /// `GET /time-series/model` (`:94`) — one row per bucket × model.
    static func modelTimeSeries(startTime: String?, endTime: String?, granularity: TokenGranularity) -> Endpoint {
        Endpoint(
            .get,
            path: "\(root)/time-series/model",
            query: trend(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    /// `GET /time-series/agent` (`:122`) — one row per bucket × agent.
    static func agentTimeSeries(startTime: String?, endTime: String?, granularity: TokenGranularity) -> Endpoint {
        Endpoint(
            .get,
            path: "\(root)/time-series/agent",
            query: trend(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    /// `GET /time-series/session` (`:150`) — one row per bucket × session.
    static func sessionTimeSeries(startTime: String?, endTime: String?, granularity: TokenGranularity) -> Endpoint {
        Endpoint(
            .get,
            path: "\(root)/time-series/session",
            query: trend(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }
}
