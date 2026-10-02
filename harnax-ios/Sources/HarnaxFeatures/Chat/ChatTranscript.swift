import Foundation
import HarnaxCore

/// The outcome of the answer a turn opened. Only the two terminal cases stop the fold.
public enum ChatOutcome: Equatable {
    /// Frames are still expected.
    case streaming
    /// The `EndEvent` arrived.
    case ended
    /// The server stopped the run with an `ErrorEvent`; `code` is its machine name, `message` its text.
    case failed(code: String, message: String)
    /// The read closed without an end frame — a stop, a conversation switch, or a dropped connection.
    case interrupted
}

extension ChatOutcome {
    /// Whether this turn's run was given up on rather than finished.
    ///
    /// A stop or the server's own `ErrorEvent` means nothing is coming back to that turn, while an `EndEvent`
    /// can be a tool wait closing the stream around the pause (`HarnessAgentWrapper.kt:763-798`) — the answer
    /// is what resumes it, so an ended turn is not abandoned.
    public var isAbandoned: Bool {
        switch self {
        case .interrupted, .failed: return true
        case .streaming, .ended: return false
        }
    }
}

/// One displayable block of an answer. The fold only ever grows or closes these; the screen switches on
/// `kind` and never re-derives structure from the raw frames.
public struct ChatSegment: Identifiable, Equatable {
    public enum Kind: Equatable {
        /// Consecutive text deltas merged into one growing block.
        case text(String)
        case thinking(String)
        case tool(ChatToolRun)
        /// The tools a `ToolConfirmEvent` is waiting on an answer for, in the order the frame listed them.
        case confirmation([ChatPendingTool])
        /// A file the run produced in the sandbox, reported by the end frame.
        case file(ChatFileAttachment)
        /// The plan the session is on, read off `current-plan` while this answer was running
        /// (`plan_card`, `ChatWindow.tsx:2545-2555`). One block per plan phase: every later reading rewrites the
        /// block that is already there (`:2558-2572`), and the plan going away leaves it on screen
        /// (`:2576-2584`). Nothing in the fold adds or removes one — the plan read does, through `showPlan`.
        case plan(PlanNote)
    }

    public let id: Int
    public var kind: Kind

    public init(id: Int, kind: Kind) {
        self.id = id
        self.kind = kind
    }
}

/// A tool call and the result it paired with. One block per call: a result never stands on its own
/// (`ChatWindow.tsx:1596-1617` writes into the card, and `:2771-2773` renders a standalone result as null).
public struct ChatToolRun: Equatable {
    public struct Result: Equatable {
        public let message: String
        public let succeeded: Bool

        public init(message: String, succeeded: Bool) {
            self.message = message
            self.succeeded = succeeded
        }
    }

    public let toolId: String
    public var toolName: String
    public var arguments: [String: JSONValue]
    public var result: Result?
    /// Named by a `ToolConfirmEvent` nobody has answered yet.
    public var awaitingConfirmation: Bool
    /// The answer that went out for this call, once it has. `nil` while the run never asked, or while an ask
    /// is still waiting.
    public var confirmAnswer: ToolConfirmAnswer?
    /// The turn closed while this call was still waiting for a result, so it never will get one
    /// (`markOpenToolCards`, `ChatWindow.tsx:1148-1153`).
    public var interrupted: Bool

    public init(
        toolId: String,
        toolName: String,
        arguments: [String: JSONValue] = [:],
        result: Result? = nil,
        awaitingConfirmation: Bool = false,
        confirmAnswer: ToolConfirmAnswer? = nil,
        interrupted: Bool = false
    ) {
        self.toolId = toolId
        self.toolName = toolName
        self.arguments = arguments
        self.result = result
        self.awaitingConfirmation = awaitingConfirmation
        self.confirmAnswer = confirmAnswer
        self.interrupted = interrupted
    }

    /// Refused outright: the tool will not run, so no result is on its way.
    public var isRefused: Bool { confirmAnswer == .denied }

    /// Still waiting for the tool to come back — the one state that opens its own card. A call the user
    /// refused is finished business even before the server's refusal result lands
    /// (`ChatWindow.tsx:319-324` ranks `rejected` above a result).
    public var isRunning: Bool { result == nil && !awaitingConfirmation && !interrupted && !isRefused }
}

/// One tool a confirmation frame is asking about.
public struct ChatPendingTool: Equatable {
    public let toolId: String
    public let toolName: String
    public let arguments: [String: JSONValue]
    public let isDangerous: Bool
    /// Set when the ask came from a team member's run: the answer has to go back to that parked run, and
    /// only that id says which one (`DESIGN.md` line 15).
    public let childRunId: String?
    /// The answer the user gave, `nil` while the block is still waiting.
    public let answer: ToolConfirmAnswer?

    public init(
        toolId: String,
        toolName: String,
        arguments: [String: JSONValue],
        isDangerous: Bool,
        childRunId: String? = nil,
        answer: ToolConfirmAnswer? = nil
    ) {
        self.toolId = toolId
        self.toolName = toolName
        self.arguments = arguments
        self.isDangerous = isDangerous
        self.childRunId = childRunId
        self.answer = answer
    }

    /// The same row with an answer written into it.
    func resolved(_ answer: ToolConfirmAnswer?) -> ChatPendingTool {
        ChatPendingTool(
            toolId: toolId,
            toolName: toolName,
            arguments: arguments,
            isDangerous: isDangerous,
            childRunId: childRunId,
            answer: answer
        )
    }
}

/// The confirmation block the screen is waiting on an answer for.
///
/// One at a time, and the newest one: an answered block stays on screen as a record of what was decided, so
/// "what may the user answer right now" is a question about the transcript, not about the last frame
/// (`ChatWindow.tsx:1720-1725` rewrites every pending segment rather than the newest one, which is the same
/// rule read from the other side).
public struct ChatPendingConfirmation: Equatable {
    /// The block this is, so a card can tell "you are the question" from "you have been answered".
    public let segmentID: Int
    /// Non-nil when the ask belongs to a team member's run rather than this session's own agent.
    public let childRunId: String?
    /// The tools, in the order the frame listed them. The wire body follows this order.
    public let tools: [ChatPendingTool]

    public init(segmentID: Int, childRunId: String?, tools: [ChatPendingTool]) {
        self.segmentID = segmentID
        self.childRunId = childRunId
        self.tools = tools
    }
}

/// One bubble: what the user sent, or the answer to it.
public struct ChatTurn: Identifiable, Equatable {
    public enum Role: String, Equatable {
        case user
        case assistant
    }

