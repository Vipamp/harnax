import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// What the detail sheet does with the by-id executor read: which route the row picks, how often it fires,
/// and which panels the answer opens.
///
/// The panel contents themselves are `SessionDetailViewModelTests`' business; this file is about the read —
/// the three branches the console's effect takes (`DetailModal.tsx:112-148`), the refusal that must not be
/// mistaken for "no tools", and the two names of the same 失效 badge on the team row.
@MainActor
final class SessionDetailExecutorTests: XCTestCase {
    private let basic = "chat.detail.section.basic"
    private let executor = "chat.detail.section.executor"
    private let teamLead = "chat.detail.section.teamLead"
    private let tools = "chat.detail.section.tools"
    private let skills = "chat.detail.section.skills"
    private let cli = "chat.detail.section.cli"
    private let members = "chat.detail.section.members"
    private let mcp = "chat.detail.section.mcp"

    private func titles(_ vm: SessionDetailViewModel) -> [String] {
        vm.sections.map(\.titleKey)
    }

    /// A conversation with both snapshot lists filled, so a test can tell "the panel stayed because the
    /// snapshot still feeds it" from "the panel never appeared".
    private var snapshotted: SessionSummary {
        SessionSummary.stub(
            mcpList: [SessionMcpItem.stub()],
            skillList: [SessionSkillItem.stub()]
        )
    }

    // MARK: - which route the row picks

    func testAnAgentConversationReadsItsAgentsRow() async throws {
        let executors = FakeExecutors()
        executors.agentReply = .success(try ExecutorRows.agent(tools: [ExecutorRows.tool()]))
        let vm = SessionDetailViewModel(
            session: SessionSummary.stub(agentId: 92, teamId: nil), executor: executors
        )

        await vm.loadExecutor()

        XCTAssertEqual(executors.agentCalls, [92])
        XCTAssertEqual(executors.teamCalls, [])
        XCTAssertEqual(vm.executorPhase, .loaded)
    }

    func testATeamConversationReadsTheTeamsRowAndANamedTeamWins() async {
        let executors = FakeExecutors()
        let vm = SessionDetailViewModel(
            session: SessionSummary.stub(agentId: nil, teamId: 12), executor: executors
        )

        await vm.loadExecutor()

        XCTAssertEqual(executors.teamCalls, [12])
        XCTAssertEqual(executors.agentCalls, [])

        // The console tests `teamId` first and returns (`DetailModal.tsx:114-128`), so a hand-built row that
        // names both reads the team and never the agent. The backend cannot produce one — a team conversation
        // leaves `agent_id` NULL (`SessionServiceImpl.kt:199-207`).
        let both = SessionDetailViewModel(
            session: SessionSummary.stub(agentId: 92, teamId: 12), executor: executors
        )
        await both.loadExecutor()
        XCTAssertEqual(executors.teamCalls, [12, 12])
        XCTAssertEqual(executors.agentCalls, [])
    }

    func testARowThatNamesNoExecutorFiresNothing() async {
        let executors = FakeExecutors()
        let vm = SessionDetailViewModel(
            session: SessionSummary.stub(agentId: nil, teamId: nil), executor: executors
        )

        await vm.loadExecutor()

        XCTAssertEqual(executors.agentCalls, [])
        XCTAssertEqual(executors.teamCalls, [])
        XCTAssertEqual(vm.executorPhase, .none)
        XCTAssertFalse(vm.canReadExecutor)
        XCTAssertNil(vm.executorNotice)
    }

    func testAHostThatWiredNoLegStaysSilentAboutIt() async {
        // The list screen's host may carry no executor read; the sheet then shows the snapshot panels and no
        // line claiming anything is being fetched or failed.
        let vm = SessionDetailViewModel(session: snapshotted)
        await vm.loadExecutor()

        XCTAssertEqual(vm.executorPhase, .none)
        XCTAssertFalse(vm.canReadExecutor)
        XCTAssertFalse(vm.isLoadingExecutor)
        XCTAssertNil(vm.executorNotice)
        XCTAssertEqual(titles(vm), [basic, executor, mcp, skills])
    }

