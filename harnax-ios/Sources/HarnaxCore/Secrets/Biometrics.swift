import Foundation

/// The unlocker this build can actually offer.
public enum Biometrics {
    /// Real biometry where the framework exists, and the always-unavailable seam where it does not — the
    /// latter is what a Mac test host gets, so no test has to stub a system prompt.
    public static func live() -> any BiometricUnlocking {
        #if canImport(LocalAuthentication)
        LocalAuthenticationUnlocking()
        #else
        NoBiometricUnlock()
        #endif
    }
}
