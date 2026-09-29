import XCTest
@testable import HarnaxCore

/// What the two save routes put on the wire. The interesting part is not the field names but the three
/// states a binding group can be in: the server reads an absent group as 「leave those rows alone」 and an
/// empty one as 「delete them」
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:185-197`), and
/// collapsing those two would let an untouched form wipe a working agent.
final class AgentSaveDraftTests: XCTestCase {
    private func json(_ value: Encodable) throws -> [String: JSONValue] {
        try JSONDecoder().decode([String: JSONValue].self, from: JSONEncoder().encode(value))
    }

    private func rows(_ value: JSONValue?) -> [[String: JSONValue]] {
        guard case .array(let items)? = value else { return [] }
        return items.map { row in
            guard case .object(let fields) = row else { return [:] }
            return fields
        }
    }

    // MARK: - the three states

    func testAGroupTheFormNeverReachedIsAbsentRatherThanEmpty() throws {
        let body = try json(AgentSaveDraft(name: "翻译", description: "", systemPrompt: "", modelId: 3))

        XCTAssertNil(body["toolList"])
        XCTAssertNil(body["mcpList"])
        XCTAssertNil(body["cliList"])
        XCTAssertNil(body["skillList"])
    }

    func testAClearedGroupSendsAnEmptyListNotANullOne() throws {
        let body = try json(AgentSaveDraft(tools: [], mcps: [], clis: []))

        XCTAssertEqual(body["toolList"], .array([]))
        XCTAssertEqual(body["mcpList"], .array([]))
        XCTAssertEqual(body["cliList"], .array([]))
    }

    /// Skills are the odd one out: the field is a comma-joined **string**, and clearing them has to send the
    /// empty string, because the delete runs before the blank check returns (`AgentServiceImpl.kt:534-536`).
    func testSkillsTravelAsOneCommaStringInDisplayOrder() throws {
        XCTAssertEqual(try json(AgentSaveDraft(skillIDs: [9, 4, 12]))["skillList"], .string("9,4,12"))
        XCTAssertEqual(try json(AgentSaveDraft(skillIDs: []))["skillList"], .string(""))
    }

    func testCreateCarriesNoOwnerFieldWhenTheFormLeftItBlank() throws {
        let body = try json(AgentSaveDraft(name: "翻译", status: 1, isPublic: 0))

        XCTAssertNil(body["owner"])
        XCTAssertEqual(body["status"], .number(1))
        XCTAssertEqual(body["isPublic"], .number(0))
    }

    // MARK: - binding rows

    func testAToolRowSendsItsIdItsConfirmFlagAndItsParameters() throws {
        let body = try json(
            AgentSaveDraft(tools: [
                AgentToolDraft(id: 7, needConfirm: true, envBindings: [.custom(envKey: "TIMEOUT", value: "30")])
            ])
        )

        XCTAssertEqual(rows(body["toolList"]), [
            [
                "id": .number(7),
                "needConfirm": .boolean(true),
                "envBindings": .array([.object(["envKey": .string("TIMEOUT"), "customValue": .string("30")])]),
            ]
        ])
    }

    /// A row that points at an environment variable sends the pointer and nothing else. Echoing the read
    /// side's display value back would snapshot `******` as the fallback the tool gets once the variable is
    /// deleted (`AgentServiceImpl.kt:612-626`).
    func testAReferencedParameterSendsOnlyTheReference() throws {
        let body = try json(AgentSaveDraft(mcps: [
            AgentMcpDraft(id: 2, envBindings: [.referenced(envKey: "API_KEY", envVarID: 11)])
        ]))

        let binding = rows(rows(body["mcpList"])[0]["envBindings"])[0]
        XCTAssertEqual(binding["envKey"], .string("API_KEY"))
        XCTAssertEqual(binding["envVarId"], .number(11))
        XCTAssertNil(binding["customValue"])
        XCTAssertNil(binding["envValue"])
        XCTAssertNil(binding["envVarName"], "the server resolves the name itself when it is absent")
    }

    func testTheThreeCapabilityListsKeepTheirOwnShapes() throws {
        let body = try json(
            AgentSaveDraft(
                tools: [AgentToolDraft(id: 1)],
                mcps: [AgentMcpDraft(id: 2)],
                clis: [AgentCliDraft(id: 3)]
            )
        )

        XCTAssertEqual(rows(body["toolList"])[0]["id"], .number(1))
        XCTAssertEqual(rows(body["mcpList"])[0]["id"], .number(2))
        XCTAssertEqual(rows(body["cliList"])[0]["id"], .number(3))
        XCTAssertNil(rows(body["toolList"])[0]["needConfirm"])
    }

    // MARK: - teams

    func testTeamSkillsAreAnArrayWhereTheAgentEquivalentIsAString() throws {
        let body = try json(TeamSaveDraft(name: "报告组", skillIDs: [5, 6]))

        XCTAssertEqual(body["skillIds"], .array([.number(5), .number(6)]))
    }

    /// Create demands at least one member (`TeamCreateRequest.kt:41-44`), so an absent list is not the same
    /// thing as an empty one here either.
    func testMembershipIsReplacedOnlyWhenTheFormTouchedIt() throws {
        let untouched = try json(TeamSaveDraft(name: "报告组"))
        let replaced = try json(TeamSaveDraft(members: []))
        let given = try json(TeamSaveDraft(members: [TeamMemberDraft(agentId: 8, delegationDescription: "取数")]))

        XCTAssertNil(untouched["members"])
        XCTAssertEqual(replaced["members"], .array([]))
        XCTAssertEqual(rows(given["members"]), [
            ["agentId": .number(8), "delegationDescription": .string("取数")]
        ])
    }

    func testAMemberWithoutADescriptionSendsJustItsAgentId() throws {
        let body = try json(TeamSaveDraft(members: [TeamMemberDraft(agentId: 8)]))

        XCTAssertEqual(rows(body["members"]), [["agentId": .number(8)]])
    }
}
