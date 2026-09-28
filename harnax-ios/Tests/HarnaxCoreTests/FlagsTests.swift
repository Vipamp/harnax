import XCTest
@testable import HarnaxCore

final class FlagsTests: XCTestCase {
    func testOnlyOneMeansOn() {
        let absent: Int? = nil
        let off: Int? = 0
        let on: Int? = 1
        // A flag the backend has never heard of stays off rather than throwing.
        let unknown: Int? = 7
        XCTAssertFalse(absent.hxFlag)
        XCTAssertFalse(off.hxFlag)
        XCTAssertTrue(on.hxFlag)
        XCTAssertFalse(unknown.hxFlag)
    }

    func testRequestSideWritesTheIntegerForm() {
        XCTAssertEqual(true.hxInt, 1)
        XCTAssertEqual(false.hxInt, 0)
    }

    func testThinkingModeDerivesSupportReasoning() {
        XCTAssertEqual(ThinkingMode.off.supportReasoning, 0)
        XCTAssertEqual(ThinkingMode.optional.supportReasoning, 1)
        // Both "optional" and "required" reason, so both set the legacy capability bit.
        XCTAssertEqual(ThinkingMode.required.supportReasoning, 1)
    }

    func testLegacyRowsFallBackOntoTheReasoningBit() {
        XCTAssertEqual(ThinkingMode.resolve(stored: 2, supportReasoning: 0), .required)
        XCTAssertEqual(ThinkingMode.resolve(stored: nil, supportReasoning: 1), .optional)
        XCTAssertEqual(ThinkingMode.resolve(stored: nil, supportReasoning: 0), .off)
        XCTAssertEqual(ThinkingMode.resolve(stored: nil, supportReasoning: nil), .off)
        // An out-of-range stored value must not invent a mode the form cannot render.
        XCTAssertEqual(ThinkingMode.resolve(stored: 9, supportReasoning: 0), .off)
        XCTAssertEqual(ThinkingMode.resolve(stored: 9, supportReasoning: 1), .optional)
    }
}

final class RelatedEntitiesTests: XCTestCase {
    func testAgentRowReadsTheIntegerStatus() throws {
        let data = Data(#"[{"agentId":7,"agentName":"Support Desk","status":0}]"#.utf8)
        let rows = try JSONDecoder().decode([RelatedAgent].self, from: data)
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].agentId, 7)
        XCTAssertFalse(rows[0].isEnabled)
    }

    /// A row with a missing `agentName` still renders: the confirmation sheet lists sessions, and dropping
    /// one would understate what a disable reaches.
    func testSparseRowsDecode() throws {
        let data = Data(
            #"[{"sessionId":"chn-1","sourceType":"channel","sourceName":"WeChat"},{"sessionId":"ses-2","sourceType":"session","sourceName":"Web"}]"#.utf8
        )
        let sessions = try JSONDecoder().decode([RelatedSession].self, from: data)
        XCTAssertEqual(sessions.count, 2)
        XCTAssertTrue(sessions[0].isChannel)
        XCTAssertNil(sessions[0].agentName)
        XCTAssertFalse(sessions[1].isChannel)
    }

    func testRefreshVerdictIsPerSessionNotPerRequest() throws {
        let data = Data(
            """
            [{"sessionId":"a","success":true},{"sessionId":"b","success":false,"error":"agent offline"}]
            """.utf8
        )
        let outcomes = try JSONDecoder().decode([SessionRefreshOutcome].self, from: data)
        XCTAssertEqual(outcomes.map(\.failed), [false, true])
        XCTAssertEqual(outcomes[1].error, "agent offline")
    }

    func testRefreshBodyUsesTheBackendKey() throws {
        let data = try JSONEncoder().encode(SessionRefreshRequest(sessionIds: ["a", "b"]))
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(json.contains("\"sessionIds\""), json)
    }
}
