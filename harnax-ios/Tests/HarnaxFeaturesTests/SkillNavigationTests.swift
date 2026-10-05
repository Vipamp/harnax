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

    /// A source gate reads code, not prose: a line comment naming an API is documentation about a shape, not
    /// the shape. Only whole-line `//` comments are dropped — a trailing comment could sit inside a string
    /// literal, and none of these gates look that close.
    static func code(text: String) -> String {
        text.split(separator: "\n", omittingEmptySubsequences: false).filter { line in
            !line.drop(while: { $0 == " " || $0 == "\t" }).hasPrefix("//")
        }.joined(separator: "\n")
    }

    static func code(of relative: String) throws -> String {
        code(text: try contents(of: relative))
    }

    /// Every Swift file under one domain directory, so a gate can read a whole flow rather than one screen.
    static func contents(ofDomain relative: String) throws -> [(path: String, text: String)] {
        let root = sourcesRoot.appendingPathComponent(relative)
        guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else {
            throw XCTSkip("no domain directory at \(relative)")
        }
        var found: [(String, String)] = []
        for case let url as URL in walker where url.pathExtension == "swift" {
            found.append((url.path.replacingOccurrences(of: sourcesRoot.path + "/", with: ""),
                          code(text: try String(contentsOf: url, encoding: .utf8))))
        }
        return found.sorted { $0.0 < $1.0 }
    }

    /// Every Swift file of every feature domain, keyed by the directory name the domain lives in.
    static func contentsOfFeatureDomains() throws -> [(name: String, files: [(path: String, text: String)])] {
        let root = sourcesRoot.appendingPathComponent("HarnaxFeatures")
        let entries = try FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]
        )
        let directories = entries.filter {
            (try? urlIsDirectory($0)) ?? false
        }
        return try directories.map { url in
            (url.lastPathComponent, try contents(ofDomain: "HarnaxFeatures/\(url.lastPathComponent)"))
        }.sorted { $0.0 < $1.0 }
    }

    private static func urlIsDirectory(_ url: URL) throws -> Bool {
        try url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory ?? false
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
    private let homeFile = "HarnaxFeatures/Skills/SkillHomeView.swift"
    private let tableFile = "HarnaxFeatures/Skills/SkillTableView.swift"
    private let sourceFile = "HarnaxFeatures/Skills/SkillSourceListView.swift"

    /// A link whose value type has no destination in the stack is the worst kind of dead control: the row
    /// looks tappable, the tap is swallowed, and nothing ever reaches the detail screen
    /// (`SkillTableView.swift:97` sent `skill.id`, which `SkillResponse.kt:13` makes nullable, while line 68
    /// answered `Int64`).
    func testTheTableOnlySendsItsRowsToADestinationItRegisters() throws {
        let text = try FeatureSources.code(of: tableFile)
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
    /// here that panel is a pushed screen, so it has to follow the tap alone — which a row link is by
    /// construction, since nothing but a press can activate it.
    func testTheSourceListOpensATableOnlyFromItsRow() throws {
        let text = try FeatureSources.code(of: sourceFile)
        XCTAssertFalse(
            text.contains("onChange(of: vm.selection)"),
            "the highlight follows the automatic head row, so forwarding that change opens a table on entry"
        )
        XCTAssertTrue(
            text.contains("NavigationLink(value:"),
            "the row itself has to carry the push: a gesture that writes a presented value can be re-run by the "
                + "stack, a link cannot"
        )
        let home = try FeatureSources.code(of: homeFile)
        XCTAssertTrue(
            home.contains("navigationDestination(for: SkillSourceRoute.self)"),
            "and the host must answer the route the rows send"
        )
    }

    /// The defect this gate holds shut, reported 2026-10-03: tapping a skill left the skill table on screen and
    /// the detail only appeared after one back.
    ///
    /// `navigationDestination(item:)` presents while the binding holds a value, and the path change a deeper
    /// `NavigationLink(value:)` makes re-evaluates the view that owns the binding — so the presenting screen was
    /// pushed a second time on top of the destination it had just opened. No other screen in the app was shaped
    /// like that: every item-presented destination (`McpListView.swift:42`, `ChatTab.swift:29`,
    /// `TaskLogListView.swift:70`) is a leaf, and the flow that does go two levels deep (`ModelProviderListView
    /// .swift:40` and its rows) presents by value at both.
    func testTheSkillFlowPresentsNothingByItem() throws {
        for file in [homeFile, sourceFile, tableFile] {
            let text = try FeatureSources.code(of: file)
            XCTAssertFalse(
                text.contains("navigationDestination(item:"),
                "\(file) presents a screen by item while the skill flow pushes deeper by value"
            )
        }
    }

    /// The same rule as a shape over the whole feature layer, so the next domain cannot rediscover it: a domain
    /// may present by state only where nothing below it pushes by value.
    ///
    /// Both spellings of a state-presented destination are caught, because both are the same defect: the screen
    /// SwiftUI is presenting is re-evaluated by the deeper push's own path update and lands on the stack a
    /// second time. Gating only `item:` would leave `isPresented:` as the way through.
    func testNoDomainPresentsAScreenThatPushesDeeper() throws {
        let domains = try FeatureSources.contentsOfFeatureDomains()
        XCTAssertFalse(domains.isEmpty, "no domain directories under HarnaxFeatures")
        for domain in domains {
            let presented = domain.files.filter {
                $0.text.contains("navigationDestination(item:") || $0.text.contains("navigationDestination(isPresented:")
            }
            let pushed = domain.files.filter { $0.text.contains("NavigationLink(value:") }
            guard !presented.isEmpty else { continue }
            XCTAssertTrue(
                pushed.isEmpty,
                "\(domain.name) presents \(presented.map { $0.path }) by state and pushes "
                    + "\(pushed.map { $0.path }) by value: the state-presented screen is re-pushed by the deeper "
                    + "push's own stack update"
            )
        }
    }

    /// `SkillSyncModel` reports a finished install through `onChanged` (`SkillSyncSheet.swift:53-57`), and a
    /// sync writes both the source's skill rows and its last-sync column — the two things the source row
    /// shows. Left at the initializer's no-op default, the list keeps the pre-sync numbers on screen until the
    /// operator pulls to refresh, which reads as the sync having changed nothing.
    func testTheSourceListReloadsAfterItsSyncSheetWrites() throws {
        let text = try FeatureSources.code(of: sourceFile)
        let calls = try FeatureSources.captures(#"SkillSyncSheet\(([^)]*)\)"#, in: text)
        XCTAssertEqual(calls.count, 1, "the sync sheet has exactly one entry, from the source row")
        XCTAssertTrue(
            calls[0].contains("onChanged:"),
            "the sheet's change report has to reach the list: \(calls[0])"
        )
    }
}
