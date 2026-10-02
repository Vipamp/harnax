import Foundation

public struct ContrastPair: Equatable, Sendable {
    public let foreground: PaletteSlot
    public let background: PaletteSlot
    public let minimum: Double

    public init(_ foreground: PaletteSlot, on background: PaletteSlot, minimum: Double = 4.5) {
        self.foreground = foreground
        self.background = background
        self.minimum = minimum
    }
}

public struct Palette: Sendable {
    public let slots: [PaletteSlot: HarnaxRGB]

    public init(_ values: [PaletteSlot: UInt32]) {
        slots = values.mapValues { HarnaxRGB(hex: $0) }
    }

    public subscript(_ slot: PaletteSlot) -> HarnaxRGB {
        guard let value = slots[slot] else {
            preconditionFailure("Palette is missing slot \(slot.rawValue)")
        }
        return value
    }

    public static func palette(isDark: Bool) -> Palette { isDark ? dark : light }

    public static func rgb(_ slot: PaletteSlot, isDark: Bool) -> HarnaxRGB {
        palette(isDark: isDark)[slot]
    }

    /// Light tier keeps the mockup hues but pulls the pale ones down: the iOS system greys and oranges
    /// used in `ui-mockup/index.html` land under WCAG AA once they carry text.
    public static let light = Palette([
        .background: 0xF2F4F7,
        .surface: 0xFFFFFF,
        .surfaceAlt: 0xE9ECF1,
        .separator: 0xE0E3E8,
        .textPrimary: 0x1A1C20,
        .textSecondary: 0x5A6069,
        .textTertiary: 0x666B73,
        .brand: 0x2A5FE8,
        .onBrand: 0xFFFFFF,
        .success: 0x17784A,
        .warning: 0xA26000,
        .danger: 0xC0392B,
        .indigo: 0x4560D8,
        .purple: 0x9646BE,
        .teal: 0x207584,
    ])

    public static let dark = Palette([
        .background: 0x0E1014,
        .surface: 0x171A20,
        .surfaceAlt: 0x1F232B,
        .separator: 0x2C313A,
        .textPrimary: 0xF2F4F7,
        .textSecondary: 0xA9B0BA,
        .textTertiary: 0x818A95,
        .brand: 0x5C8BFF,
        .onBrand: 0x0B0D10,
        .success: 0x3FBF7F,
        .warning: 0xE0A23C,
        .danger: 0xF06A5F,
        .indigo: 0x90A7FF,
        .purple: 0xD28DF1,
        .teal: 0x70D4E5,
    ])

    /// Every combination a component in this kit is allowed to paint. The contrast test walks this table
    /// against both tiers, so a new component usage has to add its pair here first.
    public static let contrastRequirements: [ContrastPair] = [
        ContrastPair(.textPrimary, on: .background),
        ContrastPair(.textPrimary, on: .surface),
        ContrastPair(.textPrimary, on: .surfaceAlt),
        ContrastPair(.textSecondary, on: .background),
        ContrastPair(.textSecondary, on: .surface),
        ContrastPair(.textTertiary, on: .surface),
        ContrastPair(.textTertiary, on: .surfaceAlt),
        ContrastPair(.onBrand, on: .brand),
        ContrastPair(.success, on: .background),
        ContrastPair(.success, on: .surface),
        ContrastPair(.warning, on: .background),
        ContrastPair(.warning, on: .surface),
        ContrastPair(.danger, on: .background),
        ContrastPair(.danger, on: .surface),
        ContrastPair(.indigo, on: .background),
        ContrastPair(.indigo, on: .surface),
        ContrastPair(.purple, on: .background),
        ContrastPair(.purple, on: .surface),
        ContrastPair(.teal, on: .background),
        ContrastPair(.teal, on: .surface),
    ]
}
