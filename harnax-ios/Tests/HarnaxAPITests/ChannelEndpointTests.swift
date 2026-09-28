import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The nine channel routes, checked against the shapes the backend declares. The two that cannot be copied
/// from a neighbour are `PUT /update/{id}` (id after the verb, unlike the detail and delete reads) and the
/// toggle's flag name `status` (the API Key and variable toggles both call theirs `enabled`).
final class ChannelEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"
    private let router = "https://runtime.example.com"

    func testPageCarriesBothPageParametersAndAllThreeFilters() {
        let url = ChannelEndpoint.page(keyword: "助理", type: "feishu", status: 1, num: 2, size: 20)
            .url(baseURL: admin)
        XCTAssertEqual(
            url?.absoluteString,
            "\(admin)/api/admin/channels/page?pageNum=2&pageSize=20&keyword=%E5%8A%A9%E7%90%86&type=feishu&status=1"
        )
    }

    /// A stopped row is `status=0`, and zero is a real filter value rather than an absent one.
    func testZeroIsSentForTheStatusFilterWhileUnusedFiltersAreLeftOff() {
        let url = ChannelEndpoint.page(keyword: "  ", type: nil, status: 0, num: 1, size: 10).url(baseURL: admin)
        XCTAssertEqual(url?.absoluteString, "\(admin)/api/admin/channels/page?pageNum=1&pageSize=10&status=0")
    }

    /// `@PutMapping("/update/{id}")` — the id rides after the verb here and alone on the delete.
    func testUpdatePutsTheIdAfterTheVerb() throws {
        let endpoint = try ChannelEndpoint.update(
            id: 12,
            ChannelChange(name: "值班群", type: nil, agentId: nil, communicationMode: nil, enabled: nil,
                          configJson: nil, enableThink: nil, enableSearch: nil, enablePlan: nil, description: nil)
        )
        XCTAssertEqual(endpoint.method, .put)
        XCTAssertEqual(endpoint.path, "/api/admin/channels/update/12")
        XCTAssertNotNil(endpoint.body)
    }

    /// `@PutMapping("/toggle/{id}")` with `@RequestParam status`: a body here would be ignored and the
    /// switch would flip to nothing.
    func testToggleIsAQueryOnlyPutWithTheFlagNamedStatus() {
        let endpoint = ChannelEndpoint.toggle(id: 7, status: 0)
        XCTAssertEqual(endpoint.method, .put)
        XCTAssertEqual(endpoint.path, "/api/admin/channels/toggle/7")
        XCTAssertEqual(endpoint.query.map { "\($0.name)=\($0.value ?? "")" }, ["status=0"])
        XCTAssertNil(endpoint.body)
    }

    func testCreateIsARootPostAndDeleteIsABareIdPath() throws {
        let create = try ChannelEndpoint.create(
            ChannelDraft(name: "n", type: "wecom", agentId: 3, communicationMode: "websocket",
                         configJson: "{}", description: nil)
        )
        XCTAssertEqual(create.method, .post)
        XCTAssertEqual(create.path, "/api/admin/channels")
        XCTAssertEqual(ChannelEndpoint.delete(id: 9).path, "/api/admin/channels/9")
        XCTAssertEqual(ChannelEndpoint.delete(id: 9).method, .delete)
    }

    /// The sandbox read is the one channel call that leaves the admin host: it is served by the runtime's
    /// agent proxy, so it must go to the router base with the ids comma-joined in one parameter.
    func testSandboxStatusesGoToTheRouterBaseWithOneJoinedParameter() {
        let endpoint = ChannelEndpoint.sandboxStatuses(sessionIds: ["chn-a", "chn-b"])
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/router/agent/workspace/status")
        XCTAssertEqual(endpoint.query.map { "\($0.name)=\($0.value ?? "")" }, ["sessionIds=chn-a,chn-b"])
        if case .router = endpoint.base {} else {
            XCTFail("the workspace status read would be sent to the admin host")
        }
        let url = endpoint.url(baseURL: router)
        XCTAssertEqual(url?.absoluteString, "\(router)/api/router/agent/workspace/status?sessionIds=chn-a,chn-b")
    }

    /// The three scan routes sit under the row they log into, and the status read is the only GET.
    func testTheThreeScanRoutesHangOffTheRow() {
        XCTAssertEqual(ChannelEndpoint.startWechatLogin(id: 13).path, "/api/admin/channels/13/wechat/login")
        XCTAssertEqual(ChannelEndpoint.startWechatLogin(id: 13).method, .post)
        XCTAssertEqual(ChannelEndpoint.wechatLoginStatus(id: 13).path,
                       "/api/admin/channels/13/wechat/login/status")
        XCTAssertEqual(ChannelEndpoint.wechatLoginStatus(id: 13).method, .get)
        XCTAssertEqual(ChannelEndpoint.cancelWechatLogin(id: 13).path,
                       "/api/admin/channels/13/wechat/login/cancel")
        XCTAssertEqual(ChannelEndpoint.cancelWechatLogin(id: 13).method, .post)
    }

    func testEveryChannelRouteNeedsTheToken() throws {
        for endpoint in [
            ChannelEndpoint.page(keyword: nil, type: nil, status: nil, num: 1, size: 10),
            try ChannelEndpoint.create(
                ChannelDraft(name: "n", type: "wecom", agentId: 3, communicationMode: nil,
                             configJson: nil, description: nil)
            ),
            ChannelEndpoint.toggle(id: 1, status: 1),
            ChannelEndpoint.delete(id: 1),
            ChannelEndpoint.sandboxStatuses(sessionIds: ["chn-a"]),
            ChannelEndpoint.startWechatLogin(id: 1),
            ChannelEndpoint.wechatLoginStatus(id: 1),
            ChannelEndpoint.cancelWechatLogin(id: 1),
        ] {
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path) would be sent without a token")
        }
    }
}
