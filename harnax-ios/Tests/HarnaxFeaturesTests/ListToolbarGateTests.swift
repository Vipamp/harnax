import XCTest

/// Gate for the shape every entity list shares: the name search in its own box, every other filter and the
/// create entry in the top-right corner, and nothing that narrows the list inside the list itself.
///
/// The 上下文 tab's model list has drawn it this way from the start and the rest of the app was brought to it.
/// Three of these rules are negative, so each one walks the whole tree and proves it read something: a scan
/// that matched no file would pass all three forever without describing the app.
///
/// It reads source text for the same reason every other gate here does — no view is reachable from
/// `swift test`, since the screens are instantiated by `Harnax.xcodeproj` through
/// `App/HarnaxDebugScreens.swift` — so a filter left in the leading corner is neither a type error nor a
/// failing assertion. It is a control that lands on top of the back button.
enum ToolbarSources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    /// Every `.swift` under `prefix`, as paths relative to `Sources`.
    static func walk(_ prefix: String) throws -> [String] {
        let directory = sourcesRoot.appendingPathComponent(prefix)
        guard let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else {
            throw XCTSkip("\(prefix)/ is not readable — the folder moved or the prefix is misspelled")
        }
        return walker.compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" }
            .map { $0.path.replacingOccurrences(of: sourcesRoot.path + "/", with: "") }
            .sorted()
    }

    static func contents(of relative: String) throws -> String {
        try String(contentsOf: sourcesRoot.appendingPathComponent(relative), encoding: .utf8)
    }

    /// The text from `marker` up to and including the brace that closes it.
    static func block(_ text: String, from marker: String) throws -> String {
        guard let found = text.range(of: marker) else {
            throw XCTSkip("nothing in the file matches \(marker)")
        }
        var depth = 0
        var opened = false
        var body = ""
        var index = found.lowerBound
        while index < text.endIndex {
            let character = text[index]
            if character == "{" {
                depth += 1
                opened = true
            } else if character == "}" {
                depth -= 1
            }
            body.append(character)
            if opened && depth == 0 { return body }
            index = text.index(after: index)
        }
        return body
    }
}

final class ListToolbarGateTests: XCTestCase {
    /// The screens that carry a filter menu, by the name they give it.
    private static let menuDeclarations = ["private var filterMenu", "private var statusFilterMenu"]

    /// Every file under `prefixes` with its text already in hand. Reading here rather than inside a `filter`
    /// closure means a source file the test cannot open is an error, not a silent pass.
    private func texts(_ prefixes: [String]) throws -> [(path: String, text: String)] {
        var pairs: [(path: String, text: String)] = []
        for prefix in prefixes {
            for path in try ToolbarSources.walk(prefix) {
                pairs.append((path, try ToolbarSources.contents(of: path)))
            }
        }
        return pairs.sorted { $0.path < $1.path }
    }

    /// The leading corner is where a pushed screen keeps its back button.
    func testNothingSitsInTheLeadingCornerOfAScreen() throws {
        let files = try texts(["HarnaxFeatures"])
        XCTAssertGreaterThanOrEqual(files.count, 50, "the walk read \(files.count) files — it has stopped walking")

        let offenders = files.filter { $0.text.contains("placement: .navigation") }.map(\.path)
        XCTAssertTrue(
            offenders.isEmpty,
            "a toolbar item in the leading corner collides with the back button; these screens have to put "
                + "their chrome at .primaryAction:\n" + offenders.joined(separator: "\n")
        )
    }

    /// The `+` and the funnel are drawn once, in the kit, so eleven lists cannot drift into two shapes.
    func testTheToolbarGlyphsAreDrawnInOneFile() throws {
        let files = try texts(["HarnaxFeatures", "HarnaxKit"])
        let home = "HarnaxKit/Components/HXListToolbar.swift"
        XCTAssertTrue(files.contains { $0.path == home }, "\(home) is gone, so the rule below has nothing to point at")

        for marker in ["systemName: \"plus\"", "line.3.horizontal.decrease.circle"] {
            let offenders = files.filter { $0.path != home && $0.text.contains(marker) }.map(\.path)
            XCTAssertTrue(
                offenders.isEmpty,
                "\(marker) is drawn by the shared component only; found it inlined again:\n"
                    + offenders.joined(separator: "\n")
            )
        }
    }

    /// A strip of segments inside a searched list is the second way a screen grew its own filter UI. It also
    /// disappears when the list does: the API key screen's segment row lived below the phase switch, so
    /// filtering to zero rows took the only control that could undo it off the screen.
    func testNoSearchedListCarriesItsOwnSegmentStrip() throws {
        let searched = try texts(["HarnaxFeatures"]).filter { $0.text.contains(".searchable(") }
        XCTAssertGreaterThanOrEqual(searched.count, 10, "only \(searched.count) searched lists — the walk changed")

        let offenders = searched.filter { $0.text.contains("HXSegmented(") }.map(\.path)
        XCTAssertTrue(
            offenders.isEmpty,
            "everything but the name belongs in the toolbar menu:\n" + offenders.joined(separator: "\n")
        )
    }

    /// The positive half of the same rule: a menu that is declared but never mounted is a filter that exists
    /// in the view model and nowhere on screen.
    func testEveryFilterMenuIsMountedAtTheTrailingEnd() throws {
        var checked = 0
        for file in try texts(["HarnaxFeatures"]) {
            for marker in Self.menuDeclarations where file.text.contains(marker) {
                checked += 1
                let name = marker.replacingOccurrences(of: "private var ", with: "")
                XCTAssertTrue(
                    file.text.contains("ToolbarItem(placement: .primaryAction) { \(name) }"),
                    "\(file.path) declares \(name) but never puts it in the toolbar"
                )
                XCTAssertTrue(
                    file.text.contains("HXFilterMenu("),
                    "\(file.path) builds its filter menu by hand instead of through the shared component"
                )
            }
        }
        XCTAssertGreaterThanOrEqual(checked, 10, "only \(checked) screens declare a filter menu — the walk changed")
    }

    /// The model list is the one screen with a filter that cannot become a menu choice: two numbers the user
    /// types. They stay above the rows, and the capability tags that used to share that band moved in with the
    /// status and the type.
    func testTheModelListHoldsThreeGroupsAndKeepsItsPriceBoxes() throws {
        let path = "HarnaxFeatures/Models/ModelListView.swift"
        let text = try ToolbarSources.contents(of: path)
        let menu = try ToolbarSources.block(text, from: "private var filterMenu")
        XCTAssertEqual(menu.components(separatedBy: "Divider()").count - 1, 2, "status, type, tags")
        XCTAssertTrue(menu.contains("tagChoices"), "the capability tags are a menu group now")
        XCTAssertTrue(
            try ToolbarSources.block(text, from: "private var tagChoices").contains("ModelCapability.allCases"),
            "and the group is the five tags the endpoint's `tags` parameter takes"
        )
        XCTAssertTrue(
            try ToolbarSources.block(text, from: "private var screen").contains("priceFilter"),
            "the price boxes still ride above the rows, where a list that filtered itself empty keeps them"
        )
    }
}
