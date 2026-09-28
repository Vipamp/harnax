import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// A broken reference stays on the team drill-down and says what is wrong with it — hiding the row is the
/// failure these tests guard against.
final class TeamBindingsPresenterTests: XCTestCase {
    private func team(members: [[String: Any]] = [], skills: [[String: Any]] = []) throws -> TeamSummary {
        try TeamSummary.stub([
            "name": "投研团队",
            "systemPrompt": "",
            "modelId": 7,
            "memberList": members,
            "skillList": skills,
        ])
    }

    private func rows(_ team: TeamSummary, _ titleKey: String) throws -> [HXBindingRow] {
        let section = try XCTUnwrap(TeamBindingsPresenter.sections(for: team).first { $0.titleKey == titleKey })
        return section.rows
    }

    func testMembersComeBeforeTheLeadsSkills() throws {
        let summary = try team(
            members: [["agentId": 11, "agentName": "数据抓取", "agentAvailable": true]],
            skills: [["skillId": 12, "skillName": "公告解析", "skillAvailable": true]]
        )
        XCTAssertEqual(
            TeamBindingsPresenter.sections(for: summary).map(\.titleKey),
            ["team.section.members", "team.section.skills"]
        )
    }

    func testAnEmptyDimensionIsNotAHeadingOverNothing() throws {
        let summary = try team(members: [])
        XCTAssertEqual(TeamBindingsPresenter.sections(for: summary).map(\.titleKey), [])
    }

    func testADisabledMemberIsNamedAndMarkedRatherThanGone() throws {
        let summary = try team(members: [[
            "agentId": 12, "agentName": "估值分析", "agentStatus": 0, "agentAvailable": false,
        ]])
        let member = try XCTUnwrap(rows(summary, "team.section.members").first)
        XCTAssertEqual(member.title, "估值分析")
        XCTAssertEqual(member.badges, [hx("team.binding.unavailable")])
    }

    func testTheDelegationTextWinsOverTheMembersOwnDescription() throws {
        let summary = try team(members: [[
            "agentId": 11, "agentName": "数据抓取", "agentDescription": "抓取行情与公告",
            "delegationDescription": "负责取数", "agentAvailable": true,
        ]])
        XCTAssertEqual(try rows(summary, "team.section.members").first?.subtitle, "负责取数")
    }

    func testAMemberWithNoDelegationFallsBackToItsOwnDescription() throws {
        let summary = try team(members: [[
            "agentId": 11, "agentName": "数据抓取", "agentDescription": "抓取行情与公告", "agentAvailable": true,
        ]])
        XCTAssertEqual(try rows(summary, "team.section.members").first?.subtitle, "抓取行情与公告")
    }

    func testANamelessMemberOrSkillIsPrintedByItsId() throws {
        let summary = try team(
            members: [["agentId": 13, "agentName": "   ", "agentAvailable": false]],
            skills: [["skillId": 15, "skillName": "", "skillAvailable": false]]
        )
        XCTAssertEqual(try rows(summary, "team.section.members").first?.title, hx("team.binding.memberFallback", 13))
        XCTAssertEqual(try rows(summary, "team.section.skills").first?.title, hx("team.binding.skillFallback", 15))
    }

    func testASkillCarriesItsRepositoryFirstAndTheStaleMarkSecond() throws {
        let summary = try team(skills: [[
            "skillId": 12, "skillName": "公告解析", "repositoryName": "qoder-skills",
            "skillDescription": "读取交易所公告", "skillAvailable": false,
        ]])
        let skill = try XCTUnwrap(rows(summary, "team.section.skills").first)
        XCTAssertEqual(skill.subtitle, "读取交易所公告")
        XCTAssertEqual(skill.badges, ["qoder-skills", hx("team.binding.unavailable")])
    }

    func testAnAvailableSkillWithNoRepositoryHasNoMarksAtAll() throws {
        let summary = try team(skills: [["skillId": 12, "skillName": "公告解析", "skillAvailable": true]])
        XCTAssertTrue(try rows(summary, "team.section.skills").first?.badges.isEmpty == true)
    }
}
