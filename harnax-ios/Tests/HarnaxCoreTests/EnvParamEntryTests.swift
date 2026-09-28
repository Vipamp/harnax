import XCTest
@testable import HarnaxCore

/// `ToolEnvParamEntry` is the one place in the admin contract where the two flags are JSON booleans, so
/// the shared chip's decoding is worth pinning down before three domains read through it.
final class EnvParamEntryTests: XCTestCase {
    func testBooleanFlagsDecodeAsBooleans() throws {
        let data = Data(
            #"[{"id":7,"envParamName":"API_KEY","description":"Your OpenAI API key","required":true,"secret":true,"defaultValue":null}]"#.utf8
        )
        let entries = try JSONDecoder().decode([EnvParamEntry].self, from: data)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].id, 7)
        XCTAssertEqual(entries[0].envParamName, "API_KEY")
        XCTAssertTrue(entries[0].required)
        XCTAssertTrue(entries[0].secret)
        XCTAssertNil(entries[0].defaultValue)
    }

    func testRequiredNamesAreTheSameSetAsTheServerShortcut() throws {
        let entries = [
            EnvParamEntry(envParamName: "API_KEY", required: true),
            EnvParamEntry(envParamName: "REGION", required: false),
            EnvParamEntry(envParamName: "TOKEN", required: true),
        ]
        XCTAssertEqual(entries.requiredNames, ["API_KEY", "TOKEN"])
    }

    /// The binding forms post entries back; an unset optional must be absent rather than `null`, since the
    /// backend reads an absent key as "leave what is stored alone".
    func testEncodingOmitsUnsetOptionals() throws {
        let data = try JSONEncoder().encode(EnvParamEntry(envParamName: "API_KEY", required: true))
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(json.contains("\"envParamName\":\"API_KEY\""))
        XCTAssertFalse(json.contains("defaultValue"), json)
        XCTAssertFalse(json.contains("description"), json)
    }
}
