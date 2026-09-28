import XCTest
import HarnaxKit
@testable import HarnaxFeatures

/// The `F1` summary row and the `F1a` picker read their copy through this one place.
final class AppearanceSummaryTests: XCTestCase {
    func testLineShowsThePickedLanguageInItsOwnTongue() {
        XCTAssertEqual(
            AppearanceSummary.line(mode: .dark, language: .zhHans),
            "\(hx("me.theme.dark")) · 简体中文"
        )
        XCTAssertEqual(
            AppearanceSummary.line(mode: .light, language: .en),
            "\(hx("me.theme.light")) · English"
        )
    }

    func testFollowSystemResolvesToALanguageNameRatherThanTheMenuChoice() {
        let line = AppearanceSummary.line(mode: .system, language: .system)
        XCTAssertTrue(
            line.hasSuffix(" · 简体中文") || line.hasSuffix(" · English"),
            "expected a language name, got \(line)"
        )
        // The picker row, in contrast, names the behaviour.
        XCTAssertEqual(AppearanceSummary.languageLabel(.system), hx("me.language.followSystem"))
    }

    func testSegmentOrderFollowsTheEnumOrder() {
        XCTAssertEqual(
            AppearanceSummary.themeOptions.map(\.titleKey),
            ["me.theme.system", "me.theme.light", "me.theme.dark"]
        )
        XCTAssertEqual(AppearanceSummary.themeOptions.map(\.id), [0, 1, 2])
    }

    func testIndexAndModeRoundTripForEveryTier() {
        for mode in ThemeMode.allCases {
            XCTAssertEqual(AppearanceSummary.themeMode(for: AppearanceSummary.index(of: mode)), mode)
        }
    }
}
