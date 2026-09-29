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
    ///
    /// An empty keychain and a keychain that refused to be read are different answers, and only the first is
    /// a verdict: `KeychainStore` hands back `nil` for `errSecItemNotFound` and throws for every other
    /// OSStatus (`Sources/HarnaxCore/Secrets/KeychainStore.swift:36-41`). A thrown status is `.unknown`, the
    /// state the root already treats as "not settled yet", so the next `sync()` reads again instead of
    /// spending a real sign-in on one locked hop. A card this side cannot decode is the opposite case —
    /// waiting on it would never resolve, and signing in again is the remedy, so that stays `.signedOut`.
    public func state() async -> AuthState {
        do {
            guard try await session.isSignedIn() else { return .signedOut }
            guard let account = try await session.cachedAccount() else { return .signedOut }
            return .signedIn(account)
        } catch is SecretStoreError {
            return .unknown
        } catch {
            return .signedOut
        }
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
    ///
    /// The card is written first, and the answer only becomes a success once it is down: `X-Tenant-ID` is
    /// read back off the keychain for every request, so a header the persistent copy cannot match would have
    /// the screen naming the tenant the user left while every later call went to the one they chose. A store
    /// that refused either write gets told, through the returned error, that the switch did not happen.
    public func switchTenant(to tenant: TenantSummary) async -> Result<Void, APIError> {
        guard let id = tenant.id else { return .failure(.decoding) }
        guard let endpoint = try? AdminEndpoint.switchTenant(SwitchTenantRequest(tenantId: id)) else {
            return .failure(.decoding)
        }
        let response = await client.send(RefreshedToken.self, endpoint)
        switch response {
        case let .success(token):
            let previous: AccountSnapshot?
            do {
                previous = try await session.cachedAccount()
            } catch {
                return .failure(.unpackable)
            }
            if let previous {
                do {
                    try await session.cache(account: previous.withTenant(id: id, name: tenant.name))
                } catch {
                    return .failure(.unpackable)
                }
            }
            do {
                try await session.adopt(token, mintedFor: id)
            } catch let error as APIError {
                await restore(previous)
                return .failure(error)
            } catch {
                await restore(previous)
                return .failure(.unpackable)
            }
            return .success(())
        case let .failure(error):
            if case .unauthorized = error { await logout() }
            return .failure(error)
        }
    }

    /// Puts the card back when the bearer it was written for could not be stored, so no screen ends up
    /// naming a tenant the session never entered. A rollback the store also refused needs no second error:
    /// the caller has already been told the switch failed.
    private func restore(_ card: AccountSnapshot?) async {
        guard let card else { return }
        try? await session.cache(account: card)
    }

    public func serverConfiguration() async -> Result<ServerConfig, APIError> {
        do {
            return .success(try await configs.current())
        } catch {
            return .failure(.invalidServerConfig(String(describing: error)))
        }
    }

    /// Takes effect on the next request, since `ServerConfigStore` is the one the client reads per call.
    ///
    /// Moving the stack ends the session signed into it. `DESIGN.md:81` puts the address entry *before* the
    /// login, and the web console has no such setting to compare against, but this sheet is reachable from
    /// `Me` — and a bearer only means something on the host that signed it. So the credential minted for the
    /// old pair of addresses goes with them: `signOut()` clears token, router key, tenant and card while
    /// leaving both addresses alone, which is exactly the half of the state the user just re-entered. The
    /// two bases name one stack (`login.address.hint`), so moving either one counts.
    public func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError> {
        let stored: ServerConfig?
        do {
            stored = try await configs.current()
        } catch {
            stored = nil
        }
        do {
            try await configs.update(serverConfiguration)
        } catch {
            return .failure(.invalidServerConfig(String(describing: error)))
        }
        // Failed guesses belonged to another stack; carrying the streak over would throttle a fresh server
        // the user has no reason to have already tried.
        backoff = LoginBackoff()
        guard stored != serverConfiguration else { return .success(()) }
        guard (try? await session.isSignedIn()) ?? false else { return .success(()) }
        do {
            try await session.signOut()
        } catch {
            // Nothing durable happened to the session, so the address change is reported as a failure: a
            // stack the app has moved to must not be left reachable with the old host's bearer.
            return .failure(.invalidServerConfig(String(describing: error)))
        }
        NotificationCenter.default.post(name: .harnaxCredentialsDropped, object: nil)
        return .success(())
    }
}

extension Notification.Name {
    /// The credentials left the keychain while a screen was still showing them, posted by the one call that
    /// ends a session on the user's behalf rather than on the server's word: moving the server address.
    ///
    /// The root owns the only copy of the truth the tab bar renders, and it reads that copy on demand, so a
    /// session dropped from a pushed sheet has to announce itself or the shell keeps greeting an account that
    /// is gone. `AuthFlow.save(serverConfiguration:)` is the producer and `AppModel` the subscriber.
    public static let harnaxCredentialsDropped = Notification.Name("com.agnetix.harnax.ios.credentials-dropped")
}
