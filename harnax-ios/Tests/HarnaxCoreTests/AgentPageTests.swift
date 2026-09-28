import XCTest

@testable import HarnaxCore

final class AgentPageTests: XCTestCase {
    func testEmptyPageFromRealCaptureDecodes() throws {
        let page = try Fixture.decode(Envelope<Page<AgentSummary>>.self, "agents-page-empty").data!
        XCTAssertEqual(page.pageNum, 1)
        XCTAssertEqual(page.pageSize, 2)
        XCTAssertEqual(page.total, 0)
        XCTAssertTrue(page.records.isEmpty)
    }

    /// Row 2 of the fixture omits `modelName` / `isPublic` / `sessionCount` the way the backend
    /// really omits nulls, and keeps `description: null` to cover the other shape.
    func testSparseRowsDecodeWithoutDropping() throws {
        let page = try Fixture.decode(Envelope<Page<AgentSummary>>.self, "agents-page-two-rows").data!
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)

        let first = page.records[0]
        XCTAssertEqual(first.id, 11)
        XCTAssertEqual(first.name, "翻译助手")
        XCTAssertEqual(first.modelName, "qwen-plus")
        XCTAssertTrue(first.isEnabled)
        XCTAssertTrue(first.isShared)
        XCTAssertEqual(first.sessionCount, 3)

        let second = page.records[1]
        XCTAssertEqual(second.id, 12)
        XCTAssertNil(second.description)
        XCTAssertNil(second.modelName)
        XCTAssertNil(second.isPublic)
        XCTAssertNil(second.sessionCount)
        XCTAssertFalse(second.isEnabled)
    }
}
