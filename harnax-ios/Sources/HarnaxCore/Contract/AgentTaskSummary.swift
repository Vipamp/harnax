import Foundation

/// One row of `GET /api/admin/agent-tasks/page`.
///
/// This page answers with the **entity**, not a DTO (`harnax-scheduler/.../controller/AgentTaskController.kt:72`
/// returns `Page<AgentTask>`, entity `AgentTask.kt:8-66`), which is why the eighteen keys below are declared
/// against `§2.1`'s nullability column instead of being made optional wholesale the way `EnvVarSummary` is.
/// The two exceptions are `lastRunStatus` and `lastRunTime`: they are not `agent_task` columns at all but a
/// LEFT JOIN onto the task's newest log row (`AgentTaskMapper.xml:72-83`), so a task that has never run
/// answers without those keys (`default-property-inclusion: non_null`).
///
/// `lastRunStatus`/`lastRunTime` also explain the ordering of a list this endpoint never sorts client-side:
/// the SQL is fixed to `ORDER BY t.update_time DESC` (`AgentTaskMapper.xml:95`) and there is no sort parameter
/// to send.
public struct AgentTaskSummary: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    /// A snapshot column, not a scope: this domain answers the same rows to every account and filters by
    /// creator instead of tenant (`AgentTaskCrudServiceImpl.kt:40-42`).
    public let tenantId: Int64?
    public let name: String
    public let agentId: Int64?
    /// Denormalised copy of the agent's name, stamped by admin on the way in
    /// (`AgentTaskController.kt:230-239`). iOS reads it and never writes it.
    public let agentName: String
    public let prompt: String
    /// Quartz 6-or-7-field expression; the server only counts its fields (`§3.3`), so it is also the only
    /// input iOS has for the next-run calculation.
    public let cronExpression: String
    /// `1` running, `0` paused. Note the collision with every other domain's `status`: on this one `0` is
    /// "stopped", and `active` is the deleted flag.
    public let taskStatus: Int
    public let concurrent: Int
    public let timeoutSeconds: Int
    public let description: String
    public let isPublic: Int
    public let creator: String
    /// Soft-delete flag; the list filters it to `1` server-side, so a row that arrived is a live row.
    public let active: Int
    public let createTime: String?
    public let updateTime: String?
    /// 0..5, or absent when the task has never fired. See `AgentTaskLastRun`.
    public let lastRunStatus: Int?
    public let lastRunTime: String?

    /// `id` is the address of every write route in this domain, so a row the wire gave no id for is readable
    /// only — which is why it stays optional here rather than crashing the whole page on a null.
    public var isEnabled: Bool { taskStatus == 1 }
    public var allowsConcurrentRun: Bool { concurrent == 1 }
    /// Listed to everyone under `is_public = 1` (`AgentTaskMapper.xml:85`), which is why the list mixes other
    /// people's rows in.
    public var isShared: Bool { isPublic == 1 }
    public var title: String? { hxPresented(name) }
    public var agentDisplayName: String? { hxPresented(agentName) }
    public var schedule: String? { hxPresented(cronExpression) }
    public var note: String? { hxPresented(description) }
    public var promptText: String? { hxPresented(prompt) }
    public var lastRun: AgentTaskLastRun? { AgentTaskLastRun.resolve(lastRunStatus) }

    /// Whether this account may write the row.
    ///
    /// **Not** `AccountSnapshot.canManage`: this domain's gate is creator-only, with no administrator pass —
    /// the update and delete statements carry `AND creator = #{currentUsername}` in the predicate itself
    /// (`AgentTaskMapper.xml:64,69`), so an admin editing someone else's task is refused with 400 "Only the
    /// task creator can modify this task" (`AgentTaskCrudServiceImpl.kt:161-164,185-186`). Reusing
    /// `canManage` here would hand out controls the backend is certain to reject.
    ///
    /// "Immediate run" is gated on this deliberately *not* — `§2.4` lists edit, delete, stop and start/stop
    /// as the creator-only writes, and a run request goes through the same visibility read as the list.
    public func writable(by account: AccountSnapshot?) -> Bool {
        hxIsRowOwner(creator, account)
    }
}

