import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The skill screen's two navigation flows: the source row that opens the table, and the table row that
/// opens the detail.
///
/// Both defects are invisible to the compiler, which is why the first two tests read the source text the way
/// the colour and copy gates in `HarnaxKitTests` do. `NavigationLink(value:)` accepts any `Hashable`, so an
/// optional value next to a destination registered for the unwrapped type builds fine and simply never
/// pushes; and an `onChange(of:)` cannot tell an automatic selection from the tap that made the same change.
/// `TestSources` lives in the kit target and is not visible here, so the locator is local.
enum FeatureSources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    static func contents(of relative: String) throws -> String {
        let url = sourcesRoot.appendingPathComponent(relative)
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The captured group of every match, in order.
    static func captures(_ pattern: String, in text: String) throws -> [String] {
        let expression = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..., in: text)
        return expression.matches(in: text, range: range).compactMap { match in
            guard match.numberOfRanges > 1, let captured = Range(match.range(at: 1), in: text) else { return nil }
            return String(text[captured])
        }
    }

    /// The text from `marker` up to and including the brace that closes it. Enough to scope a gate to one
    /// declaration — an enum's cases, a switch's branches — without a parser.
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

final class SkillNavigationTests: XCTestCase {
    private let tableFile = "HarnaxFeatures/Skills/SkillTableView.swift"
    private let sourceFile = "HarnaxFeatures/Skills/SkillSourceListView.swift"

    /// A link whose value type has no destination in the stack is the worst kind of dead control: the row
    /// looks tappable, the tap is swallowed, and nothing ever reaches the detail screen
    /// (`SkillTableView.swift:97` sent `skill.id`, which `SkillResponse.kt:13` makes nullable, while line 68
    /// answered `Int64`).
    func testTheTableOnlySendsItsRowsToADestinationItRegisters() throws {
        let text = try FeatureSources.contents(of: tableFile)
        let destinations = try FeatureSources.captures(
            #"navigationDestination\(for: ([A-Za-z0-9_.]+)\.self\)"#,
            in: text
        )
        let links = try FeatureSources.captures(#"NavigationLink\(value: ([A-Za-z0-9_.]+)\)"#, in: text)
        XCTAssertFalse(links.isEmpty, "\(tableFile) is the skill detail's only entry")
        for value in links {
            XCTAssertTrue(
                text.contains("if let \(value) =") || text.contains("let \(value): "),
                "NavigationLink(value: \(value)) would send a type no destination answers for; the link has to "
                    + "carry a value the compiler already unwrapped"
            )
        }
        XCTAssertEqual(
            destinations,
            ["SkillDetailRoute"],
            "the destination must be registered for exactly the route type the rows build"
        )
    }

    /// `SkillSourceListView.swift:71` forwarded every change of `selection`, and
    /// `SkillSourceListViewModel.apply()` picks the head row on its own after each load
    /// (`harnax-webui/src/pages/skill/index.tsx:58-62`), so opening the tab pushed a table nobody asked for.
    /// The console's auto-selection only decides which source the right-hand panel of the same screen shows;
    /// here that panel is a pushed screen, so it has to follow the tap alone.
    func testTheSourceListOpensATableOnlyWhenTheRowIsTapped() throws {
        let text = try FeatureSources.contents(of: sourceFile)
        XCTAssertFalse(
            text.contains("onChange(of: vm.selection)"),
            "the highlight follows the automatic head row, so forwarding that change opens a table on entry"
        )
        XCTAssertTrue(
            text.contains("onChange(of: vm.pendingOpen)"),
            "the screen must react to the tap the view model recorded as an open request"
        )
        XCTAssertTrue(
            text.contains("vm.open("),
            "and the row's gesture has to be the thing that records it"
        )
    }

    /// `SkillSyncModel` reports a finished install through `onChanged` (`SkillSyncSheet.swift:53-57`), and a
    /// sync writes both the source's skill rows and its last-sync column — the two things the source row
    /// shows. Left at the initializer's no-op default, the list keeps the pre-sync numbers on screen until the
    /// operator pulls to refresh, which reads as the sync having changed nothing.
    func testTheSourceListReloadsAfterItsSyncSheetWrites() throws {
        let text = try FeatureSources.contents(of: sourceFile)
        let calls = try FeatureSources.captures(#"SkillSyncSheet\(([^)]*)\)"#, in: text)
        XCTAssertEqual(calls.count, 1, "the sync sheet has exactly one entry, from the source row")
        XCTAssertTrue(
            calls[0].contains("onChanged:"),
            "the sheet's change report has to reach the list: \(calls[0])"
        )
    }
}
