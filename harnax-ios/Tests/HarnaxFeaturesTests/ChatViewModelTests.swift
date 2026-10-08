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
        commands: (any AgentCommanding)? = nil,
        config: (any SessionConfiguring)? = nil,
        workspace: (any SessionWorkspaceReading)? = nil,
        contextUsage: (any ContextUsageReading)? = nil,
        conversation: ChatConversation? = nil
    ) -> (ChatViewModel, ScriptedChatStream) {
        let stream = ScriptedChatStream()
        let vm = ChatViewModel(
            streaming: stream,
            commands: commands,
            config: config,
            workspace: workspace,
            contextUsage: contextUsage,
            conversation: conversation ?? self.conversation
        )
        return (vm, stream)
    }

    /// A conversation row with only the columns the composer reads. Absent flags stay absent, which is how a
    /// model that never declared a capability arrives.
    private func configRow(
        think: Int? = nil,
        search: Int? = nil,
        plan: Int? = nil,
        reasoning: Int? = nil,
        thinkingMode: Int? = nil,
        internet: Int? = nil,
        vision: Int? = nil,
        permission: String? = nil
    ) -> SessionSummary {
        SessionSummary(
            modelSupportReasoning: reasoning,
            modelThinkingMode: thinkingMode,
            modelSupportInternet: internet,
            modelSupportVision: vision,
            enableThink: think,
            enableSearch: search,
            enablePlan: plan,
            permissionMode: permission
        )
    }

    private func picture(_ byte: UInt8 = 0x89) -> String {
        ChatImageData.dataURL(mime: "image/png", payload: Data([byte, 0x50, 0x4E, 0x47]))
    }

    /// The stream is hand-driven, so the only way to know a frame landed is to let the reader run.
    private func waitUntil(
        _ what: String,
        file: StaticString = #filePath,
        line: UInt = #line,
        check: @MainActor () -> Bool
    ) async {
        for _ in 0..<400 {
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
        XCTAssertNil(vm.transcript.segments.dropFirst().first?.tool?.result)
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
        XCTAssertEqual(vm.transcript.segments.dropFirst().first?.tool?.interrupted, true)
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

    // MARK: - a turn that never landed gives the composer back

    /// The send took the words out of the box before the request went out, so a read that died without a
    /// single frame leaves the user with nothing to retry. The failed turn and its notice stay exactly as
    /// they are — the bubble is the record that this was attempted.
    func testATurnThatNeverLandedPutsTheWordsBackInTheBox() async {
        let stream = ScriptedChatStream()
        stream.errorToThrow = APIError.timeout
        let vm = ChatViewModel(streaming: stream, conversation: conversation)
        vm.draft = "把这段翻成英文"

        vm.send()
        await waitUntil("the failure") { vm.stopNotice != nil }

        XCTAssertEqual(vm.draft, "把这段翻成英文", "the box gets back what the dead turn consumed")
        XCTAssertEqual(vm.stopNotice, .failed(text: hx("error.timeout")))
        XCTAssertEqual(vm.transcript.turns.map(\.role), [.user, .assistant], "the attempt stays on screen")
        XCTAssertTrue(vm.canSend, "so the retry is one tap away")
        vm.detach()
    }

    /// Pictures are the same loss as the text: base64 the user re-picks from the library costs them a sheet.
    func testATurnThatNeverLandedBringsThePickedPicturesBackToo() async {
        let stream = ScriptedChatStream()
        stream.errorToThrow = APIError.offline
        let vm = ChatViewModel(streaming: stream, conversation: conversation)
        vm.draft = "看这张图"
        vm.addImages([picture(), picture(0xFF)])

        vm.send()
        await waitUntil("the failure") { vm.stopNotice != nil }

        XCTAssertFalse(vm.images.isEmpty, "the strip comes back with the turn that died")
        XCTAssertEqual(vm.images.count, 2)
        XCTAssertEqual(vm.draft, "看这张图")
        vm.detach()
    }

    /// Once a frame has arrived the router definitely has the message, so putting it back in the box would
    /// invite a second run of the same turn.
    func testAnAnswerThatAlreadyGotAFrameKeepsTheBoxEmpty() async throws {
        let (vm, stream) = makeModel()
        vm.draft = "说一句"
        vm.send()
        await waitUntil("the stream") { !stream.ports.isEmpty }
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the frame") { vm.transcript.segments.count == 1 }

        stream.latest.fail(APIError.timeout)
        await waitUntil("the notice") { vm.stopNotice != nil }

        XCTAssertEqual(vm.draft, "", "the message reached the run, so it stays out of the box")
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["半句"])
    }

    /// A stream the server accepted and then closed without a frame is the disconnect case, not a request
    /// that failed to go out: the turn is already on the router's side.
    func testAStreamThatClosedWithoutAFrameKeepsTheBoxEmpty() async {
        let (vm, stream) = makeModel()
        vm.draft = "没人回答的那句"
        vm.send()
        await waitUntil("the stream") { !stream.ports.isEmpty }

        stream.latest.close()
        await waitUntil("the notice") { vm.stopNotice != nil }

        XCTAssertEqual(vm.stopNotice, .disconnected)
        XCTAssertEqual(vm.draft, "", "the request landed, so a refill would only cause a duplicate run")
    }

    /// Stopping is the user letting the turn go after the server took it; nothing comes back.
    func testAStoppedTurnDoesNotHandTheMessageBack() async {
        let (vm, stream) = makeModel()
        vm.draft = "长回答的一条"
        vm.send()
        await waitUntil("the stream") { !stream.ports.isEmpty }

        vm.stop()
        await waitUntil("the read to let go") { !vm.isStreaming }

        XCTAssertEqual(vm.draft, "")
        XCTAssertTrue(vm.images.isEmpty)
    }

    /// The failure may arrive while the user is already typing the next thing; their new words win, and the
    /// dead turn's text is not poured on top of them.
    func testABoxTheUserAlreadyRefilledKeepsTheirNewWords() async {
        let (vm, stream) = makeModel()
        vm.draft = "第一条"
        vm.send()
        await waitUntil("the stream") { !stream.ports.isEmpty }

        stream.latest.fail(APIError.offline)
        vm.draft = "第二条已经在打"
        await waitUntil("the notice") { vm.stopNotice != nil }

        XCTAssertEqual(vm.draft, "第二条已经在打", "a recovery never overwrites what the user typed since")
    }

    /// A command is the other channel, and the recovery belongs to the stream leg only: the line reaches the
    /// server even when the answer comes back as a failure, and the console clears the box on the send
    /// (`ChatWindow.tsx:986-1032`).
    func testACommandTheServerNeverAnsweredDoesNotRefillTheBox() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .failure(.offline)
        let (vm, stream) = makeModel(commands: commands)
        vm.draft = "/compact"

        vm.send()
        await waitUntil("the reply") { vm.transcript.turns.count == 2 }

        XCTAssertEqual(vm.draft, "")
        XCTAssertTrue(stream.chatRequests.isEmpty, "a command never opened a stream to recover from")
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

    /// A toolbar chip is not a second send button. The console leaves both disabled for the length of a run
    /// because the backend refuses a second command on a busy session (`ChatWindow.tsx:3679-3686`); here a
    /// chip that got through would have replaced the read's task and orphaned the socket behind it.
    func testAChipCannotTakeOverALiveRead() async throws {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)
        await send("先翻译", on: vm, stream)

        vm.requestClear()
        vm.requestStopSandbox()
        vm.requestCompact()
        vm.confirmPendingCommand()

        XCTAssertEqual(stream.ports.count, 1, "a chip must not open a second read")
        XCTAssertTrue(commands.requests.isEmpty, "…or fire a command at a busy session")
        XCTAssertTrue(vm.isStreaming)

        try stream.latest.feed(ChatFrames.text("还在跑"))
        await waitUntil("the frame behind the chips") { vm.transcript.segments.count == 1 }
        XCTAssertEqual(vm.transcript.segments.map(\.text), ["还在跑"])
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

    // MARK: - a typed command

    /// A command line is its own bubble and a `POST /command`: no stream is opened, so the two channels cannot
    /// be confused by a later frame (`ChatWindow.tsx:986-1032`).
    func testATypedCommandGoesToTheCommandChannelAndNotToTheStream() async {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)

        XCTAssertTrue(vm.send("/enable thinking"))
        await waitUntil("the command") { !commands.requests.isEmpty }

        XCTAssertTrue(stream.chatRequests.isEmpty, "a command has no stream to read")
        XCTAssertEqual(commands.requests.first?.sessionId, "s-1")
        XCTAssertEqual(commands.requests.first?.command, .enable)
        XCTAssertEqual(commands.requests.first?.args, "thinking")
    }

    func testATypedCommandDrawsTheLineAsItWasTyped() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true, message: "已中断当前执行"))
        let (vm, _) = makeModel(commands: commands)

        vm.send("/stop 现在")
        await waitUntil("the reply bubble") { vm.transcript.turns.count == 2 }

        XCTAssertEqual(vm.transcript.turns.first?.role, .user)
        XCTAssertEqual(vm.transcript.turns.first?.segments.first?.text, "/stop 现在")
        XCTAssertEqual(vm.transcript.turns.last?.role, .assistant)
    }

    func testTheReplyIsOneAssistantBubbleFromTheServersOwnWords() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true, message: "上下文已压缩"))
        let (vm, _) = makeModel(commands: commands)
        vm.draft = "/compact"

        vm.send()
        await waitUntil("the reply") { vm.transcript.turns.count == 2 }

        let reply = vm.transcript.turns.last
        XCTAssertEqual(reply?.segments.count, 1, "one text segment, not a stream folded into pieces (`:1012`)")
        XCTAssertEqual(reply?.segments.first?.text, "上下文已压缩")
        XCTAssertEqual(vm.draft, "", "a message that left cannot still be in the box")
        XCTAssertFalse(vm.isStreaming)
    }

    func testACommandThatSaidNothingAnswersWithDone() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true))
        let (vm, _) = makeModel(commands: commands)

        vm.send("/approve yes")
        await waitUntil("the reply") { vm.transcript.turns.count == 2 }

        XCTAssertEqual(vm.transcript.turns.last?.segments.first?.text, hx("chat.command.done"))
    }

    /// A command the server refused answers with its own words, which is the third leg of the console's reply
    /// chain (`harnax-webui/src/pages/session/components/ChatWindow.tsx:1023`) and the only useful thing a
    /// bubble can carry: a compaction refusal says why (a member's child session, a task session, a call
    /// already running).
    func testACommandRefusedByTheServerAnswersWithItsOwnWords() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .failure(.business(code: 500, message: "Compact not yet implemented"))
        let (vm, _) = makeModel(commands: commands)

        vm.send("/compact")
        await waitUntil("the reply") { vm.transcript.turns.count == 2 }

        XCTAssertEqual(
            vm.transcript.turns.last?.segments.first?.text,
            ChatViewModel.commandSentence(for: commands.reply)
        )
        XCTAssertFalse(vm.isStreaming)
    }

    func testAnUnrecognisedKeywordIsSentAsAnOrdinaryMessage() async {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)

        XCTAssertTrue(vm.send("/hello there"))
        await waitUntil("the stream") { !stream.chatRequests.isEmpty }

        XCTAssertEqual(stream.chatRequests.first?.message, "/hello there")
        XCTAssertTrue(commands.requests.isEmpty)
    }

    func testACommandKeepsThePicturesInTheStrip() async {
        let commands = ScriptedAgentCommands()
        let (vm, _) = makeModel(commands: commands)
        vm.addImages([picture()])
        vm.draft = "/interrupt"

        vm.send()
        XCTAssertEqual(vm.images.count, 1, "the command leg never reads imageUrls, so the picture is still waiting")
        XCTAssertEqual(vm.draft, "")
    }

    func testACommandInFlightParksTheComposerTheSameWayAStreamDoes() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true, message: "稍等"))
        let (vm, stream) = makeModel(commands: commands)
        vm.addImages([picture()])

        XCTAssertTrue(vm.send("/interrupt"))
        XCTAssertTrue(vm.isStreaming)
        XCTAssertFalse(vm.canSend)
        XCTAssertFalse(vm.send("另一条"))
        XCTAssertTrue(stream.chatRequests.isEmpty)
        XCTAssertEqual(vm.images.count, 1, "the refused send did not consume the strip")

        await waitUntil("the command to land") { !commands.requests.isEmpty }
    }

    // MARK: - the two commands that ask first

    func testClearWaitsForAConfirmationAndSendsNothingBeforeIt() async {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)
        await send("一句旧话", on: vm, stream)
        stream.latest.close()
        await waitUntil("the read to finish") { !vm.isStreaming }

        vm.send("/clear")
        XCTAssertEqual(vm.pendingCommand?.kind, .clear)
        XCTAssertTrue(commands.requests.isEmpty)
        XCTAssertEqual(vm.transcript.turns.count, 2, "the typed line only becomes a bubble once it is confirmed")

        vm.confirmPendingCommand()
        await waitUntil("the confirmed command") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.first?.command, .clear)
        XCTAssertNil(vm.pendingCommand)
    }

    func testACancelledClearSendsNothingAndAddsNoBubble() async {
        let commands = ScriptedAgentCommands()
        let (vm, _) = makeModel(commands: commands)

        vm.send("/clear")
        let before = vm.transcript.turns.count
        vm.cancelPendingCommand()

        XCTAssertNil(vm.pendingCommand)
        XCTAssertEqual(vm.transcript.turns.count, before)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    /// The toolbar's Clear has no typed line, so its answer is a banner and its success really does empty the
    /// transcript (`ChatWindow.tsx:2678-2708`).
    func testTheToolbarClearEmptiesTheTranscriptOnSuccess() async throws {
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(commands: commands)
        await send("要清掉的话", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("答"), ChatFrames.end())
        await waitUntil("the finished turn") { !vm.isStreaming }
        XCTAssertFalse(vm.transcript.turns.isEmpty)

        vm.requestClear()
        vm.confirmPendingCommand()
        await waitUntil("the cleared transcript") { vm.transcript.turns.isEmpty }
        XCTAssertEqual(vm.composerNotice?.tone, .info)
    }

    func testStopSandboxRefusesWhenTheSandboxIsNotRunning() async {
        let commands = ScriptedAgentCommands()
        let sandbox = ScriptedSandbox()
        sandbox.status = .idle
        let (vm, _) = makeModel(commands: commands, workspace: sandbox)

        vm.send("/stop-sandbox")
        await waitUntil("the refusal") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertNil(vm.pendingCommand)
        XCTAssertTrue(commands.requests.isEmpty)
        XCTAssertEqual(sandbox.statusRequested, ["s-1"])
    }

    func testStopSandboxOffersTheConfirmationOnlyWhileTheSandboxIsUp() async {
        let commands = ScriptedAgentCommands()
        let sandbox = ScriptedSandbox()
        sandbox.status = .running
        let (vm, _) = makeModel(commands: commands, workspace: sandbox)

        vm.send("/stop-sandbox")
        await waitUntil("the confirmation") { vm.pendingCommand != nil }
        XCTAssertEqual(vm.pendingCommand?.kind, .stopSandbox)
        XCTAssertTrue(commands.requests.isEmpty)

        vm.confirmPendingCommand()
        await waitUntil("the confirmed command") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.first?.command, .stopSandbox)
    }

    /// A status read that failed is not the same news as a sandbox that is down: one is an error, the other a
    /// warning, and neither may offer a confirmation (`ChatWindow.tsx:3639-3664`).
    func testAFailedStatusReadSaysTheReadFailedAndAsksNothing() async {
        let commands = ScriptedAgentCommands()
        let sandbox = ScriptedSandbox()
        sandbox.statusFails = true
        let (vm, _) = makeModel(commands: commands, workspace: sandbox)

        vm.requestStopSandbox()
        await waitUntil("the error") { vm.composerNotice?.tone == .error }

        XCTAssertNil(vm.pendingCommand)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    func testStopSandboxWithoutAWorkspaceReadSaysNothingIsWired() async {
        let commands = ScriptedAgentCommands()
        let (vm, _) = makeModel(commands: commands)

        vm.requestStopSandbox()
        await waitUntil("the notice") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .error)
        XCTAssertNil(vm.pendingCommand)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    // MARK: - the sandbox workspace entry

    func testTheWorkspaceEntryFollowsTheStatusAnswer() async {
        let sandbox = ScriptedSandbox()
        let (vm, _) = makeModel(workspace: sandbox)
        XCTAssertFalse(vm.sandboxIsRunning, "nothing has been asked yet")

        sandbox.status = .running
        await vm.refreshSandboxStatus()
        XCTAssertTrue(vm.sandboxIsRunning)

        sandbox.status = .idle
        await vm.refreshSandboxStatus()
        XCTAssertFalse(vm.sandboxIsRunning)

        // A read that failed is not news that a sandbox is up, and it is not allowed to say anything either:
        // the entry going quiet is a state, not a refusal the user has to be told about.
        sandbox.status = .running
        sandbox.statusFails = true
        await vm.refreshSandboxStatus()
        XCTAssertFalse(vm.sandboxIsRunning)
        XCTAssertNil(vm.composerNotice)
        XCTAssertEqual(sandbox.statusRequested, ["s-1", "s-1", "s-1"])
    }

    /// The answer is addressed to a session, and the entry belongs to the conversation on screen: only the id
    /// tells the two apart (`ChatTranscript`'s folding gets the same treatment on a switch).
    func testAnAnswerForTheConversationJustLeftDoesNotLightThisOnesEntry() async {
        let sandbox = ScriptedSandbox()
        sandbox.gateStatus = true
        sandbox.status = .running
        let (vm, _) = makeModel(workspace: sandbox)

        let read = Task { await vm.refreshSandboxStatus() }
        await waitUntil("the parked status read") { sandbox.statusRequested == ["s-1"] }

        vm.bind(ChatConversation(id: "s-2", title: "另一组"))
        sandbox.releaseStatuses()
        // Waiting the task out is what makes this a race the guard has to win rather than a leg that never
        // landed: `running` is on its way back for the conversation that was just left.
        await read.value

        XCTAssertFalse(vm.sandboxIsRunning, "the sandbox belongs to the conversation that was left")
        XCTAssertEqual(sandbox.statusRequested, ["s-1"], "a switch is not itself a read")
    }

    func testTheDrawerPanelAndAnOpenDrawerFollowTheConversation() {
        let sandbox = ScriptedSandbox()
        let (vm, _) = makeModel(workspace: sandbox)
        let first = vm.workspacePanel
        XCTAssertNotNil(first, "a host that wired the workspace read gets a panel")

        vm.isWorkspacePresented = true
        vm.bind(ChatConversation(id: "s-2", title: "另一组"))

        XCTAssertNotNil(vm.workspacePanel)
        XCTAssertFalse(vm.workspacePanel === first, "the drawer lists the conversation now on screen")
        XCTAssertFalse(vm.isWorkspacePresented, "an open drawer goes with the conversation it was opened for")
    }

    func testAHostWithoutTheWorkspaceReadHasNoPanelAndAsksNothing() async {
        let (vm, _) = makeModel()
        XCTAssertNil(vm.workspacePanel)
        await vm.refreshSandboxStatus()
        XCTAssertFalse(vm.sandboxIsRunning)
    }

    /// The container is not there when the send goes out — the run's first tool call makes it — so a turn's last
    /// word has to ask again or the entry never appears without leaving the screen and coming back.
    func testTheTurnsLastWordAsksForTheSandboxAgain() async throws {
        let sandbox = ScriptedSandbox()
        sandbox.status = .running
        let (vm, stream) = makeModel(workspace: sandbox)

        await send("跑一下", on: vm, stream)
        XCTAssertTrue(sandbox.statusRequested.isEmpty, "a send asks nothing of its own")

        try stream.latest.feed(ChatFrames.text("好了"), ChatFrames.end())
        await waitUntil("the entry to light after the turn") { vm.sandboxIsRunning }

        XCTAssertEqual(sandbox.statusRequested, ["s-1"])
    }

    // MARK: - the context occupancy reading

    /// The tag is the latest answer and nothing else: the console's `loadContextUsage` overwrites on every
    /// read, including with null, because a session the router cannot see must not keep a number on screen
    /// (`harnax-webui/src/pages/session/index.tsx:142-154`).
    func testTheOccupancyTagFollowsTheRead() async {
        let reader = ScriptedContextUsage()
        let (vm, _) = makeModel(contextUsage: reader)
        XCTAssertNil(vm.contextUsage, "nothing has been asked yet")

        await vm.refreshContextUsage()
        XCTAssertEqual(vm.contextUsage, reader.reading)

        // The `ResultVo.success(null)` leg — the session was never bound to an instance.
        reader.reading = ContextUsage()
        await vm.refreshContextUsage()
        XCTAssertNil(vm.contextUsage, "a session the router cannot see is not a context that is empty")

        // And the business-error leg (no instance holds this session) takes the tag down with it rather than
        // leaving the previous reading up.
        reader.reading = ContextUsage(
            messageCount: 3,
            estimatedTokens: 100,
            contextWindow: 32_000,
            ratio: 0.2
        )
        reader.fails = true
        await vm.refreshContextUsage()
        XCTAssertNil(vm.contextUsage, "an unreadable answer does not keep the old reading alive")
        XCTAssertNil(vm.composerNotice, "the tag not being there is a state, not a refusal to explain")
        XCTAssertEqual(reader.requested, ["s-1", "s-1", "s-1"])
    }

    /// A switch clears the reading — its denominator is another model's window — and the new conversation is
    /// answered by its own read (`ChatView`'s `.task(id:)`).
    func testAConversationSwitchTakesTheOldReadingWithIt() async {
        let reader = ScriptedContextUsage()
        let (vm, _) = makeModel(contextUsage: reader)
        await vm.refreshContextUsage()
        XCTAssertNotNil(vm.contextUsage)

        vm.bind(ChatConversation(id: "s-2", title: "另一组"))

        XCTAssertNil(vm.contextUsage)
        XCTAssertEqual(reader.requested, ["s-1"], "a switch is not itself a read")
    }

    func testAnAnswerForTheConversationJustLeftDoesNotPutATagHere() async {
        let reader = ScriptedContextUsage()
        reader.gate = true
        let (vm, _) = makeModel(contextUsage: reader)

        let read = Task { await vm.refreshContextUsage() }
        await waitUntil("the parked occupancy read") { reader.requested == ["s-1"] }

        vm.bind(ChatConversation(id: "s-2", title: "另一组"))
        reader.release()
        await read.value

        XCTAssertNil(vm.contextUsage, "the reading belongs to the conversation that was left")
    }

    /// Two re-reads can overlap — a turn's last word and a command's reply each ask on their own — and the
    /// answer that has to stay on screen is the one from the *later* ask. The read overwrites whatever it
    /// brings, so a slow one that started before a compaction would otherwise put the pre-compaction number
    /// back beside a context that has since been folded away.
    func testTheLaterReadKeepsItsNumberWhenTwoOverlap() async {
        let reader = ScriptedContextUsage()
        reader.reading = ContextUsage(estimatedTokens: 8_000, contextWindow: 32_000, ratio: 0.25)
        reader.gate = true
        let (vm, _) = makeModel(contextUsage: reader)

        let older = Task { await vm.refreshContextUsage() }
        await waitUntil("the parked occupancy read") { reader.requested == ["s-1"] }

        reader.gate = false
        reader.reading = ContextUsage(estimatedTokens: 960, contextWindow: 32_000, ratio: 0.03)
        await vm.refreshContextUsage()
        XCTAssertEqual(vm.contextUsage?.ratio, 0.03)

        reader.release()
        await older.value

        XCTAssertEqual(vm.contextUsage?.ratio, 0.03, "the reading from before the newer ask is stale")
    }

    /// The reading's numerator is the last billed call, so it only becomes worth asking again when a turn ends
    /// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:2503-2507`).
    func testTheTurnsLastWordRereadsTheOccupancy() async throws {
        let reader = ScriptedContextUsage()
        let (vm, stream) = makeModel(contextUsage: reader)

        await send("再跑一轮", on: vm, stream)
        XCTAssertTrue(reader.requested.isEmpty, "a send asks nothing of its own")

        try stream.latest.feed(ChatFrames.text("好了"), ChatFrames.end())
        await waitUntil("the reading to land after the turn") { vm.contextUsage != nil }

        XCTAssertEqual(reader.requested, ["s-1"])
    }

    func testAHostWithoutTheOccupancyReadAsksNothing() async {
        let (vm, _) = makeModel()
        await vm.refreshContextUsage()
        XCTAssertNil(vm.contextUsage)
    }

    // MARK: - manual compaction

    func testTheCompactEntrySendsTheCommandAndReportsTheCounts() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true, result: .init(beforeMessages: 40, afterMessages: 12)))
        let reader = ScriptedContextUsage()
        let (vm, _) = makeModel(commands: commands, contextUsage: reader)

        vm.requestCompact()
        await waitUntil("the compaction answer") { vm.composerNotice != nil }

        XCTAssertEqual(commands.requests.count, 1)
        XCTAssertEqual(commands.requests.first?.command, .compact)
        XCTAssertEqual(commands.requests.first?.args, "")
        XCTAssertEqual(vm.composerNotice?.tone, .info)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.context.compact.done", 40, 12))
        XCTAssertNotEqual(
            vm.composerNotice?.text,
            hx("chat.context.compact.done", 12, 40),
            "the counts go in the order the server sent them: before, then after"
        )
        XCTAssertEqual(reader.requested, ["s-1"], "the context the command rewrote is read again")
    }

    /// A too-short session answers `success` with both counts equal, and calling that a completed compaction
    /// would be a false report (`CompactionOutcome`).
    func testACompactionThatRemovedNothingSaysSo() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true, result: .init(beforeMessages: 6, afterMessages: 6)))
        let (vm, _) = makeModel(commands: commands)

        vm.requestCompact()
        await waitUntil("the no-op answer") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .info)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.context.compact.noop"))
    }

    func testACompactionWithoutCountsSaysOnlyThatItRan() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: true))
        let (vm, _) = makeModel(commands: commands)

        vm.requestCompact()
        await waitUntil("the answer") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .info)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.context.compact.donePlain"))
    }

    /// A refusal carries why (a member's child session, a task session, a call already running), which beats a
    /// local「压缩失败」— the console reads `reply?.message` first on that leg alone.
    func testARefusedCompactionWarnsWithTheServersReason() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: false, message: "Task sessions cannot be compacted"))
        let (vm, _) = makeModel(commands: commands)

        vm.requestCompact()
        await waitUntil("the refusal") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, "Task sessions cannot be compacted")
    }

    func testACompactionThatNeverGotThroughSaysWhy() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .failure(.business(code: 500, message: "No context held for session s-1 on this instance"))
        let (vm, _) = makeModel(commands: commands)

        vm.requestCompact()
        await waitUntil("the failed call") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, "No context held for session s-1 on this instance")
    }

    /// The typed form keeps the console's reply chain, whose first leg is the server's own sentence — the four
    /// outcome strings are what the *tapped* entry answers with.
    func testATypedCompactStillBubblesTheServersOwnSentence() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(
            success: true,
            message: "Compacted 40 messages into 12",
            result: .init(beforeMessages: 40, afterMessages: 12)
        ))
        let (vm, _) = makeModel(commands: commands)

        vm.send("/compact")
        await waitUntil("the reply bubble") { vm.transcript.turns.count == 2 }

        XCTAssertEqual(vm.transcript.turns.last?.segments.first?.text, "Compacted 40 messages into 12")
        XCTAssertNil(vm.composerNotice, "a typed line answers in the transcript, not in the banner")
    }

    /// The flag is what the banner reports on. A body that carries neither it nor a refusal is a reply this
    /// side did not read, and 「已压缩上下文」 under it would be a report of a compaction nobody confirmed.
    func testACompactionReplyThatCarriesNoFlagWarnsRatherThanReportingADoneDeal() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply())
        let (vm, _) = makeModel(commands: commands)

        vm.requestCompact()
        await waitUntil("the answer") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.context.compact.failed"))
    }

    /// A stop suppresses the banner, not the state of the server's context: the compaction ran wherever this
    /// side's answer went, so the reading the command rewrote is still taken — the console's re-read sits in a
    /// `finally` (`ChatWindow.tsx:2497-2499`) for the same reason. The composer is already free, because a
    /// stop is what puts `isStreaming` back.
    func testAStoppedCompactionStillRereadsTheContext() async {
        let commands = ScriptedAgentCommands()
        commands.gate = true
        commands.reply = .success(AgentCommandReply(success: true, result: .init(beforeMessages: 40, afterMessages: 12)))
        let reader = ScriptedContextUsage()
        let (vm, _) = makeModel(commands: commands, contextUsage: reader)

        vm.requestCompact()
        await waitUntil("the parked compaction") { commands.requests.contains { $0.command == .compact } }
        vm.stop()
        commands.release()

        await waitUntil("the reading after the stop") { reader.requested == ["s-1"] }
        XCTAssertNil(vm.composerNotice, "the banner is what the stop was for, not the re-read")
    }

    // MARK: - the capability switches

    /// A composer whose gates come from one row, read the way the screen reads it on entry.
    private func composerReady(
        row: SessionSummary,
        commands: ScriptedAgentCommands,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async -> ChatViewModel {
        let config = ScriptedSessionConfig()
        config.row = row
        let (vm, _) = makeModel(commands: commands, config: config)
        await vm.loadComposerConfig()
        XCTAssertEqual(config.requested, ["s-1"], "the row is read for this conversation", file: file, line: line)
        return vm
    }

    func testTheStoredColumnsArriveAsSwitchStates() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(think: 1, search: 1, plan: 1, reasoning: 1), commands: commands)

        XCTAssertTrue(vm.composer.enableThink)
        XCTAssertTrue(vm.composer.enableSearch)
        XCTAssertTrue(vm.composer.enablePlan)
        XCTAssertTrue(commands.requests.isEmpty, "reading the row is not a write")
    }

    /// A model that cannot reason gets a warning and no command, and the switch does not move at all
    /// (`ChatWindow.tsx:3506-3512`).
    func testThinkingRefusesAModelThatCannotReason() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(reasoning: 0), commands: commands)

        vm.toggleThink()
        await waitUntil("the warning") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertFalse(vm.composer.enableThink)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    /// Thinking mode 2 is “required”: the console returns before it computes a new value, so the switch never
    /// moves and no command leaves (`:3512-3517`).
    func testThinkingExplainsAModelThatMustThink() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(reasoning: 1, thinkingMode: 2), commands: commands)
        XCTAssertTrue(vm.composer.enableThink, "a required model reads as on, whatever the column said")

        vm.toggleThink()
        await waitUntil("the explanation") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .info)
        XCTAssertTrue(vm.composer.enableThink)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    func testThinkingFlipsOptimisticallyAndStaysFlippedOnSuccess() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(reasoning: 1), commands: commands)

        vm.toggleThink()
        XCTAssertEqual(vm.composer.enableThink, true, "the switch moves before the server answers")
        await waitUntil("the enable command") { !commands.requests.isEmpty }

        XCTAssertEqual(commands.requests.first?.command, .enable)
        XCTAssertEqual(commands.requests.first?.args, "thinking")
        XCTAssertTrue(vm.composer.enableThink)
        XCTAssertNil(vm.composerNotice)
    }

    func testThinkingRollsBackWhenTheCommandRefuses() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: false, message: "Unknown capability"))
        let vm = await composerReady(row: configRow(reasoning: 1), commands: commands)

        vm.toggleThink()
        await waitUntil("the rollback") { vm.composer.enableThink == false }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, "Unknown capability")
    }

    func testSearchRefusesAModelWithoutInternet() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(internet: 0), commands: commands)

        vm.toggleSearch()
        await waitUntil("the warning") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertFalse(vm.composer.enableSearch)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    func testSearchFlipsAndRollsBack() async {
        let landed = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(internet: 1), commands: landed)
        vm.toggleSearch()
        await waitUntil("the search command") { !landed.requests.isEmpty }
        XCTAssertEqual(landed.requests.first?.command, .enable)
        XCTAssertEqual(landed.requests.first?.args, "search")
        XCTAssertTrue(vm.composer.enableSearch)

        let refused = ScriptedAgentCommands()
        refused.reply = .success(AgentCommandReply(success: false))
        let second = await composerReady(row: configRow(search: 1, internet: 1), commands: refused)
        second.toggleSearch()
        XCTAssertEqual(second.composer.enableSearch, false, "off first, whatever the server turns out to say")
        await waitUntil("the rollback") { second.composer.enableSearch == true }
        XCTAssertEqual(second.composerNotice?.tone, .warning)
    }

    /// Plan is a session column, not a model capability (`:3590-3600`), so no flag can disable it.
    func testPlanHasNoModelGate() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(
            row: configRow(reasoning: 0, internet: 0, vision: 0),
            commands: commands
        )

        vm.togglePlan()
        await waitUntil("the plan command") { !commands.requests.isEmpty }

        XCTAssertEqual(commands.requests.first?.command, .enable)
        XCTAssertEqual(commands.requests.first?.args, "plan")
        XCTAssertTrue(vm.composer.enablePlan)
    }

    func testTheSecondTapSendsTheOppositeVerb() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(think: 1, reasoning: 1), commands: commands)

        vm.toggleThink()
        await waitUntil("the disable") { commands.requests.count == 1 }
        XCTAssertEqual(commands.requests.first?.command, .disable)
        XCTAssertEqual(commands.requests.first?.args, "thinking")

        vm.toggleThink()
        await waitUntil("the enable") { commands.requests.count == 2 }
        XCTAssertEqual(commands.requests.last?.command, .enable)
    }

    // MARK: - the permission picker

    func testThePickerOffersTheFiveModesInTheServersOrder() async {
        let (vm, _) = makeModel()
        XCTAssertEqual(vm.permissionOptions, ChatPermissionMode.allCases)
        XCTAssertEqual(vm.permissionOptions.count, 5)
    }

    func testThePickerStartsWhereTheRowSaid() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(permission: "explore"), commands: commands)
        XCTAssertEqual(vm.composer.permissionMode, .explore)

        vm.selectPermission(.bypass)
        XCTAssertEqual(vm.composer.permissionMode, .bypass, "the label moves before the answer comes back")
        await waitUntil("the permission command") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.first?.command, .permission)
        XCTAssertEqual(
            commands.requests.first?.args, "BYPASS",
            "the wire value, upper case as the server compares it (`DefaultAgentRunner.kt:1017`)"
        )
    }

    func testChoosingTheModeAlreadyOnIsAQuietNoOp() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(permission: "BYPASS"), commands: commands)

        vm.selectPermission(.bypass)
        try? await Task.sleep(nanoseconds: 20_000_000)

        XCTAssertTrue(commands.requests.isEmpty)
        XCTAssertNil(vm.composerNotice)
    }

    func testAPermissionChangeRollsBackWhenTheServerRefuses() async {
        let commands = ScriptedAgentCommands()
        commands.reply = .success(AgentCommandReply(success: false, message: "Invalid permission mode"))
        let vm = await composerReady(row: configRow(permission: "DEFAULT"), commands: commands)

        vm.selectPermission(.acceptEdits)
        XCTAssertEqual(vm.composer.permissionMode, .acceptEdits)

        await waitUntil("the rollback") { vm.composer.permissionMode == .defaultMode }
        XCTAssertEqual(vm.composerNotice?.text, "Invalid permission mode")
    }

    /// A `task-` conversation belongs to the scheduler, and the runtime refuses to move its mode
    /// (`DefaultAgentRunner.kt:1025-1027`), so the change is never offered.
    func testATaskConversationIsNotOfferedAPermissionChange() async {
        let commands = ScriptedAgentCommands()
        let config = ScriptedSessionConfig()
        let (vm, _) = makeModel(
            commands: commands,
            config: config,
            conversation: ChatConversation(id: "task-9", title: "夜间整理")
        )
        XCTAssertTrue(vm.isTaskConversation)
        XCTAssertFalse(vm.canPickPermission)

        vm.selectPermission(.bypass)
        await waitUntil("the refusal") { vm.composerNotice != nil }

        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composer.permissionMode, .defaultMode)
        XCTAssertTrue(commands.requests.isEmpty)
    }

    // MARK: - pictures

    func testAPictureCanBeTheOnlyThingSent() async {
        let (vm, stream) = makeModel()
        vm.addImages([picture()])
        XCTAssertTrue(vm.canSend, "an empty box with a picture in it is still a message (`:3684`)")

        vm.send()
        await waitUntil("the request") { !stream.chatRequests.isEmpty }

        XCTAssertEqual(stream.chatRequests.first?.imageUrls.count, 1)
        XCTAssertEqual(stream.chatRequests.first?.message, "")
        XCTAssertTrue(vm.images.isEmpty, "the strip empties once the message has gone")
    }

    func testTextAndAPictureGoOutTogetherAndTheUserBubbleKeepsBoth() async {
        let (vm, stream) = makeModel()
        vm.draft = "看看这张"
        vm.addImages([picture(), picture(0xFF)])

        vm.send()
        await waitUntil("the request") { !stream.chatRequests.isEmpty }

        XCTAssertEqual(stream.chatRequests.first?.imageUrls.count, 2)
        XCTAssertEqual(vm.transcript.turns.first?.images.count, 2)
        XCTAssertEqual(vm.transcript.turns.first?.segments.first?.text, "看看这张")
    }

    func testAPictureCanBeRemovedOnItsOwn() async {
        let (vm, _) = makeModel()
        vm.addImages([picture(), picture(0xFF)])
        vm.removeImage(at: 0)

        XCTAssertEqual(vm.images.count, 1)
        vm.removeImage(at: 4)
        XCTAssertEqual(vm.images.count, 1, "an index that is not there removes nothing")
    }

    func testAStringThatIsNotAnImageNeverEntersTheStrip() async {
        let (vm, _) = makeModel()
        vm.addImages(["https://cdn/photo.png", picture()])

        XCTAssertEqual(vm.images.count, 1, "the runtime would open that as a sandbox path")
        XCTAssertEqual(vm.composerNotice?.tone, .warning)
    }

    /// The console puts no number on the strip — its picker appends every file it is handed
    /// (`ChatWindow.tsx:2442-2468`, the append itself at `:2458`) off a bare `multiple` input (`:3692-3699`) —
    /// so the limit is the server's. The edge that answers for that console takes a request body of 1 MB and
    /// no more: the streaming `location` sets no `client_max_body_size`
    /// (`harnax-deploy/nginx.conf:72-101`), and the same file spells out what that default means
    /// (`:196-197`: "nginx defaults to 1 MB and answers with a bare 413 page"). Base64 pictures go into that
    /// body whole, so a picture over the ceiling has to be refused here rather than die as an unreadable 413
    /// two seconds later.
    func testAPictureTooBigForTheStreamingRouteNeverEntersTheStrip() {
        let (vm, _) = makeModel()
        let oversized = ChatImageData.dataURL(mime: "image/png", payload: Data(count: 2_000_000))

        vm.addImages([oversized])

        XCTAssertTrue(vm.images.isEmpty, "the turn would be rejected before the router ever saw it")
        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.image.overLimit"))
        XCTAssertFalse(vm.canSend, "and with nothing attached there is nothing to send")
    }

    /// How many pictures there are is not the constraint, and no count cap has anything behind it: eight small
    /// ones ride out together, exactly as the console's `multiple` input lets eight be picked.
    func testNoMatterHowManyTheStripHoldsSmallPicturesAreAllAccepted() {
        let (vm, _) = makeModel()

        vm.addImages((0..<8).map { picture(UInt8($0)) })

        XCTAssertEqual(vm.images.count, 8, "the limit is bytes, not a number this side invented")
        XCTAssertNil(vm.composerNotice)
    }

    /// The ceiling sits on the attachment leg as a whole, so it is the picture that tips it over which gets
    /// refused: two 400 KB originals base64 to just past 1 MB together.
    func testThePictureThatTipsTheBodyOverItsCeilingIsTheOneRefused() {
        let (vm, _) = makeModel()
        let chunk = ChatImageData.dataURL(mime: "image/png", payload: Data(count: 400_000))
        vm.addImages([chunk])
        XCTAssertEqual(vm.images.count, 1, "one of them fits on its own")

        vm.addImages([chunk])

        XCTAssertEqual(vm.images.count, 1, "the second would take the body past the edge")
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.image.overLimit"))
    }

    /// Removing a picture gives its room back, which is the only remedy the notice can point a user at.
    func testRemovingAPictureGivesItsRoomBackToTheStrip() {
        let (vm, _) = makeModel()
        let chunk = ChatImageData.dataURL(mime: "image/png", payload: Data(count: 400_000))
        vm.addImages([chunk, chunk])
        XCTAssertEqual(vm.images.count, 1, "the batch's second picture had no room left")

        vm.removeImage(at: 0)
        vm.addImages([chunk])
        XCTAssertEqual(vm.images.count, 1, "the room it held is what the new one went into")

        vm.addImages([chunk])
        XCTAssertEqual(vm.images.count, 1, "and with two in there again the edge is reached once more")
    }

    /// A batch that only partly overshoots keeps what fits: one picture the camera shot too large does not
    /// take the rest of the roll away with it, and the turn carries exactly the strip that survived.
    func testAnOversizedPictureInABatchDoesNotTakeTheOnesThatFitAway() async {
        let (vm, stream) = makeModel()
        let oversized = ChatImageData.dataURL(mime: "image/png", payload: Data(count: 2_000_000))

        vm.addImages([oversized, picture(), picture(0xFF)])

        XCTAssertEqual(vm.images.count, 2, "the two that fit stay")
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.image.overLimit"))

        vm.send()
        await waitUntil("the request") { !stream.chatRequests.isEmpty }
        XCTAssertEqual(stream.chatRequests.first?.imageUrls.count, 2, "and they are what the turn carries")
    }

    func testAModelWithoutVisionCannotPickButTheChipStillAnswers() async {
        let commands = ScriptedAgentCommands()
        let vm = await composerReady(row: configRow(vision: 0), commands: commands)
        XCTAssertFalse(vm.canPickImages)
        XCTAssertFalse(vm.requestImages(), "no sheet for a model that could not read the picture")
        XCTAssertEqual(vm.composerNotice?.tone, .warning)

        vm.addImages([picture()])
        XCTAssertTrue(vm.images.isEmpty)
        XCTAssertTrue(commands.requests.isEmpty, "a refused picture sends nothing")
    }

    func testAPictureAlreadyInTheStripStillGoesOutWhenVisionWentAway() async {
        let config = ScriptedSessionConfig()
        config.row = configRow(vision: 0)
        let (vm, _) = makeModel(config: config)
        // The strip filled while the defaults were still in charge, then the row came back and said no.
        vm.addImages([picture()])
        await vm.loadComposerConfig()

        XCTAssertFalse(vm.canPickImages, "the picker is what gets blocked")
        XCTAssertFalse(vm.images.isEmpty, "not the picture already waiting")
        XCTAssertTrue(vm.canSend)
    }

    // MARK: - reading and resetting the composer

    func testTheComposerReadsTheConversationRowOnEntry() async {
        let config = ScriptedSessionConfig()
        config.row = configRow(
            think: 1,
            search: 1,
            reasoning: 1,
            internet: 1,
            vision: 1,
            permission: "ACCEPT_EDITS"
        )
        let (vm, _) = makeModel(config: config)

        await vm.loadComposerConfig()

        XCTAssertEqual(config.requested, ["s-1"])
        XCTAssertTrue(vm.composer.enableThink)
        XCTAssertTrue(vm.composer.enableSearch)
        XCTAssertTrue(vm.composer.canToggleSearch)
        XCTAssertEqual(vm.composer.permissionMode, .acceptEdits)
        XCTAssertTrue(vm.canPickImages)
        XCTAssertTrue(config.writes.isEmpty, "a switch moves through /command, never PUT /sessions/{id}/config")
    }

    func testAConfigReadThatFailsSaysSoInsteadOfSilentlyUsingDefaults() async {
        let config = ScriptedSessionConfig()
        config.error = .offline
        let (vm, _) = makeModel(config: config)

        await vm.loadComposerConfig()

        XCTAssertEqual(vm.composerNotice?.tone, .error)
        XCTAssertTrue(vm.composer.canToggleThink, "the shipped defaults stay in charge meanwhile")
    }

    func testSwitchingConversationResetsTheComposer() async {
        let config = ScriptedSessionConfig()
        config.row = configRow(think: 1, reasoning: 1, permission: "BYPASS")
        let (vm, _) = makeModel(config: config)
        await vm.loadComposerConfig()
        vm.addImages([picture()])
        vm.send("/clear")
        XCTAssertNotNil(vm.pendingCommand)

        vm.bind(ChatConversation(id: "s-2", title: "另一个"))

        XCTAssertFalse(vm.composer.enableThink, "the switches are per-conversation columns (`:683-705`)")
        XCTAssertEqual(vm.composer.permissionMode, .defaultMode)
        XCTAssertTrue(vm.images.isEmpty)
        XCTAssertNil(vm.composerNotice)
        XCTAssertNil(vm.pendingCommand)
    }
}
