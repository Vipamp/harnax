import Foundation
import HarnaxCore

public protocol TokenRefreshing: Sendable {
    /// The old token must still be valid for the backend to hand out a new one
    /// (`TokenController.kt:33-49` reads the caller from the security context).
    func refresh(current accessToken: String) async throws -> RefreshedToken
}

/// Keychain-facing session state. The cached snapshot only exists so a cold restore can render the
/// identity card immediately; `GET /api/admin/auth/me` stays the canonical copy the screen refreshes.
public actor AuthSession {
    /// The lead used when no lifetime is on record (see `needsRefresh()`).
    public static let refreshWindow: TimeInterval = 60

    private let store: SecretStoring
    private let now: @Sendable () -> Date

    public init(store: SecretStoring, now: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.now = now
    }

    public func signIn(_ response: LoginResponse) throws {
        guard let token = response.accessToken, !token.isEmpty else {
            throw APIError.unpackable
        }
        try store.setValue(token, for: .accessToken)
        if let routerAPIKey = response.routerApiKey, !routerAPIKey.isEmpty {
            try store.setValue(routerAPIKey, for: .routerApiKey)
        }
        if let tenantID = response.currentTenantId {
            try store.setValue(String(tenantID), for: .tenantId)
        }
        try AccountSnapshot.live(from: response).store(in: store)
        try persistExpiry(expiresIn: response.expiresIn, expiresAt: response.expiresAt)
    }

    /// The wire gives either a lifetime or an absolute instant depending on which login endpoint was
    /// used; `refresh-token` returns only a lifetime, so both are normalised to an absolute deadline on
    /// the client clock.
    private func persistExpiry(expiresIn: Int64?, expiresAt: Int64?) throws {
        if let expiresAt {
            try store.setValue(String(expiresAt), for: .tokenExpiresAtMillis)
            if let expiresIn { try store.setValue(String(expiresIn), for: .tokenLifetimeMillis) }
            return
        }
        guard let expiresIn else { return }
        let deadline = now().addingTimeInterval(TimeInterval(expiresIn)).milliseconds
        try store.setValue(String(deadline), for: .tokenExpiresAtMillis)
        try store.setValue(String(expiresIn), for: .tokenLifetimeMillis)
    }

    /// `POST /api/admin/auth/cli-login` is the only path that also yields a router key; refresh responses
    /// carry neither, so their expiry is folded in through this overload.
    ///
    /// `tenantID` overrides what the response echoed. Only the tenant switch needs it: the `X-Tenant-ID`
    /// header is read back off this key, and a header naming the tenant the user just left would send every
    /// later request there (`TenantInterceptor.kt:42-56` trusts a verified header over the token claim).
    public func adopt(_ token: RefreshedToken, mintedFor tenantID: Int64? = nil) throws {
        guard let accessToken = token.accessToken, !accessToken.isEmpty else {
            throw APIError.unauthorized
        }
        try store.setValue(accessToken, for: .accessToken)
        let effectiveTenantID = tenantID ?? token.tenantId
        if let effectiveTenantID {
            try store.setValue(String(effectiveTenantID), for: .tenantId)
        }
        try persistExpiry(expiresIn: token.expiresIn, expiresAt: nil)
    }

    public func accessToken() throws -> String? {
        try store.value(for: .accessToken)
    }

    public func tenantID() throws -> String? {
        try store.value(for: .tenantId)
    }

    public func routerAPIKey() throws -> String? {
        try store.value(for: .routerApiKey)
    }

    public func cachedAccount() throws -> AccountSnapshot? {
        try AccountSnapshot.load(from: store)
    }

    /// Called when `auth/me` supersedes what the login response cached.
    public func cache(account: AccountSnapshot) throws {
        try account.store(in: store)
    }

    public func isSignedIn() throws -> Bool {
        !(try accessToken() ?? "").isEmpty
    }

    /// True when the token is missing an expiry or has run into its renewal threshold, i.e. worth refreshing
    /// up front rather than waiting for a 401 round trip.
    ///
    /// The threshold is one third of the lifetime (`DESIGN.md` §5.3), not a fixed lead: the swap only works
    /// while the old token still verifies (`TokenController.kt:35` reads the caller off the security context),
    /// so the margin has to be several round trips wide. A production JWT lives 7 200 s (`JWT_EXPIRATION`),
    /// which under a 60-second lead left 119 of its 120 minutes unprotected.
    public func needsRefresh() throws -> Bool {
        guard try isSignedIn() else { return false }
        guard let raw = try store.value(for: .tokenExpiresAtMillis), let deadline = Int64(raw) else { return true }
        let threshold = try refreshThresholdMillis()
        return deadline - now().milliseconds <= threshold
    }

    /// One third of the lifetime the login or refresh response named, falling back to the flat window when it
    /// named only an absolute deadline and there is nothing to divide.
    private func refreshThresholdMillis() throws -> Int64 {
        guard let stored = try store.value(for: .tokenLifetimeMillis), let lifetime = Int64(stored) else {
            return Int64(Self.refreshWindow * 1000)
        }
        return lifetime / 3 * 1000
    }

    public func secondsUntilExpiry() throws -> Int? {
        guard let raw = try store.value(for: .tokenExpiresAtMillis), let deadline = Int64(raw) else { return nil }
        return max(0, Int((deadline - now().milliseconds) / 1000))
    }

    /// Clears credentials and leaves the two server addresses alone — signing out must not send the user
    /// back to re-entering an endpoint they already configured.
    ///
    /// This is the one place credentials leave the keychain, so it is the one place that says so: the root
    /// holds the only copy of the truth the tab bar renders and reads it on demand, and three of this
    /// method's four callers (`AuthFlow.logout`, `AuthFlow.save(serverConfiguration:)`, and the two 401
    /// settles in `APIClient`) sit in layers that have no way back into it.
    public func signOut() throws {
        for key in [
            SecretKey.accessToken, .routerApiKey, .tenantId,
            .tokenExpiresAtMillis, .tokenLifetimeMillis, .cachedAccount,
        ] {
            try store.setValue(nil, for: key)
        }
        NotificationCenter.default.post(name: .harnaxCredentialsDropped, object: nil)
    }
}

private extension Date {
    var milliseconds: Int64 { Int64(timeIntervalSince1970 * 1000) }
}
