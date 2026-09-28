import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The stream's owner: what a turn asks for, how frames land while it is still reading, and what every path
/// that leaves the screen or the conversation has to clean up behind it.
@MainActor
final class ChatViewModelTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "翻译一组")

    private func makeModel(
        commands: (any AgentCommanding)? = nil
    ) -> (ChatViewModel, ScriptedChatStream) {
        let stream = ScriptedChatStream()
        let vm = ChatViewModel(streaming: stream, commands: commands, conversation: conversation)
        return (vm, stream)
    }

    /// The stream is hand-driven, so the only way to know a frame landed is to let the reader run.
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

    /// Sends and waits until the stream has been asked for.
    private func send(
        _ message: String,
        on vm: ChatViewModel,
        _ stream: ScriptedChatStream
    ) async {
        let before = stream.ports.count
        XCTAssertTrue(vm.send(message))
        await waitUntil("a stream for “\(message)”") { stream.ports.count > before }
    }

    // MARK: - a turn

    func testASendAsksTheRouterForThisConversation() async {
        let (vm, stream) = makeModel()
        await send("翻译这段", on: vm, stream)

        XCTAssertEqual(stream.chatRequests.count, 1)
        XCTAssertEqual(stream.chatRequests.first?.sessionId, "s-1")
        XCTAssertEqual(stream.chatRequests.first?.message, "翻译这段")
        XCTAssertEqual(vm.conversation.id, "s-1")
    }

    func testTheUserBubbleIsOnScreenBeforeTheFirstFrame() async {
        let (vm, stream) = makeModel()
        await send("第一条", on: vm, stream)

        XCTAssertEqual(vm.transcript.turns.map(\.role), [.user, .assistant])
        XCTAssertEqual(vm.transcript.turns.first?.segments.first?.text, "第一条")
        XCTAssertFalse(vm.transcript.turns.last?.hasContent ?? true)
        XCTAssertTrue(vm.isStreaming)
    }

    func testTheDraftIsOnlyConsumedByItsOwnSend() async {
        let (vm, _) = makeModel()
        vm.draft = "  写点东西  "
        vm.send()

        XCTAssertEqual(vm.draft, "", "a message that left cannot still be in the box")
    }

    func testBlankAndRepeatedSendsAreRefused() async {
        let (vm, stream) = makeModel()
        XCTAssertFalse(vm.send("   "))
        XCTAssertFalse(vm.send(""))

        await send("一条", on: vm, stream)
        XCTAssertFalse(vm.send("第二条"), "one turn reads at a time")
        XCTAssertEqual(stream.chatRequests.count, 1)
        XCTAssertEqual(vm.transcript.turns.count, 2, "the refused message left no bubble behind")
    }

    func testFramesLandAsTheyArriveNotAtTheEnd() async throws {
        let (vm, stream) = makeModel()
        await send("说一句", on: vm, stream)
        let port = stream.latest

        try port.feed(ChatFrames.text("你好"))
        await waitUntil("the first delta") { vm.transcript.segments.count == 1 }
        XCTAssertEqual(vm.transcript.segments.first?.text, "你好")

        try port.feed(
            ChatFrames.text("，世界"),
            ChatFrames.call(id: "t-1", name: "bash")
        )
        await waitUntil("the tool card") { vm.transcript.segments.count == 2 }
        XCTAssertEqual(vm.transcript.segments.first?.text, "你好，世界")
        XCTAssertNil(vm.transcript.segments[1].tool?.result)
        XCTAssertTrue(vm.isStreaming)
    }

    func testTheEndFrameEndsTheStream() async throws {
        let (vm, stream) = makeModel()
        await send("收尾", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("好了"), ChatFrames.end())

        await waitUntil("the read to let go") { stream.latest.isTerminated }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .ended)
        XCTAssertNil(vm.stopNotice)
    }

    // MARK: - stop

    func testStopCancelsTheReadAndInterruptsTheAnswer() async throws {
        let (vm, stream) = makeModel()
        await send("长活", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("半句"), ChatFrames.call(id: "t-1", name: "bash"))
        await waitUntil("the card") { vm.transcript.segments.count == 2 }

        vm.stop()

        await waitUntil("the read to be cancelled, not just ignored") { stream.latest.isTerminated }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .interrupted)
        XCTAssertEqual(vm.transcript.segments.compactMap(\.text), ["半句"], "a stop keeps what was already on screen")
        XCTAssertEqual(vm.transcript.segments[1].tool?.interrupted, true)
        XCTAssertNil(vm.stopNotice, "stopping on purpose is not a failure")
    }

    func testStopAlsoTellsTheServer() async {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)
        await send("停一下", on: vm, stream)

        vm.stop()

        await waitUntil("the interrupt command") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.first?.sessionId, "s-1")
        XCTAssertEqual(commands.requests.first?.command, .interrupt)
    }

    func testStopWithoutAStreamIsSilent() async {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)
        vm.stop()

        XCTAssertTrue(stream.chatRequests.isEmpty)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    // MARK: - leaving and switching

    /// The defect the console carries: switching the conversation leaves the old socket reading into the new
    /// screen. Aborting first is the whole point of `bind` (`specs/02-session-chat.md`, 流式行为).
    func testSwitchingConversationAbortsTheOldStreamBeforeAnythingElse() async throws {
        let (vm, stream) = makeModel()
        await send("旧会话", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("旧答案"))
        await waitUntil("the old frame") { vm.transcript.segments.count == 1 }

        vm.bind(ChatConversation(id: "s-2", title: "另一个"))

        await waitUntil("the previous stream to be cancelled, not orphaned") { stream.latest.isTerminated }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.conversation.id, "s-2")
        XCTAssertTrue(vm.transcript.turns.isEmpty, "the old transcript does not travel with the screen")

        await send("新会话", on: vm, stream)
        XCTAssertEqual(stream.chatRequests.map(\.sessionId), ["s-1", "s-2"])
        XCTAssertTrue(stream.ports.first?.isTerminated ?? false, "the first read is still the cancelled one")
    }

    func testBindingTheSameConversationAgainKeepsTheAnswer() async throws {
        let (vm, stream) = makeModel()
        await send("同一个", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("在跑"))
        await waitUntil("the frame") { vm.transcript.segments.count == 1 }

        vm.bind(conversation)

        XCTAssertFalse(stream.latest.isTerminated, "a re-appear is not a switch")
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["在跑"])
    }

    func testLeavingTheScreenAbortsTheRead() async {
        let (vm, stream) = makeModel()
        await send("要走了", on: vm, stream)

        vm.detach()

        await waitUntil("the read to close") { stream.latest.isTerminated }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .interrupted)
    }

    // MARK: - failure

    func testAnErrorFrameKeepsTheTextAndNamesTheFailure() async throws {
        let (vm, stream) = makeModel()
        await send("出错", on: vm, stream)
        try stream.latest.feed(
            ChatFrames.text("写到一半"),
            ChatFrames.failure(code: "AGENT_MODEL_NOT_CONFIGURED", message: "未配置模型")
        )

        await waitUntil("the notice") { vm.stopNotice != nil }
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["写到一半"])
        XCTAssertEqual(vm.stopNotice, .failed(text: hx("chat.error.occurred", "未配置模型")))
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .failed(
            code: "AGENT_MODEL_NOT_CONFIGURED",
            message: "未配置模型"
        ))
        XCTAssertFalse(vm.isStreaming)
    }

    func testAnErrorFrameWithNoMessageFallsBackToItsCode() async throws {
        let (vm, stream) = makeModel()
        await send("出错", on: vm, stream)
        try stream.latest.feed(ChatFrames.failure(code: "RESOURCE_LOCKED", message: ""))

        await waitUntil("the notice") { vm.stopNotice != nil }
        XCTAssertEqual(vm.stopNotice, .failed(text: hx("chat.error.occurred", "RESOURCE_LOCKED")))
    }

    func testATransportFailureKeepsThePartialAnswer() async throws {
        let (vm, stream) = makeModel()
        await send("断了", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the frame") { vm.transcript.segments.count == 1 }

        stream.latest.fail(APIError.timeout)

        await waitUntil("the notice") { vm.stopNotice != nil }
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["半句"])
        XCTAssertEqual(vm.stopNotice, .failed(text: hx("error.timeout")))
        XCTAssertFalse(vm.isStreaming)
    }

    func testAStreamThatNeverStartedIsReportedOnce() async {
        let stream = ScriptedChatStream()
        stream.errorToThrow = APIError.offline
        let vm = ChatViewModel(streaming: stream, conversation: conversation)

        XCTAssertTrue(vm.send("发一条"))
        await waitUntil("the failure") { vm.stopNotice != nil }

        XCTAssertEqual(vm.stopNotice, .failed(text: hx("error.offline")))
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .interrupted)
        XCTAssertTrue(vm.send("再试一次"), "a turn that never started must not lock the input")
        vm.detach()
    }

    func testACloseWithNothingOnScreenSaysConnectionLost() async {
        let (vm, stream) = makeModel()
        await send("没人回答", on: vm, stream)

        stream.latest.close()

        await waitUntil("the notice") { vm.stopNotice != nil }
        XCTAssertEqual(vm.stopNotice, .disconnected)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .interrupted)
    }

    /// A half answer that simply stopped is the console's silent case: the text stays, and nothing claims
    /// the connection died when the user can see it did not (`ChatWindow.tsx:2361-2366`).
    func testACloseAfterContentStaysQuiet() async throws {
        let (vm, stream) = makeModel()
        await send("断在中间", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the frame") { vm.transcript.segments.count == 1 }

        stream.latest.close()

        await waitUntil("the read to finish") { !vm.isStreaming }
        XCTAssertNil(vm.stopNotice)
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["半句"])
    }

    func testAKeepAliveHoldsTheTurnOpen() async throws {
        let (vm, stream) = makeModel()
        await send("等着", on: vm, stream)
        try stream.latest.feed(
            ChatFrames.keepAlive,
            ChatFrames.memberKeepAlive(run: "run-3"),
            ChatFrames.text("在跑")
        )

        await waitUntil("the delta behind the pings") { vm.transcript.segments.count == 1 }
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["在跑"], "a ping is not content")
        XCTAssertTrue(vm.isStreaming)
        XCTAssertFalse(vm.transcript.isTerminated)
    }

    // MARK: - scroll

    func testTheThresholdIsTheDistanceThatStillCountsAsFollowing() {
        let (vm, _) = makeModel()
        XCTAssertTrue(vm.isAnchoredToBottom)

        vm.didScroll(distanceFromBottom: ChatViewModel.nearBottomThreshold)
        XCTAssertTrue(vm.isAnchoredToBottom)

        vm.didScroll(distanceFromBottom: ChatViewModel.nearBottomThreshold + 1)
        XCTAssertFalse(vm.isAnchoredToBottom)
    }

    func testFramesPullTheViewOnlyWhileItIsFollowing() async throws {
        let (vm, stream) = makeModel()
        await send("滚动", on: vm, stream)
        let before = vm.scrollToBottomID

        try stream.latest.feed(ChatFrames.text("一"))
        await waitUntil("the first pull") { vm.scrollToBottomID > before }
        let following = vm.scrollToBottomID

        vm.didScroll(distanceFromBottom: 400)
        XCTAssertFalse(vm.isAnchoredToBottom)

        try stream.latest.feed(ChatFrames.text("二"))
        await waitUntil("the second delta") { vm.transcript.segments.first?.text == "一二" }
        XCTAssertEqual(vm.scrollToBottomID, following, "a stream that scrolls the view under a reading user is a bug")
    }

    func testComingBackDownReArmsTheFollow() async throws {
        let (vm, stream) = makeModel()
        await send("回到底部", on: vm, stream)
        vm.didScroll(distanceFromBottom: 400)

        vm.jumpToBottom()
        XCTAssertTrue(vm.isAnchoredToBottom)
        let after = vm.scrollToBottomID

        try stream.latest.feed(ChatFrames.text("新内容"))
        await waitUntil("the pull") { vm.scrollToBottomID > after }
    }

    func testANewTurnStartsAtTheTailEvenAfterAReadUp() async {
        let (vm, stream) = makeModel()
        await send("第一条", on: vm, stream)
        vm.didScroll(distanceFromBottom: 400)
        stream.latest.close()
        await waitUntil("the read to finish") { !vm.isStreaming }

        await send("第二条", on: vm, stream)
        XCTAssertTrue(vm.isAnchoredToBottom, "the message the user just sent is what they want to see")
    }
}
