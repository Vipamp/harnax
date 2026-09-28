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

    private let auth: any AuthFlowing

    public init(auth: any AuthFlowing) {
        self.auth = auth
    }

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
        if case let .success(config) = await auth.serverConfiguration() {
            serverLine = ServerAddressSummary.line(admin: config.adminBaseURL, router: config.routerBaseURL)
        }
    }
}
