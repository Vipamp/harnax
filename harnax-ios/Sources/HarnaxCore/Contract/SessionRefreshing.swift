import Foundation

/// The cross-entity side effect three domains share: after a binding changes, the sessions that already
/// loaded the old configuration have to be rebuilt. CLI, MCP and the agent wizard all end up here.
public protocol SessionRefreshing: Sendable {
    func refreshSessions(_ ids: [String]) async -> Result<[SessionRefreshOutcome], APIError>
}
