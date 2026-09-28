import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The console's history mapping, ported: which stored rows become bubbles, in what order, and what a
/// replayed tool card claims to know (`harnax-webui/src/pages/session/components/ChatWindow.tsx:759-930`).
final class ChatHistoryReplayTests: XCTestCase {
    // MARK: - fixtures

    private let member = ChatEventSource(
        teamId: 3,
        teamName: "翻译组",
        memberAgentId: 11,
        memberAgentName: "日志专家",
        childRunId: "team-9-m11",
        childSessionId: "team-9-m11"
    )

    private func user(_ message: String, stamp: Int64 = 1000) -> ChatHistoryLog {
        .user(message: message, timestamp: stamp, source: nil)
    }

    private func assistant(
        thinking: String = "",
        text: String = "",
        calls: [ChatHistoryLog.Call] = [],
        stamp: Int64 = 1000,
        source: ChatEventSource? = nil
    ) -> ChatHistoryLog {
        .assistant(thinking: thinking, text: text, calls: calls, timestamp: stamp, source: source)
    }

    private func tool(
        _ name: String,
        _ result: String,
        source: ChatEventSource? = nil
    ) -> ChatHistoryLog {
        .tool(name: name, result: result, timestamp: 1000, source: source)
    }

    private func call(_ name: String, _ arguments: [String: JSONValue] = [:]) -> ChatHistoryLog.Call {
        ChatHistoryLog.Call(name: name, input: arguments)
    }

    /// The shape of one bubble, as the order its blocks arrive in.
    private func kinds(_ turn: ChatTurn) -> [String] {
        turn.segments.map { segment in
            switch segment.kind {
            case .text: return "text"
            case .thinking: return "thinking"
            case .tool: return "tool"
            case .confirmation: return "confirmation"
            case .file: return "file"
            }
        }
    }

    private func replay(_ logs: [ChatHistoryLog]) -> ChatTranscript {
        ChatTranscript(replaying: logs)
    }

    // MARK: - bubbles

    func testAUserRowAndTheAnswerItOpensAreTwoBubbles() throws {
        let transcript = replay([user("第一条"), assistant(text: "答一")])

        XCTAssertEqual(transcript.turns.count, 2)
        XCTAssertEqual(transcript.turns.map(\.role), [.user, .assistant])
        XCTAssertEqual(transcript.turns[0].segments.compactMap(\.text), ["第一条"])
    }

    func testConsecutiveAssistantRowsAggregateIntoOneBubble() throws {
        let transcript = replay([
            user("第一条"),
            assistant(thinking: "想一", text: "答一", calls: [call("shell")]),
            tool("shell", "out"),
            assistant(thinking: "想二", text: "答二"),
        ])

        XCTAssertEqual(transcript.turns.count, 2, "one bubble per user turn, as while streaming")
        let answer = try XCTUnwrap(transcript.turns.last)
        XCTAssertEqual(kinds(answer), ["thinking", "tool", "text", "thinking", "text"])
    }

    /// Consecutive thinking blocks merge with a blank line between them, and nothing else breaks the run
    /// (`ChatWindow.tsx:839-849`).
    func testConsecutiveThinkingBlocksMergeWithABlankLine() throws {
        let transcript = replay([assistant(thinking: "想一"), assistant(thinking: "想二", text: "答")])

        let answer = try XCTUnwrap(transcript.turns.last)
        XCTAssertEqual(answer.segments.compactMap(\.thinking), ["想一\n\n想二"])
        XCTAssertEqual(kinds(answer), ["thinking", "text"])
    }

    func testAUserRowOpensANewBubbleForTheNextAnswer() throws {
        let transcript = replay([
            user("第一条"), assistant(text: "答一"),
            user("第二条"), assistant(text: "答二"),
        ])

        XCTAssertEqual(transcript.turns.count, 4)
        XCTAssertEqual(transcript.turns.map(\.role), [.user, .assistant, .user, .assistant])
        XCTAssertEqual(transcript.turns[3].segments.compactMap(\.text), ["答二"])
    }

