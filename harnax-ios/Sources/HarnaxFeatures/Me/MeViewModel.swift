import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// F1's supporting work: pull the account fields from the server and show which stack is configured.
///
/// The identity card itself reads `AppModel.account`, which comes off the keychain, so a user on a dead
/// network still sees who they are.
@MainActor
public final class MeViewModel: ObservableObject {
    @Published public private(set) var serverLine = ""
    @Published public private(set) var errorText: String?
    @Published public private(set) var isRefreshingProfile = false
    @Published public private(set) var tenants: [TenantSummary] = []
    /// Its own line, because a tenant read that failed says something else than "this account has one
    /// tenant" — the second would silently hide the only way into the other one.
    @Published public private(set) var tenantErrorText: String?
    @Published public private(set) var isSwitching = false
    @Published public private(set) var switchErrorText: String?

    private let auth: any AuthFlowing

    public init(auth: any AuthFlowing) {
        self.auth = auth
    }

    /// One tenant is the ordinary account, and a picker over a single row is a step to nowhere.
    public var canSwitchTenant: Bool { tenants.count > 1 }

    /// A 401 here has already cleared the session inside `AuthFlowing`; the root re-reads the state and
    /// swaps the screen, which is why nothing below says "signed out".
    public func reload() async {
        isRefreshingProfile = true
        let profile = await auth.profile()
        isRefreshingProfile = false
        switch profile {
        case .success:
            errorText = nil
        case let .failure(error):
            errorText = ErrorMessage.text(for: error)
        }
        switch await auth.tenantOptions() {
        case let .success(rows):
            tenants = rows
            tenantErrorText = nil
        case let .failure(error):
            tenantErrorText = ErrorMessage.text(for: error)
        }
        if case let .success(config) = await auth.serverConfiguration() {
            serverLine = ServerAddressSummary.line(admin: config.adminBaseURL, router: config.routerBaseURL)
        }
    }

    /// Returns whether the session moved, so the screen can re-read the account and let the root rebuild
    /// every tab on the new tenant. A refusal keeps the sheet open with its reason on screen.
    public func switchTo(_ tenant: TenantSummary) async -> Bool {
        isSwitching = true
        defer { isSwitching = false }
        switch await auth.switchTenant(to: tenant) {
        case .success:
            switchErrorText = nil
            return true
        case let .failure(error):
            switchErrorText = ErrorMessage.text(for: error)
            return false
        }
    }
}