/// The one write gate this domain has: the row's own `creator` against the signed-in username.
///
/// Both callers need the same comparison and neither has an administrator pass — the update, delete and stop
/// statements all carry `AND creator = #{currentUsername}` in the predicate itself (`AgentTaskMapper.xml:64,69`,
/// `AgentTaskLogMapper.xml:42-48`), so `AccountSnapshot.canManage` would hand out controls the SQL is certain to
/// reject. A row with no creator, or an account with no username, is not writable.
public func hxIsRowOwner(_ creator: String?, _ account: AccountSnapshot?) -> Bool {
    guard let username = hxPresented(account?.username), let owner = hxPresented(creator) else { return false }
    return username == owner
}

/// `agent_task.last_run_status` — the newest execution log's status, joined in by the list query.
///
/// The entity's own comment is the authority here (`AgentTaskLog.kt:31-32` lists all six), not
/// `AgentTaskLogResponse.kt:35-36`, whose schema comment stops at 3 and whose DTO never reaches the list
/// anyway (`§5.1`). A value outside 0..5 is read as absent so the row still decodes; the console falls back
/// to the failed badge for anything it cannot name, and iOS keeps that one behaviour in the view.
public enum AgentTaskLastRun: Int, CaseIterable, Sendable {
    case failed = 0
    case succeeded = 1
    case timedOut = 2
    case running = 3
    case stopping = 4
    case stopped = 5

    public var titleKey: String {
        switch self {
        case .failed: "task.lastRun.failed"
        case .succeeded: "task.lastRun.succeeded"
        case .timedOut: "task.lastRun.timeout"
        case .running: "task.lastRun.running"
        case .stopping: "task.lastRun.stopping"
        case .stopped: "task.lastRun.stopped"
        }
    }

    /// The two states the list's 3-second poll keys off (`§2.5`). Named here so the poll and any later row
    /// glyph agree on the pair rather than each re-listing the raw numbers.
    public var isInFlight: Bool { self == .running || self == .stopping }

    /// An unknown number reads as absent so the row still decodes. The list keeps the two cases apart on the
    /// raw value: a missing key is "never ran", a number outside 0..5 is an unknown status
    /// (`TaskListView.lastRunBadge`), where the console paints both with the Failed styling
    /// (`harnax-webui/src/pages/agent-task/index.tsx:213`).
    public static func resolve(_ raw: Int?) -> AgentTaskLastRun? {
        guard let raw else { return nil }
        return AgentTaskLastRun(rawValue: raw)
    }
}

/// The two business codes of this domain that are not plain failures.
///
/// Everything else this domain can refuse answers 400 (`update`/`delete`) or 500 (`create`) with an English
/// sentence the client shows verbatim — the errors here are never localised (`§0`). The asymmetry itself is
/// `§4.2`: `create` catches only `Exception`, so its business rejections collapse into `ResultVo.error(String)`
/// and arrive as 500, while `update`/`delete` catch `SchedulerBizException` and carry their own code out.
public enum AgentTaskCode {
    /// Data committed, shared Quartz store not converged (`AgentTaskCrudServiceImpl.kt:264-293,302`). The web
    /// console treats this as a completed write plus a warning, and so does iOS (`§4.3`).
    public static let schedulerSyncFailed = 40902
    // `schedulingDisabled` (40903) is deliberately *not* modelled: its message is already the whole answer ("this
    // instance does not schedule"), there is nothing for a caller to redo, and it belongs in the ordinary failure
    // path where `ResponseMapper` hands the English text straight to the screen.

    /// True for the "saved but not scheduled" case, which every write screen routes to its success branch.
    public static func isSchedulerOutOfSync(_ error: APIError) -> Bool {
        if case let .business(code, _) = error { return code == schedulerSyncFailed }
        return false
    }
}

/// The create body (`harnax-scheduler/.../dto/AgentTaskCreateRequest.kt:22-54`).
///
/// Exactly the eight fields of `§3.1`: `taskStatus`, `active`, `creator` and `tenantId` are filled by the
/// server (`AgentTaskCrudServiceImpl.kt:97-105`) and `agentName` by admin's forward proxy
/// (`AgentTaskController.kt:230-239`), so sending any of them would at best be ignored and at worst disagree
/// with what the stack wrote.
public struct AgentTaskDraft: Encodable, Equatable, Sendable {
    public let name: String
    public let agentId: Int64
    public let prompt: String
    public let cronExpression: String
    public let concurrent: Int?
    public let timeoutSeconds: Int?
    public let description: String?
    public let isPublic: Int?

