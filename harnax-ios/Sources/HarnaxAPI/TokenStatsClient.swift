import Foundation
import HarnaxCore

/// The token-monitoring reads, all five of them one GET each.
///
/// Every route hands back the same `TokenStatsAggregationResponse` shape, so each call decodes
/// `TokenStatsPayload` and then takes the member this route is the source of: `TokenStatsCataloging` promises
/// the four trend routes their rows, which is `payload.timeSeries`, and the aggregation route the whole
/// payload, because the KPI cards and the three pies come out of one reply.
extension AdminClient: TokenStatsCataloging {
    public func tokenAggregation(
        startTime: String?,
        endTime: String?
    ) async -> Result<TokenStatsPayload, APIError> {
        await client.send(TokenStatsPayload.self, TokenStatsEndpoint.aggregation(startTime: startTime, endTime: endTime))
    }

    public func tokenTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await rows(TokenStatsEndpoint.timeSeries(startTime: startTime, endTime: endTime, granularity: granularity))
    }

    public func tokenModelTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await rows(
            TokenStatsEndpoint.modelTimeSeries(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    public func tokenAgentTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await rows(
            TokenStatsEndpoint.agentTimeSeries(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    public func tokenSessionTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        await rows(
            TokenStatsEndpoint.sessionTimeSeries(startTime: startTime, endTime: endTime, granularity: granularity)
        )
    }

    /// A trend route that answered with its `timeSeriesData` key absent is an empty series, not a failed read —
    /// `TokenStatsPayload` already normalises the absent list to `[]`, so there is nothing to unwrap here.
    private func rows(_ endpoint: Endpoint) async -> Result<[TokenTimePoint], APIError> {
        let payload = await client.send(TokenStatsPayload.self, endpoint)
        switch payload {
        case .success(let payload): return .success(payload.timeSeries)
        case .failure(let error): return .failure(error)
        }
    }
}
