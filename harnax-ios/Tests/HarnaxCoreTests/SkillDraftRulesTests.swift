import XCTest

@testable import HarnaxCore

/// The predicates a review screen has to share with the service instead of re-deriving.
///
/// Two of these are load-bearing in a way a reader may not expect, and both are why this file exists rather
/// than the assertions living next to the views:
/// - the「已打补丁」marker decides whether a row warns the reviewer that the bytes under the digest moved
///   (`harnax-webui/src/pages/skill/drafts.tsx:102`), so its comparison has to be the same strict one;
/// - every length ceiling here is counted in **UTF-16 code units**, because that is what the server counts
///   (`String.length` in Kotlin) and what the console counts (JS `.length`), while Swift's `String.count`
///   counts grapheme clusters. Measuring with `count` would wave through input the service then refuses.
final class SkillDraftRulesTests: XCTestCase {
    /// A row shaped the way the queue answers one: `data` filled, null keys dropped the way admin's Jackson 3
    /// drops them (`harnax-admin/src/main/resources/application.yml:25`).
    private func row(_ body: String) throws -> SkillDraftRow {
        try JSONDecoder().decode(SkillDraftRow.self, from: Data(body.utf8))
    }

    /// A decision as the service answers one. `findings` is in every payload: the DTO declares it with an
    /// empty-list default, so it is never a key Jackson drops (`SkillDraftDecisionResponse.kt:33`).
    private func decision(_ body: String) throws -> SkillDraftDecision {
        try JSONDecoder().decode(SkillDraftDecision.self, from: Data(body.utf8))
    }

    // MARK: - the patched marker

    /// The queue warns on a patch and stays silent on a proposal nobody touched. Two stamps one second apart
    /// is a patch; two identical stamps are the same insert written twice.
    func testOnlyAnUpdateStrictlyAfterTheCreateMarksAPatch() {
        XCTAssertTrue(
            SkillDraftRules.isPatched(createTime: "2026-10-05 09:00:00", updateTime: "2026-10-05 09:00:01")
        )
        XCTAssertFalse(
            SkillDraftRules.isPatched(createTime: "2026-10-05 09:00:00", updateTime: "2026-10-05 09:00:00"),
            "equal stamps are one write, not a patch"
        )
        XCTAssertFalse(
            SkillDraftRules.isPatched(createTime: "2026-10-05 09:00:00", updateTime: "2026-10-05 08:59:59"),
            "a stamp before the create says nothing about a patch and must not warn"
        )
    }

    /// A row the server gave no first-proposal time cannot be said to have moved, and the console's own
    /// predicate requires both halves (`drafts.tsx:102`). Falling through to「patched」here would put a warning
    /// on every legacy row.
    func testAMissingHalfNeverMarksAPatch() {
        XCTAssertFalse(SkillDraftRules.isPatched(createTime: nil, updateTime: "2026-10-05 09:00:01"))
        XCTAssertFalse(SkillDraftRules.isPatched(createTime: "2026-10-05 09:00:00", updateTime: nil))
        XCTAssertFalse(SkillDraftRules.isPatched(createTime: "", updateTime: "2026-10-05 09:00:01"))
        XCTAssertFalse(SkillDraftRules.isPatched(createTime: "not a stamp", updateTime: "2026-10-05 09:00:01"))
    }

    /// Admin serialises `LocalDateTime` with the space pattern (`application.yml:23`) and every other stamp in
    /// the app is read with the `T` one, so the marker must not break if a route ever answers the other form.
    func testEitherStampFormOnTheWireParses() {
        XCTAssertTrue(
            SkillDraftRules.isPatched(createTime: "2026-10-05 09:00:00", updateTime: "2026-10-05T09:30:00")
        )
    }

