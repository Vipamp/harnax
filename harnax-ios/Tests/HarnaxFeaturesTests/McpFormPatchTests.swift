import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The edit body is "omitted means keep what is stored" (`McpServerServiceImpl.kt:174-252`), which makes an
/// erased field and an untouched one the same event on a form that only sends differences — unless the
/// difference is encoded as the value the service itself clears with.
///
/// The server clears these three columns with the empty string and never with null: `description`, `command`
/// and `url` are non-null on the entity, and the transport switch blanks the field the row is leaving
/// (`:200`, `:205`). `McpServerPatch` encodes with `encodeIfPresent`, so a `nil` here is the key going absent
/// and the old text surviving the save the operator just made.
@MainActor
final class McpFormPatchTests: XCTestCase {
    private func row(_ fields: [String: Any]) throws -> McpServerRow {
        try XCTUnwrap(PageStub.list([McpServerRow].self, [fields]).first)
    }

    private func httpRow() throws -> McpServerRow {
        try row([
            "id": 5, "name": "报表服务", "description": "旧备注", "type": "streamablehttp",
            "url": "https://example.com/mcp", "status": 1, "isPublic": 1, "creator": "heqingsong",
        ])
    }

    private func stdioRow() throws -> McpServerRow {
        try row([
            "id": 6, "name": "本地服务", "description": "旧备注", "type": "stdio",
            "command": "npx mcp-server", "status": 1, "isPublic": 0, "creator": "heqingsong",
        ])
    }

    func testAClearedRemarkIsSentAsAnEmptyString() throws {
        let vm = McpFormViewModel(mcp: FakeMcpServers(), mode: .edit(try httpRow()))
        XCTAssertEqual(vm.detail, "旧备注")
        vm.detail = ""
        XCTAssertEqual(
            vm.buildPatch().description,
            "",
            "a remark the operator deleted is a write, not a field they never touched"
        )
    }

    func testAClearedURLIsSentAsAnEmptyString() throws {
        let vm = McpFormViewModel(mcp: FakeMcpServers(), mode: .edit(try httpRow()))
        vm.endpointURL = ""
        XCTAssertEqual(vm.buildPatch().url, "")
    }

    func testAClearedCommandIsSentAsAnEmptyString() throws {
        let vm = McpFormViewModel(mcp: FakeMcpServers(), mode: .edit(try stdioRow()))
        vm.command = ""
        XCTAssertEqual(vm.buildPatch().command, "")
    }

    /// The other half of the same rule: a form nobody edited must still send nothing, or the whole diff
    /// collapses into "overwrite every column with what the sheet happened to echo".
    func testAnUntouchedFormSendsNoneOfTheThree() throws {
        let patch = McpFormViewModel(mcp: FakeMcpServers(), mode: .edit(try httpRow())).buildPatch()
        XCTAssertNil(patch.description, "an untouched remark is the key going absent")
        XCTAssertNil(patch.url)
        XCTAssertNil(patch.command)
        XCTAssertNil(patch.name)
    }
}
