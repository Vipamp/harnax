import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// URL assembly across the two bases, and the address store the sheet writes through.
final class EndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"
    private let router = "https://router.example.com:28081"

    func testAdminPageURLCarriesBothPageParameters() {
        let url = AgentEndpoint.page(name: nil, status: nil, num: 2, size: 20).url(baseURL: admin)
        XCTAssertEqual(url?.absoluteString, "\(admin)/api/admin/agents/page?pageNum=2&pageSize=20")
    }

    /// An unused filter is absent from the URL, not `name=` — the server treats the two differently only
    /// for the keyword, and a blank keyword would still be sent as a pattern.
    func testBlankFiltersAreLeftOffTheURL() {
        let url = AgentEndpoint.page(name: "", status: nil, num: 1, size: 10).url(baseURL: admin)
        XCTAssertEqual(url?.query, "pageNum=1&pageSize=10")
    }

    func testBothFiltersRideTheQuery() {
        let url = AgentEndpoint.page(name: "research", status: 0, num: 1, size: 10).url(baseURL: admin)
        XCTAssertEqual(url?.query, "pageNum=1&pageSize=10&name=research&status=0")
    }

    /// `PUT /agents/toggle/{id}?status=` takes its flag as a query item and sends no body.
    func testToggleIsAQueryOnlyPut() {
        let endpoint = AgentEndpoint.toggle(id: 7, status: 1)
        XCTAssertEqual(endpoint.method, .put)
        XCTAssertEqual(endpoint.path, "/api/admin/agents/toggle/7")
        XCTAssertEqual(endpoint.query.first?.name, "status")
        XCTAssertNil(endpoint.body)
    }

    func testTeamRoutesMirrorTheAgentOnes() {
        let page = TeamEndpoint.page(name: nil, status: nil, num: 3, size: 10).url(baseURL: admin)
        XCTAssertEqual(page?.absoluteString, "\(admin)/api/admin/teams/page?pageNum=3&pageSize=10")
        XCTAssertEqual(TeamEndpoint.toggle(id: 4, status: 0).path, "/api/admin/teams/toggle/4")
        XCTAssertEqual(TeamEndpoint.delete(id: 4).method, .delete)
        XCTAssertEqual(TeamEndpoint.delete(id: 4).path, "/api/admin/teams/4")
        XCTAssertEqual(TeamEndpoint.relatedSessions(id: 4).path, "/api/admin/teams/4/related-sessions")
    }

    /// A deployed stack is often mounted behind a path prefix; appending `/api/...` must not eat it.
    func testBasePathPrefixSurvives() {
        let url = AdminEndpoint.profile.url(baseURL: "https://gateway.example.com/harnax")
        XCTAssertEqual(url?.absoluteString, "https://gateway.example.com/harnax/api/admin/auth/me")
    }

    func testNonASCIIQueryValuesArePercentEncodedAndRoundTrip() throws {
        let endpoint = Endpoint(
            .get,
            path: "/api/admin/agents/page",
            query: [URLQueryItem(name: "name", value: "翻译 助手")]
        )
        let url = try XCTUnwrap(endpoint.url(baseURL: admin))

        XCTAssertFalse(url.absoluteString.contains("翻译"), "got \(url.absoluteString)")
        XCTAssertEqual(queryItems(of: url).first?.value, "翻译 助手")
    }

    func testRouterBaseIsSelectedForRouterEndpoints() async throws {
        let store = MemorySecretStore()
        let configs = ServerConfigStore(store: store)
        try await configs.update(ServerConfig(adminBaseURL: admin, routerBaseURL: router))

        let selected = try await configs.baseURL(for: .router)
        let adminSelected = try await configs.baseURL(for: .admin)
        XCTAssertEqual(selected, router)
        XCTAssertEqual(adminSelected, admin)
    }

    func testStoreFallsBackToTheDevStackBeforeAnySave() async throws {
        let configs = ServerConfigStore(store: MemorySecretStore())
        let current = try await configs.current()
        XCTAssertEqual(current.adminBaseURL, ServerConfig.devAdminBaseURL)
    }

    /// The sheet writes through the store, so an address saved elsewhere still shows up after the cache is
    /// dropped.
    func testInvalidateReReadsTheKeychain() async throws {
        let store = MemorySecretStore()
        let configs = ServerConfigStore(store: store)
        _ = try await configs.current()

        let other = try ServerConfig(
            adminBaseURL: "https://staging.example.com",
            routerBaseURL: "https://staging.example.com:28081"
        )
        try other.save(into: store)
        await configs.invalidate()

        let current = try await configs.current()
        XCTAssertEqual(current, other)
    }
}
