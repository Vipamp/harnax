import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The memory list: one bare array, the two non-happy paths kept apart, and the purge.
///
/// Four of the domain's seven judgments land here, and three of them are invisible to the compiler: that the
/// route is an array rather than a page (`MemoryController.kt:54`) is only a bug once a pager exists, that
///「没有这台」and「读失败」are different sentences is only a bug once a screen shows one for the other, and that
/// a prefix sweep takes three things is only a bug once the confirmation names two. The first two are pinned by
/// reading behaviour, the third by reading the source the way the copy gates do.
@MainActor
final class MemoryListViewModelTests: XCTestCase {
    private let dotted = "main-bot.v2"

    override func setUp() {
        super.setUp()
        HarnaxCatalog.shared.language = .en
    }

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    // MARK: - the shape of the read

    /// The list is the array the route answered, in the order it arrived and with nothing added: no page
    /// counter, no tail read, no `total` to keep in step with the rows.
    func testOneRefreshReplacesTheWholeArray() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub("alpha", content: "a body"), try MemoryAgentRow.stub("beta")]
        fake.listReplies = [.success(rows)]
        let vm = MemoryListViewModel(catalog: fake)
        XCTAssertEqual(vm.phase, .loading, "the screen starts on the loading card, not on an empty one")

        await vm.refresh()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items, rows)
        XCTAssertEqual(vm.agentCount, 2)
        XCTAssertEqual(fake.listCalls, 1, "one refresh is one request; a route with no paging has no second leg")
    }

    /// A pull while a read is outstanding must not lose the newer ask, and an answer to a generation nobody wants
    /// any more may not take the rows with it.
    func testAnAnswerToASupersededReadIsDroppedAndTheNewerAskStillRuns() async throws {
        let fake = FakeMemory()
        let stale = try MemoryAgentRow.stub("stale")
        let current = try MemoryAgentRow.stub("current")
        fake.listReplies = [.success([stale])]
        fake.listGate.arm()
        let vm = MemoryListViewModel(catalog: fake)
        let first = Task { await vm.refresh() }
        try await waitUntil { fake.listCalls == 1 }

        fake.listReplies = [.success([current])]
        await vm.refresh()
        fake.listGate.release()
        _ = await first.value

        XCTAssertEqual(fake.listCalls, 2, "the queued ask still goes out once the outstanding read lands")
        XCTAssertEqual(vm.items, [current], "the parked first answer belongs to a generation nobody wants")
    }

    // MARK: - judgment 1: both layers grouped, only the long-term one shown

    /// An agent that has only ever written its conversation layer still arrives as a row: the list groups both
    /// layers (`MemoryStoreGateway.kt:94`) and subtracts the conversation ones afterwards (`:95`), leaving a row
    /// with a blank body, no dates and a count of what still owes a merge. Filtering that row out would tell the
    /// owner the agent has no memory at all, which is the opposite of what the bucket says.
    func testARowWithOnlyConversationMemorySurvivesTheRead() async throws {
        let fake = FakeMemory()
        let onlySessions = try MemoryAgentRow.stub("talker", dates: [], pending: 3)
        fake.listReplies = [.success([onlySessions])]
        let vm = MemoryListViewModel(catalog: fake)

        await vm.refresh()

        XCTAssertEqual(vm.phase, .content, "a row whose body is empty is an answer, not a failed read")
        XCTAssertEqual(vm.items.count, 1)
        XCTAssertFalse(vm.items[0].hasCuratedText)
        XCTAssertEqual(vm.items[0].dateCount, 0)
        XCTAssertEqual(vm.items[0].pendingSessionLayers, 3)
    }

    /// What the conversation layer may be represented by, on screen and in code: a count. A draft list, a session
    /// id or a candidate body anywhere in this domain would mean the screen had found a way to read a layer the
    /// routes do not address (`prod_doc/ios-memory-domain.zh-CN.md` §2).
    func testTheDomainReadsNoSessionLayer() throws {
        for file in [Self.listFile, Self.listVMFile, Self.detailFile, Self.detailVMFile] {
            let text = try FeatureSources.code(of: file)
            for forbidden in ["MemoryDraft", "sessionId"] {
                XCTAssertFalse(
                    text.lowercased().contains(forbidden.lowercased()),
                    "\(file) reaches for the conversation layer with \(forbidden)"
                )
            }
        }
    }

    /// The array is the whole state: a pager's machinery in this file would be a second source of truth about
    /// how many rows there are, on a route that reports no total at all.
    func testTheListCarriesNoPagingStateMachine() throws {
        for file in [Self.listFile, Self.listVMFile] {
            let text = try FeatureSources.code(of: file)
            for forbidden in ["PagedState", "loadMore", "pageNum", "pageSize", "hasMore", "total"] {
                XCTAssertFalse(text.contains(forbidden), "\(file) pages a route that does not page")
            }
        }
    }

    // MARK: - judgment 2: nothing yet is not a read that failed

    /// The two sentences have to stay apart, and the code that carries the first one is real: the by-name read
    /// puts a `404` inside a successful HTTP response (`MemoryController.kt:73`), which `ResponseMapper` hands
    /// over as `APIError.business`, and the listing's own「nothing here」is a success with an empty array
    /// (`:36-37`). Either way the list shows the empty card — an error banner would tell the owner their memory
    /// is lost when it has simply not been written.
    func testMissingMemoryIsAnEmptyStateNotAnErrorCard() async throws {
        let fake = FakeMemory()
        fake.listReplies = [.failure(MemoryFailure.missing)]
        let vm = MemoryListViewModel(catalog: fake)

        await vm.refresh()

        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
        XCTAssertNil(vm.inlineError, "the bucket being empty is not news the owner needs a banner about")
    }

    /// The normal leg of the same judgment: a listing that found nothing answers HTTP 200 with an empty array
    /// (`MemoryController.kt:36-37`), so zero rows is a completed read with an answer, not a screen stuck on the
    /// loading card and not a content phase drawing an empty list.
    func testAnEmptyArrayIsTheSameEmptyCard() async throws {
        let fake = FakeMemory()
        fake.listReplies = [.success([])]
        let vm = MemoryListViewModel(catalog: fake)

        await vm.refresh()

        XCTAssertEqual(vm.phase, .empty)
        XCTAssertEqual(vm.agentCount, 0)
    }

    /// A 404 with any other sentence is a refusal: `MemoryServiceImpl.kt:57-60` answers an unaddressable name
    /// with `BizException("Invalid agent id")`, which the envelope carries as a `400`. Folding either of those
    /// into the empty state would report memory as merely unwritten.
    func testAnyOtherRefusalIsAFailureWithTheServersSentence() async throws {
        for error in [MemoryFailure.refused, APIError.business(code: 404, message: "Gateway off"),
                      APIError.business(code: 400, message: "Invalid agent id")] {
            let fake = FakeMemory()
            fake.listReplies = [.failure(error)]
            let vm = MemoryListViewModel(catalog: fake)

            await vm.refresh()

            XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: error)))
        }
    }

    /// The two empty sentences are about different things —「none of your agents has memory」and「this agent has
    /// nothing yet」— so they must not be one key, and neither may be the generic `state.empty.title`, which
    /// names nobody.
    func testTheTwoEmptyStatesDoNotShareACopyKey() {
        XCTAssertNotEqual(MemoryListViewModel.emptyKey, MemoryDetailViewModel.emptyKey)
        for key in [MemoryListViewModel.emptyKey, MemoryDetailViewModel.emptyKey] {
            XCTAssertNotEqual(key, "state.empty.title")
            XCTAssertNotEqual(hx(key), key, "\(key) is missing from a catalogue")
        }
        XCTAssertNotEqual(hx(MemoryListViewModel.emptyKey), hx(MemoryDetailViewModel.emptyKey))
        XCTAssertNotEqual(hx(MemoryListViewModel.emptyKey), hx("state.empty.title"))
    }

    /// A read that failed while rows are on screen keeps the rows: the stack still has them, and replacing a
    /// list with a full-screen error card because one pull failed is how a screen loses information the owner
    /// was reading.
    func testARefreshThatFailsOverRowsKeepsTheRowsAndReportsInline() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub(dotted, content: "curated")]
        fake.listReplies = [.success(rows), .failure(MemoryFailure.refused)]
        let vm = MemoryListViewModel(catalog: fake)

        await vm.refresh()
        await vm.refresh()

        XCTAssertEqual(vm.phase, .content, "the card is a verdict on the stack, not on the rows")
        XCTAssertEqual(vm.items, rows)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: MemoryFailure.refused))
    }

    /// A cancelled pull is not a refusal, so it gets no banner (`PullRefreshGateTests` holds the same rule over
    /// the whole feature layer).
    func testACancelledRefreshStaysSilent() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub(dotted)]
        fake.listReplies = [.success(rows), .failure(.cancelled)]
        let vm = MemoryListViewModel(catalog: fake)

        await vm.refresh()
        await vm.refresh()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertNil(vm.inlineError)
    }

    // MARK: - judgment 3: the purge names three things and reports a number

    /// The DELETE runs a prefix sweep that lists every object under the agent's prefix at both layers
    /// (`MemoryStoreGateway.kt:153`, `:162`) and so takes the curated `MEMORY.md`, every daily note and any
    /// conversation memory that has not been merged away yet. A confirmation naming two of the three would
    /// under-report what just left, in the one place in this app where it cannot be undone.
    func testTheConfirmationNamesAllThreeThingsTheSweepTakes() throws {
        let row = try MemoryAgentRow.stub(dotted, content: "x", dates: ["2026-10-04"], pending: 2)
        let vm = MemoryListViewModel(catalog: FakeMemory())
        vm.beginPurge(row)
        let target = try XCTUnwrap(vm.purgeTarget)
        let warning = MemoryCopy.purgeWarning(target.agentId)

        XCTAssertTrue(warning.contains(dotted), "the sentence has to say whose memory goes: \(warning)")
        XCTAssertTrue(warning.contains("MEMORY.md"), "the curated layer: \(warning)")
        XCTAssertTrue(warning.lowercased().contains("daily"), "the ledger: \(warning)")
        XCTAssertTrue(
            warning.lowercased().contains("merged"),
            "and the conversation layer that has not been merged away: \(warning)"
        )
        XCTAssertTrue(
            warning.lowercased().contains("restor"),
            "an erasure has to say it is final: \(warning)"
        )
    }

    /// The same sentence in the other language, with the same three things in it — a translation that drops one
    /// is how a Chinese reader ends up surprised by a gone ledger.
    func testTheChineseConfirmationNamesTheSameThreeThings() throws {
        HarnaxCatalog.shared.language = .zhHans
        let warning = MemoryCopy.purgeWarning(dotted)
        XCTAssertTrue(warning.contains(dotted))
        XCTAssertTrue(warning.contains("MEMORY.md"))
        XCTAssertTrue(warning.contains("每日"), "the daily ledger: \(warning)")
        XCTAssertTrue(warning.contains("并入"), "the un-merged conversation layer: \(warning)")
        XCTAssertTrue(warning.contains("无法恢复"), "the erasure is final: \(warning)")
    }

    /// A finished purge takes the row out and says how many objects left. `deletedObjects` counts across both
    /// files and ledgers (`MemoryDeleteResponse.kt:17-18`), so one and four are different answers and the banner
    /// has to carry the number rather than just the word「deleted」.
    func testASuccessfulPurgeRemovesTheRowAndReportsTheCount() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub(dotted, content: "keep"), try MemoryAgentRow.stub("other")]
        fake.listReplies = [.success(rows)]
        fake.deleteReplies = [.success(try MemoryDeleteResult.stub(dotted, deletedObjects: 4))]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        vm.beginPurge(vm.items[0])
        await vm.confirmPurge()

        XCTAssertEqual(fake.deleteRequests, [dotted], "addressed by runtime name, verbatim")
        XCTAssertEqual(vm.items.map(\.agentId), ["other"], "the sweep cleared the whole prefix, so it cannot come back")
        let report = try XCTUnwrap(vm.report)
        XCTAssertEqual(report, .removed(agentName: dotted, objects: 4))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertTrue(MemoryCopy.report(report).contains("4"), "the count is the answer: \(MemoryCopy.report(report))")
        XCTAssertEqual(MemoryCopy.tone(report).slot, .success)
        XCTAssertNil(vm.inlineError)
    }

    /// Zero objects out of the bucket is not a completed erasure: the memory is still there and the owner should
    /// keep looking. The console keeps the same two branches apart (`harnax-webui/src/pages/memory/index.tsx:116-126`),
    /// and the row stays because a re-list would answer the same row back.
    func testAPurgeThatFoundNothingKeepsTheRowAndSaysSo() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub(dotted)]
        fake.listReplies = [.success(rows)]
        fake.deleteReplies = [.success(try MemoryDeleteResult.stub(dotted, deletedObjects: 0))]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        vm.beginPurge(vm.items[0])
        await vm.confirmPurge()

        XCTAssertEqual(vm.items, rows, "nothing left the bucket, so nothing leaves the screen")
        let report = try XCTUnwrap(vm.report)
        XCTAssertEqual(report, .nothingFound(agentName: dotted))
        XCTAssertEqual(vm.phase, .content)
        XCTAssertFalse(MemoryCopy.report(report).contains("Deleted"), "the sentence must not claim an erasure")
        XCTAssertTrue(MemoryCopy.report(report).contains("nothing was removed"))
        XCTAssertEqual(MemoryCopy.tone(report).slot, .warning, "a warning, painted as a warning")
    }

    /// A refused purge leaves the row and says the server's own reason: a half-finished sweep leaves objects
    /// behind (`MemoryStoreGateway.kt:818-822`), and a silent no-op would tell the owner the memory is gone.
    func testARefusedPurgeLeavesTheRowAndExplains() async throws {
        let fake = FakeMemory()
        let rows = [try MemoryAgentRow.stub(dotted)]
        fake.listReplies = [.success(rows)]
        fake.deleteReplies = [.failure(MemoryFailure.refused)]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        vm.beginPurge(vm.items[0])
        await vm.confirmPurge()

        XCTAssertEqual(vm.items, rows)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: MemoryFailure.refused))
        XCTAssertNil(vm.report)
        XCTAssertEqual(vm.phase, .content)
    }

    /// The dialog is what makes the deletion intentional, so cancelling it must put nothing on the wire.
    func testACancelledDialogSendsNoDelete() async throws {
        let fake = FakeMemory()
        let row = try MemoryAgentRow.stub(dotted)
        fake.listReplies = [.success([row])]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        vm.beginPurge(vm.items[0])
        XCTAssertNotNil(vm.purgeTarget)
        vm.cancelPurge()
        await vm.confirmPurge()

        XCTAssertNil(vm.purgeTarget)
        XCTAssertTrue(fake.deleteRequests.isEmpty, "a dismissed dialog is not a queued deletion")
    }

    /// One tap, one DELETE: the second tap has to find the row already spoken for, since two prefix sweeps of
    /// the same prefix cannot be undone separately.
    func testTheSecondTapFindsThePurgeAlreadyRunning() async throws {
        let fake = FakeMemory()
        fake.gateDeletes = true
        let row = try MemoryAgentRow.stub(dotted)
        fake.listReplies = [.success([row])]
        fake.deleteReplies = [.success(try MemoryDeleteResult.stub(dotted, deletedObjects: 1))]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        vm.beginPurge(vm.items[0])
        let first = Task { await vm.confirmPurge() }
        try await waitUntil { fake.deleteRequests.count == 1 }

        vm.beginPurge(vm.items[0])
        await vm.confirmPurge()
        XCTAssertEqual(fake.deleteRequests, [dotted], "the first sweep is still outstanding")

        fake.releaseDeletes()
        _ = await first.value
        XCTAssertEqual(vm.items, [], "the row leaves once the sweep is reported, not before")
        XCTAssertNil(vm.purgingName)
    }

    /// `agentId` is the only address these routes take (`MemoryController.kt:70`, `:89`) and the DTO defaults it
    /// to `""` (`MemoryAgentResponse.kt:20`), so a blank row has nothing to name and must not offer a deletion
    /// that would address the whole tenant prefix or nothing at all.
    func testARowWithNoNameOffersNoPurge() async throws {
        let fake = FakeMemory()
        let blank = try MemoryAgentRow.stub("")
        fake.listReplies = [.success([blank])]
        let vm = MemoryListViewModel(catalog: fake)
        await vm.refresh()

        XCTAssertFalse(vm.canPurge(vm.items[0]))
        vm.beginPurge(vm.items[0])
        await vm.confirmPurge()
        XCTAssertTrue(fake.deleteRequests.isEmpty)
        XCTAssertNil(vm.inlineError, "an unaddressable row is not a failure the owner can act on")
    }

    // MARK: - judgment 4:「待并入」counts conversations that owe a merge

    /// The number is a count of conversations still holding their own layer, not of candidates waiting on a
    /// decision: a rejection leaves the layer where it is (`MemoryDraftServiceImpl.kt:314`) and the same material
    /// is proposed again next window. The console's own tooltip calls it「正等你审批」, which is a claim the count
    /// does not support, so this app says「still owes a merge」in both languages and never uses either word for
    /// approval.
    func testThePendingChipOwesAMergeRatherThanADecision() {
        XCTAssertEqual(hxCount("memory.row.pending", 3), "3 conversations still owe a merge")
        XCTAssertEqual(hxCount("memory.row.pending", 1), "1 conversation still owes a merge")
        for word in ["approv", "review", "pending decision"] {
            XCTAssertFalse(MemoryCopy.pending(3).lowercased().contains(word), "\(word) is a different claim")
        }

        HarnaxCatalog.shared.language = .zhHans
        let chinese = MemoryCopy.pending(2)
        XCTAssertTrue(chinese.contains("并入"), "the word is 并入, as the merge is: \(chinese)")
        for word in ["审批", "审核", "待处理"] {
            XCTAssertFalse(chinese.contains(word), "\(word) would promise a decision queue this route has no data for")
        }
    }

    /// The chip's tooltip is the sentence that explains why the material is not in the list at all, and it has to
    /// repeat the reason the count cannot carry: a rejection does not remove the layer, so the same text comes
    /// back next window.
    func testTheTooltipExplainsWhatTheCountCannot() {
        let why = hx("memory.pending.why")
        XCTAssertNotEqual(why, "memory.pending.why")
        XCTAssertTrue(why.lowercased().contains("merge") || why.contains("并"), "\(why)")
        XCTAssertFalse(why.lowercased().contains("approv"))
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertTrue(hx("memory.pending.why").contains("驳回"), "the Chinese sentence names the rejection case")
    }

    /// The row's two counted chips are counted copy, so one and two take different English forms.
    func testTheRowCountsInBothLanguages() {
        XCTAssertEqual(hxCount("memory.row.days", 1), "1 daily note")
        XCTAssertEqual(hxCount("memory.row.days", 5), "5 daily notes")
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertEqual(hxCount("memory.row.days", 5), "5 条每日记录")
        XCTAssertEqual(MemoryCopy.day("2026-10-05T12:00:00Z"), "2026-10-05")
    }

    // MARK: - judgment 5: the name is the address, dots and hyphens included

    /// The name goes out exactly as the row carried it: `main-bot.v2` is a legal runtime name
    /// (`MemoryAgentResponse.kt:19-20`) and the store keys the prefix off that string, so a screen that
    /// normalised it — trimmed the dot, swapped the hyphen — would read back「没有这台」for memory that exists.
    func testADottedAndHyphenatedNameIsSentVerbatim() async throws {
        let fake = FakeMemory()
        for name in ["main-bot.v2", "agent_eu.prod", "with space"] {
            let row = try MemoryAgentRow.stub(name, content: "kept")
            fake.listReplies = [.success([row])]
            fake.deleteReplies = [.success(try MemoryDeleteResult.stub(name, deletedObjects: 2))]
            let vm = MemoryListViewModel(catalog: fake)
            await vm.refresh()

            vm.beginPurge(vm.items[0])
            await vm.confirmPurge()
            XCTAssertEqual(fake.deleteRequests.last, name)
        }
    }

    /// What a row pushes is the same string the row was addressed by, unwrapped: an optional or a number here
    /// would send a type the destination never registered, which is a row that looks tappable and does nothing.
    func testTheRowPushesItsOwnName() async throws {
        let row = try MemoryAgentRow.stub(dotted)
        let ref = MemoryAgentRef(agentName: row.agentId)
        XCTAssertEqual(ref, MemoryAgentRef(agentName: "main-bot.v2"))
        XCTAssertEqual(Set([ref, MemoryAgentRef(agentName: "other")]).count, 2)
        let text = try FeatureSources.code(of: Self.listFile)
        XCTAssertTrue(
            text.contains("NavigationLink(value: MemoryAgentRef(agentName: row.agentId))"),
            "the card pushes the name the route takes"
        )
        XCTAssertTrue(text.contains("navigationDestination(for: MemoryAgentRef.self)"))
    }

    // MARK: - judgment 7: the destructive action is not the read affordance

    /// The whole card is the read, as `ModelProviderListView.swift:169-185` draws it, and the purge is a corner
    /// menu whose button is destructive and sits outside that label: one gesture must not both open somebody's
    /// memory and erase it. Read out of the source, because a `NavigationLink` and a `Button` look the same to
    /// the compiler.
    func testThePurgeLivesOutsideTheCardsLink() throws {
        let text = try FeatureSources.code(of: Self.listFile)
        let link = try FeatureSources.block(text, from: "NavigationLink(value: MemoryAgentRef")
        XCTAssertTrue(link.contains("HXCard"), "the label is the card itself: \(link)")
        XCTAssertFalse(link.contains("destructive"), "no erase inside the read's own tap area")
        XCTAssertFalse(link.contains("onPurge"))

        let menu = try FeatureSources.block(text, from: "var menu: some View")
        XCTAssertTrue(menu.contains("Button(role: .destructive"), "the house shape for an erase")
        XCTAssertTrue(menu.contains("HXText(\"memory.purge.action\")"))
        XCTAssertTrue(menu.contains(".disabled(!canPurge)"))

        // `block(_:from:)` stops at the first brace pair that closes, and here that is the binding's own `get`
        // closure, so the dialog is sliced between its opening and the message that ends it instead.
        let opened = try XCTUnwrap(text.range(of: ".confirmationDialog"))
        let warning = try XCTUnwrap(text.range(of: "MemoryCopy.purgeWarning"))
        let dialog = text[opened.lowerBound..<warning.upperBound]
        XCTAssertTrue(dialog.contains("role: .destructive"))
        XCTAssertTrue(dialog.contains("vm.confirmPurge()"), "the dialog is what starts the sweep")
        XCTAssertTrue(dialog.contains("MemoryCopy.purgeWarning"), "and the three-thing sentence is its message")
    }

    /// The other half of the same rule: the app presents nothing by state in a domain that pushes by value
    /// (`SkillNavigationTests.swift:186`), so the confirmation must be a dialog on a binding, never a
    /// `navigationDestination(item:)`.
    func testTheDomainPresentsNothingByItem() throws {
        for file in [Self.listFile, Self.detailFile] {
            let text = try FeatureSources.code(of: file)
            XCTAssertFalse(text.contains("navigationDestination(item:"))
            XCTAssertFalse(text.contains("navigationDestination(isPresented:"))
        }
    }

    /// An icon-only control owes a label, and here there are two of them: the menu glyph says「more」to a screen
    /// reader, and a counted chip says what it counts.
    func testTheIconOnlyControlsAreSpoken() throws {
        let text = try FeatureSources.code(of: Self.listFile)
        XCTAssertTrue(text.contains("Button(role: .destructive, action: onPurge)"))
        XCTAssertTrue(text.contains(".accessibilityLabel(hx(\"state.action.more\"))"))
        XCTAssertTrue(text.contains(".accessibilityLabel(hx(\"memory.column.days\"))"))
        XCTAssertTrue(text.contains(".accessibilityLabel(hx(\"memory.column.pending\"))"))
    }

    // MARK: - the stamp

    /// `lastModified` is an instant (`MemoryStoreGateway.kt:787-790` renders `Instant.toString()`), while every
    /// other stamp in this app is a zoneless server wall clock. A time of day printed without its zone would be
    /// the handset's offset away from the truth, so the row shows the day the memory was written and nothing else.
    func testAnInstantIsShownAsItsDay() throws {
        XCTAssertEqual(MemoryCopy.day("2026-10-05T12:00:00Z"), "2026-10-05")
        XCTAssertEqual(MemoryCopy.day("2026-10-05 12:00:00"), "2026-10-05")
        for unusable in [nil, "", "   ", "2026-10", "not a date", "2026-10-5T12:00:00Z"] {
            XCTAssertNil(MemoryCopy.day(unusable), "\(String(describing: unusable)) must drop the byline, not guess one")
        }
    }

    /// A row with no curated text still says something: the DTO ships `""` rather than null
    /// (`MemoryAgentResponse.kt:22-23`), and a card that printed nothing reads as a row that failed to load.
    func testAnEmptyBodyHasItsOwnSentence() {
        XCTAssertNotEqual(hx("memory.row.noCurated"), "memory.row.noCurated")
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertTrue(hx("memory.row.noCurated").contains("长期"))
    }

    // MARK: - plumbing

    private static let listFile = "HarnaxFeatures/Memory/MemoryListView.swift"
    private static let listVMFile = "HarnaxFeatures/Memory/MemoryListViewModel.swift"
    private static let detailFile = "HarnaxFeatures/Memory/MemoryDetailView.swift"
    private static let detailVMFile = "HarnaxFeatures/Memory/MemoryDetailViewModel.swift"

    /// A parked reply only lands when the test lets it, so the wait is for the fake's log rather than for a
    /// timeout — the same shape `ApiKeyFormViewModelTests.swift:411` uses.
    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
