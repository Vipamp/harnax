import XCTest

@testable import HarnaxCore

/// The occupancy readout's four judgements, plus the wire shape they are read out of.
///
/// Every rule here is a copy of a rule the console already runs
/// (`harnax-webui/src/pages/session/components/contextUsage.ts`), because the two screens are meant to say the
/// same number for the same session. The expected strings below were written against that source rather than
/// against this implementation's output, which is the only way the pair of them says anything.
final class ContextUsageTests: XCTestCase {
    /// The runtime answers all eight keys (`ContextUsageResponse.kt:41-50`), so a fixture spells the ones it is
    /// not interested in out instead of leaving them to be guessed at.
    private func payload(
        messageCount: Int = 0,
        estimatedTokens: Int = 0,
        lastCallInputTokens: Int? = nil,
        contextWindow: Int = 0,
        windowSource: String? = nil,
        ratio: Double = 0,
        triggerTokens: Int = 0,
        triggerMessages: Int = 0
    ) -> String {
        let bill = lastCallInputTokens.map { "\"lastCallInputTokens\":\($0)," } ?? ""
        let tier = windowSource.map { "\"windowSource\":\"\($0)\"," } ?? ""
        return """
        {"messageCount":\(messageCount),"estimatedTokens":\(estimatedTokens),\(bill)\
        "contextWindow":\(contextWindow),\(tier)"ratio":\(ratio),"triggerTokens":\(triggerTokens),\
        "triggerMessages":\(triggerMessages)}
        """
    }

    private func usage(_ json: String) throws -> ContextUsage {
        try JSONDecoder().decode(ContextUsage.self, from: Data(json.utf8))
    }

    // MARK: - the wire shape

