import XCTest

@testable import HarnaxCore

/// The three columns the self-evolution feature added to DTOs that already existed, read off the wire the
/// server puts them on.
///
/// Each one has a different reason to be pinned:
/// - `AgentSummary.skillSelfWrite` is the switch that decides whether an agent may propose skills at all, so a
///   form that reads it wrong would tell the operator the feature is off on an agent that is writing skills;
/// - `AgentSaveDraft.skillSelfWrite` is the one write lever with three states, and the server only moves the
///   column when the key arrives (`AgentServiceImpl.kt:180`), so an absent key means 「leave it」;
/// - `SkillItem.origin` is what marks a promoted skill as agent-written, and the promoted flag is read against
///   a constant the entity owns (`harnax-entity/src/main/kotlin/com/agnetix/harnax/entity/Skill.kt:13`).
final class SkillDraftContractTests: XCTestCase {
    // MARK: - the agent's self-write switch

    private func agent(_ body: String) throws -> AgentSummary {
        try JSONDecoder().decode(AgentSummary.self, from: Data(body.utf8))
    }

    /// `1` on, `0` off, and a row written before the column existed — or read back by a server that drops null
    /// keys (`application.yml:25`) — arrives with no key at all and reads as off, which is the column default.
    func testASelfWritingRowIsReadOnExactlyOne() throws {
        XCTAssertTrue(try agent(#"{"id":3,"name":"翻译","skillSelfWrite":1}"#).isSelfWriting)
        XCTAssertFalse(try agent(#"{"id":3,"name":"翻译","skillSelfWrite":0}"#).isSelfWriting)
        XCTAssertFalse(try agent(#"{"id":3,"name":"翻译"}"#).isSelfWriting)
    }

    /// The flag is its own column and not a side effect of the two it sits next to on the row: a private,
    /// disabled agent may still self-write, and an enabled shared one may not.
    func testTheSelfWriteFlagStandsApartFromStatusAndSharing() throws {
        let row = try agent(#"{"id":3,"name":"翻译","status":0,"isPublic":0,"skillSelfWrite":1}"#)
        XCTAssertFalse(row.isEnabled)
        XCTAssertFalse(row.isShared)
        XCTAssertTrue(row.isSelfWriting)
    }

    // MARK: - writing the switch back

    private func body(_ draft: AgentSaveDraft) throws -> [String: JSONValue] {
        try JSONDecoder().decode([String: JSONValue].self, from: JSONEncoder().encode(draft))
    }

    /// A form that never reached the toggle must not send the key: the update route reads an absent
    /// `skillSelfWrite` as 「keep what is there」, so sending `0` because the local copy defaulted it would turn
    /// a working self-writing agent off on the next unrelated save.
    func testAnUntouchedToggleSendsNoKey() throws {
        let saved = try body(AgentSaveDraft(name: "翻译", description: "改个描述"))
        XCTAssertNil(saved["skillSelfWrite"])
    }

    /// Both directions are spelled as the 0/1 column the server declares, not as a Swift boolean.
    func testAMovedToggleSendsTheColumnValue() throws {
        XCTAssertEqual(try body(AgentSaveDraft(skillSelfWrite: 1))["skillSelfWrite"], .number(1))
        XCTAssertEqual(try body(AgentSaveDraft(skillSelfWrite: 0))["skillSelfWrite"], .number(0))
    }

    // MARK: - provenance on a skill row

    private func skill(_ body: String) throws -> SkillItem {
        try JSONDecoder().decode(SkillItem.self, from: Data(body.utf8))
    }

    /// The two bound counters carry non-null defaults and always arrive, so a hand-shaped row has to carry them
    /// too — a fixture that left them off would be testing a shape the server never sends.
    func testAPromotedSkillIsMarkedByItsOrigin() throws {
        let promoted = try skill(
            #"{"id":41,"name":"csv-clean","status":1,"boundAgentCount":0,"boundTeamCount":0,"origin":"agent_promoted","originRef":"sess-7"}"#
        )
        XCTAssertTrue(promoted.isAgentPromoted)
        XCTAssertEqual(promoted.originRef, "sess-7", "the session that proposed it")

        let human = try skill(
            #"{"id":42,"name":"pdf-report","status":1,"boundAgentCount":0,"boundTeamCount":0,"origin":"human"}"#
        )
        XCTAssertFalse(human.isAgentPromoted)
        XCTAssertNil(human.originRef, "a human skill has no proposing session, and the key is dropped")
    }

    /// A row from before the provenance columns arrived reads as a human skill rather than as a broken one: the
    /// two are indistinguishable on the wire and only the tag depends on the difference.
    func testASkillWithNoOriginColumnReadsAsHuman() throws {
        let legacy = try skill(
            #"{"id":43,"name":"old-skill","status":0,"boundAgentCount":2,"boundTeamCount":1}"#
        )
        XCTAssertFalse(legacy.isAgentPromoted)
        XCTAssertTrue(legacy.isBound)
        XCTAssertEqual(legacy.bindings, 3)
    }
}
