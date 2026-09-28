import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

@MainActor
final class AgentListViewModelTests: XCTestCase {
    func testTheFirstPageBecomesRowsNotASpinner() async throws {
        let agents = FakeAgents()
        agents.replies = [.success(try PageStub.page(
            [["id": 1, "name": "客服助手"], ["id": 2, "name": "代码评审员"]],
            total: 2
        ))]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 2)
        XCTAssertEqual(vm.total, 2)
        XCTAssertEqual(agents.requests.map(\.num), [1])
        XCTAssertEqual(agents.requests.map(\.size), [20])
    }

    func testTheRequestAsksForThePageSizeTheScreenWasBuiltWith() async throws {
        let agents = FakeAgents()
        agents.replies = [.success(try PageStub.page([], total: 0))]
        let vm = AgentListViewModel(agents: agents, pageSize: 5)
        await vm.refresh()
        XCTAssertEqual(agents.requests.map(\.num), [1])
        XCTAssertEqual(agents.requests.map(\.size), [5])
    }

    func testAnEmptyAccountIsNotASpinningWheel() async throws {
        let agents = FakeAgents()
        agents.replies = [.success(try PageStub.page([], total: 0))]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .empty)
        XCTAssertTrue(vm.items.isEmpty)
    }

    func testAFailureWithNothingOnScreenBecomesAnErrorState() async {
        let agents = FakeAgents()
        agents.replies = [.failure(.offline)]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
    }

    func testAFailedRefreshKeepsTheRowsAndRaisesABanner() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1, "name": "客服助手"]], total: 1)),
            .failure(.timeout),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
        XCTAssertEqual(vm.items.count, 1, "a refresh that failed must not blank the list")
        XCTAssertEqual(vm.inlineError, hx("error.timeout"))
    }

    func testTheNextRefreshAfterABannerWipeClearsTheError() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1]], total: 1)),
            .failure(.timeout),
            .success(try PageStub.page([["id": 1]], total: 1)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        await vm.refresh()
        XCTAssertNotNil(vm.inlineError)
        await vm.refresh()
        XCTAssertNil(vm.inlineError)
    }

    func testPagingAsksForTheFollowingPage() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1], ["id": 2]], pageNum: 1, total: 5)),
            .success(try PageStub.page([["id": 3], ["id": 4]], pageNum: 2, total: 5)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertTrue(vm.canLoadMore)
        await vm.loadMore()
        XCTAssertEqual(agents.requests.map(\.num), [1, 2])
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3, 4])
        XCTAssertTrue(vm.canLoadMore)
    }

    func testARowThatArrivedTwiceIsNotRenderedTwice() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1], ["id": 2]], pageNum: 1, total: 4)),
            .success(try PageStub.page([["id": 2], ["id": 3]], pageNum: 2, total: 4)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3])
    }

    func testLoadMoreStopsOnceTheTotalIsReached() async throws {
        let agents = FakeAgents()
        agents.replies = [.success(try PageStub.page([["id": 1], ["id": 2]], pageNum: 1, total: 2))]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertFalse(vm.canLoadMore)
        await vm.loadMore()
        XCTAssertEqual(agents.requests.count, 1, "no request once the accumulated rows match total")
    }

    func testAFailedPageKeepsTheCounterSoTheNextScrollRetriesTheSamePage() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1], ["id": 2]], pageNum: 1, total: 5)),
            .failure(.offline),
            .success(try PageStub.page([["id": 3]], pageNum: 2, total: 5)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertEqual(vm.inlineError, hx("error.offline"))
        await vm.loadMore()
        XCTAssertEqual(agents.requests.map(\.num), [1, 2, 2])
        XCTAssertEqual(vm.items.map(\.id), [1, 2, 3])
    }

    func testRetryingFromTheErrorStateRefillsTheList() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .failure(.offline),
            .success(try PageStub.page([["id": 1]], total: 1)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        XCTAssertEqual(vm.phase, .failed(hx("error.offline")))
        await vm.refresh()
        XCTAssertEqual(vm.phase, .content)
    }

    func testAStandaloneTotalOfZeroAfterContentDoesNotPagingForever() async throws {
        let agents = FakeAgents()
        agents.replies = [
            .success(try PageStub.page([["id": 1], ["id": 2]], pageNum: 1, total: 6)),
            .success(try PageStub.page([["id": 3]], pageNum: 2, total: 2)),
        ]
        let vm = AgentListViewModel(agents: agents)
        await vm.refresh()
        await vm.loadMore()
        XCTAssertFalse(vm.canLoadMore, "accumulated rows already exceed the shrunken total")
    }
}
