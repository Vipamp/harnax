import Foundation
import HarnaxCore

/// The call-metrics half of the admin surface (`ToolMetricsEndpoint.swift`).
///
/// Read-only, and the tenant comes from the caller's own credentials — no route here takes a `tenantId`
/// (`ToolMetricsController.kt:20-25`), so the shared header is the only lever, as in every other admin domain.
extension AdminClient: ToolMetricsReading {
    public func summary(
        window: CallMetricsWindow,
        group: CallMetricsGroup,
        kind: CallMetricsKind?
    ) async -> Result<CallMetricsSummary, APIError> {
        await client.send(CallMetricsSummary.self, ToolMetricsEndpoint.summary(window: window.days, group: group, kind: kind))
    }

    public func timeSeries(
        window: CallMetricsWindow,
        kind: CallMetricsKind?,
        subjectId: Int64?,
        granularity: CallMetricsGranularity
    ) async -> Result<CallMetricsTimeSeries, APIError> {
        await client.send(
            CallMetricsTimeSeries.self,
            ToolMetricsEndpoint.timeSeries(
                window: window.days,
                kind: kind,
                subjectId: subjectId,
                granularity: granularity
            )
        )
    }

    public func invocations(_ query: CallInvocationQuery) async -> Result<Page<CallInvocationRow>, APIError> {
        await client.send(Page<CallInvocationRow>.self, ToolMetricsEndpoint.invocations(query))
    }
}
