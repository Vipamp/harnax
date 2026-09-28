import XCTest

@testable import HarnaxCore

/// The list row is the whole payload: `GET /api/admin/agents/page` answers the four binding lists and the
/// session list per row, so the card and its drill-down never ask a second endpoint.
///
/// Key names follow `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/AgentResponse.kt:91-181`,
/// not the `{id, name}` shape the web console's own state uses — a binding row whose keys were wrong still
/// decoded, it just decoded to all-nil names, and the counts alone would have looked green.
final class AgentBindingTests: XCTestCase {
    private func row(_ index: Int) throws -> AgentSummary {
        let page = try Fixture.decode(Envelope<Page<AgentSummary>>.self, "agents-page-two-rows").data!
        return page.records[index]
    }

    func testBindingListsReachTheCounts() throws {
        let agent = try row(0)
        XCTAssertEqual(agent.mcpCount, 1)
        XCTAssertEqual(agent.skillCount, 2)
        XCTAssertEqual(agent.toolCount, 3)
        XCTAssertEqual(agent.cliCount, 1)
        XCTAssertEqual(agent.sessionTotal, 3, "sessionCount wins over the shorter sessionList it carries")
        XCTAssertEqual(agent.sessionList?.count, 3)
    }

    /// A row the backend sent with `non_null` inclusion leaves the lists absent altogether, which is a
    /// count of zero and not a decoding failure.
    func testSparseRowHasNoListsAndNoTitle() throws {
        let agent = try row(1)
        XCTAssertNil(agent.modelName)
        XCTAssertNil(agent.sessionCount)
        XCTAssertEqual(agent.mcpCount, 0)
        XCTAssertEqual(agent.toolCount, 0)
        XCTAssertEqual(agent.sessionTotal, 0)
        XCTAssertFalse(agent.isEnabled)
        XCTAssertFalse(agent.isShared)
    }

    func testSkillBindingNamesItsRepository() throws {
        let skills = try row(0).skillList!
        XCTAssertEqual(skills[0].name, "术语表")
        XCTAssertEqual(skills[0].repository, "qoder-skills")
        XCTAssertEqual(skills[0].description, "领域术语对照")
        XCTAssertNil(skills[1].description)
    }

    /// Chinese reads `中文名 → 显示名 → 代码名`, English skips the Chinese column
    /// (`harnax-webui/src/pages/agent/index.tsx:105-110`).
    func testToolNameChainPerLanguage() throws {
        let tools = try row(0).toolList!
        XCTAssertEqual(tools[0].name(chinese: true), "联网搜索")
        XCTAssertEqual(tools[0].name(chinese: false), "Web Search")
        XCTAssertEqual(tools[1].name(chinese: true), "write-file", "no Chinese column, so the display name stands")
        XCTAssertNil(tools[2].name(chinese: true), "blank and whitespace-only columns are not names")
    }

    func testToolConfirmationIsOnlyEverOn() throws {
        let tools = try row(0).toolList!
        XCTAssertTrue(tools[0].requiresConfirmation)
        XCTAssertFalse(tools[1].requiresConfirmation)
        XCTAssertEqual(tools[0].envCount, 1)
        XCTAssertEqual(tools[1].envCount, 0)
    }

    /// The resolved value is either the latest value of a referenced variable — masked when sensitive — or
    /// what the operator typed. Whichever it is, it is display copy: writing a mask back would store asterisks.
    func testEnvironmentBindingDisplayValue() throws {
        let bindings = try row(0).mcpList!.first!.envBindings!
        XCTAssertEqual(bindings[0].envKey, "AMAP_KEY")
        XCTAssertEqual(bindings[0].displayValue, "******")
        XCTAssertTrue(bindings[0].referencesVariable)
        XCTAssertEqual(bindings[1].displayValue, "cn-hangzhou")
        XCTAssertFalse(bindings[1].referencesVariable)
    }

    func testCliBindingCarriesItsPackageSkill() throws {
        let cli = try row(0).cliList!.first!
        XCTAssertEqual(cli.name, "harnax-cli")
        XCTAssertEqual(cli.packageVersion, "1.30.0")
        XCTAssertEqual(cli.skillNames, ["公告解析"])
        XCTAssertEqual(cli.envCount, 1)
    }

    func testSessionRowsFallBackToTheirUUID() throws {
        let sessions = try row(0).sessionList!
        XCTAssertEqual(sessions[0].name, "周报生成")
        XCTAssertEqual(sessions[0].sessionId, "s-8842")
        XCTAssertNil(sessions[1].name)
        XCTAssertNil(sessions[2].name, "a whitespace-only title is not a name")
    }

    func testPresentedNormalizesEveryKindOfEmpty() {
        XCTAssertNil(hxPresented(nil))
        XCTAssertNil(hxPresented(""))
        XCTAssertNil(hxPresented("  \n "))
        XCTAssertEqual(hxPresented(" 术语表 "), "术语表")
    }

    func testTeamRowDecodesItsLeadAndMembers() throws {
        let page = try Fixture.decode(Envelope<Page<TeamSummary>>.self, "teams-page-one-row").data!
        let team = page.records[0]
        XCTAssertEqual(team.name, "投研团队")
        XCTAssertEqual(team.leadModelName, "qwen3.7-max")
        XCTAssertEqual(team.skillList.count, 2)
        XCTAssertFalse(team.skillList[0].isUnavailable)
        XCTAssertTrue(team.skillList[1].isUnavailable, "a deleted skill row stays visible and says so")
        XCTAssertEqual(team.memberList.count, 2)
        XCTAssertEqual(team.memberList[0].displayName, "数据抓取")
        XCTAssertTrue(team.memberList[1].isUnavailable)
        XCTAssertEqual(team.memberList[1].detail, "估值分析", "no delegation note, so the agent's own description stands")
        XCTAssertEqual(team.systemPrompt, "")
        XCTAssertEqual(team.modelId, 7, "non-null on the wire, so it is not an optional here")
    }
}
