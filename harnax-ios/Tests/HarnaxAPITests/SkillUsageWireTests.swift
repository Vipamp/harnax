import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The skill-usage read: one route, and the reason the page exists is the part most likely to be lost in
/// transport — the rows that got nothing.
///
/// The shape worth pinning is the family the path belongs to (`SkillUsageController.kt:23,31`). It is **not**
/// under `/api/admin/skills/…`, whose `{id}` is typed `Long`: a `skills/usage` segment would be matched by that
/// route and fail to parse (`SkillEndpoint.swift`'s `/{id}` reads), so the answer arrives as an error that
/// looks like a broken id rather than a wrong path.
///
/// What the payload says: `rows` includes skills with no events at all
/// (`SkillUsageSummaryResponse.kt:6-14`), because the question this page answers is which skill never gets
/// loaded, and an aggregate grouped over the event stream would omit exactly those rows — leaving an operator
/// unable to tell「unused」from「unknown」.
final class SkillUsageWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func query(_ endpoint: Endpoint) -> [String] {
        endpoint.query.map { "\($0.name)=\($0.value ?? "")" }
    }

    // MARK: - the one route

    func testTheSummaryIsAGetOnItsOwnFamily() {
        let endpoint = SkillUsageEndpoint.summary(days: 30)
        XCTAssertEqual(endpoint.method, .get)
        XCTAssertEqual(endpoint.path, "/api/admin/skill-usage/summary")
        XCTAssertFalse(
            endpoint.path.hasPrefix("/api/admin/skills/"),
            "that family's {id} is a Long and would swallow this segment"
        )
        XCTAssertNil(endpoint.body)
        XCTAssertNil(endpoint.url(baseURL: admin)?.fragment)
    }

    /// The window always goes out rather than being left to the server's 30 (`SkillUsageController.kt:37`):
    /// the response echoes the window it answered, so the screen can check its own label against that echo
    /// instead of assuming a default it never asked for.
    func testTheWindowIsAlwaysSent() {
        XCTAssertEqual(query(SkillUsageEndpoint.summary(days: 7)), ["days=7"])
        XCTAssertEqual(
            SkillUsageEndpoint.summary(days: 7).url(baseURL: admin)?.absoluteString,
            "\(admin)/api/admin/skill-usage/summary?days=7"
        )
    }

    /// The console's three picks are the app's three picks too (`harnax-webui/src/pages/skill/usage.tsx:23-25`),
    /// and a window the route would have to clamp back is not offered at all.
    func testTheThreeWindowsTheConsoleUses() {
        for days in [7, 30, 90] {
            XCTAssertEqual(query(SkillUsageEndpoint.summary(days: days)), ["days=\(days)"])
        }
    }

    /// A zero or negative window asks the server to count backwards from itself, which is not what a screen
    /// means: the same clamp the route applies (`:37`, `DEFAULT_WINDOW_DAYS`) is applied here so one request
    /// shape leaves the app.
    func testAnEmptyWindowBecomesOneDayRatherThanZero() {
        for asked in [0, -5] {
            XCTAssertEqual(
                query(SkillUsageEndpoint.summary(days: asked)), ["days=1"],
                "\(asked) would ask for a window that counts from the future"
            )
        }
    }

    func testTheRouteIsAnAuthenticatedAdminReadWithNoTenantParameter() {
        let endpoint = SkillUsageEndpoint.summary(days: 30)
        if case .admin = endpoint.base {} else { XCTFail("\(endpoint.path) would go to the router host") }
        XCTAssertTrue(endpoint.authenticated)
        XCTAssertTrue(endpoint.sendsTenantHeader, "the tenant comes from the header alone (:20-24, :36-39)")
        XCTAssertFalse(endpoint.path.lowercased().contains("tenant"))
        XCTAssertFalse(endpoint.query.contains { $0.name.lowercased().contains("tenant") })
    }

    // MARK: - what the answer decodes into

    /// Both counters on a row and the roll-up beside them: a skill loaded but never followed is a different
    /// finding from one that was neither, and only the second is what「只看没用过的」keeps.
    func testTheSummaryKeepsTheZeroRowsItIsSupposedToShow() throws {
        let envelope = """
        {"code":200,"message":"success","data":\
        {"days":30,"since":"2026-09-10T00:00:00","totalSkills":3,"zeroUseCount":1,\
        "totalViews":41,"totalUses":9,"rows":[\
        {"skillId":11,"name":"invoice-pdf","repositoryId":4,"repositoryName":"ops","status":1,\
        "origin":"agent_promoted","viewCount":30,"useCount":7,"lastUsedAt":"2026-10-08T10:00:00"},\
        {"skillId":12,"name":"csv-clean","repositoryId":4,"repositoryName":"ops","status":1,\
        "origin":"human","viewCount":11,"useCount":0},\
        {"skillId":13,"name":"never-loaded","repositoryId":9,"status":0,"origin":"human",\
        "viewCount":0,"useCount":0}]},\
        "timestamp":1790592457109,"isSuccess":true}
        """
        let summary = try JSONDecoder().decode(
            Envelope<SkillUsageSummary>.self, from: Data(envelope.utf8)
        ).data!

        XCTAssertEqual(summary.days, 30, "the window the server actually answered")
        XCTAssertEqual(summary.rows.count, 3, "a skill with no events still gets a row")
        XCTAssertEqual(summary.usedSkillCount, 2, "the complement the page shows beside the zero count")

        XCTAssertTrue(summary.rows[0].isEnabled)
        XCTAssertTrue(summary.rows[0].isAgentPromoted)
        XCTAssertFalse(summary.rows[0].isUnused)

        XCTAssertFalse(summary.rows[1].isUnused, "loaded but never followed is a different finding")

        let unused = summary.rows[2]
        XCTAssertTrue(unused.isUnused)
        XCTAssertFalse(unused.isEnabled, "the 0/1 column convention, not a boolean")
        XCTAssertNil(unused.repositoryName, "admin drops a null key rather than sending one")
        XCTAssertNil(unused.lastUsedAt)
    }

    /// The two instant columns on this route are `LocalDateTime` (`SkillUsageSummaryResponse.kt:21`, `:70`),
    /// unlike the call-metrics family where the service formats them — so they arrive in Jackson's ISO form.
    /// What is pinned here is that form; the screen cuts only the `T` out of it, which `SkillUsageViewModelTests`
    /// pins from the other side.
    func testTheWindowOpensInTheIsoFormTheServerWrites() throws {
        let summary = try JSONDecoder().decode(
            Envelope<SkillUsageSummary>.self,
            from: Data(#"""
                {"code":200,"message":"success","data":{"days":7,"since":"2026-10-03T00:00:00","totalSkills":0,
                   "zeroUseCount":0,"totalViews":0,"totalUses":0,"rows":[]},"timestamp":1,"isSuccess":true}
                """#.utf8)
        ).data!
        XCTAssertEqual(summary.since, "2026-10-03T00:00:00")
        XCTAssertEqual(summary.rows, [], "an empty table is an empty array, not a missing key")
        XCTAssertEqual(summary.usedSkillCount, 0)
    }

    /// A tenant with no skills at all answers zero and no rows; nothing here may divide by the total.
    func testATenantWithNoSkillsDecodesAsZeroRatherThanFailing() throws {
        let summary = try JSONDecoder().decode(
            Envelope<SkillUsageSummary>.self,
            from: Data(#"""
                {"code":200,"message":"success","data":{"days":30,"totalSkills":0,"zeroUseCount":0,"totalViews":0,
                   "totalUses":0,"rows":[]},"timestamp":1,"isSuccess":true}
                """#.utf8)
        ).data!
        XCTAssertEqual(summary.totalSkills, 0)
        XCTAssertTrue(summary.rows.isEmpty)
        XCTAssertEqual(summary.usedSkillCount, 0)
    }
}