    // MARK: - one read per open

    func testASecondCallWhileTheFirstIsOnTheWireAddsNoRead() async throws {
        let executors = FakeExecutors()
        executors.delayNanoseconds = 30_000_000
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        async let first = vm.loadExecutor()
        // Whichever call reaches the guard first has already settled on `.loading`, so this one is the sheet
        // reappearing after a background stint rather than a second request.
        await vm.loadExecutor()
        _ = await first

        XCTAssertEqual(executors.agentCalls, [92])
        XCTAssertEqual(vm.executorPhase, .loaded)
    }

    func testACallAfterTheRowLandedAddsNoRead() async {
        let executors = FakeExecutors()
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()
        await vm.loadExecutor()

        XCTAssertEqual(executors.agentCalls, [92])
    }

    func testTheSheetIsLoadingOnlyWhileTheAnswerIsOut() async throws {
        let executors = FakeExecutors()
        executors.delayNanoseconds = 40_000_000
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)
        XCTAssertFalse(vm.isLoadingExecutor)

        async let pending = vm.loadExecutor()
        // The phase is set before the read suspends, so the sheet's progress line is up as soon as it is out.
        try await Task.sleep(nanoseconds: 10_000_000)
        XCTAssertTrue(vm.isLoadingExecutor)
        XCTAssertNil(vm.executorNotice)
        _ = await pending

