import Foundation
import HarnaxAPI
import HarnaxCore
import SwiftUI

/// The device-loss guard from the design's R4: opt-in, off by default, and read only at launch.
public enum BiometricGate {
    public static let defaultsKey = "com.agnetix.harnax.biometric-unlock"
}

/// Root view model: who is signed in, and which tab is showing.
///
/// `authState` is written by exactly two things: `publish(...)`, which every session read goes through — the
/// launch read, `sync()`, the gate answer and the reconcile re-read that follows a signed-in launch — and the
/// local settle in `signOut()`. Login, logout and a refresh that hit 401 all end up in the same place, so no
/// screen has to remember to update a second copy of the truth.
@MainActor
public final class AppModel: ObservableObject {
    public let dependencies: HarnaxDependencies

    @Published public private(set) var authState: AuthState = .unknown
    /// Which tab is showing — and the only navigation state that outlives a trip through the login screen.
    ///
    /// `DESIGN.md:221` asks a 401 to 清凭据 *and* 保留当前页面路径以便登录后回跳, and this root has no URL bar
    /// to carry the return address in. What it has instead is the lifetime of this object: `HarnaxRootView`
    /// tears the whole tab subtree down when `authState` goes back to `LoginView` and rebuilds it on the next
    /// sign-in, so whatever the selection lives in is exactly what survives. `AppModel` lives on the root, so
    /// it does; `@State` inside `HarnaxTabView` would not.
    ///
    /// A 401 reaches the root through `publish(_:readBefore:)` — the keychain was emptied in the API layer and
    /// a screen re-reads it — and that method deliberately says nothing about the tab. Only the sign-out
    /// button's own `signOut()` sends the operator back to the first one, which is a different request.
    /// Pinned by `SessionPathRestorationTests`, so the preservation stops being a by-product of which method
    /// the 401 path happens to call.
    ///
    /// One screen deeper is not covered: the per-tab `NavigationStack` is built without a path binding, so a
    /// pushed detail goes with the discarded subtree and the return lands on the tab's root. Carrying it
    /// would make this property a per-tab path store and pull each domain's own push state up into it.
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
    /// The token for the credentials-dropped observer, kept only so `deinit` can cancel it.
    private var credentialsObserver: NSObjectProtocol?
    /// Moved by every sign-out this object performs. A session read is a suspension — `restore()`, `sync()`
    /// and `answerGate()` all wait on the facade — so one that started before a tap on the logout button can
    /// land after it and put the tab bar back over the top of the answer. A read carries the number it left
    /// on, and a number that has moved means the read has no say any more.
    private var sessionGeneration = 0
    /// One system prompt at a time: `unlock` suspends the main actor, so a second tap on the guard would open
    /// a second prompt and let the two answers fight over the gate.
    private var unlockInFlight = false

    public init(
        dependencies: HarnaxDependencies,
        biometrics: any BiometricUnlocking = Biometrics.live(),
        gateEnabled: Bool = UserDefaults.standard.bool(forKey: BiometricGate.defaultsKey)
    ) {
        self.dependencies = dependencies
        self.biometrics = biometrics
        self.biometricsAvailable = biometrics.isAvailable
        self.gateEnabled = gateEnabled
        // Saving a different server address drops the credential the old host minted, and that happens inside
        // the API layer, which has no way back into this object. Without the signal the root would keep
        // rendering an account whose bearer is already gone.
        credentialsObserver = NotificationCenter.default.addObserver(
            forName: .harnaxCredentialsDropped,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            Task { @MainActor in await self.sync() }
        }
    }

    deinit {
        if let credentialsObserver { NotificationCenter.default.removeObserver(credentialsObserver) }
    }

    public var account: AccountSnapshot? { authState.account }
    public var isRestoring: Bool { if case .unknown = authState { return true }; return false }
    public var isSignedIn: Bool { if case .signedIn = authState { return true }; return false }

