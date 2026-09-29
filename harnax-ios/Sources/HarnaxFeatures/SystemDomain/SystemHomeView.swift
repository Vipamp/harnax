import SwiftUI
import HarnaxCore
import HarnaxKit

/// The screens this tab owns, as one navigation value so the group can be a `ForEach` and its last row
/// knows it is last.
public enum SystemRoute: Hashable, CaseIterable, Sendable {
    case envVars
    case apiKeys
    case channels
    case tokenMonitor

    /// The console gates the whole API Key domain behind an administrator, not the row
    /// (`harnax-webui/config/routes.ts:140` → `src/access.ts:13`), so a member gets a shorter group rather
    /// than a list the backend would answer 403 for. Variables are scoped per account server-side
    /// (`EnvVariableServiceImpl.kt:35-41`), and neither the channel route nor the token-monitor route names an
    /// access rule at all (`routes.ts:131-135`, `:147-152`), so both of those rows stay open — a member simply
    /// sees rows whose menus are empty, because every stored channel was created by `system`.
    public static func visible(for account: AccountSnapshot?) -> [SystemRoute] {
        account?.isAdministrator == true ? allCases : [.envVars, .channels, .tokenMonitor]
    }

    public var titleKey: String {
        switch self {
        case .envVars: return "env.title"
        case .apiKeys: return "apikey.title"
        case .channels: return "channel.title"
        case .tokenMonitor: return "monitor.title"
        }
    }

    public var subtitleKey: String {
        switch self {
        case .envVars: return "system.env.subtitle"
        case .apiKeys: return "system.apikey.subtitle"
        case .channels: return "system.channel.subtitle"
        case .tokenMonitor: return "system.monitor.subtitle"
        }
    }

    /// Icon names avoid the copy namespaces on purpose: a dotted literal that starts with one of them
    /// reads as a localisation key to the gate, and `system` is a namespace here.
    public var systemImage: String {
        switch self {
        case .envVars: return "slider.horizontal.3"
        case .apiKeys: return "key"
        case .channels: return "antenna.radiowaves.left.and.right"
        case .tokenMonitor: return "chart.line.uptrend.xyaxis"
        }
    }
}

/// E1 — the 系统 tab is a grouped list of the admin surfaces that are not agent context.
///
/// Rows are plain links on the tab's own stack, so each domain keeps the navigation shape it has on the
/// context tab: one column on screen at a time, its forms and detail sheets riding on top of it.
///
/// The rows carry no counts, because the only way to get one is a paged read per domain and this tab is opened
/// far more often than any of its lists is.
public struct SystemHomeView: View {
    private let dependencies: HarnaxDependencies
    private let account: AccountSnapshot?

    public init(dependencies: HarnaxDependencies, account: AccountSnapshot?) {
        self.dependencies = dependencies
        self.account = account
    }

    public var body: some View {
        let routes = SystemRoute.visible(for: account)
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HXSectionHeader("system.section.administration")
                HXGroupCard {
                    ForEach(Array(routes.enumerated()), id: \.element) { offset, route in
                        NavigationLink(value: route) {
                            HXRow(
                                route.titleKey,
                                subtitle: hx(route.subtitleKey),
                                systemImage: route.systemImage,
                                divider: offset < routes.count - 1,
                                trailing: { HXChevron() }
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
            .padding(.bottom, HXLayout.tabBarClearance)
        }
        .harnaxScreen()
        .navigationDestination(for: SystemRoute.self) { route in
            switch route {
            case .envVars:
                EnvVarListView(catalog: dependencies.envVars)
            case .apiKeys:
                ApiKeyListView(catalog: dependencies.apiKeys, account: account)
            case .channels:
                ChannelListView(catalog: dependencies.channels, agents: dependencies.agents, account: account)
            case .tokenMonitor:
                TokenMonitorView(catalog: dependencies.tokenStats)
            }
        }
    }
}
