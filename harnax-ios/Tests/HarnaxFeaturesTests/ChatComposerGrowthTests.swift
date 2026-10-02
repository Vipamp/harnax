import XCTest
@testable import HarnaxFeatures

/// The composer's growth ceiling, measured against the screen it is on.
final class ChatComposerGrowthTests: XCTestCase {
    private let lineHeight: CGFloat = 22.5

    func testHalfAScreenIsTheCeiling() {
        XCTAssertEqual(ChatComposerGrowth.lineCap(screenHeight: 852, lineHeight: lineHeight), 18)
    }

    /// The rule is 「不超过半屏」, so the assertion walks heights rather than one of them.
    func testTheFieldNeverEatsMoreThanHalfTheScreen() {
        for height in [320.0, 480.0, 700.0, 852.0, 1024.0] {
            let cap = ChatComposerGrowth.lineCap(screenHeight: height, lineHeight: lineHeight)
            let taken = CGFloat(cap) * lineHeight + ChatComposerGrowth.verticalPadding
            XCTAssertLessThanOrEqual(taken, height * ChatComposerGrowth.fraction, "at height \(height)")
        }
    }

    func testAScreenTooShortForHalfStillGivesTwoLines() {
        XCTAssertEqual(ChatComposerGrowth.lineCap(screenHeight: 40, lineHeight: lineHeight), 2)
    }

    /// The height arrives from a layout pass, so the first frame has none — and an unmeasured screen must not
    /// collapse the field to two lines.
    func testUnmeasuredHeightKeepsTheOldSixLines() {
        XCTAssertEqual(ChatComposerGrowth.lineCap(screenHeight: 0, lineHeight: lineHeight), 6)
        XCTAssertEqual(ChatComposerGrowth.lineCap(screenHeight: 852, lineHeight: 0), 6)
    }
}