    public init(
        name: String,
        agentId: Int64,
        prompt: String,
        cronExpression: String,
        concurrent: Int? = nil,
        timeoutSeconds: Int? = nil,
        description: String? = nil,
        isPublic: Int? = nil
    ) {
        self.name = name
        self.agentId = agentId
        self.prompt = prompt
        self.cronExpression = cronExpression
        self.concurrent = concurrent
        self.timeoutSeconds = timeoutSeconds
        self.description = description
        self.isPublic = isPublic
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encode(name, forKey: .name)
        try box.encode(agentId, forKey: .agentId)
        try box.encode(prompt, forKey: .prompt)
        try box.encode(cronExpression, forKey: .cronExpression)
        try box.encodeIfPresent(concurrent, forKey: .concurrent)
        try box.encodeIfPresent(timeoutSeconds, forKey: .timeoutSeconds)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(isPublic, forKey: .isPublic)
    }

    private enum Key: String, CodingKey {
        case name, agentId, prompt, cronExpression, concurrent, timeoutSeconds, description, isPublic
    }
}

/// The update body (`harnax-scheduler/.../dto/AgentTaskUpdateRequest.kt:16-45`).
///
/// Partial by construction: the service applies a field only when it arrives non-null
/// (`AgentTaskCrudServiceImpl.kt:122-154`), so an omitted key means "keep what is stored" and a cleared text
/// field has to be sent as `""` rather than left off. Two omissions are deliberate policy rather than laziness:
/// `agentId` stays out when unchanged, because admin resolves and stamps `agentName` for any body that names
/// one (`AgentTaskController.kt:100,230-239`); and there is no `taskStatus` here at all — but note that a
/// successful update pauses the task anyway (`§3.2`), which is why the list re-reads after this call.
public struct AgentTaskChange: Encodable, Equatable, Sendable {
    public let name: String?
    public let agentId: Int64?
    public let prompt: String?
    public let cronExpression: String?
    public let concurrent: Int?
    public let timeoutSeconds: Int?
    public let description: String?
    public let isPublic: Int?

    public init(
        name: String? = nil,
        agentId: Int64? = nil,
        prompt: String? = nil,
        cronExpression: String? = nil,
        concurrent: Int? = nil,
        timeoutSeconds: Int? = nil,
        description: String? = nil,
        isPublic: Int? = nil
    ) {
        self.name = name
        self.agentId = agentId
        self.prompt = prompt
        self.cronExpression = cronExpression
        self.concurrent = concurrent
        self.timeoutSeconds = timeoutSeconds
        self.description = description
        self.isPublic = isPublic
    }

    public func encode(to encoder: any Encoder) throws {
        var box = encoder.container(keyedBy: Key.self)
        try box.encodeIfPresent(name, forKey: .name)
        try box.encodeIfPresent(agentId, forKey: .agentId)
        try box.encodeIfPresent(prompt, forKey: .prompt)
        try box.encodeIfPresent(cronExpression, forKey: .cronExpression)
        try box.encodeIfPresent(concurrent, forKey: .concurrent)
        try box.encodeIfPresent(timeoutSeconds, forKey: .timeoutSeconds)
        try box.encodeIfPresent(description, forKey: .description)
        try box.encodeIfPresent(isPublic, forKey: .isPublic)
    }

    /// Nothing moved: the route would answer 200 having written nothing, and it would still pause the task.
    public var isEmpty: Bool {
        name == nil && agentId == nil && prompt == nil && cronExpression == nil
            && concurrent == nil && timeoutSeconds == nil && description == nil && isPublic == nil
    }

    private enum Key: String, CodingKey {
        case name, agentId, prompt, cronExpression, concurrent, timeoutSeconds, description, isPublic
    }
}

/// One entry of `GET /api/admin/agent-tasks/agents` — the target-agent picker's whole source.
///
/// Admin answers this one route locally rather than forwarding: it is a projection of
/// `agentService.getActiveAgents()` (`AgentTaskController.kt:202-210`), so it carries exactly two keys and no
/// status field to read.
public struct AgentTaskAgentOption: Decodable, Identifiable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?

    /// Not decoder-only, because the picker's list is not always purely what the route returned: a stored task
    /// whose agent has since stopped is re-added by the form so its own target stays on screen.
    public init(id: Int64?, name: String?) {
        self.id = id
        self.name = name
    }

    public var displayName: String? { hxPresented(name) }
}
