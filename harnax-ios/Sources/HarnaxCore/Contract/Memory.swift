import Foundation

/// One agent's long-term memory as its owner's list shows it: `GET /api/admin/memory`.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryAgentResponse.kt:18-35`.
///
/// `agentId` is the **runtime agent name**, not the database row id (`MemoryController.kt:70`), and it is the
/// only address the two by-agent routes take. `id` therefore carries the name rather than a number.
///
/// The list groups both memory layers together (`MemoryStoreGateway.kt:94`) and subtracts afterwards (`:95`),
/// so an agent that has only ever written its conversation layer still gets a row: `content` and `dates` come
/// from the long-term layer and can both be empty while `pendingSessionLayers` is not zero.
public struct MemoryAgentRow: Decodable, Identifiable, Equatable, Sendable {
    public let agentId: String
    /// The curated `MEMORY.md` text the runtime loads into the prompt. Empty when there is none yet
    /// (`MemoryAgentResponse.kt:22-23`), which is why「no conclusion layer」is a blank here and not a nil.
    public let content: String
    /// When the curated layer was last written, ISO-8601 UTC (`:25-26`).
    public let lastModified: String?
    /// Daily ledger dates present, oldest first (`:28-29`). Listed, not read: a page of agents has no
    /// business fetching a whole memory history per row.
    public let dates: [String]
    /// How many of this agent's conversations still hold a layer that has not been merged into the curated
    /// text (`:30-35`, counted at `MemoryStoreGateway.kt:108` from `:468`).
    ///
    /// This is a count of *conversations that owe a merge*, not of candidates waiting for a decision: a
    /// rejection leaves the layer alone (`MemoryDraftServiceImpl.kt:314`), so the same material is proposed
    /// again next window. Copy has to say「not merged yet」rather than「n candidates for you to approve」.
    public let pendingSessionLayers: Int

    public var id: String { agentId }
    public var hasCuratedText: Bool { !content.isEmpty }
    public var dateCount: Int { dates.count }
}

/// One agent's whole long-term memory: `GET /api/admin/memory/{agentId}`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDetailResponse.kt:12-24`).
///
/// Both layers here come from the long-term store only — the detail read subtracts first, then fetches
/// (`MemoryStoreGateway.kt:122`). No route addresses a conversation's own layer, so a client never shows it.
public struct MemoryDetail: Decodable, Equatable, Sendable {
    public let agentId: String
    public let content: String
    public let lastModified: String?
    /// The append-only daily ledger, oldest first (`MemoryDetailResponse.kt:22-23`).
    public let entries: [MemoryDailyEntry]

    public var hasCuratedText: Bool { !content.isEmpty }
}

/// One day of that ledger (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDailyEntryResponse.kt:13-22`).
/// The object key stays server-side — `date` is the identity a client shows and sorts by.
public struct MemoryDailyEntry: Decodable, Identifiable, Equatable, Sendable {
    public let date: String
    public let content: String
    public let lastModified: String?

    public var id: String { date }
}

/// What a deletion took out of the store: `DELETE /api/admin/memory/{agentId}`
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/MemoryDeleteResponse.kt:13-19`).
///
/// `deletedObjects` counts the objects that left, across both files and ledgers (`:17-18`) — one and four are
/// different answers, so the screen reports the number instead of just saying「deleted」.
public struct MemoryDeleteResult: Decodable, Equatable, Sendable {
    public let agentId: String
    public let deletedObjects: Int
}

public extension APIError {
    /// admin's own sentence for「this caller has no memory for that agent」
    /// (`MemoryController.kt:73` — `ResultVo.error(404, "No memory for this agent")`).
    static let noMemoryMessage = "No memory for this agent"

    /// Whether the read answered「there is nothing here yet」rather than「the read failed」.
    ///
    /// The two have to stay apart on screen: the controller puts a `404` code in a successful HTTP response
    /// (`MemoryController.kt:36-37`), and a listing that found nothing is a success with an empty list, so
    /// an empty state is what this must render — an error banner would tell the owner their memory is lost
    /// when it has simply not been written (`specs/08-memory.md` judgment 2).
    var isMissingAgentMemory: Bool {
        if case let .business(code, message) = self {
            return code == 404 && message == Self.noMemoryMessage
        }
        return false
    }
}
