import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// An appended page has to belong to the query that is on screen.
///
/// `refresh()` learned to carry an identity (`ListRefreshIdentityTests`), and `loadMore()` did not: it captured
/// nothing and ran `pages.append(with: page)` whatever the answer was for. A reader who changes the filter or
/// the keyword while page 2 is on the wire therefore gets the OLD query's rows spliced into the NEW list, and
/// `PagedState.append` advances `pageNum` and `total` from a reply that belongs to a query nobody is looking at
/// any more — so the next scroll asks for a page number of that retired query.
///
/// Two scenarios, run against all thirteen list models because all thirteen append:
///
/// - a late page must not land at all: not its rows, not its total, not its page counter;
/// - an append asked for *while a refresh is out* must be re-issued for the newer query once that refresh
///   answers, because the reader is still waiting for page 2 and silently discarding the request would leave
///   the list one page short forever.
///
/// The machinery is the one the refresh-identity tests already use: a page reply held open by the domain's own
/// double (`PageReadGate`), a scripted queue of pages, and the state read back through `LateAppend` so the two
/// scenarios are written once.
@MainActor
final class ListAppendIdentityTests: XCTestCase {
    // MARK: - the thirteen screens

    func testTheAgentListDropsALateAppendPage() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try page(AgentSummary.self, .plain, [1], total: 4)),
            .success(try page(AgentSummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(AgentSummary.self, .plain, [5], total: 1)),
        ]
        let screen = AgentListViewModel(agents: agents, pageSize: 1)
        await screen.refresh()
        try await assertALateAppendPageNeverLands(on: agentScreen(screen, agents))
    }

    func testTheAgentListReissuesAnAppendAskedForDuringARefresh() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try page(AgentSummary.self, .plain, [1], total: 4)),
            .success(try page(AgentSummary.self, .plain, [5], total: 2)),
            .success(try page(AgentSummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = AgentListViewModel(agents: agents, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: agentScreen(vm, agents))
    }

    func testTheSkillSourceListDropsALateAppendPage() async throws {
        let skills = FakeSkills()
        skills.sourceReplies = [
            .success(try page(SkillSourceSummary.self, .source, [1], total: 4)),
            .success(try page(SkillSourceSummary.self, .source, [2], num: 2, total: 4)),
            .success(try page(SkillSourceSummary.self, .source, [5], total: 1)),
        ]
        let vm = SkillSourceListViewModel(skills: skills, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: sourceScreen(vm, skills))
    }

    func testTheSkillSourceListReissuesAnAppendAskedForDuringARefresh() async throws {
        let skills = FakeSkills()
        skills.sourceReplies = [
            .success(try page(SkillSourceSummary.self, .source, [1], total: 4)),
            .success(try page(SkillSourceSummary.self, .source, [5], total: 2)),
            .success(try page(SkillSourceSummary.self, .source, [6], num: 2, total: 2)),
        ]
        let vm = SkillSourceListViewModel(skills: skills, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: sourceScreen(vm, skills))
    }

    func testTheSkillTableDropsALateAppendPage() async throws {
        let skills = FakeSkills()
        skills.skillPageReplies = [
            .success(try page(SkillItem.self, .skillItem, [1], total: 4)),
            .success(try page(SkillItem.self, .skillItem, [2], num: 2, total: 4)),
            .success(try page(SkillItem.self, .skillItem, [5], total: 1)),
        ]
        let vm = SkillTableViewModel(skills: skills, pageSize: 1)
        await vm.show(id: 7)
        try await assertALateAppendPageNeverLands(on: skillTableScreen(vm, skills))
    }

    func testTheSkillTableReissuesAnAppendAskedForDuringARefresh() async throws {
        let skills = FakeSkills()
        skills.skillPageReplies = [
            .success(try page(SkillItem.self, .skillItem, [1], total: 4)),
            .success(try page(SkillItem.self, .skillItem, [5], total: 2)),
            .success(try page(SkillItem.self, .skillItem, [6], num: 2, total: 2)),
        ]
        let vm = SkillTableViewModel(skills: skills, pageSize: 1)
        await vm.show(id: 7)
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: skillTableScreen(vm, skills))
    }

    func testTheCliListDropsALateAppendPage() async throws {
        let clis = FakeClis()
        clis.replies = [
            .success(try page(CliSummary.self, .plain, [1], total: 4)),
            .success(try page(CliSummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(CliSummary.self, .plain, [5], total: 1)),
        ]
        let vm = CliListViewModel(clis: clis, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: cliScreen(vm, clis))
    }

    func testTheCliListReissuesAnAppendAskedForDuringARefresh() async throws {
        let clis = FakeClis()
        clis.replies = [
            .success(try page(CliSummary.self, .plain, [1], total: 4)),
            .success(try page(CliSummary.self, .plain, [5], total: 2)),
            .success(try page(CliSummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = CliListViewModel(clis: clis, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: cliScreen(vm, clis))
    }

    func testTheProviderListDropsALateAppendPage() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [
            .success(try page(ModelProviderSummary.self, .provider, [1], total: 4)),
            .success(try page(ModelProviderSummary.self, .provider, [2], num: 2, total: 4)),
            .success(try page(ModelProviderSummary.self, .provider, [5], total: 1)),
        ]
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: providerScreen(vm, catalog))
    }

    func testTheProviderListReissuesAnAppendAskedForDuringARefresh() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [
            .success(try page(ModelProviderSummary.self, .provider, [1], total: 4)),
            .success(try page(ModelProviderSummary.self, .provider, [5], total: 2)),
            .success(try page(ModelProviderSummary.self, .provider, [6], num: 2, total: 2)),
        ]
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: providerScreen(vm, catalog))
    }

    func testTheModelListDropsALateAppendPage() async throws {
        let catalog = FakeModelCatalog()
        catalog.modelReplies = [
            .success(try page(ModelSummary.self, .plain, [1], total: 4)),
            .success(try page(ModelSummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(ModelSummary.self, .plain, [5], total: 1)),
        ]
        let vm = ModelListViewModel(providerID: 3, catalog: catalog, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: modelScreen(vm, catalog))
    }

    func testTheModelListReissuesAnAppendAskedForDuringARefresh() async throws {
        let catalog = FakeModelCatalog()
        catalog.modelReplies = [
            .success(try page(ModelSummary.self, .plain, [1], total: 4)),
            .success(try page(ModelSummary.self, .plain, [5], total: 2)),
            .success(try page(ModelSummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = ModelListViewModel(providerID: 3, catalog: catalog, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: modelScreen(vm, catalog))
    }

    func testTheApiKeyListDropsALateAppendPage() async throws {
        let keys = FakeApiKeys()
        keys.replies = [
            .success(try page(ApiKeySummary.self, .plain, [1], total: 4)),
            .success(try page(ApiKeySummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(ApiKeySummary.self, .plain, [5], total: 1)),
        ]
        let vm = ApiKeyListViewModel(catalog: keys, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: apiKeyScreen(vm, keys))
    }

    func testTheApiKeyListReissuesAnAppendAskedForDuringARefresh() async throws {
        let keys = FakeApiKeys()
        keys.replies = [
            .success(try page(ApiKeySummary.self, .plain, [1], total: 4)),
            .success(try page(ApiKeySummary.self, .plain, [5], total: 2)),
            .success(try page(ApiKeySummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = ApiKeyListViewModel(catalog: keys, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: apiKeyScreen(vm, keys))
    }

    /// The environment-variable page has no status filter on its route, so the query the reader changes to
    /// arrives through the debounced keyword rather than the filter that drives the other thirteen.
    func testTheEnvVarListDropsALateAppendPage() async throws {
        let vars = FakeEnvVars()
        vars.replies = [
            .success(try page(EnvVarSummary.self, .plain, [1], total: 4)),
            .success(try page(EnvVarSummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(EnvVarSummary.self, .plain, [5], total: 1)),
        ]
        let vm = EnvVarListViewModel(catalog: vars, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: envVarScreen(vm, vars))
    }

    func testTheEnvVarListReissuesAnAppendAskedForDuringARefresh() async throws {
        let vars = FakeEnvVars()
        vars.replies = [
            .success(try page(EnvVarSummary.self, .plain, [1], total: 4)),
            .success(try page(EnvVarSummary.self, .plain, [5], total: 2)),
            .success(try page(EnvVarSummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = EnvVarListViewModel(catalog: vars, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: envVarScreen(vm, vars))
    }

    /// The channel page reads a second route for the rows it lands (the sandbox status), so the append's
    /// guard has to sit in front of everything the retired page would have caused, not just the rows.
    func testTheChannelListDropsALateAppendPage() async throws {
        let channels = FakeChannels()
        channels.replies = [
            .success(try page(ChannelSummary.self, .plain, [1], total: 4)),
            .success(try page(ChannelSummary.self, .plain, [2], num: 2, total: 4)),
            .success(try page(ChannelSummary.self, .plain, [5], total: 1)),
        ]
        let vm = ChannelListViewModel(catalog: channels, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: channelScreen(vm, channels))
    }

    func testTheChannelListReissuesAnAppendAskedForDuringARefresh() async throws {
        let channels = FakeChannels()
        channels.replies = [
            .success(try page(ChannelSummary.self, .plain, [1], total: 4)),
            .success(try page(ChannelSummary.self, .plain, [5], total: 2)),
            .success(try page(ChannelSummary.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = ChannelListViewModel(catalog: channels, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: channelScreen(vm, channels))
    }

    func testTheTaskListDropsALateAppendPage() async throws {
        let tasks = FakeAgentTasks()
        tasks.pageReplies = [
            .success(try page(AgentTaskSummary.self, .task, [1], total: 4)),
            .success(try page(AgentTaskSummary.self, .task, [2], num: 2, total: 4)),
            .success(try page(AgentTaskSummary.self, .task, [5], total: 1)),
        ]
        let vm = TaskListViewModel(catalog: tasks, pageSize: 1, pollInterval: .seconds(60))
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: taskScreen(vm, tasks))
    }

    func testTheTaskListReissuesAnAppendAskedForDuringARefresh() async throws {
        let tasks = FakeAgentTasks()
        tasks.pageReplies = [
            .success(try page(AgentTaskSummary.self, .task, [1], total: 4)),
            .success(try page(AgentTaskSummary.self, .task, [5], total: 2)),
            .success(try page(AgentTaskSummary.self, .task, [6], num: 2, total: 2)),
        ]
        let vm = TaskListViewModel(catalog: tasks, pageSize: 1, pollInterval: .seconds(60))
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: taskScreen(vm, tasks))
    }

    /// The log page keeps a timer alive whatever the rows say, so its append is the one that can be overtaken
    /// by a filter the operator picked three seconds after the scroll. Its idle cadence is stretched here so
    /// the tick is not what the test is accidentally measuring.
    func testTheTaskLogListDropsALateAppendPage() async throws {
        let tasks = FakeAgentTasks()
        tasks.logReplies = [
            .success(try page(AgentTaskLog.self, .taskLog, [1], total: 4)),
            .success(try page(AgentTaskLog.self, .taskLog, [2], num: 2, total: 4)),
            .success(try page(AgentTaskLog.self, .taskLog, [5], total: 1)),
        ]
        let vm = makeLogViewModel(tasks)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: taskLogScreen(vm, tasks))
    }

    func testTheTaskLogListReissuesAnAppendAskedForDuringARefresh() async throws {
        let tasks = FakeAgentTasks()
        tasks.logReplies = [
            .success(try page(AgentTaskLog.self, .taskLog, [1], total: 4)),
            .success(try page(AgentTaskLog.self, .taskLog, [5], total: 2)),
            .success(try page(AgentTaskLog.self, .taskLog, [6], num: 2, total: 2)),
        ]
        let vm = makeLogViewModel(tasks)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: taskLogScreen(vm, tasks))
    }

    func testTheMcpListDropsALateAppendPage() async throws {
        let mcp = FakeMcpServers()
        mcp.pageReplies = [
            .success(try page(McpServerRow.self, .plain, [1], total: 4)),
            .success(try page(McpServerRow.self, .plain, [2], num: 2, total: 4)),
            .success(try page(McpServerRow.self, .plain, [5], total: 1)),
        ]
        let vm = McpListViewModel(mcp: mcp, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: mcpScreen(vm, mcp))
    }

    func testTheMcpListReissuesAnAppendAskedForDuringARefresh() async throws {
        let mcp = FakeMcpServers()
        mcp.pageReplies = [
            .success(try page(McpServerRow.self, .plain, [1], total: 4)),
            .success(try page(McpServerRow.self, .plain, [5], total: 2)),
            .success(try page(McpServerRow.self, .plain, [6], num: 2, total: 2)),
        ]
        let vm = McpListViewModel(mcp: mcp, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: mcpScreen(vm, mcp))
    }

    func testTheSessionListDropsALateAppendPage() async throws {
        let sessions = FakeSessions()
        sessions.replies = [
            .success(try page(SessionSummary.self, .session, [1], total: 4)),
            .success(try page(SessionSummary.self, .session, [2], num: 2, total: 4)),
            .success(try page(SessionSummary.self, .session, [5], total: 1)),
        ]
        let vm = SessionListViewModel(sessions: sessions, pageSize: 1)
        await vm.refresh()
        try await assertALateAppendPageNeverLands(on: sessionScreen(vm, sessions))
    }

    func testTheSessionListReissuesAnAppendAskedForDuringARefresh() async throws {
        let sessions = FakeSessions()
        sessions.replies = [
            .success(try page(SessionSummary.self, .session, [1], total: 4)),
            .success(try page(SessionSummary.self, .session, [5], total: 2)),
            .success(try page(SessionSummary.self, .session, [6], num: 2, total: 2)),
        ]
        let vm = SessionListViewModel(sessions: sessions, pageSize: 1)
        await vm.refresh()
        try await assertAnAppendAskedForDuringARefreshIsReissued(on: sessionScreen(vm, sessions))
    }

    // MARK: - the two scenarios, written once

    /// The reader reaches the bottom, page 2 goes out, and they change the query before it answers.
    ///
    /// The retired page must change *nothing*: not the rows, not the total, and above all not the page counter
    /// the next scroll reads its page number off — which is what makes this a test of the guard rather than of
    /// the cosmetic order of the list.
    private func assertALateAppendPageNeverLands(
        on screen: LateAppend,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let tag = screen.label
        XCTAssertEqual(
            screen.pageCalls(),
            1,
            "\(tag): only the first page has been read before the scroll",
            file: file,
            line: line
        )
        XCTAssertTrue(
            screen.canLoadMore(),
            "\(tag): the first page promises a second one, which is what the scroll is answering",
            file: file,
            line: line
        )

        screen.armGate()
        let lateAppend = Task { await screen.loadMore() }
        try await waitUntil { screen.pageCalls() == 2 }
        XCTAssertEqual(
            screen.requestedNums(),
            [1, 2],
            "\(tag): the append asks for the page after the one on screen",
            file: file,
            line: line
        )

        screen.changeQuery()
        try await waitUntil { screen.pageCalls() == 3 }
        let onScreen = screen.rowIDs()
        let announced = screen.total()
        XCTAssertEqual(
            onScreen,
            [5],
            "\(tag): the changed query has put its own first page up",
            file: file,
            line: line
        )
        XCTAssertFalse(
            screen.canLoadMore(),
            "\(tag): and that page is the whole answer for the new query",
            file: file,
            line: line
        )

        screen.releaseGate()
        await lateAppend.value
        try await settle()

        XCTAssertEqual(
            screen.pageCalls(),
            3,
            "\(tag): the retired answer does not ask for anything else",
            file: file,
            line: line
        )
        XCTAssertEqual(
            screen.rowIDs(),
            onScreen,
            "\(tag): page 2 of the query that has been left behind does not append its rows",
            file: file,
            line: line
        )
        XCTAssertEqual(
            screen.total(),
            announced,
            "\(tag): nor does its total land on a list that never served those rows",
            file: file,
            line: line
        )
        XCTAssertFalse(
            screen.canLoadMore(),
            "\(tag): nor the page counter — paging continues from the query on screen",
            file: file,
            line: line
        )
        XCTAssertEqual(
            screen.requestedNums(),
            [1, 2, 1],
            "\(tag): the three reads are the two queries' own, in the order they were asked",
            file: file,
            line: line
        )
    }

    /// The reader reaches the bottom *while the screen is refreshing*.
    ///
    /// One page read on the wire at a time, so the append cannot go out yet — but it is not thrown away either:
    /// the reader is still waiting for page 2, and the read on the wire is for a newer query than the one that
    /// page was asked of. So the refresh remembers the request and re-issues it for its own query before it
    /// returns, and the list still reaches page 2 by itself.
    private func assertAnAppendAskedForDuringARefreshIsReissued(
        on screen: LateAppend,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let tag = screen.label
        XCTAssertTrue(screen.canLoadMore(), "\(tag): a bottom is in reach", file: file, line: line)

        screen.armGate()
        screen.changeQuery()
        try await waitUntil { screen.pageCalls() == 2 }

        await screen.loadMore()
        XCTAssertEqual(
            screen.requestedNums(),
            [1, 1],
            "\(tag): the append waits for the refresh that is out instead of racing it",
            file: file,
            line: line
        )

        screen.releaseGate()
        try await waitUntil { screen.pageCalls() == 3 }
        try await settle()

        XCTAssertEqual(
            screen.requestedNums(),
            [1, 1, 2],
            "\(tag): the remembered append goes out for the newer query, as its own page 2",
            file: file,
            line: line
        )
        XCTAssertEqual(
            screen.rowIDs(),
            [5, 6],
            "\(tag): and the reader gets the tail of the query they are looking at",
            file: file,
            line: line
        )
        XCTAssertEqual(screen.total(), 2, "\(tag): the total is the new query's", file: file, line: line)
        XCTAssertFalse(
            screen.canLoadMore(),
            "\(tag): and paging ends where the new query ends",
            file: file,
            line: line
        )
    }

    // MARK: - the screens, seen through the four things a late page can get wrong

    private func agentScreen(_ vm: AgentListViewModel, _ agents: ParkedPageAgents) -> LateAppend {
        LateAppend(
            label: "the agent list",
            pageCalls: { agents.pageCalls },
            requestedNums: { agents.requestedNums },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { agents.gateNextRead = true },
            releaseGate: { agents.releaseRead() }
        )
    }

    private func sourceScreen(_ vm: SkillSourceListViewModel, _ skills: FakeSkills) -> LateAppend {
        LateAppend(
            label: "the skill source list",
            pageCalls: { skills.sourceRequests.count },
            requestedNums: { skills.sourceRequests.map(\.num) },
            rowIDs: { vm.items.map(\.id) },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { skills.sourceGate.arm() },
            releaseGate: { skills.sourceGate.release() }
        )
    }

    private func skillTableScreen(_ vm: SkillTableViewModel, _ skills: FakeSkills) -> LateAppend {
        LateAppend(
            label: "the skill table",
            pageCalls: { skills.skillRequests.count },
            requestedNums: { skills.skillRequests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { skills.skillGate.arm() },
            releaseGate: { skills.skillGate.release() }
        )
    }

    private func cliScreen(_ vm: CliListViewModel, _ clis: FakeClis) -> LateAppend {
        LateAppend(
            label: "the CLI list",
            pageCalls: { clis.requests.count },
            requestedNums: { clis.requests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { clis.pageGate.arm() },
            releaseGate: { clis.pageGate.release() }
        )
    }

    private func providerScreen(_ vm: ModelProviderListViewModel, _ catalog: FakeModelCatalog) -> LateAppend {
        LateAppend(
            label: "the provider list",
            pageCalls: { catalog.providerRequests.count },
            requestedNums: { catalog.providerRequests.map(\.num) },
            rowIDs: { vm.items.map(\.providerID) },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { catalog.providerGate.arm() },
            releaseGate: { catalog.providerGate.release() }
        )
    }

    private func modelScreen(_ vm: ModelListViewModel, _ catalog: FakeModelCatalog) -> LateAppend {
        LateAppend(
            label: "the model list",
            pageCalls: { catalog.modelRequests.count },
            requestedNums: { catalog.modelRequests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { catalog.modelGate.arm() },
            releaseGate: { catalog.modelGate.release() }
        )
    }

    private func apiKeyScreen(_ vm: ApiKeyListViewModel, _ keys: FakeApiKeys) -> LateAppend {
        LateAppend(
            label: "the API key list",
            pageCalls: { keys.requests.count },
            requestedNums: { keys.requests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { keys.pageGate.arm() },
            releaseGate: { keys.pageGate.release() }
        )
    }

    private func envVarScreen(_ vm: EnvVarListViewModel, _ vars: FakeEnvVars) -> LateAppend {
        LateAppend(
            label: "the environment-variable list",
            pageCalls: { vars.requests.count },
            requestedNums: { vars.requests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            // This route takes no status parameter (`EnvVariableController.kt:25-37`), so the query the reader
            // changes to arrives through the debounced keyword.
            changeQuery: { vm.keyword = "代理" },
            armGate: { vars.pageGate.arm() },
            releaseGate: { vars.pageGate.release() }
        )
    }

    private func channelScreen(_ vm: ChannelListViewModel, _ channels: FakeChannels) -> LateAppend {
        LateAppend(
            label: "the channel list",
            pageCalls: { channels.requests.count },
            requestedNums: { channels.requests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { channels.pageGate.arm() },
            releaseGate: { channels.pageGate.release() }
        )
    }

    private func taskScreen(_ vm: TaskListViewModel, _ tasks: FakeAgentTasks) -> LateAppend {
        LateAppend(
            label: "the task list",
            pageCalls: { tasks.pageRequests.count },
            requestedNums: { tasks.pageRequests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { tasks.pageGate.arm() },
            releaseGate: { tasks.pageGate.release() }
        )
    }

    private func taskLogScreen(_ vm: TaskLogListViewModel, _ tasks: FakeAgentTasks) -> LateAppend {
        LateAppend(
            label: "the task log",
            pageCalls: { tasks.logRequests.count },
            requestedNums: { tasks.logRequests.map(\.num) },
            rowIDs: { vm.items.map(\.id) },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter.state = .succeeded },
            armGate: { tasks.logGate.arm() },
            releaseGate: { tasks.logGate.release() }
        )
    }

    private func mcpScreen(_ vm: McpListViewModel, _ mcp: FakeMcpServers) -> LateAppend {
        LateAppend(
            label: "the MCP list",
            pageCalls: { mcp.pageRequests.count },
            requestedNums: { mcp.pageRequests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { mcp.pageGate.arm() },
            releaseGate: { mcp.pageGate.release() }
        )
    }

    private func sessionScreen(_ vm: SessionListViewModel, _ sessions: FakeSessions) -> LateAppend {
        LateAppend(
            label: "the conversation list",
            pageCalls: { sessions.requests.count },
            requestedNums: { sessions.requests.map(\.num) },
            rowIDs: { vm.items.compactMap { $0.id } },
            total: { vm.total },
            canLoadMore: { vm.canLoadMore },
            loadMore: { await vm.loadMore() },
            changeQuery: { vm.filter = .enabled },
            armGate: { sessions.pageGate.arm() },
            releaseGate: { sessions.pageGate.release() }
        )
    }

    private func makeLogViewModel(_ tasks: FakeAgentTasks) -> TaskLogListViewModel {
        TaskLogListViewModel(
            taskID: 41,
            catalog: tasks,
            account: AccountSnapshot(username: "admin"),
            pageSize: 1,
            idleInterval: .seconds(60)
        )
    }

    // MARK: - the page script

    /// Which columns a row has to carry beyond its id, because four of these thirteen summaries have non-null
    /// columns the decoder insists on (`SkillSourceResponse.kt`, `ModelProviderResponse.kt`, `AgentTaskSummary`,
    /// `AgentTaskLog`, `SessionResponse.kt`) and the rest are all-optional.
    private enum RowKind {
        case plain, provider, source, skillItem, task, taskLog, session
    }

    /// The three pages both scenarios run: a first page that promises more, the tail page the scroll asks for,
    /// and the first page of the query the reader changes to — always id 1, id 2, id 5, so the two are never
    /// confusable and a row that came from the retired query is visible in the id list.
    private func page<T: Decodable>(
        _ type: T.Type,
        _ kind: RowKind,
        _ ids: [Int],
        num: Int = 1,
        total: Int,
        size: Int = 1
    ) throws -> Page<T> {
        try PageStub.page(T.self, rows(kind, ids), pageNum: num, total: total, pageSize: size)
    }

    private func rows(_ kind: RowKind, _ ids: [Int]) -> [[String: Any]] {
        ids.map { id in
            switch kind {
            case .plain: ["id": id]
            case .provider: [
                "id": id, "type": "dashscope", "name": "厂商", "status": 1, "isPublic": 1,
                "creator": "admin", "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
            ]
            case .source: [
                "id": id, "name": "技能源", "sourceType": "GIT", "version": "1.0.0",
                "url": "https://example.com/skills.git", "branch": "main", "description": "示例来源",
                "status": 1, "isPublic": 1, "creator": "admin",
                "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
            ]
            case .skillItem: ["id": id, "name": "技能", "boundAgentCount": 0, "boundTeamCount": 0]
            case .task: [
                "id": id, "name": "晨报", "agentName": "翻译助手", "prompt": "汇总昨天的构建失败",
                "cronExpression": "0 0 9 * * ?", "taskStatus": 1, "concurrent": 0, "timeoutSeconds": 300,
                "description": "", "isPublic": 1, "creator": "admin", "active": 1,
            ]
            case .taskLog: [
                "id": id, "taskId": 41, "taskName": "每日晨报", "prompt": "汇总昨天的构建失败",
                "response": "汇总完成", "sessionId": "task-41-7-3f2b", "status": 1, "errorInfo": "",
                "tokenUsage": "TokenUsage(inputTokens=100, outputTokens=28, totalTokens=128, costTime=1.4)",
                "startTime": "2026-09-25 09:00:03", "endTime": "2026-09-25 09:00:05", "durationMs": 1_500,
                "creator": "admin", "createTime": "2026-09-25 09:00:03",
            ]
            case .session: [
                "id": id, "title": "季度估值复核", "status": 1, "mcpList": [], "skillList": [],
            ]
            }
        }
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }

    /// Long enough for a re-issued read to have gone out and landed, and short enough that a dropped request
    /// cannot be mistaken for one that is merely slow.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }
}

/// One list screen, seen through the four things a late append page can get wrong: the rows, the total,
/// whether paging may go on, and the page number the next scroll would ask for.
///
/// All fourteen models answer the same shape of question — `loadMore()` reads `pageNum + 1` of whatever query
/// is on screen — so the two scenarios are written once against this and each domain only has to say how to
/// read its own state back, how to hold its page reply open, and what its reader does to change the query.
@MainActor struct LateAppend {
    let label: String
    let pageCalls: @MainActor () -> Int
    let requestedNums: @MainActor () -> [Int]
    let rowIDs: @MainActor () -> [Int64]
    let total: @MainActor () -> Int
    let canLoadMore: @MainActor () -> Bool
    let loadMore: @MainActor () async -> Void
    /// The reader picks another filter, which asks for a new page 1.
    let changeQuery: @MainActor () -> Void
    let armGate: @MainActor () -> Void
    let releaseGate: @MainActor () -> Void
}

/// A page read that can be held open, so two queries really do overlap on the wire.
///
/// Every list double answers its page read straight away, which is why the append's identity could stay broken
/// for as long as it did: nothing in the suite ever had a page in flight while the reader changed the query. A
/// double exposes one of these per paged route; `arm()` parks the *next* read until `release()`, and every read
/// that was not armed answers as before, so an un-gated test is unaffected.
final class PageReadGate<Reply>: @unchecked Sendable {
    private var armed = false
    private var waiting: CheckedContinuation<Reply, Never>?
    private var parkedReply: Reply?

    /// Holds the next read open.
    func arm() {
        armed = true
    }

    /// The double calls this in place of returning its reply: parks it when armed, hands it back immediately
    /// otherwise.
    func absorb(_ reply: Reply) async -> Reply {
        guard armed else { return reply }
        armed = false
        parkedReply = reply
        return await withCheckedContinuation { waiting = $0 }
    }

    /// Lets the parked read answer, as a server that was simply slow.
    func release() {
        guard let continuation = waiting, let reply = parkedReply else { return }
        waiting = nil
        parkedReply = nil
        continuation.resume(returning: reply)
    }
}
