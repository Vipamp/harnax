import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The owner's own memory list: `GET /api/admin/memory`.
///
/// A bare array, not a page — `MemoryController.kt:54` answers `ResultVo<List<MemoryAgentResponse>>` where every
/// other admin list answers the `Page` envelope, so this screen has no page counter, no tail read and no
/// `total` to keep in step with the rows. One refresh replaces the whole set.
///
/// Both memory layers are grouped together before the conversation layers are subtracted
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:94`, `:95`), so an
/// agent that has only ever written its own conversation layer still arrives as a row with an empty body, no
/// dates and a non-zero `pendingSessionLayers`. That row is the difference between「this agent has written
/// nothing worth keeping」and「this agent has no memory」, and the second would be a lie — nothing here filters
/// a row out.
@MainActor
public final class MemoryListViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        /// This account's memory bucket holds no agent. Kept apart from `.failed` on purpose: the console's own
        /// page splits the same two sentences (`harnax-webui/src/pages/memory/index.tsx:61-74`), and a failed
        /// read shown as「none of your agents has memory」tells an owner their memory is gone.
        case empty
        case failed(String)
    }

    /// What a finished purge reports. The two branches are not one sentence with a different number: with zero
    /// objects out of the bucket the memory is still there, and calling that a completed erasure would stop the
    /// owner looking further (`harnax-webui/src/pages/memory/index.tsx:116-126`).
    public enum PurgeReport: Equatable {
        case removed(agentName: String, objects: Int)
        case nothingFound(agentName: String)
    }

    /// The empty sentence, never `state.empty.hint`: the generic one says nothing about whose memory is missing.
    public static let emptyKey = "memory.list.empty"

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var items: [MemoryAgentRow] = []
    /// A purge refused over rows that are still on screen, and a list read that failed while the owner was
    /// reading — the rows stay, since the stack kept them.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var report: PurgeReport?
    /// The row whose purge dialog is open. The dialog is what makes the deletion intentional: one prefix sweep
    /// takes both layers of that agent and nothing restores it (`MemoryStoreGateway.kt:153`, `:162`).
    @Published public private(set) var purgeTarget: MemoryAgentRow?
    /// The agent currently being purged, so a second tap cannot put a second DELETE on the wire.
    @Published public private(set) var purgingName: String?

    private let catalog: any MemoryCataloging
    /// Refresh identity and the one-in-flight rule, as `AgentListViewModel` documents them: an answer to a read
    /// nobody asked for any more may not take the rows with it.
    private var refreshGeneration = 0
    private var isRefreshing = false
    private var rerunRequested = false

    public init(catalog: any MemoryCataloging) {
        self.catalog = catalog
    }

    /// The count line. This is the array's own length because the route has no total to disagree with it.
    public var agentCount: Int { items.count }

    public func refresh() async {
        refreshGeneration += 1
        guard !isRefreshing else {
            rerunRequested = true
            return
        }
        await runRefresh(generation: refreshGeneration)
        while rerunRequested {
            rerunRequested = false
            await runRefresh(generation: refreshGeneration)
        }
    }

    private func runRefresh(generation: Int) async {
        isRefreshing = true
        defer { isRefreshing = false }
        switch await catalog.list() {
        case let .success(rows):
            guard generation == refreshGeneration else { return }
            inlineError = nil
            items = rows
            apply()
        case let .failure(error):
            guard generation == refreshGeneration else { return }
            if error.isMissingAgentMemory {
                inlineError = nil
                items = []
                phase = .empty
                return
            }
            guard ErrorMessage.carriesNews(error) else { return }
            let text = ErrorMessage.text(for: error)
            if items.isEmpty {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    // MARK: - Purge

    public func beginPurge(_ row: MemoryAgentRow) {
        guard purgingName == nil else { return }
        inlineError = nil
        report = nil
        purgeTarget = row
    }

    public func cancelPurge() {
        purgeTarget = nil
    }

    /// Whether the dialog's destructive button may be shown at all: a row with no name has nothing to address,
    /// and `agentId` is the only address these two routes take (`MemoryController.kt:70`, `:89`).
    public func canPurge(_ row: MemoryAgentRow) -> Bool {
        hxPresented(row.agentId) != nil && purgingName == nil
    }

    public func confirmPurge() async {
        guard let row = purgeTarget else { return }
        guard canPurge(row) else { return }
        purgeTarget = nil
        purgingName = row.agentId
        defer { purgingName = nil }
        switch await catalog.delete(agentName: row.agentId) {
        case let .success(result):
            if result.deletedObjects > 0 {
                // The sweep addresses the whole agent prefix, both layers inside it
                // (`MemoryStoreGateway.kt:161-164`), so this row cannot come back on the next read — it leaves
                // without a re-list, which is what keeps the deleted count and the rows in one answer.
                items.removeAll { $0.agentId == row.agentId }
                report = .removed(agentName: row.agentId, objects: result.deletedObjects)
            } else {
                report = .nothingFound(agentName: result.agentId)
            }
            apply()
        case let .failure(error):
            // The row stays put: the count in the server's sentence is the only thing this screen learns about
            // a half-finished delete, and it names how many objects went before the failure
            // (`MemoryStoreGateway.kt:818-822`).
            inlineError = ErrorMessage.text(for: error)
        }
    }

    private func apply() {
        phase = items.isEmpty ? .empty : .content
    }
}
