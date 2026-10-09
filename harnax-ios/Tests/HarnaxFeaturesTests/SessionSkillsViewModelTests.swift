import XCTest
import HarnaxCore
import HarnaxFeatures

/// The session's own skill panel: what its three answers have to say, and which of them it is allowed to say
/// before a read has arrived.
///
/// The panel reads two routes at once and gets one `SessionSkillRead` back (`SessionSkillReading.read` merges
/// them and never throws, each leg carrying its own failure), so the interesting failures here are all about
/// *which sentence the screen is entitled to*. A queue that would not load and a conversation whose agent proposed
/// nothing both end up as a list of rows — and only one of them is a statement about the conversation. The model's
/// job is to land the half that answered *while* saying the other is missing, rather than letting either of the
/// two erase the other. Same for an enable: the server's own code names the cause, and anything that never carried
/// a code may not borrow one.
@MainActor
final class SessionSkillsViewModelTests: XCTestCase {
    private typealias ReadReply = SessionSkillRead
    private typealias EnableReply = Result<Void, Error>

    /// Both legs answered.
    private func answered(_ rows: [SessionSkillRow] = []) -> SessionSkillRead {
        SessionSkillRead(rows: rows, unavailable: false)
    }

    /// At least one leg did not answer; the rows are the half that did.
    private func missing(_ rows: [SessionSkillRow] = []) -> SessionSkillRead {
        SessionSkillRead(rows: rows, unavailable: true)
    }

    /// The directory refused because this session has no running container, which is a reason the panel owns a
    /// separate sentence for; the rows are still the half the queue did answer.
    private func stopped(_ rows: [SessionSkillRow] = []) -> SessionSkillRead {
        SessionSkillRead(rows: rows, unavailable: true, noSandbox: true)
    }

    private final class StubSessionSkills: SessionSkillReading, @unchecked Sendable {
        /// One reply per read, in call order; the last entry repeats, so a test that only cares about the second
        /// read does not have to enumerate the first.
        var readReplies: [ReadReply] = []
        let rowsGate = PageReadGate<ReadReply>()
        private(set) var reads: [String] = []

        var enableReply: EnableReply = .success(())
        let enableGate = PageReadGate<EnableReply>()
        private(set) var enables: [(sessionId: String, name: String)] = []

        func read(sessionId: String) async -> SessionSkillRead {
            reads.append(sessionId)
            let reply = readReplies.isEmpty
                ? SessionSkillRead(rows: [], unavailable: false)
                : readReplies[min(reads.count - 1, readReplies.count - 1)]
            return await rowsGate.absorb(reply)
        }

        func enable(sessionId: String, name: String) async throws {
            enables.append((sessionId, name))
            try await enableGate.absorb(enableReply).get()
        }
    }

    private func stub(_ replies: [ReadReply]) -> StubSessionSkills {
        let reading = StubSessionSkills()
        reading.readReplies = replies
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
        let vm = SessionSkillsViewModel(reading: stub([answered()]), sessionId: "s-1")
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
        let vm = SessionSkillsViewModel(reading: stub([answered()]), sessionId: "s-1")
        await vm.refresh()
        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertFalse(vm.unavailable, "both reads answered — this really is a conversation that wrote nothing")
    }

    func testAReadThatAnsweredNothingSaysTheLoadFailedRatherThanThatTheSessionWroteNothing() async {
        let vm = SessionSkillsViewModel(reading: stub([missing()]), sessionId: "s-1")
        await vm.refresh()
        XCTAssertTrue(vm.unavailable)
        XCTAssertTrue(vm.rows.isEmpty)
    }

