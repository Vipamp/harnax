import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The kill switch is the only write on this page, and everything worth guarding lives in the order its
/// requests go out: ask who binds the package, then decide whether to flip it
/// (`harnax-webui/src/pages/cli/index.tsx:114-172`).
@MainActor
final class CliListViewModelTests: XCTestCase {
    private func cli(_ id: Any?, _ name: String = "harnax-cli") -> [String: Any] {
        var row: [String: Any] = ["name": name, "version": "1.4.0"]
        if let id { row["id"] = id }
        return row
    }

    private func seeded(_ count: Int, total: Int? = nil) throws -> Page<CliSummary> {
        try PageStub.page(
            CliSummary.self,
            (1...count).map { cli($0, "包 \($0)") },
            total: total ?? count
        )
    }

    private func fresh() async throws -> (CliListViewModel, FakeClis) {
        let clis = FakeClis()
        clis.replies = [.success(try seeded(2))]
        let vm = CliListViewModel(clis: clis)
        await vm.refresh()
        return (vm, clis)
    }

    // MARK: - the list itself

    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let (vm, clis) = try await fresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(clis.requests.map(\.num), [1])
    }

    func testAnInstallationWithNoPackagesIsNotASpinningWheel() async throws {
        let clis = FakeClis()
        clis.replies = [.success(try PageStub.page(CliSummary.self, [], total: 0))]
        let vm = CliListViewModel(clis: clis)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertFalse(vm.isFiltered)
    }

    func testTheStatusFilterIsSentAsTheZeroOneColumn() async throws {
        let (vm, clis) = try await fresh()
        clis.replies = [.success(try seeded(1))]
        vm.filter = .disabled
        // The didSet spawns its own task; wait for the reply it consumed.
        try await waitUntil { clis.filters.count == 2 }
        XCTAssertEqual(clis.filters.last?.status, 0)
        vm.filter = .all
        try await waitUntil { clis.filters.count == 3 }
        XCTAssertNil(clis.filters.last?.status)
    }

    func testTheSecondPageIsAppendedWhenTheFirstReportsMoreRowsThanItCarried() async throws {
        let clis = FakeClis()
        clis.replies = [.success(try seeded(2, total: 3))]
        let vm = CliListViewModel(clis: clis)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        clis.replies = [.success(try PageStub.page(CliSummary.self, [cli(3, "包 3")], pageNum: 2, total: 3))]
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)
        XCTAssertEqual(clis.requests.map(\.num), [1, 2])
        XCTAssertFalse(vm.canLoadMore, "the counter says the list is exhausted, so no third request goes out")
    }

    func testAFailedRefreshOnTopOfRowsKeepsThemAndSaysSo() async throws {
        let (vm, clis) = try await fresh()
        clis.replies = [.failure(.offline)]
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content, "the list on screen is still the truth until the stack says otherwise")
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
    }

    // MARK: - the gate

    /// A related list nobody could read is not an empty related list: only one of the two means "nothing
    /// loses anything by this", and the switch may be thrown on that answer alone.
    func testAnUnreadableRelatedListSendsNoWriteAndOpensNoDialog() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .failure(.offline)
        await vm.requestStatus(false, for: vm.items[0])
        XCTAssertTrue(clis.statusCalls.isEmpty, "the switch must not flip on an unanswered question")
        XCTAssertNil(vm.disableTarget)
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
        XCTAssertEqual(clis.relatedAgentRequests, [1])
    }

    func testNothingBoundFlipsTheSwitchWithNoDialogAndNoRefreshOffer() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([])
        await vm.requestStatus(false, for: vm.items[0])
        XCTAssertNil(vm.disableTarget)
        XCTAssertNil(vm.refreshOffer, "no session can hold a package nothing ever bound")
        XCTAssertEqual(clis.statusCalls.map(\.id), [1])
        XCTAssertEqual(clis.statusCalls.map(\.enabled), [false])
        XCTAssertEqual(vm.status(of: vm.items[0]), false)
    }

    /// Enabling puts a capability back, so the console runs it straight away and only asks after
    /// (`harnax-webui/src/pages/cli/index.tsx:149-152`).
    func testEnablingInFrontOfBoundAgentsWritesImmediatelyThenOffersTheRefresh() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([try relatedAgent(7, "翻译助手")])
        await vm.requestStatus(true, for: vm.items[0])
        XCTAssertNil(vm.disableTarget)
        XCTAssertEqual(clis.statusCalls.map(\.id), [1])
        XCTAssertEqual(clis.statusCalls.map(\.enabled), [true])
        XCTAssertEqual(vm.refreshOffer?.id, 1)
    }

    func testDisablingInFrontOfBoundAgentsAsksFirstAndNamesThem() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([
            try relatedAgent(7, "翻译助手"),
            try relatedAgent(8, "投研助手"),
        ])
        await vm.requestStatus(false, for: vm.items[1])
        XCTAssertTrue(clis.statusCalls.isEmpty, "a disable is the direction that removes something")
        XCTAssertEqual(vm.disableTarget?.count, 2)
        XCTAssertEqual(vm.disableTarget?.names, "翻译助手、投研助手")
        XCTAssertEqual(vm.disableTarget?.cli.id, 2)
    }

    func testConfirmingADisableWritesAndHandsOffToTheRefreshPanel() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([try relatedAgent(7, "翻译助手")])
        await vm.requestStatus(false, for: vm.items[0])
        await vm.confirmDisable()
        XCTAssertEqual(clis.statusCalls.map(\.id), [1])
        XCTAssertEqual(clis.statusCalls.map(\.enabled), [false])
        XCTAssertNil(vm.disableTarget)
        XCTAssertEqual(vm.refreshOffer?.id, 1)
    }

    func testCancellingTheDisableDialogWritesNothing() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([try relatedAgent(7, "翻译助手")])
        await vm.requestStatus(false, for: vm.items[0])
        vm.cancelDisable()
        XCTAssertNil(vm.disableTarget)
        XCTAssertTrue(clis.statusCalls.isEmpty)
    }

    /// The sentence has to carry both numbers the operator weighs: how many lose the command, and which.
    func testTheDisableMessageFillsBothPlaceholders() {
        let rendered = hx("cli.disable.body", 3, "翻译助手、投研助手")
        XCTAssertNotEqual(rendered, "cli.disable.body", "the key missed the catalogue")
        XCTAssertTrue(rendered.contains("3"))
        XCTAssertTrue(rendered.contains("翻译助手、投研助手"))
    }

    /// Only five names fit a dialog; the count in front of them is what the rest of the list is for.
    func testTheDialogNamesFiveAgentsAtMost() async throws {
        let (vm, clis) = try await fresh()
        let bound = try (1...7).map { try relatedAgent($0, "助手 \($0)") }
        clis.relatedAgentsReply = .success(bound)
        await vm.requestStatus(false, for: vm.items[0])
        let names = try XCTUnwrap(vm.disableTarget?.names)
        XCTAssertEqual(names.components(separatedBy: "、").count, 5)
        XCTAssertEqual(vm.disableTarget?.count, 7)
    }

    /// A bound agent whose name column came back blank still counts and still gets called something: the id
    /// first, and the placeholder only when even that is missing.
    func testNamelessBoundAgentsFallBackToTheirIdThenAPlaceholder() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([
            try relatedAgent(7, ""),
            try rawRelatedAgent(["status": 1]),
        ])
        await vm.requestStatus(false, for: vm.items[0])
        let names = try XCTUnwrap(vm.disableTarget?.names)
        XCTAssertEqual(names, "\(hx("cli.related.agentFallback", 7))、\(hx("cli.related.agentUnnamed"))")
    }

    // MARK: - the write itself

    func testARefusedSwitchKeepsTheRowAndShowsTheServersOwnSentence() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedAgentsReply = .success([])
        clis.statusReplies = [.failure(.business(code: 500, message: "Failed to toggle CLI status"))]
        let row = vm.items[0]
        await vm.requestStatus(false, for: row)
        XCTAssertEqual(vm.status(of: row), true, "a refused write returns to what the stack last said")
        XCTAssertEqual(
            vm.inlineError,
            ErrorMessage.text(for: .business(code: 500, message: "Failed to toggle CLI status"))
        )
        XCTAssertEqual(vm.items.count, 2, "the row stays on the list")
        XCTAssertNil(vm.refreshOffer, "nothing changed, so no session is worth refreshing")
    }

    func testARowWithNoIdCannotBeWrittenSoNothingIsSent() async throws {
        let (vm, clis) = try await fresh()
        let nameless = try CliSummary.stub(cli(nil, "无 id 包"))
        await vm.requestStatus(false, for: nameless)
        XCTAssertTrue(clis.relatedAgentRequests.isEmpty)
        XCTAssertTrue(clis.statusCalls.isEmpty)
        XCTAssertNil(vm.refreshTarget(for: nameless))
    }

    // MARK: - the refresh hand-off

    func testTheRefreshTargetReadsTheCliLineAndCarriesThePackagesName() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedSessionsReply = .success([
            try relatedSession("s-1", name: "会话一", agent: "翻译助手"),
            try relatedSession("s-2", source: "channel", name: "运维群"),
        ])
        let target = try XCTUnwrap(vm.refreshTarget(for: vm.items[0]))
        XCTAssertEqual(target.id, 1)
        XCTAssertEqual(target.name, "包 1")
        if case .cli = target.source {} else {
            XCTFail("the panel has to speak the CLI sentence, not the agent or team one")
        }
        XCTAssertTrue(clis.relatedSessionRequests.isEmpty, "the read only happens when the panel asks")

        let rows = try await result(of: target)
        XCTAssertEqual(rows.map(\.id), ["s-1", "s-2"])
        XCTAssertEqual(rows[0].ownerName, "翻译助手")
        XCTAssertTrue(rows[1].isChannel)
        XCTAssertEqual(clis.relatedSessionRequests, [1])
    }

    /// A failure on the related read must reach the panel as a failure, not as the empty list — the panel
    /// renders "no session holds this" from the empty case, and that is the opposite claim.
    func testTheRefreshTargetHandsItsFailuresToThePanel() async throws {
        let (vm, clis) = try await fresh()
        clis.relatedSessionsReply = .failure(.offline)
        let target = try XCTUnwrap(vm.refreshTarget(for: vm.items[0]))
        if case .success = await target.load() {
            XCTFail("a dead read cannot look like a list")
        }
    }

    private func result(of target: SessionRefreshTarget) async throws -> [RelatedSession] {
        switch await target.load() {
        case let .success(rows): return rows
        case let .failure(error): throw AssertionFailure(error: error)
        }
    }

    private struct AssertionFailure: Error {
        let error: APIError
    }

    /// Sends a raw related-agent object through the decoder, so a field may be left absent.
    private func rawRelatedAgent(_ fields: [String: Any]) throws -> RelatedAgent {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(RelatedAgent.self, from: data)
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
