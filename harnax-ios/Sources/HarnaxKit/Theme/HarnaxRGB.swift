import Foundation

/// An 8-bit-per-channel colour stored as data so the palette can be audited without a rendering pass.
public struct HarnaxRGB: Equatable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double

    public init(hex: UInt32) {
        red = Double((hex >> 16) & 0xFF) / 255
        green = Double((hex >> 8) & 0xFF) / 255
        blue = Double(hex & 0xFF) / 255
    }

    public init(red: Double, green: Double, blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    public var hex: UInt32 {
        (UInt32((red * 255).rounded()) << 16) | (UInt32((green * 255).rounded()) << 8) | UInt32((blue * 255).rounded())
    }

    private static func linearized(_ channel: Double) -> Double {
        channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
    }

    /// WCAG 2.1 relative luminance.
    public var luminance: Double {
        0.2126 * Self.linearized(red) + 0.7152 * Self.linearized(green) + 0.0722 * Self.linearized(blue)
    }

    /// WCAG contrast ratio, 1...21.
    public func contrastRatio(with other: HarnaxRGB) -> Double {
        let lhs = luminance
        let rhs = other.luminance
        let higher = max(lhs, rhs)
        let lower = min(lhs, rhs)
        return (higher + 0.05) / (lower + 0.05)
    }
}