    /// The finding this round exists for: one leg failing costs only itself, so the rows the other leg answered
    /// land *together with* the flag, and neither one erases the other. On the first open that is the difference
    /// between a list the user can act on under a banner and 「这个会话的技能读不出来」 over an empty panel — the
    /// console answers the same failure with the same rows (`SessionSkillsDrawer.tsx`'s `Promise.allSettled`,
    /// `sessionSkills.test.ts:82-92`).
    func testAHalfAnsweredReadLandsTheHalfItAnsweredBesideTheMissingFlag() async {
        let reading = stub([
            missing([SessionSkillRow(name: "invoice-fill", description: "fills an invoice", enabled: false)]),
            answered([SessionSkillRow(name: "invoice-fill", description: "fills an invoice", enabled: true)]),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()

        XCTAssertEqual(
            vm.rows.map(\.name),
            ["invoice-fill"],
            "the half that answered is a row the user can still see and act on; dropping it is the whole defect"
        )
        XCTAssertTrue(vm.unavailable, "and the panel still says that the other half is missing")
        XCTAssertFalse(vm.isLoading)

        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["invoice-fill"])
        XCTAssertFalse(vm.unavailable, "a read where both legs answered retires the flag")
    }

    /// The rows from the last read that did answer stay on screen when a re-read comes back with nothing at all,
    /// with the failure said out loud: the list the reader is looking at is now a statement about an older answer
    /// (`TeamArtifactsViewModel.load()` keeps its rows for the same reason). A read that answered nothing is not an
    /// answer of "no rows".
    func testAFailedReReadKeepsTheRowsItAlreadyHadAndSaysSo() async {
        let reading = stub([
            answered([SessionSkillRow(name: "weekly-digest", enabled: true, enabledAt: "2026-10-08 10:00:00")]),
            missing(),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"])
        XCTAssertFalse(vm.unavailable)

        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"], "a failed refresh is not news that the skill went away")
        XCTAssertTrue(vm.unavailable)
        XCTAssertFalse(vm.noSandbox, "a read that simply would not answer never said the sandbox is stopped")
    }

    /// The reason a refused read carries has to survive the merge and reach the sheet: 「这个会话的技能读不出来」 and
    /// 「这个会话的沙箱没有在运行」 send the operator to two different places, and only the second one is a state a
    /// restart of this conversation fixes. The rows the queue did answer stay beside it either way.
    func testAReadRefusedForNoRunningSandboxNamesThatReasonAndKeepsTheNominations() async {
        let reading = stub([
            stopped([SessionSkillRow(name: "invoice-fill", description: "fills an invoice", enabled: false)]),
            answered([SessionSkillRow(name: "invoice-fill", description: "fills an invoice", enabled: true)]),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()

        XCTAssertTrue(vm.noSandbox)
        XCTAssertTrue(vm.unavailable, "the enabled half is still missing, reason or no reason")
        XCTAssertEqual(vm.rows.map(\.name), ["invoice-fill"])

        await vm.refresh()
        XCTAssertFalse(vm.noSandbox, "a read the container answered retires the reason with the flag")
    }

    /// The other branch of the same rule: a re-read that found no sandbox keeps the rows from the last answer, so
    /// the sentence printed over them has to be the one this re-read said. Leaving the generic failure here would
    /// keep the panel claiming a read problem after the server named a stopped container.
    func testAReReadThatFoundNoSandboxNamesThatReasonOverTheRowsItKept() async {
        let reading = stub([
            answered([SessionSkillRow(name: "weekly-digest", enabled: true, enabledAt: "2026-10-08 10:00:00")]),
            stopped(),
        ])
        let vm = SessionSkillsViewModel(reading: reading, sessionId: "s-1")
        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"])
        XCTAssertFalse(vm.noSandbox)

        await vm.refresh()
        XCTAssertEqual(vm.rows.map(\.name), ["weekly-digest"])
        XCTAssertTrue(vm.unavailable)
        XCTAssertTrue(vm.noSandbox, "the banner over a kept list speaks for the read that just failed")
    }

    // MARK: - the enable action

    /// Each refusal keeps the sentence its own code names, and the list re-reads afterwards: whether the row is
    /// enabled is the directory's answer, not something the tap may assume.
    func testARefusalKeepsItsOwnCauseAndNoReReadFollowsIt() async {
        let reading = stub([answered()])
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
        let reading = stub([answered()])
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
            answered([SessionSkillRow(name: "invoice-fill", enabled: false)]),
            answered([SessionSkillRow(name: "invoice-fill", enabled: true, enabledAt: "2026-10-08 11:00:00")]),
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
        let reading = stub([answered([SessionSkillRow(name: "invoice-fill", enabled: false)])])
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
            answered([SessionSkillRow(name: "stale-draft", enabled: false)]),
            answered([SessionSkillRow(name: "fresh-draft", enabled: false)]),
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
