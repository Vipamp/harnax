import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// A refresh that has been overtaken must not land.
///
/// `refresh()` used to carry no identity of its own: `filter.didSet` spawns an unguarded
/// `Task { await refresh() }`, `.refreshable` and the retry row fire more, and the debounce's `cancel()`
/// cannot retract a request whose `await` has already started — so whichever answer arrived last won the
/// rows, and the screen showed one query's rows while paging continued from the other's page number
/// (`PagedState` keeps `pageNum` and `total` per accumulated page). The double below holds the first read
/// open so the second one can overtake it for real.
@MainActor
final class ListRefreshIdentityTests: XCTestCase {
    /// The stale read asks for a page that promises more rows than the newer one, so a response that
    /// landed out of order is visible in `total` and `canLoadMore` as well as in the rows themselves.
    func testARefreshOvertakenByANewerQueryNeverLands() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1, "name": "旧查询的行"]], total: 40, pageSize: 1)),
            .success(try PageStub.page([["id": 2, "name": "新查询的行"]], total: 1, pageSize: 1)),
        ]
        agents.gateFirstRead = true
        let vm = AgentListViewModel(agents: agents, pageSize: 1)

        let overtaken = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 1 }

        await vm.refresh()
        XCTAssertLessThanOrEqual(
            agents.peakConcurrentReads,
            1,
            "the newer query waits for its turn instead of racing the read that is already out"
        )

        agents.releaseRead()
        await overtaken.value

        XCTAssertEqual(
            vm.items.compactMap(\.name),
            ["新查询的行"],
            "the answer that came last belongs to the query nobody is looking at any more"
        )
        XCTAssertEqual(vm.total, 1, "and the total comes along with it, not from the stale page")
        XCTAssertFalse(vm.canLoadMore, "paging continues from the query on screen")
        XCTAssertEqual(agents.pageCalls, 2, "the newer query really was read")
    }

    /// A read that fails while a newer one has taken its place is not news either: the sentence it would
    /// have raised is about the query the operator already left.
    func testASupersededFailureRaisesNoBanner() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .failure(.offline),
            .success(try PageStub.page([["id": 2, "name": "新查询的行"]], total: 1, pageSize: 1)),
        ]
        agents.gateFirstRead = true
        let vm = AgentListViewModel(agents: agents, pageSize: 1)

        let overtaken = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 1 }
        await vm.refresh()
        agents.releaseRead()
        await overtaken.value

        XCTAssertNil(vm.inlineError, "the refusal belongs to the retired request")
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.compactMap(\.name), ["新查询的行"])
    }

    /// One read at a time, however many refreshes are asked for while it is out. The three behind it are
    /// not thrown away — the screen is waiting for a newer answer than the one on the wire — so the run
    /// that is on the wire goes out again for the query everyone settled on before it returns.
    func testRefreshesDoNotStackOnTopOfOneAnother() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1, "name": "客服助手"]], total: 1, pageSize: 1)),
            .success(try PageStub.page([["id": 1, "name": "客服助手"]], total: 1, pageSize: 1)),
        ]
        agents.gateFirstRead = true
        let vm = AgentListViewModel(agents: agents, pageSize: 1)

        let first = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 1 }
        let others = (0..<3).map { _ in Task { await vm.refresh() } }
        for task in others { await task.value }
        XCTAssertEqual(agents.pageCalls, 1, "the reads behind the one on the wire are not sent")

        agents.releaseRead()
        await first.value
        XCTAssertEqual(agents.peakConcurrentReads, 1, "one page on the wire at a time")
        XCTAssertEqual(agents.pageCalls, 2, "and the query on screen is the one that gets read")
        XCTAssertEqual(vm.items.count, 1)
    }

    /// The guard at the landing has a job the three cases above cannot see: an answer the screen no longer
    /// waits for must not so much as appear. Newest-wins survives without it, because the queued rerun
    /// overwrites the stale page a moment later — so this case parks the rerun too and looks at the interval.
    func testAnOvertakenAnswerNeverReachesTheScreen() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1, "name": "旧查询的行"]], total: 40, pageSize: 1)),
            .success(try PageStub.page([["id": 2, "name": "新查询的行"]], total: 1, pageSize: 1)),
        ]
        agents.gateEveryRead = true
        let vm = AgentListViewModel(agents: agents, pageSize: 1)

        let overtaken = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 1 }
        await vm.refresh()
        XCTAssertEqual(agents.pageCalls, 1, "the newer query waits rather than racing the read that is out")

        agents.releaseRead()
        try await waitUntil { agents.pageCalls == 2 }

        XCTAssertTrue(vm.items.isEmpty, "the retired answer never writes the rows")
        XCTAssertEqual(vm.total, 0, "…nor the total that page carried")

        agents.releaseRead()
        await overtaken.value
        XCTAssertEqual(vm.items.compactMap(\.name), ["新查询的行"], "the read for the query on screen does")
        XCTAssertEqual(vm.total, 1)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// A pull that is torn down mid-flight is not a load that failed.
