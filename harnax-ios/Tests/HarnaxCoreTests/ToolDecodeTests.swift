import XCTest

@testable import HarnaxCore

/// The tool wire shape, read off `AgentToolResponse.kt:7-55` and `ToolEnvParamEntry.kt:12-31`.
///
/// These fixtures are hand-shaped from those two DTOs rather than curl-captured: the dev stack answers
/// `/api/admin/tools/**` only behind a bearer token, so key names and the `non_null` omission pattern are
/// taken from the Kotlin declarations, which is what the decoder has to survive either way.
final class ToolDecodeTests: XCTestCase {
    private func rows() throws -> [ToolSummary] {
        try Fixture.decode(Envelope<Page<ToolSummary>>.self, "tools-page-two-rows").data!.records
    }

    func testPageEnvelopeCarriesItsCounterAndBothRows() throws {
        let page = try Fixture.decode(Envelope<Page<ToolSummary>>.self, "tools-page-two-rows").data!
        XCTAssertEqual(page.pageNum, 1)
        XCTAssertEqual(page.pageSize, 50)
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)
    }

    func testEveryColumnOfAFullRowDecodes() throws {
        let tool = try XCTUnwrap(rows().first)
        XCTAssertEqual(tool.id, 1)
        XCTAssertEqual(tool.name, "send_email")
        XCTAssertEqual(tool.displayName, "Send Email")
        XCTAssertEqual(tool.displayNameZh, "发送邮件")
        XCTAssertEqual(tool.description, "按收件人邮箱发送已渲染的正文")
        XCTAssertEqual(tool.beanName, "emailTool")
        XCTAssertEqual(tool.methodName, "send")
        XCTAssertEqual(tool.status, 1)
        XCTAssertEqual(tool.creator, "heqingsong")
        XCTAssertEqual(tool.createTime, "2026-09-12 10:20:30")
        XCTAssertEqual(tool.updateTime, "2026-09-28 08:01:12")
        XCTAssertTrue(tool.isEnabled)
        XCTAssertTrue(tool.isCodeOwned)
        XCTAssertTrue(tool.requiresConfirmation)
        XCTAssertFalse(tool.isMandatory)
        XCTAssertEqual(tool.declaredRequiredKeys, ["SMTP_PASSWORD"])
    }

    /// `required` and `secret` are the two booleans on this contract; the 0/1 columns stay integers so a
    /// missing flag is not silently read as a `0`.
    func testEnvParamEntriesKeepTheirFlagsAndMaskedDefault() throws {
        let tool = try XCTUnwrap(rows().first)
        XCTAssertEqual(tool.entries.count, 2)

        let password = tool.entries[0]
        XCTAssertEqual(password.id, 3)
        XCTAssertEqual(password.envParamName, "SMTP_PASSWORD")
        XCTAssertEqual(password.description, "SMTP 登录口令")
        XCTAssertTrue(password.required)
        XCTAssertTrue(password.secret)
        XCTAssertEqual(password.defaultValue, "abc****wxyz")

        let timeout = tool.entries[1]
        XCTAssertFalse(timeout.required)
        XCTAssertFalse(timeout.secret)
        XCTAssertEqual(timeout.defaultValue, "30")
        XCTAssertNil(timeout.description)
    }

    /// The second row omits `displayNameZh` / `beanName` / `methodName` / `readOnly` / `needConfirm` /
    /// `isRequired` / `creator` / both timestamps the way `default-property-inclusion: non_null` omits them,
    /// and keeps `description: null` to cover the other shape.
    func testSparseRowKeepsItsOwnNullsApartFromAbsentKeys() throws {
        let tool = try XCTUnwrap(rows().dropFirst().first)
        XCTAssertEqual(tool.id, 2)
        XCTAssertNil(tool.description)
        XCTAssertNil(tool.displayNameZh)
        XCTAssertNil(tool.beanName)
        XCTAssertNil(tool.methodName)
        XCTAssertNil(tool.readOnly)
        XCTAssertNil(tool.needConfirm)
        XCTAssertNil(tool.isRequired)
        XCTAssertNil(tool.creator)
        XCTAssertNil(tool.createTime)
        XCTAssertEqual(tool.entries, [], "an empty parameter table arrives as [] rather than being absent")
        XCTAssertEqual(tool.entryCountFallback, 2)
    }

    /// An absent 0/1 column is not a `0`: only `status == 0` stops a row, and only an explicit `1` raises a
    /// mark.
    func testAbsentFlagsAreNotReadAsMarks() throws {
        let tool = try XCTUnwrap(rows().dropFirst().first)
        XCTAssertTrue(tool.isEnabled)
        XCTAssertFalse(tool.isCodeOwned)
        XCTAssertFalse(tool.requiresConfirmation)
        XCTAssertFalse(tool.isMandatory)
    }

    func testNamingFollowsTheLocaleColumn() throws {
        let bilingual = try XCTUnwrap(rows().first)
        XCTAssertEqual(bilingual.title(chinese: true), "发送邮件")
        XCTAssertEqual(bilingual.title(chinese: false), "Send Email")
        XCTAssertEqual(bilingual.codeName, "send_email")

        let englishOnly = try XCTUnwrap(rows().dropFirst().first)
        XCTAssertEqual(englishOnly.title(chinese: true), "Web Search")
    }

    /// `   ` is what the sync writes when a display column exists but was never filled in.
    func testBlankNamesFallThroughToTheCodeName() throws {
        let tool = try ToolSummary.stub([
            "id": 7, "name": "clip_video", "displayName": "   ", "displayNameZh": "",
        ])
        XCTAssertEqual(tool.title(chinese: true), "clip_video")
        XCTAssertEqual(tool.title(chinese: false), "clip_video")
    }

    func testImplementationLocationNamesBothOrNeither() throws {
        let full = try XCTUnwrap(rows().first)
        XCTAssertEqual(full.implementation, "emailTool#send")

        let beanOnly = try ToolSummary.stub(["id": 8, "beanName": "fileTool"])
        XCTAssertEqual(beanOnly.implementation, "fileTool")

        let methodOnly = try ToolSummary.stub(["id": 9, "methodName": "read"])
        XCTAssertEqual(methodOnly.implementation, "#read")

        let bare = try ToolSummary.stub(["id": 10, "name": "noop"])
        XCTAssertNil(bare.implementation)
    }

    /// The parameter keys without the entry table is a real shape: the two come from different columns
    /// (`AgentToolServiceImpl.kt:39-40`).
    func testRequiredKeysStandAloneWhenTheEntryTableIsShort() throws {
        let tool = try XCTUnwrap(rows().dropFirst().first)
        XCTAssertTrue(tool.entries.isEmpty)
        XCTAssertEqual(tool.declaredRequiredKeys, ["SEARCH_QUOTA", "SEARCH_REGION"])
    }

    func testDetailRouteDecodesTheSameRowShape() throws {
        let tool = try Fixture.decode(Envelope<ToolSummary>.self, "tool-detail").data!
        XCTAssertEqual(tool.id, 1)
        XCTAssertEqual(tool.name, "send_email")
        XCTAssertTrue(tool.isMandatory, "the row re-read by id is a mandatory tool")
        XCTAssertEqual(tool.updateTime, "2026-10-02 19:44:05")
        XCTAssertEqual(tool.entries.count, 1)
        XCTAssertEqual(tool.entries.first?.defaultValue, "******", "a secret default never arrives in clear")
    }
}

private extension ToolSummary {
    /// Field maps rather than string literals: every key on a tool row is optional on the wire, so a test
    /// about one rule spells only the columns that rule reads.
    static func stub(_ fields: [String: Any]) throws -> ToolSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(ToolSummary.self, from: data)
    }
}
