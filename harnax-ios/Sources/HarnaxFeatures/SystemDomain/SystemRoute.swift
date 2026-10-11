import Foundation
import HarnaxCore

/// The administration domains that are neither agent context nor a monitor, as one navigation value so the
/// 「系统管理」 block on 「我的」 can be a `ForEach` and its last row knows it is last.
///
/// The console gates the whole API Key domain behind an administrator, not the row
/// (`harnax-webui/config/routes.ts:205-210` → `src/access.ts:13`), so a member gets a shorter block rather
/// than a list the backend would answer 403 for. Variables are scoped per account server-side
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/EnvVariableServiceImpl.kt:35-41`),
/// and neither the env route nor the channel route names an access rule at all (`routes.ts:211-215`,
/// `:216-221`), so both of those rows stay open — a member simply sees rows whose menus are empty, because
/// every stored channel was created by `system`.
///
/// The token monitor used to be in this array. It is a read of aggregate counters, like the two monitors that
/// joined it later, and the console moved it out of 「系统管理」 for the same reason (`routes.ts:199-204`):
/// `MonitorRoute` carries all three now, ungated.
public enum SystemRoute: Hashable, CaseIterable, Sendable {
    case envVars
    case apiKeys
    case channels

    public static func visible(for account: AccountSnapshot?) -> [SystemRoute] {
        account?.isAdministrator == true ? allCases : [.envVars, .channels]
    }

    public var titleKey: String {
        switch self {
        case .envVars: return "env.title"
        case .apiKeys: return "apikey.title"
        case .channels: return "channel.title"
        }
    }

    public var subtitleKey: String {
        switch self {
        case .envVars: return "system.env.subtitle"
        case .apiKeys: return "system.apikey.subtitle"
        case .channels: return "system.channel.subtitle"
        }
    }

    /// Icon names avoid the copy namespaces on purpose: a dotted literal that starts with one of them
    /// reads as a localisation key to the gate, and `system` is a namespace here.
    public var systemImage: String {
        switch self {
        case .envVars: return "slider.horizontal.3"
        case .apiKeys: return "key"
        case .channels: return "antenna.radiowaves.left.and.right"
        }
    }
}
