import XCTest
import SwiftUI
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Order arrangement for the lists that have a place in them: the agent's tools, MCP servers, skills and CLIs,
/// and a team's members.
///
/// The gap this closes is `specs/01-agent-team.md` 注意点 6 — 「顺序编辑缺交互」 — and the two facts under it,
/// both verified against the backend before any of this was written:
///
/// * nothing new has to be saved. Every binding table is deleted and re-inserted walking the request array in
///   order (`AgentServiceImpl.kt:400`-`:404` tools, `:439`-`:442` MCP, `:528`-`:535` skills, `:557`-`:560` CLI,
///   `deleteByAgentId` at `:249`-`:252`) and read back `ORDER BY id` (the four `Agent*BindingMapper.xml` files,
///   `TeamMemberMapper.xml:16`). The array the form sends *is* the stored order;
/// * an arranged order is only worth arranging if a pure rearrangement still reaches the wire. The agent's four
///   lists and the team's members are always assigned in `buildDraft`, so they do — which is what
///   `testAnAgentBodyAlwaysCarriesAllFourListsSoAnArrangementIsSent` and
///   `testReorderingMembersAloneIsEnoughToChangeTheBody` hold. The team's *skills* are the exception, because
///   that field's no-send rule compares the set and a rearrangement alone is judged unchanged
///   (`skillIDsIfChanged`, 注意点 11); that step therefore draws no handles, and
///   `testReorderingOnlyTheSkillRowsIsStillNotASave` keeps the rule honest.
///
/// The invariants an arrangement must not break are the expensive part, so they are asserted rather than
/// assumed: parameters belong to the row they were typed on (per-row `UUID` for tools and MCPs, a dictionary
/// keyed by package id for CLIs, a field on the row for a member's delegation text), 「别的行已选不再列出」 is
/// computed from row identity and not from index, and the comma string skills go out as keeps the arranged order.
///
/// Indexes are `onMove` indexes throughout — the destination counts the list with the moved row already taken
/// out — because that is what SwiftUI's transfer gesture would hand the same API, so a row that can be dragged
/// later needs no second move rule.
@MainActor
final class BindingOrderArrangementTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HarnaxCatalog.shared.language = .en
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    // MARK: - the four agent lists land in the arranged order

    func testMovingAToolRowMovesItsPlaceInTheToolArray() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [
                ["toolId": 11, "toolName": "fetch_url"],
                ["toolId": 12, "toolName": "read_file"],
                ["toolId": 13, "toolName": "write_file"],
            ]
        )))

        rig.vm.moveToolRow(from: 2, to: 0)

        XCTAssertEqual(rig.vm.toolRows.map(\.toolID), [13, 11, 12])
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().tools).map(\.id), [13, 11, 12])
        XCTAssertEqual(
            try ids(in: try json(rig.vm.buildDraft()), "toolList"),
            [13, 11, 12],
            "the body is what the insert loop walks, so this is the stored order"
        )
    }

    /// An `onMove`-shaped move to the tail lands on the shortened list's last index, not the old one.
    func testMovingToTheTailUsesTheShortenedListsIndex() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [
                ["toolId": 11, "toolName": "a"],
                ["toolId": 12, "toolName": "b"],
                ["toolId": 13, "toolName": "c"],
            ]
        )))

        rig.vm.moveToolRow(from: 0, to: 2)

        XCTAssertEqual(rig.vm.toolRows.map(\.toolID), [12, 13, 11])
    }

    func testMovingAnMCPRowMovesItsPlaceInTheMcpArray() throws {
        let rig = try rig(mode: .edit(try agent(
            mcps: [["mcpId": 21, "mcpName": "报表服务"], ["mcpId": 22, "mcpName": "检索服务"]]
        )))

        rig.vm.moveMcpRow(from: 1, to: 0)

        XCTAssertEqual(rig.vm.mcpRows.map(\.mcpID), [22, 21])
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().mcps).map(\.id), [22, 21])
        XCTAssertEqual(try ids(in: try json(rig.vm.buildDraft()), "mcpList"), [22, 21])
    }

    /// Skills are the one kind that goes out as a comma string, so the arrangement has to survive the join —
    /// the join is the only place the order could be lost (`AgentSaveDraft.encode` maps `skillIDs` in order and
    /// joins with 「,」; `AgentServiceImpl.kt:528`-`:535` inserts them in that order).
    func testMovingASkillRowRewritesTheCommaStringInTheArrangedOrder() throws {
        let rig = try rig(mode: .edit(try agent(skills: [
            ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
            ["skillId": 52, "skillName": "DOCX", "repositoryId": 7],
            ["skillId": 53, "skillName": "XLSX", "repositoryId": 7],
        ])))

        rig.vm.moveSkillRow(from: 2, to: 0)

        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().skillIDs), [53, 51, 52])
        XCTAssertEqual(try json(rig.vm.buildDraft())["skillList"] as? String, "53,51,52")
    }

    func testMovingACLIRowMovesItsPlaceInTheCLIArray() throws {
        let rig = try rig(mode: .edit(try agent(clis: [
            ["cliId": 31, "cliName": "harnax-cli"],
            ["cliId": 32, "cliName": "report-cli"],
        ])))

        rig.vm.moveCLI(from: 1, to: 0)

        XCTAssertEqual(rig.vm.selectedCLIIDs, [32, 31])
        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().clis).map(\.id), [32, 31])
        XCTAssertEqual(try ids(in: try json(rig.vm.buildDraft()), "cliList"), [32, 31])
    }

    // MARK: - a move takes its own parameters along

    /// The one most likely to be got wrong, and the reason the rows are value types: the typed values ride on
    /// the row, so an arrangement cannot put one tool's key on another tool.
    func testMovingAToolRowTakesItsTypedParametersWithIt() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [
                ["toolId": 11, "toolName": "fetch_url", "envBindings": [["envKey": "API_KEY", "customValue": "for-fetch"]]],
                ["toolId": 12, "toolName": "read_file", "envBindings": [["envKey": "API_KEY", "customValue": "for-read"]]],
            ]
        )))

        rig.vm.moveToolRow(from: 1, to: 0)

        let tools = try XCTUnwrap(rig.vm.buildDraft().tools)
        XCTAssertEqual(tools.count, 2)
        XCTAssertEqual(tools[0].id, 12)
        XCTAssertEqual(tools[0].envBindings, [.custom(envKey: "API_KEY", value: "for-read")])
        XCTAssertEqual(tools[1].id, 11)
        XCTAssertEqual(tools[1].envBindings, [.custom(envKey: "API_KEY", value: "for-fetch")])
    }

    /// The confirmation flag is the other per-row value, and it is not index-addressed either.
    func testMovingAToolRowTakesItsConfirmFlagWithIt() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [
                ["toolId": 11, "toolName": "a", "needConfirm": true],
                ["toolId": 12, "toolName": "b", "needConfirm": false],
            ]
        )))

        rig.vm.moveToolRow(from: 0, to: 1)

        let tools = try XCTUnwrap(rig.vm.buildDraft().tools)
        XCTAssertEqual(tools.map(\.id), [12, 11])
        XCTAssertEqual(tools.map(\.needConfirm), [false, true])
    }

    func testMovingAnMCPRowTakesItsParametersWithIt() throws {
        let rig = try rig(mode: .edit(try agent(
            mcps: [
                ["mcpId": 21, "mcpName": "报表服务", "envBindings": [["envKey": "BASE_URL", "customValue": "https://a"]]],
                ["mcpId": 22, "mcpName": "检索服务", "envBindings": [["envKey": "BASE_URL", "customValue": "https://b"]]],
            ]
        )))

        rig.vm.moveMcpRow(from: 0, to: 1)

        let mcps = try XCTUnwrap(rig.vm.buildDraft().mcps)
        XCTAssertEqual(mcps[0].id, 22)
        XCTAssertEqual(mcps[0].envBindings, [.custom(envKey: "BASE_URL", value: "https://b")])
        XCTAssertEqual(mcps[1].id, 21)
        XCTAssertEqual(mcps[1].envBindings, [.custom(envKey: "BASE_URL", value: "https://a")])
    }

    /// The CLI tables live in a dictionary keyed by package id, which is the structural reason they cannot
    /// follow a row's index — the pairing is asserted here so a later refactor to index addressing is charged.
    func testMovingACLIRowTakesItsParametersWithIt() throws {
        let rig = try rig(mode: .edit(try agent(
            clis: [
                ["cliId": 31, "cliName": "harnax-cli", "envBindings": [["envKey": "TOKEN", "customValue": "one"]]],
                ["cliId": 32, "cliName": "report-cli", "envBindings": [["envKey": "TOKEN", "customValue": "two"]]],
            ]
        )))

        rig.vm.moveCLI(from: 0, to: 1)

        let clis = try XCTUnwrap(rig.vm.buildDraft().clis)
        XCTAssertEqual(clis.map(\.id), [32, 31])
        XCTAssertEqual(clis[0].envBindings, [.custom(envKey: "TOKEN", value: "two")])
        XCTAssertEqual(clis[1].envBindings, [.custom(envKey: "TOKEN", value: "one")])
    }

    /// Cards are picked into by index, so an arrangement changes which package index 0 means — and the name and
    /// the typed table must follow the package, not the slot.
    func testAReorderedCLICardIsStillPickedAtItsNewIndex() async throws {
        let rig = try rig(mode: .edit(try agent(clis: [
            ["cliId": 31, "cliName": "harnax-cli"],
        ])))
        try seed(rig, clis: [
            ["id": 31, "name": "harnax-cli", "envParams": [["envParamName": "TOKEN"]]],
            ["id": 32, "name": "report-cli", "envParams": [["envParamName": "PROXY"]]],
        ])
        await rig.vm.load(.cli)
        rig.vm.addCLI()
        XCTAssertEqual(rig.vm.selectedCLIIDs, [31, 32], "the add appends the first unselected package")

        rig.vm.moveCLI(from: 1, to: 0)
        XCTAssertEqual(rig.vm.selectedCLIIDs, [32, 31])

        rig.vm.pickCLI(HXEntityPickerOption(id: 99, title: "third-cli"), at: 0)
        XCTAssertEqual(rig.vm.selectedCLIIDs, [99, 31], "index 0 now holds the card that moved there")
        XCTAssertEqual(rig.vm.cliName(for: 99), "third-cli")
    }

    func testMovingAMemberRowTakesItsDelegationTextWithIt() throws {
        let rig = teamRig(mode: .edit(try team(
            members: [
                ["agentId": 11, "agentName": "研究员", "delegationDescription": "负责抓取"],
                ["agentId": 12, "agentName": "写手", "delegationDescription": "负责成稿"],
            ]
        )))

        rig.vm.moveMemberRow(from: 1, to: 0)

        XCTAssertEqual(
            try XCTUnwrap(rig.vm.buildDraft().members),
            [
                TeamMemberDraft(agentId: 12, delegationDescription: "负责成稿"),
                TeamMemberDraft(agentId: 11, delegationDescription: "负责抓取"),
            ]
        )
    }

    // MARK: - the team's members

    func testMovingAMemberRowRewritesTheMemberArrayOrder() throws {
        let rig = teamRig(mode: .edit(try team(
            members: [
                ["agentId": 11, "agentName": "研究员"],
                ["agentId": 12, "agentName": "写手"],
                ["agentId": 13, "agentName": "审校"],
            ]
        )))

        rig.vm.moveMemberRow(from: 2, to: 0)

        XCTAssertEqual(rig.vm.memberRows.map(\.agentID), [13, 11, 12])
        let body = try json(rig.vm.buildDraft())
        XCTAssertEqual(try ids(in: body, "members", id: "agentId"), [13, 11, 12])
    }

    // MARK: - a rearrangement is a change

    /// 「不动即不发」 must not be read backwards: an arrangement-only edit has to reach the server. All four
    /// agent lists are always assigned, so nothing on this form can be judged unchanged — the assertion is that
    /// the four keys are on the body at all, which is what makes the arrangement load-bearing.
    func testAnAgentBodyAlwaysCarriesAllFourListsSoAnArrangementIsSent() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [["toolId": 11, "toolName": "fetch_url"]],
            mcps: [["mcpId": 21, "mcpName": "报表服务"]],
            skills: [["skillId": 51, "skillName": "PDF", "repositoryId": 7]],
            clis: [["cliId": 31, "cliName": "harnax-cli"]]
        )))
        let untouched = try json(rig.vm.buildDraft())
        for key in ["toolList", "mcpList", "skillList", "cliList"] {
            XCTAssertTrue(untouched.keys.contains(key), "\(key) is always sent, touched or not")
        }

        rig.vm.addToolRow()
        rig.vm.pickTool(HXEntityPickerOption(id: 12, title: "read_file"), into: rig.vm.toolRows[1].id)
        rig.vm.moveToolRow(from: 1, to: 0)

        XCTAssertEqual(
            try ids(in: try json(rig.vm.buildDraft()), "toolList"),
            [12, 11],
            "a rearrangement of a list the form always sends is a save by construction"
        )
    }

    /// Members go out the same way, so arranging the delegation order is a change even when nothing else moved.
    func testReorderingMembersAloneIsEnoughToChangeTheBody() throws {
        let rig = teamRig(mode: .edit(try team(
            members: [
                ["agentId": 11, "agentName": "研究员"],
                ["agentId": 12, "agentName": "写手"],
            ]
        )))

        rig.vm.moveMemberRow(from: 0, to: 1)
        let draft = rig.vm.buildDraft()

        XCTAssertTrue(try json(draft).keys.contains("members"), "the field is on the body, not left off")
        XCTAssertEqual(try XCTUnwrap(draft.members).map(\.agentId), [12, 11])
    }

    /// The team's *skill* field keeps the set comparison it was written with (注意点 11): an arrangement of
    /// those rows alone is judged unchanged, which is exactly why that step draws no handles. Held here so the
    /// omission stays a decision with a rule behind it rather than a forgotten control.
    func testReorderingOnlyTheSkillRowsIsStillNotASave() throws {
        let rig = teamRig(mode: .edit(try team(
            skills: [
                ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
                ["skillId": 52, "skillName": "DOCX", "repositoryId": 7],
            ]
        )))

        rig.vm.skillRows.reverse()

        XCTAssertNil(rig.vm.buildDraft().skillIDs)
        XCTAssertFalse(try json(rig.vm.buildDraft()).keys.contains("skillIds"))
    }

    /// Once the skill set really does change, what goes out is the on-screen order — so an operator who arranges
    /// the rows and then edits one of them does get the arrangement stored.
    func testAChangedSkillSetGoesOutInTheArrangedOrder() throws {
        let rig = teamRig(mode: .edit(try team(
            skills: [
                ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
                ["skillId": 52, "skillName": "DOCX", "repositoryId": 7],
                ["skillId": 53, "skillName": "XLSX", "repositoryId": 7],
            ]
        )))

        rig.vm.skillRows.reverse()
        rig.vm.removeSkillRow(rig.vm.skillRows[1].id)

        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().skillIDs), [53, 51])
        XCTAssertEqual(try json(rig.vm.buildDraft())["skillIds"] as? [Int], [53, 51])
    }

    // MARK: - what a move may not touch

    func testAMoveOutsideTheListOrOntoItselfDoesNothing() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [["toolId": 11, "toolName": "a"], ["toolId": 12, "toolName": "b"]]
        )))

        rig.vm.moveToolRow(from: 5, to: 0)
        rig.vm.moveToolRow(from: 0, to: 5)
        rig.vm.moveToolRow(from: 0, to: 0)
        XCTAssertEqual(rig.vm.toolRows.map(\.toolID), [11, 12])

        rig.vm.moveCLI(from: 0, to: 9)
        XCTAssertTrue(rig.vm.selectedCLIIDs.isEmpty, "this agent binds no CLI, and an out-of-range move cannot make it")
    }

    /// 「别的行已选不再列出」 is computed from the rows, not from their places, so an arrangement must leave the
    /// exclusion the set it was before.
    func testAReorderLeavesTheOtherRowsChoicesStillHidden() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [["toolId": 11, "toolName": "a"], ["toolId": 12, "toolName": "b"], ["toolId": 13, "toolName": "c"]],
            mcps: [["mcpId": 21, "mcpName": "x"], ["mcpId": 22, "mcpName": "y"]],
            skills: [
                ["skillId": 51, "skillName": "PDF", "repositoryId": 7],
                ["skillId": 52, "skillName": "DOCX", "repositoryId": 7],
            ]
        )))
        let lastTool = rig.vm.toolRows[2].id
        let secondMCP = rig.vm.mcpRows[1].id
        let secondSkill = rig.vm.skillRows[1].id
        let before = (
            tools: rig.vm.excludedToolIDs(except: lastTool),
            mcps: rig.vm.excludedMCPIDs(except: secondMCP),
            skills: rig.vm.excludedSkillIDs(except: secondSkill),
            clis: rig.vm.excludedCLIIDs
        )

        rig.vm.moveToolRow(from: 0, to: 2)
        rig.vm.moveMcpRow(from: 0, to: 1)
        rig.vm.moveSkillRow(from: 0, to: 1)

        XCTAssertEqual(rig.vm.excludedToolIDs(except: lastTool), before.tools, "the same ids, reached by row identity")
        XCTAssertEqual(rig.vm.excludedMCPIDs(except: secondMCP), before.mcps)
        XCTAssertEqual(rig.vm.excludedSkillIDs(except: secondSkill), before.skills)
        XCTAssertEqual(rig.vm.excludedCLIIDs, before.clis)
    }

    /// A row that names an entity the candidate page no longer holds keeps its id through an arrangement —
    /// broken references stay visible and sendable (注意点 12), and moving them must not drop them.
    func testAReferenceThatFellOutOfTheCandidatesSurvivesAnArrangement() throws {
        let rig = try rig(mode: .edit(try agent(
            tools: [["toolId": 11, "toolName": "a"], ["toolId": 99, "toolName": "gone_tool"]]
        )))

        rig.vm.moveToolRow(from: 1, to: 0)

        XCTAssertEqual(try XCTUnwrap(rig.vm.buildDraft().tools).map(\.id), [99, 11])
    }

    // MARK: - plumbing

    private struct Rig {
        let vm: AgentFormViewModel
        let clis: FakeClis
    }

    private struct TeamRig {
        let vm: TeamFormViewModel
    }

    private func rig(mode: AgentFormViewModel.Mode = .create) throws -> Rig {
        let clis = FakeClis()
        let vm = AgentFormViewModel(
            mode: mode,
            account: AccountSnapshot(username: "admin", isAdministrator: true),
            agents: FakeAgents(),
            writer: FakeAgents(),
            models: FakeModelCatalog(),
            tools: FakeToolCatalog(),
            mcp: FakeMcpServers(),
            skills: FakeSkills(),
            clis: clis,
            envVars: FakeEnvVars()
        )
        return Rig(vm: vm, clis: clis)
    }

    private func teamRig(mode: TeamFormViewModel.Mode = .create) -> TeamRig {
        let vm = TeamFormViewModel(
            mode: mode,
            account: AccountSnapshot(username: "admin", isAdministrator: true),
            teams: FakeTeams(),
            writer: FakeTeams(),
            models: FakeModelCatalog(),
            skills: FakeSkills(),
            agents: FakeAgents()
        )
        return TeamRig(vm: vm)
    }

    /// An agent row carrying the four binding lists. The read side spells an item's id `toolId`, `mcpId`,
    /// `skillId` and `cliId` while the write side wants `id` — the asymmetry 注意点 9 names, so the fixtures
    /// spell it out rather than let a stub guess.
    private func agent(
        tools: [[String: Any]] = [],
        mcps: [[String: Any]] = [],
        skills: [[String: Any]] = [],
        clis: [[String: Any]] = []
    ) throws -> AgentSummary {
        try AgentSummary.stub([
            "id": 3,
            "name": "报表",
            "description": "抓取并汇总",
            "systemPrompt": "你负责抓取",
            "modelId": 1,
            "isPublic": 1,
            "toolList": tools,
            "mcpList": mcps,
            "skillList": skills,
            "cliList": clis,
        ])
    }

    /// A team row. `TeamResponse.kt` gives `skillList` and `memberList` non-null defaults and every item a
    /// non-null availability flag, so both are spelled out here too.
    private func team(
        skills: [[String: Any]] = [],
        members: [[String: Any]] = []
    ) throws -> TeamSummary {
        try TeamSummary.stub([
            "id": 3,
            "name": "报表团队",
            "systemPrompt": "你负责拆解目标",
            "modelId": 1,
            "skillList": skills.map { flag($0, "skillAvailable") },
            "memberList": members.map { flag($0, "agentAvailable") },
        ])
    }

    private func flag(_ item: [String: Any], _ key: String) -> [String: Any] {
        guard item[key] == nil else { return item }
        var filled = item
        filled[key] = true
        return filled
    }

    private func seed(_ rig: Rig, clis rows: [[String: Any]]) throws {
        let filled = rows.map { row -> [String: Any] in
            guard let params = row["envParams"] as? [[String: Any]] else { return row }
            var updated = row
            // A declared parameter always carries its two flags: `required` and `secret` are non-null booleans
            // with a default (`ToolEnvParamEntry.kt:25`, `:28`), so Jackson writes them even when the fixture
            // is about something else entirely.
            updated["envParams"] = params.map { param in
                var entry = param
                if entry["required"] == nil { entry["required"] = false }
                if entry["secret"] == nil { entry["secret"] = false }
                return entry
            }
            return updated
        }
        rig.clis.replies = [.success(try PageStub.page(CliSummary.self, filled))]
    }

    /// The `id` of every item of a body array, in the order the array holds them — which is the point of these
    /// tests, since that order is what the insert loop walks.
    private func ids(in body: [String: Any], _ arrayKey: String, id idKey: String = "id") throws -> [Int] {
        let items = try XCTUnwrap(body[arrayKey] as? [[String: Any]], "\(arrayKey) is not the array of objects the draft encodes")
        return try items.map { try XCTUnwrap($0[idKey] as? Int) }
    }

    private func json<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}
