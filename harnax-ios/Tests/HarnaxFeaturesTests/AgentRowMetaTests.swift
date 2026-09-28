import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

final class AgentRowMetaTests: XCTestCase {
    func testASpaceSeparatedLocalDateTimeLosesItsYear() throws {
        let agent = try AgentSummary.stub(["createTime": "2026-09-12 14:20:00"])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "09-12")
    }

    func testTheISOFormWithATSeparatorReadsTheSame() throws {
        let agent = try AgentSummary.stub(["createTime": "2026-09-12T14:20:00"])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "09-12")
    }

    func testADateWithMillisecondsIsStillParsed() throws {
        let agent = try AgentSummary.stub(["createTime": "2026-09-12T14:20:00.123"])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "09-12")
    }

    func testAnUnparsableTimestampIsLeftOutRatherThanGuessed() throws {
        for raw in ["12345", "2026-9-12", "not a date", ""] {
            let agent = try AgentSummary.stub(["createTime": raw])
            XCTAssertEqual(AgentRowMeta.monthDay(agent.createTime), nil, "\(raw) should not be shown")
        }
    }

    func testAMissingTimestampLeavesNoSeparatorBehind() throws {
        let agent = try AgentSummary.stub(["creator": "heqingsong"])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "heqingsong")
    }

    func testCreatorAndDayAndSessionsAreJoinedInOrder() throws {
        let agent = try AgentSummary.stub([
            "creator": "heqingsong",
            "createTime": "2026-09-12 14:20:00",
            "sessionCount": 3,
        ])
        XCTAssertEqual(
            AgentRowMeta.byline(for: agent),
            "heqingsong · 09-12 · " + hx("agent.sessionCount", 3)
        )
    }

    func testASessionCountOfZeroIsNotWorthALine() throws {
        let agent = try AgentSummary.stub(["creator": "admin", "sessionCount": 0])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "admin")
    }

    func testAnEmptyCreatorFieldDoesNotPrintANull() throws {
        let agent = try AgentSummary.stub(["creator": "", "createTime": "2026-09-12 10:00:00"])
        XCTAssertEqual(AgentRowMeta.byline(for: agent), "09-12")
    }
}
