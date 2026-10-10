import Foundation
import HarnaxCore

/// The team half of the transcript merge: which member run a frame belongs to, which `team_delegate` card
/// that run answers, and what task the member was given.
///
/// Ported from `harnax-webui/src/pages/session/components/teamRun.ts`, which holds the same contract because
/// the live stream and the history replay have to agree on what a member bubble is: a member speaks on the
/// lead's channel stamped with its own `source`, and on reopen its words come back from a child session
/// merged into the same row list (`TeamHistoryReplay.kt:36-89`).
///
/// Everything here is pure state over one transcript, so every rule is a unit test rather than a screenshot.
/// `specs/02-session-chat.md` line 368 marks the replay mapping as a 1:1 port.

// MARK: - the delegate call

/// The only tool of the lead's that starts a member run (`teamRun.ts:33`). Its result is what ends one.
public let teamDelegateToolName = "team_delegate"

/// One `team_delegate` call: who was asked, and to do what.
public struct TeamDelegateCall: Equatable {
    public let memberAgentId: Int64
    /// A call that named no task is still a delegation — the member id alone routes the run.
    public let task: String?

    public init(memberAgentId: Int64, task: String?) {
        self.memberAgentId = memberAgentId
        self.task = task
    }
}

/// `parseDelegateCall` (`teamRun.ts:71-80`).
///
/// Both doors into here hand over the same payload — the live `CallToolEvent.arguments` and, on reload, the
/// lead's persisted `toolUseLog.input` — and the wire is inconsistent about it: the id arrives as the string
/// `"7"` in one run and the number `9` in another, sometimes with quotes or spaces left in by the model.
public func teamDelegateCall(_ arguments: [String: JSONValue]) -> TeamDelegateCall? {
    guard let id = arguments["member_agent_id"].flatMap(teamMemberAgentID) else { return nil }
    guard case let .string(task)? = arguments["task"] else { return TeamDelegateCall(memberAgentId: id, task: nil) }
    return TeamDelegateCall(memberAgentId: id, task: task)
}

/// The member id a delegate argument carries, in whichever shape it arrived.
///
/// The console runs the value through `Number(String(id).replace(/["'\s]/g, ''))` (`teamRun.ts:76`). Two
/// corners of that JS expression are deliberately not reproduced: a blank or fractional id parses to `0` or
/// to a fraction there, and neither can name a member — ids come from a database and start at 1 — so here an
/// id that is not a whole number is no id at all rather than a bubble that belongs to everybody.
func teamMemberAgentID(_ value: JSONValue) -> Int64? {
    switch value {
    case let .number(number):
        guard number.isFinite, number == number.rounded() else { return nil }
        return Int64(number)
    case let .string(raw):
        let cleaned = String(raw.filter { !$0.isWhitespace && $0 != "\"" && $0 != "'" })
        guard !cleaned.isEmpty else { return nil }
        return Int64(cleaned)
    default:
        return nil
    }
}

/// The tasks one assistant row delegated, in call order (`delegatedTasksOf`, `teamRun.ts:96-105`).
///
/// A member's own rows carry only what it produced; the task it was given lives in the lead's
/// `team_delegate` call, which is the same join the live stream uses. Delegation blocks the lead and the
/// call is recorded before the member runs, so reading it off the lead's turn puts every bubble next to its
/// own task.
///
/// Shared by both paths: the replay feeds it a stored row's calls, the fold a single live call.
public func teamDelegatedTasks(of calls: [ChatHistoryLog.Call]) -> [TeamDelegateCall] {
    calls.compactMap { call in
        guard call.name == teamDelegateToolName, let task = teamDelegateCall(call.input) else { return nil }
        return task
    }
}

// MARK: - what the bubble line says

/// First meaningful line of a delegated task, cut for one summary row (`firstTaskLine`, `teamRun.ts:54-62`).
///
/// Cut by character rather than by measured width on purpose: this line is a hint, the full task stays in the
/// lead's own `team_delegate` card, and an exact-width cut would need a font metric to be right.
public func teamTaskHeadline(_ task: String?, maxChars: Int = 48) -> String {
    guard let task else { return "" }
    guard let line = task
        .split(separator: "\n", omittingEmptySubsequences: false)
        .map({ $0.trimmingCharacters(in: .whitespaces) })
        .first(where: { !$0.isEmpty })
    else { return "" }
    guard line.count > maxChars else { return line }
    return String(line.prefix(maxChars)) + "…"
}

