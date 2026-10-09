import XCTest
import HarnaxCore

@testable import HarnaxAPI

/// The session panel's two legs as they actually go out, and the way each one fails on its own.
///
/// The panel is built from one admin route and one router route at once, so its drift is silent in a way a
/// single-route read is not: a queue read that loses its `sessionId` answers with the whole tenant's nominations
/// while still looking like a healthy list (`SkillDraftController.kt`'s `sessionId` parameter is what Task 10
/// binds). The other half of the rule is what a failing leg *costs*: only itself. `read` never throws, because a
/// throw would make the client drop the half that did answer, and the panel would say 「这个会话的技能读不出来」 over
/// rows it actually has — the console's drawer answers the same failure with those rows still on screen
/// (`harnax-webui/src/pages/session/components/SessionSkillsDrawer.tsx`'s `Promise.allSettled`, pinned by
/// `sessionSkills.test.ts:82-126`).
///
/// Failing and answering empty are still not the same shape: a leg that did not answer flips `unavailable`, while
/// an enabled set that answered with nothing is exactly what a stopped or unbound session says and leaves the flag
/// alone. Refusal codes belong to the enable call — the only one that changes anything.
///
/// The enable matters for the same reason in the other direction: the panel has one sentence per server code, and
/// a request that never reached a server must not borrow one of them.
final class SessionSkillWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"
    private let queuePath = "/api/admin/skill-drafts"
    private var directoryPath: String { directoryPath(for: "s-1") }

    /// The directory leg's path for one conversation. Routed replies are matched on the exact path, so a test that
    /// reads a session other than `s-1` has to park its answer under that session's own path — otherwise the leg
    /// falls through to the 599 fallback and the test reads a failure it never asked for.
    private func directoryPath(for sessionId: String) -> String {
        "/api/router/agent/session-skills/\(sessionId)"
    }

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
    ///
    /// The two legs go out together (`async let`), so which one reaches the transport first belongs neither to the
    /// client nor to this test: the requests are picked by path and each reply is routed to the path that waits for
    /// it, so the fixture cannot land on the wrong leg.
    func testTheQueueLegAsksForThisSessionsPendingNominations() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue("", total: 0))
        harness.transport.enqueue(forPath: directoryPath(for: "s-7"), Wire.success("[]"))

        _ = await harness.agents.read(sessionId: "s-7")

        let sent = harness.transport.requests.compactMap(\.url)
        XCTAssertEqual(sent.count, 2, "the panel is two reads, not one")
        let queueURL = try XCTUnwrap(sent.first { $0.path == queuePath })
        let items = Dictionary(uniqueKeysWithValues: queryItems(of: queueURL).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(items["sessionId"], "s-7", "without this the panel lists the tenant, not this conversation")
        XCTAssertEqual(items["status"], "PENDING", "the queue only ever nominates what is still waiting")
        XCTAssertEqual(items["pageNum"], "1")
        XCTAssertEqual(items["pageSize"], "50")
        XCTAssertNil(items["name"], "no term is being sent, so the key stays off the URL")
        XCTAssertEqual(
            sent.first { $0.path.hasPrefix("/api/router/agent/session-skills/") }?.path,
            "/api/router/agent/session-skills/s-7",
            "the enabled set is read for the same conversation the nominations were"
        )
    }

    /// The endpoint's own behaviour, unchanged by the client rule below: a blank value is dropped rather than sent
    /// as an empty scope. What dropping it means is the server's answer — the whole tenant's queue, not a
    /// conversation nobody named — which is why the client stops asking instead (`testABlankConversation…`).
    func testASessionIdNobodyHasIsLeftOffTheQueueQuery() {
        XCTAssertFalse(
            SkillDraftEndpoint.page(status: .pending, name: nil, sessionId: "   ", num: 1, size: 50)
                .query.contains { $0.name == "sessionId" },
            "a blank value loses the key, and a query without it is the reviewer's whole tenant"
        )
        XCTAssertEqual(
            SkillDraftEndpoint.page(status: .pending, name: nil, num: 1, size: 20).query.map(\.name),
            ["pageNum", "pageSize", "status"],
            "the reviewer's queue keeps exactly the query it always sent"
        )
    }

    /// The client half of the same rule, one level up: a conversation id that trims to nothing is not asked about.
    ///
    /// The endpoint above answers that query with the tenant's rows, and the merge would draw them under this
    /// conversation's title as though they were its own nominations — the one thing a session panel may not do. So
    /// the leg stays home and reports itself as not answered, which is what makes the panel say 「读不出来」 rather
    /// than 「还没有自写的技能」.
    func testABlankConversationDoesNotSendAnUnscopedQueueQuery() async throws {
        let harness = await harness()
        // Armed anyway: were a leg to go out, it would get a plausible answer back and the panel would look healthy.
        harness.transport.enqueue(forPath: queuePath, queue(oneNomination, total: 1))
        harness.transport.enqueue(forPath: directoryPath, Wire.success("[]"))

        let read = await harness.agents.read(sessionId: "   ")

        let sent = harness.transport.requests.compactMap(\.url)
        XCTAssertTrue(
            sent.filter { $0.path == queuePath }.isEmpty,
            "the nominations leg has to stay home for a conversation nobody named: \(sent.map(\.path))"
        )
        XCTAssertTrue(
            sent.filter { $0.path == directoryPath }.isEmpty,
            "so does the directory leg: \(sent.map(\.path))"
        )
        XCTAssertTrue(read.unavailable, "a leg that never went out did not answer")
        XCTAssertTrue(read.rows.isEmpty, "the tenant's nominations must not be drawn as this conversation's")
    }

    /// The mutating leg obeys the same rule: a blank conversation id would go out as `/api/router/agent/
    /// session-skills/%20/<name>/enable`, which the router answers as a session with no sandbox. Refusing locally
    /// costs nothing and says the same thing the panel already has copy for.
    func testABlankConversationSendsNoEnable() async throws {
        let harness = await harness()
        do {
            try await harness.agents.enable(sessionId: " ", name: "invoice-fill")
            XCTFail("a conversation nobody named must not have an enable placed")
        } catch let refusal as SessionSkillRefusal {
            XCTAssertEqual(refusal.code, -1, "no envelope answered, so no code this app can explain: \(refusal.code)")
        }
        XCTAssertTrue(
            harness.transport.requests.compactMap(\.url).isEmpty,
            "the enable must not reach the transport: \(harness.transport.requests.compactMap { $0.url?.path })"
        )
    }

    /// The legs are independent, so they are issued at once: awaiting one before sending the other buys the panel a
    /// second round trip on every open for two answers nobody needs in order.
    ///
    /// The gate parks whichever leg reaches the transport first, and the second request only appears while that one
    /// is still outstanding if both were issued before either answered. Two sequential `await`s leave exactly one
    /// request on the wire, and the wait fails with the armed reply still held.
    func testTheTwoLegsGoOutTogetherRatherThanInTurn() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue(oneNomination, total: 1))
        harness.transport.enqueue(forPath: directoryPath, Wire.success("[]"))
        harness.transport.replyGate.arm()

        async let pending = harness.agents.read(sessionId: "s-1")
        try await waitUntil { harness.transport.requests.count == 2 }
        harness.transport.replyGate.release()
        let read = await pending

        XCTAssertEqual(read.rows.map(\.name), ["invoice-fill"])
        XCTAssertFalse(read.unavailable, "a leg that was merely slow on the way out is not a leg that failed")
    }

    /// Polls until both legs really are on the wire, the same way `AuthFlowTests` polls for the one it parks
    /// (`AuthFlowTests.swift:510-518`).
    private func waitUntil(_ condition: @escaping @Sendable () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("the second leg never went out while the first was still outstanding")
    }

    // MARK: - the merge over the wire

    /// One admin page and one router list become one list: the queue's order and text, the directory's enabled
    /// state and stamp, and an enabled skill whose draft has already left the queue still on screen.
    func testBothLegsMergeIntoOneRowPerName() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue("""
        \(oneNomination),\
        {"id":2,"name":"csv-clean","status":"PENDING","upstreamFindingCount":0}
        """, total: 2))
        harness.transport.enqueue(forPath: directoryPath, Wire.success("""
        [{"name":"invoice-fill","description":"the copy taken at enable time","enabledAt":"2026-10-08 10:00:00"},\
        {"name":"weekly-digest","enabledAt":"2026-10-07 18:30:00"}]
        """))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertFalse(read.unavailable, "both legs answered, so the panel is not missing anything")
        XCTAssertEqual(read.rows.map(\.name), ["invoice-fill", "csv-clean", "weekly-digest"])
        XCTAssertEqual(read.rows[0].description, "fills an invoice", "the live draft text wins over the copied one")
        XCTAssertTrue(read.rows[0].enabled)
        XCTAssertEqual(read.rows[0].enabledAt, "2026-10-08 10:00:00")
        XCTAssertFalse(read.rows[1].enabled)
        XCTAssertNil(read.rows[1].enabledAt)
        XCTAssertNil(read.rows[1].description)
        XCTAssertTrue(read.rows[2].enabled, "the directory only lists what the session may already use")
    }

    /// A row the panel cannot address is dropped rather than drawn: the name is the enable route's path segment,
    /// so keeping it would put a button on screen whose only possible answer is a wrong URL.
    func testANamelessRowDropsRatherThanBecomingADeadButton() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue("""
        \(oneNomination),\
        {"id":9,"status":"PENDING","upstreamFindingCount":0}
        """, total: 2))
        harness.transport.enqueue(forPath: directoryPath, Wire.success(#"[{"enabledAt":"2026-10-08 10:00:00"}]"#))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertEqual(read.rows.map(\.name), ["invoice-fill"])
        XCTAssertFalse(read.unavailable, "a row nobody can name is not a leg that failed")
    }

    /// 两份读各走各的失败, in the direction the throw used to get wrong: a queue that would not load costs the
    /// panel its nominations and nothing else. The directory's rows still reach the screen beside the sentence
    /// saying half the read is missing — the console answers the same failure with the same rows
    /// (`sessionSkills.test.ts:82-92`), and a throw would have left an empty list under 「这个会话的技能读不出来」 for
    /// rows this panel is holding in its hand.
    func testAFailedQueueLegCostsTheNominationsAndNotTheEnabledRows() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, Wire.business(500, "draft queue is down"))
        harness.transport.enqueue(
            forPath: directoryPath,
            Wire.success(#"[{"name":"weekly-digest","enabledAt":"2026-10-07 18:30:00"}]"#)
        )

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertTrue(read.unavailable, "the queue did not answer, and the panel has to say so")
        XCTAssertEqual(
            read.rows.map(\.name),
            ["weekly-digest"],
            "a leg that failed is not news that nothing was nominated — the half that answered stays"
        )
        XCTAssertTrue(read.rows[0].enabled)
        XCTAssertEqual(read.rows[0].enabledAt, "2026-10-07 18:30:00")
    }

    /// The same rule seen from the other leg. The directory's rows are the ones carrying `enabled`, so losing it
    /// leaves the queue's nomination in the merge without an enabled answer — the shape the console lands for the
    /// identical failure (`sessionSkills.test.ts:112-114`), and why `unavailable` has to travel with it rather than
    /// the row being drawn as a verdict.
    func testAFailedDirectoryLegCostsTheEnabledAnswersAndNotTheNominations() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue(oneNomination, total: 1))
        harness.transport.enqueue(forPath: directoryPath, Wire.business(500, "agent-service is down"))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertTrue(read.unavailable, "an unreadable enabled set is said out loud")
        XCTAssertEqual(
            read.rows.map(\.name),
            ["invoice-fill"],
            "the nomination the queue did answer is the row the user can still act on"
        )
        XCTAssertFalse(read.rows[0].enabled, "the only leg that knew is the one that did not answer")
        XCTAssertNil(read.rows[0].enabledAt)
    }

    /// An empty enabled set is an answer. A session whose sandbox is stopped, or which was never bound to one,
    /// gives back `data: []` on this leg — and that is not a leg missing: `unavailable` stays off, or every session
    /// without a running container would read as one the panel cannot see into.
    func testAnEmptyDirectoryBesideNominationsIsAnAnswerRatherThanAFailure() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue(oneNomination, total: 1))
        harness.transport.enqueue(forPath: directoryPath, Wire.success("[]"))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertFalse(read.unavailable, "nothing enabled is an answer, not a leg that went missing")
        XCTAssertEqual(
            read.rows.map(\.name),
            ["invoice-fill"],
            "nothing enabled is not nothing nominated; the queue's row stays on screen"
        )
        XCTAssertFalse(read.rows[0].enabled)
        XCTAssertNil(read.rows[0].enabledAt)
    }

    /// Both legs failing is the one case an empty list is honest about: nothing answered, so the panel may claim
    /// nothing about this conversation — and it claims 「读不出来」, not 「还没有自写的技能」.
    func testBothLegsFailingAnswerNothingRatherThanAnEmptySession() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, Wire.business(500, "draft queue is down"))
        harness.transport.enqueue(forPath: directoryPath, Wire.business(500, "agent-service is down"))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertTrue(read.unavailable)
        XCTAssertTrue(read.rows.isEmpty, "nothing answered, so no row may be invented from a failure")
    }

    /// Half a merge is worse than none *for that leg*: a body that is not the list shape is the directory's own
    /// failure, and the nominations the queue answered still reach the panel beside it.
    func testAnUnparseableDirectoryBodyCostsOnlyItsOwnLeg() async throws {
        let harness = await harness()
        harness.transport.enqueue(forPath: queuePath, queue(oneNomination, total: 1))
        harness.transport.enqueue(forPath: directoryPath, Wire.success(#"{"name":"invoice-fill"}"#))

        let read = await harness.agents.read(sessionId: "s-1")

        XCTAssertTrue(read.unavailable, "a body this side cannot read is a leg that did not answer")
        XCTAssertEqual(read.rows.map(\.name), ["invoice-fill"])
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
