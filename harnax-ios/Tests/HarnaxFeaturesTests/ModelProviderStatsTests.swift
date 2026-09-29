import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The provider cards' counts, and the identity the grid keys its cards on.
///
/// The counts come from a second route the page does not carry (`ModelProviderServiceImpl.kt:152-161`), so
/// each card asks for them itself. That is where the two halves of this test meet: `refresh()` discards the
/// `stats` map to start over, and the only thing that ever asked again was the card's own `onAppear` — which
/// does not fire for a row that was already on screen. Every card then sits on "loading" for the rest of the
/// session, which is what the first two tests are about. The third is the grid's identity: with the slot
/// number as the key, a card that changes provider keeps the read of whoever sat in that slot before.
@MainActor
final class ModelProviderStatsTests: XCTestCase {
    private let gridFile = "HarnaxFeatures/Models/ModelProviderListView.swift"

    /// The console's page size is eight cards; these tests use a smaller one so paging is reachable.
    private func page(
        _ rows: [[String: Any]],
        num: Int = 1,
        total: Int,
        size: Int = 2
    ) throws -> Result<Page<ModelProviderSummary>, APIError> {
        .success(try PageStub.page(ModelProviderSummary.self, rows, pageNum: num, total: total, pageSize: size))
    }

    private func row(_ id: Int, name: String = "厂商") -> [String: Any] {
        [
            "id": id, "type": "dashscope", "name": name, "status": 1, "isPublic": 1, "creator": "heqingsong",
            "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
        ]
    }

    /// A refresh that wipes the counts has to read them again, and must not wait for a card that is already
    /// on screen to appear a second time.
    func testARefreshBringsTheCountsBackInsteadOfLeavingThemOnLoading() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [try page([row(11)], total: 1), try page([row(11)], total: 1)]
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 2)
        await vm.refresh()
        let provider = try XCTUnwrap(vm.items.first)

        await vm.loadStats(for: provider)
        XCTAssertEqual(
            vm.statsState(for: provider),
            .loaded(ModelProviderStats(totalModels: 2, enabledModels: 1, disabledModels: 1)),
            "the card's own read is what puts numbers on it"
        )

        await vm.refresh()
        try await waitUntil { vm.statsState(for: provider) != .pending }
        XCTAssertEqual(
            vm.statsState(for: provider),
            .loaded(ModelProviderStats(totalModels: 2, enabledModels: 1, disabledModels: 1)),
            "a refresh cleared the counts and never asked for them again, so every card sat on loading"
        )
        XCTAssertEqual(catalog.statsRequests, [11, 11], "one read per page, not one per render")
    }

    /// The page tail arrives without a card appearing on its own, so the rows a `loadMore` added need the
    /// same read the rows a `refresh` replaced get.
    func testAPagingInReadsTheCountsOfTheRowsItAdded() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [
            try page([row(11), row(12)], total: 3),
            try page([row(13, name: "第二页")], num: 2, total: 3),
        ]
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 2)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.count, 3)

        for provider in vm.items {
            try await waitUntil { vm.statsState(for: provider) != .pending }
        }
        XCTAssertEqual(
            catalog.statsRequests.sorted(),
            [11, 12, 13],
            "the tail row is on screen and its counts are not there yet — it has to be read like the rest"
        )
    }

    /// A provider another tenant owns refuses the count route. That answer is not permanent: the next
    /// refresh asks again, because the row may have changed hands since.
    func testARefusedCountIsReadAgainByTheNextRefresh() async throws {
        let catalog = FakeModelCatalog()
        catalog.providerReplies = [try page([row(11)], total: 1), try page([row(11)], total: 1)]
        catalog.statsReply = .failure(.business(code: 500, message: "无权限"))
        let vm = ModelProviderListViewModel(catalog: catalog, pageSize: 2)
        await vm.refresh()
        let provider = try XCTUnwrap(vm.items.first)
        try await waitUntil { vm.statsState(for: provider) == .unavailable }
        XCTAssertEqual(vm.statsState(for: provider), .unavailable, "a refusal is its own answer, not a zero")

        catalog.statsReply = .success(ModelProviderStats(totalModels: 5, enabledModels: 4, disabledModels: 1))
        await vm.refresh()
        try await waitUntil { vm.statsState(for: provider) != .unavailable }
        XCTAssertEqual(
            vm.statsState(for: provider),
            .loaded(ModelProviderStats(totalModels: 5, enabledModels: 4, disabledModels: 1)),
            "and the refresh gets a vote on it again"
        )
    }

    /// The grid keyed its cards by slot (`ModelProviderListView.swift:127`): a page that came back with the
    /// same rows in the same slots never re-appeared, and a row that moved kept the read of the slot it
    /// landed in instead of its own.
    func testTheGridDoesNotKeyItsCardsByPosition() throws {
        let text = try FeatureSources.contents(of: gridFile)
        XCTAssertFalse(
            text.contains(#"id: \.offset"#),
            "the card's identity has to be the provider's own, or the counts travel with the slot"
        )
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
