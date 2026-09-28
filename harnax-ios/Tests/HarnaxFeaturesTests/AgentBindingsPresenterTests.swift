import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The drill-down's rules — which name column wins, what a nameless row falls back to, which dimension is
/// dropped instead of shown empty — are all presenter logic and none of it needs a view.
final class AgentBindingsPresenterTests: XCTestCase {
    private func agent(_ fields: [String: Any]) throws -> AgentSummary {
        try AgentSummary.stub(fields)
    }

    private func one(_ record: [String: Any]) -> [[String: Any]] { [record] }

    private func section(_ agent: AgentSummary, _ titleKey: String, chinese: Bool = false) throws -> HXBindingRow {
        let sections = try XCTUnwrap(AgentBindingsPresenter.sections(for: agent, chinese: chinese).first {
            $0.titleKey == titleKey
        })
        return try XCTUnwrap(sections.rows.first)
    }

    func testADimensionTheAgentHasNoneOfIsNotShownAsAnEmptySection() throws {
        let agent = try agent(["toolList": [["toolId": 2, "toolName": "webSearch"]]])
        let sections = AgentBindingsPresenter.sections(for: agent, chinese: false)
        XCTAssertEqual(sections.map(\.titleKey), ["agent.section.tools"])
    }

    func testTheFiveDimensionsKeepTheWizardOrder() throws {
        let agent = try agent([
            "sessionList": [["id": 1]],
            "cliList": [["cliId": 1]],
            "skillList": [["skillId": 1]],
            "mcpList": [["mcpId": 1]],
            "toolList": [["toolId": 1]],
        ])
        XCTAssertEqual(
            AgentBindingsPresenter.sections(for: agent, chinese: false).map(\.titleKey),
            ["agent.section.tools", "agent.section.mcp", "agent.section.skills", "agent.section.cli", "agent.section.sessions"]
        )
    }

    func testChinesePrefersTheChineseColumnAndEnglishSkipsIt() throws {
        let agent = try agent(["toolList": one([
            "toolId": 2,
            "toolName": "webSearch",
            "toolDisplayName": "Web Search",
            "toolDisplayNameZh": "联网搜索",
        ])])
        XCTAssertEqual(try section(agent, "agent.section.tools", chinese: true).title, "联网搜索")
        XCTAssertEqual(try section(agent, "agent.section.tools", chinese: false).title, "Web Search")
    }

    func testABlankChineseColumnFallsThroughToTheNextName() throws {
        let agent = try agent(["toolList": one([
            "toolId": 2, "toolName": "webSearch", "toolDisplayName": "   ", "toolDisplayNameZh": "",
        ])])
        XCTAssertEqual(try section(agent, "agent.section.tools", chinese: true).title, "webSearch")
    }

    func testAllThreeNameColumnsBlankGivesTheToolItsId() throws {
        let agent = try agent(["toolList": one(["toolId": 9, "toolName": "   ", "toolDisplayName": ""])])
        XCTAssertEqual(
            try section(agent, "agent.section.tools", chinese: false).title,
            hx("agent.binding.toolFallback", 9)
        )
    }

    func testAToolWithNoIdAtAllGivesThePlaceholderNotAToolNumber() throws {
        let agent = try agent(["toolList": one(["toolDescription": "检索公开网页"])])
        let tool = try section(agent, "agent.section.tools")
        XCTAssertEqual(tool.title, hx("agent.binding.unnamed"))
        XCTAssertEqual(tool.subtitle, "检索公开网页")
    }

    func testConfirmationAndEnvironmentCountsAreMarksButAZeroCountIsNot() throws {
        let confirmed = try agent(["toolList": one([
            "toolId": 2, "toolName": "webSearch", "needConfirm": true,
            "envBindings": [["envKey": "SEARCH_QUOTA", "customValue": "50"]],
        ])])
        XCTAssertEqual(
            try section(confirmed, "agent.section.tools").badges,
            [hx("agent.binding.confirm"), hxCount("env.count", 1)]
        )
        let plain = try agent(["toolList": one(["toolId": 3, "toolName": "writeFile", "needConfirm": false])])
        XCTAssertTrue(try section(plain, "agent.section.tools").badges.isEmpty)
    }

    func testASkillRowMarksItsSourceRepository() throws {
        let agent = try agent(["skillList": one([
            "skillId": 5, "skillName": "术语表", "repositoryName": "qoder-skills", "skillDescription": "领域术语对照",
        ])])
        let skill = try section(agent, "agent.section.skills")
        XCTAssertEqual(skill.title, "术语表")
        XCTAssertEqual(skill.subtitle, "领域术语对照")
        XCTAssertEqual(skill.badges, ["qoder-skills"])
    }

    func testACPackagesVersionIsAMarkAndItsBundledSkillsBackADescriptionlessRow() throws {
        let agent = try agent(["cliList": one([
            "cliId": 1, "cliName": "harnax-cli", "version": "1.30.0",
            "skillList": [["skillId": 21, "skillName": "公告解析"], ["skillId": 22, "skillName": "估值表"]],
            "envBindings": [["envKey": "KUBECONFIG", "envValue": "/etc/k/config"]],
        ])])
        let cli = try section(agent, "agent.section.cli")
        XCTAssertEqual(cli.title, "harnax-cli")
        XCTAssertEqual(cli.subtitle, "公告解析 · 估值表")
        XCTAssertEqual(cli.badges, ["1.30.0", hxCount("env.count", 1)])
    }

    func testACPackagesOwnDescriptionWinsOverTheBundledSkillNames() throws {
        let agent = try agent(["cliList": one([
            "cliId": 1, "cliName": "harnax-cli", "cliDescription": "集群运维",
            "skillList": [["skillId": 21, "skillName": "公告解析"]],
        ])])
        XCTAssertEqual(try section(agent, "agent.section.cli").subtitle, "集群运维")
    }

    func testASessionWithoutATitleIsNamedByItsRowIdNotItsWireId() throws {
        let agent = try agent(["sessionList": [
            ["id": 881, "title": "   ", "sessionId": "s-8842", "sessionDescription": "web · 3 小时前"],
            ["sessionId": "s-8790"],
        ]])
        let rows = AgentBindingsPresenter.sections(for: agent, chinese: false).first!.rows
        XCTAssertEqual(rows[0].title, hx("agent.binding.sessionFallback", 881))
        XCTAssertEqual(rows[0].subtitle, "web · 3 小时前")
        XCTAssertEqual(rows[1].title, "s-8790")
        XCTAssertTrue(rows[1].badges.isEmpty)
    }
}
