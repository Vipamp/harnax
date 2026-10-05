import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The reviewer's decision screen, seen through the six answers the server can give.
///
/// §5.4 of `specs/07-skill-draft-review.md` is a table of outcomes, and three of its rows are load-bearing in
/// ways a passing tap cannot reveal: `NAME_TAKEN` returns **without** re-reading, because the conflict dialog
/// has to keep the name this attempt tried to land and a reload would overwrite the draft the reviewer is
/// mid-decision on (`harnax-webui/src/pages/skill/draftDetail.tsx:179-184`); an error envelope does not reload
/// either, with `code: 409` as the one named exception (`SkillDraftController.kt:115-117`); and a draft with no
/// digest refuses locally rather than paying for a round trip the service would only send back.
@MainActor
final class SkillDraftDetailViewModelTests: XCTestCase {
    private let digest = String(repeating: "a", count: 64)

    /// A pending draft with a digest, which is the only shape the decision buttons appear on.
    private func loaded(
        skills: (any SkillCataloging)? = FakeSkills(),
        _ fields: [String: Any] = [:]
    ) async throws -> (SkillDraftDetailViewModel, FakeSkillDrafts) {
        let drafts = FakeSkillDrafts()
        var detail: [String: Any] = ["id": 1, "contentDigest": digest]
        for (key, value) in fields { detail[key] = value }
        drafts.detailReplies = [.success(try SkillDraftDetail.stub(detail))]
        let vm = SkillDraftDetailViewModel(id: 1, drafts: drafts, skills: skills)
        await vm.load()
        return (vm, drafts)
    }

    /// The re-read that every outcome branch except `NAME_TAKEN` owes the screen. Queued explicitly, because a
    /// reload nobody scripted answers as a decoding failure and still shows up in the call log.
    private func queueReload(_ drafts: FakeSkillDrafts, _ fields: [String: Any] = [:]) throws {
        drafts.detailReplies = [.success(try SkillDraftDetail.stub(fields))]
    }

    private func promoted(_ fields: [String: Any] = [:]) throws -> SkillDraftDecision {
        var decision: [String: Any] = ["skillId": 9, "skillStatus": 1, "promotedName": "周报汇总"]
        for (key, value) in fields { decision[key] = value }
        return try SkillDraftDecision.stub(decision)
    }

    // MARK: - the two reads

    func testTheDetailOpensLoadingAndLandsReady() async throws {
        let drafts = FakeSkillDrafts()
        let vm = SkillDraftDetailViewModel(id: 1, drafts: drafts)
        XCTAssertEqual(vm.phase, .loading)
        drafts.detailReplies = [.success(try SkillDraftDetail.stub(["contentDigest": digest]))]
        await vm.load()
        XCTAssertEqual(vm.phase, .ready)
        XCTAssertEqual(drafts.detailRequests, [1])
        XCTAssertEqual(vm.expectedDigest, digest)
        XCTAssertEqual(vm.digestPreview, "aaaaaaaaaaaa…", "the pending banner shows twelve characters (`draftDetail.tsx:399`)")
        XCTAssertTrue(vm.isDecidable, "PENDING is the only status with buttons on it (§5.3)")
        XCTAssertNil(vm.decidedSummary)
    }

    func testADecidedDraftLosesTheButtonsAndGainsAReport() async throws {
        let (vm, _) = try await loaded([
            "status": SkillDraftStatus.rejected.rawValue,
            "reviewedBy": "admin",
            "reviewedAt": "2026-10-04 18:22:07",
            "rejectReason": "与既有技能重复",
        ])
        XCTAssertFalse(vm.isDecidable)
        let summary = try XCTUnwrap(vm.decidedSummary)
        XCTAssertEqual(summary.statusKey, "skill.draft.status.REJECTED")
        XCTAssertEqual(summary.by, "admin")
        XCTAssertEqual(summary.at, "2026-10-04 18:22")
        XCTAssertEqual(summary.reason, "与既有技能重复")
    }

    /// The reason line is a rejection's alone (`draftDetail.tsx:413`). Approval blanks the column server-side,
    /// but the report reads the row it was handed — a stale reason must not turn an approved draft's banner
    /// into a rejection's.
    func testAnApprovedDraftPrintsNoReasonEvenWhenTheRowCarriesOne() async throws {
        let (vm, _) = try await loaded([
            "status": SkillDraftStatus.approved.rawValue,
            "reviewedBy": "admin",
            "reviewedAt": "2026-10-04 18:22:07",
            "rejectReason": "与既有技能重复",
        ])
        let summary = try XCTUnwrap(vm.decidedSummary)
        XCTAssertEqual(summary.statusKey, "skill.draft.status.APPROVED")
        XCTAssertNil(summary.reason)
    }

