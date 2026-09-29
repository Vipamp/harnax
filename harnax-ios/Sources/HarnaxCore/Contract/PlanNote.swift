import Foundation

/// One plan the agent wrote for this conversation, as `GET /api/router/agent/session/{sessionId}/plans` and
/// `.../current-plan` answer it.
///
/// Backend: `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/adaptor/PlanNoteAdaptor.kt:16-27`,
/// which the two router routes pass through untouched
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/controller/AgentProxyController.kt:157-178`),
/// so the JSON keys are that data class's own property names.
///
/// Two things about it are not guessable from the field list:
/// - `createdAt`/`finishedAt` are **strings**, not epoch numbers (`PlanNoteAdaptor.kt:23-24`, `:35-36`).
///   Whatever format the runtime writes reaches the screen as it arrived; this side neither parses nor
///   re-formats it, because guessing at the pattern would turn a date the app cannot read into a blank line.
/// - A plan with no `name` is not a plan. The console's `hasValidCurrentPlan` uses the name as the validity
///   flag and renders nothing without it (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2965-3038`),
///   and its `plan_card` segment returns null on the same test (`:2746-2858`). `isValid` is that rule.
///
/// Every field is read as optional: the routes answer `ResultVo<List<Any>>` and `ResultVo<Any?>`
/// (`AgentProxyController.kt:162`, `:175`) — the runtime's objects through the stack's `non_null` inclusion,
/// so an unset `finishedAt` is absent rather than null, and a half-written plan arrives half-written. One
/// unreadable subtask costs that row, not the plan.
public struct PlanNote: Decodable, Equatable, Sendable, Identifiable {
    /// The plan's own key inside its session, which is what the console uses as the accordion row id
    /// (`ChatWindow.tsx:3041-3290`) and what an incremental refresh compares against.
    public let planId: String?
    public let sessionId: String?
    public let name: String?
    public let description: String?
    public let expectedOutcome: String?
    public let subtasks: [PlanSubTask]
    public let createdAt: String?
    public let finishedAt: String?
    public let costTimeSeconds: Int64?
    public let status: PlanState?

    public init(
        planId: String? = nil,
        sessionId: String? = nil,
        name: String? = nil,
        description: String? = nil,
        expectedOutcome: String? = nil,
        subtasks: [PlanSubTask] = [],
        createdAt: String? = nil,
        finishedAt: String? = nil,
        costTimeSeconds: Int64? = nil,
        status: PlanState? = nil
    ) {
        self.planId = planId
        self.sessionId = sessionId
        self.name = name
        self.description = description
        self.expectedOutcome = expectedOutcome
        self.subtasks = subtasks
        self.createdAt = createdAt
        self.finishedAt = finishedAt
        self.costTimeSeconds = costTimeSeconds
        self.status = status
    }

    private enum Field: String, CodingKey {
        case planId
        case sessionId
        case name
        case description
        case expectedOutcome
        case subtasks
        case createdAt
        case finishedAt
        case costTimeSeconds
        case status
    }

    public init(from decoder: Decoder) throws {
        let row = try decoder.container(keyedBy: Field.self)
        planId = row.hxText(.planId)
        sessionId = row.hxText(.sessionId)
        name = row.hxText(.name)
        description = row.hxText(.description)
        expectedOutcome = row.hxText(.expectedOutcome)
        subtasks = row.hxDecoded(.subtasks) ?? []
        createdAt = row.hxText(.createdAt)
        finishedAt = row.hxText(.finishedAt)
        costTimeSeconds = row.hxSeconds(.costTimeSeconds)
        status = row.hxState(.status)
    }

    /// The plan id when there is one; a plan the runtime never stamped still has to keep one identity for
    /// the accordion, and its timestamp is the only other column that distinguishes it.
    public var id: String { planId ?? createdAt ?? "" }

    /// The name is the validity flag, not a label (`hasValidCurrentPlan`, `ChatWindow.tsx:2965-3038`).
    public var isValid: Bool { hxPresented(name) != nil }

    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var goal: String? { hxPresented(expectedOutcome) }
    public var finishedAtText: String? { hxPresented(finishedAt) }
    public var createdAtText: String? { hxPresented(createdAt) }

    /// Subtasks in the order the runtime wrote them. The console never sorts this list — its table shows the
    /// array as it arrived (`ChatWindow.tsx:3041-3290`) — so neither does the screen.
    public var steps: [PlanSubTask] { subtasks }

    /// How far the plan got, spelled from its own rows rather than from the `status` column: the runtime
    /// stamps `status` on the note, but the rows are what the user can check.
    public var progress: (done: Int, total: Int) {
        (subtasks.filter { $0.state == .done }.count, subtasks.count)
    }
}

/// `PlanNote.subtasks[]` (`PlanNoteAdaptor.kt:29-38`). Same string-not-epoch rule as the plan itself, and the
/// same tolerance for an absent key.
public struct PlanSubTask: Decodable, Equatable, Sendable {
    public let name: String?
    public let description: String?
    public let expectedOutcome: String?
    /// What the run actually produced, as free text the harness writes back.
    public let outcome: String?
    public let state: PlanState?
    public let createdAt: String?
    public let finishedAt: String?
    public let costTimeSeconds: Int64?

    public init(
        name: String? = nil,
        description: String? = nil,
        expectedOutcome: String? = nil,
        outcome: String? = nil,
        state: PlanState? = nil,
        createdAt: String? = nil,
        finishedAt: String? = nil,
        costTimeSeconds: Int64? = nil
    ) {
        self.name = name
        self.description = description
        self.expectedOutcome = expectedOutcome
        self.outcome = outcome
        self.state = state
        self.createdAt = createdAt
        self.finishedAt = finishedAt
        self.costTimeSeconds = costTimeSeconds
    }