    /// A row the model answered with nothing still gets its bubble, which is what the next row then writes
    /// into rather than starting a second one.
    func testAnEmptyAssistantRowOpensABubbleButDrawsNothing() throws {
        let transcript = replay([assistant()])

        XCTAssertEqual(transcript.turns.count, 1)
        XCTAssertFalse(try XCTUnwrap(transcript.turns.last).hasContent, "nothing in it, so nothing to stamp")
    }

    func testTheNextRowWritesIntoTheBubbleTheEmptyRowOpened() throws {
        let transcript = replay([assistant(), assistant(text: "答")])

        XCTAssertEqual(transcript.turns.count, 1)
        XCTAssertEqual(transcript.turns.last?.segments.compactMap(\.text), ["答"])
    }

    // MARK: - segment order

    func testOneRowWritesThinkingThenCallsThenText() throws {
        let transcript = replay([
            assistant(thinking: "想", text: "答", calls: [call("shell", ["command": .string("ls")])]),
            tool("shell", "a.md"),
        ])

        let answer = try XCTUnwrap(transcript.turns.last)
        XCTAssertEqual(kinds(answer), ["thinking", "tool", "text"])
    }

    // MARK: - tool cards

    func testACallTakesTheToolRowThatFollowsItAsItsResult() throws {
        let transcript = replay([
            assistant(calls: [call("shell", ["command": .string("ls")])]),
            tool("shell", "a.md"),
        ])

        let run = try XCTUnwrap(transcript.turns.last?.segments.first?.tool)
        XCTAssertEqual(run.arguments["command"], .string("ls"), "the stored row is the argument shape it ran with")
        XCTAssertEqual(run.result?.message, "a.md")
        XCTAssertFalse(run.isRunning, "a replayed card is over, whatever else it knows")
    }