    public let id: String
    public let role: Role
    public var segments: [ChatSegment]
    /// The pictures this bubble was sent with, as `data:image/…;base64,…` strings.
    ///
    /// Memory only. The stored user row has no field for them — `UserMessageLog` is `message`, `timestamp` and
    /// `source` (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:34-40`) —
    /// so a conversation reopened later comes back without its pictures, and nothing here tries to
    /// reconstruct them from the transcript.
    public var images: [String]
    public var outcome: ChatOutcome
    public let timestamp: Date
    /// Set when this bubble is one team member's run rather than the answer the user is talking to.
    ///
    /// `role` stays `.assistant` either way: a member's rows are assistant rows with a source on them
    /// (`TeamHistoryReplay.kt:65-89`), and what separates the two bubbles is this field.
    public var member: TeamMemberRun?

    public init(
        id: String,
        role: Role,
        segments: [ChatSegment] = [],
        images: [String] = [],
        outcome: ChatOutcome,
        timestamp: Date,
        member: TeamMemberRun? = nil
    ) {
        self.id = id
        self.role = role
        self.segments = segments
        self.images = images
        self.outcome = outcome
        self.timestamp = timestamp
        self.member = member
    }

    /// Whether this bubble belongs to a team member rather than to the session's own agent.
    public var isMemberBubble: Bool { member != nil }

    /// Nothing reached the screen for this turn — the difference between "connection lost" and "stopped
    /// with a half answer still on screen" (`ChatWindow.tsx:2361`).
    public var hasContent: Bool { !segments.isEmpty || !images.isEmpty }

    /// The words one bubble is worth handing to the pasteboard: the text runs in the order they were streamed,
    /// joined by a newline. Thinking, tool cards, files and the plan block stay out — copying an answer should
    /// not drag its reasoning trace along.
    ///
    /// The one place this is assembled. `ChatWindow.tsx` has no whole-message copy at all (its only clipboard
    /// call is the code block's, `:181`), so this is the app's own rule rather than a mirrored one.
    public var copyableText: String { segments.compactMap(\.text).joined(separator: "\n") }
}

/// The folding rules for a streamed answer, kept apart from the screen that draws them so every rule can
/// be a unit test. It owns the ordered turns and, for the one turn still open, the segment list a frame
/// writes into.
///
/// Copy does not live here. A failed frame keeps its `code` and `message`, and the screen picks the words,
/// so the reducer answers the same way in either language.
public struct ChatTranscript: Equatable {
    /// The plan tools are driven by the plan interface rather than by the transcript: their call and
    /// confirm frames create no card and break no text run (`isPlanRelatedTool`,
    /// `ChatWindow.tsx:2943-2962`, skipped at `:1507` and `:1676`). What the panel does with those frames is
    /// the view model's business — it sees the raw event and reacts to it (`ChatWindow.tsx:1499-1504`,
    /// `:1566-1591`), and the card itself comes from the plan read, never from the frame
    /// (`showPlan`, which is the only way one gets here).
    private static let planTools: Set<String> = ["plan_enter", "plan_write", "plan_exit"]

    /// Whether a tool name belongs to the plan interface, and so to `planTools`' drop-everything rule.
    public static func isPlanTool(_ toolName: String) -> Bool {
        planTools.contains(toolName)
    }

    public private(set) var turns: [ChatTurn] = []

    private var segmentCounter = 0
    private var turnCounter = 0
    private var synthesizedCounter = 0
    /// Numbering for the ids a replayed call has to be given (`ChatWindow.tsx:881`).
    private var replayToolSeq = 0
    /// A closing frame (`isLast`) ends the run its kind was growing: the next delta opens a new block rather
    /// than extending one the model already signed off. One seal per kind, which is what the console's two
    /// accumulator resets amount to (`ChatWindow.tsx:1422-1428`, `:1444-1485`).
    private var textSealed = false
    private var thinkingSealed = false
    /// The team half of the merge, shared by the replay and the live fold on purpose: a member's rows and a
    /// member's frames have to agree on what one bubble is (`teamRun.ts`'s reason for existing).
    private var team = TeamRunMerge()
    /// The inline plan block this transcript is following, by segment id — the console's
    /// `currentPlanMessageIdRef` (`ChatWindow.tsx:592-595`). One card per plan phase: a reading that finds it
    /// rewrites that block, and `endAnswerForPlanExit` clearing it is what lets a later plan open its own card
    /// while the finished one stays on screen (`:1588`).
    private var planCardID: Int?

    public init() {}

