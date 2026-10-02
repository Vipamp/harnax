import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// A row-scoped write has to refuse itself while the same row's write is still out.
///
/// Every list tracks its in-flight rows in `pendingIDs` — but nothing ever *read* it on the way in. The view
/// disables the switch from `isPending`, and a `@State`-driven disable lands a render pass late, so two taps in
/// the same beat both reach the model: two POSTs for one intended change. On a status switch that is a wasted
/// round trip; on a key rotation (`ApiKeyListViewModel.regenerate`) it is two new secrets for one row, with the
/// one-time screen showing only the last.
///
/// The scenario is the one `ContextWriteGateTests` set up for the form paths, run against every row write in
/// the fourteen paged screens: hold the facade's answer open, throw the first tap, throw a second one without
/// awaiting anything in between, and count what the facade was asked to do. A guard that is not there cannot
/// tell the difference between "refused" and "posted and now parked", which is exactly why the reply is parked
/// rather than merely slow.
///
/// Nothing here gates a write that is not row-scoped: a delete goes through `deleteTarget` (one sheet, one
/// row), a connectivity test keeps its own `testingIDs`, and `loadStats` is a read.
@MainActor
final class RowWriteReentryTests: XCTestCase {
    private let admin = AccountSnapshot(username: "admin")

    // MARK: - the fourteen screens

    func testTheAgentSwitchPostsOnce() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [.success(try page(AgentSummary.self, [["id": 7]], total: 1))]
        let vm = AgentListViewModel(agents: agents, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the agent switch",
            requests: { agents.statusCalls.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { agents.gateWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { agents.releaseWrites() }
        ))
    }

    func testTheSkillSourceSwitchPostsOnce() async throws {
        let skills = FakeSkills()
        skills.sourceReplies = [.success(try page(SkillSourceSummary.self, [sourceRow()], total: 1))]
        let vm = SkillSourceListViewModel(skills: skills, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the skill source switch",
            requests: { skills.statusCalls.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { skills.gateStatusWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { skills.releaseWrites() }
        ))
    }

    /// A second install is worse than a second switch: it re-runs the whole sync and overwrites the report the
    /// first one published with a run nobody meant to start.
    func testTheSkillSourceInstallPostsOnce() async throws {
        let skills = FakeSkills()
        skills.sourceReplies = [
            .success(try page(SkillSourceSummary.self, [sourceRow()], total: 1)),
            .success(try page(SkillSourceSummary.self, [sourceRow()], total: 1)),
        ]
        skills.installReplies = [.success(.none), .success(.none)]
        let vm = SkillSourceListViewModel(skills: skills, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the source's reinstall",
            requests: { skills.installRequests.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { skills.gateWrites = true },
            tap: { await vm.installAll(row) },
            release: { skills.releaseWrites() }
        ))
    }

    func testTheSkillSwitchPostsOnce() async throws {
        let skills = FakeSkills()
        skills.skillPageReplies = [
            .success(try page(SkillItem.self, [["id": 9, "name": "技能", "boundAgentCount": 0, "boundTeamCount": 0]], total: 1)),
        ]
        let vm = SkillTableViewModel(skills: skills, pageSize: 20)
        await vm.show(id: 7)
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the skill switch",
            requests: { skills.skillStatusCalls.count },
            isPending: { vm.pendingIDs == [9] },
            arm: { skills.gateStatusWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { skills.releaseWrites() }
        ))
    }

