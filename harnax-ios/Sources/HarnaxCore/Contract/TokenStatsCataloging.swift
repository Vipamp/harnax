import Foundation

/// Everything the Token 监控 screen does: the one aggregation read that fills the seven cards and the three
/// donuts, and the four time-series reads that fill the four line charts.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TokenStatsController.kt:27` on
/// `/api/admin/token-stats`, all five routes GET and all five answer the same
/// `ResultVo<TokenStatsAggregationResponse>`.
///
/// Two properties of this surface are worth stating before anyone reaches for a sixth call:
///
/// - **The tenant is never a parameter.** Every route resolves it from the caller's own credentials
///   (`TokenStatsController.kt:20-24`, `:37` and `TenantResolver`), and it can only ever be steered through the
///   `X-Tenant-ID` header the shared transport injects (`APIClient.swift:87-89`). A client that could name a
///   tenant in the URL could name any other one, so nothing here takes one.
/// - **The window has no sixth argument.** `startTime`/`endTime` are optional `yyyy-MM-dd HH:mm:ss` strings and
///   both default server-side to "now minus seven days" (`:43-53`, `:69-85`); `granularity` exists on the four
///   time-series routes only and defaults to `day` (`:77`).
public protocol TokenStatsCataloging: Sendable {
    /// `GET /aggregation` — the read that fills the seven cards *and* the three donuts, because the three
    /// aggregations and the overall row come back in one body (`TokenStatsServiceImpl.kt:30-61`). Its
    /// `timeSeries` half is always empty on this route.
    func tokenAggregation(
        startTime: String?,
        endTime: String?
    ) async -> Result<TokenStatsPayload, APIError>

    /// `GET /time-series` — the overall trend. Answers one point per bucket, with no dimension attached
    /// (`TokenStatsServiceImpl.kt:65-92`), which is the one chart that is not multi-series.
    func tokenTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError>

    /// `GET /time-series/model` — points per bucket *per model* (`:97-111`). The same call shape as the three
    /// siblings, and the reason `dimensionName`/`dimensionId` exist at all: three different SQL column pairs
    /// are normalised into those two fields (`TokenStatsAggregationResponse.kt:106-118`).
    func tokenModelTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError>

    /// `GET /time-series/agent` (`:113-127`).
    func tokenAgentTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError>

    /// `GET /time-series/session` (`:129-146`). Session titles are the dimension name here, and an untitled
    /// session arrives with `dimensionName` absent and only its `sessionId` in `dimensionId`.
    func tokenSessionTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError>
}
