import XCTest
@testable import HarnaxFeatures

/// One title style for the whole app, and one place that decides it.
///
/// Reported 2026-10-11: every screen's title sat top-left in iOS's large title block. The house answer is
/// `harnaxTitle` (`HarnaxKit/Components/HXScreen.swift`), which pins the inline display mode so the name is
/// centred at the bar's own smaller size — a bare `navigationTitle` silently brings the large block back, and
/// the compiler cannot see the difference, so these gates read the sources.
final class TitleStyleGateTests: XCTestCase {
    /// A screen that calls `navigationTitle` itself is a screen that can drift back to large titles, and one
    /// such screen undoes the point of the change: the two styles sit one push apart.
    func testEveryScreenTitleGoesThroughTheHouseModifier() throws {
        for domain in try FeatureSources.contentsOfFeatureDomains() {
            for file in domain.files {
                XCTAssertFalse(
                    file.text.contains(".navigationTitle("),
                    "\(file.path) titles itself with a bare navigationTitle: it would show the large "
                        + "left-aligned block the other screens just dropped — hand the title to `harnaxTitle`"
                )
            }
        }
    }

    /// The style lives in the modifier. If the display-mode line leaves it, every screen silently goes back
    /// to large titles while this file still compiles — the one failure mode worth a gate of its own.
    func testTheHouseModifierPinsTheInlineDisplayMode() throws {
        let body = try FeatureSources.block(
            try FeatureSources.code(of: "HarnaxKit/Components/HXScreen.swift"),
            from: "func harnaxTitle"
        )
        XCTAssertTrue(
            body.contains(".navigationBarTitleDisplayMode(.inline)"),
            "harnaxTitle lost its inline display mode, so the app is back to large titles while every call "
                + "site still reads as if it were centred"
        )
    }
}
