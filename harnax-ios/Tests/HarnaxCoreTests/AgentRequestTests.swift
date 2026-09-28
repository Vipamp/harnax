import XCTest
@testable import HarnaxCore

/// What the three agent calls put on the wire. The server picks the request class off an
/// `EXISTING_PROPERTY` discriminator (`AgentRequest.kt:17-26`), so a body without `type` is a 400.
final class AgentRequestTests: XCTestCase {
    private func json(_ value: Encodable) throws -> [String: JSONValue] {
        try JSONDecoder().decode([String: JSONValue].self, from: JSONEncoder().encode(value))
    }

    func testChatBodyNamesItsTypeAndLeavesOptionalFieldsExplicit() throws {
        let body = try json(ChatAgentRequest(sessionId: "s-1", message: "翻译这句话"))

        XCTAssertEqual(body["type"], .string("CHAT"))
        XCTAssertEqual(body["sessionId"], .string("s-1"))
        XCTAssertEqual(body["message"], .string("翻译这句话"))
        XCTAssertEqual(body["imageUrls"], .array([]))
        XCTAssertEqual(body["requestId"], .string(""))
        XCTAssertNil(body["userId"] ?? nil, "an unset optional drops the key, which the server reads as null")
    }

    /// Images travel as base64 data URLs; a remote URL would be read as a sandbox path by the backend.
    func testChatBodyCarriesImageDataUrlsAsGiven() throws {
        let data = "data:image/png;base64,iVBORw0KGgo="
        let body = try json(ChatAgentRequest(sessionId: "s-1", message: "看这张图", imageUrls: [data]))

        XCTAssertEqual(body["imageUrls"], .array([.string(data)]))
    }

    func testCommandBodySendsTheServerEnumName() throws {
        let body = try json(CommandAgentRequest(sessionId: "s-1", command: .permission, args: "BYPASS"))

        XCTAssertEqual(body["type"], .string("COMMAND"))
        XCTAssertEqual(body["command"], .string("PERMISSION"))
        XCTAssertEqual(body["args"], .string("BYPASS"))
    }

    func testInterruptAndRefreshAreNamedAsTheCommandTypeEnum() throws {
        let body = try json(CommandAgentRequest(sessionId: "s-1", command: .interrupt))

        XCTAssertEqual(body["command"], .string("INTERRUPT"))
        XCTAssertEqual(body["args"], .string(""))
    }

    func testConfirmBodyCanAnswerOneMemberRunPerTool() throws {
        let body = try json(ConfirmAgentRequest(
            sessionId: "s-1",
            isConfirmed: true,
            toolInfoList: [.init(toolId: "t-1", toolName: "bash")],
            toolResults: [.init(toolId: "t-1", toolName: "bash", confirmed: true, alwaysAllow: true)],
            childRunId: "run-3"
        ))

        XCTAssertEqual(body["type"], .string("CONFIRM"))
        XCTAssertEqual(body["isConfirmed"], .boolean(true))
        XCTAssertEqual(body["childRunId"], .string("run-3"))
        let decisions = try XCTUnwrap(body["toolResults"])
        guard case let .array(entries) = decisions, case let .object(first)? = entries.first else {
            return XCTFail("expected one per-tool decision")
        }
        XCTAssertEqual(first["toolId"], .string("t-1"))
        XCTAssertEqual(first["alwaysAllow"], .boolean(true))
    }

    /// Bulk mode is the legacy shape: no per-tool list, no run to resume.
    func testConfirmBodyDefaultsToBulkMode() throws {
        let body = try json(ConfirmAgentRequest(sessionId: "s-1", isConfirmed: false))

        XCTAssertEqual(body["isConfirmed"], .boolean(false))
        XCTAssertEqual(body["toolResults"], .array([]))
        XCTAssertEqual(body["toolInfoList"], .array([]))
        XCTAssertNil(body["childRunId"] ?? nil)
    }
}
