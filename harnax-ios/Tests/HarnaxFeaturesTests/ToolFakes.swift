import Foundation
import HarnaxCore

/// The tool surface's stand-in, with the same reply-queue discipline as `FakeAgents`: a request nobody
/// queued answers as a decoding failure and still shows up in `requests`, so an extra call reads as a wrong
/// number rather than a crash.
///
/// Lives in its own file because the tool domain is read-only: there are no status or delete queues here.
final class FakeToolCatalog: ToolCataloging, @unchecked Sendable {
    private(set) var requests: [(num: Int, size: Int)] = []
    /// The keyword's own label is what this records, so a test can tell `/tools/page?keyword=` from
    /// `/agents/page?name=`.
    private(set) var filters: [(keyword: String?, status: Int?)] = []
    var replies: [Result<Page<ToolSummary>, APIError>] = []

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<ToolSummary, APIError>] = []

    func toolPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ToolSummary>, APIError> {
        requests.append((num: num, size: size))
        filters.append((keyword: keyword, status: status))
        return replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
    }

    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> {
        detailRequests.append(id)
        return detailReplies.isEmpty ? .failure(.decoding) : detailReplies.removeFirst()
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
