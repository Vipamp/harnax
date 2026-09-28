import XCTest

@testable import HarnaxKit

/// English and Simplified Chinese ship together; nothing reaches a screen in one language only.
final class LocalizationKeyTests: XCTestCase {
    /// Screens add their namespace here as they land. Only dotted literals in this set are treated as keys.
    private static let namespaces = [
        "common", "state", "tab", "login", "server", "me",
        "agent", "team", "task", "chat", "model", "skill", "mcp", "cli",
        "channel", "cron", "token", "monitor", "log", "error", "auth", "session",
    ]

    private static let enPath = TestSources.sourcesRoot
        .appendingPathComponent("HarnaxKit/Resources/en.lproj/Localizable.strings")
    private static let zhPath = TestSources.sourcesRoot
        .appendingPathComponent("HarnaxKit/Resources/zh-Hans.lproj/Localizable.strings")

    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    func testBothCatalogsDefineTheSameKeys() throws {
        let en = try keys(in: Self.enPath).map(\.key)
        let zh = try keys(in: Self.zhPath).map(\.key)
        XCTAssertEqual(Set(en), Set(zh), "key sets drifted apart")
        XCTAssertEqual(en.sorted(), zh.sorted())
        XCTAssertFalse(en.isEmpty, "no keys parsed — the reader is broken")
    }

    func testNoCatalogCarriesEmptyValues() throws {
        for path in [Self.enPath, Self.zhPath] {
            let entries = try keys(in: path)
            for entry in entries where entry.value.trimmingCharacters(in: .whitespaces).isEmpty {
                XCTFail("\(TestSources.relative(path)) has an empty value for \(entry.key)")
            }
        }
    }

    func testEveryKeyUsedInSourceExistsInBothCatalogs() throws {
        let en = Set(try keys(in: Self.enPath).map(\.key))
        let zh = Set(try keys(in: Self.zhPath).map(\.key))
        var missing: [String] = []
        for url in TestSources.swiftFiles() {
            let source = Self.strippingComments(try TestSources.contents(of: url))
            for key in Self.usedKeys(in: source) where !en.contains(key) || !zh.contains(key) {
                missing.append("\(TestSources.relative(url)) -> \(key)")
            }
        }
        XCTAssertTrue(missing.isEmpty, "unresolved copy keys:\n  " + missing.joined(separator: "\n  "))
    }

    func testChineseCatalogActuallyResolves() {
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertEqual(hx("common.retry"), "重试")
        XCTAssertEqual(hx("state.empty.title"), "暂无内容")
        XCTAssertEqual(hx("agent.sessionCount", 3), "3 个会话")
    }

    func testEnglishCatalogActuallyResolves() {
        HarnaxCatalog.shared.language = .en
        XCTAssertEqual(hx("common.retry"), "Retry")
        XCTAssertEqual(hx("agent.sessionCount", 12), "12 sessions")
    }

    func testSystemLanguageFallsBackToACatalogNotToTheKey() {
        HarnaxCatalog.shared.language = .system
        let resolved = hx("common.retry")
        XCTAssertFalse(resolved.isEmpty)
        XCTAssertNotEqual(resolved, "common.retry", "lookup missed the bundle and echoed the key")
    }

    func testUnknownKeyEchoesItselfRatherThanAMissingStringMarker() {
        HarnaxCatalog.shared.language = .en
        XCTAssertEqual(hx("nope.not-a-key"), "nope.not-a-key")
    }

    // MARK: - parsing

    private struct Entry {
        let key: String
        let value: String
    }

    private func keys(in url: URL) throws -> [Entry] {
        let text = try TestSources.contents(of: url)
        let expression = try NSRegularExpression(pattern: #"^\s*"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;"#)
        return text.components(separatedBy: "\n").compactMap { line in
            guard let match = expression.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
                  let keyRange = Range(match.range(at: 1), in: line),
                  let valueRange = Range(match.range(at: 2), in: line) else { return nil }
            return Entry(key: String(line[keyRange]), value: String(line[valueRange]))
        }
    }

    /// Call-site keys (`hx("a.b")`, `HXText("a.b"`) plus bare dotted literals, which covers keys held in a
    /// `var titleKey` and handed to `HXText` later.
    private static func usedKeys(in source: String) -> Set<String> {
        var found = Set<String>()
        let span = NSRange(source.startIndex..., in: source)
        let callSite = try! NSRegularExpression(pattern: #"\b(?:hx|HXText)\(\s*"([^"]+)""#)
        for match in callSite.matches(in: source, range: span) {
            if let range = Range(match.range(at: 1), in: source) { found.insert(String(source[range])) }
        }
        let literal = try! NSRegularExpression(pattern: #""([a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+)""#)
        for match in literal.matches(in: source, range: span) {
            guard let range = Range(match.range(at: 1), in: source) else { continue }
            let candidate = String(source[range])
            if namespaces.contains(where: { candidate.hasPrefix($0 + ".") }) { found.insert(candidate) }
        }
        return found
    }

    private static func strippingComments(_ source: String) -> String {
        source
            .replacingOccurrences(of: #"/\*[\s\S]*?\*/"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"//.*"#, with: "", options: .regularExpression)
    }
}
