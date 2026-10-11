import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// One agent's long-term memory: `GET /api/admin/memory/{agentId}`, addressed by the agent's **runtime name**.
///
/// The name is the whole address this route has — `MemoryController.kt:70` binds `@PathVariable(name = "agentId")`
/// to「Agent name as the runtime keys it」(`MemoryAgentResponse.kt:19-20`) — so this type takes no id and no row
/// number. A name the store cannot place is refused by the stack before it reads anything
/// (`MemoryServiceImpl.kt:57-60` throws `BizException("Invalid agent id")`, a `400` in the envelope), and that
/// answer stays an error rather than being folded into「no memory yet」.
///
/// Everything on screen comes out of the long-term layer: the read subtracts the conversation objects first and
/// groups afterwards (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/MemoryStoreGateway.kt:122`),
/// so neither the curated text nor the daily entries can be a conversation's own draft, and no route exists that
/// would fetch one (`prod_doc/ios-memory-domain.zh-CN.md` §2).
@MainActor
public final class MemoryDetailViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        /// The read answered. A detail with neither curated text nor entries still lands here — the bucket has
        /// objects for this agent, and each pane says what it has not got.
        case content
        /// admin's own 404 sentence for this agent: the long-term layer has nothing yet. An answer, not a
        /// failure, so it gets its own phase and its own copy
        /// (`MemoryController.kt:73`, and `APIError.isMissingAgentMemory` in `HarnaxCore/Contract/Memory.swift`).
        case empty
        case failed(String)
    }

    /// The sentence for「this agent has not written its long-term layer yet」— deliberately not
    /// `MemoryListViewModel.emptyKey`, which is about every agent the caller owns.
    public static let emptyKey = "memory.detail.empty"

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var detail: MemoryDetail?

    public let agentName: String
    private let catalog: any MemoryCataloging
    /// A pull and the entry read are two reads of one screen; the answer that arrives second to a request that
    /// is no longer wanted takes nothing off the screen.
    private var loadGeneration = 0

    public init(agentName: String, catalog: any MemoryCataloging) {
        self.agentName = agentName
        self.catalog = catalog
    }

    public func load() async {
        loadGeneration += 1
        let generation = loadGeneration
        // Only the first read swaps the screen for a spinner: a pull has to leave what the owner is reading up,
        // since swapping it mid-gesture is how the read cancels itself.
        if detail == nil { phase = .loading }
        switch await catalog.read(agentName: agentName) {
        case let .success(detail):
            guard generation == loadGeneration else { return }
            self.detail = detail
            phase = .content
        case let .failure(error):
            guard generation == loadGeneration else { return }
            if error.isMissingAgentMemory {
                phase = .empty
                return
            }
            guard ErrorMessage.carriesNews(error) else { return }
            phase = .failed(ErrorMessage.text(for: error))
        }
    }

    /// The curated `MEMORY.md`, or `nil` when the agent has none yet: the field ships as `""` rather than null
    /// (`MemoryDetailResponse.kt:16-17`), and「nothing curated」is a sentence the pane has to be able to say.
    public var curated: String? { hxPresented(detail?.content) }

    /// The append-only ledger, oldest first as the server sorts it (`MemoryStoreGateway.kt:137`).
    public var entries: [MemoryDailyEntry] { detail?.entries ?? [] }

    public var updatedDay: String? { MemoryCopy.day(detail?.lastModified) }
}
