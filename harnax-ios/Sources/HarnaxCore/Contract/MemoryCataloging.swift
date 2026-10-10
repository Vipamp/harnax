import Foundation

/// The owner's own long-term agent memory: list it, read one agent, have one agent's removed.
///
/// Three routes, no user and no tenant parameter — both come off the session
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/MemoryController.kt:30-37`), so the
/// shared tenant header is the only lever a client has and no request can name somebody else's namespace.
///
/// Kept out of `AgentCataloging` because the rows here are keyed by runtime name and one agent with no memory
/// answers nothing at all, so the two lists do not have the same membership.
public protocol MemoryCataloging: Sendable {
    /// Every agent of the caller that has objects in their memory bucket.
    ///
    /// A bare array, not the `Page` envelope every other admin list returns
    /// (`MemoryController.kt:54`), so the app's paging state machine has no scope on this read.
    func list() async -> Result<[MemoryAgentRow], APIError>

    /// The curated text plus the daily ledger of one agent, addressed by **runtime name**.
    ///
    /// A caller with no memory for that agent gets `404` inside the envelope — check
    /// `APIError.isMissingAgentMemory` and render an empty state, not a failure.
    func read(agentName: String) async -> Result<MemoryDetail, APIError>

    /// Removes the curated text and every daily ledger of that agent for this caller, and nothing outside
    /// their own prefix (`MemoryController.kt:83-99`; the sweep lists raw object names under the prefix at
    /// `MemoryStoreGateway.kt:153`, `:162`, which is why both layers leave together).
    func delete(agentName: String) async -> Result<MemoryDeleteResult, APIError>
}