        XCTAssertFalse(vm.isLoadingExecutor)
    }

    // MARK: - a refusal is not an empty answer

    func testARefusalKeepsTheThreePanelsOffAndNamesTheError() async {
        let executors = FakeExecutors()
        executors.agentReply = .failure(.offline)
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()

        XCTAssertEqual(vm.executorPhase, .failed(.offline))
        XCTAssertTrue(vm.canReadExecutor)
        XCTAssertEqual(vm.executorNotice, hx("error.offline"))
        // The panels the snapshot cannot fill are absent, not empty: "No tool configuration" would say this
        // agent binds nothing, which is a claim the sheet has no answer for.
        XCTAssertFalse(titles(vm).contains(tools))
        XCTAssertFalse(titles(vm).contains(cli))
        XCTAssertFalse(titles(vm).contains(members))
        // …and the two the row does carry stay on screen, still fed by the snapshot.
        XCTAssertEqual(titles(vm), [basic, executor, mcp, skills])
    }

    func testAServerMessageSurvivesTheRefusal() async {
        let executors = FakeExecutors()
        executors.agentReply = .failure(.business(code: 500, message: "Agent not found"))
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()

        XCTAssertEqual(vm.executorNotice, "Agent not found", "the sheet shows the server's own words")
    }

    func testARetryPutsTheReadBackOnTheWireOnlyAfterARefusal() async throws {
        let executors = FakeExecutors()
        executors.agentReply = .failure(.timeout)
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()
        XCTAssertEqual(executors.agentCalls, [92])

        await vm.retryExecutorLoad()
        XCTAssertEqual(executors.agentCalls, [92, 92])
        XCTAssertEqual(vm.executorPhase, .failed(.timeout))

        // The row lands, and the retry is over: a further tap must not keep asking.
        executors.agentReply = .success(try ExecutorRows.agent(tools: [ExecutorRows.tool()]))
        await vm.retryExecutorLoad()
        XCTAssertEqual(executors.agentCalls, [92, 92, 92])
        XCTAssertEqual(vm.executorPhase, .loaded)
        XCTAssertTrue(titles(vm).contains(tools))
        XCTAssertNil(vm.executorNotice)

        await vm.retryExecutorLoad()
        XCTAssertEqual(executors.agentCalls, [92, 92, 92], "a loaded sheet has nothing to retry")
    }

    func testAHealthyRowIgnoresARetry() async {
        let executors = FakeExecutors()
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.retryExecutorLoad()

        XCTAssertEqual(executors.agentCalls, [], "no refusal, so no read")
        XCTAssertEqual(vm.executorPhase, .none)
    }

    // MARK: - what each answer opens

    func testAnAgentsRowOpensToolsAndCLIAndKeepsMembersOff() async throws {
        let agent = try ExecutorRows.agent(
            mcp: [ExecutorRows.mcp(envCount: 2)],
            skills: [ExecutorRows.agentSkill()],
            tools: [ExecutorRows.tool(name: "web_search", displayName: nil, displayNameZh: nil)],
            cli: [ExecutorRows.cli()]
        )
        let executors = FakeExecutors()
        executors.agentReply = .success(agent)
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()

        XCTAssertEqual(titles(vm), [basic, executor, tools, mcp, skills, cli])
        // A tool with no display name keeps the code name the server sent, the web's English choice
        // (`DetailModal.tsx:313-315`).
        XCTAssertEqual(rowValues(vm, tools), ["web_search"])
        XCTAssertEqual(rowValues(vm, cli), ["harnax-cli"])
        XCTAssertFalse(titles(vm).contains(members), "an agent conversation has no members to show")
        // The agent row says the same thing about MCP as the snapshot and adds each server's parameter count,
        // which is the one thing it brings to that panel.
        XCTAssertEqual(
            try XCTUnwrap(vm.sections.first { $0.titleKey == mcp }).rows.first?.marks,
            [.chip(text: hxCount("env.count", 2), tone: nil)]
        )
    }

    func testATeamsRowOpensMembersAndClosesTheThreeAgentPanels() async throws {
        let team = try ExecutorRows.team(
            skills: [ExecutorRows.teamSkill()],
            members: [ExecutorRows.member(agentName: "研报检索"), ExecutorRows.member(agentId: 93, agentName: "合规检查")]
        )
        let executors = FakeExecutors()
        executors.teamReply = .success(team)
        let vm = SessionDetailViewModel(session: snapshotted.withTeam, executor: executors)

        await vm.loadExecutor()

        XCTAssertEqual(titles(vm), [basic, teamLead, skills, members])
        // In the order the lead delegates to them, which is the order the server returned
        // (`TeamServiceImpl.kt:192-203`).
        XCTAssertEqual(rowValues(vm, members), ["研报检索", "合规检查"])
        XCTAssertFalse(titles(vm).contains(mcp), "a team binds no MCP server, so the snapshot's copy is dropped")
    }

    func testAGoneMemberStaysListedAndFlagged() async throws {
        // The binding table outlives the agent row, so the server keeps it and flags it; hiding the row would
        // leave the operator wondering where a member went.
        let team = try ExecutorRows.team(members: [
            ExecutorRows.member(agentId: 94, agentName: "旧风控", available: false),
            ExecutorRows.member(agentId: 95, agentName: "新材料", available: true),
        ])
        let executors = FakeExecutors()
        executors.teamReply = .success(team)
        let vm = SessionDetailViewModel(session: snapshotted.withTeam, executor: executors)

        await vm.loadExecutor()

        let rows = try XCTUnwrap(vm.sections.first { $0.titleKey == members }?.rows)
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].marks, [.badge(key: "chat.detail.badge.unavailable", tone: .danger)])
        XCTAssertEqual(rows[0].value, "旧风控")
        XCTAssertEqual(rows[0].detail, "拉取近三年的公告")
        XCTAssertEqual(rows[1].marks, [])
    }

    func testAGoneLeadSkillIsFlaggedAndTheLiveOneIsNot() async throws {
        let team = try ExecutorRows.team(skills: [
            ExecutorRows.teamSkill(skillId: 21, skillName: "公告解析", repository: nil, available: false),
            ExecutorRows.teamSkill(skillId: 22, skillName: "表格输出", repository: nil, available: true),
        ])
        let executors = FakeExecutors()
        executors.teamReply = .success(team)
        let vm = SessionDetailViewModel(session: snapshotted.withTeam, executor: executors)

        await vm.loadExecutor()

        let rows = try XCTUnwrap(vm.sections.first { $0.titleKey == skills }?.rows)
        XCTAssertEqual(rows.map(\.value), ["公告解析", "表格输出"])
        XCTAssertEqual(rows[0].marks, [.badge(key: "chat.detail.badge.unavailable", tone: .danger)])
        XCTAssertEqual(rows[1].marks, [])
    }

    func testAnAgentsSkillListOpensNoBadgeBecauseTheColumnDoesNotExist() async throws {
        let agent = try ExecutorRows.agent(skills: [ExecutorRows.agentSkill(skillName: "公告解析", repository: nil)])
        let executors = FakeExecutors()
        executors.agentReply = .success(agent)
        let vm = SessionDetailViewModel(session: snapshotted, executor: executors)

        await vm.loadExecutor()

        let rows = try XCTUnwrap(vm.sections.first { $0.titleKey == skills }?.rows)
        XCTAssertEqual(rows.map(\.value), ["公告解析"])
        XCTAssertEqual(rows[0].marks, [])
    }

    func testAnExecutorWithNothingBoundOpensNoPanelOfItsOwn() async throws {
        // The console shows an Empty illustration in each of these panels; a sheet that hides them says the
        // same thing in the voice the rest of this screen uses. `bindings` landed, so the phase is `.loaded`
        // and no retry line is offered.
        let executors = FakeExecutors()
        let vm = SessionDetailViewModel(session: SessionSummary.stub(), executor: executors)

        await vm.loadExecutor()

        XCTAssertEqual(vm.executorPhase, .loaded)
        XCTAssertNil(vm.executorNotice)
        XCTAssertEqual(titles(vm), [basic, executor])
    }

    // MARK: - the read and the write do not wait on each other

    func testTheConfigEditorWorksWhileTheExecutorReadIsStillOut() async throws {
        let executors = FakeExecutors()
        executors.delayNanoseconds = 40_000_000
        let config = ScriptedSessionConfig()
        let vm = SessionDetailViewModel(session: snapshotted, config: config, executor: executors)

        async let pending = vm.loadExecutor()
        try await Task.sleep(nanoseconds: 10_000_000)
        vm.beginConfigEdit()
        vm.setConfigPlan(true)
        XCTAssertTrue(vm.isEditingConfig)
        XCTAssertTrue(vm.canSaveConfig)
        _ = await pending
    }

    func testAConfigWriteDoesNotReFireTheExecutorRead() async throws {
        let executors = FakeExecutors()
        let config = ScriptedSessionConfig()
        config.writeResult = .success(EmptyResponse())
        let vm = SessionDetailViewModel(session: snapshotted, config: config, executor: executors)

        await vm.loadExecutor()
        vm.beginConfigEdit()
        vm.setConfigPlan(true)
        await vm.saveConfig()

        XCTAssertEqual(executors.agentCalls, [92], "the write's read-back goes to the config leg only")
        XCTAssertEqual(vm.executorPhase, .loaded)
    }

    // MARK: - helpers

    private func rowValues(_ vm: SessionDetailViewModel, _ titleKey: String) -> [String?] {
        (vm.sections.first { $0.titleKey == titleKey }?.rows ?? []).map(\.value)
    }
}

private extension SessionSummary {
    /// The same conversation moved onto a team, which is the shape `isTeamConversation` reads.
    var withTeam: SessionSummary {
        SessionSummary.stub(
            agentId: nil,
            teamId: 12,
            name: "估值小组",
            mcpList: mcpList,
            skillList: skillList
        )
    }
}
