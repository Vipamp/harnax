import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The queue's and the detail screen's shared read of a row, checked without a view.
///
/// These are the four rules the review screens cannot re-derive locally (`specs/07-skill-draft-review.md` §5.2,
/// §5.3, §5.4): how a stamp shortens, which colours a status or a verdict owns, what an addressless row offers,
/// and which outcome owes the sheet what. Two of them are pinned because the console has an explicit fallback
/// for the same case — a status column that holds something other than the three arms, or a fourth verdict word —
/// and a client that guesses a meaning for either would put a green tick on a refusal.
final class SkillDraftCopyTests: XCTestCase {
    // MARK: - stamps

    /// Both serialisations the stack answers with (`application.yml:23`), plus the milliseconds a `LocalDateTime`
    /// gains when a column is read through a driver that keeps the fraction. The screen shows minute precision
    /// either way, so all three have to land on the same text.
    func testAStampKeepsDateAndMinuteFromEitherWireForm() {
        XCTAssertEqual(SkillDraftCopy.stamp("2026-10-05 09:12:33"), "2026-10-05 09:12")
        XCTAssertEqual(SkillDraftCopy.stamp("2026-10-05T09:12:33"), "2026-10-05 09:12")
        XCTAssertEqual(SkillDraftCopy.stamp("2026-10-05 09:12:33.123"), "2026-10-05 09:12")
    }

    /// Unreadable text is kept, not replaced: a reviewer comparing two rows would read a fabricated `-` as
    /// 「never touched」, and a wrong time on a review screen is worse than a raw one.
    func testAnUnreadableStampSurvivesRatherThanBecomingADash() {
        XCTAssertEqual(SkillDraftCopy.stamp("later"), "later")
        XCTAssertEqual(SkillDraftCopy.stamp("2026-10-05"), "2026-10-05")
        XCTAssertEqual(SkillDraftCopy.stamp("2026-10-05 9:12"), "2026-10-05 9:12")
    }

    /// Absent and blank are the same cell, and it is the console's empty cell verbatim
    /// (`drafts.tsx:86,155`).
    func testAnAbsentStampIsTheDash() {
        XCTAssertEqual(SkillDraftCopy.stamp(nil), SkillDraftCopy.dash)
        XCTAssertEqual(SkillDraftCopy.stamp("   "), SkillDraftCopy.dash)
        XCTAssertEqual(SkillDraftCopy.stamp(""), SkillDraftCopy.dash)
        XCTAssertEqual(SkillDraftCopy.dash, "-")
    }

    /// The decided-by column drops the year (`drafts.tsx:152`) — and, unlike `stamp`, has no raw text to fall
    /// back on: without a parsable date half there is no shorter form to show, so the dash is the honest answer.
    func testTheShortStampDropsTheYearAndFallsBackToTheDash() {
        XCTAssertEqual(SkillDraftCopy.shortStamp("2026-10-05 09:12:33"), "10-05 09:12")
        XCTAssertEqual(SkillDraftCopy.shortStamp("2026-10-05T09:12:33"), "10-05 09:12")
        XCTAssertEqual(SkillDraftCopy.shortStamp(nil), SkillDraftCopy.dash)
        XCTAssertEqual(SkillDraftCopy.shortStamp("later"), SkillDraftCopy.dash)
        XCTAssertEqual(SkillDraftCopy.shortStamp("2026-10-05"), SkillDraftCopy.dash)
    }

    // MARK: - status and verdict meaning

    /// The three arms of the filter each have a catalogue key; anything else in the column has none to hand out,
    /// which is what lets the badge draw a neutral chip instead of echoing `skill.draft.status.EXPIRED`.
    func testOnlyTheThreeKnownStatusesGetAKey() {
        XCTAssertEqual(SkillDraftCopy.statusKey("PENDING"), "skill.draft.status.PENDING")
        XCTAssertEqual(SkillDraftCopy.statusKey("APPROVED"), "skill.draft.status.APPROVED")
        XCTAssertEqual(SkillDraftCopy.statusKey("REJECTED"), "skill.draft.status.REJECTED")
        XCTAssertNil(SkillDraftCopy.statusKey("EXPIRED"))
        XCTAssertNil(SkillDraftCopy.statusKey(nil))
        XCTAssertNil(SkillDraftCopy.statusKey("pending"), "the column is upper-case and matching is case-sensitive")
    }

    /// Orange / green / red for the three arms, neutral for a fourth (`drafts.tsx:10-14`).
    func testTheStatusTonesAreTheConsolesThreeAndThenNeutral() {
        XCTAssertEqual(SkillDraftCopy.statusTone("PENDING"), .warning)
        XCTAssertEqual(SkillDraftCopy.statusTone("APPROVED"), .success)
        XCTAssertEqual(SkillDraftCopy.statusTone("REJECTED"), .danger)
        XCTAssertEqual(SkillDraftCopy.statusTone("EXPIRED"), .textTertiary)
        XCTAssertEqual(SkillDraftCopy.statusTone(nil), .textTertiary)
    }

    /// The verdict tag is the server's own word kept verbatim (`drafts.tsx:128`), so only its colour is derived
    /// here — and an unrecognised fourth verdict must not claim one.
    func testTheVerdictTonesCoverThreeWordsAndLeaveTheRestUnclaimed() {
        XCTAssertEqual(SkillDraftCopy.verdictTone("SAFE"), .success)
        XCTAssertEqual(SkillDraftCopy.verdictTone("CAUTION"), .warning)
        XCTAssertEqual(SkillDraftCopy.verdictTone("DANGEROUS"), .danger)
        XCTAssertEqual(SkillDraftCopy.verdictTone(nil), .textSecondary)
        XCTAssertEqual(SkillDraftCopy.verdictTone("UNKNOWN"), .textSecondary)
    }

