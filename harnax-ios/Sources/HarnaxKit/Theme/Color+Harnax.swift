import SwiftUI
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

public extension Color {
    /// Resolves a semantic slot against the drawing appearance, so one call site serves both tiers.
    static func hx(_ slot: PaletteSlot) -> Color {
        #if canImport(UIKit)
        return Color(uiColor: UIColor { traits in
            let rgb = Palette.rgb(slot, isDark: traits.userInterfaceStyle == .dark)
            return UIColor(red: rgb.red, green: rgb.green, blue: rgb.blue, alpha: 1)
        })
        #else
        return Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            let rgb = Palette.rgb(slot, isDark: isDark)
            return NSColor(srgbRed: rgb.red, green: rgb.green, blue: rgb.blue, alpha: 1)
        })
        #endif
    }

    /// Tinted fill behind a badge or chip. The hue stays in the token, only the alpha varies, so the
    /// foreground pair in `Palette.contrastRequirements` remains the thing under test.
    static func hxFill(_ slot: PaletteSlot, alpha: Double = 0.14) -> Color {
        Color.hx(slot).opacity(alpha)
    }
}