    func testARefusedReReadKeepsTheRowAndSaysSo() async throws {
        let (vm, drafts) = try await loaded()
        drafts.detailReplies = [.failure(.timeout)]
        await vm.load()
        XCTAssertEqual(vm.notice, .refused(message: hx("error.timeout")))
        XCTAssertNotNil(vm.draft, "a read that failed says nothing about the draft on screen")
        XCTAssertEqual(vm.phase, .ready)
    }

    func testAFirstLoadThatFailsIsAnErrorStateNotAnEmptyPane() async throws {
        let drafts = FakeSkillDrafts()
        drafts.detailReplies = [.failure(.offline)]
        let vm = SkillDraftDetailViewModel(id: 1, drafts: drafts)
        await vm.load()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        XCTAssertNil(vm.draft)
    }

    // MARK: - approval

    func testApprovalAsksBeforeItSendsAndSendsOneKey() async throws {
        let (vm, drafts) = try await loaded()
        await vm.approve()
        XCTAssertTrue(vm.isConfirmingApprove, "approval is a publish, so it is confirmed before it is sent (§5.4)")
        XCTAssertTrue(drafts.approveRequests.isEmpty)

        vm.cancelApproval()
        XCTAssertFalse(vm.isConfirmingApprove)
        XCTAssertTrue(drafts.approveRequests.isEmpty, "dismissing the confirmation sends nothing")

        drafts.approveReplies = [.success(try promoted())]
        try queueReload(drafts)
        await vm.confirmApprove()
        XCTAssertEqual(drafts.approveRequests.count, 1)
        let payload = try XCTUnwrap(drafts.approveRequests.first?.payload)
        XCTAssertEqual(payload.expectedDigest, digest)
        XCTAssertNil(payload.conflictResolution, "the first attempt has no resolution to offer (`SkillDraftApproveRequest.kt:31-35`)")
        XCTAssertNil(payload.newName)
        XCTAssertEqual(drafts.detailRequests.count, 2, "a landed decision moved the row, so the row is re-read")
    }