    /// Launch reads the keychain only, so a cold start on a train tunnel still knows who it is — and then asks
    /// the server, because the design judges identity by `GET /api/admin/auth/me` rather than by the stored
    /// card (`DESIGN.md:125`).
    public func restore() async {
        guard case .unknown = authState else { return }
        let generation = sessionGeneration
        let state = await dependencies.auth.state()
        // The guard sits in front of an existing session, so the session still has to be read to know the
        // gate is worth showing — and then kept to itself until the owner answers. A session ended while that
        // read was in flight is not owed a guard: there is nothing left in front of which to stand.
        if generation == sessionGeneration, gateEnabled, biometrics.isAvailable, case .signedIn = state {
            authState = .signedOut
            isAwaitingBiometric = true
            return
        }
        publish(state, readBefore: generation)
        if case .signedIn = state { await reconcile(from: generation) }
    }

    /// Re-read the session. A gate that is up stays up: the keychain still holds the sign-in the guard is
    /// standing in front of, so a plain re-read must not smuggle it past the prompt.
    public func sync() async {
        if isAwaitingBiometric {
            authState = .signedOut
            return
        }
        let generation = sessionGeneration
        publish(await dependencies.auth.state(), readBefore: generation)
    }

    /// The only place `authState` is written by a read, with the two answers it is not allowed to give.
    ///
    /// A read that left before a sign-out is refused whatever it says: the sign-out already settled the root
    /// locally, so its resurrecting an account the keychain has forgotten is one half of the defect and its
    /// bouncing a fresh sign-in back to the credential form the other — the operator who taps logout and
    /// signs straight again gets the second one.
    ///
    /// And `.unknown` is not a verdict but the keychain refusing one read: publishing it over a settled state
    /// puts the launch placeholder back on screen, which discards the sign-in form and everything typed into
    /// it. Over the placeholder itself it has nothing to discard, but it still cannot stand: the placeholder is
    /// a promise about one read in flight, so a launch read the keychain refuses — `-34018`, which is what a
    /// build without an `application-identifier` answers — settles on the credential form rather than spinning
    /// with nothing left to wait for.
    private func publish(_ state: AuthState, readBefore generation: Int) {
        if generation != sessionGeneration { return }
        if case .unknown = state {
            if authState == .unknown { authState = .signedOut }
            return
        }
        authState = state
    }

    /// The gate has exactly two answers: an unlock that went through, and credentials that actually
    /// exchanged. Anything else — a cancel, a refused password, an empty form — leaves it standing.
    public func answerGate() async {
        let generation = sessionGeneration
        isAwaitingBiometric = false
        biometricFailure = nil
        let state = await dependencies.auth.state()
        publish(state, readBefore: generation)
        // The session behind the guard is the same one a plain launch would reconcile — a prompt is not a
        // verdict about who is on the other end of the bearer.
        if case .signedIn = state { await reconcile(from: generation) }
    }

    /// `DESIGN.md:125`: 「冷启动用 `GET /api/admin/auth/me` 恢复登录态与租户，不靠本地缓存判身份」. The card the
    /// keychain handed back is a placeholder until this answer lands, so an account renamed, demoted or
    /// re-tenanted since the last launch corrects itself at launch rather than whenever the operator next
    /// opens 「我的」 — the only other screen that reaches `profile()`.
    ///
    /// Two replies publish, because the facade has already moved the keychain for both: a success rewrote the
    /// cached card (`AuthFlow.swift:106-110`) and a 401 ended the session (`:112`). Anything else reached no
    /// server, and an unreachable host says nothing about who is signed in — publishing its absence would
    /// bounce a session the keychain still holds valid back to the credential form on a train tunnel.
    private func reconcile(from generation: Int) async {
        let reply = await dependencies.auth.profile()
        let settled: Bool
        switch reply {
        case .success:
            settled = true
        case let .failure(error):
            if case .unauthorized = error {
                settled = true
            } else {
                settled = false
            }
        }
        guard settled else { return }
        publish(await dependencies.auth.state(), readBefore: generation)
    }

    /// `reason` is the caller's copy: the prompt text belongs to the screen, and this layer has no catalogue.
    public func unlockWithBiometrics(reason: String) async {
        guard !unlockInFlight else { return }
        unlockInFlight = true
        defer { unlockInFlight = false }
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
        // Settled before anything is awaited: the revoke can take a whole timeout, and a root still showing
        // the tab bar for that long is a root the operator has already left. Moving the generation first also
        // disqualifies every session read that was in flight when the button was answered, so none of them
        // can put the account back over this answer.
        sessionGeneration += 1
        authState = .signedOut
        tab = .agents
        isAwaitingBiometric = false
        biometricFailure = nil
        await dependencies.auth.logout()
    }
}
