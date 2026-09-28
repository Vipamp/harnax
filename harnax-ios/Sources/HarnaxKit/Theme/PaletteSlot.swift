import Foundation

/// The semantic colour slots every screen must draw from. Slots are roles, not hues: a caller asks for
/// `textSecondary`, never for grey.
public enum PaletteSlot: String, CaseIterable, Sendable {
    case background
    case surface
    case surfaceAlt
    case separator
    case textPrimary
    case textSecondary
    case textTertiary
    case brand
    case onBrand
    case success
    case warning
    case danger
    case indigo
    case purple
    case teal
}