    /// The row's own readers go through the same rule rather than a second copy of it, and a PENDING row whose
    /// update stamp moved reads as both open and patched — which is the combination the queue has to warn on.
    func testTheRowAsksTheSameRuleTheQueueDoes() throws {
        let moved = try row(#"""
        {"id":17,"name":"invoice-pdf-fill","status":"PENDING","upstreamFindingCount":2,
         "sourceSessionId":"sess-1","agentId":3,
         "createTime":"2026-10-05 09:00:00","updateTime":"2026-10-05 11:00:00"}
        """#)
        XCTAssertEqual(moved.title, "invoice-pdf-fill")
        XCTAssertTrue(moved.isPending)
        XCTAssertTrue(moved.isPatched)
        XCTAssertEqual(moved.upstreamFindingCount, 2)

        let fresh = try row(#"""
        {"id":18,"name":"csv-clean","status":"PENDING","upstreamFindingCount":0,
         "createTime":"2026-10-05 09:00:00","updateTime":"2026-10-05 09:00:00"}
        """#)
        XCTAssertFalse(fresh.isPatched)
    }

    /// A decided row carries the three review columns and no longer reads as open. `REJECTED` is both a status
    /// and an outcome name, and only the status column reaches here.
    func testAReviewedRowIsNotPending() throws {
        let decided = try row(#"""
        {"id":19,"name":"csv-clean","status":"REJECTED","upstreamFindingCount":0,
         "reviewedBy":"admin","reviewedAt":"2026-10-05 12:00:00","rejectReason":"重复"}
        """#)
        XCTAssertFalse(decided.isPending)
        XCTAssertEqual(decided.reviewedBy, "admin")
        XCTAssertEqual(decided.rejectReason, "重复")
    }

    // MARK: - the UTF-16 ceilings

    /// 512 is admissible and 513 is not, because the service compares with `>`
    /// (`SkillDraftServiceImpl.kt:372`), not `>=`. A client that refused 512 would make a legal rejection
    /// impossible to submit.
    func testTheRejectReasonCeilingIsFiftyTwelveInclusive() {
        XCTAssertTrue(SkillDraftRules.isRejectReasonAdmissible(String(repeating: "a", count: 512)))
        XCTAssertFalse(SkillDraftRules.isRejectReasonAdmissible(String(repeating: "a", count: 513)))
        XCTAssertEqual(SkillDraftRules.maxRejectReasonLength, 512)
    }

    /// The server trims before it measures (`SkillDraftServiceImpl.kt:370`), so padding costs nothing and a
    /// reason that is only padding is the「a rejection needs a reason」refusal rather than a stored blank.
    func testPaddingAroundAReasonIsNotPartOfItsLength() {
        XCTAssertTrue(SkillDraftRules.isRejectReasonAdmissible("   理由不够充分   "))
        XCTAssertFalse(SkillDraftRules.isRejectReasonAdmissible("   "))
        XCTAssertFalse(SkillDraftRules.isRejectReasonAdmissible(""))
        XCTAssertEqual(SkillDraftRules.trimmedWireLength("  ab  "), 2)
    }

    /// The whole reason this rule exists instead of `text.count`. One emoji is one grapheme cluster and two
    /// UTF-16 units, so a reason of 260 emoji reads as 260 characters to Swift and as 520 to the service —
    /// `count` would send a body the server answers with a refusal and a round trip.
    func testOneEmojiCostsTwoUnitsHereAndOnTheServer() {
        let emoji = "👍"
        XCTAssertEqual(emoji.count, 1, "Swift counts grapheme clusters")
        XCTAssertEqual(SkillDraftRules.wireLength(emoji), 2, "Kotlin and JS count UTF-16 code units")

        XCTAssertTrue(SkillDraftRules.isRejectReasonAdmissible(String(repeating: emoji, count: 256)))
        XCTAssertFalse(
            SkillDraftRules.isRejectReasonAdmissible(String(repeating: emoji, count: 257)),
            "257 emoji are 514 units; a `count`-based check would have waved this through"
        )
    }

    /// Chinese is one unit per character, and the reason field is mostly written in Chinese, so the ceiling has
    /// to be reached by the same arithmetic as an ASCII reason.
    func testChineseCharactersCostOneUnitEach() {
        XCTAssertEqual(SkillDraftRules.wireLength("这是驳回理由"), 6)
        XCTAssertTrue(SkillDraftRules.isRejectReasonAdmissible(String(repeating: "分", count: 512)))
    }

    /// A rename has to fit the column the promoted row grows into, so the ceiling is the installer's own
    /// (`SkillInstaller.kt:330`) rather than the draft table's.
    func testARenameCeilingIsAHundredInclusive() {
        XCTAssertTrue(SkillDraftRules.isRenameAdmissible(String(repeating: "s", count: 100)))
        XCTAssertFalse(SkillDraftRules.isRenameAdmissible(String(repeating: "s", count: 101)))
        XCTAssertFalse(SkillDraftRules.isRenameAdmissible("  "))
        XCTAssertEqual(SkillDraftRules.maxSkillNameLength, 100)
    }

    /// Validated untrimmed, submitted trimmed — the console's order (`draftDetail.tsx:524-551`), and the reason
    /// a form can show an error on a value the user is still typing without rejecting the final one.
    func testRenameValidationIgnoresTrailingSpaceItHasNotYetStripped() {
        XCTAssertTrue(SkillDraftRules.isRenameAdmissible("invoice-pdf-fill "))
    }

    // MARK: - digest display forms

    /// 12 characters for the pending banner, 16 for a replacement digest and for a script hash
    /// (`draftDetail.tsx:399`/`:170`/`:582`). A digest exactly as long as the window must not gain an ellipsis:
    /// the truncation marker is a claim that bytes were dropped.
    func testTruncationKeepsTheHeadAndMarksOnlyWhatItDropped() {
        let digest = String(repeating: "ab", count: 32)
        XCTAssertEqual(digest.count, 64)
        XCTAssertEqual(SkillDraftRules.truncated(digest, digits: 12), String(digest.prefix(12)) + "…")
        XCTAssertEqual(SkillDraftRules.truncated(digest, digits: 16), String(digest.prefix(16)) + "…")

        let exact = String(repeating: "0", count: 12)
        XCTAssertEqual(SkillDraftRules.truncated(exact, digits: 12), exact, "nothing was dropped")

        let short = "abc123"
        XCTAssertEqual(SkillDraftRules.truncated(short, digits: 12), short)
    }

    // MARK: - what a decision means

    /// `PROMOTED` says a row was written; only `skillStatus == 1` says it is live. The console tests the same
    /// thing on exactly 1 (`draftDetail.tsx:99-127`), and a scan-held promotion arrives as 0.
    func testOnlyStatusOneOnAPromotionReadsAsEnabled() {
        XCTAssertTrue(SkillDraftRules.isPromotedEnabled(1))
        XCTAssertFalse(SkillDraftRules.isPromotedEnabled(0), "held back by the content scan")
        XCTAssertFalse(SkillDraftRules.isPromotedEnabled(nil))
        XCTAssertFalse(SkillDraftRules.isPromotedEnabled(2))
    }

    /// The five outcomes the contract has, each reached from the raw string the server sends, plus the
    /// fall-through. A sixth value keeps its text rather than collapsing into a known arm: a refusal the screen
    /// does not know how to undo must still read as a refusal, and the console's own union type would have
    /// silently taken the default branch (`harnax-webui/src/typings.d.ts:647`).
    func testAnOutcomeMapsOrKeepsItsOwnName() throws {
        for (raw, kind) in [
            ("PROMOTED", SkillDraftOutcome.promoted),
            ("REJECTED", .rejected),
            ("DRAFT_CHANGED", .draftChanged),
            ("ALREADY_REVIEWED", .alreadyReviewed),
            ("NAME_TAKEN", .nameTaken),
        ] {
            XCTAssertEqual(
                try decision(#"{"outcome":"\#(raw)","findings":[]}"#).kind,
                kind,
                raw
            )
        }

        let future = try decision(#"{"outcome":"PARKED","findings":[],"reason":"held"}"#)
        XCTAssertEqual(future.kind, .unknown(raw: "PARKED"))
        XCTAssertEqual(future.reason, "held")
    }

    /// The two columns the `PROMOTED` branch reads to decide what to say: a skill id and whether the row it
    /// promoted is live. Both are optional on the wire, so a scan-held promotion may carry a `0` or nothing.
    func testAPromotionCarriesItsRowAndItsLiveFlag() throws {
        let live = try decision(#"{"outcome":"PROMOTED","skillId":41,"skillStatus":1,"promotedName":"csv-clean","findings":[]}"#)
        XCTAssertEqual(live.skillId, 41)
        XCTAssertTrue(SkillDraftRules.isPromotedEnabled(live.skillStatus))

        let held = try decision(
            #"{"outcome":"PROMOTED","skillId":42,"skillStatus":0,"findings":["rm -rf","curl | sh"]}"#
        )
        XCTAssertFalse(SkillDraftRules.isPromotedEnabled(held.skillStatus))
        XCTAssertEqual(held.findings.count, 2, "the warning quotes the scan hit count")
    }

    // MARK: - the queue's arms

    /// Three arms, no「show me everything」, and PENDING first: the console's filter is exactly this
    /// (`drafts.tsx:193-205`) and it opens on the list a reviewer has a reason to ask for. A fourth arm here
    /// would be a status the service refuses to filter by — `EXPIRED` is documented on the column but written
    /// by no code (`SkillDraftServiceImpl.kt:570-577`).
    func testTheStatusFilterIsThreeArmsWithPendingFirst() {
        XCTAssertEqual(SkillDraftStatus.allCases, [.pending, .approved, .rejected])
        XCTAssertEqual(SkillDraftStatus.pending.titleKey, "skill.draft.status.PENDING")
        XCTAssertEqual(SkillDraftStatus.pending.rawValue, "PENDING")
    }

    // MARK: - provenance

    /// The value is the entity's own constant (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt:13`),
    /// spelled once here so a skill row and a review screen cannot disagree about what「agent written」is.
    func testTheAgentPromotedOriginIsTheServersConstant() {
        XCTAssertEqual(SkillDraftRules.agentPromotedOrigin, "agent_promoted")
    }
}
