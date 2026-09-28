import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The tool routes as they actually go out. Its own file because the one thing worth pinning here is the
/// filter's *name* — the page route calls it `keyword`, while the agent and team routes call theirs `name`,
/// and a copied query builder would still have returned 200 with every row attached.
final class ToolEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    func testThePageRouteCarriesItsCounterAndNoFilters() {
        let url = ToolEndpoint.page(keyword: nil, status: nil, num: 1, size: 50).url(baseURL: admin)
        XCTAssertEqual(url?.absoluteString, "\(admin)/api/admin/tools/page?pageNum=1&pageSize=50")
    }

    func testBothFiltersRideTheQueryUnderTheirOwnNames() {
        let url = ToolEndpoint.page(keyword: "email", status: 0, num: 2, size: 50).url(baseURL: admin)
        XCTAssertEqual(url?.query, "pageNum=2&pageSize=50&keyword=email&status=0")
    }

    /// `status=0` is a real filter — a stopped row is a row the user asked to see. A blank keyword is not a
    /// pattern worth sending.
    func testAZeroStatusIsSentWhileABlankKeywordIsNot() {
        let url = ToolEndpoint.page(keyword: "", status: 0, num: 1, size: 10).url(baseURL: admin)
        XCTAssertEqual(url?.query, "pageNum=1&pageSize=10&status=0")
    }

    func testTheDetailRouteIsTheIdInThePath() {
        let endpoint = ToolEndpoint.detail(id: 7)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/tools/7")
        XCTAssertTrue(endpoint.query.isEmpty)
        XCTAssertNil(endpoint.body)
    }
}