    // MARK: - address

    /// `SkillDraftRow.id` is nullable, and a row with no address is not tappable. This is the only place that
    /// decision is expressed, so the screen cannot forget to ask.
    func testARowOffersItsRouteOnlyWhenItCarriesAnId() throws {
        let first = try decodeRow(#"{"id":7,"name":"csv-clean","status":"PENDING","upstreamFindingCount":0}"#)
        XCTAssertEqual(SkillDraftRef.forRow(first)?.id, 7)

        let addressless = try decodeRow(#"{"name":"csv-clean","status":"PENDING","upstreamFindingCount":0}"#)
        XCTAssertNil(SkillDraftRef.forRow(addressless))
        XCTAssertNil(SkillDraftRef.forRow(try decodeRow(#"{"id":null,"name":"x","upstreamFindingCount":0}"#)))
    }

    private func decodeRow(_ json: String) throws -> SkillDraftRow {
        let data = Data(json.utf8)
        return try JSONDecoder().decode(SkillDraftRow.self, from: data)
    }

    // MARK: - the six panes and the two conflict answers

    /// Six panes in the console's order, each with a key of its own (`draftDetail.tsx:275-333`). A pane added
    /// without a catalogue entry fails the localisation gate; a pane *reordered* fails here.
    func testTheDetailScreenHasSixPanesInOrder() {
        XCTAssertEqual(SkillDraftTab.allCases, [.body, .files, .scripts, .scans, .source, .history])
        XCTAssertEqual(
            SkillDraftTab.allCases.map(\.titleKey),
            [
                "skill.draft.tab.body", "skill.draft.tab.files", "skill.draft.tab.scripts",
                "skill.draft.tab.scans", "skill.draft.tab.source", "skill.draft.tab.history",
            ]
        )
    }

    /// `rename` first and preselected, `replace` second (`draftDetail.tsx:511-520`): both answers overwrite or
    /// duplicate something somebody else published, so the dialog starts on the arm that leaves the existing
    /// row alone. The order is the promise, not cosmetics — a list that reorders puts the destructive arm under
    /// the thumb.
    func testTheConflictDialogListsRenameBeforeReplace() {
        XCTAssertEqual(SkillDraftResolution.allCases, [.rename, .replace])
        XCTAssertEqual(SkillDraftResolution.rename.titleKey, "skill.draft.conflict.rename")
        XCTAssertEqual(SkillDraftResolution.replace.titleKey, "skill.draft.conflict.replace")
    }

    // MARK: - what each outcome owes the sheet

    /// Success for the two things that happened, warning for the three that need a second look, information for
    /// 「somebody else already closed this」, error only for what the screen cannot act on.
    func testTheNoticeTonesMatchWhatTheReviewerHasToDoNext() {
        let notices: [(SkillDraftNotice, SkillDraftNoticeTone)] = [
            (.promotedEnabled(name: "csv-clean", skillID: 1, canOpenSkill: true), .success),
            (.rejectionRecorded, .success),
            (.promotedHeldBack(name: "csv-clean", findings: 2, skillID: 1, canOpenSkill: true), .warning),
            (.draftChanged(digest: "abc"), .warning),
            (.nameRace, .warning),
            (.alreadyReviewed(by: "-", at: "-", reason: nil), .info),
            (.refused(message: "boom"), .error),
        ]
        for (notice, tone) in notices {
            XCTAssertEqual(notice.tone, tone, "\(notice)")
        }
    }

    /// Every slot the four tones resolve to is a real palette role, so a tone cannot ask for a colour the
    /// theme does not have.
    func testTheFourTonesTakeFourDistinctSlotsFromThePalette() {
        XCTAssertEqual(SkillDraftNoticeTone.success.slot, .success)
        XCTAssertEqual(SkillDraftNoticeTone.warning.slot, .warning)
        XCTAssertEqual(SkillDraftNoticeTone.info.slot, .brand)
        XCTAssertEqual(SkillDraftNoticeTone.error.slot, .danger)
    }

    /// 「打开技能」only exists for a promotion, and only when the host can actually read the skill back. A nil
    /// facade has to produce a nil address rather than a link to a screen that would fail to load.
    func testOnlyAPromotionCanBeOpenedAndOnlyWhenTheHostAllowsIt() {
        XCTAssertEqual(
            SkillDraftNotice.promotedEnabled(name: "csv-clean", skillID: 41, canOpenSkill: true).openableSkillID,
            41
        )
        XCTAssertEqual(
            SkillDraftNotice.promotedHeldBack(name: "csv-clean", findings: 2, skillID: 42, canOpenSkill: true)
                .openableSkillID,
            42
        )

        XCTAssertNil(
            SkillDraftNotice.promotedEnabled(name: "csv-clean", skillID: 41, canOpenSkill: false).openableSkillID,
            "no skills facade means no destination"
        )
        XCTAssertNil(
            SkillDraftNotice.promotedEnabled(name: "csv-clean", skillID: nil, canOpenSkill: true).openableSkillID
        )
        for notice in [
            SkillDraftNotice.draftChanged(digest: "abc"),
            .alreadyReviewed(by: "heqingsong", at: "2026-10-05 09:12", reason: nil),
            .rejectionRecorded,
            .nameRace,
            .refused(message: "boom"),
        ] {
            XCTAssertNil(notice.openableSkillID, "\(notice) has no skill behind it")
        }
    }
}