    func testTheCliSwitchPostsOnce() async throws {
        let clis = FakeClis()
        clis.replies = [.success(try page(CliSummary.self, [["id": 3, "name": "harnax-cli"]], total: 1))]
        // Nobody bound, so the switch runs straight away instead of opening the confirmation.
        clis.relatedAgentsReply = .success([])
        let vm = CliListViewModel(clis: clis, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the CLI kill switch",
            requests: { clis.statusCalls.count },
            isPending: { vm.pendingIDs == [3] },
            arm: { clis.gateWrites = true },
            tap: { await vm.requestStatus(false, for: row) },
            release: { clis.releaseWrites() }
        ))
    }

    func testTheProviderSwitchPostsOnce() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [.success(try page(ModelProviderSummary.self, [providerRow()], total: 1))]
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 8)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the provider switch",
            requests: { catalog.providerStatusCalls.count },
            isPending: { vm.pendingIDs == [4] },
            arm: { catalog.gateStatusWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { catalog.releaseWrites() }
        ))
    }

    func testTheModelSwitchPostsOnce() async throws {
        let catalog = FakeModelCatalog()
        catalog.modelReplies = [.success(try page(ModelSummary.self, [["id": 9, "name": "新模型"]], total: 1))]
        let vm = ModelListViewModel(providerID: 3, catalog: catalog, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the model switch",
            requests: { catalog.modelStatusCalls.count },
            isPending: { vm.pendingIDs == [9] },
            arm: { catalog.gateStatusWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { catalog.releaseWrites() }
        ))
    }

    func testTheApiKeySwitchPostsOnce() async throws {
        let keys = FakeApiKeys()
        keys.replies = [.success(try page(ApiKeySummary.self, [["id": 7, "name": "报表密钥"]], total: 1))]
        let vm = ApiKeyListViewModel(catalog: keys, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the API key switch",
            requests: { keys.statusRequests.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { keys.gateWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { keys.releaseWrites() }
        ))
    }

    /// The worst case of the whole set. Rotating replaces the secret, and the one-time screen can only ever
    /// show one — so two rotations leave a live key the operator never sees and cannot revoke.
    func testTheKeyRotationPostsOnce() async throws {
        let keys = FakeApiKeys()
        keys.replies = [
            .success(try page(ApiKeySummary.self, [["id": 7, "name": "报表密钥"]], total: 1)),
            .success(try page(ApiKeySummary.self, [["id": 7, "name": "报表密钥"]], total: 1)),
        ]
        keys.regenerateReplies = [
            .success(try PageStub.page(ApiKeyCreatedSummary.self, [["id": 7, "name": "报表密钥", "rawKey": "sk-new", "keyPrefix": "sk-new-abcd"]], total: 1).records.first!),
            .success(try PageStub.page(ApiKeyCreatedSummary.self, [["id": 7, "name": "报表密钥", "rawKey": "sk-new", "keyPrefix": "sk-new-abcd"]], total: 1).records.first!),
        ]
        let vm = ApiKeyListViewModel(catalog: keys, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the key rotation",
            requests: { keys.regenerateRequests.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { keys.gateWrites = true },
            tap: { await vm.regenerate(row) },
            release: { keys.releaseWrites() }
        ))
    }

    func testTheEnvVarSwitchPostsOnce() async throws {
        let vars = FakeEnvVars()
        vars.replies = [.success(try page(EnvVarSummary.self, [["id": 7, "envKey": "PROXY", "envValue": "1"]], total: 1))]
        let vm = EnvVarListViewModel(catalog: vars, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the environment-variable switch",
            requests: { vars.statusRequests.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { vars.gateWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { vars.releaseWrites() }
        ))
    }

    func testTheChannelSwitchPostsOnce() async throws {
        let channels = FakeChannels()
        channels.replies = [
            .success(try page(ChannelSummary.self, [["id": 7, "name": "企业微信", "type": "wechat", "agentId": 1]], total: 1)),
        ]
        channels.sandboxReplies = [.success(SandboxStatusMap(states: [:]))]
        let vm = ChannelListViewModel(catalog: channels, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the channel switch",
            requests: { channels.statusRequests.count },
            isPending: { vm.pendingIDs == [7] },
            arm: { channels.gateWrites = true },
            tap: { await vm.setStatus(true, for: row) },
            release: { channels.releaseWrites() }
        ))
    }

    func testTheTaskSwitchPostsOnce() async throws {
        let tasks = FakeAgentTasks()
        tasks.pageReplies = [.success(try page(AgentTaskSummary.self, [taskRow()], total: 1))]
        let vm = TaskListViewModel(catalog: tasks, pageSize: 20, pollInterval: .seconds(60))
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the task switch",
            requests: { tasks.statusRequests.count },
            isPending: { vm.pendingIDs == [41] },
            arm: { tasks.gateWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { tasks.releaseWrites() }
        ))
    }

    /// A run request is the write where a double tap is not merely redundant: the second one is refused by the
    /// scheduler as "already in flight", and the operator gets an error about a task they only started once.
    func testTheTaskRunPostsOnce() async throws {
        let tasks = FakeAgentTasks()
        tasks.pageReplies = [.success(try page(AgentTaskSummary.self, [taskRow()], total: 1))]
        let vm = TaskListViewModel(catalog: tasks, pageSize: 20, pollInterval: .seconds(60))
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the task's run request",
            requests: { tasks.triggerRequests.count },
            isPending: { vm.pendingIDs == [41] },
            arm: { tasks.gateWrites = true },
            tap: { _ = await vm.trigger(row) },
            release: { tasks.releaseWrites() }
        ))
    }

    /// Stopping interrupts a live agent execution, so a second tap would be a second interrupt command for a
    /// run that is already on its way down.
    func testTheLogStopPostsOnce() async throws {
        let tasks = FakeAgentTasks()
        tasks.logReplies = [.success(try page(AgentTaskLog.self, [logRow(status: 3)], total: 1))]
        let vm = TaskLogListViewModel(
            taskID: 41,
            catalog: tasks,
            account: admin,
            pageSize: 10,
            idleInterval: .seconds(60)
        )
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)
        XCTAssertTrue(vm.stoppable(row), "a mid-flight row owned by this account is the row that can be stopped")

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the log stop",
            requests: { tasks.stopRequests.count },
            isPending: { vm.stoppingIDs == [61] },
            arm: { tasks.gateWrites = true },
            tap: { await vm.stop(row) },
            release: { tasks.releaseWrites() }
        ))
    }

    func testTheMcpSwitchPostsOnce() async throws {
        let mcp = FakeMcpServers()
        mcp.pageReplies = [.success(try page(McpServerRow.self, [["id": 5, "name": "地图服务"]], total: 1))]
        let vm = McpListViewModel(mcp: mcp, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the MCP switch",
            requests: { mcp.statusCalls.count },
            isPending: { vm.pendingIDs == [5] },
            arm: { mcp.gateStatusWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { mcp.releaseWrites() }
        ))
    }

    func testTheSessionSwitchPostsOnce() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try page(SessionSummary.self, [sessionRow()], total: 1))]
        let vm = SessionListViewModel(sessions: sessions, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the conversation switch",
            requests: { sessions.statusRequests.count },
            isPending: { vm.pendingIDs == [11] },
            arm: { sessions.gateWrites = true },
            tap: { await vm.setStatus(false, for: row) },
            release: { sessions.releaseWrites() }
        ))
    }

    func testTheSessionRenamePostsOnce() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try page(SessionSummary.self, [sessionRow()], total: 1))]
        sessions.renameReplies = [.success(EmptyResponse()), .success(EmptyResponse())]
        let vm = SessionListViewModel(sessions: sessions, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the conversation rename",
            requests: { sessions.renameRequests.count },
            isPending: { vm.pendingIDs == [11] },
            arm: { sessions.gateWrites = true },
            tap: { _ = await vm.rename(row, to: "改过的标题") },
            release: { sessions.releaseWrites() }
        ))
    }

    func testTheSessionClearPostsOnce() async throws {
        let sessions = FakeSessions()
        sessions.replies = [.success(try page(SessionSummary.self, [sessionRow()], total: 1))]
        sessions.clearReplies = [
            .success(AgentCommandReply(success: true, message: "已清空历史消息")),
            .success(AgentCommandReply(success: true, message: "已清空历史消息")),
        ]
        let vm = SessionListViewModel(sessions: sessions, pageSize: 20)
        await vm.refresh()
        let row = try XCTUnwrap(vm.items.first)

        try await assertTheSecondTapIsRefused(on: RowWrite(
            label: "the conversation clear",
            requests: { sessions.clearRequests.count },
            isPending: { vm.pendingIDs == [11] },
            arm: { sessions.gateWrites = true },
            tap: { await vm.clearMessages(row) },
            release: { sessions.releaseWrites() }
        ))
    }

    // MARK: - the scenario, written once

    /// The tap, then a second one with nothing awaited in between — which is what a fast double tap is.
    ///
    /// The refusal has to be visible in what the facade was asked for, not in how long it took: the row's own
    /// pending flag is already set by the first write, and a model that only sets it before its `await` would
    /// pass a test that measured the flag alone.
    private func assertTheSecondTapIsRefused(
        on write: RowWrite,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws {
        let tag = write.label
        write.arm()
        let first = Task { await write.tap() }
        try await waitUntil { write.requests() == 1 }
        XCTAssertTrue(
            write.isPending(),
            "\(tag): the row shows progress while the answer is out",
            file: file,
            line: line
        )

        let second = Task { await write.tap() }
        try await settle()
        XCTAssertEqual(
            write.requests(),
            1,
            "\(tag): a second tap cannot put the same row's write out twice",
            file: file,
            line: line
        )

        write.release()
        await first.value
        await second.value
        XCTAssertEqual(write.requests(), 1, "\(tag): one intended change, one request", file: file, line: line)
        XCTAssertFalse(
            write.isPending(),
            "\(tag): and the row is settled again, whatever the answer said",
            file: file,
            line: line
        )
    }

    // MARK: - the rows

    /// A page of one, as the wire answers it. The ids are the row's own, because every assertion below names
    /// the row by id — the pending set is the model's only statement about *which* row is busy.
    private func page<T: Decodable>(
        _ type: T.Type,
        _ records: [[String: Any]],
        total: Int
    ) throws -> Page<T> {
        try PageStub.page(type, records, pageNum: 1, total: total, pageSize: 20)
    }

    /// `SkillSourceResponse.kt` gives every column but the config block a non-null default.
    private func sourceRow() -> [String: Any] {
        [
            "id": 7, "name": "技能源", "sourceType": "GIT", "version": "1.0.0",
            "url": "https://example.com/skills.git", "branch": "main", "description": "示例来源",
            "status": 1, "isPublic": 1, "creator": "admin",
            "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
        ]
    }

    /// `ModelProviderResponse.kt:64-68` masks the key, and the six columns above are non-null.
    private func providerRow() -> [String: Any] {
        [
            "id": 4, "type": "dashscope", "name": "厂商", "status": 1, "isPublic": 1,
            "creator": "admin", "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
        ]
    }

    private func taskRow() -> [String: Any] {
        [
            "id": 41, "name": "晨报", "agentName": "翻译助手", "prompt": "汇总昨天的构建失败",
            "cronExpression": "0 0 9 * * ?", "taskStatus": 1, "concurrent": 0, "timeoutSeconds": 300,
            "description": "", "isPublic": 1, "creator": "admin", "active": 1,
        ]
    }

    /// Only a `3` is still running (`AgentTaskLog.isStoppable`), and the stop is creator-only
    /// (`hxIsRowOwner`), so the row has to be this account's own.
    private func logRow(status: Int) -> [String: Any] {
        [
            "id": 61, "taskId": 41, "taskName": "每日晨报", "prompt": "汇总昨天的构建失败",
            "response": "汇总完成", "sessionId": "task-41-7-3f2b", "status": status, "errorInfo": "",
            "tokenUsage": "TokenUsage(inputTokens=100, outputTokens=28, totalTokens=128, costTime=1.4)",
            "startTime": "2026-09-25 09:00:03", "endTime": "2026-09-25 09:00:05", "durationMs": 1_500,
            "creator": "admin", "createTime": "2026-09-25 09:00:03",
        ]
    }

    /// The clear is addressed by the string business key, so a row without one is refused before the facade.
    private func sessionRow() -> [String: Any] {
        [
            "id": 11, "title": "季度估值复核", "sessionId": "session-11", "status": 1,
            "mcpList": [], "skillList": [],
        ]
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }

    /// Long enough for a second task to reach the facade it is about to park in, and short enough that a
    /// guard that does exist cannot be mistaken for one that is merely slow.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }
}

/// One row-scoped write, seen through the three things that distinguish a guarded model from an unguarded one:
/// how many times the facade was asked, whether the row still reads as busy afterwards, and how long the answer
/// can be held open.
@MainActor struct RowWrite {
    let label: String
    /// What the facade recorded, in the order it was asked.
    let requests: @MainActor () -> Int
    /// The model's own statement that this row's write is in flight.
    let isPending: @MainActor () -> Bool
    /// Turns the double's park on, before the first tap.
    let arm: @MainActor () -> Void
    /// The write, exactly as the row's gesture calls it.
    let tap: @MainActor () async -> Void
    let release: @MainActor () -> Void
}
