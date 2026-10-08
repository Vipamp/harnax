import XCTest
import HarnaxCore
import HarnaxFeatures

/// The session's own skill panel: what its three answers have to say, and which of them it is allowed to say
/// before a read has arrived.
///
/// The panel reads two routes at once (`SessionSkillReading.rows` merges them), so the interesting failures here
/// are all about *which sentence the screen is entitled to*. A queue that would not load and a conversation
/// whose agent proposed nothing look identical in `[SessionSkillRow]` — both are an empty list — and only one of
/// them is a statement about the conversation. Same for an enable: the server's own code names the cause, and
/// anything that never carried a code may not borrow one.
@MainActor
final class SessionSkillsViewModelTests: XCTestCase {
    /// The double's replies are `Result`s rather than thrown errors because `PageReadGate` parks a *value*, and
    /// the gate is what makes two overlapping reads real instead of sequential (`ListAppendIdentityTests:845-876`).
    private typealias RowsReply = Result<[SessionSkillRow], Error>
    private typealias EnableReply = Result<Void, Error>

    private final class StubSessionSkills: SessionSkillReading, @unchecked Sendable {
        /// One reply per read, in call order; the last entry repeats, so a test that only cares about the second
        /// read does not have to enumerate the first.
        var rowsReplies: [RowsReply] = []
        let rowsGate = PageReadGate<RowsReply>()
        private(set) var reads: [String] = []

        var enableReply: EnableReply = .success(())
        let enableGate = PageReadGate<EnableReply>()
        private(set) var enables: [(sessionId: String, name: String)] = []

        func rows(sessionId: String) async throws -> [SessionSkillRow] {
            reads.append(sessionId)
            let reply = rowsReplies.isEmpty
                ? .success([])
                : rowsReplies[min(reads.count - 1, rowsReplies.count - 1)]
            return try await rowsGate.absorb(reply).get()
        }

        func enable(sessionId: String, name: String) async throws {
            enables.append((sessionId, name))
            try await enableGate.absorb(enableReply).get()
        }
    }

    private func stub(_ replies: [RowsReply]) -> StubSessionSkills {
        let reading = StubSessionSkills()
        reading.rowsReplies = replies
        return reading
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }

    // MARK: - what the screen may say before an answer arrives

    func testThePanelOpensOnASpinnerNotOnAnEmptyPromise() {
        let vm = SessionSkillsViewModel(reading: stub([.success([])]), sessionId: "s-1")
        XCTAssertTrue(
            vm.isLoading,
            "the sheet's first frame has no read behind it yet: an empty list here prints 「这个会话还没有自写的技能」, "
                + "a claim about the conversation that nothing has answered"
        )
        XCTAssertFalse(vm.unavailable, "nothing has failed yet either")
        XCTAssertTrue(vm.rows.isEmpty)
    }

    /// The unwired host is the one case with no read to wait for, and it must still not end up on the spinner.
    func testAnUnwiredHostSaysItCannotReadAndStopsLoading() async {
        let vm = SessionSkillsViewModel(reading: nil, sessionId: "s-1")
        await vm.refresh()
        XCTAssertTrue(vm.unavailable)
        XCTAssertFalse(vm.isLoading, "no answer is ever coming, so the spinner has to stop")
    }

    // MARK: - empty versus unreadable

    func testAnAnsweredEmptyMergeIsTheOnlyThingThatMayCallTheSessionEmpty() async {
        let vm = SessionSkillsViewModel(reading: stub([.success([])]), sessionId: "s-1")
        await vm.refresh()
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertFalse(vm.unavailable, "both reads answered — this really is a conversation that wrote nothing")
    }

    func testAReadThatFailsSaysTheLoadFailedRatherThanThatTheSessionWroteNothing() async {
        let vm = SessionSkillsViewModel(reading: stub([.failure(APIError.decoding)]), sessionId: "s-1")
        await vm.refresh()
        XCTAssertTrue(vm.unavailable)
        XCTAssertTrue(vm.rows.isEmpty)
    }

