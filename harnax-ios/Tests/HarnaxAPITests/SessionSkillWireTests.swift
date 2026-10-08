import XCTest
import HarnaxCore

@testable import HarnaxAPI

/// The session panel's two legs as they actually go out, and the two ways each can come back wrong.
///
/// The panel is built from one admin route and one router route at once, so its drift is silent in a way a
/// single-route read is not: a queue read that loses its `sessionId` answers with the whole tenant's nominations
/// while still looking like a healthy list (`SkillDraftController.kt`'s `sessionId` parameter is what Task 10
/// binds), and a leg that fails has to stay a failure rather than becoming the empty half of a merge — an empty
/// merge is the sentence 「这个会话还没有自写的技能」 on screen.
///
/// The two halves of that rule are not the same shape, and this file keeps them apart: a leg that did not answer
/// is a failure, while an enabled set that answered with nothing is exactly what a stopped or unbound session
/// says. Refusal codes belong to the enable call — the only one that changes anything.
///
/// The enable matters for the same reason in the other direction: the panel has one sentence per server code, and
/// a request that never reached a server must not borrow one of them.
final class SessionSkillWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func harness() async -> APIHarness {
        let harness = APIHarness()
        try? await harness.signIn()
        return harness
    }

    /// A queue page as admin answers one. Only the keys the panel reads are spelled out; Jackson drops the nulls
    /// the service never sets, and `SkillDraftRow` treats every one of them as absent-by-default
    /// (`SkillDraftWireTests:182-206`).
    private func queue(_ records: String, total: Int) -> String {
        Wire.success(#"{"pageNum":1,"pageSize":50,"total":\#(total),"records":[\#(records)]}"#)
    }

    private var oneNomination: String {
        """
        {"id":1,"name":"invoice-fill","description":"fills an invoice","status":"PENDING","upstreamFindingCount":0}
        """
    }

    // MARK: - the two routes

    func testTheDirectoryIsAGetOnTheRouterBase() throws {
        let endpoint = SessionSkillEndpoint.rows(sessionId: "s-1")

        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/router/agent/session-skills/s-1")
        XCTAssertEqual(
            endpoint.url(baseURL: admin)?.absoluteString,
            "\(admin)/api/router/agent/session-skills/s-1"
        )
        XCTAssertTrue(endpoint.query.isEmpty, "the session key is the whole request")
        XCTAssertNil(endpoint.body)
        if case .router = endpoint.base {} else {
            XCTFail("the enabled set lives behind the router, which knows which instance holds the session")
        }
    }

    func testTheEnableIsAPostOnTheNameUnderTheSessionAndCarriesNoBody() throws {
        let endpoint = SessionSkillEndpoint.enable(sessionId: "s-1", name: "invoice-fill")

        XCTAssertEqual(endpoint.method, .post)
        XCTAssertEqual(endpoint.path, "/api/router/agent/session-skills/s-1/invoice-fill/enable")
        XCTAssertNil(endpoint.body, "the router names the operator off its own auth context, not off a body")
        XCTAssertTrue(endpoint.query.isEmpty)
    }

    /// Both keys are caller-supplied strings going into path segments: an unencoded `/` reaches the router as two
    /// extra segments and misses the binding (`ContextUsageEndpointTests:29-42` pins the same rule for the
    /// occupancy read).
    func testASlashInTheSessionKeyStaysInsideItsSegment() {
        let encoded = "a%2Fb%3Fc%23d"
        XCTAssertEqual(
            SessionSkillEndpoint.rows(sessionId: "a/b?c#d").path,
            "/api/router/agent/session-skills/\(encoded)"
        )
        XCTAssertEqual(
            SessionSkillEndpoint.enable(sessionId: "a/b?c#d", name: "x/y").path,
            "/api/router/agent/session-skills/\(encoded)/x%2Fy/enable"
        )
    }

    // MARK: - the queue leg is the scoped one

    /// The one parameter this task adds, and the one whose absence is invisible: without it the panel still draws
    /// a list, it just draws somebody else's conversation.
    func testTheQueueLegAsksForThisSessionsPendingNominations() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue("", total: 0))
        harness.transport.enqueue(200, Wire.success("[]"))

        _ = try await harness.agents.rows(sessionId: "s-7")

        XCTAssertEqual(harness.transport.requests.count, 2, "the panel is two reads, not one")
        let queueURL = try XCTUnwrap(harness.transport.requests[0].url)
        let items = Dictionary(uniqueKeysWithValues: queryItems(of: queueURL).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(items["sessionId"], "s-7", "without this the panel lists the tenant, not this conversation")
        XCTAssertEqual(items["status"], "PENDING", "the queue only ever nominates what is still waiting")
        XCTAssertEqual(items["pageNum"], "1")
        XCTAssertEqual(items["pageSize"], "50")
        XCTAssertNil(items["name"], "no term is being sent, so the key stays off the URL")
        XCTAssertEqual(
            try XCTUnwrap(harness.transport.requests[1].url).path,
            "/api/router/agent/session-skills/s-7",
            "the enabled set is read for the same conversation the nominations were"
        )
    }

    func testASessionIdNobodyHasIsLeftOffTheQueueQuery() {
        XCTAssertFalse(
            SkillDraftEndpoint.page(status: .pending, name: nil, sessionId: "   ", num: 1, size: 50)
                .query.contains { $0.name == "sessionId" },
            "a blank scope would ask for a conversation that does not exist, not for the tenant"
        )
        XCTAssertEqual(
            SkillDraftEndpoint.page(status: .pending, name: nil, num: 1, size: 20).query.map(\.name),
            ["pageNum", "pageSize", "status"],
            "the reviewer's queue keeps exactly the query it always sent"
        )
    }

    // MARK: - the merge over the wire

    /// One admin page and one router list become one list: the queue's order and text, the directory's enabled
    /// state and stamp, and an enabled skill whose draft has already left the queue still on screen.
    func testBothLegsMergeIntoOneRowPerName() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue("""
        \(oneNomination),\
        {"id":2,"name":"csv-clean","status":"PENDING","upstreamFindingCount":0}
        """, total: 2))
        harness.transport.enqueue(200, Wire.success("""
        [{"name":"invoice-fill","description":"the copy taken at enable time","enabledAt":"2026-10-08 10:00:00"},\
        {"name":"weekly-digest","enabledAt":"2026-10-07 18:30:00"}]
        """))

        let rows = try await harness.agents.rows(sessionId: "s-1")

        XCTAssertEqual(rows.map(\.name), ["invoice-fill", "csv-clean", "weekly-digest"])
        XCTAssertEqual(rows[0].description, "fills an invoice", "the live draft text wins over the copied one")
        XCTAssertTrue(rows[0].enabled)
        XCTAssertEqual(rows[0].enabledAt, "2026-10-08 10:00:00")
        XCTAssertFalse(rows[1].enabled)
        XCTAssertNil(rows[1].enabledAt)
        XCTAssertNil(rows[1].description)
        XCTAssertTrue(rows[2].enabled, "the directory only lists what the session may already use")
    }

    /// A row the panel cannot address is dropped rather than drawn: the name is the enable route's path segment,
    /// so keeping it would put a button on screen whose only possible answer is a wrong URL.
    func testANamelessRowDropsRatherThanBecomingADeadButton() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue("""
        \(oneNomination),\
        {"id":9,"status":"PENDING","upstreamFindingCount":0}
        """, total: 2))
        harness.transport.enqueue(200, Wire.success(#"[{"enabledAt":"2026-10-08 10:00:00"}]"#))

        let rows = try await harness.agents.rows(sessionId: "s-1")

        XCTAssertEqual(rows.map(\.name), ["invoice-fill"])
    }

    /// A queue that would not load must not read as a conversation whose agent proposed nothing — that is the
    /// difference between 「读不出来」 and 「还没有自写的技能」 on screen, and it is decided here.
    func testAFailedQueueLegThrowsRatherThanAnsweringAnEmptyHalf() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.business(500, "draft queue is down"))

        do {
            _ = try await harness.agents.rows(sessionId: "s-1")
            XCTFail("a leg that failed is not news that nothing was nominated")
        } catch let error as APIError {
            XCTAssertEqual(error, .business(code: 500, message: "draft queue is down"))
        }
    }

    /// The rule: a directory leg that did not answer stays a failure, rather than becoming the empty half of the
    /// merge. It is refused here the way the stack refuses a read — HTTP 200 carrying an envelope code — because
    /// a stopped or unbound session is **not** this shape: that one answers `data: []`, and the panel has to draw
    /// 「还没有自写的技能」 for it (`testAnEmptyDirectoryBesideNominationsIsAnAnswerRatherThanAFailure`). 410 belongs
    /// to the enable leg alone.
    func testAFailedDirectoryLegThrowsRatherThanAnsweringAnEmptyHalf() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue(oneNomination, total: 1))
        harness.transport.enqueue(200, Wire.business(500, "agent-service is down"))

        do {
            _ = try await harness.agents.rows(sessionId: "s-1")
            XCTFail("an unreadable enabled set is not an unenabled one")
        } catch let error as APIError {
            XCTAssertEqual(error, .business(code: 500, message: "agent-service is down"))
        }
    }

    /// An empty enabled set is an answer. A session whose sandbox is stopped, or which was never bound to one,
    /// gives back `data: []` on this leg — and the nominations the queue did return still have to reach the
    /// panel, which is the difference between a row that can be enabled and a screen that claims it cannot read.
    func testAnEmptyDirectoryBesideNominationsIsAnAnswerRatherThanAFailure() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue(oneNomination, total: 1))
        harness.transport.enqueue(200, Wire.success("[]"))

        let rows = try await harness.agents.rows(sessionId: "s-1")

        XCTAssertEqual(
            rows.map(\.name),
            ["invoice-fill"],
            "nothing enabled is not nothing nominated; the queue's row stays on screen"
        )
        XCTAssertFalse(rows[0].enabled)
        XCTAssertNil(rows[0].enabledAt)
    }

    /// Half a merge is worse than none: a body that is not the list shape fails the whole read instead of quietly
    /// returning the nominations that did decode.
    func testAnUnparseableDirectoryBodyFailsTheReadRatherThanLosingThatLeg() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, queue(oneNomination, total: 1))
        harness.transport.enqueue(200, Wire.success(#"{"name":"invoice-fill"}"#))

        do {
            _ = try await harness.agents.rows(sessionId: "s-1")
            XCTFail("expected the read to fail")
        } catch let error as APIError {
            XCTAssertEqual(error, .decoding)
        }
    }

    // MARK: - the enable's answers

    func testASuccessfulEnableThrowsNothing() async throws {
        let harness = await harness()
        harness.transport.enqueue(200, Wire.success())

        try await harness.agents.enable(sessionId: "s-1", name: "invoice-fill")
    }

    /// Each refusal arrives as an HTTP 200 with the code inside the envelope (`APIError.swift:24-25`), and each one
    /// has to keep its own cause: the panel has a separate sentence for a blocked scan, a full session, a stopped
    /// sandbox, a gone draft and a refused copy — and a plain one for a code nobody documented.
    func testEveryBusinessCodeKeepsItsOwnRefusal() async throws {
        for (code, key) in [
            (403, "chat.skills.blocked"),
            (409, "chat.skills.full"),
            (410, "chat.skills.noSandbox"),
            (404, "chat.skills.sourceGone"),
            (500, "chat.skills.copyFailed"),
            (418, "chat.skills.enableFailed"),
        ] {
            let harness = await harness()
            harness.transport.enqueue(200, Wire.business(code, "refused"))

            do {
                try await harness.agents.enable(sessionId: "s-1", name: "invoice-fill")
                XCTFail("code \(code) refused the enable")
            } catch let refusal as SessionSkillRefusal {
                XCTAssertEqual(refusal.code, code)
                XCTAssertEqual(refusal.messageKey, key)
            }
        }
    }

    /// The one answer a network failure must not borrow: 「这份草稿已经不在了」. Nothing said the draft was gone —
    /// the request never reached a server that could.
    func testATransportFailureIsNotReportedAsTheDraftBeingGone() async throws {
        let harness = await harness()
        harness.transport.enqueueFailure(URLError(.notConnectedToInternet))

        do {
            try await harness.agents.enable(sessionId: "s-1", name: "invoice-fill")
            XCTFail("the transport failed")
        } catch let refusal as SessionSkillRefusal {
            XCTAssertEqual(refusal.code, -1)
            XCTAssertEqual(refusal.messageKey, "chat.skills.enableFailed")
        }
    }
}
