import XCTest

/// The source gates behind the design rules: colour comes from a token, appearance is resolved in one
/// place, and no user-visible string is hardcoded at a call site.
final class ThemeGateTests: XCTestCase {
    private static let themeDirectory = "HarnaxKit/Theme/"
    private static let paletteTable = "HarnaxKit/Theme/Palette.swift"

    private static let systemColorTokens = [
        "gray", "grey", "white", "black", "red", "blue", "green", "yellow", "orange",
        "pink", "mint", "cyan", "brown", "primary", "secondary",
        "systemBackground", "systemGray", "systemBlue",
    ]

    private static let copyTakingInitializers = [
        "Text", "Label", "Button", "Toggle", "Picker", "SecureField", "TextField", "NavigationLink",
    ]

    struct Violation {
        let rule: String
        let location: String
        let line: String
    }

    func testNoLiteralColorsOutsideThemeTokens() throws {
        var violations: [Violation] = []
        for (url, line) in try scan(where: isColorLiteral, skippingTheme: true) {
            violations.append(Violation(rule: "colour must come from Color.hx(.slot)", location: location(url, line), line: line.text))
        }
        XCTAssertNone(violations)
    }

    func testNoHexLiteralsOutsideThePaletteTable() throws {
        var violations: [Violation] = []
        for (url, line) in try scan(where: isHexLiteral, skippingTheme: false) {
            guard TestSources.relative(url) != Self.paletteTable else { continue }
            violations.append(Violation(rule: "hex belongs in Palette.swift", location: location(url, line), line: line.text))
        }
        XCTAssertNone(violations)
    }

    func testColorSchemeIsOnlyTouchedInsideTheme() throws {
        var violations: [Violation] = []
        for (url, line) in try scan(where: isColorSchemeTouched, skippingTheme: true) {
            violations.append(Violation(rule: "only HarnaxKit/Theme may read or force colorScheme", location: location(url, line), line: line.text))
        }
        XCTAssertNone(violations)
    }

    func testNoHardcodedCopyAtCallSites() throws {
        var violations: [Violation] = []
        for (url, line) in try scan(where: isHardcodedCopy, skippingTheme: false) {
            violations.append(Violation(rule: "wrap copy in HXText/hx with a key", location: location(url, line), line: line.text))
        }
        XCTAssertNone(violations)
    }

    // MARK: - scanning

    private struct Line {
        let number: Int
        let text: String
    }

    private func scan(where predicate: (String) -> Bool, skippingTheme: Bool) throws -> [(URL, Line)] {
        var hits: [(URL, Line)] = []
        for url in TestSources.swiftFiles() {
            let relative = TestSources.relative(url)
            if skippingTheme, relative.hasPrefix(Self.themeDirectory) { continue }
            let stripped = Self.strippingComments(try TestSources.contents(of: url))
            let lines = stripped.components(separatedBy: "\n")
            for (index, text) in lines.enumerated() {
                if predicate(text) { hits.append((url, Line(number: index + 1, text: text))) }
            }
        }
        return hits
    }

    private func location(_ url: URL, _ line: Line) -> String {
        "\(TestSources.relative(url)):\(line.number)"
    }

    /// Comments legitimately name `.gray` and `#8e8e93` while explaining what these gates forbid.
    private static func strippingComments(_ source: String) -> String {
        source
            .replacingOccurrences(of: #"/\*[\s\S]*?\*/"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"//.*"#, with: "", options: .regularExpression)
    }

    // MARK: - predicates

    private func isColorLiteral(_ line: String) -> Bool {
        if matches(line, #"Color\((red|hue|white|cgColor|uiColor|nsColor)\s*:"#) { return true }
        if matches(line, #"UIColor\((red|hue|white|dynamicProvider)\s*:"#) { return true }
        if matches(line, #"NSColor\("#) { return true }
        let alternation = Self.systemColorTokens.joined(separator: "|")
        return matches(line, #"^\s*\.(\#(alternation))\b"#)
            || matches(line, #"[^a-zA-Z_]\.(\#(alternation))\b"#)
    }

    private func isHexLiteral(_ line: String) -> Bool {
        matches(line, #"0x[0-9A-Fa-f]{6}\b"#) || matches(line, #"#[0-9A-Fa-f]{6}\b"#)
    }

    private func isColorSchemeTouched(_ line: String) -> Bool {
        matches(line, #"\.colorScheme\b"#)
    }

    private func isHardcodedCopy(_ line: String) -> Bool {
        let alternation = Self.copyTakingInitializers.joined(separator: "|")
        if matches(line, #"\b(\#(alternation))\(\s*""#) { return true }
        return matches(line, #"navigation(Title|Subtitle)\(\s*""#)
    }

    private func matches(_ text: String, _ pattern: String) -> Bool {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return false }
        return expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }
}

private func XCTAssertNone(_ violations: [ThemeGateTests.Violation], file: StaticString = #filePath, line: UInt = #line) {
    guard !violations.isEmpty else { return }
    let report = violations
        .map { "  \($0.location) — \($0.rule):\n    \($0.line.trimmingCharacters(in: .whitespaces))" }
        .joined(separator: "\n")
    XCTFail("\(violations.count) gate violation(s):\n\(report)", file: file, line: line)
}
