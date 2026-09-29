import Foundation

#if canImport(LocalAuthentication)
import LocalAuthentication

/// The real `BiometricUnlocking`, on the device-owner policy.
///
/// `.deviceOwnerAuthentication` rather than `...WithBiometrics`: the guard is against someone else holding
/// this phone, and a passcode answers that just as well while still working when a wet finger is refused.
/// It is also the only policy that keeps the token reachable on a Mac test host.
public struct LocalAuthenticationUnlocking: BiometricUnlocking {
    public init() {}

    public var isAvailable: Bool {
        let context = LAContext()
        return context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    public func unlock(reason: String) async -> Result<Void, BiometricUnlockFailure> {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
            return .failure(.unavailable)
        }
        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: reason,
                reply: { granted, error in
                    if granted {
                        continuation.resume(returning: .success(()))
                    } else {
                        continuation.resume(returning: .failure(Self.classify(error)))
                    }
                }
            )
        }
    }

    /// Everything outside the three outcomes a screen can act on is a plain failure: the form is already
    /// there, so a lockout, a cancelled system dialog and an unexpected code all read the same way.
    static func classify(_ error: (any Error)?) -> BiometricUnlockFailure {
        guard let code = (error as? LAError)?.code else { return .failed }
        switch code {
        case .userCancel: return .cancelled
        case .userFallback: return .passwordFallback
        case .biometryNotAvailable, .biometryNotEnrolled, .biometryLockout: return .unavailable
        default: return .failed
        }
    }
}
#endif
