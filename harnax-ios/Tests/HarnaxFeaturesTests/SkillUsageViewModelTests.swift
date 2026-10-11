import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// 技能监控 — what the screen does with one read: the window is chosen rather than typed and echoed back, the
/// five numbers are the console's four plus the contract's complement, the zero-use rows survive every path, a
/// row's pills say what the row is,「no data」and「read failed」stay two states, and the table keeps the reply's
/// order.
///
/// The wire shape is covered by `SkillUsageWireTests`; this file is only about the view model.
@MainActor
final class SkillUsageViewModelTests: XCTestCase {
    private func viewModel() -> (SkillUsageViewModel, FakeSkillUsage) {
        let catalog = FakeSkillUsage()
        return (SkillUsageViewModel(catalog: catalog), catalog)
    }

    /// A window that holds three skills and one of them nobody ever loaded. The counts are the DTO's own
    /// columns (`SkillUsageSummaryResponse.kt:16-37`), not a shape this file invented.
    private func populated(days: Int = 30) throws -> SkillUsageSummary {
        try SkillUsageSummary.stub(
            days: days,
            totalSkills: 3,
            zeroUseCount: 1,
            totalViews: 12,
            totalUses: 5,
            rows: [
                try .stub(
                    id: 1, name: "web-search",
                    origin: SkillDraftRules.agentPromotedOrigin, views: 7, uses: 5
                ),
                try .stub(id: 2, name: "quiet-skill", views: 0, uses: 0, lastUsedAt: nil),
                try .stub(id: 3, name: "loaded-only", repositoryName: nil, status: 0, views: 5, uses: 0),
            ]
        )
    }

    private func metric(_ kind: SkillUsageMetric.Kind, in vm: SkillUsageViewModel) -> SkillUsageMetric? {
        vm.metrics.first { $0.kind == kind }
    }

    // MARK: - 1. the window is a choice, and the echo is the server's

    /// Three presets, never a free field, and the one parameter this route has is always named
    /// (`usage.tsx:191-200`, `SkillUsageController.kt:37`, `:46`). The console starts on 30 and so does the
    /// server; the screen agrees with both.
    func testTheWindowIsOneOfThreePresetsAndAlwaysSent() async throws {
        let (vm, catalog) = viewModel()
        XCTAssertEqual(SkillUsageViewModel.Window.allCases.map(\.days), [7, 30, 90])
        XCTAssertEqual(vm.window, .thirty, "30 on both sides: the controller's default and the console's state")
        catalog.replies = [.success(try populated())]
        await vm.refresh()
        XCTAssertEqual(catalog.requests, [30])

        catalog.replies = [.success(try populated(days: 7))]
        vm.window = .seven
        try await waitUntil { catalog.requests.count == 2 }
        XCTAssertEqual(catalog.requests, [30, 7], "a new window is a new read, and nothing else changes the span")
        XCTAssertEqual(vm.window.days, 7)
    }

    /// A window the control cannot offer is no window at all: the segment reports an id, and an id outside the
    /// three must not go out as a `days` the operator never chose.
    func testAnIdOutsideTheThreeFallsBackToTheDefaultWindow() {
        XCTAssertEqual(SkillUsageViewModel.Window.for(21), .thirty)
        XCTAssertEqual(SkillUsageViewModel.Window.for(90), .ninety)
    }

