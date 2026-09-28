import Foundation
import HarnaxKit

/// Copy for the two preferences the app owns: which appearance tier to draw in, and which language to
/// read the catalogue from.
public enum AppearanceSummary {
    public static func themeKey(_ mode: ThemeMode) -> String {
        switch mode {
        case .system: return "me.theme.system"
        case .light: return "me.theme.light"
        case .dark: return "me.theme.dark"
        }
    }

    public static func themeMode(for index: Int) -> ThemeMode {
        ThemeMode.allCases[index]
    }

    public static func index(of mode: ThemeMode) -> Int {
        ThemeMode.allCases.firstIndex(of: mode) ?? 0
    }

    /// Ordering is `allCases` ordering, which is what the two helpers above assume.
    public static var themeOptions: [HXSegmentOption] {
        ThemeMode.allCases.enumerated().map { HXSegmentOption(id: $0.offset, themeKey($0.element)) }
    }

    /// Language names are shown in their own language, except "follow the device", which is a behaviour
    /// rather than a locale.
    public static func languageLabel(_ language: HarnaxLanguage) -> String {
        if let key = language.labelKey { return hx(key) }
        return language.nativeName
    }

    /// The `F1` summary: what the app draws in, and which language it actually reads. "Follow the device"
    /// resolves to a language name here, because the row reports state rather than the menu choice.
    public static func line(mode: ThemeMode, language: HarnaxLanguage) -> String {
        "\(hx(themeKey(mode))) · \(resolvedLanguageName(language))"
    }

    private static func resolvedLanguageName(_ language: HarnaxLanguage) -> String {
        guard language == .system else { return language.nativeName }
        let code = Locale.current.language.languageCode?.identifier ?? "en"
        return code == "zh" ? HarnaxLanguage.zhHans.nativeName : HarnaxLanguage.en.nativeName
    }
}

