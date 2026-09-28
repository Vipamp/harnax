import SwiftUI
import HarnaxCore
import HarnaxKit

/// The app's only root: restore, then either the login screen or the five-tab bar.
public struct HarnaxRootView: View {
    @StateObject private var model: AppModel

    public init(dependencies: HarnaxDependencies) {
        _model = StateObject(wrappedValue: AppModel(dependencies: dependencies))
    }

    /// Test seam — the app target uses `init(dependencies:)`, a harness hands in a model over fakes.
    public init(model: AppModel) {
        _model = StateObject(wrappedValue: model)
    }

    public var body: some View {
        Group {
            switch model.authState {
            case .unknown:
                // Only the keychain is read, so this frame lasts as long as a disk access.
                HXStateView(.loading)
            case .signedOut:
                LoginView(model: model)
            case .signedIn:
                HarnaxTabView(model: model)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .harnaxThemed()
        .task { await model.restore() }
    }
}

struct HarnaxTabView: View {
    @ObservedObject var model: AppModel
    /// Held so a language picked inside the app redraws the tab labels, which are plain `Text` and
    /// therefore cannot observe the catalogue themselves.
    @ObservedObject private var catalog = HarnaxCatalog.shared

    var body: some View {
        TabView(selection: $model.tab) {
            ForEach(HarnaxTab.allCases) { tab in
                NavigationStack {
                    screen(for: tab)
                        .navigationTitle(Text(verbatim: hx(tab.titleKey)))
                }
                .tabItem {
                    Image(systemName: tab.systemImage)
                    Text(verbatim: hx(tab.titleKey))
                }
                .tag(tab)
            }
        }
    }

    @ViewBuilder
    private func screen(for tab: HarnaxTab) -> some View {
        switch tab {
        case .agents: AgentListView(agents: model.dependencies.agents)
        case .me: MeView(model: model)
        case .chat, .context, .system: SoonView(titleKey: tab.titleKey)
        }
    }
}
