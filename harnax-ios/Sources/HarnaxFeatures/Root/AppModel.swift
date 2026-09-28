import Foundation
import HarnaxCore
import SwiftUI

/// Root view model: who is signed in, and which tab is showing.
///
/// `authState` is only ever written from `sync()`, which asks the facade what the keychain says. Login,
/// logout and a refresh that hit 401 all end up in the same place, so no screen has to remember to
/// update a second copy of the truth.
@MainActor
public final class AppModel: ObservableObject {
    public let dependencies: HarnaxDependencies

    @Published public private(set) var authState: AuthState = .unknown
    @Published public var tab: HarnaxTab = .agents

    public init(dependencies: HarnaxDependencies) {
        self.dependencies = dependencies
    }

    public var account: AccountSnapshot? { authState.account }
    public var isRestoring: Bool { if case .unknown = authState { return true }; return false }
    public var isSignedIn: Bool { if case .signedIn = authState { return true }; return false }

    /// Launch reads the keychain only, so a cold start on a train tunnel still knows who it is.
    public func restore() async {
        if case .unknown = authState { await sync() }
    }

    public func sync() async {
        authState = await dependencies.auth.state()
    }

    public func signOut() async {
        await dependencies.auth.logout()
        authState = .signedOut
        tab = .agents
    }
}
