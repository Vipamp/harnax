import XCTest

@testable import HarnaxCore

/// The session panel answers from two reads at once — Admin's PENDING nominations for this session and
/// agent-service's enabled directory — and a row has to say which of the two put it there.
final class SessionSkillTests: XCTestCase {

    private func draft(_ name: String, _ description: String?) -> SessionSkillRules.Draft {
        SessionSkillRules.Draft(name: name, description: description)
    }

    func testDraftOrderIsKeptAndEachRowCarriesItsOwnEnableState() {
        let rows = SessionSkillRules.merged(
            drafts: [draft("invoice-fill", "fills"), draft("other", nil)],
            enabled: [SessionSkillRow(name: "invoice-fill", description: nil, enabled: true, enabledAt: "2026-10-08T10:00:00Z")]
        )
        XCTAssertEqual(rows.map(\.name), ["invoice-fill", "other"])
        XCTAssertTrue(rows[0].enabled)
        XCTAssertEqual(rows[0].enabledAt, "2026-10-08T10:00:00Z")
        XCTAssertFalse(rows[1].enabled)
        XCTAssertNil(rows[1].enabledAt)
    }

    func testTheQueueRowKeepsItsTextWhereBothReadsHaveTheName() {
        let rows = SessionSkillRules.merged(
            drafts: [draft("invoice-fill", "fills an invoice")],
            enabled: [SessionSkillRow(name: "invoice-fill", description: "stale copy", enabled: true, enabledAt: "x")]
        )
        XCTAssertEqual(rows.count, 1, "one name is one row, whatever both reads said about it")
        XCTAssertEqual(rows[0].description, "fills an invoice")
    }

    func testAnEnabledSkillWhoseDraftHasLeftTheQueueStillShows() {
        let rows = SessionSkillRules.merged(
            drafts: [],
            enabled: [SessionSkillRow(name: "gone", description: nil, enabled: true, enabledAt: nil)]
        )
        XCTAssertEqual(rows.map(\.name), ["gone"])
    }

    func testEachRefusalIsNamedByItsOwnCause() {
        XCTAssertEqual(SessionSkillRefusal(code: 403).messageKey, "chat.skills.blocked")
        XCTAssertEqual(SessionSkillRefusal(code: 409).messageKey, "chat.skills.full")
        XCTAssertEqual(SessionSkillRefusal(code: 410).messageKey, "chat.skills.noSandbox")
        XCTAssertEqual(SessionSkillRefusal(code: 404).messageKey, "chat.skills.sourceGone")
        XCTAssertEqual(SessionSkillRefusal(code: 500).messageKey, "chat.skills.copyFailed")
    }

    func testACodeNobodyDocumentedDoesNotClaimTheDraftIsGone() {
        XCTAssertEqual(SessionSkillRefusal(code: 502).messageKey, "chat.skills.enableFailed")
    }
}
