import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Opening a conversation: the read that fetches the stored rows, the three races it has to lose gracefully,
/// and what the screen is told while it is waiting.
///
/// The console loads on mount (`ChatWindow.tsx:745`, called from `:688-710`); the difference is that here the
/// read can come back after the user has moved on, and that has to be a no-op rather than a wrong transcript.
@MainActor
final class ChatHistoryLoadTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "翻译一组")
    private let other = ChatConversation(id: "s-2", title: "日志组")

    private func makeModel(
        history: (any ChatHistoryReading)?,
        commands: (any AgentCommanding)? = nil
    ) -> (ChatViewModel, ScriptedChatStream) {
        let stream = ScriptedChatStream()
        let vm = ChatViewModel(
            streaming: stream,
            commands: commands,
            history: history,
            conversation: conversation
        )
        return (vm, stream)
    }

    private func userRow(_ message: String) -> ChatHistoryLog {
        .user(message: message, timestamp: 1_700_000_000_000, source: nil)
    }

    private func assistantRow(_ text: String) -> ChatHistoryLog {
        .assistant(thinking: "", text: text, calls: [], timestamp: 1_700_000_000_001, source: nil)
    }

    // MARK: - the happy path

    func testOpeningAConversationReadsItsStoredRowsOnce() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls == ["s-1"] }
        history.reply(.success([userRow("第一条"), assistantRow("答一")]))
        await task.value

        XCTAssertEqual(vm.transcript.turns.count, 2)
        XCTAssertEqual(vm.transcript.turns.map(\.role), [.user, .assistant])
        XCTAssertFalse(vm.isLoadingHistory)
        XCTAssertNil(vm.historyFailure)

        // Re-entering the same conversation is not a refresh.
        await vm.load()
        XCTAssertEqual(history.calls, ["s-1"], "one read per conversation")
    }

    func testLoadedRowsPullTheScreenToTheTail() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)
        let before = vm.scrollToBottomID

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        history.reply(.success([userRow("第一条")]))
        await task.value

        XCTAssertGreaterThan(vm.scrollToBottomID, before)
    }

    /// The screen shows a loading state while the rows are on their way — an empty conversation and a
    /// conversation still being read look identical otherwise.
    func testTheReadIsAnnouncedWhileItIsInFlight() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the loading flag") { vm.isLoadingHistory }

        history.reply(.success([]))
        await task.value
        XCTAssertFalse(vm.isLoadingHistory)
    }

    func testAnEmptySessionIsNotAFailure() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        history.reply(.success([]))
        await task.value

        XCTAssertTrue(vm.transcript.turns.isEmpty)
        XCTAssertNil(vm.historyFailure)
    }

    // MARK: - when the read cannot happen

    /// A host that has not wired the history read still gets a working screen.
    func testNoHistoryDependencyMeansNoReadAndNoFailure() async {
        let (vm, _) = makeModel(history: nil)

        await vm.load()

        XCTAssertTrue(vm.transcript.turns.isEmpty)
        XCTAssertNil(vm.historyFailure)
        XCTAssertFalse(vm.isLoadingHistory)
    }

    func testAConversationThatIsStreamingDoesNotReload() async throws {
        let history = ScriptedHistory()
        let (vm, stream) = makeModel(history: history)
        await send("在跑", on: vm, stream)

        await vm.load()

        XCTAssertTrue(history.calls.isEmpty, "the stream owns the transcript while it reads")
        try await finishStream(stream)
    }

    // MARK: - failure and retry

    func testAFailedReadLeavesTheTranscriptAloneAndSaysWhy() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        history.reply(.failure(.offline))
        await task.value

        XCTAssertTrue(vm.transcript.turns.isEmpty)
        XCTAssertEqual(vm.historyFailure, ErrorMessage.text(for: .offline))
        XCTAssertFalse(vm.isLoadingHistory)
    }

    /// Retrying is the screen's error-state button, so a failed read must not be recorded as loaded.
    func testAFailedReadCanBeRetried() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let first = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        history.reply(.failure(.offline))
        await first.value

        let second = Task { await vm.load() }
        await waitUntil("the retry") { history.calls.count == 2 }
        history.reply(.success([userRow("第一条")]))
        await second.value

        XCTAssertEqual(vm.transcript.turns.count, 1)
        XCTAssertNil(vm.historyFailure)
    }

    // MARK: - the races

    /// The user moved on while the rows were in flight; what comes back belongs to another screen.
    func testRowsThatLandAfterAConversationSwitchAreDropped() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        vm.bind(other)
        history.reply(.success([userRow("旧会话")]))
        await task.value

        XCTAssertTrue(vm.transcript.turns.isEmpty)
        XCTAssertEqual(vm.conversation.id, "s-2")

        // And the new conversation is still owed its own read.
        await loadOnce(vm, history: history)
        XCTAssertEqual(history.calls, ["s-1", "s-2"])
    }

    /// A turn the user started during the read owns the screen — stored rows cannot overwrite live work.
    func testRowsThatLandAfterATurnStartedAreDropped() async throws {
        let history = ScriptedHistory()
        let (vm, stream) = makeModel(history: history)

        let task = Task { await vm.load() }
        await waitUntil("the read to start") { history.calls.count == 1 }
        await send("我抢先说一句", on: vm, stream)
        history.reply(.success([userRow("旧会话"), assistantRow("旧答复")]))
        await task.value

        XCTAssertEqual(vm.transcript.turns.first?.segments.first?.text, "我抢先说一句")
        XCTAssertEqual(vm.transcript.turns.count, 2, "the live user bubble and its live answer, no stored rows")
        try await finishStream(stream)
    }

    /// Switching away and back is a fresh mount at this level: `bind` clears the flag, so the rows are read
    /// again rather than trusted to be what the screen left.
    func testComingBackToAConversationReadsItAgain() async {
        let history = ScriptedHistory()
        let (vm, _) = makeModel(history: history)

        await loadOnce(vm, history: history, rows: [userRow("第一条")])
        vm.bind(other)
        await loadOnce(vm, history: history)
        vm.bind(conversation)
        await loadOnce(vm, history: history)

        XCTAssertEqual(history.calls, ["s-1", "s-2", "s-1"])
    }

    // MARK: - what a compaction leaves on screen

    /// The requirement this screen exists to keep: compaction rewrites the *model's* context, while the bubbles
    /// here come from the append-only archive (`session_message`), so a compaction that worked has to leave
    /// every original message exactly as it was — nothing dropped, nothing folded into a summary bubble. The
    /// two counts in the reply describe the context, not the screen, and a transcript rebuilt from them would
    /// be the summary view this screen does not show.
    func testACompactionLeavesEveryStoredBubbleOnScreen() async {
        let history = ScriptedHistory()
        let commands = ScriptedAgentCommands()
        commands.reply = .success(
            AgentCommandReply(success: true, result: .init(beforeMessages: 40, afterMessages: 12))
        )
        let (vm, _) = makeModel(history: history, commands: commands)

        await loadOnce(
            vm,
            history: history,
            rows: [userRow("第一条"), assistantRow("答一"), userRow("第二条"), assistantRow("答二")]
        )
        let before = vm.transcript.turns.map { turn in turn.segments.map(\.text) }
        XCTAssertEqual(before.count, 4)

        vm.requestCompact()
        await waitUntil("the compaction answer") { vm.composerNotice != nil }

        XCTAssertEqual(vm.transcript.turns.count, 4, "the archive keeps what the context folded away")
        XCTAssertEqual(vm.transcript.turns.map { turn in turn.segments.map(\.text) }, before)
        XCTAssertEqual(vm.transcript.turns.map(\.role), [.user, .assistant, .user, .assistant])
    }

    // MARK: - driving

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

    /// One read, start to finish. The scripted history parks until answered, so a load that was expected to
    /// happen has to be answered for the view model to get back to idle.
    private func loadOnce(
        _ vm: ChatViewModel,
        history: ScriptedHistory,
        rows: [ChatHistoryLog] = []
    ) async {
        let before = history.calls.count
        let task = Task { await vm.load() }
        await waitUntil("a read for \(vm.conversation.id)") { history.calls.count > before }
        history.reply(.success(rows))
        await task.value
    }

    private func finishStream(_ stream: ScriptedChatStream) async throws {
        try stream.latest.feed(ChatFrames.text("好了"), ChatFrames.end())
        await waitUntil("the read to let go") { stream.latest.isTerminated }
    }
}

/// The stored rows, handed out when the test says so.
///
/// A read that answered immediately would make the races untestable — the point of each is what the screen
/// looks like when the rows arrive late.
private final class ScriptedHistory: ChatHistoryReading, @unchecked Sendable {
    private typealias Continuation = CheckedContinuation<Result<[ChatHistoryLog], APIError>, Never>

    private let lock = NSLock()
    private var _calls: [String] = []
    private var waiting: [Continuation] = []

    /// The sessions the screen asked for, in order.
    var calls: [String] { lock.withLock { _calls } }

    func history(sessionId: String) async -> Result<[ChatHistoryLog], APIError> {
        await withCheckedContinuation { continuation in
            lock.withLock {
                _calls.append(sessionId)
                waiting.append(continuation)
            }
        }
    }

    /// Answers every read that is parked — which, with one conversation on screen, is the one read.
    func reply(_ result: Result<[ChatHistoryLog], APIError>) {
        let parked: [Continuation] = lock.withLock {
            let parked = waiting
            waiting = []
            return parked
        }
        for continuation in parked { continuation.resume(returning: result) }
    }
}
