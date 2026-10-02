import Combine
import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// Gate for `DESIGN.md` §10 性能's 「流式增量合并节流到一帧一次」 — a streamed run updates the screen once per
/// frame, not once per token.
///
/// The merge itself predates this file (`ChatTranscript` already folds consecutive deltas into one growing
/// block), so what has to be proven here is the *publish*: that words which arrive inside one frame window
/// reach the view as a single invalidation, and that nothing the user could notice waits for a tick anyway.
/// The window's own rules are asserted straight on `ChatStreamCoalescer`, which holds no clock and is
/// therefore entirely decidable without waiting; the screen's rules are asserted through a view model whose
/// clock is this file's ``HandDrivenFrameClock``, so a test says when a frame ends rather than sleeping for
/// one-sixtieth of a second and hoping.
///
/// The two halves the requirement is really about both appear below: the first word of a run costs no
/// latency at all, and a frame that changes the screen's state — an end, a failure, a tool call, a
/// confirmation — is never queued behind words that are waiting.
@MainActor
final class ChatStreamPublishingTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "翻译一组")

    private func makeModel() -> (ChatViewModel, ScriptedChatStream, HandDrivenFrameClock) {
        let stream = ScriptedChatStream()
        let clock = HandDrivenFrameClock()
        let vm = ChatViewModel(
            streaming: stream,
            background: ChatBackgroundAssertion(),
            frameClock: clock,
            conversation: conversation
        )
        return (vm, stream, clock)
    }

    private func event(_ payload: String) throws -> ChatEvent {
        try ChatEvent.decode(payload)
    }

    /// The stream is hand-driven, so the only way to know a frame reached the reader is to let it run.
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

    private func send(_ message: String, on vm: ChatViewModel, _ stream: ScriptedChatStream) async {
        let before = stream.ports.count
        XCTAssertTrue(vm.send(message))
        await waitUntil("a stream for “\(message)”") { stream.ports.count > before }
    }

    // MARK: - the window's rules

    func testTheFirstWordOfAWindowGoesToTheScreenAtOnce() throws {
        var window = ChatStreamCoalescer()
        let first = try event(ChatFrames.text("开"))

        XCTAssertEqual(window.accept(first), .publish([first]), "the first token of a run costs no latency")
        XCTAssertTrue(window.isWindowOpen)
        XCTAssertTrue(window.held.isEmpty)
    }

    func testTheWordsBehindItWaitForTheTick() throws {
        var window = ChatStreamCoalescer()
        let first = try event(ChatFrames.text("开"))
        let second = try event(ChatFrames.text("口"))
        let third = try event(ChatFrames.text("始"))

        _ = window.accept(first)
        XCTAssertEqual(window.accept(second), .hold)
        XCTAssertEqual(window.accept(third), .hold)
        XCTAssertEqual(window.held, [second, third], "and they wait in the order they arrived")
    }

    func testATickHandsTheWholeWindowOverAsOnePublish() throws {
        var window = ChatStreamCoalescer()
        let words = try ["开", "口", "始"].map { try event(ChatFrames.text($0)) }
        for word in words { _ = window.accept(word) }

        XCTAssertEqual(window.tick(), .publish(Array(words[1...])), "one update, three words, arrival order")
        XCTAssertTrue(window.isWindowOpen, "and a run still talking re-arms immediately, not once per two frames")
        XCTAssertTrue(window.held.isEmpty)
    }

    func testATickThatHeldNothingEndsTheWindow() throws {
        var window = ChatStreamCoalescer()
        _ = window.accept(try event(ChatFrames.text("只")) )

        XCTAssertEqual(window.tick(), .idle, "a run that said one word and stopped talking stops costing frames")
        XCTAssertFalse(window.isWindowOpen)
        XCTAssertEqual(window.tick(), .idle, "…and a closed window has nothing left to hand over")
    }

    func testAFrameThatChangesTheScreensStateTakesTheQueueWithIt() throws {
        var window = ChatStreamCoalescer()
        let first = try event(ChatFrames.text("半"))
        let held = try event(ChatFrames.text("句"))
        let call = try event(ChatFrames.call(id: "t-1", name: "bash"))
        _ = window.accept(first)
        _ = window.accept(held)

        XCTAssertEqual(
            window.accept(call),
            .publish([held, call]),
            "the card goes out with the words that were waiting ahead of it, not one tick after"
        )
    }

    func testOnlyGrowingWordsAreEverAllowedToWait() throws {
        let growing = [ChatFrames.text("字"), ChatFrames.thinking("想")]
        let instant = [
            ChatFrames.call(id: "t-1", name: "bash"),
            ChatFrames.result(id: "t-1", name: "bash", message: "ok"),
            ChatFrames.confirm(ChatFrames.pending(id: "t-2", name: "write")),
            ChatFrames.end(),
            ChatFrames.failure(code: "E", message: "坏了"),
            ChatFrames.keepAlive,
        ]
        for payload in growing {
            XCTAssertTrue(
                ChatStreamCoalescer.growsContent(try event(payload)),
                "\(payload) only grows what is already on screen, so it may wait"
            )
        }
        for payload in instant {
            let frame = try event(payload)
            XCTAssertFalse(ChatStreamCoalescer.growsContent(frame), "\(payload) changes the screen's state")

            var window = ChatStreamCoalescer()
            _ = window.accept(try event(ChatFrames.text("等")))
            let held = try event(ChatFrames.text("待"))
            _ = window.accept(held)
            XCTAssertEqual(
                window.accept(frame),
                .publish([held, frame]),
                "…and nothing waiting holds it up, with the held word ahead of it"
            )
        }
    }

    func testASealingDeltaRidesWithTheWordsItFollows() throws {
        var window = ChatStreamCoalescer()
        _ = window.accept(try event(ChatFrames.text("第")))
        let seal = try event(ChatFrames.text("", isLast: true))
        let after = try event(ChatFrames.text("二"))

        XCTAssertEqual(window.accept(seal), .hold, "a seal says nothing, so it is not a state change")
        XCTAssertEqual(window.accept(after), .hold, "…and neither is the word that follows it")
        XCTAssertEqual(window.tick(), .publish([seal, after]), "…and the reducer still gets the seal first")
    }

    func testSettlingEmptiesTheWindowAndClosesIt() throws {
        var window = ChatStreamCoalescer()
        _ = window.accept(try event(ChatFrames.text("甲")))
        let held = [try event(ChatFrames.text("乙")), try event(ChatFrames.text("丙"))]
        for frame in held { _ = window.accept(frame) }

        XCTAssertEqual(window.settle(), held)
        XCTAssertFalse(window.isWindowOpen)
        XCTAssertTrue(window.held.isEmpty)
        XCTAssertEqual(window.settle(), [], "a window that was already taken out hands back nothing again")
    }

    // MARK: - the screen's updates

    /// The requirement in one assertion: N words inside one frame window are one invalidation, not N.
    func testAWindowOfWaitingWordsCostsTheScreenOneUpdate() async throws {
        let (vm, stream, clock) = makeModel()
        await send("说一句", on: vm, stream)
        let port = stream.latest
        try port.feed(ChatFrames.text("第一"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }

        var emissions = 0
        let watching = vm.objectWillChange.sink { _ in emissions += 1 }
        try port.feed(ChatFrames.text("第二"), ChatFrames.text("第三"))
        await waitUntil("both words to have arrived") { vm.heldStreamFrames.count == 2 }
        XCTAssertEqual(emissions, 0, "two frames that reached the app reached nobody's body")

        clock.tick()
        XCTAssertEqual(emissions, 1, "…and the window that delivers them is exactly one update")
        XCTAssertEqual(vm.transcript.segments.compactMap(\.text), ["第一第二第三"])
        watching.cancel()
    }

    func testTheTailIsPulledOncePerWindowNotOncePerWord() async throws {
        let (vm, stream, clock) = makeModel()
        await send("跟着尾巴", on: vm, stream)
        let port = stream.latest

        let before = vm.scrollToBottomID
        try port.feed(ChatFrames.text("一"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }
        XCTAssertEqual(vm.scrollToBottomID, before + 1, "the word that went to the screen pulled the tail once")

        try port.feed(ChatFrames.text("二"), ChatFrames.text("三"))
        await waitUntil("both words to have arrived") { vm.heldStreamFrames.count == 2 }
        XCTAssertEqual(vm.scrollToBottomID, before + 1, "two waiting words move it zero times")

        clock.tick()
        XCTAssertEqual(vm.scrollToBottomID, before + 2, "the window that delivers them moves it once")
    }

    func testTheFirstWordReachesTheScreenWithoutWaitingForATick() async throws {
        let (vm, stream, clock) = makeModel()
        await send("开场白", on: vm, stream)

        try stream.latest.feed(ChatFrames.text("开口第一个字"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }

        XCTAssertFalse(clock.hasTicked, "the clock was never advanced, so nothing but the frame itself landed it")
        XCTAssertTrue(vm.heldStreamFrames.isEmpty)
    }

    func testAnEndFrameIsNeverHeldBehindTheWordsWaitingForATick() async throws {
        let (vm, stream, clock) = makeModel()
        await send("收尾", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }

        try stream.latest.feed(ChatFrames.text("后半句"), ChatFrames.end())
        await waitUntil("the read to let go") { stream.latest.isTerminated }

        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .ended)
        XCTAssertEqual(vm.transcript.segments.compactMap(\.text), ["半句后半句"])
        XCTAssertTrue(vm.heldStreamFrames.isEmpty)
        XCTAssertFalse(clock.hasTicked, "…all of it without a frame boundary, because the socket's own test reads the truth")
    }

    func testTheClockRunsForExactlyAsLongAsAWindowIsStanding() async throws {
        let (vm, stream, clock) = makeModel()
        await send("一句话的活", on: vm, stream)

        XCTAssertFalse(clock.isRunning, "no read, no window, no clock")
        try stream.latest.feed(ChatFrames.text("只"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }
        XCTAssertTrue(clock.isRunning, "a word waiting for company is the only thing that needs a tick")
        XCTAssertEqual(clock.startCount, 1, "and one window is one start, however many times the owner re-asserts it")

        try stream.latest.feed(ChatFrames.end())
        await waitUntil("the read to let go") { stream.latest.isTerminated }
        XCTAssertFalse(clock.isRunning, "the run ended, so the frames stopped being asked for")
        XCTAssertEqual(clock.stopCount, 1)
    }

    func testAStopTakesTheWordsStillWaitingOntoTheScreen() async throws {
        let (vm, stream, clock) = makeModel()
        await send("长活", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }
        try stream.latest.feed(ChatFrames.text("没说完了"))
        await waitUntil("the waiting word") { vm.heldStreamFrames.count == 1 }

        vm.stop()
        await waitUntil("the turn to be interrupted") { vm.transcript.turns.last?.outcome == .interrupted }

        XCTAssertEqual(
            vm.transcript.segments.compactMap(\.text),
            ["半句没说完了"],
            "「a stop keeps what was already on screen」 includes what had arrived but not yet been shown"
        )
        XCTAssertTrue(vm.heldStreamFrames.isEmpty)
        XCTAssertFalse(clock.isRunning)
        XCTAssertFalse(vm.isStreaming)
    }

    func testAStreamCutJustAfterItsLastWordStillShowsThatWord() async throws {
        let (vm, stream, clock) = makeModel()
        await send("断线", on: vm, stream)
        try stream.latest.feed(ChatFrames.text("只有"))
        await waitUntil("the leading word") { vm.transcript.segments.count == 1 }
        try stream.latest.feed(ChatFrames.text("半句"))
        await waitUntil("the waiting word") { vm.heldStreamFrames.count == 1 }

        stream.latest.close()
        await waitUntil("the read to end") { !vm.isStreaming }

        XCTAssertFalse(clock.hasTicked, "nothing closed the window but the read itself")
        XCTAssertEqual(
            vm.transcript.segments.compactMap(\.text),
            ["只有半句"],
            "the last word is not lost because it was one tick late"
        )
        XCTAssertEqual(vm.transcript.turns.last?.outcome, .interrupted)
        XCTAssertNil(vm.stopNotice, "the answer carried something, so this is not the disconnect the console names")
    }

    func testAConfirmAnswerSettlesTheWindowBeforeTheNewReadStarts() async throws {
        let confirmer = PublishingConfirmer()
        let stream = ScriptedChatStream()
        let clock = HandDrivenFrameClock()
        let vm = ChatViewModel(
            streaming: stream,
            confirming: confirmer,
            background: ChatBackgroundAssertion(),
            frameClock: clock,
            conversation: conversation
        )
        await send("要确认的活", on: vm, stream)
        let port = stream.latest
        try port.feed(ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")))
        await waitUntil("the panel") { vm.pendingConfirmation != nil }
        try port.feed(ChatFrames.text("确认前"))
        await waitUntil("the leading word") { vm.transcript.segments.compactMap(\.text) == ["确认前"] }
        try port.feed(ChatFrames.text("的最后"))
        await waitUntil("the waiting word") { vm.heldStreamFrames.count == 1 }

        vm.answerAllConfirmation(.allowed)
        await waitUntil("the answer's own stream") { !confirmer.requests.isEmpty }

        XCTAssertTrue(
            vm.heldStreamFrames.isEmpty,
            "a delta held over from the previous socket must not land inside the resumed answer"
        )
        XCTAssertEqual(
            vm.transcript.segments.compactMap(\.text),
            ["确认前的最后"],
            "the waiting word reached the screen ahead of the answer's own turn"
        )
        XCTAssertEqual(vm.transcript.segments.first?.tool?.awaitingConfirmation, false, "the panel settled")
        XCTAssertFalse(clock.isRunning, "and the new read starts with no window standing")
    }
}

/// The clock a test drives itself.
///
/// `ChatFrameTicker` answers "when does this window close" with the display's cadence, which is the right
/// answer in an app and the wrong one in a test: 「at most one update per frame」 is only assertable when the
/// test is the thing holding the ticks. This has the same idempotence as the production ticker — `start`
/// while running does nothing, so the owner may re-assert as often as it likes — and the same
/// running/not-running answer, so a screen that stops a clock it never needed is caught here.
@MainActor
final class HandDrivenFrameClock: ChatFrameClock {
    private var onTick: (@MainActor () -> Void)?

    /// Starts that actually opened a running clock, i.e. transitions from stopped.
    private(set) var startCount = 0
    /// Stops that actually stopped a running clock.
    private(set) var stopCount = 0
    /// Whether a tick has ever been handed out. The tests that mean "without a frame boundary" assert on this
    /// rather than on a count that stays at zero by luck.
    private(set) var hasTicked = false

    var isRunning: Bool { onTick != nil }

    func start(onTick: @escaping @MainActor () -> Void) {
        guard !isRunning else { return }
        self.onTick = onTick
        startCount += 1
    }

    func stop() {
        guard isRunning else { return }
        onTick = nil
        stopCount += 1
    }

    /// Ends the standing window, the way a display frame would.
    func tick() {
        guard isRunning else { return }
        hasTicked = true
        onTick?()
    }
}

/// The confirm channel, driven by the test.
///
/// Only needed because answering a parked tool opens a read of its own (`SessionRouterService.kt:221`), and
/// the test wants to know that read started — that is the moment the frame window left behind by the previous
/// socket has to already be empty.
private final class PublishingConfirmer: ToolConfirming, @unchecked Sendable {
    private(set) var requests: [ConfirmAgentRequest] = []
    private(set) var ports: [ChatStreamPort] = []

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        requests.append(request)
        let port = ChatStreamPort()
        ports.append(port)
        return port.makeStream()
    }
}
