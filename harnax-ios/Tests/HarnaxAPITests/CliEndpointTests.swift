import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The five CLI routes, checked against the shapes the backend declares — the two that are easy to guess
/// wrong are that `toggle` carries `status` in the query with no body, and that the detail read is a bare
/// id path sitting next to the `related-*` reads.
final class CliEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    func testPageCarriesBothPageParametersAndBothFilters() {
        let url = CliEndpoint.page(name: "harnax", status: 0, num: 2, size: 20).url(baseURL: admin)
        XCTAssertEqual(
            url?.absoluteString,
            "\(admin)/api/admin/clis/page?pageNum=2&pageSize=20&name=harnax&status=0"
        )
    }

    func testUnusedFiltersAreLeftOffTheURL() {
        let url = CliEndpoint.page(name: "", status: nil, num: 1, size: 10).url(baseURL: admin)
        XCTAssertEqual(url?.absoluteString, "\(admin)/api/admin/clis/page?pageNum=1&pageSize=10")
    }

    /// `@GetMapping("/{id}")` — the only route that answers the three detail-only fields.
    func testDetailIsABareIdGet() {
        let endpoint = CliEndpoint.detail(id: 3)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/clis/3")
        XCTAssertTrue(endpoint.query.isEmpty)
        XCTAssertNil(endpoint.body)
    }

    /// `@PutMapping("/toggle/{id}")` with `@RequestParam status`: a body here would be ignored by the
    /// backend and the switch would silently flip to nothing.
    func testToggleIsAQueryOnlyPut() {
        let endpoint = CliEndpoint.toggle(id: 7, status: 0)
        XCTAssertEqual(endpoint.method, .put)
        XCTAssertEqual(endpoint.path, "/api/admin/clis/toggle/7")
        XCTAssertEqual(endpoint.query.map { "\($0.name)=\($0.value ?? "")" }, ["status=0"])
        XCTAssertNil(endpoint.body)
    }

    func testTheTwoBlastRadiusReadsAreDistinctPaths() {
        XCTAssertEqual(CliEndpoint.relatedAgents(id: 4).path, "/api/admin/clis/4/related-agents")
        XCTAssertEqual(CliEndpoint.relatedSessions(id: 4).path, "/api/admin/clis/4/related-sessions")
    }

    func testEveryRouteSitsOnTheAdminBaseAndNeedsTheToken() {
        for endpoint in [
            CliEndpoint.page(name: nil, status: nil, num: 1, size: 10),
            CliEndpoint.detail(id: 1),
            CliEndpoint.toggle(id: 1, status: 1),
            CliEndpoint.relatedAgents(id: 1),
            CliEndpoint.relatedSessions(id: 1),
        ] {
            if case .admin = endpoint.base {} else {
                XCTFail("\(endpoint.path) is not an /api/admin route and would be sent to the router host")
            }
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path) is not one of the anonymous routes")
        }
    }
}
