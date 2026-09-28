import Foundation

public enum HarnaxLanguage: String, CaseIterable, Identifiable, Sendable {
    case system
    case en
    case zhHans = "zh-Hans"

    public static let storageKey = "harnax.language"

    public var id: String { rawValue }

    /// Shown in the picker in its own language, which is the convention for language lists.
    public var nativeName: String {
        switch self {
        case .system: return ""
        case .en: return "English"
        case .zhHans: return "简体中文"
        }
    }

    /// "Follow the device" is not a language name, so it needs a localized label instead.
    public var labelKey: String? {
        self == .system ? "me.language.followSystem" : nil
    }

    /// `nil` hands resolution back to the OS, so the per-app language in Settings keeps working.
    var localeName: String? {
        switch self {
        case .system: return nil
        case .en: return "en"
        case .zhHans: return "zh-Hans"
        }
    }

    /// Which side of a bilingual data column the user reads. Backend rows carry both, and only the
    /// device-language case can be answered by the OS.
    public var prefersChinese: Bool {
        switch self {
        case .zhHans: return true
        case .en: return false
        case .system: return Locale.current.language.languageCode?.identifier == "zh"
        }
    }
}

public extension HarnaxLanguage {
    /// The picked language, or the device's when the app is set to follow it.
    static var effective: HarnaxLanguage { HarnaxCatalog.shared.language }
}