    private enum Field: String, CodingKey {
        case name
        case description
        case expectedOutcome
        case outcome
        case state
        case createdAt
        case finishedAt
        case costTimeSeconds
    }

    public init(from decoder: Decoder) throws {
        let row = try decoder.container(keyedBy: Field.self)
        name = row.hxText(.name)
        description = row.hxText(.description)
        expectedOutcome = row.hxText(.expectedOutcome)
        outcome = row.hxText(.outcome)
        state = row.hxState(.state)
        createdAt = row.hxText(.createdAt)
        finishedAt = row.hxText(.finishedAt)
        costTimeSeconds = row.hxSeconds(.costTimeSeconds)
    }

    public var title: String? { hxPresented(name) }
    public var detail: String? { hxPresented(description) }
    public var goal: String? { hxPresented(expectedOutcome) }
    public var result: String? { hxPresented(outcome) }

    /// A row with no state is an unfinished row: the runtime's own default is `TODO`
    /// (`PlanNoteAdaptor.kt:34`), so an absent column says the same thing as that value.
    public var stateOrTodo: PlanState { state ?? .todo }
}

/// `TaskState` of `PlanNoteAdaptor.kt:40-45`, serialised by enum name.
///
/// Nothing here decodes it through `Decodable`: the runtime owns this enum, and a failed decode would cost the
/// whole plan rather than the one state column. Both containers read it with `init(wireValue:)` instead, so a
/// fifth state added after the app shipped costs one badge.
public enum PlanState: String, CaseIterable, Decodable, Sendable {
    case todo = "TODO"
    case inProgress = "IN_PROGRESS"
    case done = "DONE"
    case abandoned = "ABANDONED"

    /// The console renders this column from the wire name (`ChatWindow.tsx:3041-3290`). Spelled as literals
    /// rather than built from `rawValue`, because the copy gate only sees a key it can read off the source.
    public var titleKey: String {
        switch self {
        case .todo: return "chat.plan.todo"
        case .inProgress: return "chat.plan.inProgress"
        case .done: return "chat.plan.done"
        case .abandoned: return "chat.plan.abandoned"
        }
    }

    public init?(wireValue: String?) {
        guard let name = hxPresented(wireValue) else { return nil }
        self.init(rawValue: name.uppercased())
    }
}

/// `GET .../current-plan` answers `ResultVo<Any?>` — the plan, or nothing when the session has no plan open.
/// The envelope then carries no `data`, which the shared mapper would read as an unpackable reply, so the
/// no-plan case is modelled as this type's empty value instead (it conforms to `HarnaxVoid` in the API layer,
/// like `AgentCommandReply` does).
public struct CurrentPlan: Decodable, Equatable, Sendable {
    public let note: PlanNote?

    public init() {
        note = nil
    }

    public init(note: PlanNote?) {
        self.note = note
    }

    public init(from decoder: Decoder) throws {
        let box = try decoder.singleValueContainer()
        note = try box.decode(PlanNote.self)
    }

    /// The one rule the panel applies before it draws anything (`ChatWindow.tsx:2965-3038`).
    public var valid: Bool { note?.isValid ?? false }
}

// MARK: - tolerant reads

private extension KeyedDecodingContainer {
    /// Absent, explicitly null and unreadable all read as "no value", which is what the console does for a
    /// plan column the runtime left empty (`ChatWindow.tsx:2965-3038`).
    func hxText(_ key: K) -> String? {
        hxPresented((try? decodeIfPresent(String.self, forKey: key)) ?? nil)
    }

    /// A number the runtime wrote as a string, or as a number, decodes either way; anything else is no value.
    func hxSeconds(_ key: K) -> Int64? {
        if let exact = try? decodeIfPresent(Int64.self, forKey: key) { return exact }
        guard let text = (try? decodeIfPresent(String.self, forKey: key)) ?? nil else { return nil }
        return Int64(text.trimmingCharacters(in: .whitespaces))
    }

    /// The state column read as the name it arrives under, case and spacing aside. An unrecognised value is no
    /// state rather than a failed decode, because failing here would take the plan or the row with it.
    func hxState(_ key: K) -> PlanState? {
        PlanState(wireValue: hxText(key))
    }

    /// A nested value that will not decode is dropped rather than failed, so one bad row cannot blank the
    /// panel the other rows would still fill.
    func hxDecoded<T: Decodable>(_ key: K) -> T? {
        (try? decodeIfPresent(T.self, forKey: key)) ?? nil
    }
}

/// The two plan reads the plan panel lives off.
///
/// Its own protocol rather than members of `ChatHistoryReading`: the transcript and the plans share a backend
/// controller class (`AgentProxyController.kt:144-178`) but not a screen, and one open conversation has plenty
/// of rows with no plan at all. Keeping them apart means a test that only exercises the transcript does not
/// have to answer for a panel it never opens.
public protocol PlanReading: Sendable {
    /// `GET /api/router/agent/session/{sessionId}/plans` (`AgentProxyController.kt:157-165`) — every plan this
    /// conversation has written. The console loads this on opening the panel and on a manual refresh only:
    /// its 5-second timer is declared and never started
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:603`, `:2651-2675`).
    func planNotes(sessionId: String) async -> Result<[PlanNote], APIError>

    /// `GET /api/router/agent/session/{sessionId}/current-plan` (`AgentProxyController.kt:170-178`) — the plan
    /// the session is on right now. `ResultVo<Any?>`, so "no plan open" is an answer with no `data`, and that
    /// is `CurrentPlan()` rather than a failure.
    func currentPlan(sessionId: String) async -> Result<CurrentPlan, APIError>
}