    func testAPromotedOutcomeSaysTheSkillIsLiveAndReReads() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(try promoted(["findings": ["bash 联网"]]))]
        try queueReload(drafts)
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .promotedEnabled(name: "周报汇总", skillID: 9, canOpenSkill: true))
        XCTAssertEqual(vm.openableSkillID, 9)
        XCTAssertEqual(vm.notice?.tone, .success)
        XCTAssertEqual(drafts.detailRequests.count, 2, "`PROMOTED` moved the row, so the row is re-read")
    }

    func testAHeldBackPromotionCountsTheFindingsInstead() async throws {
        // `skillStatus == 1` is the only enabled reading; 0 and absent both mean the content scan held the row back.
        let heldBack: [[String: Any]] = [["skillStatus": 0], ["skillStatus": NSNull()]]
        for held in heldBack {
            let (vm, drafts) = try await loaded()
            var decision: [String: Any] = ["skillId": 9, "findings": ["脚本越权", "外联地址"]]
            for (key, value) in held { decision[key] = value }
            drafts.approveReplies = [.success(try SkillDraftDecision.stub(decision))]
            try queueReload(drafts)
            await vm.confirmApprove()
            XCTAssertEqual(
                vm.notice,
                .promotedHeldBack(name: "周报汇总", findings: 2, skillID: 9, canOpenSkill: true),
                "a row written but not live still has to be distinguishable from one that is"
            )
            XCTAssertEqual(vm.openableSkillID, 9, "held back is not the same as gone; the row can still be opened")
        }
    }

    func testTheOpenSkillOfferDisappearsWithoutASkillFacade() async throws {
        let (vm, drafts) = try await loaded(skills: nil)
        drafts.approveReplies = [.success(try promoted())]
        try queueReload(drafts)
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .promotedEnabled(name: "周报汇总", skillID: 9, canOpenSkill: false))
        XCTAssertNil(vm.openableSkillID, "the same sentence, with no dead button under it (§5.4 末行)")
    }

    func testADraftChangedOutcomeCarriesTheNewDigestAndReReads() async throws {
        let (vm, drafts) = try await loaded()
        let fresh = String(repeating: "b", count: 64)
        drafts.approveReplies = [.success(
            try SkillDraftDecision.stub(["outcome": "DRAFT_CHANGED", "currentDigest": fresh])
        )]
        try queueReload(drafts, ["contentDigest": fresh])
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .draftChanged(digest: fresh))
        XCTAssertEqual(vm.notice?.tone, .warning)
        XCTAssertEqual(vm.expectedDigest, fresh, "the next attempt has to send the digest that is on the row now")
        XCTAssertEqual(drafts.detailRequests.count, 2)
    }

    func testAnAlreadyReviewedOutcomeNamesWhoDecided() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(
            try SkillDraftDecision.stub([
                "outcome": "ALREADY_REVIEWED",
                "reviewedBy": "ops",
                "reviewedAt": "2026-10-03 07:15:00",
                "rejectReason": "命名不合规范",
            ])
        )]
        try queueReload(drafts)
        await vm.confirmApprove()
        XCTAssertEqual(
            vm.notice,
            .alreadyReviewed(by: "ops", at: "2026-10-03 07:15", reason: "命名不合规范")
        )
        XCTAssertEqual(vm.notice?.tone, .info)

        let (bare, bareDrafts) = try await loaded()
        bareDrafts.approveReplies = [.success(try SkillDraftDecision.stub(["outcome": "ALREADY_REVIEWED"]))]
        try queueReload(bareDrafts)
        await bare.confirmApprove()
        XCTAssertEqual(
            bare.notice,
            .alreadyReviewed(by: "-", at: "-", reason: nil),
            "a blank `reviewedBy` must not read as no decision at all (`draftDetail.tsx:138-139`)"
        )
    }

    func testAnUnknownOutcomeFallsBackToTheServersSentence() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(
            try SkillDraftDecision.stub(["outcome": "QUARANTINED", "reason": "该草稿已进入隔离区"])
        )]
        try queueReload(drafts)
        await vm.confirmApprove()
        XCTAssertEqual(vm.notice, .refused(message: "该草稿已进入隔离区"))
        XCTAssertEqual(vm.notice?.tone, .error)

        let (silent, silentDrafts) = try await loaded()
        silentDrafts.approveReplies = [.success(try SkillDraftDecision.stub(["outcome": "QUARANTINED"]))]
        try queueReload(silentDrafts)
        await silent.confirmApprove()
        XCTAssertEqual(
            silent.notice,
            .refused(message: hx("skill.draft.decision.failed")),
            "`reason` is not a contract (`SkillDraftDecisionResponse.kt:35-36`), so it cannot be the only voice"
        )
    }

    // MARK: - the name conflict

    func testNameTakenOpensTheConflictDialogAndDoesNotReRead() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(
            try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42])
        )]
        await vm.confirmApprove()

        XCTAssertEqual(vm.conflict?.name, "周报汇总", "the name this attempt tried to land, which is the draft's own")
        XCTAssertEqual(vm.conflict?.skillID, 42)
        XCTAssertEqual(vm.resolution, .rename, "preselected on the arm that leaves the existing row alone (§5.5)")
        XCTAssertEqual(vm.newName, "", "cleared rather than leaving the refused name in the box")
        XCTAssertNil(vm.conflictRefusal)
        XCTAssertFalse(vm.isConfirmingApprove)
        XCTAssertNil(vm.notice)
        XCTAssertEqual(
            drafts.detailRequests.count,
            1,
            "the early exit: a reload would overwrite the draft the reviewer is mid-decision on (`draftDetail.tsx:179-184`)"
        )
    }

    func testNameTakenOnARenameAttemptShowsTheNameJustTyped() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42])),
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 43])),
        ]
        await vm.confirmApprove()
        vm.newName = "  周报汇总 v2  "
        await vm.resolveConflict()

        XCTAssertEqual(vm.conflict?.name, "周报汇总 v2", "not the draft's title — that is not the name that was refused")
        let payload = try XCTUnwrap(drafts.approveRequests.last?.payload)
        XCTAssertEqual(payload.conflictResolution, "rename")
        XCTAssertEqual(payload.newName, "周报汇总 v2", "validated untrimmed, submitted trimmed (§5.5)")
        XCTAssertEqual(drafts.detailRequests.count, 1, "still no re-read, on either attempt")
    }

    func testReplacingSendsNoNameAndRenameNeedsOne() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42])),
            .success(try promoted()),
        ]
        await vm.confirmApprove()
        vm.choose(.replace)
        try queueReload(drafts)
        await vm.resolveConflict()
        let payload = try XCTUnwrap(drafts.approveRequests.last?.payload)
        XCTAssertEqual(payload.conflictResolution, "replace")
        XCTAssertNil(payload.newName, "a replace answer lands under the draft's own name, so there is nothing to validate")
        XCTAssertEqual(vm.notice, .promotedEnabled(name: "周报汇总", skillID: 9, canOpenSkill: true))

        let (renaming, renamingDrafts) = try await loaded()
        renamingDrafts.approveReplies = [
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN"])),
            .success(try promoted()),
        ]
        await renaming.confirmApprove()
        renaming.newName = "   "
        await renaming.resolveConflict()
        XCTAssertEqual(renaming.conflictRefusal, hx("skill.draft.conflict.nameRequired"))
        XCTAssertEqual(renamingDrafts.approveRequests.count, 1, "a blank rename never reaches the server")
        XCTAssertEqual(renaming.conflict?.name, "周报汇总", "the dialog stays open with what the reviewer typed in it")

        renaming.newName = "周报 v2"
        try queueReload(renamingDrafts)
        await renaming.resolveConflict()
        XCTAssertNil(
            renaming.conflictRefusal,
            "an accepted attempt retires the sentence that explained the refused one"
        )
        XCTAssertEqual(renamingDrafts.approveRequests.count, 2)
        XCTAssertEqual(renamingDrafts.approveRequests.last?.payload.newName, "周报 v2")
    }

    func testChoosingAnArmRetiresTheSentenceThatExplainedTheLastRefusal() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN"]))]
        await vm.confirmApprove()
        vm.newName = "   "
        await vm.resolveConflict()
        XCTAssertEqual(vm.conflictRefusal, hx("skill.draft.conflict.nameRequired"))
        XCTAssertEqual(drafts.approveRequests.count, 1, "a blank rename is refused before the round trip")

        vm.choose(.replace)
        XCTAssertNil(vm.conflictRefusal, "that sentence describes an attempt the dialog is no longer about to send")
        XCTAssertEqual(vm.newName, "   ", "the form itself is left alone")
        XCTAssertEqual(vm.resolution, .replace)
    }

    func testCancellingTheConflictClearsTheFormAndReReads() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42]))]
        await vm.confirmApprove()
        vm.newName = "周报 v2"
        vm.choose(.replace)

        try queueReload(drafts)
        await vm.cancelConflict()
        XCTAssertNil(vm.conflict)
        XCTAssertNil(vm.conflictRefusal)
        XCTAssertEqual(vm.newName, "")
        XCTAssertEqual(vm.resolution, .rename, "the next attempt starts on the safe arm again")
        XCTAssertEqual(
            drafts.detailRequests.count,
            2,
            "the digest this dialog was about to send may no longer be the digest on the row (`draftDetail.tsx:485-489`)"
        )
    }

    // MARK: - the two refusal channels

    func testARefusedApprovalKeepsTheDialogAndReReadsNothing() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42])),
            .failure(.business(code: 500, message: "只有待审草稿可以被批准")),
        ]
        await vm.confirmApprove()
        vm.newName = "周报 v2"
        await vm.resolveConflict()

        XCTAssertEqual(
            vm.conflictRefusal,
            "只有待审草稿可以被批准",
            "the sheet is on top of the screen, so the sentence has to land inside it or nowhere the reviewer sees"
        )
        XCTAssertNil(vm.notice, "the banner behind the sheet stays as the last decision left it")
        XCTAssertEqual(vm.conflict?.name, "周报汇总", "the reviewer has a server sentence to read and a name to fix")
        XCTAssertEqual(vm.newName, "周报 v2")
        XCTAssertEqual(
            drafts.detailRequests.count,
            1,
            "an error envelope is a verdict about a row that has not changed — re-reading would bury it"
        )
    }

    func testARolledBackNameRaceClosesTheDialogAndReReads() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 42])),
            .failure(.business(code: 409, message: "该名称在批准期间已被占用")),
            .success(try SkillDraftDecision.stub(["outcome": "NAME_TAKEN", "skillId": 43])),
        ]
        await vm.confirmApprove()
        vm.newName = "周报 v2"
        vm.choose(.replace)

        try queueReload(drafts)
        await vm.resolveConflict()
        XCTAssertEqual(vm.notice, .nameRace)
        XCTAssertEqual(vm.notice?.tone, .warning)
        XCTAssertNil(vm.conflict)
        XCTAssertNil(vm.conflictRefusal)
        XCTAssertEqual(vm.newName, "")
        XCTAssertEqual(
            drafts.detailRequests.count,
            2,
            "the write rolled back and the draft is still pending, so a re-read is the whole remedy (`SkillDraftController.kt:115-117`)"
        )

        // The arm the closed dialog left behind is `.replace`, and nothing can read it again: a collision
        // always re-opens on the safe arm, which is what makes clearing it here unnecessary.
        await vm.approve()
        await vm.confirmApprove()
        XCTAssertEqual(vm.conflict?.name, "周报汇总")
        XCTAssertEqual(vm.resolution, .rename, "preselected on the arm that leaves the existing row alone (§5.5)")
    }

    func testApprovingWithoutADigestRefusesBeforeTheConfirmationOpens() async throws {
        let (vm, drafts) = try await loaded(skills: FakeSkills(), ["contentDigest": ""])
        XCTAssertNil(vm.expectedDigest)
        XCTAssertNil(vm.digestPreview)

        try queueReload(drafts)
        await vm.approve()
        XCTAssertFalse(
            vm.isConfirmingApprove,
            "a question the reviewer cannot answer is not worth asking (`draftDetail.tsx:222-232`)"
        )
        XCTAssertTrue(drafts.approveRequests.isEmpty, "nothing to approve against, so nothing goes on the wire")
        XCTAssertEqual(vm.notice, .refused(message: hx("skill.draft.digest.missing")))
        XCTAssertEqual(drafts.detailRequests.count, 2, "…but the row still gets re-read")

        // The send keeps the same guard for the path that opens the dialog before the digest goes away: a
        // re-read that lands while the confirmation is up replaces the row on screen, and a row with no digest
        // cannot put a key on the wire.
        try queueReload(drafts, ["contentDigest": ""])
        await vm.load()
        XCTAssertNil(vm.expectedDigest, "the row that replaced this one carries nothing to approve against")
        try queueReload(drafts, ["contentDigest": ""])
        await vm.confirmApprove()
        XCTAssertTrue(drafts.approveRequests.isEmpty)
        XCTAssertEqual(vm.notice, .refused(message: hx("skill.draft.digest.missing")))
    }

    func testTheSecondTapOnApproveSendsNothingWhileTheFirstIsOnTheWire() async throws {
        let (vm, drafts) = try await loaded()
        drafts.gateWrites = true
        drafts.approveReplies = [.success(try promoted())]
        try queueReload(drafts)
        let first = Task { await vm.confirmApprove() }
        try await waitUntil { drafts.approveRequests.count == 1 }
        XCTAssertTrue(vm.isActing, "the sheet has to hold its buttons down while the server thinks")

        await vm.confirmApprove()
        XCTAssertEqual(drafts.approveRequests.count, 1, "a double tap is one publish, not two")

        drafts.releaseWrites()
        await first.value
        XCTAssertFalse(vm.isActing)
        XCTAssertEqual(vm.notice, .promotedEnabled(name: "周报汇总", skillID: 9, canOpenSkill: true))
    }

    // MARK: - rejection

    func testRejectionSendsTheTrimmedReasonAndRecordsIt() async throws {
        let (vm, drafts) = try await loaded()
        vm.startRejection()
        XCTAssertTrue(vm.isRejectOpen)
        vm.rejectReason = "  与既有技能重复，建议合并  "
        drafts.rejectReplies = [.success(try SkillDraftDecision.stub(["outcome": "REJECTED"]))]
        try queueReload(drafts)
        await vm.submitRejection()

        XCTAssertEqual(drafts.rejectRequests.count, 1)
        XCTAssertEqual(drafts.rejectRequests.last?.payload.reason, "与既有技能重复，建议合并")
        XCTAssertEqual(drafts.rejectRequests.last?.id, 1)
        XCTAssertFalse(vm.isRejectOpen)
        XCTAssertEqual(vm.notice, .rejectionRecorded)
        XCTAssertNil(vm.rejectRefusal)
        XCTAssertEqual(drafts.detailRequests.count, 2, "the row it describes moved, so the row is re-read")
    }

    func testABlankRejectionIsRefusedBeforeTheRoundTrip() async throws {
        let (vm, drafts) = try await loaded()
        vm.startRejection()
        vm.rejectReason = "   \n "
        await vm.submitRejection()
        XCTAssertEqual(vm.rejectRefusal, hx("skill.draft.reject.reasonRequired"))
        XCTAssertTrue(drafts.rejectRequests.isEmpty, "the service would only say「write something」back (`SkillDraftServiceImpl.kt:370-371`)")
        XCTAssertTrue(vm.isRejectOpen, "the sheet stays up with what the reviewer typed in it")

        drafts.rejectReplies = [.success(try SkillDraftDecision.stub(["outcome": "REJECTED"]))]
        try queueReload(drafts)
        vm.rejectReason = "重复"
        await vm.submitRejection()
        XCTAssertNil(vm.rejectRefusal, "an accepted reason retires the sentence about the refused one")
        XCTAssertFalse(vm.isRejectOpen, "and a decision that landed is the only thing that closes the sheet")
    }

    /// A refused rejection keeps the sheet: the reason is still worth sending and the server's sentence is
    /// still worth reading, and both live inside it (`draftDetail.tsx:246-260` closes the modal only once a
    /// decision landed).
    func testARefusedRejectionKeepsTheSheetAndItsReason() async throws {
        let (vm, drafts) = try await loaded()
        vm.startRejection()
        vm.rejectReason = "与既有技能重复"
        drafts.rejectReplies = [.failure(.business(code: 400, message: "只有待审草稿可以被驳回"))]
        await vm.submitRejection()

        XCTAssertTrue(vm.isRejectOpen, "a sheet that closed on a failure would throw the typed reason away")
        XCTAssertEqual(vm.rejectReason, "与既有技能重复")
        XCTAssertEqual(vm.rejectRefusal, "只有待审草稿可以被驳回")
        XCTAssertNil(vm.notice, "the banner sits behind the sheet, so writing there reports nothing")
        XCTAssertEqual(
            drafts.detailRequests.count,
            1,
            "an error envelope is a verdict about a row that has not changed"
        )
    }

    func testTheRejectionCeilingIsCountedInTheServersOwnUnits() async throws {
        // 512 is legal because the service compares with `>` (`SkillDraftServiceImpl.kt:372`).
        let (exactly, exactlyDrafts) = try await loaded()
        exactly.startRejection()
        exactly.rejectReason = String(repeating: "报", count: 512)
        exactlyDrafts.rejectReplies = [.success(try SkillDraftDecision.stub(["outcome": "REJECTED"]))]
        try queueReload(exactlyDrafts)
        await exactly.submitRejection()
        XCTAssertEqual(exactlyDrafts.rejectRequests.count, 1)
        XCTAssertNil(exactly.rejectRefusal)

        let (over, overDrafts) = try await loaded()
        over.startRejection()
        over.rejectReason = String(repeating: "报", count: 513)
        await over.submitRejection()
        XCTAssertEqual(over.rejectRefusal, hx("skill.draft.reject.tooLong", SkillDraftRules.maxRejectReasonLength))
        XCTAssertTrue(overDrafts.rejectRequests.isEmpty)

        let (emoji, emojiDrafts) = try await loaded()
        emoji.startRejection()
        // 257 emoji are 257 graphemes and 514 UTF-16 units: Swift's `count` would wave this through, the
        // server's `String.length` would not, so the client has to count the way the server does.
        emoji.rejectReason = String(repeating: "😀", count: 257)
        XCTAssertEqual(emoji.rejectReason.count, 257)
        await emoji.submitRejection()
        XCTAssertNotNil(emoji.rejectRefusal, "a reason measured in graphemes is a round trip that can only fail")
        XCTAssertTrue(emojiDrafts.rejectRequests.isEmpty)
    }

    func testOpeningEitherFormClearsTheLastNotice() async throws {
        let (vm, drafts) = try await loaded()
        drafts.approveReplies = [.failure(.timeout)]
        await vm.confirmApprove()
        XCTAssertNotNil(vm.notice)

        await vm.approve()
        XCTAssertNil(vm.notice, "a fresh question must not arrive wearing the last answer")

        vm.cancelApproval()
        drafts.approveReplies = [.failure(.timeout)]
        await vm.confirmApprove()
        XCTAssertNotNil(vm.notice)
        vm.startRejection()
        XCTAssertNil(vm.notice)
        XCTAssertNil(vm.rejectRefusal)
        XCTAssertEqual(vm.rejectReason, "", "the rejection form opens empty, not holding the last attempt's text")
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
