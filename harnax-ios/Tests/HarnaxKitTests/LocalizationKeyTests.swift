import XCTest

@testable import HarnaxKit

/// English and Simplified Chinese ship together; nothing reaches a screen in one language only.
final class LocalizationKeyTests: XCTestCase {
    /// Screens add their namespace here as they land. Only dotted literals in this set are treated as keys.
    /// A namespace word also claims the matching SF Symbol prefix — `server.rack` reads as a copy key — so
    /// an icon name inside a screen has to avoid these prefixes.
    private static let namespaces = [
        "common", "state", "tab", "login", "server", "me",
        "context", "system", "env", "agent", "team", "task", "chat", "model", "tool", "skill", "mcp", "cli",
        "apikey",
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

    /// `%@` in one language and `%d` in the other is not a translation slip but a different string at
    /// runtime, and a placeholder that only one side has leaves one language rendering a bare `%@` on
    /// screen (`HarnaxCatalog.render` hands the template to `String(format:)` unchanged when the call site
    /// passed nothing).
    func testPlaceholdersAgreeAcrossLanguages() throws {
        let en = Dictionary(uniqueKeysWithValues: try keys(in: Self.enPath).map { ($0.key, $0.value) })
        let zh = Dictionary(uniqueKeysWithValues: try keys(in: Self.zhPath).map { ($0.key, $0.value) })
        var drifted: [String] = []
        for key in en.keys where zh[key] != nil {
            let left = Self.placeholders(in: en[key]!)
            let right = Self.placeholders(in: zh[key]!)
            if left != right { drifted.append("\(key): en=\(left) zh=\(right)") }
        }
        let templated = en.keys.filter { !Self.placeholders(in: en[$0]!).isEmpty }
        XCTAssertFalse(
            templated.isEmpty,
            "not one templated string parsed — the placeholder reader is not matching anything"
        )
        XCTAssertTrue(drifted.isEmpty, "placeholder drift between catalogs:\n  " + drifted.joined(separator: "\n  "))
    }

    /// A counted key is handed its number by `hxCount`, and a `hx("k")` of a templated string prints the
    /// placeholder instead of the sentence. Either way the caller owes an argument.
    func testTemplatedCopyIsAlwaysCalledWithItsArguments() throws {
        let en = Dictionary(uniqueKeysWithValues: try keys(in: Self.enPath).map { ($0.key, $0.value) })
        let zh = Dictionary(uniqueKeysWithValues: try keys(in: Self.zhPath).map { ($0.key, $0.value) })
        var bare: [String] = []
        for url in TestSources.swiftFiles() {
            let source = Self.strippingComments(try TestSources.contents(of: url))
            for key in Self.bareCallSites(in: source) where !Self.placeholders(in: en[key] ?? "").isEmpty
                || !Self.placeholders(in: zh[key] ?? "").isEmpty {
                bare.append("\(TestSources.relative(url)) -> \(key)")
            }
        }
        XCTAssertTrue(bare.isEmpty, "templated copy rendered without arguments:\n  " + bare.joined(separator: "\n  "))
    }

    /// The two gates above only mean something if their readers read. A gate that parses nothing is green
    /// forever, so the machinery gets its own case.
    func testThePlaceholderAndArityReadersRead() {
        XCTAssertEqual(Self.placeholders(in: "已同步 %d 项"), ["d"])
        XCTAssertEqual(Self.placeholders(in: "Synced %d items"), ["d"])
        XCTAssertEqual(Self.placeholders(in: "%@ 与 %@"), ["@", "@"])
        XCTAssertEqual(Self.placeholders(in: "100%% 确定"), [], "an escaped percent asks for nothing")
        XCTAssertEqual(Self.placeholders(in: "没有占位符"), [])
        XCTAssertEqual(
            Self.bareCallSites(
                in: #"hx("common.retry") and HXText("common.refresh") and hx("state.empty.title", 1)"#
            ),
            ["common.retry", "common.refresh"],
            "only the calls that pass nothing are bare"
        )
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
        XCTAssertEqual(hxCount("agent.sessionCount", 3), "3 个会话")
        XCTAssertEqual(hxCount("agent.sessionCount", 1), "1 个会话")
    }

    func testEnglishCatalogActuallyResolves() {
        HarnaxCatalog.shared.language = .en
        XCTAssertEqual(hx("common.retry"), "Retry")
        XCTAssertEqual(hxCount("agent.sessionCount", 12), "12 sessions")
        XCTAssertEqual(hxCount("agent.sessionCount", 1), "1 session")
        XCTAssertEqual(hxCount("env.count", 1), "1 param")
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

    /// The conversion characters a `String(format:)` template consumes, in sorted order so only the multiset
    /// matters. `%%` is an escaped percent and asks for nothing.
    private static func placeholders(in template: String) -> [Character] {
        let span = NSRange(template.startIndex..., in: template)
        let expression = try! NSRegularExpression(
            pattern: #"%[-+ #0]*[0-9]*(?:\.[0-9]+)?([@dDuUxXoOfeEgGcCsSpF%])"#
        )
        return expression.matches(in: template, range: span).compactMap { match in
            guard let range = Range(match.range(at: 1), in: template) else { return nil }
            let conversion = template[range].first!
            return conversion == "%" ? nil : conversion
        }.sorted()
    }

    /// Keys reached with no argument at all: `hx("a.b")` and `HXText("a.b")`, where the closing paren comes
    /// straight after the string.
    private static func bareCallSites(in source: String) -> Set<String> {
        let span = NSRange(source.startIndex..., in: source)
        let expression = try! NSRegularExpression(pattern: #"\b(?:hx|HXText)\(\s*"([^"]+)"\s*\)"#)
        return Set(expression.matches(in: source, range: span).compactMap { match in
            guard let range = Range(match.range(at: 1), in: source) else { return nil }
            return String(source[range])
        })
    }

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
    /// `var titleKey` and handed to `HXText` later. `hxCount` claims the two plural forms of its base and
    /// not the base, which holds no value.
    private static func usedKeys(in source: String) -> Set<String> {
        var found = Set<String>()
        let span = NSRange(source.startIndex..., in: source)
        let counted = try! NSRegularExpression(pattern: #"\bhxCount\(\s*"([^"]+)""#)
        let bases = Set(counted.matches(in: source, range: span).compactMap { match -> String? in
            guard let range = Range(match.range(at: 1), in: source) else { return nil }
            return String(source[range])
        })
        for base in bases {
            found.insert(base + ".one")
            found.insert(base + ".other")
        }
        let callSite = try! NSRegularExpression(pattern: #"\b(?:hx|HXText)\(\s*"([^"]+)""#)
        for match in callSite.matches(in: source, range: span) {
            if let range = Range(match.range(at: 1), in: source) { found.insert(String(source[range])) }
        }
        let literal = try! NSRegularExpression(pattern: #""([a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+)""#)
        for match in literal.matches(in: source, range: span) {
            guard let range = Range(match.range(at: 1), in: source) else { continue }
            let candidate = String(source[range])
            if bases.contains(candidate) { continue }
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
