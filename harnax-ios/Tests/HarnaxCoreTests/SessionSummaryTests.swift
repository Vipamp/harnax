import XCTest

@testable import HarnaxCore

/// The one shape three routes answer with, read through the tolerances the backend's serialisation forces.
///
/// Key names are `dto/SessionResponse.kt:10-66`'s own, and `mcpList`/`skillList` are the only two the server
/// cannot omit (`:52-54` give them an empty-list default), so they are on every row below.
final class SessionSummaryTests: XCTestCase {
    private func row(_ json: String) throws -> SessionSummary {
        try JSONDecoder().decode(
            Page<SessionSummary>.self,
            from: Data(#"{"pageNum":1,"pageSize":20,"total":1,"records":[\#(json)]}"#.utf8)
        ).records[0]
    }

    private func encodedKeys(_ request: SessionRenameRequest) throws -> [String] {
        let data = try JSONEncoder().encode(request)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        return object.keys.sorted()
    }

    func testFullRowDecodesEveryColumnTheRouteSends() throws {
        let session = try row("""
        {"id":21,"title":"翻译一组周报","sessionDescription":"把三条反馈翻成英文","sessionId":"web-3f2a9c41",\
        "agentId":11,"name":"Support Desk","description":"Answers product questions","modelId":3,\
        "modelName":"qwen3.7-max","modelPrice":15.0,"modelSupportReasoning":1,"modelThinkingMode":2,\
        "modelSupportInternet":0,"modelSupportVision":null,"enableThink":1,"enableSearch":0,"enablePlan":0,\
        "permissionMode":"ACCEPT_EDITS","owner":"admin","status":1,"isPublic":1,"creator":"admin",\
        "createTime":"2026-09-26 10:24:31","updateTime":"2026-09-27 08:02:11",\
        "mcpList":[{"mcpId":4,"mcpName":"amap-maps","mcpDescription":"Maps and routing"}],\
        "skillList":[{"repositoryId":2,"repositoryName":"qoder-skills","skillId":5,"skillName":"Glossary",\
        "skillDescription":"领域术语表"}]}
        """)

        XCTAssertEqual(session.id, 21)
        XCTAssertEqual(session.sessionId, "web-3f2a9c41")
        XCTAssertEqual(session.agentId, 11)
        XCTAssertNil(session.teamId)
        XCTAssertEqual(session.executorName, "Support Desk")
        XCTAssertEqual(session.detail, "把三条反馈翻成英文")
        XCTAssertEqual(session.modelName, "qwen3.7-max")
        XCTAssertEqual(session.permissionMode, "ACCEPT_EDITS")
        XCTAssertTrue(session.isEnabled)
        XCTAssertTrue(session.isShared)
        XCTAssertEqual(session.mcpNames, ["amap-maps"])
        XCTAssertEqual(session.skillNames, ["Glossary"])
    }

    /// `default-property-inclusion: non_null` (`application.yml:25`) means a null arrives as an absent key,
    /// and a row that loses four columns to it must still decode — one dropped row would silently shorten
    /// the list the user is scanning.
    func testServerDroppedNullsDecodeAsAbsentKeysNotAsAFailure() throws {
        let session = try row(#"{"title":"只有标题","mcpList":[],"skillList":[]}"#)

        XCTAssertNil(session.id)
        XCTAssertNil(session.sessionDescription)
        XCTAssertNil(session.modelName)
        XCTAssertNil(session.creator)
        XCTAssertEqual(session.displayName, "只有标题")
        XCTAssertNil(session.detail)
        XCTAssertNil(session.executorName)
        XCTAssertTrue(session.isEnabled, "no status column means the column's own default of 1")
        XCTAssertFalse(session.isShared)
        XCTAssertFalse(session.isTeamConversation)
    }

    /// `null` in the body and an explicit zero are different on the wire and must not read the same here:
    /// only a zero switches a conversation off.
    func testExplicitZeroIsNotTheSameAsAnOmittedColumn() throws {
        let stopped = try row(#"{"title":"t","status":0,"isPublic":0,"mcpList":[],"skillList":[]}"#)
        XCTAssertFalse(stopped.isEnabled)
        XCTAssertFalse(stopped.isShared)

        let unset = try row(#"{"title":"t","status":null,"isPublic":null,"mcpList":[],"skillList":[]}"#)
        XCTAssertTrue(unset.isEnabled)
        XCTAssertFalse(unset.isShared)
    }

    /// A team conversation has no agent row behind it (`SessionServiceImpl.kt:199-206`), and its `name`
    /// column holds the team's name rather than an agent's — the same column for two different objects, so
    /// only `teamId` tells them apart.
    func testTeamRowIsToldApartFromAnAgentRow() throws {
        let team = try row(#"{"id":22,"teamId":5,"name":"Research Desk","mcpList":[],"skillList":[]}"#)
        XCTAssertTrue(team.isTeamConversation)
        XCTAssertEqual(team.executorName, "Research Desk")

        let agent = try row(#"{"id":21,"agentId":11,"name":"Support Desk","mcpList":[],"skillList":[]}"#)
        XCTAssertFalse(agent.isTeamConversation)
        XCTAssertEqual(agent.agentId, 11)
    }

    /// Both binding lists are denormalised onto the row (`SessionServiceImpl.kt:86-120`), and a bound
    /// executor can have lost its name in the meantime. A blank entry counts as no entry, because the chip
    /// row shows these as a count.
    func testBindingNamesDropBlankAndMissingEntries() throws {
        let session = try row("""
        {"mcpList":[{"mcpId":4,"mcpName":"amap-maps"},{"mcpId":5,"mcpName":"  "},{"mcpId":6}],\
        "skillList":[{"skillId":5,"skillName":"Glossary"},{"skillId":6,"skillName":null},{"skillName":"  "},\
        {"skillId":7,"skillName":"Polish","repositoryId":2,"repositoryName":"qoder-skills"}]}
        """)

        XCTAssertEqual(session.mcpNames, ["amap-maps"])
        XCTAssertEqual(session.skillNames, ["Glossary", "Polish"])
        XCTAssertEqual(session.mcpList.count, 3, "the row keeps what it was sent; only the names filter down")
    }

    // MARK: - the rename body

    /// The route takes a whole `SessionCreateRequest`, so the body is built from the row rather than from
    /// the field the sheet edited (`SessionController.kt:91-109`).
    func testRenameSendsTheTrimmedTitleAndTheRowItRestores() throws {
        let session = try row(#"{"id":21,"title":"旧标题","sessionDescription":"原说明","agentId":11,"mcpList":[],"skillList":[]}"#)
        let request = try XCTUnwrap(SessionRenameRequest(renaming: session, to: "  新标题 \n"))

        XCTAssertEqual(request.title, "新标题")
        XCTAssertEqual(request.sessionDescription, "原说明")
        XCTAssertEqual(request.agentId, 11)
        XCTAssertEqual(try encodedKeys(request), ["agentId", "sessionDescription", "title"])
    }

    /// The update route runs no bean validation (`SessionController.kt:95` has no `@Validated`), so a blank
    /// title would be written as-is and render as a card with no name. Refusing to build the body is the
    /// only place that can stop it.
    func testBlankTitleBuildsNoBody() throws {
        let session = try row(#"{"id":21,"title":"旧标题","mcpList":[],"skillList":[]}"#)
        XCTAssertNil(SessionRenameRequest(renaming: session, to: "   \n "))
        XCTAssertNil(SessionRenameRequest(renaming: session, to: ""))
    }

    /// A row with no description yet sends an empty one, which is what the DTO's own default makes it
    /// (`dto/SessionCreateRequest.kt:20`); a team row names no agent, and the key is left off rather than
    /// sent as null so the server keeps the row's own `agent_id` — which for a team conversation is NULL
    /// either way, and naming an agent there is refused outright (`SessionServiceImpl.kt:249-251`).
    func testMissingDescriptionAndTeamRowProduceAWholeBody() throws {
        let bare = try row(#"{"id":23,"title":"t","mcpList":[],"skillList":[]}"#)
        let bareRequest = try XCTUnwrap(SessionRenameRequest(renaming: bare, to: "新"))
        XCTAssertEqual(bareRequest.sessionDescription, "")
        XCTAssertNil(bareRequest.agentId)
        XCTAssertEqual(try encodedKeys(bareRequest), ["sessionDescription", "title"])

        let team = try row(#"{"id":22,"title":"t","teamId":5,"mcpList":[],"skillList":[]}"#)
        let teamRequest = try XCTUnwrap(SessionRenameRequest(renaming: team, to: "新"))
        XCTAssertEqual(try encodedKeys(teamRequest), ["sessionDescription", "title"], "no teamId on the wire")
    }
}