    /// The screen shows the window the server says it answered, not the one it asked for
    /// (`SkillUsage.swift:12-13`). A label that only ever repeats the request would still be a label after the
    /// stack answered a different span.
    func testTheScreenEchoesTheWindowTheServerAnswered() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated(days: 90))]
        await vm.refresh()
        XCTAssertEqual(vm.window.days, 30, "the control still holds what the reader chose")
        XCTAssertEqual(vm.answeredDays, 90, "and the sentence under the table quotes the answer")
    }

    /// A superseded reply may take none of the screen with it — the rows, the five numbers or the window echo.
    func testASupersededWindowReplyLandsOnNothing() async throws {
        let (vm, catalog) = viewModel()
        catalog.gate = true
        catalog.replies = [
            .success(try SkillUsageSummary.stub(
                days: 90, totalSkills: 1, zeroUseCount: 0, totalViews: 40, totalUses: 9,
                rows: [try .stub(id: 8, name: "old-window", views: 40, uses: 9)]
            )),
            .success(try SkillUsageSummary.stub(
                days: 7, totalSkills: 1, zeroUseCount: 0, totalViews: 2, totalUses: 1,
                rows: [try .stub(id: 9, name: "new-window", views: 2, uses: 1)]
            )),
        ]

        vm.window = .ninety
        try await waitUntil { catalog.requests.count == 1 }
        vm.window = .seven
        try await waitUntil { catalog.requests.count == 2 }
        catalog.release()
        try await waitUntil { vm.entries.first?.name == "new-window" }

        XCTAssertEqual(vm.answeredDays, 7)
        XCTAssertEqual(metric(.skills, in: vm)?.value, "1")
        XCTAssertEqual(vm.entries.map(\.name), ["new-window"], "the 90-day reply never reached the screen")
    }

    // MARK: - 2. the five numbers

    /// The console's four cards in the console's order, plus the complement the contract derives
    /// (`usage.tsx:51-75`, `SkillUsage.swift:25-27`).
    func testTheHeadlineNumbersAreTheConsoleFourPlusTheDerivedComplement() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated())]
        await vm.refresh()

        XCTAssertEqual(vm.metrics.map(\.kind), [.skills, .loads, .uses, .unused, .used])
        XCTAssertEqual(metric(.skills, in: vm)?.value, "3")
        XCTAssertEqual(metric(.loads, in: vm)?.value, "12")
        XCTAssertEqual(metric(.uses, in: vm)?.value, "5")
        XCTAssertEqual(metric(.unused, in: vm)?.value, "1")
        XCTAssertEqual(metric(.used, in: vm)?.value, "2", "3 skills, 1 never used")
    }

    /// `usedSkillCount` is not on the wire: the DTO has no such field (`SkillUsageSummaryResponse.kt:16-37`) and
    /// the contract computes it from the two counts it did get (`SkillUsage.swift:25-27`). A same-named field in
    /// the payload is therefore ignored, and the screen never re-divides by a total that can be zero.
    func testTheUsedCountIsDerivedHereRatherThanReadFromThePayload() async throws {
        let (vm, catalog) = viewModel()
        let withABogusField = try SkillUsageSummary.stub(
            totalSkills: 5,
            zeroUseCount: 2,
            extraFields: ["usedSkillCount": 99]
        )
        XCTAssertEqual(withABogusField.usedSkillCount, 3, "the derivation is total minus zero-use, not a wire value")

        catalog.replies = [.success(withABogusField)]
        await vm.refresh()
        XCTAssertEqual(metric(.used, in: vm)?.value, "3")
        XCTAssertEqual(metric(.used, in: vm)?.value, String(withABogusField.usedSkillCount))
    }

    // MARK: - 3. the zero-use rows survive

    /// The page exists to show the skills nobody loads, so a row with no load and no use is the point rather
    /// than noise to filter (`SkillUsageSummaryResponse.kt:10-13`). Nothing on the default path may drop one.
    func testEveryZeroUseRowStandsOnTheScreen() async throws {
        let (vm, catalog) = viewModel()
        let allUnused = try SkillUsageSummary.stub(
            totalSkills: 4,
            zeroUseCount: 4,
            rows: (1...4).map { try SkillUsageRow.unused(id: Int64($0), name: "quiet-\($0)") }
        )
        catalog.replies = [.success(allUnused)]
        await vm.refresh()

        XCTAssertEqual(vm.phase, .content, "four unused skills is news, not an empty window")
        XCTAssertEqual(vm.entries.count, 4, "not one row dropped for having no events")
        XCTAssertEqual(vm.entries.map(\.id), [1, 2, 3, 4])
        XCTAssertTrue(vm.entries.allSatisfy(\.isUnused))
        XCTAssertEqual(vm.unusedCount, 4)
        XCTAssertTrue(vm.entries.allSatisfy { $0.lastEvent == nil }, "a window with no event says so")
    }

    /// The one filter is the console's `Switch` and it inverts the rule instead of breaking it: switched on it
    /// keeps *only* the zero rows. It is client-side, exactly as theirs is, so it must not cost a request
    /// (`usage.tsx:46-49`, `:201-204`).
    func testUnusedOnlyKeepsTheZeroRowsAndCostsNoRequest() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated())]
        await vm.refresh()
        XCTAssertEqual(vm.entries.map(\.id), [1, 2, 3])

        vm.unusedOnly = true
        XCTAssertEqual(vm.entries.map(\.id), [2], "the only row it keeps is the one nobody loads")
        XCTAssertTrue(vm.entries.allSatisfy(\.isUnused))
        XCTAssertEqual(catalog.requests, [30], "the filter decides about rows already answered")

        vm.unusedOnly = false
        XCTAssertEqual(vm.entries.map(\.id), [1, 2, 3])
        XCTAssertEqual(catalog.requests, [30])
    }

    /// A filter that matches nothing is not「no skill to report」— the rows are on the other side of a switch
    /// the reader just threw.
    func testAFilterThatMatchesNothingIsNotTheEmptyState() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try SkillUsageSummary.stub(
            totalSkills: 2,
            rows: [
                try .stub(id: 1, name: "busy", views: 4, uses: 2),
                try .stub(id: 2, name: "also-busy", views: 1, uses: 0),
            ]
        ))]
        await vm.refresh()

        vm.unusedOnly = true
        XCTAssertTrue(vm.entries.isEmpty)
        XCTAssertEqual(vm.phase, .content)
        XCTAssertTrue(vm.showsNoUnusedNote)
    }

    // MARK: - 4. what a row is

    /// Status, provenance, and the repository only when the row names one. `origin` on this payload is not
    /// nullable (`SkillUsageSummaryResponse.kt:60-61`), so both pills always stand; `agent_promoted` is the one
    /// value that means an agent wrote it (`SkillUsage.swift:53`, `SkillDraftRules.agentPromotedOrigin`).
    func testARowSaysWhatItIs() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated())]
        await vm.refresh()

        let promoted = vm.entries[0]
        XCTAssertEqual(promoted.badges, [.enabled, .agentPromoted])
        XCTAssertEqual(promoted.repository, "qoder-skills")

        let quiet = vm.entries[1]
        XCTAssertEqual(quiet.badges, [.enabled, .human], "the DTO's default origin is a human skill")
        XCTAssertNotNil(quiet.repository)

        let held = vm.entries[2]
        XCTAssertEqual(held.badges, [.disabled, .human])
        XCTAssertNil(held.repository, "a row that names no repository shows no repository pill")
    }

    /// The 0/1 column convention, and an origin the app has never heard of still renders — as a human skill it
    /// does not, but the contract keeps the text so a new provenance is not silently relabelled
    /// (`SkillUsage.swift:36-41`).
    func testAnUnrecognisedOriginIsNotReadAsAnAgentSkill() throws {
        let row = try SkillUsageRow.stub(id: 4, name: "legacy", origin: "imported")
        XCTAssertFalse(row.isAgentPromoted)
        XCTAssertEqual(SkillUsageBadge.make(for: row), [.enabled, .human])
    }

    /// An empty repository name is the same absence as a null one — the console folds both into a bare `-`
    /// (`usage.tsx:98`), and a pill drawn from a dash would read as a value.
    func testABlankRepositoryNameIsTreatedAsAbsent() throws {
        let row = try SkillUsageRow.stub(id: 5, name: "orphan", repositoryName: "   ")
        XCTAssertNil(SkillUsageEntry(row: row).repository)
    }

    // MARK: - 5. no data and read failed stay two states

    /// An empty window is the tenant having nothing to count (`SkillUsageSummaryResponse.kt:23-24`, `:35-36`),
    /// and the console's own sentence for it is「No skill to report」(`usage.tsx:217-223`).
    func testAnEmptyWindowIsTheEmptyStateNotAFailure() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try SkillUsageSummary.stub())]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertEqual(vm.answeredDays, 30, "the window is still what the server said it was")
        XCTAssertEqual(Set(vm.metrics.map(\.value)), ["0"], "five zeros, and no table drawn under them")
        XCTAssertTrue(vm.entries.isEmpty)
    }

    /// A transport failure is the error state, in the transport's own words.
    func testATransportFailureIsTheErrorState() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.failure(.offline)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(ErrorMessage.text(for: .offline)))
        XCTAssertNotEqual(vm.phase, .empty, "「could not read」 is not 「nothing to read」")
    }

    /// A non-200 envelope is the same state, and the server's sentence wins when it brought one
    /// (`usage.tsx:32-41` treats the two branches alike; `AdminClient` folds them into one `APIError`).
    func testANonOKEnvelopeCodeIsTheErrorStateToo() async throws {
        let (vm, catalog) = viewModel()
        let refusal = APIError.business(code: 500, message: "Failed to get skill usage summary")
        catalog.replies = [.failure(refusal)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed("Failed to get skill usage summary"))
    }

    /// A refusal under rows that are still on screen keeps the rows: the operator was reading them, and the
    /// pull already carries its own spinner (`EnvVarListViewModel.swift:96-101` draws the same line).
    func testARefusedRefreshLeavesTheRowsUpAndSaysSoInABanner() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated()), .failure(.offline)]
        await vm.refresh()
        XCTAssertEqual(vm.entries.count, 3)

        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.entries.map(\.id), [1, 2, 3])
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
    }

    /// A read nobody is waiting for any more is silence about the call, not a verdict on the stack
    /// (`ErrorMessage.carriesNews`).
    func testACancelledReadRaisesNoErrorCard() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated()), .failure(.cancelled)]
        await vm.refresh()
        await vm.refresh()

        XCTAssertEqual(vm.phase, .content)
        XCTAssertNil(vm.inlineError)
    }

    /// The two states do not borrow each other's sentence, and the screen does not draw one state's words for
    /// the other. A source gate rather than a render, because a copy string only exists on screen.
    func testNoDataAndReadFailedDrawSeparateCopy() throws {
        let text = try FeatureSources.code(of: "HarnaxFeatures/SkillUsage/SkillUsageView.swift")
        XCTAssertTrue(
            text.contains("HXStateView(.empty, message: hx(\"skill.usage.empty\")"),
            "the empty state has to quote this page's own「no skill to report」"
        )
        XCTAssertTrue(
            text.contains("HXStateView(.error, message: message"),
            "the failure state quotes the sentence the view model resolved, never the empty one"
        )
        XCTAssertFalse(text.contains("HXStateView(.empty, message: message"))
    }

    // MARK: - 6. the order

    /// The reply is sorted most-loaded first (`SkillUsageSummaryResponse.kt:35-36`) and the console hands it to
    /// its table untouched (`usage.tsx:46-49`). A second order decided here would put a different skill on top
    /// of the two surfaces, so the screen keeps the row set exactly as it arrived — uses, statuses and blanks
    /// aside.
    func testTheRowsKeepTheRepliesOrder() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try SkillUsageSummary.stub(
            totalSkills: 4,
            zeroUseCount: 1,
            totalViews: 14,
            totalUses: 11,
            rows: [
                // Equal loads, so no client-side tie-break may quietly re-order them; a heavy-use row sits
                // below a load-only one; the zero row is last, which a sort by「interesting」would move.
                try .stub(id: 11, name: "a", views: 7, uses: 1),
                try .stub(id: 12, name: "b", views: 7, uses: 10),
                try .stub(id: 13, name: "c", status: 0, views: 0, uses: 0),
                try .stub(id: 14, name: "d", views: 0, uses: 0),
            ]
        ))]
        await vm.refresh()

        XCTAssertEqual(vm.entries.map(\.id), [11, 12, 13, 14])
        XCTAssertEqual(vm.metrics.map(\.kind), [.skills, .loads, .uses, .unused, .used])
        vm.unusedOnly = true
        XCTAssertEqual(vm.entries.map(\.id), [13, 14], "the filter shortens the list, it never re-sorts it")
    }

    // MARK: - 7. the two stamps print the console's text

    /// `since` and a row's `lastUsedAt` are `LocalDateTime` columns, so they come over the wire with a `T`
    /// (`SkillUsageSummaryResponse.kt:21`, `:70`; `JacksonConfig.kt:13`), while the console masks them to
    /// `YYYY-MM-DD HH:mm:ss` with dayjs (`usage.tsx:136`). The screen cuts that one separator and keeps every
    /// digit the server wrote; an event the window does not hold stays no-stamp rather than an empty one.
    func testTheStampsLoseTheIsoSeparatorAndNothingElse() async throws {
        let (vm, catalog) = viewModel()
        catalog.replies = [.success(try populated())]
        await vm.refresh()
        XCTAssertEqual(vm.since, "2026-09-05 09:00:00", "the window line under the table")
        XCTAssertEqual(vm.entries.first?.lastEvent, "2026-09-20 10:00:00")

        catalog.replies = [.success(try SkillUsageSummary.stub(
            since: nil,
            rows: [try .stub(id: 7, name: "web-search", lastUsedAt: "")]
        ))]
        await vm.refresh()
        XCTAssertNil(vm.since, "a window the server did not date stays undated")
        XCTAssertNil(vm.entries.first?.lastEvent, "a blank stamp is the same no-event as an absent one")
    }

    // MARK: - helpers

    /// Long enough for a `didSet`-launched read to reach the catalog, and short enough that a guard which does
    /// exist cannot be mistaken for one that is merely slow (`ContextWriteGateTests.swift:146-152`).
    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
