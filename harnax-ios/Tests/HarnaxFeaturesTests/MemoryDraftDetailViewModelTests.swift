import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// One memory candidate, the two writes it accepts, and the six answers those writes can come back with.
///
/// The shape worth guarding is that **a refusal is not an error here**. `approve` and `reject` answer `code: 200`
/// with an `outcome` that says what actually happened, and four of the five outcomes each carry one extra field
/// that is the whole remedy (`MemoryDraftDecisionResponse.kt:9-19`): `DRAFT_CHANGED` carries the new digest,
/// `ALREADY_REVIEWED` carries who decided, `STALE_BASE` carries the version the layer reached and the file that
/// moved. Branching on the server's `reason` sentence instead of on `kind` would be the bug, because that field
/// is explicitly not a contract (`:44-45`).
///
/// The one refusal keyed on an envelope code is `409`, and it is the write that lost the race: the candidate is
/// still pending, so re-reading it — which also replaces the digest the failed approval sent — is the whole fix
/// (`MemoryDraftController.kt:106-122`).
///
/// Every decision reply is followed by one more detail reply in the fixtures, because `apply(_:)` re-reads rather
/// than editing the row it has (`draftDetail.tsx:176-260` calls `load()` once, after the switch).
@MainActor
final class MemoryDraftDetailViewModelTests: XCTestCase {
    private static let digest = String(repeating: "a", count: 64)

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }

    /// A loaded pending candidate. `extraDetails` are the replies the re-reads after a decision will draw.
    private func loaded(
        id: Int64 = 7,
        _ fields: [String: Any] = [:],
        extraDetails: [Result<MemoryDraftDetail, APIError>] = []
    ) async throws -> (MemoryDraftDetailViewModel, FakeMemoryDrafts) {
        let drafts = FakeMemoryDrafts()
        drafts.detailReplies = [.success(try MemoryDraftDetail.stub(fields))] + extraDetails
        let vm = MemoryDraftDetailViewModel(id: id, catalog: drafts)
        await vm.load()
        return (vm, drafts)
    }

    // MARK: - 4. the proposal, not the store

    func testTheDetailReadsTheWholeProposalAndCountsItTheWayTheQueueDoes() async throws {
        let (vm, drafts) = try await loaded([
            "baseMd": "# 已有\n\n用户偏好中文。",
            "baseVersion": 4,
            "sources": [
                ["path": "memory/sess-1.md", "content": "一"],
                ["path": "memory/sess-2.md", "content": "二"],
            ],
            "targets": [["path": "memory/2026-10-08.md", "expectedVersion": 0]],
        ])
        XCTAssertEqual(drafts.detailRequests, [7])
        XCTAssertEqual(vm.phase, .ready)
        XCTAssertEqual(vm.mergedMarkdown, "# 用户偏好\n\n中文优先。")
        XCTAssertEqual(vm.baseMarkdown, "# 已有\n\n用户偏好中文。")
        XCTAssertEqual(vm.draft?.baseVersion, 4)
        XCTAssertEqual(vm.draft?.sources.count, 2, "what the merge took out, in its own card")
        XCTAssertEqual(vm.dailyTargetCount, 1, "what the same approval writes in, in another")
        XCTAssertEqual(vm.mergedCharCount, MemoryDraftRules.wireLength("# 用户偏好\n\n中文优先。"))
        XCTAssertEqual(vm.baseCharCount, MemoryDraftRules.wireLength("# 已有\n\n用户偏好中文。"))
        XCTAssertEqual(vm.isDecidable, true)
    }

    /// A candidate whose owner had no long-term layer at all: `baseVersion == 0` and no text. Blank and absent
    /// are the same pane here, exactly as the console's trimmed test makes them
    /// (`draftDetail.tsx:424-450`).
    func testABlankBaseIsTheSameNoTextAsAnAbsentOne() async throws {
        let (blank, _) = try await loaded(["baseMd": "   ", "baseVersion": 0])
        XCTAssertNil(blank.baseMarkdown)
        XCTAssertEqual(blank.baseCharCount, 0)
        XCTAssertEqual(blank.draft?.baseVersion, 0, "「no layer yet」, which the view prints rather than a bare v0")

        let (absent, _) = try await loaded(["baseVersion": 0])
        XCTAssertNil(absent.draft?.baseMd)
        XCTAssertNil(absent.baseMarkdown)

        let (empty, _) = try await loaded(["mergedMd": ""])
        XCTAssertNil(empty.mergedMarkdown, "a merge that produced nothing has nothing to approve")
    }

    func testThePatchedAndDecidedReadsComeOffTheDetailItself() async throws {
        let (pending, _) = try await loaded([
            "createTime": "2026-10-05 09:00:00",
            "updateTime": "2026-10-05 11:00:00",
        ])
        XCTAssertTrue(
            SkillDraftRules.isPatched(
                createTime: pending.draft?.createTime,
                updateTime: pending.draft?.updateTime
            ),
            "the same rule `MemoryDraftRow.patched` uses, so a row and its candidate cannot disagree"
        )
        XCTAssertNil(pending.decidedSummary, "a pending candidate has no decision to report")

        let (rejected, _) = try await loaded([
            "status": MemoryDraftStatus.rejected.rawValue,
            "reviewedBy": "admin",
            "reviewedAt": "2026-10-08 09:12:33",
            "rejectReason": "这批材料已经并进去了",
        ])
        XCTAssertEqual(rejected.decidedSummary?.statusKey, "memory.draft.status.REJECTED")
        XCTAssertEqual(rejected.decidedSummary?.by, "admin")
        XCTAssertEqual(rejected.decidedSummary?.at, "2026-10-08 09:12")
        XCTAssertEqual(rejected.decidedSummary?.reason, "这批材料已经并进去了")
        XCTAssertFalse(rejected.isDecidable, "the two buttons belong on a pending candidate and on nothing else")

        /// An approval blanks the reason column server-side, but the row is what it is once it arrives: a stale
        /// value must not turn an approved candidate's report into a refusal's (`draftDetail.tsx:582`).
        let (approved, _) = try await loaded([
            "status": MemoryDraftStatus.approved.rawValue,
            "reviewedBy": "admin",
            "rejectReason": "留下的旧值",
        ])
        XCTAssertNil(approved.decidedSummary?.reason)
        XCTAssertEqual(approved.decidedSummary?.statusKey, "memory.draft.status.APPROVED")
    }

    // MARK: - 5. the approval sends what is on screen

    func testTheApprovalSendsTheDigestOnScreenAndNothingElse() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub())]
        await vm.approve()
        XCTAssertTrue(vm.isConfirmingApprove, "one button replaces the text every later conversation is told")
        XCTAssertTrue(drafts.approveRequests.isEmpty, "opening the confirmation puts nothing on the wire")

        await vm.confirmApprove()
        XCTAssertEqual(drafts.approveRequests.count, 1)
        XCTAssertEqual(drafts.approveRequests.first?.id, 7)
        XCTAssertEqual(drafts.approveRequests.first?.payload.expectedDigest, Self.digest)
        XCTAssertFalse(vm.isConfirmingApprove)
        XCTAssertEqual(drafts.detailRequests.count, 2, "and the receipt is followed by a re-read")
    }

    /// There is no second attempt field on a memory approval — no name, no target to choose
    /// (`MemoryDraftApproveRequest.kt:18-25`) — so the payload type itself has exactly one key, and a raced
    /// approval is answered by re-reading rather than by retrying with an edited copy.
    func testAnApprovalPayloadCarriesOnlyTheDigest() throws {
        let payload = MemoryDraftApprovePayload(expectedDigest: "d-1")
        let data = try JSONEncoder().encode(payload)
        let keys = try JSONDecoder().decode([String: String].self, from: data).keys
        XCTAssertEqual(Set(keys), ["expectedDigest"])
    }

    func testCancelingTheConfirmationSendsNothing() async throws {
        let (vm, drafts) = try await loaded()
        await vm.approve()
        vm.cancelApproval()
        XCTAssertFalse(vm.isConfirmingApprove)
        XCTAssertTrue(drafts.approveRequests.isEmpty)
        XCTAssertEqual(drafts.detailRequests.count, 1, "a canceled question is not a re-read")
    }

    /// The guard runs **before** the confirmation opens: asking a reviewer to approve a candidate that has no
    /// digest, then refusing them the answer to their yes, is worse than refusing them the question
    /// (`draftDetail.tsx:291-301`).
    func testADigestlessCandidateIsRefusedLocallyAndReRead() async throws {
        let (vm, drafts) = try await loaded(
            ["contentDigest": ""],
            extraDetails: [.success(try MemoryDraftDetail.stub())]
        )
        XCTAssertNil(vm.expectedDigest)
        XCTAssertNil(vm.digestPreview)
        XCTAssertTrue(drafts.approveRequests.isEmpty)

        await vm.approve()
        XCTAssertEqual(vm.notice, .refused(message: hx("memory.draft.digest.missing")))
        XCTAssertFalse(vm.isConfirmingApprove, "the sheet never opened, so there is nothing to dismiss")
        XCTAssertEqual(drafts.detailRequests.count, 2)
    }

    /// One tap must not put a second approval on the wire: an approval rewrites the whole long-term layer, and a
    /// double would be a double version bump.
    func testASecondTapWhileTheFirstIsOnTheWireIsIgnored() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.gateWrites = true
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub())]

        Task { await vm.confirmApprove() }
        try await waitUntil { vm.isActing && drafts.approveRequests.count == 1 }
        await vm.confirmApprove()
        XCTAssertTrue(vm.isActing)

        drafts.releaseWrites()
        try await waitUntil { !vm.isActing }
        XCTAssertEqual(drafts.approveRequests.count, 1, "the second tap never reached the wire")
    }

    // MARK: - 6. every refusal is a branch, and every branch arrives with code 200

    func testAnApprovalReceiptCarriesItsOwnNumbers() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "APPROVED",
            "longTermVersion": 12,
            "clearedSources": 3,
            "keptSources": 1,
            "absentSources": 2,
            "dailyTargetsApplied": 4,
            "reason": "Merged into v12",
        ]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .approved(longTermVersion: 12, cleared: 3, kept: 1, absent: 2, dailyApplied: 4))
        XCTAssertEqual(vm.notice?.tone, .success)
        XCTAssertEqual(vm.notice?.longTermVersionText, "12")
        XCTAssertEqual(drafts.detailRequests.count, 2)
    }

    /// The version is nullable on the DTO, so a receipt without one prints the console's `-`
    /// (`draftDetail.tsx:112`) rather than inventing a `0` the store never had.
    func testAnApprovalReceiptWithoutAVersionPrintsADash() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub(["outcome": "APPROVED"]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice?.longTermVersionText, "-")
    }

    func testADraftChangedRefusalOffersTheNewDigest() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "DRAFT_CHANGED",
            "currentDigest": "d-2",
            // Server prose, and explicitly not a contract: the branch must not read it.
            "reason": "The conversation merged again",
        ]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .draftChanged(digest: "d-2"))
        XCTAssertEqual(vm.notice?.tone, .warning, "the same candidate is still decidable, just not against this text")
    }

    func testAnAlreadyReviewedRefusalSaysWhoAndWhy() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.rejectReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "ALREADY_REVIEWED",
            "reviewedBy": "admin",
            "reviewedAt": "2026-10-08 09:12:33",
            "rejectReason": "材料重复",
        ]))]
        vm.startRejection()
        vm.rejectReason = "先看过了"
        await vm.submitRejection()
        XCTAssertEqual(
            vm.notice,
            .alreadyReviewed(by: "admin", at: "2026-10-08 09:12", reason: "材料重复")
        )
        XCTAssertEqual(vm.notice?.tone, .info)
    }

    /// Both halves of the who/when pair are nullable, and a blank there would read as no decision at all, so the
    /// branch fills the console's `-` (`MemoryDraftDecisionResponse.kt:56-57`).
    func testAnAlreadyReviewedWithNoNameToPrintStillNamesADecision() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub(["outcome": "ALREADY_REVIEWED"]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .alreadyReviewed(by: "-", at: "-", reason: nil))
    }

    func testAStaleBaseRefusalNamesTheFileThatMoved() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "STALE_BASE",
            "currentBaseVersion": 9,
            "staleTarget": "memory/2026-10-08.md",
        ]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .staleBase(currentBaseVersion: 9, staleTarget: "memory/2026-10-08.md"))
        XCTAssertEqual(
            vm.notice?.tone,
            .error,
            "this decision can no longer be made on this row at all (`draftDetail.tsx:214-236`)"
        )
    }

    /// A sixth outcome is not one of the five sentences and not a crash either: it says the decision was refused
    /// and carries the token verbatim.
    func testAnOutcomeTheContractDoesNotHaveReadsAsARefusalAndKeepsItsToken() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "MEMORY_LOCKED",
            "reason": "The text changed",
        ]))]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .unrecognised(raw: "MEMORY_LOCKED"))
        XCTAssertNotEqual(vm.notice, .draftChanged(digest: nil), "the branch is keyed on `kind`, never on `reason`")
        XCTAssertEqual(vm.notice?.tone, .error)
    }

    func testTheRejectionSuccessReportsNothingWasWritten() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.rejectReplies = [.success(try MemoryDraftDecision.stub([
            "outcome": "REJECTED",
            "reviewedBy": "admin",
            "reviewedAt": "2026-10-08 09:12:33",
        ]))]
        vm.startRejection()
        vm.rejectReason = "这批材料已经并进去了"
        await vm.submitRejection()
        XCTAssertEqual(vm.notice, .rejectionRecorded)
        XCTAssertEqual(vm.notice?.tone, .success)
        XCTAssertFalse(vm.isRejectOpen, "a recorded rejection closes the sheet")
        XCTAssertTrue(vm.rejectReason.isEmpty, "and the form does not keep the words it already sent")
        XCTAssertEqual(drafts.detailRequests.count, 2)
    }

    // MARK: - 7. the reason is validated before it costs a round trip

    func testABlankReasonNeverCostsARoundTrip() async throws {
        let (vm, drafts) = try await loaded()
        vm.startRejection()
        XCTAssertTrue(vm.isRejectOpen)
        for blank in ["", "   ", "\n\t "] {
            vm.rejectReason = blank
            await vm.submitRejection()
            XCTAssertTrue(drafts.rejectRequests.isEmpty, "the predicate is local (`MemoryDraftServiceImpl.kt:319-320`)")
            XCTAssertEqual(vm.rejectRefusal, hx("memory.draft.reject.reasonRequired"))
            XCTAssertTrue(vm.isRejectOpen, "the sheet stays up holding what the reviewer typed")
            XCTAssertEqual(drafts.detailRequests.count, 1, "and nothing re-reads either")
        }
    }

    /// Exactly 512 is legal because the service compares with `>` (`MemoryDraftServiceImpl.kt:321`), and the
    /// count is UTF-16 units because that is what Kotlin's `String.length` counts (`:322`) — so the two apps
    /// cannot disagree about where the limit sits.
    func testTheReasonLimitIsFiveHundredAndTwelveUTF16Units() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.rejectReplies = [.success(try MemoryDraftDecision.stub(["outcome": "REJECTED"]))]
        vm.startRejection()

        vm.rejectReason = String(repeating: "a", count: MemoryDraftRules.maxRejectReasonLength)
        XCTAssertEqual(MemoryDraftRules.wireLength(vm.rejectReason), 512)
        await vm.submitRejection()
        XCTAssertEqual(drafts.rejectRequests.count, 1, "512 units is admissible, not over the line")

        vm.startRejection()
        vm.rejectReason = String(repeating: "a", count: MemoryDraftRules.maxRejectReasonLength + 1)
        await vm.submitRejection()
        XCTAssertEqual(
            drafts.rejectRequests.count,
            1,
            "513 units is refused here rather than on the wire (`draftDetail.tsx:635`)"
        )
        XCTAssertEqual(vm.rejectRefusal, hx("memory.draft.reject.tooLong", MemoryDraftRules.maxRejectReasonLength))
        XCTAssertTrue(vm.isRejectOpen)
    }

    /// A grapheme is not a UTF-16 unit: 256 astral emoji are exactly 512 units, one more is 514 and must be
    /// refused by the same predicate the server would apply. Swift's `count` would call both 256 and 257.
    func testAstralCharactersAreCountedAsUnitsNotAsGraphemes() async throws {
        let legal = String(repeating: "🙂", count: 256)
        XCTAssertEqual(legal.count, 256, "graphemes")
        XCTAssertEqual(MemoryDraftRules.wireLength(legal), 512, "and the server's measure")
        XCTAssertTrue(MemoryDraftRules.isRejectReasonAdmissible(legal))

        let over = String(repeating: "🙂", count: 257)
        XCTAssertEqual(MemoryDraftRules.wireLength(over), 514)
        XCTAssertFalse(MemoryDraftRules.isRejectReasonAdmissible(over))

        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.rejectReplies = [.success(try MemoryDraftDecision.stub(["outcome": "REJECTED"]))]
        vm.startRejection()
        vm.rejectReason = legal
        await vm.submitRejection()
        XCTAssertEqual(drafts.rejectRequests.count, 1)
    }

    /// The trimmed text is what is stored and shown later (`draftDetail.tsx:348`), so the form validates the
    /// trim and submits the trim.
    func testTheReasonGoesOutTrimmed() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        drafts.rejectReplies = [.success(try MemoryDraftDecision.stub(["outcome": "REJECTED"]))]
        vm.startRejection()
        vm.rejectReason = "  这批材料已经并进去了  "
        await vm.submitRejection()
        XCTAssertEqual(drafts.rejectRequests.count, 1)
        XCTAssertEqual(drafts.rejectRequests.first?.payload.reason, "这批材料已经并进去了")
        XCTAssertEqual(drafts.rejectRequests.first?.id, 7)
    }

    /// A server-side rejection refusal leaves the modal up with what the reviewer typed and puts the server's
    /// sentence *inside* it — the sheet covers the banner, so writing to the banner alone would report a refusal
    /// nobody can see (`draftDetail.tsx:604-663`).
    func testAServerRefusalKeepsTheSheetAndItsText() async throws {
        let (vm, drafts) = try await loaded()
        drafts.rejectReplies = [.failure(.business(code: 400, message: "Reason too long"))]
        vm.startRejection()
        vm.rejectReason = "太长了吧"
        await vm.submitRejection()
        XCTAssertTrue(vm.isRejectOpen)
        XCTAssertEqual(vm.rejectReason, "太长了吧", "the reviewer's words survive the refusal")
        XCTAssertEqual(vm.rejectRefusal, ErrorMessage.text(for: .business(code: 400, message: "Reason too long")))
        XCTAssertNil(vm.notice, "and nothing competes with the banner the sheet covers")
        XCTAssertEqual(drafts.detailRequests.count, 1, "an error envelope does not re-read")
        XCTAssertFalse(vm.isActing)
    }

    func testClosingTheSheetClearsTheForm() async throws {
        let (vm, drafts) = try await loaded()
        vm.startRejection()
        vm.rejectReason = "写了一半"
        vm.closeRejection()
        XCTAssertFalse(vm.isRejectOpen)
        XCTAssertTrue(vm.rejectReason.isEmpty)
        XCTAssertNil(vm.rejectRefusal)
        XCTAssertTrue(drafts.rejectRequests.isEmpty)
    }

    // MARK: - the one refusal keyed on an envelope code

    func testAFortyNineMeansTheWriteLostTheRaceSoTheCandidateIsReRead() async throws {
        let reloaded = try MemoryDraftDetail.stub(["contentDigest": "d-9"])
        let (vm, drafts) = try await loaded(extraDetails: [.success(reloaded)])
        drafts.approveReplies = [.failure(.business(code: 409, message: "The write lost the race"))]
        await vm.approve()
        await vm.confirmApprove()

        if case let .refused(message) = vm.notice {} else {
            XCTFail("a 409 is a refusal the screen reports, not a silence: \(String(describing: vm.notice))")
        }
        XCTAssertEqual(drafts.detailRequests.count, 2, "re-reading is the whole remedy")
        XCTAssertEqual(vm.expectedDigest, "d-9", "and the re-read replaces the digest this approval could not send")
        XCTAssertTrue(drafts.approveRequests.count == 1, "no silent retry")
    }

    /// After a decision, a re-read that fails must not hide the candidate the reviewer was just looking at: the
    /// screen keeps what was readable and says out loud that the refresh failed.
    func testAFailedReReadAfterADecisionKeepsTheCandidateAndSaysSo() async throws {
        let (vm, drafts) = try await loaded(extraDetails: [.failure(.timeout)])
        drafts.approveReplies = [.success(try MemoryDraftDecision.stub())]
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.phase, .ready)
        XCTAssertNotNil(vm.draft)
        XCTAssertEqual(vm.notice, .refused(message: ErrorMessage.text(for: .timeout)))
    }

    /// A first read that fails is the screen, not a banner.
    func testAFailedFirstReadIsTheWholeScreen() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.detailReplies = [.failure(.offline)]
        let vm = MemoryDraftDetailViewModel(id: 3, catalog: drafts)
        await vm.load()
        if case .failed = vm.phase {} else { XCTFail("expected a failure, got \(vm.phase)") }
        XCTAssertNil(vm.draft)
        XCTAssertFalse(vm.isDecidable)
    }

    /// A re-read that succeeds twice out of order must not let the older answer win the text the next approval
    /// sends.
    func testTheLastReReadWinsTheDigest() async throws {
        let drafts = FakeMemoryDrafts()
        drafts.detailReplies = [
            .success(try MemoryDraftDetail.stub(["contentDigest": "d-1"])),
            .success(try MemoryDraftDetail.stub(["contentDigest": "d-2"])),
        ]
        let vm = MemoryDraftDetailViewModel(id: 3, catalog: drafts)
        await vm.load()
        XCTAssertEqual(vm.expectedDigest, "d-1")
        await vm.load()
        XCTAssertEqual(vm.expectedDigest, "d-2")
    }

    // MARK: - the queue hand-off

    /// The skill queue's defect, reported 2026-10-11 and reproduced by this screen's identical wiring: a pushed
    /// detail is not re-mounted on the way back, so the queue's `.task` guarded on `.loading` never fires a
    /// second time and a decided candidate stays on screen. Every outcome branch ends in a re-read of the row,
    /// so every one of them owes a re-read of the queue too — including the three that keep the candidate
    /// pending, whose bylines the queue prints.
    func testEveryDecisionOutcomeAsksTheQueueToReload() async throws {
        let outcomes: [[String: Any]] = [
            ["outcome": "APPROVED"],
            ["outcome": "REJECTED"],
            ["outcome": "ALREADY_REVIEWED"],
            ["outcome": "DRAFT_CHANGED", "currentDigest": "d-2"],
            ["outcome": "STALE_BASE", "currentBaseVersion": 9, "staleTarget": "memory/2026-10-08.md"],
        ]
        for outcome in outcomes {
            let (vm, drafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
            drafts.approveReplies = [.success(try MemoryDraftDecision.stub(outcome))]
            await vm.approve()
            await vm.confirmApprove()
            XCTAssertEqual(drafts.detailRequests.count, 2, "\(outcome): the detail re-reads")
            XCTAssertTrue(vm.queueNeedsReload, "\(outcome): the queue has to catch up with the same row")
        }
    }

    /// The 409 race is the case a blanket "reload after any write" would get wrong: the candidate is still
    /// pending, the detail re-reads to replace the digest the failed approval sent, and the queue's rows are
    /// already right.
    func testARefusedWriteLeavesTheQueueAlone() async throws {
        let (rolled, rolledDrafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        rolledDrafts.approveReplies = [.failure(.business(code: 409, message: "该候选已被审核"))]
        await rolled.approve()
        await rolled.confirmApprove()
        XCTAssertEqual(rolledDrafts.detailRequests.count, 2, "the digest this attempt sent is replaced")
        XCTAssertFalse(rolled.queueNeedsReload, "…and nothing moved out of the arm")

        let (refused, refusedDrafts) = try await loaded(extraDetails: [.success(try MemoryDraftDetail.stub())])
        refusedDrafts.approveReplies = [.failure(.business(code: 400, message: "只有待审候选可以被批准"))]
        await refused.approve()
        await refused.confirmApprove()
        XCTAssertEqual(refused.notice, .refused(message: "只有待审候选可以被批准"))
        XCTAssertEqual(refusedDrafts.detailRequests.count, 1, "an error envelope re-reads nothing")
        XCTAssertFalse(refused.queueNeedsReload)
    }
}
