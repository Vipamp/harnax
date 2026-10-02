import Foundation
import HarnaxCore

/// The tool surface's stand-in, with the same reply-queue discipline as `FakeAgents`: a request nobody
/// queued answers as a decoding failure and still shows up in the counters, so an extra call reads as a
/// wrong number rather than a crash.
///
/// Lives in its own file because the tool domain is read-only: there are no status or delete queues here.
final class FakeToolCatalog: ToolCataloging, @unchecked Sendable {
    /// The tool list reads `GET /api/admin/tools/builtin`, which takes no parameter at all
    /// (`AgentToolController.kt:67-78`) — so the only wire fact left to record is how often it was asked.
    /// A search that still hit the network would show up here rather than in a filter list.
    private(set) var builtinCalls = 0
    var builtinReplies: [Result<[ToolSummary], APIError>] = []

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<ToolSummary, APIError>] = []

    /// `/available` takes no parameter either, so again only the count is worth keeping.
    private(set) var availableCalls = 0
    var availableReplies: [Result<[ToolSummary], APIError>] = []

    /// The builtin read is parkable: a slow answer has to be able to land after a newer one has already
    /// been painted (`ToolListViewModelTests`).
    let readGate = PageReadGate<Result<[ToolSummary], APIError>>()

    func builtinTools() async -> Result<[ToolSummary], APIError> {
        builtinCalls += 1
        return await readGate.absorb(builtinReplies.isEmpty ? .failure(.decoding) : builtinReplies.removeFirst())
    }

    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> {
        detailRequests.append(id)
        return detailReplies.isEmpty ? .failure(.decoding) : detailReplies.removeFirst()
    }

    func availableTools() async -> Result<[ToolSummary], APIError> {
        availableCalls += 1
        return availableReplies.isEmpty ? .failure(.decoding) : availableReplies.removeFirst()
    }
}

extension ToolSummary {
    /// Field maps rather than initialisers: every column is optional on the wire, and a behaviour test
    /// should only have to spell the ones it is about.
    static func stub(_ fields: [String: Any]) throws -> ToolSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ToolSummary.self, from: data)
    }
}
