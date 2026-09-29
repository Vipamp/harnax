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
    /// The turn closed while this call was still waiting for a result, so it never will get one
    /// (`markOpenToolCards`, `ChatWindow.tsx:1148-1153`).
    public var interrupted: Bool

    public init(
        toolId: String,
        toolName: String,
        arguments: [String: JSONValue] = [:],
        result: Result? = nil,
        awaitingConfirmation: Bool = false,
        interrupted: Bool = false
    ) {
        self.toolId = toolId
        self.toolName = toolName
        self.arguments = arguments
        self.result = result
        self.awaitingConfirmation = awaitingConfirmation
        self.interrupted = interrupted
    }

    /// Still waiting for the tool to come back — the one state that opens its own card.
    public var isRunning: Bool { result == nil && !awaitingConfirmation && !interrupted }
}

/// One tool a confirmation frame is asking about.
public struct ChatPendingTool: Equatable {
    public let toolId: String
    public let toolName: String
    public let arguments: [String: JSONValue]
    public let isDangerous: Bool

    public init(toolId: String, toolName: String, arguments: [String: JSONValue], isDangerous: Bool) {
        self.toolId = toolId
        self.toolName = toolName
        self.arguments = arguments
        self.isDangerous = isDangerous
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

    public init(
        id: String,
        role: Role,
        segments: [ChatSegment] = [],
        images: [String] = [],
        outcome: ChatOutcome,
        timestamp: Date
    ) {
        self.id = id
        self.role = role
        self.segments = segments
        self.images = images
        self.outcome = outcome
        self.timestamp = timestamp
    }

    /// Nothing reached the screen for this turn — the difference between "connection lost" and "stopped
    /// with a half answer still on screen" (`ChatWindow.tsx:2361`).
    public var hasContent: Bool { !segments.isEmpty || !images.isEmpty }
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
    /// `ChatWindow.tsx:2943-2962`, skipped at `:1507` and `:1676`). The panel that reads them is not in
    /// this build, so their frames stay dropped here; when it lands, the frames it needs are the ones this
    /// fold deliberately ignores.
    private static let planTools: Set<String> = ["plan_enter", "plan_write", "plan_exit"]

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
            // A member row is the server's merged output (`TeamHistoryReplay.kt:36-56`) and its bubble needs
            // the console's run grouping and delegate-card claim. Not this build: what the user sees of a
            // member's work is the text inside the lead's own `team_delegate` card.
            guard !log.isMemberOutput else { continue }
            let stamp = Self.millis(log.timestamp, now: now)
            let rowID = Self.rowID(role: log.roleKey ?? "row", stamp: stamp, index: index)
            switch log {
            case let .user(message, _, _):
                // A user row ends the answer it prompted (`ChatWindow.tsx:775-776`).
                answer = nil
                let segment = nextSegment(.text(message))
                turns.append(ChatTurn(
                    id: rowID,
                    role: .user,
                    segments: [segment],
                    outcome: .ended,
                    timestamp: Self.date(stamp)
                ))
            case let .assistant(thinking, text, calls, _, _):
                let at: Int
                if let open = answer {
                    at = open
                } else {
                    turns.append(ChatTurn(id: rowID, role: .assistant, outcome: .ended, timestamp: Self.date(stamp)))
                    at = turns.count - 1
                    answer = at
                }
                replayAssistant(
                    thinking: thinking, text: text, calls: calls, rowID: rowID,
                    following: logs, after: index, into: at
                )
            case .system, .tool, .unknown:
                // The console draws neither a system row nor a bare tool result: a result only ever reaches
                // the screen through the call it belongs to (`ChatWindow.tsx:911-929`).
                break
            }
        }
    }

    /// One stored assistant row into the bubble it belongs to, in the console's order: thinking, then one card
    /// per call, then the text (`ChatWindow.tsx:836-902`).
    ///
    /// A call's result is looked for among the TOOL rows that follow *this* row in an unbroken run, because a
    /// row can name several calls and their results come back one after another
    /// (`ChatWindow.tsx:855-861`). One that finds no result closes as interrupted: that turn was cut, and a
    /// replayed card must not sit there claiming to still be working (`:890-893`).
    private mutating func replayAssistant(
        thinking: String,
        text: String,
        calls: [ChatHistoryLog.Call],
        rowID: String,
        following logs: [ChatHistoryLog],
        after row: Int,
        into target: Int
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
            // A member's result answers a member's call, and those rows belong to another bubble.
            if follow.isMemberOutput { break }
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
    public mutating func fold(_ event: ChatEvent) {
        // A parked run says nothing. Rendering the ping would open an empty bubble, and ending on it would
        // stop the turn the ping exists to keep alive (ChatEvent.swift:80-82).
        if case .keepAlive = event { return }
        if openAnswer == nil {
            // No bubble to write into. A frame that follows the bubble's own terminal frame is dropped
            // rather than opened afresh: the server closes the socket behind that frame, so a late
            // arrival belongs to the answer that just ended, not to a new one.
            guard turns.last?.role != .assistant else { return }
            openAnswerTurn()
        }
        guard let index = openAnswer else { return }
        var turn = turns[index]
        switch event {
        case let .text(delta):
            // `isLast` closes the channel and its payload is discarded, never treated as the last batch of
            // content (`ChatWindow.tsx:1422-1428`). What was already accumulated stays where it is.
            if delta.isLast { textSealed = true } else { grow(&turn, delta.message, thinking: false) }
        case let .thinking(delta):
            if delta.isLast { thinkingSealed = true } else { grow(&turn, delta.message, thinking: true) }
        case let .toolCall(call):
            foldCall(&turn, call)
        case let .toolResult(result):
            foldResult(&turn, result)
        case let .toolConfirm(confirm):
            foldConfirm(&turn, confirm)
        case let .end(end):
            for file in end.attachments { turn.segments.append(nextSegment(.file(file))) }
            close(&turn, as: .ended)
        case let .failure(failure):
            close(&turn, as: .failed(code: failure.code, message: failure.message))
        case .keepAlive:
            break
        }
        turns[index] = turn
    }

    /// The turn's last word from the reader's side: the stream ended or threw, or a stop or a conversation
    /// switch aborted it. An already terminated answer keeps the outcome it was given.
    public mutating func terminate(as outcome: ChatOutcome) {
        guard let index = openAnswer else { return }
        var turn = turns[index]
        close(&turn, as: outcome)
        turns[index] = turn
    }

    // MARK: - frames

    private mutating func foldCall(_ turn: inout ChatTurn, _ call: ChatEvent.ToolCall) {
        guard let name = hxPresented(call.toolName) else { return }
        guard !Self.planTools.contains(name) else { return }
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
                awaitingConfirmation: existing.awaitingConfirmation
            ))
        } else {
            turn.segments.append(nextSegment(.tool(ChatToolRun(
                toolId: id,
                toolName: name,
                arguments: call.arguments
            ))))
        }
    }

    private mutating func foldResult(_ turn: inout ChatTurn, _ result: ChatEvent.ToolResult) {
        guard !Self.planTools.contains(result.toolName) else { return }
        // An unpaired result is dropped rather than drawn on its own (`ChatWindow.tsx:1614-1616`).
        guard let index = findTool(turn, id: result.toolId, name: result.toolName, broad: true),
              var run = turn.segments[index].tool
        else { return }
        run.result = ChatToolRun.Result(message: result.message, succeeded: result.success)
        turn.segments[index].kind = .tool(run)
    }

    private mutating func foldConfirm(_ turn: inout ChatTurn, _ confirm: ChatEvent.ToolConfirm) {
        var rows: [ChatPendingTool] = []
        for pending in confirm.pendingCallTools {
            guard let name = hxPresented(pending.toolName) else { continue }
            guard !Self.planTools.contains(name) else { continue }
            if let index = findTool(turn, id: pending.toolId, name: name, broad: true),
               var run = turn.segments[index].tool {
                // The card is reused and only its status changes (`ChatWindow.tsx:1683-1691`); its
                // arguments come from this frame, which is the shape the user is being asked to approve.
                run.toolName = name
                run.arguments = pending.arguments
                run.awaitingConfirmation = true
                turn.segments[index].kind = .tool(run)
            } else {
                // A confirmation can be the first frame that names the tool. Dropping it would hide the
                // very call the user is being asked about (`ChatWindow.tsx:1692-1703` opens a card).
                turn.segments.append(nextSegment(.tool(ChatToolRun(
                    toolId: pending.toolId,
                    toolName: name,
                    arguments: pending.arguments,
                    awaitingConfirmation: true
                ))))
            }
            rows.append(ChatPendingTool(
                toolId: pending.toolId,
                toolName: name,
                arguments: pending.arguments,
                isDangerous: pending.isDangerous
            ))
        }
        guard !rows.isEmpty else { return }
        if let index = lastConfirmation(turn) {
            // The same run can be asked about twice; one block, updated rather than stacked.
            turn.segments[index].kind = .confirmation(merge(turn.segments[index].rows, rows))
        } else {
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

    private func lastOpenTool(_ turn: ChatTurn, where predicate: (ChatToolRun) -> Bool) -> Int? {
        turn.segments.lastIndex {
            guard let run = $0.tool, run.result == nil else { return false }
            return predicate(run)
        }
    }

    private func lastConfirmation(_ turn: ChatTurn) -> Int? {
        turn.segments.lastIndex {
            if case .confirmation = $0.kind { return true }
            return false
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

    /// The trailing answer still taking frames. Frames always write into the last bubble, which is what
    /// makes a team member run separate work rather than a continuation of this one — routing those
    /// frames is the multi-run merge, not part of this fold.
    private var openAnswer: Int? {
        guard let index = turns.indices.last,
              turns[index].role == .assistant,
              turns[index].outcome == .streaming
        else { return nil }
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