    /// Every field keeps the data class's own property name — a key spelled from this side's habit would decode
    /// to blanks rather than fail, and a blank window reads quietly as "no reading".
    func testTheRuntimePayloadFillsEveryField() throws {
        let decoded = try usage(#"""
        {"messageCount":42,"estimatedTokens":1800,"lastCallInputTokens":36000,"contextWindow":128000,
        "windowSource":"MODEL_FIELD","ratio":0.28125,"triggerTokens":108000,"triggerMessages":50}
        """#)

        XCTAssertEqual(decoded.messageCount, 42)
        XCTAssertEqual(decoded.estimatedTokens, 1800)
        XCTAssertEqual(decoded.lastCallInputTokens, 36_000)
        XCTAssertEqual(decoded.contextWindow, 128_000)
        XCTAssertEqual(decoded.windowSource, "MODEL_FIELD")
        XCTAssertEqual(decoded.ratio, 0.28125)
        XCTAssertEqual(decoded.triggerTokens, 108_000)
        XCTAssertEqual(decoded.triggerMessages, 50)
        XCTAssertTrue(decoded.isReadable)
    }

    /// The bill is dropped from the payload rather than sent as `null`, since admin omits null keys on the way
    /// out. Absence has to read as "no bill" — a `0` there would pull the headline to `0%`.
    func testAnAbsentBillDecodesAsNoBill() throws {
        let decoded = try usage(payload(estimatedTokens: 900, contextWindow: 32_000, ratio: 0.028))

        XCTAssertNil(decoded.lastCallInputTokens)
        XCTAssertEqual(decoded.basis, .estimated)
        XCTAssertEqual(decoded.numeratorTokens, 900)
    }

    /// A payload missing a required number is a shape this build does not speak, and it fails rather than
    /// defaulting: an absent `ratio` that decoded to `0` would pass `isReadable` on a real window and tell the
    /// user their context is empty.
    func testAPayloadMissingARequiredKeyIsNotDecodedIntoDefaults() {
        XCTAssertThrowsError(try usage(#"{"contextWindow":128000}"#))
    }

    // MARK: - no reading is not a zero reading

    /// `ResultVo.success(null)` arrives as this empty value, and a header that rendered it would say "0% used"
    /// about a session the router has no view of at all.
    func testTheEmptyAnswerCarriesNoReading() {
        XCTAssertFalse(ContextUsage().isReadable)
    }

    /// A zero denominator is the runtime saying it never resolved a window, so the ratio published beside it is
    /// arithmetic on nothing.
    func testAZeroWindowIsNoReadingEvenWhenTheRatioLooksUsable() throws {
        let decoded = try usage(payload(estimatedTokens: 1000, contextWindow: 0, ratio: 0.42))

        XCTAssertEqual(decoded.ratio, 0.42, "the payload is kept as it was answered")
        XCTAssertFalse(decoded.isReadable)
    }

    /// An upstream division that produced no number is no reading either, whatever the window says.
    func testANonFiniteRatioIsNoReading() {
        for ratio in [Double.nan, Double.infinity, -.infinity] {
            let decoded = ContextUsage(contextWindow: 128_000, ratio: ratio)
            XCTAssertFalse(decoded.isReadable, "\(ratio)")
        }
    }

    // MARK: - the headline number

    /// Above ten per cent the header shows whole per cent; the decimals below that exist because a five-turn
    /// conversation is a fraction of a large window, not because the format changed.
    func testWholePerCentAboveTen() {
        XCTAssertEqual(ContextUsage.percentText(0.5), "50%")
        XCTAssertEqual(ContextUsage.percentText(0.1234), "12%")
        XCTAssertEqual(ContextUsage.percentText(0.9999), "100%")
    }

    func testOneDecimalBetweenOneAndTenPerCent() {
        XCTAssertEqual(ContextUsage.percentText(0.095), "9.5%")
        XCTAssertEqual(ContextUsage.percentText(0.01), "1.0%")
    }

    /// Below one per cent, two decimals with the trailing zeros taken off — `0.4%` rather than `0.40%`, which
    /// is what the console's `Number(...)` prints.
    func testTwoDecimalsBelowOnePerCentWithTrailingZerosDropped() {
        XCTAssertEqual(ContextUsage.percentText(0.00456), "0.46%")
        XCTAssertEqual(ContextUsage.percentText(0.004), "0.4%")
        XCTAssertEqual(ContextUsage.percentText(0.0001), "0.01%")
    }

    /// A share too small to survive two decimals collapses to `0%` rather than `0.00%`.
    func testAFractionBelowTwoShowDigitsCollapsesToZero() {
        XCTAssertEqual(ContextUsage.percentText(0.000004), "0%")
    }

    /// Zero, a negative and a non-finite ratio all read the same way, because the only honest text for "no
    /// occupancy measured" is the one the console prints for it.
    func testZeroAndUnusableRatiosReadAsZeroPerCent() {
        for ratio in [0.0, -0.1, Double.nan, Double.infinity] {
            XCTAssertEqual(ContextUsage.percentText(ratio), "0%", "\(ratio)")
        }
    }

    // MARK: - where the numerator came from

    /// The bill wins whenever the router has one, including when it disagrees sharply with the estimate: the two
    /// are not proportional, so this is a choice of source rather than a tie-break of magnitude.
    func testTheBillIsTheNumeratorWheneverTheRouterHasOne() throws {
        let decoded = try usage(
            payload(estimatedTokens: 1800, lastCallInputTokens: 36_000, contextWindow: 128_000, ratio: 0.28125)
        )

        XCTAssertEqual(decoded.numeratorTokens, 36_000)
        XCTAssertEqual(decoded.basis, .billed)
    }

    /// A bill of zero is still a bill — the session had a call and the router recorded it. Reading falsy the way
    /// JavaScript would call an empty billed context "estimated".
    func testABillOfZeroIsStillABill() throws {
        let decoded = try usage(payload(estimatedTokens: 900, lastCallInputTokens: 0, contextWindow: 32_000))

        XCTAssertEqual(decoded.basis, .billed)
        XCTAssertEqual(decoded.numeratorTokens, 0)
    }

    func testTheBasisWordsHaveTheirOwnKeys() {
        XCTAssertEqual(ContextUsageBasis.billed.titleKey, "chat.context.basis.billed")
        XCTAssertEqual(ContextUsageBasis.estimated.titleKey, "chat.context.basis.estimated")
    }

    // MARK: - the automatic trigger

    /// The comparison is `>=`: at the threshold the next turn compacts whether or not anyone asks, and that is
    /// the whole reason the readout is coloured.
    func testTheThresholdItselfCountsAsAtTrigger() throws {
        let decoded = try usage(payload(estimatedTokens: 108_000, contextWindow: 128_000, triggerTokens: 108_000))

        XCTAssertTrue(decoded.isAtAutoTrigger)
    }

    /// The trigger is compared against the same numerator the ratio used. A session whose bill is below the
    /// threshold while its estimate sits over it is not about to compact on the number this row shows, and
    /// colouring it off the other numerator would be the one misleading thing this readout can do.
    func testTheTriggerUsesTheNumeratorRatherThanTheEstimate() throws {
        let decoded = try usage(
            payload(
                estimatedTokens: 120_000, lastCallInputTokens: 90_000,
                contextWindow: 128_000, triggerTokens: 108_000
            )
        )

        XCTAssertFalse(decoded.isAtAutoTrigger)
    }

    /// A trigger of zero is the runtime saying it could not work the number out for this model, so nothing is
    /// "at" it. Without the guard every unknown model would come back looking ready to compact.
    func testATriggerOfZeroMeansNothingIsAtIt() throws {
        let decoded = try usage(payload(estimatedTokens: 500_000, contextWindow: 128_000, triggerTokens: 0))

        XCTAssertFalse(decoded.isAtAutoTrigger)
    }

    // MARK: - which tier supplied the denominator

    func testEachKnownTierNamesItself() throws {
        for (raw, key) in [
            ("MODEL_FIELD", "chat.context.source.modelField"),
            ("UPSTREAM_TABLE", "chat.context.source.upstreamTable"),
            ("FALLBACK", "chat.context.source.fallback"),
        ] {
            let decoded = try usage(payload(contextWindow: 1000, windowSource: raw))
            XCTAssertEqual(decoded.windowSourceTitleKey, key, raw)
        }
    }

    /// An absent tier is the fallback's answer, which is the server's own default
    /// (`ContextUsageResponse.kt:46`).
    func testAMissingTierReadsAsTheFallback() throws {
        let decoded = try usage(payload(contextWindow: 1000, ratio: 0.1))

        XCTAssertNil(decoded.windowSource)
        XCTAssertEqual(decoded.windowSourceTitleKey, "chat.context.source.fallback")
    }

    /// A fourth tier added upstream keeps its own name instead of being reported as "the runtime had no idea" —
    /// which is why `windowSource` is the wire string and not an enum invented on this side.
    func testAnUnknownTierKeepsItsOwnNameRatherThanBorrowingTheFallbacks() throws {
        let decoded = try usage(payload(contextWindow: 1000, windowSource: "SOMETHING_NEW"))

        XCTAssertEqual(decoded.windowSource, "SOMETHING_NEW")
        XCTAssertNil(decoded.windowSourceTitleKey)
    }
}
