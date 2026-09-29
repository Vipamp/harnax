import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The answer path of a tool confirmation: what each button puts on the wire, how the stream that comes
/// back is folded, and what happens when a resumed run asks again.
///
/// An answer is not a fire-and-forget POST. `POST /api/router/agent/confirm` answers with an SSE stream of
/// its own that carries the resumed run
/// (`harnax-session-router/src/main/kotlin/com/agnetix/harnax/router/proxy/SessionRouterService.kt:221`),
/// and that stream may hold another `ToolConfirmEvent` (`ChatWindow.tsx:2036-2331`). Every test here drives
/// the flow objects — the view model, the transcript and the request builder — never a view.
@MainActor
final class ToolConfirmationTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "翻译一组")

    /// One per-tool decision as it leaves on the wire.
    private struct Choice: Equatable {
        let toolId: String
        let confirmed: Bool
        let alwaysAllow: Bool
    }

    /// `wired: false` is a host that has no confirm channel: the dependency stays optional, exactly as the
    /// other three on this screen are.
    private func makeModel(
        wired: Bool = true
    ) -> (ChatViewModel, ScriptedChatStream, ScriptedToolConfirmer) {
        let stream = ScriptedChatStream()
        let confirmer = ScriptedToolConfirmer()
        let vm = ChatViewModel(
            streaming: stream,
            confirming: wired ? confirmer : nil,
            conversation: conversation
        )
        return (vm, stream, confirmer)
    }

    private func waitUntil(
        _ what: String,
        file: StaticString = #filePath,
        line: UInt = #line,
        check: @MainActor () -> Bool
    ) async {
        for _ in 0..<200 {
            if check() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for \(what)", file: file, line: line)
    }

    /// One turn that stops on a confirmation: the parked card, then the end frame the harness emits as the
    /// run pauses (`HarnessAgentWrapper.kt:763-798` — the resumed words come on the confirm response).
    private func park(
        on vm: ChatViewModel,
        _ stream: ScriptedChatStream,
        frames: [String]? = nil
    ) async throws {
        let before = stream.ports.count
        XCTAssertTrue(vm.send("删掉临时目录"))
        await waitUntil("a stream for the turn") { stream.ports.count > before }
        try stream.latest.feed(frames ?? [
            ChatFrames.confirm(
                ChatFrames.pending(id: "t-1", name: "bash", arguments: #"{"command":"ls"}"#, dangerous: true)
            ),
            ChatFrames.end(),
        ])
        await waitUntil("the confirmation to be on screen") { vm.pendingConfirmation != nil }
    }

    private func twoRows(_ first: String, _ second: String) -> [String] {
        [
            ChatFrames.confirm(
                ChatFrames.pending(id: first, name: first == "t-1" ? "read_file" : "bash"),
                ChatFrames.pending(id: second, name: "delete_file", dangerous: true)
            ),
            ChatFrames.end(),
        ]
    }

    private func listed(_ request: ConfirmAgentRequest) -> [String: String] {
        Dictionary(uniqueKeysWithValues: request.toolInfoList.map { ($0.toolId, $0.toolName) })
    }

    private func decided(_ request: ConfirmAgentRequest) -> [Choice] {
        request.toolResults.map { Choice(toolId: $0.toolId, confirmed: $0.confirmed, alwaysAllow: $0.alwaysAllow) }
    }

    // MARK: - the answer path may be absent

    /// A host that has not wired a confirmer must not offer a control that can only fail: the card keeps
    /// waiting, and nothing leaves the screen (`ChatWindow.tsx:451-460` gates its buttons the same way).
    func testAnUnwiredHostShowsTheWaitWithoutAnAnswer() async throws {
        let (vm, stream, confirmer) = makeModel(wired: false)
        try await park(on: vm, stream)

        XCTAssertEqual(vm.pendingConfirmation?.tools.count, 1)
        XCTAssertFalse(vm.canAnswerConfirmation)

        vm.submitConfirmation()
        vm.answerAllConfirmation(.denied)
        XCTAssertTrue(confirmer.requests.isEmpty)
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.segments.first?.tool?.awaitingConfirmation, true)
    }

    func testAConfirmationIsOnlyAnswerableWhileItIsWaiting() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)
        XCTAssertTrue(vm.canAnswerConfirmation)

        vm.submitConfirmation()
        await waitUntil("the answer to leave") { confirmer.requests.count == 1 }

        XCTAssertNil(vm.pendingConfirmation, "an answered block does not ask a second time")
        XCTAssertFalse(vm.canAnswerConfirmation)
    }

    // MARK: - the wire body

    /// Approving is the console's bulk body, field for field (`ChatWindow.tsx:1730-1749`,
    /// `harnax-protocol/src/main/kotlin/com/agnetix/harnax/agent/protocol/AgentRequest.kt:136-145`).
    func testApprovingSendsTheBulkBodyTheConsoleSends() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.submitConfirmation()
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(request.sessionId, "s-1")
        XCTAssertEqual(request.isConfirmed, true)
        XCTAssertEqual(listed(request), ["t-1": "bash"])
        XCTAssertTrue(request.toolResults.isEmpty, "a uniform approve stays in bulk mode")
        XCTAssertNil(request.childRunId)
    }

    func testRejectingTheBatchSendsBulkFalse() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.answerAllConfirmation(.denied)
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(request.isConfirmed, false)
        XCTAssertEqual(listed(request), ["t-1": "bash"])
        XCTAssertTrue(request.toolResults.isEmpty)
    }

    /// 「总是允许」 has no bulk leg on the wire — the flag lives on the per-tool decision
    /// (`AgentRequest.kt:150-160`), so an always-allow answer has to leave in per-tool mode.
    func testAlwaysAllowSendsAPerToolDecisionForEveryRow() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: twoRows("t-1", "t-2"))

        vm.answerAllConfirmation(.alwaysAllowed)
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(decided(request), [
            Choice(toolId: "t-1", confirmed: true, alwaysAllow: true),
            Choice(toolId: "t-2", confirmed: true, alwaysAllow: true),
        ])
        XCTAssertEqual(request.isConfirmed, true)
        XCTAssertEqual(request.toolInfoList.count, 2)
    }

    /// The panel's one 确定 control sends the rows' own choices, in the order the frame listed them
    /// (`AgentRequest.kt:120-124` — a non-empty `toolResults` is the mode the server reads).
    func testThePanelSendsEachRowsOwnChoice() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: twoRows("t-1", "t-2"))

        XCTAssertEqual(vm.confirmationChoice(for: "t-1"), .allowed, "a row nobody touched is approved")
        vm.setConfirmationChoice(.denied, for: "t-2")
        vm.submitConfirmation()
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(decided(request), [
            Choice(toolId: "t-1", confirmed: true, alwaysAllow: false),
            Choice(toolId: "t-2", confirmed: false, alwaysAllow: false),
        ])
        XCTAssertEqual(request.isConfirmed, false, "a batch with a refusal in it is not a bulk approval")
    }

    func testRefusingEveryRowFromThePanelFallsBackToBulkMode() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: twoRows("t-1", "t-2"))

        vm.setConfirmationChoice(.denied, for: "t-1")
        vm.setConfirmationChoice(.denied, for: "t-2")
        vm.submitConfirmation()
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(request.isConfirmed, false)
        XCTAssertTrue(request.toolResults.isEmpty)
    }

    /// A member run is resumed with one decision for the whole run, and the server ANDs a per-tool list into
    /// it (`DefaultAgentRunner.kt:427`), so a mixed list would silently deny tools the user approved: the
    /// answer goes bulk, with the run id back on it (`ChatWindow.tsx:1295-1349`).
    func testAMemberConfirmationCarriesItsRunIdBackInBulkMode() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: [
            ChatFrames.memberConfirm(run: "run-9", rows: [
                ChatFrames.pending(id: "t-1", name: "read_file"),
                ChatFrames.pending(id: "t-2", name: "delete_file", dangerous: true),
            ]),
            ChatFrames.end(),
        ])
        XCTAssertEqual(vm.pendingConfirmation?.childRunId, "run-9")

        vm.setConfirmationChoice(.denied, for: "t-2")
        vm.submitConfirmation()
        await waitUntil("the confirm request") { !confirmer.requests.isEmpty }

        let request = try XCTUnwrap(confirmer.requests.first)
        XCTAssertEqual(request.childRunId, "run-9")
        XCTAssertTrue(request.toolResults.isEmpty, "one run, one answer")
        XCTAssertEqual(request.isConfirmed, false, "a member run with a refusal in it is refused")
    }

    /// Answering a member must not make the answer leg the screen's reader. The member run resumes inside the
    /// lead's tool call and its words, and the lead's own after them, all arrive on the lead's socket, while
    /// the answer itself comes back as one bare End (`DefaultAgentRunner.kt:400-441`). Taking over the read
    /// folded that End into the live turn: the lead's socket closed, every later frame was dropped, and the
    /// turn looked finished while the team was still working.
    func testAnsweringAMemberLeavesTheLeadStreamReading() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: [
            ChatFrames.memberConfirm(run: "run-9", rows: [ChatFrames.pending(id: "t-1", name: "read_file")]),
        ])
        XCTAssertTrue(vm.isStreaming, "the lead parked on the ask but its stream is still open")
        try stream.latest.feed([ChatFrames.text("成员把文件读完了")])
        await waitUntil("the lead's own words on screen") { vm.transcript.segments.contains {
            if case .text = $0.kind { return true }
            return false
        } }

        vm.submitConfirmation()
        await waitUntil("the member answer to go out") { !confirmer.requests.isEmpty }
        try confirmer.latest.feed([ChatFrames.end()])
        await waitUntil("the member leg to let go") { confirmer.latest.isTerminated }

        XCTAssertFalse(vm.transcript.isTerminated, "the bare End of the answer is not the lead's end")
        XCTAssertTrue(vm.isStreaming)

        try stream.latest.feed([ChatFrames.end()])
        await waitUntil("the lead's end to close the turn") { vm.transcript.isTerminated }
        let words = vm.transcript.segments.compactMap { segment -> String? in
            if case let .text(message) = segment.kind { return message }
            return nil
        }
        XCTAssertEqual(words, ["成员把文件读完了"])
        XCTAssertNil(vm.pendingConfirmation, "the answered block is not pending any more")
    }

    /// A stop is the last word on that run, so the ask parked on it goes with it. The console nulls the
    /// member answer handler when the stream closes for exactly this reason
    /// (`ChatWindow.tsx:2378-2379`); leaving 「确定」 live would reopen a bubble the screen already calls
    /// interrupted and open a read the router can only refuse.
    func testStoppingATurnParkedOnAMemberAskRetiresTheAnswer() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream, frames: [
            ChatFrames.memberConfirm(run: "run-9", rows: [ChatFrames.pending(id: "t-1", name: "read_file")]),
        ])
        XCTAssertNotNil(vm.pendingConfirmation)

        vm.stop()
        XCTAssertNil(vm.pendingConfirmation, "the ask went with the run the user gave up on")
        XCTAssertFalse(vm.canAnswerConfirmation)
        vm.submitConfirmation()
        vm.answerAllConfirmation(.allowed)
        await Task.yield()
        XCTAssertTrue(confirmer.requests.isEmpty, "nothing goes out for a dead run")
    }

    // MARK: - the resumed run

    /// The frames that come back are the same turn's continuation: they land in the bubble that parked, not
    /// in a fresh one.
    func testTheResumedStreamContinuesTheSameBubble() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)
        let turns = vm.transcript.turns.count

        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        XCTAssertTrue(vm.isStreaming, "the run is reading again")

        try confirmer.latest.feed(
            ChatFrames.result(id: "t-1", name: "bash", message: "a.txt"),
            ChatFrames.text("已经列出"),
            ChatFrames.end()
        )
        await waitUntil("the resumed answer to close") { !vm.isStreaming }

        XCTAssertEqual(vm.transcript.turns.count, turns, "a resumed run opens no second bubble")
        let run = try XCTUnwrap(vm.transcript.segments.first?.tool)
        XCTAssertEqual(run.result?.message, "a.txt")
        XCTAssertEqual(run.confirmAnswer, .allowed)
        XCTAssertFalse(run.awaitingConfirmation)
        XCTAssertEqual(vm.transcript.segments.compactMap(\.text).last, "已经列出")
    }

    /// A denial is answered too: the tool comes back with a refusal result, and the card keeps the refusal as
    /// its own state (`ChatWindow.tsx:319-324` ranks `rejected` above a result).
    func testADeniedCardKeepsItsRefusalAfterTheResult() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.answerAllConfirmation(.denied)
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        try confirmer.latest.feed(
            ChatFrames.result(id: "t-1", name: "bash", message: "user rejected", success: false),
            ChatFrames.end()
        )
        await waitUntil("the refusal to land") { vm.transcript.segments.first?.tool?.result != nil }

        let run = try XCTUnwrap(vm.transcript.segments.first?.tool)
        XCTAssertEqual(run.confirmAnswer, .denied)
        XCTAssertFalse(run.isRunning, "a refused call is not still working")
    }

    /// The recursion the console only reaches by copying its read loop three times: a confirmation arriving
    /// on a confirm stream opens a fresh panel, and answering it opens a third stream
    /// (`ChatWindow.tsx:2036-2331`).
    func testANestedConfirmationAsksAgainOnTheResumedStream() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        try confirmer.latest.feed(
            ChatFrames.text("先要写文件"),
            ChatFrames.confirm(ChatFrames.pending(id: "t-2", name: "write_file", dangerous: true)),
            ChatFrames.end()
        )
        await waitUntil("the second question") { vm.pendingConfirmation?.tools.count == 1 }

        XCTAssertEqual(vm.pendingConfirmation?.tools.first?.toolId, "t-2")
        XCTAssertTrue(vm.canAnswerConfirmation, "a nested ask is answerable the same way")
        let blocks = vm.transcript.segments.filter { !$0.rows.isEmpty }
        XCTAssertEqual(blocks.count, 2, "the answered block stays, the new question gets its own")
        XCTAssertEqual(blocks.first?.rows.first?.answer, .allowed)
        XCTAssertNil(blocks.last?.rows.first?.answer)

        vm.answerAllConfirmation(.alwaysAllowed)
        await waitUntil("the second answer") { confirmer.requests.count == 2 }
        XCTAssertEqual(decided(confirmer.requests[1]), [Choice(toolId: "t-2", confirmed: true, alwaysAllow: true)])

        try confirmer.latest.feed(ChatFrames.text("写好了"), ChatFrames.end())
        await waitUntil("the third stream to close") { !vm.isStreaming }
        XCTAssertEqual(confirmer.ports.count, 2)
        XCTAssertEqual(vm.transcript.segments.compactMap(\.text), ["先要写文件", "写好了"])
    }

    /// A server that refuses the answer says so on the confirm stream
    /// (`DefaultAgentRunner.kt:355-364`, `:429-435`): its sentence reaches the screen.
    func testARefusedAnswerSaysWhyAndStopsReading() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        try confirmer.latest.feed(
            ChatFrames.failure(code: "SYSTEM_ERROR", message: "No pending tool confirmation for session s-1"),
            ChatFrames.end()
        )
        await waitUntil("the refusal") { vm.stopNotice != nil }

        guard case let .failed(text)? = vm.stopNotice else { return XCTFail("expected a failure sentence") }
        XCTAssertTrue(text.contains("No pending tool confirmation for session s-1"), "got \(text)")
        XCTAssertFalse(vm.isStreaming)
    }

    /// The answer never reached the server at all: the block goes back on the panel rather than leaving a
    /// decision the run never saw.
    func testAnAnswerThatNeverLeavesGoesBackOnThePanel() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.setConfirmationChoice(.denied, for: "t-1")
        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        confirmer.latest.fail(APIError.offline)
        await waitUntil("the answer to be handed back") { vm.pendingConfirmation != nil }

        XCTAssertTrue(vm.canAnswerConfirmation)
        let run = try XCTUnwrap(vm.transcript.segments.first?.tool)
        XCTAssertTrue(run.awaitingConfirmation, "a card whose answer never left is still waiting")
        XCTAssertNil(run.confirmAnswer)
        XCTAssertEqual(vm.confirmationChoice(for: "t-1"), .denied, "the choice the user made is still there")
        guard case let .failed(text)? = vm.stopNotice else { return XCTFail("expected a failure sentence") }
        XCTAssertFalse(text.isEmpty)
    }

    /// A send that failed is not a lost answer: the same block can be answered again.
    func testAParkedConfirmationCanBeAnsweredAgainAfterAFailedSend() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)

        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }
        confirmer.latest.fail(APIError.offline)
        await waitUntil("the answer to be handed back") { vm.pendingConfirmation != nil }

        vm.submitConfirmation()
        await waitUntil("the second answer") { confirmer.requests.count == 2 }
    }

    // MARK: - leaving the screen

    func testMovingToAnotherConversationClearsThePanel() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)
        vm.setConfirmationChoice(.denied, for: "t-1")

        vm.bind(ChatConversation(id: "s-2", title: "另一个"))

        XCTAssertNil(vm.pendingConfirmation)
        XCTAssertEqual(vm.confirmationChoice(for: "t-1"), .allowed, "a choice belongs to one conversation")
        XCTAssertTrue(confirmer.requests.isEmpty)
    }

    func testStoppingATurnThatIsReadingItsAnswerAbortsTheRead() async throws {
        let (vm, stream, confirmer) = makeModel()
        try await park(on: vm, stream)
        vm.submitConfirmation()
        await waitUntil("the confirm stream") { confirmer.ports.count == 1 }

        XCTAssertTrue(vm.isStreaming)
        vm.stop()

        await waitUntil("the resumed read to be let go") { confirmer.latest.isTerminated }
        XCTAssertFalse(vm.isStreaming)
    }

    // MARK: - the fold's own rules

    func testResolvingWritesTheDecisionIntoTheBlockAndItsCards() throws {
        var transcript = ChatTranscript()
        transcript.send("删掉临时目录")
        try foldAll(
            &transcript,
            ChatFrames.confirm(
                ChatFrames.pending(id: "t-1", name: "bash"),
                ChatFrames.pending(id: "t-2", name: "write_file")
            ),
            ChatFrames.end()
        )

        transcript.resolveConfirmation(["t-1": .alwaysAllowed, "t-2": .denied])

        let rows = transcript.segments.first { !$0.rows.isEmpty }?.rows ?? []
        XCTAssertEqual(rows.first?.answer, .alwaysAllowed)
        XCTAssertEqual(rows.last?.answer, .denied)
        let runs = transcript.segments.compactMap(\.tool)
        XCTAssertEqual(runs.map(\.awaitingConfirmation), [false, false])
        XCTAssertEqual(runs.map(\.confirmAnswer), [.alwaysAllowed, .denied])
        XCTAssertNil(transcript.pendingConfirmation, "nothing is waiting any more")
        XCTAssertFalse(transcript.isTerminated, "the parked bubble is open again for the resumed run")
    }

    func testReopeningHandsTheBlockBackToTheUser() throws {
        var transcript = ChatTranscript()
        transcript.send("删掉临时目录")
        try foldAll(&transcript, ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")))

        transcript.resolveConfirmation([:])
        XCTAssertNil(transcript.pendingConfirmation)

        transcript.reopenConfirmation()

        XCTAssertEqual(transcript.pendingConfirmation?.tools.first?.toolId, "t-1")
        XCTAssertNil(transcript.pendingConfirmation?.tools.first?.answer)
        XCTAssertTrue(transcript.segments.first?.tool?.awaitingConfirmation ?? false)
    }

    /// A second ask for a tool that was already answered is a new question, not an edit of the settled one
    /// (`ChatWindow.tsx:2036-2331` opens a fresh pending state for the nested frame).
    func testAFreshAskAfterAnAnswerOpensItsOwnBlock() throws {
        var transcript = ChatTranscript()
        transcript.send("删掉临时目录")
        try foldAll(&transcript, ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")))
        transcript.resolveConfirmation(["t-1": .allowed])
        try foldAll(&transcript, ChatFrames.confirm(ChatFrames.pending(id: "t-2", name: "write_file")))

        let blocks = transcript.segments.filter { !$0.rows.isEmpty }
        XCTAssertEqual(blocks.count, 2)
        XCTAssertEqual(blocks.first?.rows.first?.answer, .allowed)
        XCTAssertEqual(transcript.pendingConfirmation?.tools.first?.toolId, "t-2")
    }

    func testAMemberAskIsRememberedByRun() throws {
        var transcript = ChatTranscript()
        transcript.send("翻译")
        try foldAll(
            &transcript,
            ChatFrames.memberConfirm(run: "run-4", rows: [ChatFrames.pending(id: "t-1", name: "bash")])
        )

        XCTAssertEqual(transcript.pendingConfirmation?.childRunId, "run-4")
        XCTAssertEqual(transcript.pendingConfirmation?.tools.first?.childRunId, "run-4")
    }

    private func foldAll(_ transcript: inout ChatTranscript, _ frames: String...) throws {
        for frame in frames { try transcript.fold(ChatEvent.decode(frame)) }
    }
}

/// The confirm channel, driven by the test.
///
/// Each `confirm` call records the body and hands out a stream of its own, because the answer's shape and
/// the run it resumes are the two halves of one contract.
private final class ScriptedToolConfirmer: ToolConfirming, @unchecked Sendable {
    private(set) var requests: [ConfirmAgentRequest] = []
    private(set) var ports: [ChatStreamPort] = []

    /// The stream the screen is reading now.
    var latest: ChatStreamPort {
        guard let port = ports.last else {
            fatalError("the screen never answered a confirmation")
        }
        return port
    }

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        requests.append(request)
        let port = ChatStreamPort()
        ports.append(port)
        return port.makeStream()
    }
}

extension ChatFrames {
    /// A member run's ask: the same frame, with the source that says which parked run it belongs to.
    static func memberConfirm(run: String, rows: [String]) -> String {
        """
        {"eventType":"ToolConfirmEvent","pendingCallTools":[\(rows.joined(separator: ","))],\
        "source":{"teamId":7,"teamName":"翻译组","memberAgentId":3,"memberAgentName":"审校",\
        "childRunId":"\(run)","childSessionId":"s-3"}}
        """
    }
}
