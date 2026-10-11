import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The memory routes as they actually go out, and the two shapes on them that a copy-paste gets wrong.
///
/// Both are silent:
/// 1. `{agentId}` binds the **runtime agent name**, not the database row id (`MemoryController.kt:70`, `:89`).
///    A caller that hands it an id reads a name that no agent has, and a name with a `/` in it would open a
///    second path segment on a route that has exactly one;
/// 2. the list returns a **bare array** (`MemoryController.kt:54`), not the `Page` wrapper every other admin
///    list answers, so a decoder written for the house shape fails on a healthy response.
///
/// The third case here is not a wire shape but a distinction the transport cannot make for the screen: the
/// controller answers `404` inside a successful envelope for「this caller has no memory for that agent」
/// (`MemoryController.kt:73`), and that has to render as an empty state rather than as a failure.
final class MemoryWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func url(_ endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.absoluteString ?? "<no url>"
    }

    // MARK: - the three routes

    func testTheListIsAGetWithNoQueryAndNoBody() {
        let endpoint = MemoryEndpoint.list()
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/memory")
        XCTAssertTrue(endpoint.query.isEmpty, "the list takes no filter and no paging")
        XCTAssertNil(endpoint.body)
    }

    /// The two by-agent routes are one segment deep, and both address the agent by name.
    func testBothByAgentRoutesCarryTheNameAsTheirOneSegment() {
        XCTAssertEqual(MemoryEndpoint.read(agentName: "translator").path, "/api/admin/memory/translator")
        let remove = MemoryEndpoint.delete(agentName: "translator")
        XCTAssertEqual(remove.method, .delete, "a purge must not be a GET the cache may answer")
        XCTAssertEqual(remove.path, "/api/admin/memory/translator")
    }

    /// A name is free text an owner typed, so it can hold every character that means something else in a URL.
    /// `Endpoint.segment` (`Transport/Endpoint.swift:99-102`) is what keeps one agent inside one segment — a
    /// `review/2026` agent addressed without it would ask for `/api/admin/memory/review` plus a `2026` segment.
    func testAStrangeNameStaysInsideOneSegment() {
        for name in ["review/2026", "why?now", "now#1", "two words", "翻译 智能体"] {
            let path = MemoryEndpoint.read(agentName: name).path
            XCTAssertEqual(
                path.split(separator: "/", omittingEmptySubsequences: false).count,
                5,
                "\(name) escaped its segment: \(path)"
            )
            XCTAssertFalse(path.hasSuffix("/"), "\(name) would leave an empty trailing segment")
        }
    }

    /// Encoding is not the same as dropping: the name the server sees has to be the name the caller had,
    /// otherwise a legitimate agent becomes a 404 that reads as「no memory」.
    func testAnEncodedNameDecodesBackToTheAgentItNamed() {
        let name = "review/2026 v2 翻译"
        let segment = MemoryEndpoint.read(agentName: name).path
            .split(separator: "/").last.map(String.init)!
        XCTAssertEqual(segment.removingPercentEncoding, name)
    }

    /// Nothing on these routes names a user or a tenant; the shared header is the only scoping lever
    /// (`MemoryController.kt:30-31`), so a request cannot point at another account's memory bucket.
    func testEveryMemoryRouteIsAnAuthenticatedAdminReadWithNoTenantParameter() {
        for endpoint in [
            MemoryEndpoint.list(),
            MemoryEndpoint.read(agentName: "a"),
            MemoryEndpoint.delete(agentName: "a"),
        ] {
            if case .admin = endpoint.base {} else { XCTFail("\(endpoint.path) would go to the router host") }
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path)")
            XCTAssertTrue(endpoint.sendsTenantHeader, "\(endpoint.path) is tenant-scoped")
            XCTAssertFalse(endpoint.path.lowercased().contains("tenant"))
            XCTAssertFalse(endpoint.query.contains { $0.name.lowercased().contains("user") })
        }
    }

    // MARK: - what the answers decode into

    /// A bare array, with the two layer counts kept apart: an agent whose only memory is the conversation
    /// layer has no curated text and no dates, yet still gets a row (`MemoryStoreGateway.kt:94-95`).
    func testTheListDecodesFromABareArrayEnvelope() throws {
        let envelope = """
        {"code":200,"message":"success","data":[\
        {"agentId":"translator","content":"# 结论\\n用户偏好简体中文","lastModified":"2026-10-08T09:00:00",\
        "dates":["2026-09-28","2026-10-07"],"pendingSessionLayers":0},\
        {"agentId":"researcher","content":"","lastModified":null,"dates":[],"pendingSessionLayers":3}\
        ],"timestamp":1790592457109,"isSuccess":true}
        """
        let rows = try JSONDecoder().decode(Envelope<[MemoryAgentRow]>.self, from: Data(envelope.utf8)).data!
        XCTAssertEqual(rows.map(\.id), ["translator", "researcher"], "the name is the identity")

        XCTAssertTrue(rows[0].hasCuratedText)
        XCTAssertEqual(rows[0].dateCount, 2)
        XCTAssertEqual(rows[0].pendingSessionLayers, 0)

        XCTAssertFalse(rows[1].hasCuratedText, "「no conclusion layer」is a blank here, not a missing key")
        XCTAssertEqual(rows[1].dateCount, 0)
        XCTAssertEqual(
            rows[1].pendingSessionLayers, 3,
            "the unmerged count is the only reason this row exists at all"
        )
    }

    /// The detail's ledger comes back oldest first and the screen keeps that order (`MemoryDetailResponse.kt:22-23`).
    func testTheDetailKeepsTheLedgerInTheOrderTheServerSentIt() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"agentId":"translator","content":"# 结论","lastModified":"2026-10-08T09:00:00","entries":[\
        {"date":"2026-10-07","content":"今天…","lastModified":"2026-10-07T20:00:00"},\
        {"date":"2026-10-08","content":"今天又…","lastModified":null}]},\
        "timestamp":1790592457109,"isSuccess":true}
        """
        let detail = try JSONDecoder().decode(Envelope<MemoryDetail>.self, from: Data(envelope.utf8)).data!
        XCTAssertEqual(detail.entries.map(\.id), ["2026-10-07", "2026-10-08"])
        XCTAssertNil(detail.entries[1].lastModified, "admin drops a null key rather than sending one")
    }

    /// A purge reports how many objects left the store, and one and four are different answers
    /// (`MemoryDeleteResponse.kt:17-18`), so the number has to survive the decode to survive the screen.
    func testAPurgeReportsWhatItTookOut() throws {
        let envelope = """
        {"code":200,"message":"success","data":{"agentId":"translator","deletedObjects":5},\
        "timestamp":1790592457109,"isSuccess":true}
        """
        let result = try JSONDecoder().decode(Envelope<MemoryDeleteResult>.self, from: Data(envelope.utf8)).data!
        XCTAssertEqual(result.agentId, "translator")
        XCTAssertEqual(result.deletedObjects, 5)
    }

    // MARK: - 「nothing yet」against「the read failed」

    func testOnlyTheMissingMemoryAnswerIsAnEmptyState() {
        XCTAssertTrue(APIError.business(code: 404, message: "No memory for this agent").isMissingAgentMemory)

        /// The same code with a different sentence is not this deployment's way of saying「nothing here」, and
        /// treating it as such would hide a real failure behind a calm empty screen.
        for other in [
            APIError.business(code: 404, message: "No handler found"),
            APIError.business(code: 400, message: APIError.noMemoryMessage),
            APIError.unauthorized,
            APIError.offline,
        ] {
            XCTAssertFalse(other.isMissingAgentMemory, "\(other) must stay a failure")
        }
    }

    /// The judgement keys on admin's own sentence, so the string the controller composes is part of the
    /// contract and belongs next to the error type rather than inside a screen (`MemoryController.kt:73`).
    func testTheSentenceIsTheOneTheControllerWrites() {
        XCTAssertEqual(APIError.noMemoryMessage, "No memory for this agent")
    }
}
