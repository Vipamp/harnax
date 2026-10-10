import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The folding rules, frame by frame.
///
/// Every rule the conversation screen has to behave by lives in `ChatTranscript`, so every one of them is a
/// test here rather than something a screenshot has to catch.
final class ChatTranscriptTests: XCTestCase {
    private func decode(_ payload: String) throws -> ChatEvent {
        try ChatEvent.decode(payload)
    }

    private func fold(_ transcript: inout ChatTranscript, _ payloads: String...) throws {
        for payload in payloads {
            transcript.fold(try decode(payload))
        }
    }

    /// A transcript with a turn already open, which is what the screen holds when a stream starts.
    private func started() -> ChatTranscript {
        var transcript = ChatTranscript()
        transcript.send("跑一下")
        return transcript
    }

    // MARK: - text

    func testConsecutiveDeltasMergeIntoOneBlock() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("你好"), ChatFrames.text("，世界"))

        XCTAssertEqual(transcript.segments.count, 1)
        XCTAssertEqual(transcript.segments.first?.text, "你好，世界")
    }

    func testAnEmptyFrameOpensNoBlock() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text(""), ChatFrames.text("后面"))

        XCTAssertEqual(transcript.segments.count, 1)
        XCTAssertEqual(transcript.segments.first?.text, "后面")
    }

    func testThinkingAndTextAreSeparateBlocks() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.thinking("先看一眼"),
                 ChatFrames.thinking("再想想"),
                 ChatFrames.text("结论是"),
                 ChatFrames.text("可以"),
                 ChatFrames.thinking("还想再查"))

        XCTAssertEqual(transcript.segments.map(\.kind), [
            .thinking("先看一眼再想想"),
            .text("结论是可以"),
            .thinking("还想再查"),
        ])
    }

    /// A closing frame carries no content and seals the run it closes: the delta behind it belongs to a new
    /// message, not to the one that was signed off (`ChatWindow.tsx:1427-1433`).
    func testClosingFrameDropsItsPayloadAndStartsANewBlock() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("第一段"),
                 ChatFrames.text("这句不该出现", isLast: true),
                 ChatFrames.text("第二段"))

        XCTAssertEqual(transcript.segments.map(\.text), ["第一段", "第二段"])
    }

    func testAClosingThinkingFrameDoesNotSealText() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.thinking("想"),
                 ChatFrames.thinking("完", isLast: true),
                 ChatFrames.text("答"),
                 ChatFrames.text("案"))

        XCTAssertEqual(transcript.segments.map(\.kind), [.thinking("想"), .text("答案")])
    }

    // MARK: - tool cards

    func testACallAndItsResultAreOneBlock() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash", arguments: #"{"command":"ls"}"#),
                 ChatFrames.result(id: "t-1", name: "bash", message: "a.md"))

        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertEqual(run.toolId, "t-1")
        XCTAssertEqual(run.toolName, "bash")
        XCTAssertEqual(run.arguments["command"], .string("ls"))
        XCTAssertEqual(run.result, ChatToolRun.Result(message: "a.md", succeeded: true))
        XCTAssertFalse(run.isRunning)
        XCTAssertEqual(transcript.segments.count, 1, "the result does not add a block of its own")
    }

    func testAFailedResultIsStillAResult() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.result(id: "t-1", name: "bash", message: "exit 1", success: false))

        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertEqual(run.result, ChatToolRun.Result(message: "exit 1", succeeded: false))
    }

    func testAResultWithNoCallOpensNoBlock() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.result(id: "t-9", name: "bash", message: "orphan"))

        XCTAssertTrue(transcript.segments.isEmpty)
    }

    func testTwoCallsWithDifferentIDsAreTwoCards() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "read_file"),
                 ChatFrames.call(id: "t-2", name: "read_file"))

        XCTAssertEqual(transcript.segments.compactMap(\.tool).map(\.toolId), ["t-1", "t-2"])
    }

    func testACallRepeatedForOneIDRewritesItsCard() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "read_file", arguments: #"{"path":"a"}"#),
                 ChatFrames.call(id: "t-1", name: "read_file", arguments: #"{"path":"b"}"#))

        XCTAssertEqual(transcript.segments.count, 1)
        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertEqual(run.arguments["path"], .string("b"))
    }

    /// `ToolResultEvent` does not always spell the name the call used, so the fold falls back to the open
    /// card the way the console's three-level lookup does (`ChatWindow.tsx:1072-1096`).
    func testAResultWithAnotherNameStillLandsOnTheOpenCard() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.result(id: "other-id", name: "shell", message: "done"))

        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertEqual(run.toolId, "t-1")
        XCTAssertEqual(run.result?.message, "done")
    }

    func testACallWithNoIDStillPairsWithItsResult() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "", name: "grep"),
                 ChatFrames.result(id: "", name: "grep", message: "3 hits"))

        XCTAssertEqual(transcript.segments.count, 1)
        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertFalse(run.toolId.isEmpty, "a blank id needs a stamp of its own to pair against")
        XCTAssertEqual(run.result?.message, "3 hits")
    }

    func testACallWithNoNameDrawsNothing() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.call(id: "t-1", name: ""))

        XCTAssertTrue(transcript.segments.isEmpty)
    }

    /// A text run broken by a tool card does not resume into the block before it.
    func testAToolCardBreaksTheTextRun() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("先"),
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.text("后"))

        XCTAssertEqual(transcript.segments.map { $0.tool == nil }, [true, false, true])
        XCTAssertEqual(transcript.segments.map(\.text), ["先", nil, "后"])
    }

    // MARK: - confirmation

    func testAConfirmationMarksTheCardAndAddsOneBlock() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash", arguments: #"{"command":"ls"}"#),
                 ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash", arguments: #"{"command":"rm -rf /tmp"}"#, dangerous: true)))

        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertTrue(run.awaitingConfirmation)
        XCTAssertEqual(run.arguments["command"], .string("rm -rf /tmp"), "the shape being approved is the frame's, not the call's")

        let rows = transcript.segments[1].rows
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.toolName, "bash")
        XCTAssertEqual(rows.first?.isDangerous, true)
    }

    func testASecondConfirmationUpdatesTheSameBlock() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")),
                 ChatFrames.confirm(
                     ChatFrames.pending(id: "t-1", name: "bash", dangerous: true),
                     ChatFrames.pending(id: "t-2", name: "write_file")
                 ))

        let blocks = transcript.segments.filter { !$0.rows.isEmpty }
        XCTAssertEqual(blocks.count, 1, "one block for the run, updated rather than stacked")
        let rows = blocks.first?.rows
        XCTAssertEqual(rows?.count, 2, "the new tool is appended, the known one replaced in place")
        XCTAssertEqual(rows?.first?.isDangerous, true)
    }

    func testAConfirmationWithNothingAnswerableOpensNoBlock() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.confirm())
        try fold(&transcript, ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "")))

        XCTAssertTrue(transcript.segments.isEmpty)
    }

    // MARK: - plan tools

    func testPlanFramesNeitherDrawNorBreakASequence() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("前"),
                 ChatFrames.call(id: "p-1", name: "plan_enter"),
                 ChatFrames.confirm(ChatFrames.pending(id: "p-1", name: "plan_write")),
                 ChatFrames.result(id: "p-1", name: "plan_exit", message: "ok"),
                 ChatFrames.text("后"))

        XCTAssertEqual(transcript.segments.map(\.text), ["前后"], "plan frames leave the accumulator alone")
    }

    // MARK: - terminal frames

    func testTheEndFrameClosesTheAnswerAndKeepsItsFiles() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("写好了"),
                 ChatFrames.end(attachments: "[\(ChatFrames.file(id: "f-1", name: "report.md"))]"))

        XCTAssertTrue(transcript.isTerminated)
        XCTAssertEqual(transcript.turns.last?.outcome, .ended)
        let file = try XCTUnwrap(transcript.segments.last?.file)
        XCTAssertEqual(file.fileName, "report.md")
        XCTAssertEqual(file.fileSize, 1024)
    }

    func testFramesBehindTheEndFrameAreDropped() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.end(), ChatFrames.text("迟到的"), ChatFrames.text("?"))

        XCTAssertTrue(transcript.segments.isEmpty)
        XCTAssertEqual(transcript.turns.count, 2)
    }

    func testTheErrorFrameKeepsItsCodeAndMessageAndAddsNoText() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("我"), ChatFrames.failure(code: "AGENT_MODEL_NOT_CONFIGURED", message: "未配置模型"))

        let turn = try XCTUnwrap(transcript.turns.last)
        XCTAssertEqual(turn.outcome, .failed(code: "AGENT_MODEL_NOT_CONFIGURED", message: "未配置模型"))
        XCTAssertEqual(turn.segments.map(\.text), ["我"], "the failure is a state, not a sentence in the transcript")
    }

    func testKeepAliveFramesAreInvisible() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.keepAlive, ChatFrames.memberKeepAlive(run: "run-3"))

        XCTAssertTrue(transcript.segments.isEmpty)
        XCTAssertEqual(transcript.turns.count, 2)
        XCTAssertEqual(transcript.turns.last?.outcome, .streaming)
    }

    func testAStreamCanOpenABubbleWithoutASend() throws {
        var transcript = ChatTranscript()
        try fold(&transcript, ChatFrames.text("pushed"))

        XCTAssertEqual(transcript.turns.last?.role, .assistant)
        XCTAssertEqual(transcript.segments.map(\.text), ["pushed"])
    }

    // MARK: - closing from the reader

    func testClosingInterruptsCardsThatNeverGotAResult() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.call(id: "t-2", name: "grep"),
                 ChatFrames.result(id: "t-2", name: "grep", message: "hit"))

        transcript.terminate(as: .interrupted)

        let runs = transcript.segments.compactMap(\.tool)
        XCTAssertEqual(runs.map(\.interrupted), [true, false])
        XCTAssertEqual(transcript.turns.last?.outcome, .interrupted)
    }

    /// A card parked on an answer is not a card that lost its result
    /// (`ChatWindow.tsx:2388-2389` leaves those alone).
    func testClosingLeavesAParkedCardParked() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")))

        transcript.terminate(as: .interrupted)

        let run = try XCTUnwrap(transcript.segments.first?.tool)
        XCTAssertTrue(run.awaitingConfirmation)
        XCTAssertFalse(run.interrupted)
    }

    func testClosingTwiceKeepsTheFirstVerdict() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("半句"))
        transcript.terminate(as: .interrupted)
        transcript.terminate(as: .failed(code: "X", message: "Y"))

        XCTAssertEqual(transcript.turns.last?.outcome, .interrupted)
        XCTAssertEqual(transcript.segments.map(\.text), ["半句"], "a terminated answer keeps what it showed")
    }

    // MARK: - turns

    func testSendAddsABubbleAndOpensTheAnswer() {
        var transcript = ChatTranscript()
        transcript.send("第一条")

        XCTAssertEqual(transcript.turns.map(\.role), [.user, .assistant])
        XCTAssertEqual(transcript.turns.first?.segments.map(\.text), ["第一条"])
        XCTAssertTrue(transcript.turns[1].segments.isEmpty)
    }

    func testANewMessageClosesTheAnswerStillFolding() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("半句"), ChatFrames.call(id: "t-1", name: "bash"))

        transcript.send("下一条")

        XCTAssertEqual(transcript.turns.count, 4)
        let abandoned = transcript.turns[1]
        XCTAssertEqual(abandoned.outcome, .interrupted)
        XCTAssertEqual(abandoned.segments.compactMap(\.tool).first?.interrupted, true)
        XCTAssertEqual(transcript.turns.last?.role, .assistant)
        XCTAssertTrue(transcript.turns.last?.segments.isEmpty ?? false)
    }

    func testSegmentIDsAreUniqueAcrossTurns() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("a"), ChatFrames.call(id: "t-1", name: "bash"))
        transcript.send("b")

        let ids = transcript.turns.flatMap(\.segments).map(\.id)
        XCTAssertEqual(Set(ids).count, ids.count)
    }

    func testAnEmptyTurnIsNotContent() throws {
        var transcript = started()
        XCTAssertFalse(try XCTUnwrap(transcript.turns.last).hasContent)
        try fold(&transcript, ChatFrames.text("有了"))
        XCTAssertTrue(try XCTUnwrap(transcript.turns.last).hasContent)
    }

    // MARK: - copying a whole bubble

    func testCopyJoinsTheTextRunsInStreamOrder() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("前面"),
                 ChatFrames.thinking("中间在想"),
                 ChatFrames.text("后面"))

        XCTAssertEqual(transcript.turns.last?.copyableText, "前面\n后面")
    }

    func testCopyLeavesOutThinkingToolsAndFiles() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("答案"),
                 ChatFrames.thinking("思路"),
                 ChatFrames.call(id: "t-1", name: "bash", arguments: #"{"command":"ls"}"#),
                 ChatFrames.result(id: "t-1", name: "bash", message: "a.md"),
                 ChatFrames.end(attachments: "[\(ChatFrames.file(id: "f-1", name: "a.md"))]"))

        XCTAssertEqual(transcript.turns.last?.copyableText, "答案")
    }

    func testCopyLeavesOutThePlanBlock() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("答案"))
        XCTAssertTrue(transcript.showPlan(PlanNote(name: "翻译整本书")))

        XCTAssertEqual(transcript.turns.last?.copyableText, "答案")
    }

    func testAThinkingOnlyAnswerHasNothingToCopy() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.thinking("只在想"))

        XCTAssertEqual(transcript.turns.last?.copyableText, "")
    }

    func testTheUserBubbleCopiesItsOwnWords() {
        var transcript = ChatTranscript()
        transcript.send("跑一下")

        XCTAssertEqual(transcript.turns.first?.copyableText, "跑一下")
    }

    // MARK: - arguments text

    func testArgumentsPrintSortedAndLiteral() throws {
        let arguments: [String: JSONValue] = [
            "zeta": .number(2),
            "note": .string("中文"),
            "list": .array([.string("a"), .null]),
        ]
        XCTAssertEqual(chatArgumentsText(arguments), """
        {
          "list": [
            "a",
            null
          ],
          "note": "中文",
          "zeta": 2
        }
        """)
    }

    func testNoArgumentsIsNoText() {
        XCTAssertEqual(chatArgumentsText([:]), "")
    }
}
