import Foundation
import HarnaxCore
import SwiftUI

/// The device-loss guard from the design's R4: opt-in, off by default, and read only at launch.
public enum BiometricGate {
    public static let defaultsKey = "com.agnetix.harnax.biometric-unlock"
}

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
    /// A stored session the owner has not been asked for yet. The token is untouched in the keychain —
    /// this only says the root is showing the gate instead of the tab bar.
    @Published public private(set) var isAwaitingBiometric = false
    /// The last unlock outcome a screen owes a word about. A cancel and a password fallback clear it,
    /// because the credential form the user is looking at is the answer to both.
    @Published public private(set) var biometricFailure: BiometricUnlockFailure?
    /// False when the device has no biometry and no passcode enrolled: the switch would only ever promise
    /// something the next cold start cannot deliver.
    public let biometricsAvailable: Bool

    private let biometrics: any BiometricUnlocking
    /// Read once, at launch: switching the guard on in the settings screen takes effect next cold start,
    /// the same way every iOS app that asks to be re-authenticated behaves.
    private let gateEnabled: Bool

    public init(
        dependencies: HarnaxDependencies,
        biometrics: any BiometricUnlocking = Biometrics.live(),
        gateEnabled: Bool = UserDefaults.standard.bool(forKey: BiometricGate.defaultsKey)
    ) {
        self.dependencies = dependencies
        self.biometrics = biometrics
        self.biometricsAvailable = biometrics.isAvailable
        self.gateEnabled = gateEnabled
    }

    public var account: AccountSnapshot? { authState.account }
    public var isRestoring: Bool { if case .unknown = authState { return true }; return false }
    public var isSignedIn: Bool { if case .signedIn = authState { return true }; return false }

    /// Launch reads the keychain only, so a cold start on a train tunnel still knows who it is.
    public func restore() async {
        guard case .unknown = authState else { return }
        let state = await dependencies.auth.state()
        // The guard sits in front of an existing session, so the session still has to be read to know the
        // gate is worth showing — and then kept to itself until the owner answers.
        if gateEnabled, biometrics.isAvailable, case .signedIn = state {
            authState = .signedOut
            isAwaitingBiometric = true
            return
        }
        authState = state
    }

    /// Re-read the session. A gate that is up stays up: the keychain still holds the sign-in the guard is
    /// standing in front of, so a plain re-read must not smuggle it past the prompt.
    public func sync() async {
        if isAwaitingBiometric {
            authState = .signedOut
            return
        }
        authState = await dependencies.auth.state()
    }

    /// The gate has exactly two answers: an unlock that went through, and credentials that actually
    /// exchanged. Anything else — a cancel, a refused password, an empty form — leaves it standing.
    public func answerGate() async {
        isAwaitingBiometric = false
        biometricFailure = nil
        authState = await dependencies.auth.state()
    }

    /// `reason` is the caller's copy: the prompt text belongs to the screen, and this layer has no catalogue.
    public func unlockWithBiometrics(reason: String) async {
        switch await biometrics.unlock(reason: reason) {
        case .success:
            await answerGate()
        case .failure(.cancelled), .failure(.passwordFallback):
            biometricFailure = nil
        case .failure(.unavailable):
            // Hardware or enrollment went away mid-flight; the form is the only way in now.
            isAwaitingBiometric = false
            biometricFailure = .unavailable
        case .failure(.failed):
            biometricFailure = .failed
        }
    }

    public func signOut() async {
        await dependencies.auth.logout()
        authState = .signedOut
        tab = .agents
        isAwaitingBiometric = false
        biometricFailure = nil
    }
}
