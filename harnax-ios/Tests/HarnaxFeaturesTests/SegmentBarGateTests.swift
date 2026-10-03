import XCTest
@testable import HarnaxFeatures

/// The segment row a host tab switches its columns with has to travel inside the column it belongs to.
///
/// Reported 2026-10-03: on the 上下文 tab the row sat in a `VStack` above the column, so it held still while
/// the navigation title and the search field moved with the bar — two surfaces scrolling differently read as
/// a broken screen. The compiler cannot see which of two siblings owns the scroll, so this gate reads the
/// source: the bar must be handed down, and each column must draw it inside its own scroll.
final class SegmentBarGateTests: XCTestCase {
    /// The two tab shells that switch between columns.
    private let hosts = [
        "HarnaxFeatures/Context/ContextView.swift",
        "HarnaxFeatures/Agents/AgentHomeView.swift"
    ]

    /// The eight columns they switch between. A pushed second-level screen is not in this list on purpose: the
    /// row belongs to the tab, not to a detail.
    private let columns = [
        "HarnaxFeatures/Models/ModelProviderListView.swift",
        "HarnaxFeatures/Tools/ToolListView.swift",
        "HarnaxFeatures/Mcp/McpListView.swift",
        "HarnaxFeatures/Skills/SkillSourceListView.swift",
        "HarnaxFeatures/Cli/CliListView.swift",
        "HarnaxFeatures/Agents/AgentListView.swift",
        "HarnaxFeatures/Teams/TeamListView.swift",
        "HarnaxFeatures/Tasks/TaskListView.swift"
    ]

    /// A host that stacks the bar above its column re-creates the defect by hand, and a host that builds the
    /// bar inline has nothing for the column to carry.
    func testTheHostsHandTheBarDownInsteadOfStackingIt() throws {
        for file in hosts {
            let text = try FeatureSources.code(of: file)
            XCTAssertTrue(
                text.contains("harnaxSegmentBar {"),
                "\(file) has to hand the row to the column, not draw it beside the column"
            )
            XCTAssertFalse(
                text.contains("VStack(spacing: 0)"),
                "\(file) stacks the bar above the column again — the bar holds still while the rows scroll"
            )
            let bar = try FeatureSources.block(text, from: "harnaxSegmentBar {")
            XCTAssertTrue(bar.contains("HXSegmented("), "the row the \(file) hands down: \(bar)")
        }
    }

    /// Every column carries the row inside the surface that scrolls, or the row is not scrollable and the
    /// original defect is still on screen for that domain.
    func testEveryColumnCarriesTheBarInItsOwnScroll() throws {
        for file in columns {
            let text = try FeatureSources.code(of: file)
            let carrier = text.contains("private var content") ? "private var content" : "public var body"
            let block = try FeatureSources.block(text, from: carrier)
            XCTAssertTrue(
                block.contains("HXSegmentBarRow()") || block.contains("segmentBar"),
                "\(file) draws the row outside \(carrier), so it cannot leave with the rows"
            )
        }
    }

    /// The row belongs to the tab. A detail screen that draws it offers a switch over columns nobody can see,
    /// and `SkillTableView` — reached from the source list, which does carry the row — is the one screen that
    /// would inherit it by accident, since the environment reaches into a pushed destination.
    func testOnlyTheHostedColumnsDrawTheBar() throws {
        let drawn = try FeatureSources.contentsOfFeatureDomains().flatMap { $0.files }
            .filter { $0.text.contains("HXSegmentBarRow()") || $0.text.contains("@Environment(\\.harnaxSegmentBar)") }
            .map(\.path)
        let unexpected = drawn.filter { !columns.contains($0) }
        XCTAssertTrue(
            unexpected.isEmpty,
            "the row is drawn by a screen that is not one of the eight hosted columns: "
                + unexpected.joined(separator: ", ")
        )
        let missing = columns.filter { !drawn.contains($0) }
        XCTAssertTrue(
            missing.isEmpty,
            "a hosted column stopped carrying the row: \(missing.joined(separator: ", "))"
        )
    }
}
