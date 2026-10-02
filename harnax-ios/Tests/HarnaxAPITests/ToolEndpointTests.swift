import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The tool routes as they actually go out. Its own file because the one thing worth pinning here is that the
/// table's route carries *nothing*: `/builtin` takes no keyword, no status and no page counter
/// (`AgentToolController.kt:67-78`), which is what makes the search a local one — a route that silently grew
/// a `keyword` again would send the Chinese display column straight back out of reach.
final class ToolEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    func testTheBuiltinRouteIsAPathWithNoQueryAtAll() {
        let endpoint = ToolEndpoint.builtin
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/tools/builtin")
        XCTAssertTrue(endpoint.query.isEmpty, "a filter on this route would mean a server-side search")
        XCTAssertNil(endpoint.body)
        XCTAssertEqual(endpoint.url(baseURL: admin)?.absoluteString, "\(admin)/api/admin/tools/builtin")
    }

    func testTheDetailRouteIsTheIdInThePath() {
        let endpoint = ToolEndpoint.detail(id: 7)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/tools/7")
        XCTAssertTrue(endpoint.query.isEmpty)
        XCTAssertNil(endpoint.body)
    }

    func testTheWizardCandidatesTakeNoParameterEither() {
        let endpoint = ToolEndpoint.available
        XCTAssertEqual(endpoint.url(baseURL: admin)?.absoluteString, "\(admin)/api/admin/tools/available")
    }
}
