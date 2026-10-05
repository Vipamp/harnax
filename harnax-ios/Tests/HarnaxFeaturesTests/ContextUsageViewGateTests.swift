import XCTest

/// Gate for the two things the chat screen's context row has to keep being: a readout that is actually mounted
/// and actually hidden by the one judgement, and a compaction entry that is actually in the chips row.
///
/// Read off source text like every other view gate here — `swift test` cannot reach a `View`, so a chip that
/// lost its `ToolbarItem` is neither a type error nor a failing assertion. It is a feature the user cannot
/// find, which is how the console's filter menus used to drift (`ListToolbarGateTests`).
final class ContextUsageViewGateTests: XCTestCase {
    private static let chatView = "HarnaxFeatures/Chat/ChatView.swift"

    /// The chip is offered because the view model answered with a reading, and it is hidden because the view
    /// model answered with nothing. The view re-decides neither.
    func testTheOccupancyChipIsMountedAndHiddenByTheViewModelsOwnJudgement() throws {
        let text = try ToolbarSources.contents(of: Self.chatView)
        XCTAssertTrue(
            text.contains("ToolbarItem(placement: .primaryAction) { contextUsageTag }"),
            "contextUsageTag is declared but never put in the toolbar — the readout would exist only in the view model"
        )
        let chip = try ToolbarSources.block(text, from: "private var contextUsageTag")
        XCTAssertTrue(
            chip.contains("if let usage = vm.contextUsage"),
            "the chip has to open on the view model's reading, not on a second gate of its own"
        )
        XCTAssertFalse(
            chip.contains("isReadable"),
            "a view that re-checks `isReadable` is a second reader of one state: the model already dropped the "
                + "legs that answer without a reading, and two gates drift the day one of them is revised"
        )
        // The warning tint is the readout's only news, so it has to be the trigger's own judgement.
        XCTAssertTrue(chip.contains("usage.isAtAutoTrigger ? .warning : nil"), chip)
    }

    /// The console's order is permission, compact, stop sandbox, clear (`ChatWindow.tsx:3708-3765`), and the
    /// entry is inert for the length of the run it just started.
    func testTheCompactionEntrySitsInTheChipsRowInTheConsolesPlace() throws {
        let text = try ToolbarSources.contents(of: Self.chatView)
        let row = try ToolbarSources.block(text, from: "private struct ChatComposerToolbar")
        let order = ["permissionChip", "chat.composer.compact", "chat.composer.stopSandbox", "chat.composer.clear"]
        var last = row.startIndex
        for marker in order {
            guard let found = row.range(of: marker, range: last..<row.endIndex) else {
                return XCTFail("the chips row lost \(marker), or they no longer come in the console's order")
            }
            last = found.upperBound
        }
        XCTAssertTrue(row.contains("vm.requestCompact()"), "the entry has to reach the view model")
        let compact = row[row.range(of: "chat.composer.compact")!.upperBound...]
        let chip = String(compact[..<compact.range(of: "chat.composer.stopSandbox")!.lowerBound])
        XCTAssertTrue(chip.contains("isMuted: vm.isStreaming"), "…and go dead for the length of the request")
        XCTAssertTrue(
            chip.contains(".accessibilityHint(hx(\"chat.context.compactTip\"))"),
            "the sentence that says what compaction leaves on screen has to travel with the entry"
        )
    }
}
