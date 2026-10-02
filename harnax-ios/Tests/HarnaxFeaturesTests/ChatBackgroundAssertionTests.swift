import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The background allowance one run holds (`DESIGN.md:183`): one assertion for the reads in flight, given back
/// exactly once on every way a read ends, and an expiry that stops the turn rather than leaving it streaming
/// on a socket iOS is about to suspend.
///
/// Not one of these waits for the real grace period, and none of them could: the two system calls are the
/// injected seam (`ChatBackgroundAssertion.Platform`), which is what lets a test say “the system granted this
/// and then took it back” in one line. That seam is also the only way the expiry path can be reached at all —
/// on the macOS host there is no background state to enter, so the fallback grants nothing.
@MainActor
final class ChatBackgroundAssertionTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "翻译一组")

    // MARK: - the seam

    /// The platform's two calls, recorded, with the way to say the allowance has been taken back. Isolated
    /// explicitly because a nested type does not inherit the isolation of the class it sits in, and firing an
    /// expiration handler is main-thread work.
    @MainActor
    private final class BackgroundProbe {
        /// Grants and give-backs in the order they happened. The app's own reaction appends to this too, which
        /// is how one test can read the ordering rather than just the counts.
        private(set) var log: [String] = ["start"]
        private(set) var granted: [Int] = []
        private(set) var ended: [Int] = []
        private var expirations: [@MainActor () -> Void] = []
        private var nextToken: Int = 1
        /// `false` is the shape of a host that grants nothing: the state machine still runs, there is just no
        /// assertion to give back.
        var grants = true

        var beginCount: Int { log.filter { $0 == "begin" }.count }
        var endCount: Int { ended.count }
        /// Everything after the opening marker, which is the grant/give-back story on its own.
        var story: [String] { log.dropFirst().map { $0 } }

        var platform: ChatBackgroundAssertion.Platform {
            ChatBackgroundAssertion.Platform(
                begin: { [weak self] name, onExpire in self?.begin(name: name, onExpire: onExpire) },
                end: { [weak self] token in
                    self?.log.append("end")
                    self?.ended.append(token)
                }
            )
        }

        private func begin(name: String, onExpire: @escaping @MainActor () -> Void) -> Int? {
            log.append("begin")
            expirations.append(onExpire)
            guard grants else { return nil }
            let token = nextToken
            nextToken += 1
            granted.append(token)
            return token
        }

        /// The app's own step, recorded in the same order the platform's are.
        func note(_ text: String) {
            log.append(text)
        }

        /// iOS taking the assertion last granted back. Whatever the app does about it happens inside this call.
        func revokeLast() {
            guard let expiration = expirations.popLast() else { return }
            expiration()
        }
    }

    private func makeAssertion(_ probe: BackgroundProbe) -> ChatBackgroundAssertion {
        ChatBackgroundAssertion(platform: probe.platform)
    }

    /// Nothing expires in the cases that use this, so the callback says so loudly if it ever runs.
    private func neverExpires() -> @MainActor () -> Void {
        { XCTFail("the allowance was not supposed to be taken back here") }
    }

    // MARK: - take and release

    func testAssertionIsTakenOnceAndGivenBackOnce() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        XCTAssertFalse(assertion.isHolding)

        let handle = assertion.take(onExpire: neverExpires())

        XCTAssertTrue(assertion.isHolding)
        XCTAssertEqual(probe.story, ["begin"])
        XCTAssertEqual(probe.granted, [1])

        assertion.release(handle)

        XCTAssertFalse(assertion.isHolding)
        XCTAssertEqual(probe.story, ["begin", "end"])
        XCTAssertEqual(probe.ended, probe.granted, "the token given back is the one that was handed out")
    }

    /// The name sits beside the assertion in a hang report, so it is developer-facing English and stable.
    func testAssertionIsTakenUnderTheChatRunName() {
        var seen = ""
        let assertion = ChatBackgroundAssertion(
            platform: ChatBackgroundAssertion.Platform(
                begin: { name, _ in seen = name; return 9 },
                end: { _ in }
            )
        )

        let handle = assertion.take(onExpire: neverExpires())
        XCTAssertEqual(seen, "harnax-chat-run")
        assertion.release(handle)
    }

    func testTwoReadsInFlightShareOneAssertion() {
        let probe = BackgroundProbe()
        let shared = makeAssertion(probe)

        let lead = shared.take(onExpire: neverExpires())
        let answer = shared.take(onExpire: neverExpires())

        XCTAssertEqual(probe.beginCount, 1, "the answer's stream joins the allowance the lead is already holding")
        XCTAssertEqual(probe.ended, [])

        shared.release(lead)
        XCTAssertEqual(probe.endCount, 0, "one read went away, the other is still reading")
        XCTAssertTrue(shared.isHolding)

        shared.release(answer)
        XCTAssertEqual(probe.endCount, 1)
        XCTAssertEqual(probe.ended, [1])
    }

    func testReleasingTheSameHandleTwiceEndsNothingTwice() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        let handle = assertion.take(onExpire: neverExpires())

        assertion.release(handle)
        assertion.release(handle)

        XCTAssertEqual(probe.endCount, 1)
        XCTAssertFalse(assertion.isHolding)
    }

    /// A stop lets every read go at once; the socket that notices later must not end the allowance a newer run
    /// is living on.
    func testLateReleaseCannotEndANewerRunsAssertion() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        let stale = assertion.take(onExpire: neverExpires())

        assertion.releaseAll()
        XCTAssertEqual(probe.endCount, 1)

        let current = assertion.take(onExpire: neverExpires())
        XCTAssertEqual(probe.beginCount, 2)

        assertion.release(stale)
        XCTAssertTrue(assertion.isHolding, "the run that is reading keeps its allowance")
        XCTAssertEqual(probe.endCount, 1)

        assertion.release(current)
        XCTAssertEqual(probe.endCount, 2)
    }

    func testSuccessiveRunsDoNotStackTokens() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)

        for run in 1...3 {
            let handle = assertion.take(onExpire: neverExpires())
            XCTAssertTrue(assertion.isHolding)
            XCTAssertEqual(probe.beginCount, run)
            assertion.release(handle)
            XCTAssertFalse(assertion.isHolding)
            XCTAssertEqual(probe.endCount, run)
        }

        XCTAssertEqual(probe.ended, [1, 2, 3], "one token per run, each given back with the token it was handed")
    }

    // MARK: - expiry

    /// The assertion is forfeited before the app is told, so a run that reacts by stopping cannot end a second
    /// one behind it.
    func testExpiryEndsTheAssertionBeforeTheRunIsTold() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        _ = assertion.take(onExpire: { probe.note("notice") })

        probe.revokeLast()

        XCTAssertEqual(probe.story, ["begin", "end", "notice"])
        XCTAssertFalse(assertion.isHolding)
    }

    func testExpiryIsSaidOnceToEveryReadInFlight() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        var notices = 0
        let first = assertion.take(onExpire: { notices += 1 })
        let second = assertion.take(onExpire: { notices += 1 })

        probe.revokeLast()

        XCTAssertEqual(notices, 2, "every socket is about to be suspended, so none stays open in the app's eyes")
        XCTAssertEqual(probe.endCount, 1)

        assertion.release(first)
        assertion.release(second)
        XCTAssertEqual(probe.endCount, 1, "a release that arrives after the expiry has nothing left to give back")

        let next = assertion.take(onExpire: { notices += 1 })
        XCTAssertEqual(probe.beginCount, 2, "a new run asks the system again")
        assertion.release(next)
        XCTAssertEqual(notices, 2)
    }

    func testExpiryOfARunThatAlreadyEndedSaysNothing() {
        let probe = BackgroundProbe()
        let assertion = makeAssertion(probe)
        var notices = 0
        let handle = assertion.take(onExpire: { notices += 1 })
        assertion.release(handle)

        probe.revokeLast()

        XCTAssertEqual(notices, 0)
        XCTAssertEqual(probe.endCount, 1, "only the one give-back, ever")
    }

    // MARK: - the host that grants nothing

    func testAHostThatGrantsNothingStillTracksItsReads() {
        let probe = BackgroundProbe()
        probe.grants = false
        let assertion = makeAssertion(probe)
        var notices = 0

        let handle = assertion.take(onExpire: { notices += 1 })
        XCTAssertEqual(probe.beginCount, 1)
        XCTAssertFalse(assertion.isHolding, "there is no assertion to hold")

        probe.revokeLast()
        XCTAssertEqual(notices, 1, "the expiry path is the same code whatever the host granted")

        assertion.release(handle)
        XCTAssertEqual(probe.endCount, 0, "nothing was granted, so there is nothing to give back")
    }

    /// The fallback this test host compiles to: no assertion, no give-back, nothing to crash on
    /// (`Package.swift:7`).
    func testLivePlatformGrantsNothingOnThisHost() {
        let assertion = ChatBackgroundAssertion()

        let handle = assertion.take(onExpire: neverExpires())
        XCTAssertFalse(assertion.isHolding)
        assertion.release(handle)
        XCTAssertFalse(assertion.isHolding)
    }

    // MARK: - the screen

    private func makeModel(
        _ probe: BackgroundProbe,
        commands: (any AgentCommanding)? = nil,
        confirming: (any ToolConfirming)? = nil
    ) -> (ChatViewModel, ScriptedChatStream) {
        let stream = ScriptedChatStream()
        let vm = ChatViewModel(
            streaming: stream,
            confirming: confirming,
            commands: commands,
            background: ChatBackgroundAssertion(platform: probe.platform),
            conversation: conversation
        )
        return (vm, stream)
    }

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

    private func send(
        _ message: String,
        on vm: ChatViewModel,
        _ stream: ScriptedChatStream
    ) async {
        let before = stream.ports.count
        XCTAssertTrue(vm.send(message))
        await waitUntil("a stream for “\(message)”") { stream.ports.count > before }
    }

    func testASentTurnHoldsTheAssertionUntilTheEndFrameLands() async {
        let probe = BackgroundProbe()
        let (vm, stream) = makeModel(probe)

        await send("讲个长故事", on: vm, stream)
        XCTAssertTrue(vm.isStreaming)
        XCTAssertEqual(probe.story, ["begin"], "taken with the socket")

        try? stream.latest.feed(ChatFrames.text("从前", isLast: true), ChatFrames.end())
        await waitUntil("the end frame to give the allowance back") { probe.endCount == 1 }

        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(probe.story, ["begin", "end"])
    }

    func testACloseWithoutAnEndFrameGivesTheAssertionBack() async {
        let probe = BackgroundProbe()
        let (vm, stream) = makeModel(probe)

        await send("讲个长故事", on: vm, stream)
        stream.latest.close()

        await waitUntil("a dead stream to give the allowance back") { probe.endCount == 1 }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.stopNotice, .disconnected, "and the screen says the stream died, not the run finished")
    }

    func testAFailedReadGivesTheAssertionBack() async {
        let probe = BackgroundProbe()
        let (vm, stream) = makeModel(probe)

        await send("讲个长故事", on: vm, stream)
        try? stream.latest.feed(ChatFrames.failure(code: "RESOURCE_LOCKED", message: ""))

        await waitUntil("a failed read to give the allowance back") { probe.endCount == 1 }
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(vm.stopNotice?.isFailure, true)
    }

    func testAStopGivesTheAssertionBackAtOnce() async {
        let probe = BackgroundProbe()
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(probe, commands: commands)

        await send("讲个长故事", on: vm, stream)
        vm.stop()

        XCTAssertEqual(probe.endCount, 1, "a stop is the user letting the run go, allowance included")
        XCTAssertFalse(vm.isStreaming)
        await waitUntil("the interrupt to reach the server") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.last?.command, .interrupt)

        // The read's own exit runs afterwards, and finds nothing left to give back.
        stream.latest.close()
        await waitUntil("the cancelled read to let go of its stream") { stream.latest.isTerminated }
        XCTAssertEqual(probe.endCount, 1)
    }

    func testLeavingTheConversationGivesTheAssertionBack() async {
        let probe = BackgroundProbe()
        let (vm, stream) = makeModel(probe)

        await send("讲个长故事", on: vm, stream)
        vm.bind(ChatConversation(id: "s-2", title: "另一个会话"))

        XCTAssertEqual(probe.endCount, 1)
        XCTAssertEqual(vm.conversation.id, "s-2")

        let before = stream.ports.count
        XCTAssertTrue(vm.send("第二句"))
        XCTAssertEqual(probe.beginCount, 2, "the next conversation's turn asks the system again")
        await waitUntil("its stream") { stream.ports.count > before }
    }

    /// A parked run resumes on a stream of its own, after the one that parked it ended: the allowance the
    /// answer needs is taken by the answer, not inherited from a read that is already gone.
    func testTheConfirmationLegTakesItsOwnAssertion() async {
        let probe = BackgroundProbe()
        let confirmer = ScriptedConfirmer()
        let (vm, stream) = makeModel(probe, confirming: confirmer)

        await send("删掉临时目录", on: vm, stream)
        try? stream.latest.feed(
            ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash", dangerous: true)),
            ChatFrames.end()
        )
        await waitUntil("the confirmation on screen") { vm.pendingConfirmation != nil }
        await waitUntil("the parked run's read to give the allowance back") { probe.endCount == 1 }
        XCTAssertEqual(probe.beginCount, 1)

        vm.submitConfirmation()
        await waitUntil("the answer's stream") { confirmer.ports.count == 1 }
        XCTAssertEqual(probe.beginCount, 2, "the resumed run is a read in flight, so it holds an allowance")
        XCTAssertEqual(probe.endCount, 1)

        try? confirmer.latest.feed(ChatFrames.text("已删除", isLast: true), ChatFrames.end())
        await waitUntil("the resumed run's end frame") { probe.endCount == 2 }
        XCTAssertFalse(vm.isStreaming)
    }

    func testExpiryStopsTheRunAndSaysWhatHappened() async {
        let probe = BackgroundProbe()
        let commands = ScriptedAgentCommands()
        let (vm, stream) = makeModel(probe, commands: commands)

        await send("讲个长故事", on: vm, stream)
        XCTAssertTrue(vm.isStreaming)

        probe.revokeLast()

        XCTAssertFalse(vm.isStreaming, "the read is about to be suspended; the screen must not say it is running")
        XCTAssertEqual(vm.composerNotice, .warning(hx("chat.background.expired")), "in the app's own words")
        XCTAssertNil(vm.stopNotice, "one sentence about it, not two")
        XCTAssertEqual(probe.story, ["begin", "end"], "the expiry gives the assertion back and nothing ends it twice")

        await waitUntil("the interrupt to reach the server") { !commands.requests.isEmpty }
        XCTAssertEqual(commands.requests.last?.command, .interrupt)

        XCTAssertTrue(vm.send("再来一次"), "and the user can put the turn on the wire again")
        XCTAssertEqual(probe.beginCount, 2)
    }

    /// The expiry only speaks for a run that is still reading. A turn that finished a moment ago has nothing
    /// left to lose, and a second sentence under its own settled answer would be a lie.
    func testExpiryAfterTheAnswerLandedSaysNothing() async {
        let probe = BackgroundProbe()
        let (vm, stream) = makeModel(probe)

        await send("讲个长故事", on: vm, stream)
        try? stream.latest.feed(ChatFrames.text("完了", isLast: true), ChatFrames.end())
        await waitUntil("the end frame to give the allowance back") { probe.endCount == 1 }

        probe.revokeLast()

        XCTAssertNil(vm.composerNotice)
        XCTAssertFalse(vm.isStreaming)
        XCTAssertEqual(probe.endCount, 1)
    }
}

/// The stop notice's own shape, for the assertion that only cares which case it is.
private extension ChatViewModel.StopNotice {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

/// The answer channel, for the one test that has to drive a resumed run. `ToolConfirmationTests` keeps its own
/// version private; this needs the same two lines of bookkeeping and nothing else.
private final class ScriptedConfirmer: ToolConfirming, @unchecked Sendable {
    private(set) var ports: [ChatStreamPort] = []

    var latest: ChatStreamPort { ports.last! }

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> {
        let port = ChatStreamPort()
        ports.append(port)
        return port.makeStream()
    }
}