///
/// Two things went wrong together on a column with nothing on screen: `runRefresh` swapped the empty card
/// for a full-screen spinner while the pull was still open — a change to the very hierarchy the refresh
/// control hangs on — and the answer that came back afterwards was rendered as a refusal. The phone showed
/// that refusal as `error.cancelled` on 2026-10-03: nobody was waiting for the page any more, and that says
/// nothing about the data.
@MainActor
final class PullRefreshCancellationTests: XCTestCase {
    /// The card the operator is pulling stays on screen. Its replacement is what churns the pull's own
    /// container, and the pull already carries a spinner of its own.
    func testAPullFromAnEmptyColumnKeepsTheCardItIsPulling() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try PageStub.page([], total: 0, pageSize: 1)),
            .success(try PageStub.page([], total: 0, pageSize: 1)),
        ]
        let vm = AgentListViewModel(agents: agents, pageSize: 1)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)

        agents.gateNextRead = true
        let pull = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 2 }
        XCTAssertEqual(vm.phase, .empty, "the pull is loading, and its own indicator is where that shows")

        agents.releaseRead()
        await pull.value
    }

    func testACancelledReadOnAnEmptyColumnRaisesNoError() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [.success(try PageStub.page([], total: 0, pageSize: 1)), .failure(.cancelled)]
        let vm = AgentListViewModel(agents: agents, pageSize: 1)
        await vm.refresh()

        agents.gateNextRead = true
        let pull = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 2 }
        pull.cancel()
        agents.releaseRead()
        await pull.value

        XCTAssertEqual(vm.phase, .empty, "a read nobody is waiting for is not news about the stack")
        XCTAssertNil(vm.inlineError)
    }

    /// The same verdict on a column that has rows: the banner above them reports calls that were refused,
    /// not calls the screen stopped asking for.
    func testACancelledReadOnAColumnWithRowsRaisesNoBanner() async throws {
        let agents = ParkedPageAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1, "name": "客服助手"]], total: 1, pageSize: 1)),
            .failure(.cancelled),
        ]
        let vm = AgentListViewModel(agents: agents, pageSize: 1)
        await vm.refresh()

        agents.gateNextRead = true
        let pull = Task { await vm.refresh() }
        try await waitUntil { agents.pageCalls == 2 }
        pull.cancel()
        agents.releaseRead()
        await pull.value

        XCTAssertNil(vm.inlineError)
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.compactMap(\.name), ["客服助手"])
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// The tasks screen carries a reader nobody asked for: the three-second tick an in-flight row keeps armed.
///
/// That makes it the worst case of the rule rather than another copy of it — the stale answer is not a
/// double-tap, it arrives on its own schedule long after the operator stopped looking at that query.
@MainActor
final class TaskTickIdentityTests: XCTestCase {
    func testATickOvertakenByANewKeywordNeverLands() async throws {
        let tasks = ParkedPageTasks()
        tasks.replies = [
            .success(try taskPage("晨报", id: 1, inFlight: true)),
            .success(try taskPage("旧查询的行", id: 2, total: 40)),
            .success(try taskPage("新查询的行", id: 3)),
        ]
        let vm = TaskListViewModel(
            catalog: tasks,
            pageSize: 1,
            pollInterval: .milliseconds(20),
            triggerReloadDelay: .seconds(60)
        )
        await vm.refresh()
        XCTAssertTrue(vm.shouldPoll, "a mid-flight row is what keeps the timer armed")

        tasks.gateNextRead = true
        vm.resumePolling()
        try await waitUntil { tasks.pageCalls == 2 }

        vm.keyword = "新"
        try await waitUntil { tasks.pageCalls == 3 }
        XCTAssertNotEqual(
            tasks.requestedNames[1],
            "新",
            "the read still on the wire belongs to the query that has just been left behind"
        )

        vm.pausePolling()
        tasks.releaseRead()
        try await Task.sleep(for: .milliseconds(50))

        XCTAssertEqual(
            vm.items.map(\.name),
            ["新查询的行"],
            "the tick that came back late does not get to rewrite the rows"
        )
        XCTAssertEqual(vm.total, 1, "nor the total the 40-row page it carried")
        XCTAssertFalse(vm.canLoadMore, "nor the page counter paging would continue from")
        XCTAssertNil(vm.inlineError)
    }

