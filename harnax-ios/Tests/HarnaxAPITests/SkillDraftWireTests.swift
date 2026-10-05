import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The draft-review routes as they actually go out, and the two bodies the review actions accept.
///
/// The queue route is the one list in the app that does not look like the others, and both differences are
/// silent failures if they drift:
/// 1. it has **no `/page` suffix** (`SkillDraftController.kt:50` against `SkillEndpoint.swift`'s
///    `/api/admin/skills/page`), so copying the house shape yields a 404 the transport reports as a business
///    error rather than as a broken URL;
/// 2. its `status` filter is a *name*, not the 0/1 column `Endpoint.pageItems` sends, so it cannot go through
///    the shared helper (`Endpoint.swift:72-84`).
///
/// The bodies matter for a different reason: `conflictResolution` has no server-side default at all, because
/// both answers either overwrite or duplicate something somebody else published
/// (`SkillDraftApproveRequest.kt:12-20`), so the first attempt must send exactly one key and let the server
/// answer `NAME_TAKEN` before anything is chosen.
final class SkillDraftWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func url(_ endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.absoluteString ?? "<no url>"
    }

    private func keys(_ endpoint: Endpoint) throws -> [String: JSONValue] {
        try JSONDecoder().decode([String: JSONValue].self, from: try XCTUnwrap(endpoint.body))
    }

    // MARK: - the queue read

    func testTheQueueIsAGetOnTheSuffixlessPath() {
        let endpoint = SkillDraftEndpoint.page(status: .pending, name: nil, num: 1, size: 20)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/skill-drafts")
        XCTAssertFalse(endpoint.path.hasSuffix("/page"), "this route has no /page suffix")
        XCTAssertNil(endpoint.body, "a GET must not claim a body")
    }

    /// The three always-on parameters in the order the service declares them, and nothing else: the route has
    /// no tenant parameter, because the controller resolves the tenant from the caller's own credentials
    /// (`SkillDraftController.kt:55-63`).
    func testTheQueryIsPagingThenStatus() {
        XCTAssertEqual(
            SkillDraftEndpoint.page(status: .approved, name: nil, num: 2, size: 20).query.map(\.name),
            ["pageNum", "pageSize", "status"]
        )
        XCTAssertEqual(
            url(SkillDraftEndpoint.page(status: .approved, name: nil, num: 2, size: 20)),
            "\(admin)/api/admin/skill-drafts?pageNum=2&pageSize=20&status=APPROVED"
        )
    }

    /// `status` goes out even for PENDING, which is also the server's own default: naming the arm is the only
    /// way to ask for one particular list, and the console sends it the same way
    /// (`harnax-webui/src/services/ant-design-pro/skillDraft.ts:6-8`).
    func testPendingIsSentRatherThanLeftToTheServerDefault() {
        let query = SkillDraftEndpoint.page(status: .pending, name: nil, num: 1, size: 20).query
        XCTAssertEqual(query.last?.name, "status")
        XCTAssertEqual(query.last?.value, "PENDING")
    }

    /// The keyword is a partial match on the skill name (`SkillDraftController.kt:63`) and it is trimmed
    /// before it goes: the console trims too (`drafts.tsx:206-215`), and a padded search term would otherwise
    /// match nothing while looking like it had been applied.
    func testAKeywordIsTrimmedIntoTheQueryAndABlankOneIsLeftOffEntirely() {
        let padded = SkillDraftEndpoint.page(status: .pending, name: "  pdf  ", num: 1, size: 20).query
        XCTAssertEqual(padded.map { "\($0.name)=\($0.value ?? "")" }.last, "name=pdf")

        for blank in [nil, "", "   "] {
            let query = SkillDraftEndpoint.page(status: .pending, name: blank, num: 1, size: 20).query
            XCTAssertFalse(query.contains { $0.name == "name" }, "\(String(describing: blank)) must not be sent")
        }
    }

    // MARK: - the three id-addressed routes

    func testDetailIsABareIdGet() {
        let endpoint = SkillDraftEndpoint.detail(id: 17)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/skill-drafts/17")
        XCTAssertTrue(endpoint.query.isEmpty)
        XCTAssertNil(endpoint.body)
    }

    func testTheTwoReviewActionsArePostsOnTheirOwnSuffix() throws {
        let approve = try SkillDraftEndpoint.approve(
            id: 17, SkillDraftApprovePayload(expectedDigest: "abc")
        )
        XCTAssertEqual(approve.method, .post)
        XCTAssertEqual(approve.path, "/api/admin/skill-drafts/17/approve")

        let reject = try SkillDraftEndpoint.reject(id: 17, SkillDraftRejectPayload(reason: "重复"))
        XCTAssertEqual(reject.path, "/api/admin/skill-drafts/17/reject")
    }

    /// Every route here hangs off the admin host and needs the bearer; the tenant rides the header the shared
    /// transport attaches, never a parameter.
    func testEveryRouteIsAnAuthenticatedAdminRead() throws {
        for endpoint in [
            SkillDraftEndpoint.page(status: .pending, name: nil, num: 1, size: 20),
            SkillDraftEndpoint.detail(id: 1),
            try SkillDraftEndpoint.approve(id: 1, SkillDraftApprovePayload(expectedDigest: "d")),
            try SkillDraftEndpoint.reject(id: 1, SkillDraftRejectPayload(reason: "r")),
        ] {
            if case .admin = endpoint.base {} else {
                XCTFail("\(endpoint.path) would be sent to the router host")
            }
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path)")
            XCTAssertTrue(endpoint.sendsTenantHeader, "\(endpoint.path) is tenant-scoped")
            XCTAssertFalse(endpoint.path.lowercased().contains("tenant"))
        }
    }

    // MARK: - the approve body's three states

    /// The first attempt sends the digest and nothing else. Sending a `conflictResolution` pre-emptively would
    /// replace or duplicate a skill nobody had said was in the way.
    func testTheFirstApprovalSendsExactlyTheDigest() throws {
        let body = try keys(
            try SkillDraftEndpoint.approve(id: 17, SkillDraftApprovePayload(expectedDigest: "d-1"))
        )
        XCTAssertEqual(body, ["expectedDigest": .string("d-1")])
    }

    /// Rename is the only arm that carries a name, and the service trims it server-side
    /// (`SkillDraftServiceImpl.kt:415-424`).
    func testARenameSendsAllThreeKeys() throws {
        let body = try keys(
            try SkillDraftEndpoint.approve(
                id: 17,
                SkillDraftApprovePayload(
                    expectedDigest: "d-1",
                    conflictResolution: SkillDraftResolution.rename.rawValue,
                    newName: "invoice-pdf-v2"
                )
            )
        )
        XCTAssertEqual(
            body,
            [
                "expectedDigest": .string("d-1"),
                "conflictResolution": .string("rename"),
                "newName": .string("invoice-pdf-v2"),
            ]
        )
    }

    /// Replace decides not to move the name, so it must not send one: a stray `newName` beside `replace` would
    /// be read by whichever branch came first and rename against the reviewer's intent.
    func testAReplaceSendsTwoKeysAndNoName() throws {
        let body = try keys(
            try SkillDraftEndpoint.approve(
                id: 17,
                SkillDraftApprovePayload(
                    expectedDigest: "d-1",
                    conflictResolution: SkillDraftResolution.replace.rawValue
                )
            )
        )
        XCTAssertEqual(body.keys.sorted(), ["conflictResolution", "expectedDigest"])
        XCTAssertEqual(body["conflictResolution"], .string("replace"))
    }

    /// Both resolution words are the service's own vocabulary (`SkillDraftServiceImpl.kt:583-585`), lowercase.
    func testTheTwoResolutionWords() {
        XCTAssertEqual(SkillDraftResolution.replace.rawValue, "replace")
        XCTAssertEqual(SkillDraftResolution.rename.rawValue, "rename")
    }

    /// The rejection reason is required and reaches the service as the operator's own words; the trimming is
    /// the screen's job before it builds this body (`SkillDraftServiceImpl.kt:370-374`).
    func testTheRejectBodyIsTheOneRequiredReason() throws {
        let body = try keys(
            try SkillDraftEndpoint.reject(id: 17, SkillDraftRejectPayload(reason: "与既有技能重复"))
        )
        XCTAssertEqual(body, ["reason": .string("与既有技能重复")])
    }

    // MARK: - what the answers decode into

    /// A page as admin answers one. Two columns carry non-null defaults (`SkillDraftResponse.kt:33` and the
    /// page wrapper) and every other absent key is Jackson dropping a null rather than a field the row lacks.
    func testAPageOfRowsDecodesFromTheEnvelope() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"pageNum":1,"pageSize":20,"total":2,"records": [\
        {"id":17,"name":"invoice-pdf-fill","status":"PENDING","upstreamFindingCount":1,"scanVerdict":"CAUTION",\
        "sourceSessionId":"sess-7","agentId":3,"createTime":"2026-10-05 09:00:00","updateTime":"2026-10-05 11:00:00"},\
        {"id":18,"name":"csv-clean","status":"REJECTED","upstreamFindingCount":0,\
        "reviewedBy":"admin","reviewedAt":"2026-10-05 12:00:00","rejectReason":"重复"}\
        ]},"timestamp":1790592457109,"isSuccess":true}
        """
        let page = try JSONDecoder().decode(
            Envelope<Page<SkillDraftRow>>.self, from: Data(envelope.utf8)
        ).data!

        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)
        XCTAssertTrue(page.records[0].isPending)
        XCTAssertTrue(page.records[0].isPatched)
        XCTAssertEqual(page.records[0].scanVerdict, "CAUTION")
        XCTAssertFalse(page.records[1].isPending)
        XCTAssertFalse(page.records[1].isPatched)
        XCTAssertEqual(page.records[1].rejectReason, "重复")
    }

    /// The detail's `resources` is a real JSON object, unlike a skill row's, where the same column is a *string*
    /// of one (`SkillDraftResponse.kt:87-88` against `SkillItem.swift:26-28`). Decoding it through the skill
    /// shape would fail on the object, and the two must not be conflated.
    func testADetailCarriesFilesAsAnObjectAndBothScanLists() throws {
        let detail = try JSONDecoder().decode(
            SkillDraftDetail.self,
            from: Data(
                """
                {"id":17,"name":"invoice-pdf-fill","status":"PENDING","skillmd":"# 用法",\
                "resources":{"templates/a.md":"body"},"scripts":[{"relPath":"scripts/run.sh","headPreview":"#!/bin/sh",\
                "totalLines":12,"sha256":"\(String(repeating: "ab", count: 32))"}],"scanVerdict":"DANGEROUS",\
                "scanFindings":["network call"],"localFindings":["rm -rf"],"contentDigest":"d-1",\
                "sourceSessionId":"sess-7","history":[{"action":"PROPOSE","actor":"agent",\
                "createTime":"2026-10-05 09:00:00"}]}
                """.utf8
            )
        )

        XCTAssertEqual(detail.resourceFiles.map { $0.path }, ["templates/a.md"])
        XCTAssertEqual(detail.scripts.map(\.relPath), ["scripts/run.sh"])
        XCTAssertEqual(detail.scripts[0].totalLines, 12)
        XCTAssertEqual(
            SkillDraftRules.truncated(detail.scripts[0].sha256, digits: 16),
            String(repeating: "ab", count: 8) + "…",
            "the scripts tab shows a hash head, and the full value stays copyable"
        )
        XCTAssertTrue(detail.hasLocalFindings, "harnax's own scan decides whether the promotion lands disabled")
        XCTAssertEqual(detail.scanFindings, ["network call"], "the sandbox report is display only")
        XCTAssertEqual(detail.contentDigest, "d-1")
        XCTAssertEqual(detail.history.first?.actor, "agent")
    }

    /// Empty lists arrive as empty lists — the content keys all carry server-side defaults
    /// (`SkillDraftResponse.kt:85-127`), so they are present rather than dropped.
    func testADetailWithNothingAttachedDecodesAsNothingAttached() throws {
        let detail = try JSONDecoder().decode(
            SkillDraftDetail.self,
            from: Data(
                """
                {"skillmd":"","resources":{},"scripts":[],"scanFindings":[],"localFindings":[],\
                "contentDigest":"d-2","history":[]}
                """.utf8
            )
        )
        XCTAssertFalse(detail.hasLocalFindings)
        XCTAssertTrue(detail.resourceFiles.isEmpty)
        XCTAssertTrue(detail.scripts.isEmpty)
        XCTAssertTrue(detail.history.isEmpty)
    }

    /// And the converse, which is the point of keeping them non-optional: a payload that leaves one out is a
    /// contract break, and the screen must fail loudly rather than render「no scan hits」over a scan the server
    /// never reported. An approval sent on a row whose digest was dropped would certify bytes nobody read.
    func testADetailMissingOneOfTheAlwaysShippedKeysIsRefused() {
        XCTAssertThrowsError(
            try JSONDecoder().decode(
                SkillDraftDetail.self,
                from: Data(#"{"skillmd":"","contentDigest":"d-2"}"#.utf8)
            ),
            "resources, scripts, both scans and the history are never absent on this route"
        )
        XCTAssertThrowsError(
            try JSONDecoder().decode(
                SkillDraftDetail.self,
                from: Data(#"{"skillmd":"","resources":{},"scripts":[],"scanFindings":[],"localFindings":[]}"#.utf8)
            ),
            "no digest means no approval can be built"
        )
    }

    /// The race arrives the way every other admin refusal does: **HTTP 200** with `code: 409` inside the
    /// envelope (`SkillDraftController.kt:113-117` returns `ResultVo.error(409, …)`), and `ResponseMapper`
    /// turns a non-200 envelope code into `APIError.business(code:)` (`ResponseMapper.swift:22-24`).
    func testTheNameRaceArrivesAsAFortyNine() async throws {
        let harness = APIHarness()
        try? await harness.signIn()
        harness.transport.enqueue(200, Wire.business(409, "That name was taken while the approval ran"))
        let result = await harness.agents.approve(
            id: 17, SkillDraftApprovePayload(expectedDigest: "d-1")
        )

        guard case .failure(let error) = result else {
            return XCTFail("a 409 must not decode as a decision: \(result)")
        }
        if case .business(let code, let message) = error {
            XCTAssertEqual(code, 409, "the detail screen has one branch keyed on this code")
            XCTAssertEqual(message, "That name was taken while the approval ran")
        } else {
            XCTFail("409 should map to a business error, got \(error)")
        }
    }

    /// A `code: 200` carrying `NAME_TAKEN` is the other channel, and it is the one the review acts on: the
    /// envelope says nothing went wrong, so the outcome has to survive decoding intact.
    func testARefusalInsideASuccessfulEnvelopeStillDecodes() async throws {
        let harness = APIHarness()
        try? await harness.signIn()
        harness.transport.enqueue(200, Wire.success("""
        {"outcome":"NAME_TAKEN","findings":[],"skillId":41,"reason":"invoice-pdf-fill is already installed"}
        """))
        let result = await harness.agents.approve(
            id: 17, SkillDraftApprovePayload(expectedDigest: "d-1")
        )

        guard case .success(let decision) = result else {
            return XCTFail("a NAME_TAKEN refusal rides a 200 envelope: \(result)")
        }
        XCTAssertEqual(decision.kind, .nameTaken)
        XCTAssertEqual(decision.skillId, 41, "the conflict modal links to whoever holds the name")
        XCTAssertNil(decision.currentDigest)
    }

    /// The approval goes out as JSON with the digest in the body, not in the path or the query — the service
    /// reads `@Valid @RequestBody` and would refuse a request that carried no digest at all
    /// (`SkillDraftServiceImpl.kt:274-276`).
    func testTheApprovalGoesOutAsOneJsonBody() async throws {
        let harness = APIHarness()
        try? await harness.signIn()
        harness.transport.enqueue(200, Wire.success(#"{"outcome":"PROMOTED","findings":[]}"#))
        _ = await harness.agents.approve(id: 17, SkillDraftApprovePayload(expectedDigest: "d-1"))

        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(headerValue(request, "Content-Type"), "application/json")
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        XCTAssertEqual(headerValue(request, "X-Tenant-ID"), "1")
        XCTAssertTrue(queryItems(of: try XCTUnwrap(request.url)).isEmpty, "no query on a body route")
        let body = try JSONDecoder().decode([String: String].self, from: try XCTUnwrap(request.httpBody))
        XCTAssertEqual(body, ["expectedDigest": "d-1"])
    }
}
