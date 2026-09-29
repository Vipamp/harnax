import XCTest
@testable import HarnaxCore

/// Pagination arithmetic the list screen depends on: when to offer another page, and what happens when the
/// server's next page repeats or drops rows.
final class PagedStateTests: XCTestCase {
    private func decoded(_ total: Int, _ ids: [Int64?], num: Int = 1) -> Page<AgentSummary> {
        let rows = ids.map { id -> String in
            let label = id.map { String($0) } ?? "null"
            return "{\"id\":\(label),\"name\":\"agent-\(label)\",\"status\":1}"
        }.joined(separator: ",")
        let json = """
        {"pageNum":\(num),"pageSize":2,"total":\(total),"records":[\(rows)]}
        """
        return try! JSONDecoder().decode(Page<AgentSummary>.self, from: Data(json.utf8))
    }

    func testFreshStateIsBlankAndOffersNoMorePages() {
        let state = PagedState<AgentSummary>(pageSize: 2)
        XCTAssertTrue(state.isEmpty)
        XCTAssertFalse(state.hasMore)
        XCTAssertEqual(state.pageNum, 0)
    }

    func testHasMoreCountsAgainstTheServerTotalNotThePageSize() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(5, [1, 2]))
        XCTAssertTrue(state.hasMore, "2 of 5 held")

        state.append(with: decoded(5, [3, 4], num: 2))
        XCTAssertTrue(state.hasMore, "4 of 5 held")

        state.append(with: decoded(5, [5], num: 3))
        XCTAssertFalse(state.hasMore)
        XCTAssertEqual(state.elements.count, 5)
    }

    /// A short last page is the usual answer; the flag must not ask for a page that cannot exist.
    func testShortPageThatCompletesTheTotalStopsPaging() {
        var state = PagedState<AgentSummary>(pageSize: 20)
        state.replace(with: decoded(3, [1, 2], num: 1))
        state.append(with: decoded(3, [3], num: 2))
        XCTAssertFalse(state.hasMore)
    }

    func testReplaceDiscardsWhatWasAccumulated() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(4, [1, 2]))
        state.append(with: decoded(4, [3, 4], num: 2))
        XCTAssertEqual(state.elements.count, 4)

        state.replace(with: decoded(4, [1, 2]))
        XCTAssertEqual(state.elements.map(\.id), [1, 2])
        XCTAssertEqual(state.pageNum, 1)
    }

    /// The list is keyed by id, so a page that shifted under a concurrent write would otherwise render the
    /// same row twice.
    func testAppendDropsRowsAlreadyOnScreen() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(6, [1, 2]))
        state.append(with: decoded(6, [2, 3], num: 2))

        XCTAssertEqual(state.elements.map(\.id), [1, 2, 3])
    }

    /// A page that reports a smaller total than what is on screen is the server saying the rows moved;
    /// trusting it stops the loop instead of paging forever.
    func testShrunkenTotalStopsPaging() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(5, [1, 2]))
        state.append(with: decoded(0, [], num: 2))

        XCTAssertFalse(state.hasMore)
        XCTAssertEqual(state.total, 0)
        XCTAssertEqual(state.elements.count, 2)
    }

    func testPageNumNeverMovesBackward() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(6, [1, 2], num: 3))
        state.append(with: decoded(6, [3], num: 2))
        XCTAssertEqual(state.pageNum, 3)
    }

    /// What a page actually cost is what ends paging, not what survived on screen. A row that moved to another
    /// page mid-scroll never reappears, so counting the rows on screen would promise a page forever and the
    /// list would re-request it on every tick the last row stays visible.
    func testRowsLostToAShiftStillEndPaging() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(5, [1, 2]))
        state.append(with: decoded(5, [2, 3], num: 2))
        state.append(with: decoded(5, [3, 4], num: 3))

        XCTAssertEqual(state.elements.count, 4, "one row moved out of reach")
        XCTAssertFalse(state.hasMore, "five row slots served against a total of five")
    }

    /// The same trap with the server's own data. Once one row with a null id is on screen, every later one is
    /// dropped by the de-dup — `nil` matches `nil` — so the screen can never fill up to `total`.
    func testARowThatCannotBeKeyedStillEndsPaging() {
        var state = PagedState<AgentSummary>(pageSize: 2)
        state.replace(with: decoded(4, [1, nil]))
        state.append(with: decoded(4, [nil, 3], num: 2))

        XCTAssertEqual(state.elements.count, 3, "the second unkeyable row is dropped")
        XCTAssertFalse(state.hasMore, "four row slots served against a total of four")
    }
}
