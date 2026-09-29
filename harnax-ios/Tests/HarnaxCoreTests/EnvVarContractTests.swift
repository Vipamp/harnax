import XCTest

@testable import HarnaxCore

/// S3's contract layer, read against the key names the stack actually sends
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/EnvVariableResponse.kt`).
final class EnvVarContractTests: XCTestCase {
    private var page: Page<EnvVarSummary>!

    override func setUpWithError() throws {
        page = try Fixture.decode(Envelope<Page<EnvVarSummary>>.self, "env-vars-page-two-rows").data!
    }

    /// Row 2 omits `description` and `updateTime` the way `default-property-inclusion: non_null` really
    /// omits them, and row 1 keeps the mask the service computed.
    func testRealPageDecodesRowByRow() throws {
        XCTAssertEqual(page.pageNum, 1)
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)

        let secret = page.records[0]
        XCTAssertEqual(secret.id, 31)
        XCTAssertEqual(secret.envKey, "OPENAI_API_KEY")
        XCTAssertEqual(secret.envValue, "sk-****c7")
        XCTAssertEqual(secret.description, "翻译助手使用的密钥")
        XCTAssertEqual(secret.sensitive, 1)
        XCTAssertEqual(secret.creator, "admin")

        let plain = page.records[1]
        XCTAssertEqual(plain.id, 32)
        XCTAssertNil(plain.description)
        XCTAssertNil(plain.updateTime)
        XCTAssertEqual(plain.envValue, "debug")
    }

    /// `enabled` and `sensitive` are 0/1 columns, not JSON booleans, and a missing `enabled` still reads as
    /// enabled because that is the column's server-side default.
    func testFlagColumnsReadAsTheServerWritesThem() throws {
        XCTAssertTrue(page.records[0].isSensitive)
        XCTAssertFalse(page.records[1].isSensitive)
        XCTAssertTrue(page.records[0].isEnabled)
        XCTAssertFalse(page.records[1].isEnabled)

        let bare = try row(#"{"envKey":"A","envValue":"b"}"#)
        XCTAssertFalse(bare.isSensitive)
        XCTAssertTrue(bare.isEnabled)
    }

    /// The mask is display data, never a value: `displayValue` hands back exactly what arrived so the screen
    /// cannot mistake `sk-****c7` for a key.
    func testMaskedValueIsPassedThroughUnchanged() throws {
        XCTAssertEqual(page.records[0].displayValue, "sk-****c7")
        XCTAssertEqual(page.records[0].title, "OPENAI_API_KEY")
    }

    /// A blank name is not a name — the console shows its own fallback, and so does iOS.
    func testBlankColumnsReadAsAbsent() throws {
        let blank = try row(#"{"envKey":"   ","envValue":"","description":"","sensitive":0,"enabled":1}"#)
        XCTAssertNil(blank.title)
        XCTAssertNil(blank.displayValue)
        XCTAssertNil(blank.note)
    }

    // MARK: - the key pattern

    /// `@Pattern(^[A-Za-z_][A-Za-z0-9_]*$)` on both request DTOs.
    func testKeyPatternMatchesTheBeanValidationItMirrors() {
        XCTAssertTrue(EnvVarKeyPattern.isValid("OPENAI_API_KEY"))
        XCTAssertTrue(EnvVarKeyPattern.isValid("_PRIVATE"))
        XCTAssertTrue(EnvVarKeyPattern.isValid("a1"))
        XCTAssertFalse(EnvVarKeyPattern.isValid("1LEADING_DIGIT"))
        XCTAssertFalse(EnvVarKeyPattern.isValid("DASH-NOT-ALLOWED"))
        XCTAssertFalse(EnvVarKeyPattern.isValid("WITH SPACE"))
        XCTAssertFalse(EnvVarKeyPattern.isValid("with.dot"))
        XCTAssertFalse(EnvVarKeyPattern.isValid(""))
    }

    // MARK: - the write bodies

    func testCreateDraftAlwaysSendsKeyAndValue() throws {
        let json = try JSONObject(EnvVarDraft(envKey: "A", envValue: "b", description: nil, sensitive: 1, enabled: nil))
        XCTAssertEqual(json?["envKey"] as? String, "A")
        XCTAssertEqual(json?["envValue"] as? String, "b")
        XCTAssertEqual(json?["sensitive"] as? Int, 1)
        XCTAssertNil(json?["description"], "an absent description stays off the body")
        XCTAssertNil(json?["enabled"], "an absent enabled lets the server default apply")
    }

    /// The whole point of `EnvVarChange`: an untouched edit form sends nothing at all, and a mask can only
    /// reach the wire if the caller puts it there by hand — which `EnvVarFormViewModel` never does.
    func testEmptyChangeEncodesNoKeysAtAll() throws {
        XCTAssertTrue(try XCTUnwrap(JSONObject(EnvVarChange())).isEmpty)
    }

    func testChangeCarriesOnlyTheColumnsThatMoved() throws {
        let json = try JSONObject(EnvVarChange(envKey: nil, envValue: "new", description: nil, sensitive: 0))
        XCTAssertEqual(Set((json ?? [:]).keys), ["envValue", "sensitive"])
        XCTAssertNil(json?["enabled"], "the update DTO has no enabled column at all")
    }

    // MARK: - helpers

    /// A row the way the wire makes it: a key the service left out is a key that is absent, not a nil argument.
    private func row(_ json: String) throws -> EnvVarSummary {
        try JSONDecoder().decode(EnvVarSummary.self, from: Data(json.utf8))
    }

    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any]? {
        let data = try JSONEncoder().encode(value)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}
