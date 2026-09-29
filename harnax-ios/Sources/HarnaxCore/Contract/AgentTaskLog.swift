import Foundation

/// One row of `GET /api/admin/agent-tasks/{id}/logs` (`§5.1`).
///
/// The route answers with the **entity**, not with `AgentTaskLogResponse`
/// (`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt:206-223`
/// returns `Page<AgentTaskLog>`), which matters because that DTO's schema comment lists only statuses 0..3 and
/// never reaches this response at all. `AgentTaskLog.kt:8-54` is the authority, and it declares every key
/// non-null except `startTime` and `endTime` — a run still in flight has no end to report, and
/// `default-property-inclusion: non_null` drops the key rather than sending null.
public struct AgentTaskLog: Decodable, Identifiable, Equatable, Hashable, Sendable {
    /// The address `POST /logs/{logId}/stop` takes. Not the task id — the two are one path segment apart and
    /// swapping them stops the wrong row (`§5.5`).
    public let id: Int64
    public let taskId: Int64
    public let taskName: String
    public let prompt: String
    /// The agent's whole answer. The console truncates it to 60 characters in the row and shows the full text in
    /// the detail pane (`harnax-webui/src/pages/agent-task/components/TaskLogModal.tsx:183-248`), and iOS keeps
    /// that split.
    public let response: String
    /// `task-{taskId}-{agentId}-{uuid}` (`harnax-scheduler/src/main/resources/db/migration/V1__init_schema.sql:239`).
    /// Opaque here: this screen shows it, it never addresses a session with it.
    public let sessionId: String
    /// 0..5. The six cases, their titles and the in-flight pair live on `AgentTaskLastRun`, because
    /// `agent_task.last_run_status` and `agent_task_log.status` are the same numbers — one is the newest log row
    /// joined onto the task (`AgentTaskMapper.xml:72-83`), the other is that row itself.
    public let status: Int
    public let errorInfo: String
    /// Opaque. The column comment says `Token usage JSON` (`V1__init_schema.sql:242`), but that is not what
    /// arrives: the executor writes `response.tokenUsage?.toString()` (`SchedulerServiceImpl.kt:493`), which for
    /// the `TokenUsage` data class (`ChatEvent.kt:62-68`) is `TokenUsage(inputTokens=…, outputTokens=…)` — a
    /// debug rendering whose field names are *not* this route's contract (`AgentTaskLogResponse.kt:42` types the
    /// whole column as a `String`). Neither the console's log modal nor the iOS mockup shows it, so it is decoded
    /// to keep the row whole and rendered nowhere.
    public let tokenUsage: String
    public let startTime: String?
    public let endTime: String?
    public let durationMs: Int64
    public let creator: String
    /// The fixed sort key, `ORDER BY l.create_time DESC` (`AgentTaskLogMapper.xml:162`), so this screen does not
    /// sort either.
    public let createTime: String

    public var state: AgentTaskLastRun? { AgentTaskLastRun.resolve(status) }
    public var title: String? { hxPresented(taskName) }
    public var promptText: String? { hxPresented(prompt) }
    public var answerText: String? { hxPresented(response) }
    public var failureText: String? { hxPresented(errorInfo) }
    public var conversationID: String? { hxPresented(sessionId) }

    /// The console offers 停止 on a `status === 3` row and on nothing else
    /// (`TaskLogModal.tsx:232`), and the route agrees: a row outside 3/4 answers 500 "Task is not running or
    /// already completed" (`SchedulerController.kt:144-150`). `4` is deliberately *not* stoppable a second time —
    /// that row is already on its way down, either to `5` via `finalizeStopped` or to `2` via the sweep
    /// (`§5.2`).
    public var isStoppable: Bool { status == AgentTaskLastRun.running.rawValue }

    /// Seeing a log and stopping its execution are gated differently: the read joins on the task's visibility
    /// (`AgentTaskLogMapper.xml:136-141`), while the stop runs `requireOwnedLog`
    /// (`AgentTaskCrudServiceImpl.kt:200-210`) — creator only, no administrator pass. A row can therefore be
    /// readable and not stoppable.
    public func stoppable(by account: AccountSnapshot?) -> Bool {
        isStoppable && hxIsRowOwner(creator, account)
    }

    /// `1.5s` for 1500 ms, which is the console's `(ms/1000).toFixed(1) + "s"`
    /// (`TaskLogModal.tsx:219`) rendered without a locale: ten-tenths are computed in integers so a handset set to
    /// a comma-decimal locale still reads the number the way the console writes it.
    public var durationText: String { Self.spelledDuration(durationMs) }

    public static func spelledDuration(_ ms: Int64) -> String {
        guard ms >= 0 else { return "0.0s" }
        let tenths = (ms + 50) / 100
        return "\(tenths / 10).\(tenths % 10)s"
    }
}

/// The log screen's own filter set, as one value.
///
/// Four of the route's five parameters are reachable (`§5.3`): `status`, `keyword` and the `startTimeFrom` /
/// `startTimeTo` pair in `yyyy-MM-dd HH:mm:ss`. `taskName` exists on the route
/// (`AgentTaskController.kt:174`) but the console does not expose it inside a single task's log modal either, so
/// iOS leaves it off — the modal is already scoped by the path id.
public struct AgentTaskLogFilter: Equatable, Sendable {
    /// `nil` means no opinion, and an unused filter is left off the query entirely rather than sent blank.
    public var state: AgentTaskLastRun?
    public var keyword: String?
    public var from: Date?
    public var to: Date?

    public init(state: AgentTaskLastRun? = nil, keyword: String? = nil, from: Date? = nil, to: Date? = nil) {
        self.state = state
        self.keyword = keyword
        self.from = from
        self.to = to
    }

    public var isEmpty: Bool { state == nil && hxPresented(keyword) == nil && from == nil && to == nil }

    /// Whether two filters ask the route for the same rows *apart from* the keyword.
    ///
    /// The screen holds `keyword` in its own `@Published` because typing is debounced, and joins the two back
    /// together only at the read. A selection this value returns false for takes effect on the spot, so
    /// comparing keyword too would put every keystroke through two refresh paths at once.
    public func isSearchEquivalent(to other: AgentTaskLogFilter) -> Bool {
        state == other.state && from == other.from && to == other.to
    }
}