    /// A row can name several calls and their results come back one after another; pairing only the first
    /// would leave the rest spinning forever (`ChatWindow.tsx:852-861`).
    func testSeveralCallsPairWithSeveralResultsInOrder() throws {
        let transcript = replay([
            assistant(calls: [call("shell"), call("write_file")]),
            tool("shell", "out"),
            tool("write_file", "written"),
        ])

        let runs = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool))
        XCTAssertEqual(runs.count, 2)
        XCTAssertEqual(runs.map { $0.result?.message }, ["out", "written"])
    }

    func testACallWithNoResultRowReadsAsInterrupted() throws {
        let transcript = replay([assistant(text: "答", calls: [call("shell")])])

        let run = try XCTUnwrap(transcript.turns.last?.segments.first?.tool)
        XCTAssertNil(run.result)
        XCTAssertTrue(run.interrupted)
        XCTAssertFalse(run.isRunning, "a replayed card must not claim to still be working")
    }

    func testAPlanCallAndItsResultAreNotReplayed() throws {
        let transcript = replay([
            assistant(calls: [call("plan_write"), call("shell")]),
            tool("plan_write", "plan saved"),
            tool("shell", "out"),
        ])

        let runs = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool))
        XCTAssertEqual(runs.map(\.toolName), ["shell"])
        XCTAssertEqual(runs.first?.result?.message, "out", "the plan result does not steal the shell card's pair")
    }

    func testACallWithoutANameIsNotReplayed() throws {
        let transcript = replay([assistant(calls: [call(""), call("shell")])])

        let runs = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool))
        XCTAssertEqual(runs.count, 1)
    }

    /// A tool result never stands on its own: whatever is left over after the calls claimed their pairs is
    /// simply not drawn (`ChatWindow.tsx:925-926`).
    func testAnUnclaimedToolRowOpensNoBlock() throws {
        let transcript = replay([user("第一条"), assistant(text: "答"), tool("shell", "orphan")])

        XCTAssertEqual(transcript.turns.count, 2)
        XCTAssertEqual(transcript.turns.last?.segments.count, 1)
    }

    func testReplayedToolIdsAreDistinctPerCall() throws {
        let transcript = replay([
            assistant(calls: [call("shell"), call("shell")]),
            tool("shell", "one"),
            tool("shell", "two"),
        ])

        let runs = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool))
        XCTAssertEqual(Set(runs.map(\.toolId)).count, 2)
        XCTAssertEqual(runs.map { $0.result?.message }, ["one", "two"])
    }

    // MARK: - what replay leaves out

    /// A member row is the server's merged output and its bubble needs the console's run grouping and
    /// delegate-card claim. This build shows a member's work as the text inside the lead's own card instead.
    func testMemberRowsAreLeftOutOfTheReplay() throws {
        let transcript = replay([
            user("第一条"),
            assistant(calls: [call("team_delegate")]),
            tool("team_delegate", "成员结论"),
            assistant(thinking: "成员思考", text: "成员结论", source: member),
            tool("shell", "成员结果", source: member),
        ])

        XCTAssertEqual(transcript.turns.count, 2, "the member's rows open no bubble of their own")
        let runs = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool))
        XCTAssertEqual(runs.count, 1)
        XCTAssertEqual(runs.first?.result?.message, "成员结论")
    }

    /// A member's result answers a member's call, so it must not be claimed by the lead's card — the scan
    /// stops at the source boundary the same way the console's does (`ChatWindow.tsx:859`).
    func testAMemberResultDoesNotPairWithALeadCall() throws {
        let transcript = replay([
            assistant(calls: [call("shell")]),
            tool("shell", "成员结果", source: member),
        ])

        let run = try XCTUnwrap(transcript.turns.last?.segments.first?.tool)
        XCTAssertNil(run.result)
        XCTAssertTrue(run.interrupted)
    }

    func testSystemRowsAreLeftOut() throws {
        let transcript = replay([
            user("第一条"),
            .system(message: "上下文已压缩", timestamp: 1000, source: nil),
            assistant(text: "答"),
        ])

        XCTAssertEqual(transcript.turns.count, 2)
        XCTAssertEqual(transcript.turns.last?.segments.compactMap(\.text), ["答"])
    }

    func testUnknownRowsAreLeftOut() throws {
        let transcript = replay([user("第一条"), .unknown, assistant(text: "答")])

        XCTAssertEqual(transcript.turns.count, 2)
    }

    /// The team merge interleaves rows, so the array is not time-ordered even though every row is stamped
    /// (`TeamHistoryReplayTest.kt:114`). Consuming it in array order is the only safe read.
    func testRowsAreConsumedInArrayOrderNotByStamp() throws {
        let transcript = replay([
            user("先问", stamp: 900),
            assistant(text: "后答", stamp: 100),
            user("后问", stamp: 500),
        ])

        XCTAssertEqual(transcript.turns.compactMap { $0.segments.first?.text }, ["先问", "后答", "后问"])
    }

    // MARK: - what replay does not claim to know

    /// A stored result row carries no success flag — that lived in the stream's `ToolResultEvent` — so every
    /// replayed result reads as completed rather than as a guess about which one failed.
    func testAReplayedResultReadsAsCompleted() throws {
        let transcript = replay([assistant(calls: [call("shell")]), tool("shell", "命令失败")])

        XCTAssertEqual(transcript.turns.last?.segments.first?.tool?.result?.succeeded, true)
    }

    func testReplayedTurnsAreAllClosedSoANewSendStartsFresh() throws {
        var transcript = replay([user("第一条"), assistant(text: "答一")])
        XCTAssertTrue(transcript.isTerminated, "nothing from the stored session is still being folded")

        transcript.send("第二条")

        XCTAssertEqual(transcript.turns.count, 4)
        XCTAssertEqual(transcript.turns[1].outcome, .ended)
        XCTAssertEqual(transcript.turns[3].outcome, .streaming)
    }
}