    /// The stored transcript of a session, replayed row by row the way the console maps it
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:759-930`).
    ///
    /// Rows are consumed in array order and never re-sorted: the team merge interleaves the lead's rows with
    /// the members', so the list is not time-ordered even though every row is stamped. Consecutive ASSISTANT
    /// rows aggregate into one bubble — one bubble per user turn, as while streaming — and a USER row closes
    /// the bubble before it. Every turn this produces is already over, so the fold's `isTerminated` reads
    /// true and a later `send` writes into a fresh bubble rather than into history.
    public init(replaying logs: [ChatHistoryLog]) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        /// The assistant bubble later rows still land in, as an index into `turns`.
        var answer: Int?
        for (index, log) in logs.enumerated() {
            let stamp = Self.millis(log.timestamp, now: now)
            let rowID = Self.rowID(role: log.roleKey ?? "row", stamp: stamp, index: index)
            let runID = hxPresented(log.source?.childRunId)
            // Every row routes, tool results included: a member's ASSISTANT row and the TOOL rows that answer
            // it are one run, and the id has to survive the gap between them (`:766-772`).
            let bubble = team.route(runID: runID)
            switch log {
            case let .user(message, _, _):
                // A user row ends the answer it prompted, and with it the member bubbles of that round
                // (`ChatWindow.tsx:775-778`).
                answer = nil
                let segment = nextSegment(.text(message))
                turns.append(ChatTurn(
                    id: rowID,
                    role: .user,
                    segments: [segment],
                    outcome: .ended,
                    timestamp: Self.date(stamp)
                ))
            case let .assistant(thinking, text, calls, _, source):
                if let bubble, let memberSource = source, runID != nil {
                    let opened = openMemberBubble(
                        key: bubble, source: memberSource, stamp: stamp, isReplay: true
                    )
                    // The new bubble went in above the lead's, so the lead moved down with it.
                    if opened.inserted, let lead = answer, opened.index <= lead { answer = lead + 1 }
                    replayAssistant(
                        thinking: thinking, text: text, calls: calls, rowID: rowID,
                        following: logs, after: index, into: opened.index, runID: runID
                    )
                    // A replay has no lifecycle events, so the bubble's tool count and end time are read off
                    // its own rows (`:905-908`).
                    refreshMemberRun(at: opened.index, endedAt: Self.date(stamp))
                } else {
                    // What this turn delegated has to be recorded before the member's rows are reached: the
                    // lead's row is stored first and the member's come after it (`:787-792`).
                    if runID == nil { team.noteDelegatedTasks(teamDelegatedTasks(of: calls)) }
                    let at: Int
                    if let open = answer {
                        at = open
                    } else {
                        turns.append(ChatTurn(
                            id: rowID, role: .assistant, outcome: .ended, timestamp: Self.date(stamp)
                        ))
                        at = turns.count - 1
                        answer = at
                    }
                    replayAssistant(
                        thinking: thinking, text: text, calls: calls, rowID: rowID,
                        following: logs, after: index, into: at, runID: runID
                    )
                }
            case .system, .tool, .unknown:
                // The console draws neither a system row nor a bare tool result: a result only ever reaches
                // the screen through the call it belongs to (`ChatWindow.tsx:911-929`). A member's result row
                // with no member row before it opens nothing either — it only routes (`:766-772`).
                break
            }
        }
    }

    /// One stored assistant row into the bubble it belongs to, in the console's order: thinking, then one card
    /// per call, then the text (`ChatWindow.tsx:836-902`).
    ///
    /// A call's result is looked for among the TOOL rows that follow *this* row in an unbroken run, because a
    /// row can name several calls and their results come back one after another
    /// (`ChatWindow.tsx:855-861`). The run stops at a row from another source: a member's result answers a
    /// member's call, and pairing it with the lead's would hand a member's output to the wrong card
    /// (`:859`). One that finds no result closes as interrupted: that turn was cut, and a replayed card must
    /// not sit there claiming to still be working (`:890-893`).
    private mutating func replayAssistant(
        thinking: String,
        text: String,
        calls: [ChatHistoryLog.Call],
        rowID: String,
        following logs: [ChatHistoryLog],
        after row: Int,
        into target: Int,
        runID: String?
    ) {
        if !thinking.isEmpty {
            if turns[target].segments.last?.thinking != nil {
                let at = turns[target].segments.count - 1
                let merged = (turns[target].segments[at].thinking ?? "") + "\n\n" + thinking
                turns[target].segments[at].kind = .thinking(merged)
            } else {
                let segment = nextSegment(.thinking(thinking))
                turns[target].segments.append(segment)
            }
        }
        var results: [ResultRow] = []
        for follow in logs.dropFirst(row + 1) {
            guard case let .tool(name, result, _, _) = follow else { break }
            guard hxPresented(follow.source?.childRunId) == runID else { break }
            results.append(ResultRow(name: name, message: result))
        }
        for call in calls {
            guard let name = hxPresented(call.name) else { continue }
            guard !Self.planTools.contains(name) else { continue }
            replayToolSeq += 1
            var run = ChatToolRun(
                toolId: "\(rowID)-t\(replayToolSeq)",
                toolName: name,
                arguments: call.input
            )
            // A result that names no tool pairs with the first call still unclaimed, the way the console's
            // `!r.log.name || r.log.name === toolName` test reads (`ChatWindow.tsx:884-886`).
            if let at = results.firstIndex(where: { !$0.used && ($0.name.isEmpty || $0.name == name) }) {
                results[at].used = true
                // A stored result row carries no success flag — the outcome lived in the stream's
                // `ToolResultEvent` — so a replayed card reads as completed.
                run.result = ChatToolRun.Result(message: results[at].message, succeeded: true)
            } else {
                run.interrupted = true
            }
            let segment = nextSegment(.tool(run))
            turns[target].segments.append(segment)
            if name == teamDelegateToolName {
                // Registered under the id just synthesized, because a member's row can only reach a card by
                // that id and history names none (`ChatWindow.tsx:881`).
                team.noteDelegateCall(toolID: run.toolId, arguments: call.input)
            }
        }
        if !text.isEmpty {
            let segment = nextSegment(.text(text))
            turns[target].segments.append(segment)
        }
    }

    /// A stored tool result, waiting for the call it answers.
    private struct ResultRow {
        let name: String
        let message: String
        var used = false
    }

    /// `log.timestamp || Date.now()` (`ChatWindow.tsx:764`): a row the server left unstamped, or stamped with
    /// the zero the JVM default carries, reads as now.
    private static func millis(_ timestamp: Int64?, now: Int64) -> Int64 {
        guard let timestamp, timestamp != 0 else { return now }
        return timestamp
    }

    private static func date(_ millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000)
    }

    /// The console's row key, which the replayed tool ids hang off of (`ChatWindow.tsx:764`, `:881`).
    private static func rowID(role: String, stamp: Int64, index: Int) -> String {
        "\(role)-\(stamp)-\(index)"
    }

    /// The blocks of the answer last on screen, newest last.
    public var segments: [ChatSegment] { turns.last?.segments ?? [] }

    /// Nothing is folding, so the next frame would open a new bubble rather than extend this one.
    public var isTerminated: Bool { openAnswer == nil }

    // MARK: - fold

    /// The user's message and the answer it opens. A turn still folding gets closed first: two answers
    /// cannot be open at once, and the frames of the abandoned one would land in the new bubble.
    public mutating func send(_ message: String, images: [String] = [], at date: Date = Date()) {
        terminate(as: .interrupted)
        appendUserTurn(message: message, images: images, opensAnswer: true, at: date)
    }

    /// A user bubble that opens no answer: the raw text of a slash command
    /// (`ChatWindow.tsx:992-998` pushes exactly one text segment and then goes off to `/command`).
    public mutating func appendUserMessage(_ message: String, at date: Date = Date()) {
        terminate(as: .interrupted)
        appendUserTurn(message: message, images: [], opensAnswer: false, at: date)
    }

    /// The reply to a command — one finished bubble of its own, never a stream
    /// (`ChatWindow.tsx:1012-1017`, where the sentence goes in as a single text segment).
    ///
    /// It opens nothing, so `isTerminated` stays true and a late frame from an earlier read still cannot
    /// write into it.
    public mutating func appendCommandReply(_ message: String, at date: Date = Date()) {
        turns.append(ChatTurn(
            id: "answer-\(nextTurn())",
            role: .assistant,
            segments: [nextSegment(.text(message))],
            outcome: .ended,
            timestamp: date
        ))
    }

    private mutating func appendUserTurn(message: String, images: [String], opensAnswer: Bool, at date: Date) {
        // A picture with no caption is still a message; an empty text block under it would draw an empty
        // bubble the user cannot read.
        var segments: [ChatSegment] = []
        if !message.isEmpty { segments.append(nextSegment(.text(message))) }
        turns.append(ChatTurn(
            id: "user-\(nextTurn())",
            role: .user,
            segments: segments,
            images: images,
            outcome: .ended,
            timestamp: date
        ))
        if opensAnswer { openAnswerTurn(at: date) }
    }

    /// One frame of the stream. A terminal frame closes the answer, and anything after it is dropped: the
    /// end frame is the contract's last word, and the server closes the socket behind it.
    ///
    /// A frame that names a member run skips all of that: it belongs to that member's bubble, which the lead's
    /// fold never sees (`ChatWindow.tsx:1399-1403`).
    public mutating func fold(_ event: ChatEvent) {
        // A parked run says nothing. Rendering the ping would open an empty bubble, and ending on it would
        // stop the turn the ping exists to keep alive (ChatEvent.swift:80-82). A member's ping is the sharper
        // risk: `TeamOrchestrator.kt:427` stamps it with a source, so a ping routed before it was dropped
        // would paint an empty member bubble out of thin air (`specs/02-session-chat.md` line 421).
        if case .keepAlive = event { return }
        if let key = team.route(runID: event.memberRunID), let source = event.teamSource {
            foldMember(event, key: key, source: source)
            return
        }
        if openAnswer == nil {
            // No bubble to write into. A frame that follows the bubble's own terminal frame is dropped
            // rather than opened afresh: the server closes the socket behind that frame, so a late
            // arrival belongs to the answer that just ended, not to a new one. A member's bubble at the
            // tail is no such answer — with no lead turn in the round yet, this frame opens it.
            guard currentLeadIndex() == nil else { return }
            openAnswerTurn()
        }
        guard let index = openAnswer else { return }
        var turn = turns[index]
        // The lead's own terminal frames close the members of this round with it: delegation blocks the lead,
        // so a lead turn that is over cannot have left a member running (`closeAllMemberRuns`, `:1193-1197`).
        var leadClosed: (outcome: ChatOutcome, succeeded: Bool)?
        // A `team_delegate` card that came back: the member's only offline signal on the live stream, since
        // the server filters the member's own end frame (`completeDelegateResult`, `:1179-1190`).
        var returnedCard: (member: Int64, succeeded: Bool)?
        switch event {
        case let .text(delta):
            // `isLast` closes the channel and its payload is discarded, never treated as the last batch of
            // content (`ChatWindow.tsx:1422-1428`). What was already accumulated stays where it is.
            if delta.isLast { textSealed = true } else { grow(&turn, delta.message, thinking: false) }
        case let .thinking(delta):
            if delta.isLast { thinkingSealed = true } else { grow(&turn, delta.message, thinking: true) }
        case let .toolCall(call):
            let cardID = foldCall(&turn, call)
            if call.toolName == teamDelegateToolName, let cardID {
                // The member id lives on this card and nowhere else on the member's own frames — neither the
                // task (`noteDelegateCall`, `:1168-1176`) nor the run's offline signal
                // (`completeDelegateResult`, `:1179-1190`) can be read back later without it.
                team.noteDelegateCall(toolID: cardID, arguments: call.arguments)
                team.noteDelegatedTasks(teamDelegatedTasks(of: [
                    ChatHistoryLog.Call(name: call.toolName, input: call.arguments),
                ]))
            }
        case let .toolResult(result):
            if let cardID = foldResult(&turn, result), let member = team.takeDelegateCard(toolID: cardID) {
                returnedCard = (member, result.success)
            }
        case let .toolConfirm(confirm):
            foldConfirm(&turn, confirm)
        case let .end(end):
            for file in end.attachments { turn.segments.append(nextSegment(.file(file))) }
            close(&turn, as: .ended)
            leadClosed = (.ended, true)
        case let .failure(failure):
            close(&turn, as: .failed(code: failure.code, message: failure.message))
            leadClosed = (.failed(code: failure.code, message: failure.message), false)
        case .keepAlive:
            break
        }
        turns[index] = turn
        if let card = returnedCard { closeMemberRuns(ofMember: card.member, succeeded: card.succeeded) }
        if let closed = leadClosed { closeAllMemberRuns(as: closed.outcome, succeeded: closed.succeeded) }
    }

    // MARK: - a member's frames

    /// One frame of a member's run, into that member's own bubble (`handleMemberEvent`, `:1221-1292`).
    ///
    /// The lead's bubble is not touched by any of it — not by the deltas, not by the member's own terminal
    /// frame — because the lead's turn is still open waiting for this member to come back.
    private mutating func foldMember(_ event: ChatEvent, key: String, source: ChatEventSource) {
        let stamp = Int64(Date().timeIntervalSince1970 * 1000)
        let opened = openMemberBubble(key: key, source: source, stamp: stamp, isReplay: false)
        var turn = turns[opened.index]
        switch event {
        case let .text(delta):
            if !delta.isLast { growMember(&turn, delta.message, thinking: false) }
        case let .thinking(delta):
            if !delta.isLast { growMember(&turn, delta.message, thinking: true) }
        case let .toolCall(call):
            // A member's own cards never claim a delegation, so its id is not needed here.
            _ = foldCall(&turn, call)
        case let .toolResult(result):
            _ = foldResult(&turn, result)
        case let .toolConfirm(confirm):
            // The ask goes inside the member's bubble as an inline card: a member's confirmation cannot take
            // the lead's modal, which would hold the whole team behind it (`:1258-1277`).
            foldConfirm(&turn, confirm)
            if var member = turn.member {
                member.status = .awaitingConfirm
                turn.member = member
            }
        case .end:
            // The server filters a member's end frame today, so this is the defensive shape: it closes *this*
            // bubble and leaves the lead's open (`collectTurn`, `TeamOrchestrator.kt:352-356`).
            closeMember(&turn, as: .ended, succeeded: true)
        case let .failure(failure):
            closeMember(&turn, as: .failed(code: failure.code, message: failure.message), succeeded: false)
        case .keepAlive:
            break
        }
        if var member = turn.member {
            member.recountTools(from: turn.segments)
            turn.member = member
        }
        turns[opened.index] = turn
    }

    /// The bubble this member run writes into, opened when the run has no bubble yet.
    ///
    /// Both doors agree on where a new bubble goes: above the lead's, because the lead's turn does not close
    /// until its members have run, so on the timeline the lead's words come last (`paintMemberRun` `:1139-1142`,
    /// replay `:820-823`). With no lead bubble in the round to sit above, the bubble goes at the tail.
    ///
    /// - Parameter isReplay: a replayed bubble has no lifecycle events left to read, so it opens already
    ///   closed (`:805-811`); a live one opens working.
    private mutating func openMemberBubble(
        key: String,
        source: ChatEventSource,
        stamp: Int64,
        isReplay: Bool
    ) -> (index: Int, inserted: Bool) {
        if let hit = turns.firstIndex(where: { $0.id == key }) { return (hit, false) }
        let opened = Self.date(stamp)
        var member = TeamMemberRun(
            source: source,
            status: isReplay ? .done : .running,
            startedAt: opened,
            endedAt: isReplay ? opened : nil,
            task: team.task(forMember: source.memberAgentId)
        )
        let lead = currentLeadIndex()
        if let lead {
            member.parentToolId = team.claimDelegate(
                in: turns[lead].segments, forMember: source.memberAgentId
            )
        }
        let turn = ChatTurn(
            id: key,
            role: .assistant,
            outcome: isReplay ? .ended : .streaming,
            timestamp: opened,
            member: member
        )
        guard let lead else {
            turns.append(turn)
            return (turns.count - 1, true)
        }
        turns.insert(turn, at: lead)
        return (lead, true)
    }

    /// A member's deltas join the block they grew, if that block is still the newest one
    /// (`appendMemberDelta`, `:1199-1206`).
    ///
    /// No seal applies: a member's `isLast` frame carries no content worth keeping, and the lead's seal flags
    /// are the lead's accumulator — reading them here would cut a member's sentence because the lead happened
    /// to sign one off.
    private mutating func growMember(_ turn: inout ChatTurn, _ message: String, thinking: Bool) {
        guard !message.isEmpty else { return }
        switch turn.segments.last?.kind {
        case let .text(existing) where !thinking:
            rewriteLast(&turn, .text(existing + message))
        case let .thinking(existing) where thinking:
            rewriteLast(&turn, .thinking(existing + message))
        default:
            turn.segments.append(nextSegment(thinking ? .thinking(message) : .text(message)))
        }
    }

    /// A member run that is over: its own bubble closes, the lead's stays open
    /// (`closeMemberRun`, `:1156-1165`).
    ///
    /// A run parked on an ask nobody answered closes too. That is the console's rule, not an oversight: the
    /// frames that retire a run arrive when the round is being given up on — a stop, or the lead's own end
    /// frame — and `finally` nulls the member answer handler at exactly that point, so the card loses its
    /// controls rather than staying live on a run the transcript has called dead
    /// (`ChatWindow.tsx:2375-2395`).
    private mutating func closeMember(_ turn: inout ChatTurn, as outcome: ChatOutcome, succeeded: Bool) {
        close(&turn, as: outcome)
        guard var member = turn.member else { return }
        member.status = succeeded ? .done : .failed
        member.endedAt = Date()
        turn.member = member
    }

    /// Every open run of one member, closed by the lead's delegate card coming back
    /// (`closeMemberRun`, `:1156-1165`).
    private mutating func closeMemberRuns(ofMember id: Int64, succeeded: Bool) {
        for index in turns.indices {
            guard turns[index].member?.memberAgentId == id, turns[index].member?.isOpen == true else { continue }
            var turn = turns[index]
            closeMember(&turn, as: succeeded ? .ended : .interrupted, succeeded: succeeded)
            turns[index] = turn
        }
    }

    /// The whole round is over, so no member is still working (`:1193-1197`).
    private mutating func closeAllMemberRuns(as outcome: ChatOutcome, succeeded: Bool) {
        for index in turns.indices {
            guard turns[index].member?.isOpen == true else { continue }
            var turn = turns[index]
            closeMember(&turn, as: outcome, succeeded: succeeded)
            turns[index] = turn
        }
    }

    /// The bubble summary a replay has to build for itself: tool count off its own blocks, end time the
    /// newest stamp it has seen (`:905-908`).
    private mutating func refreshMemberRun(at index: Int, endedAt: Date) {
        guard var turn = turns.indices.contains(index) ? turns[index] : nil, var member = turn.member else { return }
        member.recountTools(from: turn.segments)
        // A replay has no lifecycle frames, so the newest stamp on the run's own rows is its end time.
        if member.endedAt == nil || endedAt > member.endedAt! { member.endedAt = endedAt }
        turn.member = member
        turns[index] = turn
    }

    /// The lead's bubble of the round in progress, open or closed: the newest non-member assistant turn after
    /// the user's last message. Its `team_delegate` cards are what a member bubble claims.
    private func currentLeadIndex() -> Int? {
        currentRound().reversed().first { turns[$0].role == .assistant && !turns[$0].isMemberBubble }
    }

    /// The bubbles of the round in progress: everything after the user's last message. A member's ask from an
    /// earlier round is history by now, and so is the card it belongs to.
    private func currentRound() -> [Int] {
        let start = (turns.lastIndex { $0.role == .user }).map { $0 + 1 } ?? 0
        return start < turns.count ? Array(start..<turns.count) : []
    }

    /// The turn's last word from the reader's side: the stream ended or threw, or a stop or a conversation
    /// switch aborted it. An already terminated answer keeps the outcome it was given, and so does a member
    /// whose run was already closed.
    ///
    /// Every open member bubble goes with it: a stop is the user letting the round go, and a round that was
    /// let go cannot still have a member working inside it (`closeAllMemberRuns`, `ChatWindow.tsx:2375-2395`).
    public mutating func terminate(as outcome: ChatOutcome) {
        // First the members, whatever became of the lead: a stop that arrives after the lead's own end frame
        // still has to retire a run parked inside it.
        closeAllMemberRuns(as: outcome, succeeded: false)
        guard let index = openAnswer else { return }
        var turn = turns[index]
        close(&turn, as: outcome)
        turns[index] = turn
    }

    // MARK: - the plan card

    /// The plan on screen, written by the plan read rather than by any frame.
    ///
    /// Attach or update, and idempotent in the way `:2558-2572` is: a second reading of a plan that has moved on
    /// rewrites the block that is already there instead of stacking a second card under it. The block belongs to
    /// the round's lead answer, which is the bubble the screen reads (`segments`) and the bubble the next frame
    /// writes into (`openAnswer`) — a card of its own turn would win both searches and the lead's next word
    /// would be dropped with nowhere to go.
    ///
    /// Returns whether the reading reached the screen. A plan with no name is no plan (`PlanNote.isValid`, and
    /// `hasValidCurrentPlan` at `:2965-2970`), and with no answer to hang it on the reading is dropped rather
    /// than drawn: inventing the bubble here is what this design rejects.
    @discardableResult
    public mutating func showPlan(_ plan: PlanNote) -> Bool {
        guard plan.isValid else { return false }
        if let id = planCardID,
           let turn = turns.firstIndex(where: { head in head.segments.contains { $0.id == id } }),
           let segment = turns[turn].segments.firstIndex(where: { $0.id == id }) {
            turns[turn].segments[segment].kind = .plan(plan)
            return true
        }
        guard let index = currentLeadIndex() else { return false }
        let block = nextSegment(.plan(plan))
        planCardID = block.id
        turns[index].segments.append(block)
        return true
    }

    /// The plan the transcript is still following: the one `showPlan` updates, and `nil` from
    /// `endAnswerForPlanExit` onward. A card that has been left behind is not this — it stays on screen with the
    /// last reading it was given (`:2576-2584`) and answers to nothing.
    public var planCard: PlanNote? {
        guard let id = planCardID else { return nil }
        for turn in turns.reversed() {
            if let block = turn.segments.first(where: { $0.id == id }) { return block.plan }
        }
        return nil
    }

    /// `plan_exit` came back: the plan phase is over and the execution phase starts a bubble of its own
    /// (`ChatWindow.tsx:1566-1591`).
    ///
    /// The answer closes, an empty one opens behind it, and the followed card is forgotten so a later plan gets
    /// its own block while this one stays where it landed. `.ended` is the console's own ending here — the run is
    /// not abandoned, it has moved on to the half of the turn that does the work — and the fold's sealing flags
    /// reset with the new bubble exactly as `accText` does there.
    ///
    /// With no assistant answer in this round there is nothing to break, so nothing happens.
    public mutating func endAnswerForPlanExit(at date: Date = Date()) {
        planCardID = nil
        guard currentLeadIndex() != nil else { return }
        if let index = openAnswer {
            var turn = turns[index]
            close(&turn, as: .ended)
            turns[index] = turn
        }
        openAnswerTurn(at: date)
    }

    // MARK: - answering a confirmation

    /// The block the user may answer now: the newest one with a row nobody has decided.
    ///
    /// Read off the last turn only — a parked ask from an earlier turn is history by then, and answering it
    /// would resume a run the conversation has left behind. A turn closed by a stop or by the server's
    /// `ErrorEvent` is out too: the console retires the answer handler at exactly that point rather than
    /// leaving 「确定」 live on a run it has called dead (`ChatWindow.tsx:2378-2379`). An `EndEvent` does
    /// not disqualify it — the run parks and the harness closes the stream around the wait
    /// (`HarnessAgentWrapper.kt:763-798`), and the answer is what opens a new one.
    public var pendingConfirmation: ChatPendingConfirmation? {
        guard let turn = confirmationTurn(.unanswered) else { return nil }
        let index = unansweredConfirmation(turns[turn])!
        let rows = turns[turn].segments[index].rows
        return ChatPendingConfirmation(
            segmentID: turns[turn].segments[index].id,
            childRunId: rows.lazy.compactMap(\.childRunId).first,
            tools: rows
        )
    }

    /// Which block of the round in progress the user is being asked about: the newest one still waiting on an
    /// answer, or — when a delivered answer has to go back — the newest one already settled.
    ///
    /// The search walks the round backwards rather than reading the last bubble, because a member's inline ask
    /// sits in the member's own bubble, which is *above* the lead's (`openMemberBubble`). Turns the run gave
    /// up on are out of the *unanswered* search, exactly as the last-bubble test had them out.
    private func confirmationTurn(_ search: ConfirmationSearch) -> Int? {
        for index in currentRound().reversed() {
            let turn = turns[index]
            let hasBlock = switch search {
            // The abandoned test belongs to this search only: `pendingConfirmation` is asking what the user
            // may answer *now*, and a run the read gave up on has no answer to take.
            case .unanswered: !turn.outcome.isAbandoned && unansweredConfirmation(turn) != nil
            // The other search is a rollback, and the read that failed has by then closed its own turn as
            // interrupted — filtering abandoned turns here would exclude exactly the block being handed back.
            case .settled: settledConfirmation(turn) != nil
            }
            if hasBlock { return index }
        }
        return nil
    }

    private enum ConfirmationSearch {
        case unanswered
        case settled
    }

    /// Write the decisions into the block and into the cards it names, then open the bubble again.
    ///
    /// The reopening is the point: the answer is not a receipt, it is the request that resumes the run, and
    /// the frames that come back are this same turn's continuation. Without it the fold's
    /// "no bubble to write into" guard would drop every word the resumed run says
    /// (`ChatWindow.tsx:1720-1725` likewise rewrites the pending segments before it posts).
    ///
    /// A row the caller left out is treated as approved — the panel's rows default to 「允许执行」, so an
    /// absent decision is the one the user saw, not a refusal smuggled in by a missing key.
    public mutating func resolveConfirmation(_ decisions: [String: ToolConfirmAnswer]) {
        settleConfirmation(given: decisions, answered: true)
    }

    /// Hand the newest answered block back, as if the answer had never left.
    ///
    /// The one use is a confirm request that reached nobody: leaving a settled card on screen would tell the
    /// user a decision had been made that the run never saw.
    public mutating func reopenConfirmation() {
        settleConfirmation(given: [:], answered: false)
    }

    private mutating func settleConfirmation(given decisions: [String: ToolConfirmAnswer], answered: Bool) {
        guard let index = confirmationTurn(answered ? .unanswered : .settled) else { return }
        var turn = turns[index]
        let rows: [ChatPendingTool]
        if answered {
            guard let at = unansweredConfirmation(turn) else { return }
            rows = turn.segments[at].rows.map { $0.resolved(decisions[$0.toolId] ?? .allowed) }
            turn.segments[at].kind = .confirmation(rows)
        } else {
            guard let at = settledConfirmation(turn) else { return }
            rows = turn.segments[at].rows.map { $0.resolved(nil) }
            turn.segments[at].kind = .confirmation(rows)
        }
        var settled: Set<Int> = []
        for row in rows { writeAnswer(row, answered: answered, into: &turn, claimed: &settled) }
        // The run this ask parked is the one the resumed stream writes into. An ask handed back is waiting
        // again for the same reason: the answer reached nobody, so the run is still parked even though the
        // read that failed to carry it closed the turn as interrupted.
        turn.outcome = .streaming
        if var member = turn.member {
            // 「run.status = 'running'」 after the answer goes out (`answerMemberConfirm`, `:1310`): the
            // continuation comes back on this member's channel, so its header reads working again.
            member.status = answered ? .running : .awaitingConfirm
            turn.member = member
        }
        turns[index] = turn
        textSealed = false
        thinkingSealed = false
    }

    /// Move one card between "waiting" and "answered". The match is the one that settles a card in the state
    /// the transition expects — waiting, or already answered — so a row whose card was found by name rather
    /// than by id still gets the answer the user gave it, and a card that has nothing to do with this block
    /// is left alone. A card one row has settled is not offered to the next row of the same block.
    private func writeAnswer(
        _ row: ChatPendingTool,
        answered: Bool,
        into turn: inout ChatTurn,
        claimed: inout Set<Int>
    ) {
        func match(id: String, name: String) -> Int? {
            turn.segments.firstIndex(where: { segment in
                guard let run = segment.tool, !claimed.contains(segment.id) else { return false }
                guard (!id.isEmpty && run.toolId == id) || (!name.isEmpty && run.toolName == name) else {
                    return false
                }
                return answered ? run.awaitingConfirmation : run.confirmAnswer != nil
            })
        }
        guard let at = match(id: row.toolId, name: "") ?? match(id: "", name: row.toolName),
              var run = turn.segments[at].tool else { return }
        claimed.insert(turn.segments[at].id)
        run.awaitingConfirmation = !answered
        run.confirmAnswer = answered ? row.answer : nil
        // An answer that never left makes the card wait again, interrupted or not.
        run.interrupted = answered ? run.interrupted : false
        turn.segments[at].kind = .tool(run)
    }

    // MARK: - frames

    /// One call into the turn's cards. Returns the id the card carries, nil when the frame drew nothing — the
    /// lead's fold needs the id to register a delegation with, and it is not always the frame's own
    /// (`synthesizeToolID`).
    private mutating func foldCall(_ turn: inout ChatTurn, _ call: ChatEvent.ToolCall) -> String? {
        guard let name = hxPresented(call.toolName) else { return nil }
        guard !Self.planTools.contains(name) else { return nil }
        let id = hxPresented(call.toolId) ?? synthesizeToolID()
        // A repeated call for the same id rewrites its card (`ChatWindow.tsx:1523-1530`); two calls with
        // different ids are two cards even when the tool is the same one. The console's name fallback
        // would fold the second call into the first card while the first is still running, losing a tool
        // the user can see was called twice.
        if let index = indexOfTool(turn, id: id), let existing = turn.segments[index].tool {
            turn.segments[index].kind = .tool(ChatToolRun(
                toolId: id,
                toolName: name,
                arguments: call.arguments,
                result: existing.result,
                awaitingConfirmation: existing.awaitingConfirmation,
                confirmAnswer: existing.confirmAnswer
            ))
        } else {
            turn.segments.append(nextSegment(.tool(ChatToolRun(
                toolId: id,
                toolName: name,
                arguments: call.arguments
            ))))
        }
        return id
    }

    /// One result into the card it answers. Returns the id of the card it wrote, which is how the lead's
    /// fold notices that a returned `team_delegate` card — the lead's own signal that a member is done — has
    /// come back. Nil for a result that paired with nothing.
    private mutating func foldResult(_ turn: inout ChatTurn, _ result: ChatEvent.ToolResult) -> String? {
        guard !Self.planTools.contains(result.toolName) else { return nil }
        // An unpaired result is dropped rather than drawn on its own (`ChatWindow.tsx:1614-1616`).
        guard let index = findTool(turn, id: result.toolId, name: result.toolName, broad: true),
              var run = turn.segments[index].tool
        else { return nil }
        run.result = ChatToolRun.Result(message: result.message, succeeded: result.success)
        turn.segments[index].kind = .tool(run)
        return run.toolId
    }

    private mutating func foldConfirm(_ turn: inout ChatTurn, _ confirm: ChatEvent.ToolConfirm) {
        let childRunId = confirm.source?.childRunId
        // The loose "any open card" step is only sound while the frame is about one tool; two rows in one
        // frame are two calls and each needs its own card, or the second answer would overwrite the first.
        let loose = confirm.pendingCallTools.count == 1
        var claimed: Set<Int> = []
        var rows: [ChatPendingTool] = []
        for pending in confirm.pendingCallTools {
            guard let name = hxPresented(pending.toolName) else { continue }
            guard !Self.planTools.contains(name) else { continue }
            if let index = claimedCard(turn, id: pending.toolId, name: name, claimed: claimed, loose: loose),
               var run = turn.segments[index].tool {
                // The card is reused and only its status changes (`ChatWindow.tsx:1683-1691`); its
                // arguments come from this frame, which is the shape the user is being asked to approve.
                run.toolName = name
                run.arguments = pending.arguments
                run.awaitingConfirmation = true
                run.interrupted = false
                turn.segments[index].kind = .tool(run)
                claimed.insert(turn.segments[index].id)
            } else {
                // A confirmation can be the first frame that names the tool. Dropping it would hide the
                // very call the user is being asked about (`ChatWindow.tsx:1692-1703` opens a card).
                let segment = nextSegment(.tool(ChatToolRun(
                    toolId: pending.toolId,
                    toolName: name,
                    arguments: pending.arguments,
                    awaitingConfirmation: true
                )))
                claimed.insert(segment.id)
                turn.segments.append(segment)
            }
            rows.append(ChatPendingTool(
                toolId: pending.toolId,
                toolName: name,
                arguments: pending.arguments,
                isDangerous: pending.isDangerous,
                childRunId: hxPresented(childRunId)
            ))
        }
        guard !rows.isEmpty else { return }
        if let index = unansweredConfirmation(turn) {
            // The same run can be asked about twice before an answer goes out; one block, updated rather
            // than stacked.
            turn.segments[index].kind = .confirmation(merge(turn.segments[index].rows, rows))
        } else {
            // Either no block yet, or the last one has been answered: a fresh ask after an answer is a new
            // question and gets its own panel (`ChatWindow.tsx:2036-2130`), because the settled block is the
            // record of what the user decided.
            turn.segments.append(nextSegment(.confirmation(rows)))
        }
    }

    // MARK: - segments

    /// Text and thinking each run into the block they grew last, so long as that block is still the newest
    /// one and no closing frame has signed it off — the same test the console's accumulator makes
    /// (`ChatWindow.tsx:1407-1419`). A frame carrying nothing opens no block, so an empty segment never
    /// reaches the screen (`:1432`).
    private mutating func grow(_ turn: inout ChatTurn, _ message: String, thinking: Bool) {
        guard !message.isEmpty else { return }
        let sealed = thinking ? thinkingSealed : textSealed
        if thinking { thinkingSealed = false } else { textSealed = false }
        if !sealed {
            switch turn.segments.last?.kind {
            case let .text(existing) where !thinking:
                rewriteLast(&turn, .text(existing + message))
                return
            case let .thinking(existing) where thinking:
                rewriteLast(&turn, .thinking(existing + message))
                return
            default:
                break
            }
        }
        turn.segments.append(nextSegment(thinking ? .thinking(message) : .text(message)))
    }

    private mutating func rewriteLast(_ turn: inout ChatTurn, _ kind: ChatSegment.Kind) {
        guard let index = turn.segments.indices.last else { return }
        turn.segments[index].kind = kind
    }

    /// A run that never got its result closes as interrupted, so a stopped stream does not leave a card
    /// spinning forever. A card awaiting an answer keeps that state instead — its run is parked, not gone
    /// (`ChatWindow.tsx:2383-2384`).
    private mutating func close(_ turn: inout ChatTurn, as outcome: ChatOutcome) {
        for index in turn.segments.indices {
            guard var run = turn.segments[index].tool, run.result == nil, !run.awaitingConfirmation else {
                continue
            }
            run.interrupted = true
            turn.segments[index].kind = .tool(run)
        }
        turn.outcome = outcome
    }

    /// Exact id first, then the console's two looser matches: the same tool still without a result, then
    /// any open card. The broad step exists because `CallToolEvent` and `ToolConfirmEvent` do not always
    /// spell the tool name the same way (`ChatWindow.tsx:1067-1091`).
    private func findTool(_ turn: ChatTurn, id: String, name: String, broad: Bool) -> Int? {
        if !id.isEmpty, let hit = indexOfTool(turn, id: id) { return hit }
        if let hit = lastOpenTool(turn, where: { $0.toolName == name }) { return hit }
        guard broad else { return nil }
        return lastOpenTool(turn, where: { _ in true })
    }

    private func indexOfTool(_ turn: ChatTurn, id: String) -> Int? {
        turn.segments.lastIndex { $0.tool?.toolId == id }
    }

    /// The card a confirmation row is about: its id, then a card of the same tool that is still open, then
    /// — only for a frame naming one tool — the newest card still without a result. A card another row of
    /// this same frame already took is not up for grabs again.
    private func claimedCard(
        _ turn: ChatTurn,
        id: String,
        name: String,
        claimed: Set<Int>,
        loose: Bool
    ) -> Int? {
        if !id.isEmpty, let hit = indexOfTool(turn, id: id), !claimed.contains(turn.segments[hit].id) {
            return hit
        }
        if let hit = turn.segments.lastIndex(where: { segment in
            guard let run = segment.tool, run.result == nil, run.toolName == name else { return false }
            return !claimed.contains(segment.id)
        }) { return hit }
        guard loose else { return nil }
        return turn.segments.lastIndex(where: { segment in
            guard let run = segment.tool, run.result == nil else { return false }
            return !claimed.contains(segment.id)
        })
    }

    private func lastOpenTool(_ turn: ChatTurn, where predicate: (ChatToolRun) -> Bool) -> Int? {
        turn.segments.lastIndex {
            guard let run = $0.tool, run.result == nil else { return false }
            return predicate(run)
        }
    }

    /// The newest block still waiting on an answer, which is the one a repeat of the same ask updates.
    private func unansweredConfirmation(_ turn: ChatTurn) -> Int? {
        turn.segments.lastIndex {
            guard case .confirmation = $0.kind else { return false }
            return $0.rows.contains { $0.answer == nil }
        }
    }

    /// The newest block whose rows have all been answered — the one a failed delivery hands back.
    private func settledConfirmation(_ turn: ChatTurn) -> Int? {
        turn.segments.lastIndex {
            guard case .confirmation = $0.kind else { return false }
            return !$0.rows.isEmpty && $0.rows.allSatisfy { $0.answer != nil }
        }
    }

    private func merge(_ existing: [ChatPendingTool], _ incoming: [ChatPendingTool]) -> [ChatPendingTool] {
        var rows = existing
        for row in incoming {
            guard let index = rows.firstIndex(where: { $0.toolId == row.toolId }) else {
                rows.append(row)
                continue
            }
            rows[index] = row
        }
        return rows
    }

    private mutating func openAnswerTurn(at date: Date = Date()) {
        textSealed = false
        thinkingSealed = false
        turns.append(ChatTurn(
            id: "answer-\(nextTurn())",
            role: .assistant,
            outcome: .streaming,
            timestamp: date
        ))
    }

    /// The lead's answer that is still taking frames.
    ///
    /// A member's bubble is an assistant turn with the same outcome as this one, and it can end up the last
    /// turn in the transcript — so the search goes to the round's lead turn by name rather than to the tail
    /// by position, or the lead's next frame would be written into a member's answer.
    private var openAnswer: Int? {
        guard let index = currentLeadIndex(), turns[index].outcome == .streaming else { return nil }
        return index
    }

    private mutating func nextSegment(_ kind: ChatSegment.Kind) -> ChatSegment {
        segmentCounter += 1
        return ChatSegment(id: segmentCounter, kind: kind)
    }

    private mutating func nextTurn() -> Int {
        turnCounter += 1
        return turnCounter
    }

    /// A call whose id the server left blank still needs one to pair its result. The console stamps the
    /// clock (`ChatWindow.tsx:1489`); a counter keeps the fold reproducible.
    private mutating func synthesizeToolID() -> String {
        synthesizedCounter += 1
        return "tool-\(synthesizedCounter)"
    }
}

// MARK: - reading the fold

public extension ChatSegment {
    var text: String? {
        if case .text(let message) = kind { return message }
        return nil
    }

    var thinking: String? {
        if case .thinking(let message) = kind { return message }
        return nil
    }

    var tool: ChatToolRun? {
        if case .tool(let run) = kind { return run }
        return nil
    }

    /// The pending tools of a confirmation block, empty for every other kind.
    var rows: [ChatPendingTool] {
        if case .confirmation(let tools) = kind { return tools }
        return []
    }

    var file: ChatFileAttachment? {
        if case .file(let attachment) = kind { return attachment }
        return nil
    }

    /// The plan an inline plan block carries, `nil` for every other kind.
    var plan: PlanNote? {
        if case .plan(let note) = kind { return note }
        return nil
    }
}

// MARK: - arguments

/// Arguments the way the console prints them: `JSON.stringify(arguments, null, 2)` with the keys in a
/// stable order, so re-rendering the same call does not move its lines around.
public func chatArgumentsText(_ arguments: [String: JSONValue]) -> String {
    arguments.isEmpty ? "" : chatJSONText(.object(arguments), indent: 0)
}

public func chatJSONText(_ value: JSONValue, indent: Int) -> String {
    let pad = String(repeating: "  ", count: indent)
    let inner = String(repeating: "  ", count: indent + 1)
    switch value {
    case .null:
        return "null"
    case let .boolean(flag):
        return flag ? "true" : "false"
    case let .number(number):
        return number == number.rounded() && abs(number) < 1e15
            ? String(format: "%.0f", number)
            : String(number)
    case let .string(text):
        return "\"\(chatEscaped(text))\""
    case let .array(values):
        guard !values.isEmpty else { return "[]" }
        let body = values.map { inner + chatJSONText($0, indent: indent + 1) }.joined(separator: ",\n")
        return "[\n\(body)\n\(pad)]"
    case let .object(entries):
        guard !entries.isEmpty else { return "{}" }
        let body = entries.keys.sorted().map { key in
            "\(inner)\"\(chatEscaped(key))\": \(chatJSONText(entries[key]!, indent: indent + 1))"
        }.joined(separator: ",\n")
        return "{\n\(body)\n\(pad)}"
    }
}

/// Only the characters JSON has to escape, everything else left literal — the encoder's own output turns
/// a Chinese argument into `\uXXXX` pairs.
private func chatEscaped(_ text: String) -> String {
    var output = ""
    for character in text {
        switch character {
        case "\\": output += "\\\\"
        case "\"": output += "\\\""
        case "\n": output += "\\n"
        case "\r": output += "\\r"
        case "\t": output += "\\t"
        default: output.append(character)
        }
    }
    return output
}
