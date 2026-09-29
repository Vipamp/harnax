import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// A1. Two fields, one button, and the server line under the wordmark.
///
/// The throttle lives in `AuthFlowing`, so this type only mirrors what the facade says: it never counts
/// attempts itself, which would put the lockout rule in two places.
@MainActor
public final class LoginViewModel: ObservableObject {
    @Published public var username = ""
    @Published public var password = ""
    @Published public private(set) var isSubmitting = false
    @Published public private(set) var errorText: String?
    @Published public private(set) var serverLine = ""
    /// Set once the facade has accepted the credential. The view pushes the root model to re-read the
    /// session after it changes, which is also how a failed sign-in leaves the screen alone.
    @Published public private(set) var signedInAccount: AccountSnapshot?

    private let auth: any AuthFlowing

    public init(auth: any AuthFlowing) {
        self.auth = auth
    }

    /// Best effort: an unreachable stack still lets the credential form render, and the addresses the
    /// banner would show are the ones the client is about to use anyway.
    public func reload() async {
        switch await auth.serverConfiguration() {
        case let .success(config): serverLine = ServerAddressSummary.line(admin: config.adminBaseURL, router: config.routerBaseURL)
        case let .failure(error): errorText = ErrorMessage.text(for: error)
        }
    }

    /// Whether the session is now real. The caller needs the difference: a submit that never exchanged
    /// leaves the gate standing, and only an exchange answers it.
    @discardableResult
    public func submit() async -> Bool {
        guard !isSubmitting else { return false }
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, !password.isEmpty else {
            errorText = hx("login.needCredentials")
            return false
        }
        errorText = nil
        isSubmitting = true
        let result = await auth.login(username: name, password: password)
        isSubmitting = false
        switch result {
        case let .success(account):
            signedInAccount = account
            password = ""
            return true
        case let .failure(error):
            errorText = ErrorMessage.text(for: error)
            // The remedy for a long streak is retyping, so the field is emptied rather than left with
            // the text that just failed.
            if case .refillPassword = error { password = "" }
            return false
        }
    }
}
