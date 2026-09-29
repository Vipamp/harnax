import Foundation

/// Why an unlock attempt did not produce a session.
///
/// The four cases are what a screen can actually do something about: an unavailable path hides the control,
/// a cancel or a password fallback leaves the credential form alone, and a failure is the only one worth a
/// message. `LocalAuthentication` has more codes than that, and the adapter collapses the rest.
public enum BiometricUnlockFailure: Error, Equatable, Sendable {
    case unavailable
    case cancelled
    case passwordFallback
    case failed
}

/// The device-side guard on an already-stored session.
///
/// This is not a second credential: the token stays where `KeychainStore` put it, and a successful unlock
/// only says the person holding the device may now use it. A password sign-in always remains possible, so
/// the guard can be inconvenient but never locking.
public protocol BiometricUnlocking: Sendable {
    /// False when there is no biometry or the passcode is not enrolled — the control must not appear at all.
    var isAvailable: Bool { get }

    /// `reason` is already in the app's language; the system shows it verbatim in the prompt.
    func unlock(reason: String) async -> Result<Void, BiometricUnlockFailure>
}

/// The seam a host without biometry — and every test that never opens this path — gets.
public struct NoBiometricUnlock: BiometricUnlocking {
    public init() {}

    public var isAvailable: Bool { false }

    public func unlock(reason: String) async -> Result<Void, BiometricUnlockFailure> {
        .failure(.unavailable)
    }
}