    private func taskPage(
        _ name: String,
        id: Int,
        inFlight: Bool = false,
        total: Int? = nil
    ) throws -> Page<AgentTaskSummary> {
        var json: [String: Any] = [
            "id": id,
            "name": name,
            "agentName": "翻译助手",
            "prompt": "汇总昨天的构建失败",
            "cronExpression": "0 0 9 * * ?",
            "taskStatus": 1,
            "concurrent": 0,
            "timeoutSeconds": 300,
            "description": "",
            "isPublic": 1,
            "creator": "admin",
            "active": 1,
        ]
        if inFlight { json["lastRunStatus"] = 3 }
        return try PageStub.page(AgentTaskSummary.self, [json], pageNum: 1, total: total, pageSize: 1)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// The task surface with one parkable page read, so a tick can be held open across a keyword change.
///
/// Local for the same reason as `ParkedPageAgents`: the shared `FakeAgentTasks` answers every read at once.
final class ParkedPageTasks: AgentTaskCataloging, @unchecked Sendable {
    private(set) var pageCalls = 0
    private(set) var requestedNames: [String?] = []
    var replies: [Result<Page<AgentTaskSummary>, APIError>] = []
    /// Park the next read instead of answering it, until `releaseRead()`.
    var gateNextRead = false

    private var waiting: CheckedContinuation<Result<Page<AgentTaskSummary>, APIError>, Never>?
    private var parkedReply: Result<Page<AgentTaskSummary>, APIError> = .failure(.decoding)

    func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError> {
        pageCalls += 1
        requestedNames.append(name)
        let reply = replies.isEmpty ? Result<Page<AgentTaskSummary>, APIError>.failure(.decoding) : replies.removeFirst()
        guard gateNextRead else { return reply }
        gateNextRead = false
        parkedReply = reply
        return await withCheckedContinuation { waiting = $0 }
    }

    /// Lets the parked read answer, as a server that was simply slow.
    func releaseRead() {
        guard let continuation = waiting else { return }
        waiting = nil
        continuation.resume(returning: parkedReply)
    }

    func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        .success(EmptyResponse())
    }

    func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        .success(EmptyResponse())
    }

    func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> {
        .success(EmptyResponse())
    }

    func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError> {
        .success([])
    }

    func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError> {
        .failure(.offline)
    }

    func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }
}

/// The agent surface with one parkable page read, so the window between two refreshes can be looked at.
///
/// Local to this file because the shared `FakeAgents` answers every read straight away and is a merge point
/// for the other domains.
final class ParkedPageAgents: AgentCataloging, @unchecked Sendable {
    private(set) var pageCalls = 0
    /// The page numbers the reads asked for, in the order they went out.
    private(set) var requestedNums: [Int] = []
    /// High-water mark of the reads in flight at once: an overlapping refresh would push it to two.
    private(set) var peakConcurrentReads = 0
    var replies: [Result<Page<AgentSummary>, APIError>] = []
    var gateFirstRead = false
    /// Park *every* read until it is released, so the window between one answer landing and the next being
    /// asked for can be looked at directly instead of inferred from the state at the end.
    var gateEveryRead = false
    /// Park the next read, whichever position it holds: an append is asked for after the first page has landed.
    var gateNextRead = false
    /// The switch is parkable on its own dial, so a second tap can be thrown while the first answer is out
    /// (`RowWriteReentryTests`).
    var gateWrites = false

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []

    private var inFlight = 0
    private var parkedWrites: [() -> Void] = []
    private var parked: [(
        reply: Result<Page<AgentSummary>, APIError>, cont: CheckedContinuation<Result<Page<AgentSummary>, APIError>, Never>
    )] = []

    func page(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        pageCalls += 1
        requestedNums.append(num)
        inFlight += 1
        peakConcurrentReads = max(peakConcurrentReads, inFlight)
        let reply = replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
        defer { inFlight -= 1 }
        let park = (pageCalls == 1 && gateFirstRead) || gateEveryRead || gateNextRead
        gateNextRead = false
        guard park else { return reply }
        return await withCheckedContinuation { parked.append((reply, $0)) }
    }

    /// Lets the longest-waiting read answer, as a server that was simply slow.
    func releaseRead() {
        guard !parked.isEmpty else { return }
        let (reply, continuation) = parked.removeFirst()
        continuation.resume(returning: reply)
    }

    func setStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        guard gateWrites else { return .success(EmptyResponse()) }
        return await withCheckedContinuation { continuation in
            parkedWrites.append { continuation.resume(returning: .success(EmptyResponse())) }
        }
    }

    /// Runs every parked switch in the order it went out.
    func releaseWrites() {
        let waiting = parkedWrites
        parkedWrites = []
        for resume in waiting { resume() }
    }

    func delete(id: Int64) async -> Result<EmptyResponse, APIError> {
        .success(EmptyResponse())
    }

    func relatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        .success([])
    }
}