/// Duration in the form short enough for one summary line (`formatRunDuration`, `teamRun.ts:41-46`): under a
/// minute is the common case, so seconds get no unit of their own.
public func teamRunDurationText(_ millis: Double) -> String {
    guard millis.isFinite, millis >= 0 else { return "" }
    let seconds = Int((millis / 1000).rounded())
    guard seconds < 60 else { return String(format: "%dm %02ds", seconds / 60, seconds % 60) }
    return "\(seconds)s"
}

// MARK: - one member bubble

/// How far one member run has got (`MemberRunStatus`, `teamRun.ts:20`).
public enum TeamMemberRunStatus: Equatable {
    /// Frames are still expected.
    case running
    /// Parked on a `ToolConfirmEvent` nobody has answered.
    case awaitingConfirm
    case done
    case failed
}

/// The lifecycle of one member bubble, shown in its header line
/// (`MemberRunInfo` plus the `teamSource` a bubble is stamped with, `teamRun.ts:23-30`).
public struct TeamMemberRun: Equatable {
    /// Who spoke: the team, the member, and the run this bubble belongs to.
    public let source: ChatEventSource
    public var status: TeamMemberRunStatus
    public var startedAt: Date
    public var endedAt: Date?
    /// Tool calls this member made, recounted from its blocks every time one lands.
    public private(set) var toolCount: Int
    /// The task the lead delegated, taken from the `team_delegate` call arguments.
    public var task: String?
    /// The lead's `team_delegate` card this run claimed. Nothing on the wire links the two; see
    /// `TeamRunMerge.claimDelegate`.
    public var parentToolId: String?

    public init(
        source: ChatEventSource,
        status: TeamMemberRunStatus = .running,
        startedAt: Date,
        endedAt: Date? = nil,
        toolCount: Int = 0,
        task: String? = nil,
        parentToolId: String? = nil
    ) {
        self.source = source
        self.status = status
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.toolCount = toolCount
        self.task = task
        self.parentToolId = parentToolId
    }

    /// A run nobody has closed yet is still working — that is what keeps a bubble expanded
    /// (`isRunOpen`, `teamRun.ts:36-38`).
    public var isOpen: Bool { status == .running || status == .awaitingConfirm }

    public var memberAgentId: Int64 { source.memberAgentId }

    /// Wall time the run took, or nil while it is still going.
    public var durationText: String? {
        guard let endedAt else { return nil }
        let text = teamRunDurationText(endedAt.timeIntervalSince(startedAt) * 1000)
        return text.isEmpty ? nil : text
    }

    /// The task, cut for the header line.
    public var headline: String { teamTaskHeadline(task) }

    /// The copy key for this run's state. A run waiting on an answer says so, because that is the one state
    /// the user can act on (`ChatWindow.tsx:2885-2904`).
    public var statusTitleKey: String {
        switch status {
        case .running: return "chat.team.run.running"
        case .awaitingConfirm: return "chat.team.run.awaiting"
        case .done: return "chat.team.run.done"
        case .failed: return "chat.team.run.failed"
        }
    }

    /// `teamRun.toolCount` on replay is the number of call blocks the bubble ended up with
    /// (`ChatWindow.tsx:910-913`).
    public mutating func recountTools(from segments: [ChatSegment]) {
        toolCount = segments.reduce(0) { $0 + ($1.tool == nil ? 0 : 1) }
    }
}

// MARK: - the merge state

/// The state the multi-run merge carries across frames and across stored rows.
///
/// One instance serves both paths, which is the point: `TeamHistoryReplay.kt:81-82` stamps a member's
/// history rows with `childRunId = childSessionId`, so the same id covers every delegation to that member.
/// Keying a bubble by the id alone (`Map<childRunId, bubble>`) would weld two delegations into one bubble —
/// the trap `specs/02-session-chat.md` line 394 calls out. The rule that holds on both paths is
/// 「the previous frame or row must carry the same id」.
public struct TeamRunMerge: Equatable {
    /// The task the lead last delegated to each member. A member's own frames never carry it.
    public private(set) var taskByMember: [Int64: String] = [:]
    /// Delegate cards a member run has taken, so a member delegated twice gets two cards.
    public private(set) var claimedCards: Set<String> = []
    /// Which member each of the lead's `team_delegate` cards was sent to (`delegateTargets`,
    /// `ChatWindow.tsx:1113`). The card's result is what retires the member's run, and the result frame
    /// carries no arguments, so this is read back off the call.
    public private(set) var cardMembers: [String: Int64] = [:]
    /// The run id of the frame or row last consumed, nil when that one was the lead's.
    public private(set) var lastRunID: String?
    /// The bubble the run last consumed writes into, nil while the lead has the floor.
    public private(set) var bubbleKey: String?

