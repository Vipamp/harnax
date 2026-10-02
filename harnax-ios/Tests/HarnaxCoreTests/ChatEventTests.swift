import XCTest
@testable import HarnaxCore

/// The eight frames the agent can send (`ChatEvent.kt:23-32`). Everything the chat screen draws is a fold
/// over these, so the discriminator and each payload's required keys are pinned here.
///
/// Provenance (`DESIGN.md:239` asks for it): these payloads are transcribed from the DTO the router
/// actually serves — `com.agnetix.harnax.agent.protocol.ChatEvent`, the import at
/// `AgentProxyController.kt:5` — not lifted off a live stream. Re-read against that file field by field on
/// 2026-09-30. The `harnax-client-common` copy of the same eight names is a different consumer and carries
/// no `attachments`/`source`, so it is not the wire shape.
final class ChatEventTests: XCTestCase {
    private func event(_ json: String) throws -> ChatEvent {
        try ChatEvent.decode(json)
    }

    func testTextFrameCarriesItsSegmentAndClosingFlag() throws {
        let decoded = try event(#"{"eventType":"TextEvent","message":"你好","isLast":false,"source":null}"#)

        guard case let .text(delta) = decoded else { return XCTFail("expected a text frame") }
        XCTAssertEqual(delta.message, "你好")
        XCTAssertFalse(delta.isLast)
        XCTAssertNil(delta.source)
    }

    /// Thinking shares the text shape; which segment it accumulates into is the frame's kind, not a field.
    func testThinkingFrameIsNotText() throws {
        let decoded = try event(#"{"eventType":"ThinkingEvent","message":"想一下","isLast":true,"source":null}"#)

        guard case let .thinking(delta) = decoded else { return XCTFail("expected a thinking frame") }
        XCTAssertTrue(delta.isLast)
    }

    func testToolCallKeepsArgumentsOfEveryJSONShape() throws {
        let decoded = try event("""
        {"eventType":"CallToolEvent","toolId":"t-1","toolName":"write_file",\
        "arguments":{"path":"/workspace/a.md","bytes":12,"overwrite":true,"meta":null,\
        "tags":["a","b"],"limits":{"max":4}},"tokenUsage":null,"source":null}
        """)

        guard case let .toolCall(call) = decoded else { return XCTFail("expected a tool call") }
        XCTAssertEqual(call.toolId, "t-1")
        XCTAssertEqual(call.arguments["path"], .string("/workspace/a.md"))
        XCTAssertEqual(call.arguments["bytes"], .number(12))
        XCTAssertEqual(call.arguments["overwrite"], .boolean(true))
        XCTAssertEqual(call.arguments["meta"], .null)
        XCTAssertEqual(call.arguments["tags"], .array([.string("a"), .string("b")]))
        XCTAssertEqual(
            call.arguments["limits"],
            .object(["max": .number(4)])
        )
    }

    /// `success: false` is what turns a result card into a rejected one.
    func testToolResultCarriesItsOwnVerdict() throws {
        let decoded = try event(#"""
        {"eventType":"ToolResultEvent","toolId":"t-1","toolName":"bash","message":"exit 1","success":false,"source":null}
        """#)

        guard case let .toolResult(result) = decoded else { return XCTFail("expected a tool result") }
        XCTAssertEqual(result.message, "exit 1")
        XCTAssertFalse(result.success)
    }

    func testToolConfirmListsEveryPendingTool() throws {
        let decoded = try event("""
        {"eventType":"ToolConfirmEvent","pendingCallTools":[{"toolId":"t-1","toolName":"bash",\
        "arguments":{"command":"rm -rf /tmp/x"},"isDangerous":true}],"source":null}
        """)

        guard case let .toolConfirm(confirm) = decoded else { return XCTFail("expected a confirmation") }
        XCTAssertEqual(confirm.pendingCallTools.count, 1)
        let pending = try XCTUnwrap(confirm.pendingCallTools.first)
        XCTAssertTrue(pending.isDangerous)
        XCTAssertEqual(pending.arguments["command"], .string("rm -rf /tmp/x"))
    }

    /// The end frame is the only place the run's produced files are reported.
    func testEndFrameCarriesWorkspaceAttachments() throws {
        let decoded = try event("""
        {"eventType":"EndEvent","attachments":[{"fileId":"f-1","fileName":"report.md",\
        "filePath":"/workspace/output/report.md","fileSize":2048,"mimeType":"text/markdown",\
        "url":"https://minio.example/f-1","objectKey":"k-1"}],"source":null}
        """)

        guard case let .end(end) = decoded else { return XCTFail("expected an end frame") }
        let file = try XCTUnwrap(end.attachments.first)
        XCTAssertEqual(file.fileSize, 2048)
        XCTAssertEqual(file.objectKey, "k-1")
    }

    func testErrorFrameKeepsCodeAndMessage() throws {
        let decoded = try event(#"{"eventType":"ErrorEvent","code":"AGENT_MODEL_NOT_CONFIGURED","message":"未配置模型","source":null}"#)

        guard case let .failure(failure) = decoded else { return XCTFail("expected an error frame") }
        XCTAssertEqual(failure.code, "AGENT_MODEL_NOT_CONFIGURED")
        XCTAssertEqual(failure.message, "未配置模型")
    }

    /// A parked member run produces nothing but these, and the router kills a silent stream after 120s. A
    /// consumer that rendered or ended the turn on this frame would break the run it was waiting for.
    func testKeepAliveCarriesNoContentButCanNameItsRun() throws {
        let decoded = try event("""
        {"eventType":"KeepAliveEvent","source":{"teamId":7,"teamName":"翻译组","memberAgentId":3,\
        "memberAgentName":"审校","childRunId":"run-3","childSessionId":"s-3"}}
        """)

        guard case let .keepAlive(alive) = decoded else { return XCTFail("expected a keepalive") }
        XCTAssertEqual(alive.source?.childRunId, "run-3")
        XCTAssertEqual(alive.source?.memberAgentName, "审校")
    }

    /// Which run to resume is only in the source, and several members of one session can be waiting at once.
    func testMemberSourceIsAttachedToTheFrameThatNeedsAnswering() throws {
        let decoded = try event("""
        {"eventType":"ToolConfirmEvent","pendingCallTools":[],"source":{"teamId":7,"teamName":"翻译组",\
        "memberAgentId":3,"memberAgentName":"审校","childRunId":"run-9","childSessionId":"s-9"}}
        """)

        guard case let .toolConfirm(confirm) = decoded else { return XCTFail("expected a confirmation") }
        XCTAssertEqual(confirm.source?.childRunId, "run-9")
        XCTAssertEqual(confirm.source?.teamId, 7)
    }

    func testFramesWithNoTypeOrAnUnknownTypeAreRefused() {
        XCTAssertThrowsError(try event(#"{"message":"x"}"#))
        XCTAssertThrowsError(try event(#"{"eventType":"TomorrowEvent"}"#))
    }
}
