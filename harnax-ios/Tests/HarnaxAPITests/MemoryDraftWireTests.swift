import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The memory review queue's routes, the one key each decision body carries, and the outcome codes a screen
/// has to branch on rather than display.
///
/// Two shapes are borrowed from the skill queue and both belong to these routes rather than to the app's
/// convention:
/// 1. the list has **no `/page` suffix** — `MemoryDraftController.kt:51` is a bare `@GetMapping` on the family
///    root, unlike the house list routes;
/// 2. `status` is a *name* (`PENDING` / `APPROVED` / `REJECTED`, `MemoryDraftServiceImpl.kt:594`), so it cannot
///    go through `Endpoint.pageItems`, which sends the 0/1 column.
///
/// The decisions matter for a different reason: four refusals travel back **inside a `code: 200` envelope**
/// (`MemoryDraftDecisionResponse.kt:9-19`), so a screen that treats a successful transport as a completed
/// approval has told the owner their click merged something when it did not.
final class MemoryDraftWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func query(_ endpoint: Endpoint) -> [String] {
        endpoint.query.map { "\($0.name)=\($0.value ?? "")" }
    }

    private func keys(_ endpoint: Endpoint) throws -> [String: JSONValue] {
        try JSONDecoder().decode([String: JSONValue].self, from: try XCTUnwrap(endpoint.body))
    }

    // MARK: - the queue read

    func testTheQueueIsAGetOnTheSuffixlessPath() {
        let endpoint = MemoryDraftEndpoint.page(
            status: .pending, agentName: nil, sessionId: nil, num: 1, size: 20
        )
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/memory-drafts")
        XCTAssertFalse(endpoint.path.hasSuffix("/page"), "this route has no /page suffix")
        XCTAssertNil(endpoint.body, "a GET must not claim a body")
    }

    /// Paging, then the status, then the two caller-chosen filters — the order the controller declares them in
    /// (`MemoryDraftController.kt:56-70`), and nothing else: the queue resolves the account from the caller's
    /// own credentials (`:71-77`), so there is no user or tenant parameter to send.
    func testTheQueryIsPagingThenStatusThenTheTwoFilters() {
        XCTAssertEqual(
            query(MemoryDraftEndpoint.page(status: .approved, agentName: nil, sessionId: nil, num: 2, size: 20)),
            ["pageNum=2", "pageSize=20", "status=APPROVED"]
        )
        XCTAssertEqual(
            MemoryDraftEndpoint.page(
                status: .rejected, agentName: "翻译", sessionId: "sess-7", num: 1, size: 50
            ).query.map(\.name),
            ["pageNum", "pageSize", "status", "agentName", "sessionId"]
        )
    }

    /// PENDING is also the server's own default (`MemoryDraftController.kt:74`), and naming it anyway is the
    /// only way one request shape serves both the default screen and the two decided arms.
    func testPendingIsSentRatherThanLeftToTheServerDefault() {
        let status = MemoryDraftEndpoint.page(
            status: .pending, agentName: nil, sessionId: nil, num: 1, size: 20
        ).query.first { $0.name == "status" }
        XCTAssertEqual(status?.value, "PENDING")
    }

    /// `agentName` is a partial match and `sessionId` an exact one (`:69-70`); the service trims both before
    /// the mapper sees them (`MemoryDraftServiceImpl.kt:162-168`), so a padded term would look applied while
    /// matching nothing. A blank is left off the URL entirely, which is what the console does too.
    func testBothFiltersAreTrimmedIntoTheQueryAndABlankOneIsLeftOffEntirely() {
        let padded = MemoryDraftEndpoint.page(
            status: .pending, agentName: "  翻译  ", sessionId: "  sess-7 ", num: 1, size: 20
        )
        XCTAssertEqual(query(padded).suffix(2).map { $0 }, ["agentName=翻译", "sessionId=sess-7"])

        for blank in [nil, "", "   "] {
            let items = MemoryDraftEndpoint.page(
                status: .pending, agentName: blank, sessionId: blank, num: 1, size: 20
            ).query
            XCTAssertFalse(items.contains { $0.name == "agentName" }, "\(String(describing: blank)) must not be sent")
            XCTAssertFalse(items.contains { $0.name == "sessionId" }, "\(String(describing: blank)) must not be sent")
        }
    }

    // MARK: - the three id-addressed routes

    func testDetailIsABareIdGet() {
        let endpoint = MemoryDraftEndpoint.detail(id: 41)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/memory-drafts/41")
        XCTAssertTrue(endpoint.query.isEmpty)
    }

    func testTheTwoDecisionsArePostsOnTheirOwnSuffixes() throws {
        let approve = try MemoryDraftEndpoint.approve(id: 41, MemoryDraftApprovePayload(expectedDigest: "d"))
        XCTAssertEqual(approve.method, .post)
        XCTAssertEqual(approve.path, "/api/admin/memory-drafts/41/approve")

        let reject = try MemoryDraftEndpoint.reject(id: 41, MemoryDraftRejectPayload(reason: "证据不足"))
        XCTAssertEqual(reject.method, .post)
        XCTAssertEqual(reject.path, "/api/admin/memory-drafts/41/reject")
    }

    /// One key, and that is the whole difference from a skill approval (`MemoryDraftApproveRequest.kt:18-25`):
    /// a memory candidate already names whose layer it rewrites, so there is no name or collision choice to
    /// send — only the digest of the text the reviewer actually read.
    func testTheApprovalSendsTheDigestAndNothingElse() throws {
        let body = try keys(try MemoryDraftEndpoint.approve(id: 41, MemoryDraftApprovePayload(expectedDigest: "abc123")))
        XCTAssertEqual(body.keys.sorted(), ["expectedDigest"])
        XCTAssertEqual(body["expectedDigest"], .string("abc123"))
    }

    /// The reason is load-bearing the way a skill rejection's is not: a rejected candidate leaves the
    /// conversation's layer alone, so the same material comes back next window and the reviewer reads this
    /// sentence then (`MemoryDraftServiceImpl.kt:319-323`).
    func testTheRejectionSendsTheReasonAndNothingElse() throws {
        let body = try keys(try MemoryDraftEndpoint.reject(id: 41, MemoryDraftRejectPayload(reason: "还差一步")))
        XCTAssertEqual(body, ["reason": .string("还差一步")])
    }

    /// Nothing here names a user or a tenant, on any of the five routes (`MemoryDraftController.kt:38-40`).
    func testEveryRouteIsAnAuthenticatedAdminReadWithNoTenantParameter() throws {
        for endpoint in [
            MemoryDraftEndpoint.page(status: .pending, agentName: nil, sessionId: nil, num: 1, size: 20),
            MemoryDraftEndpoint.detail(id: 1),
            try MemoryDraftEndpoint.approve(id: 1, MemoryDraftApprovePayload(expectedDigest: "d")),
            try MemoryDraftEndpoint.reject(id: 1, MemoryDraftRejectPayload(reason: "r")),
        ] {
            if case .admin = endpoint.base {} else { XCTFail("\(endpoint.path) would go to the router host") }
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path)")
            XCTAssertTrue(endpoint.sendsTenantHeader, "\(endpoint.path) is tenant-scoped")
            XCTAssertFalse(endpoint.path.lowercased().contains("tenant"))
            XCTAssertFalse(endpoint.query.contains { $0.name.lowercased().contains("user") })
        }
    }

    // MARK: - what the answers decode into

    /// The queue's row shape (`MemoryDraftResponse.kt:15-54`): neither text is on a row, so the three counts are
    /// all a reviewer has to tell two candidates of one agent apart.
    func testAPageOfRowsDecodesFromTheEnvelope() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"pageNum":1,"pageSize":20,"total":2,"records":[\
        {"id":41,"agentName":"translator","sessionId":"sess-7","status":"PENDING","baseVersion":0,\
        "mergedChars":1840,"sourceCount":2,"targetCount":3,"createTime":"2026-10-08T09:00:00",\
        "updateTime":"2026-10-08T11:00:00"},\
        {"id":42,"agentName":"translator","sessionId":"sess-8","status":"REJECTED","baseVersion":7,\
        "mergedChars":220,"sourceCount":1,"targetCount":0,"createTime":"2026-10-08T09:30:00",\
        "reviewedBy":"admin","reviewedAt":"2026-10-08T12:00:00","rejectReason":"证据不足"}\
        ]},"timestamp":1790592457109,"isSuccess":true}
        """
        let page = try JSONDecoder().decode(
            Envelope<Page<MemoryDraftRow>>.self, from: Data(envelope.utf8)
        ).data!
        XCTAssertEqual(page.total, 2)

        let first = page.records[0]
        XCTAssertTrue(first.isPending)
        XCTAssertEqual(first.baseVersion, 0, "0 means the owner had no long-term layer yet, not a missing key")
        XCTAssertTrue(first.patched, "updateTime after createTime means the merge rewrote the candidate")

        let second = page.records[1]
        XCTAssertFalse(second.isPending)
        XCTAssertFalse(second.patched, "a decided candidate with no updateTime has not been rewritten")
        XCTAssertEqual(second.rejectReason, "证据不足")
    }

    /// The detail carries the four parts that decide the click (`MemoryDraftResponse.kt:71-116`) — and a missing
    /// `baseMd` key is the owner's「I have no memory yet」state rather than a decode failure
    /// (`admin` drops null keys, `default-property-inclusion: non_null`).
    func testTheDetailCarriesBothTextsAndTheDigestTheApprovalMustReturn() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"id":41,"agentName":"translator","sessionId":"sess-7","status":"PENDING",\
        "mergedMd":"# 结论\\n用户偏好简体中文","baseVersion":0,"sources":[\
        {"path":"memory/sess-7/session.md","content":"今天…"}],"targets":[\
        {"path":"memory/2026-10-08.md","expectedVersion":0,"baseText":"","mergedText":"今天…"}],\
        "contentDigest":"9f2c","createTime":"2026-10-08T09:00:00"},\
        "timestamp":1790592457109,"isSuccess":true}
        """
        let detail = try JSONDecoder().decode(
            Envelope<MemoryDraftDetail>.self, from: Data(envelope.utf8)
        ).data!
        XCTAssertTrue(detail.isPending)
        XCTAssertEqual(detail.mergedMd, "# 结论\n用户偏好简体中文")
        XCTAssertNil(detail.baseMd, "an owner with no long-term layer yet has no base text to compare against")
        XCTAssertFalse(detail.hasBaseText)
        XCTAssertEqual(detail.sources.count, 1)
        XCTAssertEqual(detail.targets.map(\.dayLabel), ["2026-10-08"], "a target is named by its day, not its key")
        XCTAssertEqual(detail.contentDigest, "9f2c")
    }

    // MARK: - the decision, which is not the transport

    /// Each refusal carries the one extra field that makes it actionable, and the five known outcomes are
    /// exactly the vocabulary the service writes (`MemoryDraftDecisionResponse.kt:24`).
    func testTheFiveKnownOutcomesKeepTheirOwnEvidence() throws {
        let approved = try decision(#"""
            "outcome":"APPROVED","longTermVersion":12,"clearedSources":2,
            "keptSources":0,"absentSources":1,"dailyTargetsApplied":3
            """#)
        XCTAssertEqual(approved.kind, .approved)
        XCTAssertEqual(approved.longTermVersion, 12)
        XCTAssertEqual(approved.clearedSources, 2)
        XCTAssertEqual(approved.dailyTargetsApplied, 3)

        let changed = try decision(#"""
            "outcome":"DRAFT_CHANGED","clearedSources":0,"keptSources":0,
            "absentSources":0,"dailyTargetsApplied":0,"currentDigest":"new-digest"
            """#)
        XCTAssertEqual(changed.kind, .draftChanged)
        XCTAssertEqual(changed.currentDigest, "new-digest", "the screen needs the new digest to retry")

        let stale = try decision(#"""
            "outcome":"STALE_BASE","clearedSources":0,"keptSources":1,
            "absentSources":0,"dailyTargetsApplied":0,"currentBaseVersion":14,
            "staleTarget":"memory/2026-10-08.md"
            """#)
        XCTAssertEqual(stale.kind, .staleBase)
        XCTAssertEqual(stale.staleTarget, "memory/2026-10-08.md", "a refused day has to be named, not summarised")
        XCTAssertEqual(stale.currentBaseVersion, 14)

        let reviewed = try decision(#"""
            "outcome":"ALREADY_REVIEWED","clearedSources":0,"keptSources":0,
            "absentSources":0,"dailyTargetsApplied":0,"reviewedBy":"admin","rejectReason":"证据不足"
            """#)
        XCTAssertEqual(reviewed.kind, .alreadyReviewed)
        XCTAssertEqual(reviewed.reviewedBy, "admin")

        let rejected = try decision(#"""
            "outcome":"REJECTED","clearedSources":0,"keptSources":0,
            "absentSources":0,"dailyTargetsApplied":0,"reason":"服务端自己拼的一句话"
            """#)
        XCTAssertEqual(rejected.kind, .rejected)
    }

    /// A sixth outcome must not collapse into one of the five: an unknown refusal has to read as「the decision
    /// did not land」, because the screen has no way to undo something it does not know.
    func testAnUnknownOutcomeKeepsItsOwnTextRatherThanBecomingAKnownCase() throws {
        let locked = try decision(#""outcome":"LOCKED_BY_RUN""#)
        XCTAssertEqual(locked.kind, .unknown(raw: "LOCKED_BY_RUN"))
        XCTAssertNotEqual(locked.kind, .approved)
    }

    /// The one refusal keyed on the envelope code instead of the payload: a lost store race answers `409` with
    /// no `data` at all, so there is no decision to read and the candidate is still `PENDING`
    /// (`MemoryDraftController.kt:37-40`). A screen that decoded this through the success path would be reading
    /// a default, not an answer.
    func testARacedApprovalCarriesNoDecisionAtAll() throws {
        let envelope = try JSONDecoder().decode(
            Envelope<MemoryDraftDecision>.self,
            from: Data(#"{"code":409,"message":"the draft changed while it was being written","timestamp":1790592457109}"#.utf8)
        )
        XCTAssertEqual(envelope.code, 409)
        XCTAssertNil(envelope.data, "a refusal keyed on the envelope code has no payload to branch on")
    }

    // MARK: - the reason the queue will store

    /// 512 is the column's width and the service compares with `>`, so exactly 512 is admissible
    /// (`MemoryDraftServiceImpl.kt:321`, `:592`), and the count is UTF-16 code units because that is what
    /// Kotlin's `String.length` counts — so an emoji takes two of the units a reviewer has to stay inside.
    func testTheRejectReasonJudgementMatchesTheColumn() {
        XCTAssertEqual(MemoryDraftRules.maxRejectReasonLength, 512)
        XCTAssertTrue(MemoryDraftRules.isRejectReasonAdmissible(String(repeating: "字", count: 512)))
        XCTAssertFalse(MemoryDraftRules.isRejectReasonAdmissible(String(repeating: "字", count: 513)))

        XCTAssertTrue(MemoryDraftRules.isRejectReasonAdmissible("  短  "), "the service trims before it measures")
        XCTAssertFalse(MemoryDraftRules.isRejectReasonAdmissible("   \n  "), "a whitespace reason is the empty one")
        XCTAssertFalse(MemoryDraftRules.isRejectReasonAdmissible(""))

        XCTAssertEqual(MemoryDraftRules.wireLength("👍 结论"), 5, "counted in UTF-16 units")
        XCTAssertEqual("👍 结论".count, 4, "which is not Swift's own character count")
    }

    /// A decision the way the transport hands it over: inside the envelope, with the three source counters the
    /// service always writes already at zero (`MemoryDraftDecisionResponse.kt:30-42`), so each case above only
    /// has to spell out the fields it is about.
    private func decision(_ fields: String) throws -> MemoryDraftDecision {
        let json = #"{"code":200,"message":"success","data":{\#(fields),"clearedSources":0,"keptSources":0,"absentSources":0,"dailyTargetsApplied":0},"timestamp":1,"isSuccess":true}"#
        return try JSONDecoder().decode(
            Envelope<MemoryDraftDecision>.self, from: Data(json.utf8)
        ).data!
    }
}
