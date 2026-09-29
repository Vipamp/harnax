import Foundation
import HarnaxCore

/// Sign-in, cold restore and the two server addresses. Everything the login and `Me` screens need, and
/// nothing they can do wrong: the client owns headers and refresh, this type owns the throttle.
public actor AuthFlow: AuthFlowing {
    private let client: APIClient
    private let session: AuthSession
    private let configs: ServerConfigStore
    private var backoff = LoginBackoff()
    private let now: @Sendable () -> Date

    public init(
        client: APIClient,
        session: AuthSession,
        configs: ServerConfigStore,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.client = client
        self.session = session
        self.configs = configs
        self.now = now
    }

    /// Reads the keychain only, so launch never waits on the network. The identity card refreshes itself
    /// through `profile()` once it is on screen.
    public func state() async -> AuthState {
        let signedIn = (try? await session.isSignedIn()) ?? false
        guard signedIn else { return .signedOut }
        guard let account = (try? await session.cachedAccount()) ?? nil else { return .signedOut }
        return .signedIn(account)
    }

    /// Order matters: a refill request outranks a waiting window, because the caller has to clear the
    /// field either way and showing "wait 1s" first would hide that.
    public func login(username: String, password: String) async -> Result<AccountSnapshot, APIError> {
        if backoff.requiresPasswordRefill {
            // Retyping the password is the whole remedy, so the streak starts over behind it.
            backoff = LoginBackoff()
            return .failure(.refillPassword)
        }
        let remaining = backoff.remainingDelay(at: now())
        if remaining > 0 {
            return .failure(.throttled(seconds: Int(ceil(remaining))))
        }
        let request = LoginRequest(username: username, password: password)
        guard let endpoint = try? AdminEndpoint.cliLogin(request) else { return .failure(.decoding) }
        let response = await client.send(LoginResponse.self, endpoint)
        switch response {
        case let .success(body):
            guard let account = try? await signIn(body) else { return .failure(.unpackable) }
            backoff.recordSuccess()
            return .success(account)
        case let .failure(error):
            if LoginBackoff.isCredentialFailure(error) { backoff.recordFailure(at: now()) }
            return .failure(error)
        }
    }

    private func signIn(_ response: LoginResponse) async throws -> AccountSnapshot {
        try await session.signIn(response)
        return AccountSnapshot.live(from: response)
    }

    public func logout() async {
        try? await session.signOut()
    }

    /// A dead session is settled here rather than left to the screen: once the backend says 401 for a
    /// profile read, the credentials have no future.
    public func profile() async -> Result<MeInfo, APIError> {
        let response = await client.send(MeInfo.self, AdminEndpoint.profile)
        switch response {
        case let .success(me):
            let cached = (try? await session.cachedAccount()) ?? nil
            try? await session.cache(account: AccountSnapshot.live(from: me).mergingWith(cached))
            return .success(me)
        case let .failure(error):
            if case .unauthorized = error { await logout() }
            return .failure(error)
        }
    }

    /// Which tenants the account can enter. A read failure is the screen's to show — an empty list here would
    /// otherwise look like a single-tenant account and hide the switcher.
    public func tenantOptions() async -> Result<[TenantSummary], APIError> {
        await client.send([TenantSummary].self, AdminEndpoint.tenants)
    }

    /// The switch is a token swap, so the session is the only thing that changes: the keychain gets the new
    /// bearer, the tenant it was minted for, and a cached card naming it. Every screen reloads because the
    /// root re-keys its tab tree on the tenant, not because anything here tells them.
    public func switchTenant(to tenant: TenantSummary) async -> Result<Void, APIError> {
        guard let id = tenant.id else { return .failure(.decoding) }
        guard let endpoint = try? AdminEndpoint.switchTenant(SwitchTenantRequest(tenantId: id)) else {
            return .failure(.decoding)
        }
        let response = await client.send(RefreshedToken.self, endpoint)
        switch response {
        case let .success(token):
            do {
                try await session.adopt(token, mintedFor: id)
            } catch let error as APIError {
                return .failure(error)
            } catch {
                return .failure(.unpackable)
            }
            if let cached = (try? await session.cachedAccount()) ?? nil {
                try? await session.cache(account: cached.withTenant(id: id, name: tenant.name))
            }
            return .success(())
        case let .failure(error):
            if case .unauthorized = error { await logout() }
            return .failure(error)
        }
    }

    public func serverConfiguration() async -> Result<ServerConfig, APIError> {
        do {
            return .success(try await configs.current())
        } catch {
            return .failure(.invalidServerConfig(String(describing: error)))
        }
    }

    /// Takes effect on the next request, since `ServerConfigStore` is the one the client reads per call.
    public func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError> {
        do {
            try await configs.update(serverConfiguration)
        } catch {
            return .failure(.invalidServerConfig(String(describing: error)))
        }
        // Failed guesses belonged to another stack; carrying the streak over would throttle a fresh server
        // the user has no reason to have already tried.
        backoff = LoginBackoff()
        return .success(())
    }
}
