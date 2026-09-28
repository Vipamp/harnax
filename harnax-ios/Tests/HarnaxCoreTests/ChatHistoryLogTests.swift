import XCTest

@testable import HarnaxCore

/// The rows `GET /api/router/agent/chat/history/{sessionId}` answers.
///
/// Key names follow `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/chat/MessageLog.kt:26-64`
/// and the discriminator is the Kotlin enum's name (`role`), not a Jackson type tag — a row whose keys were
/// spelled from the web console's own state shape would still decode, it would just decode to blanks, so
/// every field is pinned to the wire name here.
final class ChatHistoryLogTests: XCTestCase {
    private func log(_ json: String) throws -> ChatHistoryLog {
        try JSONDecoder().decode(ChatHistoryLog.self, from: Data(json.utf8))
    }

    func testUserRowKeepsItsMessageAndStamp() throws {
        let decoded = try log(#"{"role":"USER","message":"翻译这段","timestamp":1762500000000,"source":null}"#)

        guard case let .user(message, timestamp, source) = decoded else {
            return XCTFail("expected a user row")
        }
        XCTAssertEqual(message, "翻译这段")
        XCTAssertEqual(timestamp, 1_762_500_000_000, "epoch milliseconds, not a formatted date")
        XCTAssertNil(source)
        XCTAssertEqual(decoded.roleKey, "user")
    }

    func testAssistantRowKeepsThinkingTextAndCalls() throws {
        let decoded = try log("""
        {"role":"ASSISTANT","thinking":"先看语言","text":"译文如下","toolUseLog":\
        [{"name":"shell","input":{"command":"ls","member_agent_id":9}},{"name":"team_delegate",\
        "input":{"member_agent_id":"7","task":"查一下"}}],"timestamp":1762500001000,"source":null}
        """)

        guard case let .assistant(thinking, text, calls, _, _) = decoded else {
            return XCTFail("expected an assistant row")
        }
        XCTAssertEqual(thinking, "先看语言")
        XCTAssertEqual(text, "译文如下")
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls[0].name, "shell")
        XCTAssertEqual(calls[0].input["command"], .string("ls"))
        // The model controls these values: the same field arrives as a number in one run and a quoted
        // string in another, so neither is coerced on the way in.
        XCTAssertEqual(calls[0].input["member_agent_id"], .number(9))
        XCTAssertEqual(calls[1].input["member_agent_id"], .string("7"))
    }

