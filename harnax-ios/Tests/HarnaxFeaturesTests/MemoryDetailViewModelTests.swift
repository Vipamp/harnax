import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// One agent's long-term memory: the curated text, the append-only ledger, and the two non-happy paths kept
/// apart.
///
/// The detail is where judgment 5 becomes visible: the screen is addressed by a runtime **name**
/// (`MemoryController.kt:70` binds `{agentId}` to `MemoryAgentResponse.kt:19-20`'s「Agent name as the runtime
/// keys it」), so a name with a dot or a hyphen has to read back the memory it owns rather than answer「没有这台」.
/// The route encoding is pinned in `MemoryWireTests`; what is pinned here is that no screen in between rewrites
/// the string it was handed.
@MainActor
final class MemoryDetailViewModelTests: XCTestCase {
    private static let detailFile = "HarnaxFeatures/Memory/MemoryDetailView.swift"
    private static let detailVMFile = "HarnaxFeatures/Memory/MemoryDetailViewModel.swift"

    private let dotted = "main-bot.v2"

    override func setUp() {
        super.setUp()
        HarnaxCatalog.shared.language = .en
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    // MARK: - judgment 5: the name is the whole address

    /// The name reaches the route exactly as the screen was handed it: dots, hyphens, underscores and a space
    /// included. Trimming, lowercasing or number-substituting here is how memory that exists reads as absent.
    func testTheNameReachesTheReadUntouched() async throws {
        for name in [dotted, "agent_eu.prod", "ops bot", "deep/nested"] {
            let fake = FakeMemory()
            fake.readReplies = [.success(try MemoryDetail.stub(name, content: "kept"))]
            let vm = MemoryDetailViewModel(agentName: name, catalog: fake)

            await vm.load()

            XCTAssertEqual(fake.readRequests.last, name, "\(name) must go out as it came in")
            XCTAssertEqual(vm.agentName, name, "and the screen titles itself with the same string")
        }
    }

    /// There is no second address to fall back on: `agentId` on this DTO is a name, so a numeric row id
    /// anywhere in this type's address would be a different agent's memory. Read out of the source, because an
    /// `Int64` and a `String` both compile until the wrong prefix is swept.
    func testTheAddressIsTheNameAndNothingElse() throws {
        let text = try FeatureSources.code(of: Self.detailVMFile)
        let initialiser = try FeatureSources.block(text, from: "public init(agentName:")
        XCTAssertFalse(initialiser.contains("Int"), "the route takes a name, not a row id: \(initialiser)")
        XCTAssertTrue(
            text.contains("catalog.read(agentName: agentName)"),
            "the call site hands over the screen's own name, literally rather than rebuilt"
        )
        XCTAssertFalse(
            text.contains("catalog.delete("),
            "the erase belongs to the row's menu, so a detail screen cannot start one by accident"
        )
    }

    // MARK: - judgment 6: curated text and per-day entries, and nothing of the session layer

    /// The two long-term layers are drawn apart because the runtime keeps them apart
    /// (`MemoryDetailResponse.kt:6-9`: a consolidator rewrites `MEMORY.md`, a per-call flush only appends a day).
    /// The order is the server's — oldest first (`MemoryStoreGateway.kt:137`) — and this screen does not sort it.
    func testTheCuratedTextAndTheLedgerArriveApart() async throws {
        let fake = FakeMemory()
        let entries = [
            try MemoryDailyEntry.stub("2026-10-04", content: "- booked the flight", lastModified: "2026-10-04T09:00:00Z"),
            try MemoryDailyEntry.stub("2026-10-05", content: "- moved the meeting", lastModified: "2026-10-05T18:30:00Z"),
        ]
        fake.readReplies = [.success(
            try MemoryDetail.stub(dotted, content: "# Owner\nLives in Lisbon.", lastModified: "2026-10-05T12:00:00Z", entries: entries)
        )]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

        await vm.load()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.curated, "# Owner\nLives in Lisbon.")
        XCTAssertEqual(vm.entries.map(\.date), ["2026-10-04", "2026-10-05"], "the server's order, unsorted")
        XCTAssertEqual(vm.updatedDay, "2026-10-05", "the instant is shown as the day it falls on")
    }