    /// The rows from the last read that did answer stay on screen when a re-read fails, with the failure said out
    /// loud: the list the reader is looking at is now a statement about an older answer
    /// (`TeamArtifactsViewModel.load()` keeps its rows for the same reason).
    func testAFailedReReadKeepsTheRowsItAlreadyHadAndSaysSo() async {
        let reading = stub([
            .success([SessionSkillRow(name: "weekly-digest", enabled: true, enabledAt: "2026-10-08 10:00:00")]),
            .failure(APIError.offline),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"])
        XCTAssertFalse(vm.unavailable)

        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"], "a failed refresh is not news that the skill went away")
        XCTAssertTrue(vm.unavailable)
    }

    // MARK: - the enable action

    /// Each refusal keeps the sentence its own code names, and the list re-reads afterwards: whether the row is
    /// enabled is the directory's answer, not something the tap may assume.
    func testARefusalKeepsItsOwnCauseAndNoReReadFollowsIt() async {
        let reading = stub([.success([])])
        reading.enableReply = .failure(SessionSkillRefusal(code: 404))
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()

        await vm.enable(name: "invoice-fill")

        XCTAssertEqual(reading.enables.count, 1)
        XCTAssertEqual(reading.enables.first?.sessionId, "s-1", "the action is session-scoped")
        XCTAssertEqual(reading.enables.first?.name, "invoice-fill")
        XCTAssertEqual(vm.notice?.messageKey, "chat.skills.sourceGone")
        XCTAssertEqual(reading.reads.count, 1, "a refusal changed nothing, so there is nothing to re-read")
    }

    /// The one answer a transport failure may not borrow: 「这份草稿已经不在了」. Nothing said the draft was gone —
    /// the request never reached a server that could say so.
    func testATransportFailureNeverClaimsTheDraftIsGone() async {
        let reading = stub([.success([])])
        reading.enableReply = .failure(APIError.offline)
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")

        await vm.enable(name: "invoice-fill")

        XCTAssertEqual(vm.notice?.code, -1)
        XCTAssertEqual(vm.notice?.messageKey, "chat.skills.enableFailed")
    }

    /// What the name claims, in both verbs. A refusal leaves its sentence on the panel, and the next tap that
    /// answers has to retire it: leaving 「沙箱没有接受这次复制」 under a row the panel now shows as enabled would
    /// be a claim about an attempt the user has already overtaken. The re-read is the other half — the directory
    /// owns the enabled answer, so a success is only proven by asking it again.
    func testASuccessfulEnableReReadsAndClearsTheEarlierRefusal() async {
        let reading = stub([
            .success([SessionSkillRow(name: "invoice-fill", enabled: false)]),
            .success([SessionSkillRow(name: "invoice-fill", enabled: true, enabledAt: "2026-10-08 11:00:00")]),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()
        XCTAssertFalse(vm.rows[0].enabled)

        // The earlier refusal the name promises: a first tap the sandbox refused.
        reading.enableReply = .failure(SessionSkillRefusal(code: 500))
        await vm.enable(name: "invoice-fill")
        XCTAssertEqual(vm.notice?.messageKey, "chat.skills.copyFailed", "the refused tap says why it was refused")
        XCTAssertEqual(reading.reads.count, 1, "a refusal changed nothing, so there is nothing to re-read")

        // The same tap again, this time answered — the sentence from the failed one is now false.
        reading.enableReply = .success(())
        await vm.enable(name: "invoice-fill")

        XCTAssertNil(vm.notice, "a banner naming an attempt the panel has already superseded is a lie about this row")
        XCTAssertEqual(reading.reads.count, 2, "the directory owns the enabled answer, so it is asked again")
        XCTAssertTrue(vm.rows[0].enabled)
    }

    /// The panel's one mutator, tapped twice. Two POSTs copy the same `SKILL.md` twice, re-read twice, and the
    /// second can be refused by the ten-skill ceiling the first just filled — a 「这个会话已启用十条技能」 the user
    /// never caused, on a screen whose whole job is to say only what the server said.
    func testATapThatArrivesWhileAWriteIsInFlightIsNotSentTwice() async throws {
        let reading = stub([.success([SessionSkillRow(name: "invoice-fill", enabled: false)])])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()

        reading.enableGate.arm()
        let inFlight = Task { await vm.enable(name: "invoice-fill") }
        try await waitUntil { reading.enables.count == 1 }
        XCTAssertTrue(vm.isActing, "the row's button has to know a write is running, or it invites the tap the model drops")
        XCTAssertEqual(vm.actingName, "invoice-fill", "and only the row being copied claims to be busy")

        await vm.enable(name: "invoice-fill")
        XCTAssertEqual(
            reading.enables.count,
            1,
            "the second tap is a second sandbox copy, and its 409 would name a ceiling the first tap filled"
        )

        reading.enableGate.release()
        await inFlight.value
        XCTAssertFalse(vm.isActing, "the guard retires with the write, or the panel freezes on its only action")
        XCTAssertNil(vm.actingName)
    }

    // MARK: - overlapping reads

    /// Two refreshes really do overlap — the drawer opening, the refresh row and pull-to-refresh are none of them
    /// awaited by the others — and the older answer must not land on top of the newer one
    /// (`ChatViewModel.refreshContextUsage()`'s generation guard, `:1471-1485`).
    func testAnOlderAskThatAnswersAfterANewerOneIsDiscarded() async throws {
        let reading = stub([
            .success([SessionSkillRow(name: "stale-draft", enabled: false)]),
            .success([SessionSkillRow(name: "fresh-draft", enabled: false)]),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")

        reading.rowsGate.arm()
        let staleAsk = Task { await vm.refresh() }
        try await waitUntil { reading.reads.count == 1 }

        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["fresh-draft"])

        reading.rowsGate.release()
        await staleAsk.value
        XCTAssertEqual(
            vm.rows.map(\.name),
            ["fresh-draft"],
            "the parked read is older, and putting its list back would undo a refresh the user already saw"
        )
    }
}