    func testToolRowKeepsItsNameAndResultText() throws {
        let decoded = try log(#"{"role":"TOOL","name":"shell","result":"a.md\nb.md","timestamp":1762500002000,"source":null}"#)

        guard case let .tool(name, result, _, _) = decoded else { return XCTFail("expected a tool row") }
        XCTAssertEqual(name, "shell")
        XCTAssertEqual(result, "a.md\nb.md")
    }

    func testSystemRowIsRecognised() throws {
        let decoded = try log(#"{"role":"SYSTEM","message":"上下文已压缩","timestamp":1762500003000,"source":null}"#)

        guard case let .system(message, _, _) = decoded else { return XCTFail("expected a system row") }
        XCTAssertEqual(message, "上下文已压缩")
    }

    /// A member row carries the merge marker the server stamps on it (`TeamHistoryReplay.kt:81-82`), which is
    /// what tells the client whose bubble the row belongs to.
    func testMemberRowKeepsItsSource() throws {
        let decoded = try log("""
        {"role":"ASSISTANT","thinking":"","text":"成员结论","toolUseLog":[],"timestamp":1762500004000,\
        "source":{"teamId":3,"teamName":"翻译组","memberAgentId":11,"memberAgentName":"日志专家",\
        "childRunId":"team-9-m11","childSessionId":"team-9-m11"}}
        """)

        XCTAssertEqual(decoded.source?.memberAgentName, "日志专家")
        XCTAssertEqual(decoded.source?.childRunId, "team-9-m11")
        XCTAssertTrue(decoded.isMemberOutput)
    }

    func testCallInputOfAnyShapeNeverLosesTheCall() throws {
        let decoded = try log(#"{"role":"ASSISTANT","toolUseLog":[{"name":"shell"}]}"#)

        guard case let .assistant(_, _, calls, _, _) = decoded else { return XCTFail("expected an assistant row") }
        XCTAssertEqual(calls.count, 1, "a call with no readable arguments is still a call")
        XCTAssertEqual(calls[0].input, [:])
    }

    /// A half-shaped source marker only costs the row its bubble: reading it as absent would file a member's
    /// text under the lead instead.
    func testUnreadableSourceLeavesTheRowButDropsTheMarker() throws {
        let decoded = try log(#"{"role":"USER","message":"你好","timestamp":1,"source":{"teamId":3}}"#)

        guard case let .user(message, _, source) = decoded else { return XCTFail("expected a user row") }
        XCTAssertEqual(message, "你好")
        XCTAssertNil(source)
    }

    /// Every content column is optional on this side: the console reads `log.message || ''`
    /// (`ChatWindow.tsx:782`), so a row that names no message is blank rather than undecodable.
    func testRoleAloneDecodes() throws {
        let decoded = try log(#"{"role":"ASSISTANT"}"#)

        guard case let .assistant(thinking, text, calls, timestamp, source) = decoded else {
            return XCTFail("expected an assistant row")
        }
        XCTAssertEqual(thinking, "")
        XCTAssertEqual(text, "")
        XCTAssertTrue(calls.isEmpty)
        XCTAssertNil(timestamp)
        XCTAssertNil(source)
    }

    func testStampOfAnotherTypeReadsAsAbsent() throws {
        let decoded = try log(#"{"role":"USER","message":"你好","timestamp":"1762500000000"}"#)

        XCTAssertNil(decoded.timestamp, "the column is a Long; a string stamp is not a guess we make")
    }

    func testRoleIsCaseInsensitive() throws {
        let decoded = try log(#"{"role":"user","message":"你好"}"#)

        guard case .user = decoded else { return XCTFail("expected a user row") }
    }

    /// A role this build has no branch for must not fail the list: the endpoint answers one array for the
    /// whole session, so one unknown row would otherwise blank the screen.
    func testUnknownRoleDecodesAsUnknown() throws {
        let decoded = try log(#"{"role":"SUMMARY","message":"压缩摘要"}"#)

        XCTAssertEqual(decoded, .unknown)
        XCTAssertNil(decoded.roleKey)
    }

    func testAbsentRoleDecodesAsUnknown() throws {
        XCTAssertEqual(try log(#"{"message":"没有角色"}"#), .unknown)
    }

    /// The endpoint is `ResultVo<List<Any>>` — the router passes the agent-service list through untouched
    /// (`AgentProxyController.kt:144-152`), including an empty session as an empty array.
    func testWholeEnvelopeKeepsRowOrder() throws {
        let envelope = try JSONDecoder().decode(
            Envelope<[ChatHistoryLog]>.self,
            from: Data("""
            {"code":200,"message":"ok","data":[
                {"role":"USER","message":"第一条","timestamp":100},
                {"role":"ASSISTANT","thinking":"","text":"答一","toolUseLog":[],"timestamp":200},
                {"role":"TOOL","name":"shell","result":"ok","timestamp":300},
                {"role":"ASSISTANT","thinking":"","text":"答二","toolUseLog":[],"timestamp":150},
                {"role":"SUMMARY","message":"以后再说","timestamp":400}
            ],"timestamp":1762500005000,"isSuccess":true}
            """.utf8)
        )

        let logs = try XCTUnwrap(envelope.data)
        XCTAssertEqual(logs.count, 5)
        XCTAssertEqual(logs.map(\.roleKey), ["user", "assistant", "tool", "assistant", nil])
        XCTAssertEqual(logs[3].timestamp, 150, "the array order is the conversation order; stamps do not sort it")
    }

    func testEmptySessionIsAnEmptyListNotAnError() throws {
        let envelope = try JSONDecoder().decode(
            Envelope<[ChatHistoryLog]>.self,
            from: Data(#"{"code":200,"message":"ok","data":[],"timestamp":1}"#.utf8)
        )

        XCTAssertEqual(envelope.data, [])
    }
}
