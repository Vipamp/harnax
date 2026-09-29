import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The nine scheduled-task routes, checked against the paths and query names the controller declares
/// (`harnax-scheduler/src/main/kotlin/com/agnetix/harnax/scheduler/controller/AgentTaskController.kt`).
///
/// Three of these cannot be copied from a neighbour, so each gets its own assertion instead of a table:
/// the toggle addresses the row *after* the verb (`POST /toggle/{id}`) while every other write in this domain
/// puts the bare id first, the page filter for the running flag is `taskStatus` while the toggle's 0/1 is
/// plain `status`, and the whole domain hangs off `/api/admin/agent-tasks` with the `/page` suffix owned by
/// this route alone.
final class AgentTaskEndpointTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func absolute(_ endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.absoluteString ?? "<no url>"
    }

    private func query(_ endpoint: Endpoint) -> [String] {
        endpoint.query.map { "\($0.name)=\($0.value ?? "")" }
    }

    // MARK: - the list read

    func testPageCarriesTheTwoPageParametersThenNameThenTaskStatus() {
        XCTAssertEqual(
            absolute(AgentTaskEndpoint.page(name: "日报", taskStatus: 1, num: 2, size: 20)),
            "\(admin)/api/admin/agent-tasks/page?pageNum=2&pageSize=20&name=%E6%97%A5%E6%8A%A5&taskStatus=1"
        )
    }

    /// `0` is the paused filter, and it is a value rather than an absence: dropping it because it is falsy
    /// would turn "show me the stopped tasks" back into "show me everything".
    func testZeroIsAStatusFilterWhileABlankNameIsLeftOff() {
        XCTAssertEqual(
            query(AgentTaskEndpoint.page(name: "   ", taskStatus: 0, num: 1, size: 10)),
            ["pageNum=1", "pageSize=10", "taskStatus=0"]
        )
    }

    /// With neither filter the URL is the two page parameters and nothing else — an empty `name=` would be a
    /// `LIKE '%%'` the server does not need spelled out for it.
    func testAnUnfilteredPageSendsOnlyTheTwoPageParameters() {
        let endpoint = AgentTaskEndpoint.page(name: nil, taskStatus: nil, num: 1, size: 20)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/page")
        XCTAssertEqual(query(endpoint), ["pageNum=1", "pageSize=20"])
    }

    /// `agentId` is a real parameter of this route (`AgentTaskController.kt:59`) that neither console exposes,
    /// so it must not leak in from the neighbouring domains' habit of filtering by owner.
    func testThePageSendsNoAgentFilter() {
        for status in [nil, 0, 1] as [Int?] {
            let names = AgentTaskEndpoint.page(name: "x", taskStatus: status, num: 1, size: 1).query.map(\.name)
            XCTAssertFalse(names.contains("agentId"), "\(names)")
            XCTAssertFalse(names.contains("status"), "the page flag is called taskStatus, not status")
        }
    }

    // MARK: - the writes

    /// `@PostMapping` on the class root, so a body and no path suffix at all.
    func testCreateIsARootPostWithTheDraftAsBody() throws {
        let endpoint = try AgentTaskEndpoint.create(
            AgentTaskDraft(
                name: "夜间汇总", agentId: 7, prompt: "总结今天的告警",
                cronExpression: "0 0 2 * * ?", concurrent: 1, timeoutSeconds: 300,
                description: nil, isPublic: 0
            )
        )
        XCTAssertEqual(endpoint.method, .post)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks")
        XCTAssertEqual(endpoint.query, [])
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: endpoint.body!) as? [String: Any])
        XCTAssertEqual(body.keys.sorted(), [
            "agentId", "concurrent", "cronExpression", "isPublic", "name", "prompt", "timeoutSeconds",
        ])
        XCTAssertEqual(body["agentId"] as? Int, 7)
        XCTAssertEqual(body["isPublic"] as? Int, 0, "the flag goes out as a number, never as a JSON boolean")
    }

    /// `@PutMapping("/{id}")`: the update body is partial by construction, so an unchanged field must be absent
    /// from the JSON rather than present as null — the service applies a field only when it arrives non-null
    /// (`AgentTaskCrudServiceImpl.kt:122-154`).
    func testUpdatePutsTheBareIdOnTheRootAndSendsOnlyWhatChanged() throws {
        let endpoint = try AgentTaskEndpoint.update(id: 41, AgentTaskChange(prompt: "新提示词", timeoutSeconds: 60))
        XCTAssertEqual(endpoint.method, .put)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/41")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: endpoint.body!) as? [String: Any])
        XCTAssertEqual(body.keys.sorted(), ["prompt", "timeoutSeconds"])
    }

    /// `taskStatus` is not in the update body at all (`AgentTaskUpdateRequest.kt` has no such field), which is
    /// why the list re-reads after a save: the route pauses the task on its own (`§3.2`).
    func testTheUpdateBodyCannotNameAStatus() throws {
        let json = String(data: try APIClient.encodeBody(AgentTaskChange(name: "n", isPublic: 1)), encoding: .utf8)!
        XCTAssertFalse(json.contains("taskStatus"), json)
        XCTAssertFalse(json.contains("agentName"), json)
    }

    /// The one route whose flag is bare `status`, and the one whose id rides after the verb.
    func testToggleIsAPostOnToggleSlashIdWithAStatusQuery() {
        let endpoint = AgentTaskEndpoint.toggle(id: 41, status: 0)
        XCTAssertEqual(endpoint.method, .post)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/toggle/41")
        XCTAssertEqual(query(endpoint), ["status=0"])
        XCTAssertNil(endpoint.body, "a body here would be ignored and the switch would flip to nothing")
    }

    /// `POST /{id}/trigger` shares its prefix with `PUT /{id}` and `DELETE /{id}`, so the suffix is the only
    /// thing separating "run it now" from "remove it".
    func testTriggerIsAPostOnTheIdPlusSuffix() {
        let endpoint = AgentTaskEndpoint.trigger(id: 41)
        XCTAssertEqual(endpoint.method, .post)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/41/trigger")
        XCTAssertEqual(endpoint.query, [])
        XCTAssertNil(endpoint.body)
    }

    func testDeleteIsADeleteOnTheBareId() {
        XCTAssertEqual(AgentTaskEndpoint.delete(id: 41).method, .delete)
        XCTAssertEqual(AgentTaskEndpoint.delete(id: 41).path, "/api/admin/agent-tasks/41")
    }

    /// `GET /agents` is the one route admin answers locally instead of forwarding, and it takes no parameter.
    func testAgentsIsAPublicShapelessGetWithNoQuery() {
        let endpoint = AgentTaskEndpoint.agents()
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/agents")
        XCTAssertEqual(endpoint.query, [])
    }

    // MARK: - the log routes

    /// `GET /{id}/logs`, scoped by the task in the path. The four optional filters arrive in the order the
    /// endpoint builds them, and the status one is plain `status` here while the task page calls the same idea
    /// `taskStatus` (`AgentTaskController.kt:58` against `:175`).
    func testTheLogPageIsScopedByTheTaskInThePathAndNamesItsFiltersTheServersWay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .autoupdatingCurrent
        let from = calendar.date(from: DateComponents(year: 2026, month: 9, day: 25, hour: 9, minute: 0, second: 3))
        let to = calendar.date(from: DateComponents(year: 2026, month: 9, day: 26, hour: 23, minute: 59, second: 59))
        let filter = AgentTaskLogFilter(state: .running, keyword: "晨报", from: from, to: to)
        XCTAssertEqual(
            query(AgentTaskEndpoint.logs(taskID: 41, filter: filter, num: 2, size: 10)),
            [
                "pageNum=2",
                "pageSize=10",
                "status=3",
                "startTimeFrom=2026-09-25 09:00:03",
                "startTimeTo=2026-09-26 23:59:59",
                "keyword=晨报",
            ],
            "the pair is startTimeFrom/startTimeTo in yyyy-MM-dd HH:mm:ss, and 运行中 is status 3"
        )
        XCTAssertEqual(
            AgentTaskEndpoint.logs(taskID: 41, filter: filter, num: 1, size: 10).path,
            "/api/admin/agent-tasks/41/logs"
        )
    }

    /// An unused filter is left off the query rather than sent blank: `keyword=` would be a `LIKE '%%'` and
    /// `status=` would be a number Spring has to fail to parse (`§5.3`).
    func testAnUnfilteredLogPageSendsOnlyTheTwoPageParameters() {
        let endpoint = AgentTaskEndpoint.logs(taskID: 41, filter: AgentTaskLogFilter(), num: 1, size: 10)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(query(endpoint), ["pageNum=1", "pageSize=10"])
    }

    /// A one-sided window is a real filter on this route, so each bound goes out on its own.
    func testEachWindowBoundGoesOutWhenOnlyOneWasChosen() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .autoupdatingCurrent
        let from = calendar.date(from: DateComponents(year: 2026, month: 9, day: 25, hour: 0, minute: 0, second: 0))
        let names = query(AgentTaskEndpoint.logs(
            taskID: 41,
            filter: AgentTaskLogFilter(from: from),
            num: 1,
            size: 10
        )).map { String($0.prefix(while: { $0 != "=" })) }
        XCTAssertEqual(names, ["pageNum", "pageSize", "startTimeFrom"])
    }

    /// `POST /logs/{logId}/stop` is the one route in the domain addressed by a log id, and it sits between the
    /// root and the verb — `/{id}/logs/{logId}/stop` would be a different path the controller never declares.
    func testTheStopIsAPostOnTheLogIdUnderTheLogsRootWithNothingElse() {
        let endpoint = AgentTaskEndpoint.stopLog(id: 901)
        XCTAssertEqual(endpoint.method, .post)
        XCTAssertEqual(endpoint.path, "/api/admin/agent-tasks/logs/901/stop")
        XCTAssertEqual(endpoint.query, [])
        XCTAssertNil(endpoint.body)
    }

    // MARK: - the set as a whole

    /// Every route in this file is a scheduler-admin forward on the same host behind the same token, including
    /// `/agents` — which is served locally but is still an `/api/admin` path.
    func testEveryRouteIsAnAuthenticatedAdminRouteUnderTheTaskRoot() throws {
        for endpoint in [
            AgentTaskEndpoint.page(name: nil, taskStatus: nil, num: 1, size: 10),
            try AgentTaskEndpoint.create(
                AgentTaskDraft(name: "n", agentId: 1, prompt: "p", cronExpression: "0 0 9 * * ?")
            ),
            try AgentTaskEndpoint.update(id: 1, AgentTaskChange(name: "n")),
            AgentTaskEndpoint.toggle(id: 1, status: 1),
            AgentTaskEndpoint.trigger(id: 1),
            AgentTaskEndpoint.delete(id: 1),
            AgentTaskEndpoint.agents(),
            AgentTaskEndpoint.logs(taskID: 1, filter: AgentTaskLogFilter(), num: 1, size: 10),
            AgentTaskEndpoint.stopLog(id: 1),
        ] {
            XCTAssertEqual(endpoint.base, .admin, endpoint.path)
            XCTAssertTrue(endpoint.authenticated, "\(endpoint.path) would be sent without a token")
            XCTAssertTrue(
                endpoint.path == AgentTaskEndpoint.root || endpoint.path.hasPrefix("\(AgentTaskEndpoint.root)/"),
                "\(endpoint.path) is outside the task root"
            )
        }
    }

    /// The seven task routes must not drift into the two log routes: `/logs/{logId}/stop` is addressed by a log
    /// id, and sending a task id there would stop whichever execution happens to own that row.
    func testNoTaskRouteIsALogRoute() {
        for path in [
            AgentTaskEndpoint.page(name: nil, taskStatus: nil, num: 1, size: 10).path,
            AgentTaskEndpoint.toggle(id: 1, status: 1).path,
            AgentTaskEndpoint.trigger(id: 1).path,
            AgentTaskEndpoint.delete(id: 1).path,
            AgentTaskEndpoint.agents().path,
        ] {
            XCTAssertFalse(path.contains("log"), "\(path)")
        }
    }

    /// An id reaching a path segment percent-encoded would mean a route addressed to `/agent-tasks/1%2F2`, and
    /// Spring would answer 400 for it; the domain only ever interpolates `Int64`, which has no such character.
    func testIdPathsArePlainNumbers() throws {
        XCTAssertEqual(try AgentTaskEndpoint.update(id: -9, AgentTaskChange(name: "n")).path, "/api/admin/agent-tasks/-9")
        XCTAssertEqual(AgentTaskEndpoint.delete(id: 9_000_000_000).path, "/api/admin/agent-tasks/9000000000")
    }
}
