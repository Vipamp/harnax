import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The three filters the console's model filter bar carries and this list did not: the model type and the two
/// price bounds (`harnax-ios/specs/04-context-domains.md:17`).
///
/// What these cases pin is the shape of the send, not the rows that come back: a bound the screen has parsed
/// goes out, a bound it has not stays off the wire, and `isFiltered` agrees with whichever of the two happened
/// — because the empty state reads it to decide between "this provider has no models" and "nothing matches
/// your filter", and the two are opposite claims.
@MainActor
final class ModelFilterTests: XCTestCase {
    private func catalog(_ replies: Int = 4) throws -> FakeModelCatalog {
        let catalog = FakeModelCatalog()
        catalog.modelReplies = try (0 ..< replies).map { _ in
            .success(try PageStub.page(ModelSummary.self, [["id": 1]]))
        }
        return catalog
    }

    func testTheTypeAndBothBoundsReachThePageRead() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.typeFilter = .chat
        vm.minPriceText = "0.5"
        vm.maxPriceText = "100"
        await vm.refresh()
        let sent = try XCTUnwrap(catalog.modelFilters.last)
        XCTAssertEqual(sent.modelType, "chat")
        XCTAssertEqual(sent.minPrice, 0.5)
        XCTAssertEqual(sent.maxPrice, 100)
    }

    /// A trailing dot is a number to both apps: Swift's `Double("1.")` and the console's `parseFloat`
    /// (`harnax-webui/src/pages/model/index.tsx:563`) both read it as 1, so the box and the wire agree.
    func testATrailingDotIsSentAsTheNumberItParsesTo() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.minPriceText = "1."
        await vm.refresh()
        XCTAssertEqual(try XCTUnwrap(catalog.modelFilters.last).minPrice, 1)
        XCTAssertTrue(vm.isFiltered)
    }

    /// The state that genuinely is not a number yet — an exponent stopped mid-typing. It goes out as no bound
    /// rather than as a `0`, which would empty the list under a box that shows only `1e`.
    func testAStoppedExponentIsSentAsNoBound() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.minPriceText = "1e"
        await vm.refresh()
        XCTAssertNil(try XCTUnwrap(catalog.modelFilters.last).minPrice)
        // And the list says it is unfiltered, because it is: no predicate left this screen.
        XCTAssertFalse(vm.isFiltered)
    }

    /// The other half of the same rule: a bound that did go out has to be admitted by `isFiltered`, or a
    /// provider with nothing that cheap shows the "no models" empty state under a price filter.
    func testABoundAloneMakesTheListFiltered() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.maxPriceText = "2"
        await vm.refresh()
        XCTAssertTrue(vm.isFiltered)
    }

    /// The comma fallback the model form already accepts (`ModelForm.swift:100-101`), so the two price inputs
    /// on one screen do not parse a number two different ways.
    func testACommaDecimalSeparatorIsAccepted() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.minPriceText = "0,25"
        await vm.refresh()
        XCTAssertEqual(try XCTUnwrap(catalog.modelFilters.last).minPrice, 0.25)
    }

    /// Text that is neither number nor half-number sends nothing rather than a `0`, which would filter out
    /// every priced row while the box still shows the word.
    func testUnparseableTextIsSentAsNoBound() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        vm.minPriceText = "免费"
        await vm.refresh()
        XCTAssertNil(try XCTUnwrap(catalog.modelFilters.last).minPrice)
        XCTAssertFalse(vm.isFiltered)
    }

    /// The type pick refreshes on its own, the way the status picker does, because it is a choice rather than
    /// typing — and a choice that only took effect on the next pull-up is a filter the screen lies about.
    func testTheTypeChoiceRefreshesWithoutAnotherSend() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        await vm.refresh()
        let before = catalog.modelRequests.count
        vm.typeFilter = .embedding
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(catalog.modelRequests.count, before + 1)
        XCTAssertEqual(try XCTUnwrap(catalog.modelFilters.last).modelType, "embedding")
    }

    /// The two boxes ride the keyword's debounce, so typing a three-digit price is one read and not three.
    func testTheBoundsDebounceLikeTheKeyword() async throws {
        let catalog = try catalog()
        let vm = ModelListViewModel(providerID: 3, catalog: catalog)
        await vm.refresh()
        let before = catalog.modelRequests.count
        vm.minPriceText = "1"
        vm.minPriceText = "10"
        vm.minPriceText = "100"
        try await Task.sleep(for: .milliseconds(700))
        XCTAssertEqual(catalog.modelRequests.count, before + 1)
        XCTAssertEqual(try XCTUnwrap(catalog.modelFilters.last).minPrice, 100)
    }
}
