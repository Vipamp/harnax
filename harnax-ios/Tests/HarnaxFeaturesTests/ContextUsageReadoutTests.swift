import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The header readout's two halves — what the chip says, and which number each of its five rows puts against
/// which word.
///
/// The pairing is the thing under test. `ContextUsage` carries two token counts because they answer different
/// questions, and the console's tooltip lists five rows in a fixed order
/// (`harnax-webui/src/pages/session/index.tsx:34-57`); a readout that shows the estimate under 「billed」 is
/// not a layout slip but a wrong number about the user's money.
final class ContextUsageReadoutTests: XCTestCase {
    /// Every number here is different on purpose, so a row that took another row's value cannot pass.
    private let usage = ContextUsage(
        messageCount: 9,
        estimatedTokens: 5_504,
        lastCallInputTokens: 50_000,
        contextWindow: 200_000,
        windowSource: "MODEL_FIELD",
        ratio: 0.25,
        triggerTokens: 180_000,
        triggerMessages: 50
    )

    func testTheChipSaysThePercentageThenWhereItsNumberCameFrom() {
        XCTAssertEqual(ContextUsageReadout.headline(for: usage), "25% · \(hx("chat.context.basis.billed"))")
    }

    func testTheFiveRowsKeepTheConsolesOrderAndTheirOwnNumbers() {
        let rows = ContextUsageReadout.rows(for: usage)
        XCTAssertEqual(
            rows.map(\.label),
            [
                hx("chat.context.billed"),
                hx("chat.context.estimated"),
                hx("chat.context.messages"),
                "\(hx("chat.context.window")) · \(hx("chat.context.source.modelField"))",
                hx("chat.context.trigger")
            ]
        )
        XCTAssertEqual(rows.map(\.value), ["50.00K", "5.50K", "9", "200.00K", "180.00K"])
    }

    /// The four token rows read on the token screen's tiers: millions above a million, thousands above a
    /// thousand, plain below it (`TokenFigures.token`).
    func testTheTokenRowsAbbreviateOnTheTokenScreensTiers() {
        let rows = ContextUsageReadout.rows(
            for: ContextUsage(
                messageCount: 2,
                estimatedTokens: 999,
                lastCallInputTokens: 1_500_000,
                contextWindow: 1_000,
                ratio: 1_500,
                triggerTokens: 0
            )
        )
        XCTAssertEqual(rows.map(\.value), ["1.50M", "999", "2", "1.00K", "0"])
    }

    /// A count of messages is not a count of tokens, and `1.50K` would read as a window rather than as a
    /// transcript length.
    func testTheMessageCountStaysACount() {
        let rows = ContextUsageReadout.rows(for: ContextUsage(messageCount: 1_500, ratio: 0.5))
        XCTAssertEqual(rows[2].value, "1500")
    }

    /// A session whose calls have never been billed has no bill to show, and `0` there would read as a free
    /// call rather than as nothing (`index.tsx:35-38`).
    func testABilllessSessionSaysThereIsNoBillAndRebasesTheChip() {
        let estimate = ContextUsage(
            messageCount: 3,
            estimatedTokens: 1_200,
            lastCallInputTokens: nil,
            contextWindow: 32_000,
            windowSource: "UPSTREAM_TABLE",
            ratio: 0.0372,
            triggerTokens: 12_000,
            triggerMessages: 50
        )
        let rows = ContextUsageReadout.rows(for: estimate)
        XCTAssertEqual(rows.first?.value, hx("chat.context.noneYet"))
        XCTAssertNotEqual(rows.first?.value, "0")
        XCTAssertEqual(ContextUsageReadout.headline(for: estimate), "3.7% · \(hx("chat.context.basis.estimated"))")
        XCTAssertTrue(rows[3].label.hasSuffix(hx("chat.context.source.upstreamTable")))
    }

    /// A fourth tier added upstream has to keep its own wire spelling rather than be read as the fallback,
    /// which is why `windowSourceTitleKey` answers `nil` for a name this build does not know.
    func testAnUnknownWindowTierKeepsItsOwnWord() {
        let unknown = ContextUsage(
            messageCount: 1,
            estimatedTokens: 10,
            lastCallInputTokens: 20,
            contextWindow: 100,
            windowSource: "SOMETHING_NEW",
            ratio: 0.2
        )
        let label = ContextUsageReadout.rows(for: unknown)[3].label
        XCTAssertTrue(label.hasSuffix("SOMETHING_NEW"), label)
        XCTAssertFalse(label.contains(hx("chat.context.source.fallback")), label)
    }

    /// No tier at all is the fallback's own case, not an unknown word.
    func testAMissingTierReadsAsTheFallback() {
        let bare = ContextUsage(
            messageCount: 1,
            estimatedTokens: 10,
            lastCallInputTokens: 20,
            contextWindow: 100,
            ratio: 0.2
        )
        XCTAssertTrue(
            ContextUsageReadout.rows(for: bare)[3].label.hasSuffix(hx("chat.context.source.fallback"))
        )
    }
}
