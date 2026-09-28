import SwiftUI

public enum ThemeMode: String, CaseIterable, Identifiable, Sendable {
    case system
    case light
    case dark

    public var id: String { rawValue }

    public static let storageKey = "harnax.themeMode"

    /// `nil` means "follow the device", which keeps `overrideUserInterfaceStyle` at `.unspecified`.
    public var forcedDark: Bool? {
        switch self {
        case .system: return nil
        case .light: return false
        case .dark: return true
        }
    }

    /// Guards against a corrupt AppStorage value; `@AppStorage` hands us the raw string.
    public static func stored(_ rawValue: String) -> ThemeMode {
        ThemeMode(rawValue: rawValue) ?? .system
    }
}
