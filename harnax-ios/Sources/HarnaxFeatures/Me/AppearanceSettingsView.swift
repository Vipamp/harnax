import SwiftUI
import HarnaxKit

/// The two picks behind every screen in the app: which appearance tier to draw, and which catalogue to
/// read copy from. Both take effect on the spot, without a relaunch.
public struct AppearanceSettingsView: View {
    @AppStorage(ThemeMode.storageKey) private var storedTheme = ThemeMode.system.rawValue
    @ObservedObject private var catalog = HarnaxCatalog.shared

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HXSectionHeader("me.theme")
                HXSegmented(AppearanceSummary.themeOptions, selection: themeSelection)
                HXSectionHeader("me.language")
                HXGroupCard { languageRows }
            }
            .padding(16)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationTitle(Text(verbatim: hx("me.themeAndLanguage")))
    }

    private var themeSelection: Binding<Int> {
        Binding(
            get: { AppearanceSummary.index(of: ThemeMode.stored(storedTheme)) },
            set: { storedTheme = AppearanceSummary.themeMode(for: $0).rawValue }
        )
    }

    @ViewBuilder
    private var languageRows: some View {
        ForEach(HarnaxLanguage.allCases) { language in
            Button {
                catalog.language = language
            } label: {
                HXRow(
                    text: AppearanceSummary.languageLabel(language),
                    systemImage: "character.bubble",
                    divider: language != HarnaxLanguage.allCases.last,
                    trailing: {
                        if catalog.language == language {
                            HXStatusDot(tone: .success)
                        }
                    }
                )
            }
            .buttonStyle(.plain)
        }
    }
}