    private var bubbleSeq = 0

    public init() {}

    /// Route one frame or row. Returns the bubble it belongs to, or nil for the lead's own speech.
    ///
    /// Called for every row of a replay, tool results included: a member's ASSISTANT row and its TOOL rows
    /// are one run, and the id has to survive the gap between them (`ChatWindow.tsx:771-777`).
    public mutating func route(runID: String?) -> String? {
        guard let runID else {
            lastRunID = nil
            bubbleKey = nil
            return nil
        }
        if runID == lastRunID, let bubbleKey { return bubbleKey }
        lastRunID = runID
        bubbleSeq += 1
        let key = "member-\(runID)#\(bubbleSeq)"
        bubbleKey = key
        return key
    }

    /// What the lead's `team_delegate` calls delegated. Called as those calls land, on the frame or on the
    /// stored row, because they land before the member says anything.
    public mutating func noteDelegatedTasks(_ calls: [TeamDelegateCall]) {
        for call in calls where !(call.task ?? "").isEmpty {
            taskByMember[call.memberAgentId] = call.task
        }
    }

    public func task(forMember id: Int64) -> String? { taskByMember[id] }

    /// A `team_delegate` call landed in the lead's turn: remember who it was sent to
    /// (`noteDelegateCall`, `ChatWindow.tsx:1173-1181`).
    ///
    /// Recorded on the call rather than read back on the result, because the result frame names no arguments:
    /// `ToolResultEvent` carries a `toolId`, a `toolName` and a message, so the card is the only place the
    /// member id is written.
    public mutating func noteDelegateCall(toolID: String, arguments: [String: JSONValue]) {
        guard !toolID.isEmpty, let call = teamDelegateCall(arguments) else { return }
        cardMembers[toolID] = call.memberAgentId
    }

    /// The card came back: which member's run does that retire (`completeDelegateResult`, `:1179-1190`).
    ///
    /// Consumed, not just read: a card that has answered once must not close the run of the next delegation to
    /// the same member. Nil for a card that was never a delegation, which is the common case.
    public mutating func takeDelegateCard(toolID: String) -> Int64? {
        guard let member = cardMembers.removeValue(forKey: toolID) else { return nil }
        // The card is out of the running for the next delegation, whether or not a run ever claimed it: a
        // refused or failed delegate would otherwise sit at the head of the queue and mis-file the next run
        // (`completeDelegateResult`, `:1183-1188`).
        claimedCards.insert(toolID)
        return member
    }

    /// The card this member run belongs to, in call order (`claimDelegateCard`, `teamRun.ts:133-146`).
    ///
    /// A run and a card are the same delegation, but nothing on the wire links them: `childRunId` names the
    /// member's child session — and is reused by the next delegation to that member — while the card is one
    /// call in the lead's turn. Delegation blocks the lead, so the earliest card of this member that no run
    /// has taken yet is the right one, and both paths reach for it in the same order.
    ///
    /// Returns nil when the lead's turn holds no such card, which leaves the bubble standing on its own.
    public mutating func claimDelegate(in segments: [ChatSegment], forMember id: Int64) -> String? {
        for segment in segments {
            guard let run = segment.tool, run.toolName == teamDelegateToolName else { continue }
            guard !run.toolId.isEmpty, !claimedCards.contains(run.toolId) else { continue }
            guard teamDelegateCall(run.arguments)?.memberAgentId == id else { continue }
            claimedCards.insert(run.toolId)
            cardMembers[run.toolId] = id
            return run.toolId
        }
        return nil
    }
}

// MARK: - reading a frame

public extension ChatEvent {
    /// Who spoke, for every one of the eight branches.
    var teamSource: ChatEventSource? {
        switch self {
        case let .text(delta): return delta.source
        case let .thinking(delta): return delta.source
        case let .toolCall(call): return call.source
        case let .toolResult(result): return result.source
        case let .toolConfirm(confirm): return confirm.source
        case let .end(end): return end.source
        case let .failure(failure): return failure.source
        case let .keepAlive(ping): return ping.source
        }
    }

    /// The member run this frame belongs to, nil for the session's own agent.
    ///
    /// Keyed on `childRunId` and not on 「has a source」: a frame can name the team and the member without
    /// naming a run (`TeamHistoryReplay.kt:81-82` writes the child run id onto every stamped row, but the
    /// live orchestrator only puts a source on a member's frames), and an id the server left blank names no
    /// run at all — such a frame belongs to the lead's answer, which is where a truthy test in the console
    /// puts it too (`ChatWindow.tsx:1405`).
    var memberRunID: String? { hxPresented(teamSource?.childRunId) }
}
