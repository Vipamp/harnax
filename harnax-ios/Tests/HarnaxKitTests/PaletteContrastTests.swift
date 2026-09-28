import XCTest

@testable import HarnaxKit

final class PaletteContrastTests: XCTestCase {
    private let tiers: [(name: String, palette: Palette, expectDark: Bool)] = [
        ("light", .light, false),
        ("dark", .dark, true),
    ]

    func testEverySlotIsDefinedInBothTiers() {
        for (name, palette, _) in tiers {
            for slot in PaletteSlot.allCases {
                XCTAssertNotNil(palette.slots[slot], "\(name) tier is missing \(slot.rawValue)")
            }
            XCTAssertEqual(palette.slots.count, PaletteSlot.allCases.count, "\(name) tier defines unknown slots")
        }
    }

    func testContrastPairsMeetTheirFloor() {
        for (tierName, palette, _) in tiers {
            for pair in Palette.contrastRequirements {
                let ratio = palette[pair.foreground].contrastRatio(with: palette[pair.background])
                XCTAssertGreaterThanOrEqual(
                    ratio,
                    pair.minimum,
                    "\(tierName): \(pair.foreground.rawValue) on \(pair.background.rawValue) is \(String(format: "%.2f", ratio)), needs \(pair.minimum)"
                )
            }
        }
    }

    func testTiersDoNotGetSwapped() {
        XCTAssertTrue(Palette.light[.surface].luminance > 0.5)
        XCTAssertTrue(Palette.dark[.surface].luminance < 0.2)
        XCTAssertTrue(
            Palette.light[.background].contrastRatio(with: Palette.light[.surface]) > 1.0,
            "background must sit off surface in light, otherwise cards are invisible"
        )
        XCTAssertTrue(
            Palette.dark[.background].contrastRatio(with: Palette.dark[.surface]) > 1.0,
            "background must sit off surface in dark, otherwise cards are invisible"
        )
    }

    /// Hairlines are decorative, but a separator that matches its container makes groups unreadable.
    func testSeparatorIsVisibleAgainstBothSurfaces() {
        for (_, palette, _) in tiers {
            XCTAssertGreaterThanOrEqual(palette[.separator].contrastRatio(with: palette[.surface]), 1.15)
            XCTAssertGreaterThanOrEqual(palette[.separator].contrastRatio(with: palette[.background]), 1.15)
            XCTAssertGreaterThanOrEqual(palette[.surfaceAlt].contrastRatio(with: palette[.surface]), 1.05)
        }
    }

    func testHexRoundTrip() {
        XCTAssertEqual(HarnaxRGB(hex: 0x2A5FE8).hex, 0x2A5FE8)
        XCTAssertEqual(HarnaxRGB(hex: 0x000000).contrastRatio(with: HarnaxRGB(hex: 0xFFFFFF)), 21, accuracy: 0.001)
    }
}