    /// The whole screen is the long-term layer: the read subtracts the conversation objects before it groups
    /// (`MemoryStoreGateway.kt:122`) and no route addresses one, so the detail must not even reach for the count
    /// that stands in for that layer. That count is a row's signal, and putting it on a detail page would offer
    /// a number the page cannot explain.
    func testTheDetailCarriesNoSignalForTheConversationLayer() throws {
        let text = try FeatureSources.code(of: Self.detailFile)
        XCTAssertFalse(text.contains("pendingSessionLayers"), "that count belongs to the list row")
        XCTAssertTrue(
            text.contains("HXMarkdownText(text, on: .surface)"),
            "both panes are markdown documents, so both are drawn as documents"
        )
        XCTAssertTrue(text.contains("HXSectionHeader(\"memory.detail.curated\")"))
        XCTAssertTrue(text.contains("HXSectionHeader(\"memory.detail.daily\")"))
    }

    /// A day the bucket holds but whose body could not be read still keeps its line
    /// (`MemoryStoreGateway.kt:128-133`): the owner lived through that day, and a ledger that silently loses a
    /// date is a ledger that lies about how much is missing.
    func testADayWithNoBodyIsStillADay() async throws {
        let fake = FakeMemory()
        fake.readReplies = [.success(
            try MemoryDetail.stub(dotted, entries: [try MemoryDailyEntry.stub("2026-10-01")])
        )]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

        await vm.load()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.entries.count, 1, "an empty body does not delete the date")
        XCTAssertNil(hxPresented(vm.entries[0].content), "and the pane has its own sentence for it")
        XCTAssertNotEqual(hx("memory.detail.noEntry"), "memory.detail.noEntry")
    }

    /// An agent whose bucket has objects but neither a curated text nor a ledger still lands on the content
    /// phase: the read answered, and each pane says what it has not got.
    func testAnAgentWithNeitherLayerStillHasAReadAnswer() async throws {
        let fake = FakeMemory()
        fake.readReplies = [.success(try MemoryDetail.stub(dotted))]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

        await vm.load()

        XCTAssertEqual(vm.phase, .content, "the read answered; the two empty panes are its answer")
        XCTAssertNil(vm.curated, "the DTO ships \"\" rather than null, and empty means nothing curated")
        XCTAssertTrue(vm.entries.isEmpty)
        XCTAssertNotEqual(hx("memory.detail.noCurated"), "memory.detail.noCurated")
        XCTAssertNotEqual(hx("memory.detail.noDaily"), "memory.detail.noDaily")
    }

    // MARK: - judgment 2:「没有这台」is an answer

    /// The controller answers a caller with nothing stored for that agent as `ResultVo.error(404, "No memory for
    /// this agent")` (`MemoryController.kt:73`) inside an HTTP 200, which the shared mapper delivers as a
    /// failure. It is the one failure this screen renders as an empty state, and it does not share the list's
    /// sentence: one screen is about an agent, the other about every agent the caller owns.
    func testMissingMemoryIsTheEmptyStateWithItsOwnSentence() async throws {
        let fake = FakeMemory()
        fake.readReplies = [.failure(MemoryFailure.missing)]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

        await vm.load()

        XCTAssertEqual(vm.phase, .empty)
        XCTAssertNil(vm.detail)
        XCTAssertNil(vm.curated)
        XCTAssertNotEqual(
            hx(MemoryDetailViewModel.emptyKey),
            hx("state.error.title"),
            "an owner who has simply not written any memory must not be told the read broke"
        )
    }

    /// Every other refusal stays a refusal, including the 400 the stack raises for a name it cannot place
    /// (`MemoryServiceImpl.kt:57-60` throws `BizException("Invalid agent id")`). Folding that into the empty
    /// state would report a broken address as an agent that has forgotten everything.
    func testEveryOtherRefusalStaysAFailure() async throws {
        for error in [APIError.business(code: 400, message: "Invalid agent id"), MemoryFailure.refused,
                      APIError.business(code: 404, message: "Gateway off"), APIError.offline] {
            let fake = FakeMemory()
            fake.readReplies = [.failure(error)]
            let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

            await vm.load()

            XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: error)))
        }
    }

    /// A cancelled pull is silence, not a verdict: the error card has to ask first
    /// (`PullRefreshGateTests.swift:26`).
    func testACancelledReadChangesNothing() async throws {
        let fake = FakeMemory()
        let detail = try MemoryDetail.stub(dotted, content: "kept")
        fake.readReplies = [.success(detail), .failure(.cancelled)]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)

        await vm.load()
        await vm.load()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.detail, detail)
    }

    // MARK: - the two reads of one screen

    /// The first read owes the screen a loading card.
    func testTheFirstReadShowsTheLoadingCard() async throws {
        let fake = FakeMemory()
        fake.readGate.arm()
        fake.readReplies = [.success(try MemoryDetail.stub(dotted, content: "kept"))]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)
        let first = Task { await vm.load() }
        try await waitUntil { fake.readRequests.count == 1 }

        XCTAssertEqual(vm.phase, .loading)
        fake.readGate.release()
        _ = await first.value
        XCTAssertEqual(vm.phase, .content)
    }

    /// A pull must not swap the screen it is pulling for a spinner: that card is the pull's own container, and
    /// replacing it mid-gesture is how the read cancels itself.
    func testAPullLeavesWhatTheOwnerIsReadingOnScreen() async throws {
        let fake = FakeMemory()
        let kept = try MemoryDetail.stub(dotted, content: "already here")
        fake.readReplies = [.success(kept)]
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)
        await vm.load()

        fake.readGate.arm()
        fake.readReplies = [.success(try MemoryDetail.stub(dotted, content: "replaced"))]
        let pull = Task { await vm.load() }
        try await waitUntil { fake.readRequests.count == 2 }

        XCTAssertEqual(vm.phase, .content, "the second read is a pull, not an entry")
        XCTAssertEqual(vm.curated, "already here")

        fake.readGate.release()
        _ = await pull.value
        XCTAssertEqual(vm.curated, "replaced")
    }

    /// And the answer to a read that is no longer wanted takes nothing off the screen.
    func testAnAnswerToASupersededReadTakesNothing() async throws {
        let fake = FakeMemory()
        let stale = try MemoryDetail.stub(dotted, content: "stale")
        let current = try MemoryDetail.stub(dotted, content: "current")
        fake.readReplies = [.success(stale)]
        fake.readGate.arm()
        let vm = MemoryDetailViewModel(agentName: dotted, catalog: fake)
        let first = Task { await vm.load() }
        try await waitUntil { fake.readRequests.count == 1 }

        fake.readReplies = [.success(current)]
        await vm.load()
        fake.readGate.release()
        _ = await first.value

        XCTAssertEqual(fake.readRequests.count, 2)
        XCTAssertEqual(vm.curated, "current")
    }

    // MARK: - the two sentences the detail owns

    /// The note is the one thing a ledger of dates cannot say by itself: where the append-only text stops and the
    /// conversation layer took over, and that nothing on this screen is the latter.
    func testTheNoteExplainsTheTwoLayers() {
        let note = hx("memory.detail.note")
        XCTAssertNotEqual(note, "memory.detail.note")
        XCTAssertTrue(note.lowercased().contains("merge"), "the curated text is rewritten by a merge: \(note)")
        XCTAssertTrue(note.lowercased().contains("append"), "the ledger only appends: \(note)")
        XCTAssertFalse(note.lowercased().contains("approv"))
    }

    /// Both languages, both panes: a key that resolves in one catalogue only is a screen that changes shape with
    /// the handset's language.
    func testTheChineseDetailResolves() {
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertEqual(hx("memory.detail.curated"), "整理后的记忆（MEMORY.md）")
        XCTAssertTrue(hx("memory.detail.empty").contains("长期"))
        XCTAssertTrue(hx("memory.detail.noDaily").contains("每日"))
        XCTAssertNotEqual(MemoryDetailViewModel.emptyKey, MemoryListViewModel.emptyKey)
    }

    // MARK: - plumbing

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
