import Foundation
import HarnaxCore

/// The administration domains that are not agent context, as one navigation value so the group on 「我的」
/// can be a `ForEach` and its last row knows it is last.
///
/// The console gates the whole API Key domain behind an administrator, not the row
/// (`harnax-webui/config/routes.ts:140` → `src/access.ts:13`), so a member gets a shorter group rather
/// than a list the backend would answer 403 for. Variables are scoped per account server-side
/// (`EnvVariableServiceImpl.kt:35-41`), and neither the channel route nor the token-monitor route names an
/// access rule at all (`routes.ts:131-135`, `:147-152`), so both of those rows stay open — a member simply
/// sees rows whose menus are empty, because every stored channel was created by `system`.
public enum SystemRoute: Hashable, CaseIterable, Sendable {
    case envVars
    case apiKeys
    case channels
    case tokenMonitor

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
