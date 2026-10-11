import XCTest
@testable import HarnaxFeatures

/// A screen that pins a control row above its phase switch has to hand the rest of the screen to the switch.
///
/// Reported 2026-10-11: the 技能晋升 queue opened with its three arms in the middle of the screen. Its
/// `VStack(spacing: 0)` held two children that both size to their content — the arm row and `HXStateView`,
/// which ends at `.frame(maxWidth: .infinity)` (`HarnaxKit/Components/HXStateView.swift:42`) — and the
/// screen-root frame then centred the pair as one block (`HarnaxKit/Components/HXScreen.swift:14-17`). The
/// rows branch fills on its own, so the defect only shows while a list loads, empties or fails: exactly the
/// moment a reviewer first lands on the screen. The compiler cannot see which sibling owns the slack, so this
/// gate reads the source.
final class PinnedBarFillGateTests: XCTestCase {
    /// The six screens that pin something above their phase switch, with the switch's own name.
    private let pinned: [(file: String, carrier: String)] = [
        ("HarnaxFeatures/SkillDrafts/SkillDraftListView.swift", "content"),
        ("HarnaxFeatures/MemoryDrafts/MemoryDraftListView.swift", "content"),
        ("HarnaxFeatures/CallMetrics/CallInvocationsView.swift", "content"),
        ("HarnaxFeatures/Models/ModelListView.swift", "content"),
        ("HarnaxFeatures/Chat/PlanPanelView.swift", "content"),
        ("HarnaxFeatures/Tasks/TaskLogListView.swift", "content")
    ]

    func testThePinnedSwitchTakesTheHeightTheRowLeaves() throws {
        for entry in pinned {
            let text = try FeatureSources.code(of: entry.file)
            let stack = try FeatureSources.block(text, from: "VStack(spacing: 0)")
            let framed = try FeatureSources.captures(
                "\n[ \t]*(\(entry.carrier))[ \t]*\n[ \t]*\\.frame\\(maxWidth: \\.infinity, maxHeight: \\.infinity\\)",
                in: stack
            )
            XCTAssertEqual(
                framed.count,
                1,
                "\(entry.file) stacks a pinned row above \(entry.carrier) without handing it the rest of the "
                    + "screen: the bar and the state glyph are centred together and land mid-screen while the "
                    + "list loads"
            )
        }
    }

    /// The fix belongs at the call site, and this keeps it there.
    ///
    /// `HXStateView` is also drawn beside a footnote (`SystemDomain/WechatLoginSheet.swift:28-35`) and inside
    /// a card (`CallMetrics/CallMetricsView.swift:275`); a state view that claimed every pixel it was offered
    /// would push those siblings to the bottom edge.
    func testTheStateViewStillSizesToItsOwnContent() throws {
        let body = try FeatureSources.block(
            try FeatureSources.code(of: "HarnaxKit/Components/HXStateView.swift"),
            from: "public var body"
        )
        XCTAssertFalse(
            body.contains("maxHeight"),
            "HXStateView grew a maxHeight: it would then push the siblings it shares a stack with off the "
                + "bottom edge — the screen that pins a row owns the slack, not the glyph"
        )
    }
}
