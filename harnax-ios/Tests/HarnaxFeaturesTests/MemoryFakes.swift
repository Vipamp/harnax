import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The memory screens' stand-in, on the reply-queue discipline the other fakes use: a request nobody queued
/// answers as a decoding failure and still shows up in the call log, so a screen that reads one agent too many
/// fails on a wrong count rather than passing on a silent success.
///
/// The two reads are parkable because both have a mid-flight behaviour to pin: `MemoryListViewModel.refresh`
/// drops an answer to a superseded generation, and `MemoryDetailViewModel.load` must not swap a screen the owner
/// is pulling. Same triad as `PageReadGate` (`ListAppendIdentityTests.swift:850`).
///
/// `gateDeletes` is off by default: a purge's row leaves only once the answer lands, so there is no window for
/// most tests to look at. The one that needs it is the second tap, which has to find `deleteRequests` still
/// holding one entry while the first DELETE is outstanding.
final class FakeMemory: MemoryCataloging, @unchecked Sendable {
    /// The list route takes no parameter at all — not a page, not a keyword (`MemoryController.kt:49-54`) — so
    /// the only thing loggable about a call is that there was one.
    private(set) var listCalls = 0
    var listReplies: [Result<[MemoryAgentRow], APIError>] = []
    let listGate = PageReadGate<Result<[MemoryAgentRow], APIError>>()

    /// The logged value is the **runtime agent name**, which is the whole address these two routes take
    /// (`MemoryController.kt:70`, `:89`). A screen that handed over a database id instead would be caught here.
    private(set) var readRequests: [String] = []
    var readReplies: [Result<MemoryDetail, APIError>] = []
    let readGate = PageReadGate<Result<MemoryDetail, APIError>>()

    private(set) var deleteRequests: [String] = []
    var deleteReplies: [Result<MemoryDeleteResult, APIError>] = []
    var gateDeletes = false

    private var parked: [() -> Void] = []

    func list() async -> Result<[MemoryAgentRow], APIError> {
        listCalls += 1
        return await listGate.absorb(next(from: \.listReplies))
    }

    func read(agentName: String) async -> Result<MemoryDetail, APIError> {
        readRequests.append(agentName)
        return await readGate.absorb(next(from: \.readReplies))
    }

    func delete(agentName: String) async -> Result<MemoryDeleteResult, APIError> {
        deleteRequests.append(agentName)
        guard gateDeletes else { return next(from: \.deleteReplies) }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.next(from: \.deleteReplies)) }
        }
    }

    /// Runs every parked DELETE in the order it went out, each pulling its own next reply.
    func releaseDeletes() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    /// An unqueued request is a test bug, and `.decoding` is what the real facade answers with when the envelope
    /// it got cannot be read — loud, and not a silent success.
    private func next<Payload>(
        from queue: ReferenceWritableKeyPath<FakeMemory, [Result<Payload, APIError>]>
    ) -> Result<Payload, APIError> {
        self[keyPath: queue].isEmpty ? .failure(.decoding) : self[keyPath: queue].removeFirst()
    }
}

// MARK: - fixtures

/// The four memory payloads declare no initialiser (`HarnaxCore/Contract/Memory.swift:13,41,53,66`), so the only
/// way to have one in a test is to answer the body the server answers and let the real decoder build the value —
/// the same trick `EnvVarFakes.swift:100-107` uses, with the keys as the DTOs name them.
private func memoryJSON<Payload: Decodable>(_ fields: [String: Any], as type: Payload.Type) throws -> Payload {
    try JSONDecoder().decode(type, from: try JSONSerialization.data(withJSONObject: fields))
}

extension MemoryAgentRow {
    /// `content` defaults to empty because the DTO ships `""` rather than null when nothing has been curated
    /// (`MemoryAgentResponse.kt:22-23`), and `dates`/`pendingSessionLayers` are non-null columns too (`:28-35`).
    static func stub(
        _ agentId: String,
        content: String = "",
        dates: [String] = [],
        lastModified: String? = nil,
        pending: Int = 0
    ) throws -> MemoryAgentRow {
        try memoryJSON(
            [
                "agentId": agentId,
                "content": content,
                "dates": dates,
                "lastModified": lastModified as Any,
                "pendingSessionLayers": pending,
            ],
            as: MemoryAgentRow.self
        )
    }
}

extension MemoryDailyEntry {
    static func stub(
        _ date: String,
        content: String = "",
        lastModified: String? = nil
    ) throws -> MemoryDailyEntry {
        try memoryJSON(
            ["date": date, "content": content, "lastModified": lastModified as Any],
            as: MemoryDailyEntry.self
        )
    }
}

extension MemoryDetail {
    /// The ledger is passed as values rather than as dictionaries, so a test that needs a day with no body says
    /// so with `MemoryDailyEntry.stub("2026-10-04")` instead of by hand.
    static func stub(
        _ agentId: String = "harnax",
        content: String = "",
        lastModified: String? = nil,
        entries: [MemoryDailyEntry] = []
    ) throws -> MemoryDetail {
        try memoryJSON(
            [
                "agentId": agentId,
                "content": content,
                "lastModified": lastModified as Any,
                "entries": entries.map {
                    ["date": $0.date, "content": $0.content, "lastModified": $0.lastModified as Any] as [String: Any]
                },
            ],
            as: MemoryDetail.self
        )
    }
}

extension MemoryDeleteResult {
    static func stub(_ agentId: String = "harnax", deletedObjects: Int) throws -> MemoryDeleteResult {
        try memoryJSON(["agentId": agentId, "deletedObjects": deletedObjects], as: MemoryDeleteResult.self)
    }
}

/// The two non-happy answers these screens branch on, spelled once.
enum MemoryFailure {
    /// admin's own「没有这台」sentence inside a HTTP 200 envelope (`MemoryController.kt:36-37`, `:73`).
    static var missing: APIError { .business(code: 404, message: APIError.noMemoryMessage) }
    /// A read that did not answer: the same 404 code with a different sentence is a failure, not an empty state.
    static var refused: APIError { .business(code: 500, message: "Memory bucket unreachable") }
}
