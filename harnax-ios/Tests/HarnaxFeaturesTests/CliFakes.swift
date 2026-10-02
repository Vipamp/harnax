import Foundation
import HarnaxCore
@testable import HarnaxFeatures

/// The CLI facade stand-in, with the same reply-queue discipline as `FakeAgents` and `FakeTeams`: a request
/// nobody queued answers as a decoding failure and still shows up in the call log, so an extra read reads
/// as a wrong count rather than a crash.
///
/// The gate needs three separate dials — the related-agents answer decides whether a write is even sent —
/// so each of the five facade methods has its own reply slot.
final class FakeClis: CliCataloging, @unchecked Sendable {
    private(set) var requests: [(num: Int, size: Int)] = []
    private(set) var filters: [(name: String?, status: Int?)] = []
    var replies: [Result<Page<CliSummary>, APIError>] = []

    private(set) var detailRequests: [Int64] = []
    var detailReplies: [Result<CliSummary, APIError>] = []

    private(set) var relatedAgentRequests: [Int64] = []
    var relatedAgentsReply: Result<[RelatedAgent], APIError> = .success([])

    /// The blast-radius read is parkable, because the switch's decision window sits inside it: `requestStatus`
    /// is still judging while this call is out, and a second tap has to be refused there rather than ask the
    /// same question twice.
    var gateRelatedAgents = false

    private var parked: [() -> Void] = []
    private var parkedWrites: [() -> Void] = []

    private(set) var relatedSessionRequests: [Int64] = []
    var relatedSessionsReply: Result<[RelatedSession], APIError> = .success([])

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []

    /// The switch's own POST is parkable, so a second tap can be thrown while the first is still out
    /// (`RowWriteReentryTests`).
    var gateWrites = false

    /// The page read is parkable: an append has to be able to stay in flight while the reader changes the
    /// query (`ListAppendIdentityTests`).
    let pageGate = PageReadGate<Result<Page<CliSummary>, APIError>>()

    /// Queues one page answer for every test that only needs rows on screen.
    func seedPage(_ records: [[String: Any]], total: Int? = nil) throws {
        replies = [.success(try PageStub.page(CliSummary.self, records, total: total))]
    }

    func cliPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<CliSummary>, APIError> {
        requests.append((num: num, size: size))
        filters.append((name: name, status: status))
        return await pageGate.absorb(replies.isEmpty ? .failure(.decoding) : replies.removeFirst())
    }

    func cliDetail(id: Int64) async -> Result<CliSummary, APIError> {
        detailRequests.append(id)
        return detailReplies.isEmpty ? .failure(.decoding) : detailReplies.removeFirst()
    }

    func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> {
        relatedAgentRequests.append(id)
        guard gateRelatedAgents else { return relatedAgentsReply }
        return await withCheckedContinuation { continuation in
            parked.append { continuation.resume(returning: self.relatedAgentsReply) }
        }
    }

    /// Runs every parked read in the order it went out.
    func releaseReads() {
        let waiting = parked
        parked = []
        for resume in waiting { resume() }
    }

    func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        relatedSessionRequests.append(id)
        return relatedSessionsReply
    }

    func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        guard gateWrites else { return nextStatusReply() }
        return await withCheckedContinuation { continuation in
            parkedWrites.append { continuation.resume(returning: self.nextStatusReply()) }
        }
    }

    /// Runs every parked write in the order it went out, each pulling its own next reply.
    func releaseWrites() {
        let waiting = parkedWrites
        parkedWrites = []
        for resume in waiting { resume() }
    }

    private func nextStatusReply() -> Result<EmptyResponse, APIError> {
        statusReplies.isEmpty ? .success(EmptyResponse()) : statusReplies.removeFirst()
    }
}

extension CliSummary {
    static func stub(_ fields: [String: Any]) throws -> CliSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(CliSummary.self, from: data)
    }
}

/// `RelatedAgentInfo` is all-non-null server-side, so a test spells the three keys rather than building the
/// struct field by field.
func relatedAgent(_ id: Int, _ name: Any, status: Int = 1) throws -> RelatedAgent {
    let data = try JSONSerialization.data(withJSONObject: [
        "agentId": id,
        "agentName": name,
        "status": status,
    ])
    return try JSONDecoder().decode(RelatedAgent.self, from: data)
}

func relatedSession(_ id: String, source: String = "session", name: String? = nil, agent: String? = nil) throws -> RelatedSession {
    var row: [String: Any] = ["sessionId": id, "sourceType": source]
    if let name { row["sourceName"] = name }
    if let agent { row["agentName"] = agent }
    return try PageStub.list([RelatedSession].self, [row])[0]
}
