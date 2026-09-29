import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The throttle is the only thing standing between `cli-login` and a wall of guesses, since that endpoint
/// has no captcha. Its ordering, its exemptions and its reset points are the contract tested here.
final class AuthFlowTests: XCTestCase {
    private func businessError(_ message: String = "用户名或密码错误") -> String {
        Wire.business(400, message)
    }

    func testLoginSuccessSignsInAndReturnsTheAccount() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, Wire.login())

        let result = await harness.auth.login(username: "admin", password: "admin123")
        let account = try XCTUnwrap(result.value)
        let token = try await harness.session.accessToken()

        XCTAssertEqual(account.username, "admin")
        XCTAssertEqual(account.displayName, "System Admin")
        XCTAssertEqual(account.tenantID, 1)
        XCTAssertEqual(token, "tok-1")
        XCTAssertEqual(harness.transport.callCount, 1)
    }

    func testColdRestoreReadsTheKeychainWithoutTouchingTheNetwork() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, Wire.login())
        _ = await harness.auth.login(username: "admin", password: "admin123")
        harness.transport.reset()

        let state = await harness.relaunched().state()
        XCTAssertEqual(state.account?.username, "admin")
        XCTAssertEqual(harness.transport.callCount, 0, "launch must not wait on a request")
    }

    func testNoTokenMeansSignedOut() async throws {
        let harness = APIHarness()
        let state = await harness.auth.state()
        XCTAssertEqual(state, .signedOut)
    }

    /// A token whose owner this build never cached cannot be rendered, and guessing a name on screen is
    /// worse than showing the login form again. The token itself is left alone.
    func testTokenWithoutCachedAccountIsNotPresentedAsSignedIn() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        let store = harness.store
        try store.setValue(nil, for: .cachedAccount)

        let state = await harness.auth.state()
        let token = try await harness.session.accessToken()
        XCTAssertEqual(state, .signedOut)
        XCTAssertEqual(token, "tok-1")
    }

    func testWrongPasswordThrottlesTheNextAttempt() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, businessError())

        let first = await harness.auth.login(username: "admin", password: "bad")
        let second = await harness.auth.login(username: "admin", password: "bad")

        XCTAssertEqual(first.failure, APIError.business(code: 400, message: "用户名或密码错误"))
        XCTAssertEqual(second.failure, APIError.throttled(seconds: 1))
        XCTAssertEqual(harness.transport.callCount, 1, "a throttled attempt must not reach the network")
    }

    /// The waiting window has to elapse before the next request goes out, and it doubles every rejection.
    func testThrottleDoublesAfterEachRejection() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, businessError())
        _ = await harness.auth.login(username: "admin", password: "bad")
        harness.clock.advance(1)

        harness.transport.enqueue(200, businessError())
        _ = await harness.auth.login(username: "admin", password: "bad")
        let blocked = await harness.auth.login(username: "admin", password: "bad")
        XCTAssertEqual(blocked.failure, APIError.throttled(seconds: 2))

        harness.clock.advance(2)
        harness.transport.enqueue(200, businessError())
        let third = await harness.auth.login(username: "admin", password: "bad")
        XCTAssertEqual(third.failure, APIError.business(code: 400, message: "用户名或密码错误"))
        XCTAssertEqual(harness.transport.callCount, 3, "a throttled attempt must not reach the network")
    }

    /// A network blip is not a wrong password; charging it to the streak would lock the user out of their
    /// own account for one bad hop.
    func testTransportFailuresDoNotGrowTheStreak() async throws {
        let harness = APIHarness()
        harness.transport.enqueueFailure(URLError(.cannotConnectToHost))
        let offline = await harness.auth.login(username: "admin", password: "admin123")
        XCTAssertEqual(offline.failure, APIError.offline)

        harness.transport.enqueue(200, businessError())
        let rejected = await harness.auth.login(username: "admin", password: "admin123")
        XCTAssertEqual(rejected.failure, APIError.business(code: 400, message: "用户名或密码错误"))
        XCTAssertEqual(harness.transport.callCount, 2)
    }

    /// After five rejections the field has to be cleared by hand before guessing continues.
    func testFiveFailuresAskForAPasswordRefill() async throws {
        let harness = APIHarness()
        for _ in 1 ... LoginBackoff.refillPasswordAfterFailures {
            harness.transport.enqueue(200, businessError())
            _ = await harness.auth.login(username: "admin", password: "bad")
            harness.clock.advance(120)
        }

        let refill = await harness.auth.login(username: "admin", password: "bad")
        XCTAssertEqual(refill.failure, APIError.refillPassword)
        XCTAssertEqual(harness.transport.callCount, LoginBackoff.refillPasswordAfterFailures)

        // Retyping is the whole remedy, so the next attempt is allowed out again.
        harness.transport.enqueue(200, Wire.login())
        let after = await harness.auth.login(username: "admin", password: "admin123")
        XCTAssertEqual(after.value?.username, "admin")
        XCTAssertEqual(harness.transport.callCount, LoginBackoff.refillPasswordAfterFailures + 1)
    }

    func testASuccessfulLoginResetsTheStreak() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, businessError())
        _ = await harness.auth.login(username: "admin", password: "bad")
        harness.clock.advance(10)
        harness.transport.enqueue(200, Wire.login())
        _ = await harness.auth.login(username: "admin", password: "admin123")

        harness.transport.enqueue(200, businessError())
        let again = await harness.auth.login(username: "admin", password: "bad")
        XCTAssertEqual(again.failure, APIError.business(code: 400, message: "用户名或密码错误"))
    }

    func testProfileRefreshesTheCachedIdentity() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.success(Wire.me))

        let result = await harness.auth.profile()
        XCTAssertEqual(result.value?.nickname, "System Admin")
        let cached = try await harness.session.cachedAccount()
        XCTAssertEqual(cached?.username, "admin")
    }

    /// The backend answers `/auth/me` with a bare map that has no tenant keys at all, so a refresh must
    /// not blank the tenant the login response cached.
    func testProfileRefreshKeepsTheCachedTenant() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.success(Wire.me))
        let result = await harness.auth.profile()

        XCTAssertNil(result.value?.tenantId)
        let state = await harness.relaunched().state()
        XCTAssertEqual(state.account?.tenantID, 1)
        XCTAssertEqual(state.account?.tenantName, "Default")
        XCTAssertEqual(state.account?.nickname, "System Admin")
    }

    /// A 401 on the profile read means the session has no future; the app should not sit on the token.
    func testUnauthorizedProfileEndsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(401, Wire.unauthorized)

        let result = await harness.auth.profile()
        let state = await harness.auth.state()
        XCTAssertEqual(result.failure, APIError.unauthorized)
        XCTAssertEqual(state, .signedOut)
    }

    func testLogoutKeepsTheServerAddresses() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        let config = try ServerConfig(
            adminBaseURL: "https://harnax.example.com",
            routerBaseURL: "https://harnax.example.com:28081"
        )
        try await harness.configs.update(config)

        await harness.auth.logout()

        let token = try await harness.session.accessToken()
        let stored = try await harness.configs.current()
        XCTAssertNil(token)
        XCTAssertEqual(stored, config)
    }

    /// A different stack deserves a clean slate: the earlier guesses were aimed at another server.
    func testSavingServerConfigurationClearsTheStreak() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, businessError())
        _ = await harness.auth.login(username: "admin", password: "bad")

        let saved = await harness.auth.save(
            serverConfiguration: try ServerConfig(
                adminBaseURL: "https://another.example.com",
                routerBaseURL: "https://another.example.com:28081"
            )
        )
        harness.transport.enqueue(200, businessError())
        let next = await harness.auth.login(username: "admin", password: "bad")

        XCTAssertNil(saved.failure)
        XCTAssertEqual(next.failure, APIError.business(code: 400, message: "用户名或密码错误"))
    }

    func testServerConfigurationFallsBackToTheDevStackOnFirstRun() async throws {
        let harness = APIHarness()
        let result = await harness.auth.serverConfiguration()
        XCTAssertEqual(result.value?.adminBaseURL, ServerConfig.devAdminBaseURL)
        XCTAssertEqual(result.value?.routerBaseURL, ServerConfig.devRouterBaseURL)
    }

    // MARK: - a keychain that refuses, as opposed to one that is empty

    /// `KeychainStore` answers `errSecItemNotFound` with `nil` and every other OSStatus by throwing
    /// (`Sources/HarnaxCore/Secrets/KeychainStore.swift:36-41`). A thrown -25308 is the keychain being
    /// locked, not the account being absent, so cold restore must not settle on "nobody is signed in".
    func testAKeychainThatRefusesTheTokenReadIsNotReportedAsSignedOut() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failRead(-25308, for: .accessToken)

        let state = await stack.auth.state()
        XCTAssertEqual(state, .unknown, "a refused read says nothing about who is signed in")
    }

    /// The identity card is the second read `state()` does, and a lock there is the same non-answer.
    func testAKeychainThatRefusesTheCardReadIsNotReportedAsAFreshInstall() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failRead(-25308, for: .cachedAccount)

        let state = await stack.auth.state()
        XCTAssertEqual(state, .unknown)
    }

    /// `.unknown` is a wait rather than a verdict: once the keychain answers again the session is simply
    /// there, so a store that was locked for one read never costs anyone their sign-in.
    func testASessionLockedOutByOneReadComesBackWhenTheNextReadAnswers() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failRead(-25308, for: .accessToken)
        let locked = await stack.auth.state()
        XCTAssertEqual(locked, .unknown)

        stack.store.clearFaults()
        let state = await stack.auth.state()
        XCTAssertEqual(state.account?.username, "admin")
    }

    /// A blob this side cannot read is not a locked keychain — waiting on it would never resolve, and
    /// signing in again is the only remedy, so that one still reads as signed out.
    func testACorruptCachedCardIsStillTreatedAsSignedOut() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        try stack.store.setValue("{ not json", for: .cachedAccount)

        let state = await stack.auth.state()
        XCTAssertEqual(state, .signedOut)
    }

    // MARK: - moving the stack a credential was minted for

    /// A bearer is only good on the host that signed it. `DESIGN.md:81` puts the address entry before the
    /// login and the sheet is reachable from `Me`, so a signed-in edit has to end the session the old stack
    /// owns rather than send its token to a stranger.
    func testMovingTheAdminAddressDropsTheCredentialTheOldHostMinted() async throws {
        let harness = APIHarness()
        try await harness.signIn()

        let saved = await harness.auth.save(serverConfiguration: try ServerConfig(
            adminBaseURL: "https://another.example.com",
            routerBaseURL: ServerConfig.devRouterBaseURL
        ))
        let token = try await harness.session.accessToken()
        let routerKey = try await harness.session.routerAPIKey()

        XCTAssertNil(saved.failure)
        XCTAssertNil(token, "the old stack's bearer has no business on the new one")
        XCTAssertNil(routerKey)
        let state = await harness.auth.state()
        XCTAssertEqual(state, .signedOut)
    }

    /// The two addresses name one stack (`login.address.hint`) and the router key was minted by the login on
    /// the admin side, so moving either one ends the whole session.
    func testMovingOnlyTheRouterAddressDropsTheRouterKeyToo() async throws {
        let harness = APIHarness()
        try await harness.signIn()

        _ = await harness.auth.save(serverConfiguration: try ServerConfig(
            adminBaseURL: ServerConfig.devAdminBaseURL,
            routerBaseURL: "https://router.example.com"
        ))
        let routerKey = try await harness.session.routerAPIKey()
        let token = try await harness.session.accessToken()

        XCTAssertNil(routerKey)
        XCTAssertNil(token)
    }

    /// Re-saving what is already stored is a no-op; ending a session that still matches its host would be a
    /// punishment with no crime.
    func testSavingTheAddressThatIsAlreadyStoredKeepsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        let current = try await harness.configs.current()

        _ = await harness.auth.save(serverConfiguration: current)
        let state = await harness.auth.state()

        XCTAssertEqual(state.account?.username, "admin")
        let kept = try await harness.session.accessToken()
        XCTAssertEqual(kept, "tok-1")
    }

    /// The new address has to be durable before the old session is worth destroying, so a store that refused
    /// the write leaves a signed-in user exactly where they were.
    func testAnAddressTheStoreRefusedLeavesTheSessionAlone() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failWrite(-25308, for: .adminBaseURL)

        let saved = await stack.auth.save(serverConfiguration: try ServerConfig(
            adminBaseURL: "https://another.example.com",
            routerBaseURL: "https://another.example.com:28081"
        ))
        let token = try await stack.session.accessToken()

        XCTAssertNotNil(saved.failure)
        XCTAssertEqual(token, "tok-1")
    }

    /// The sheet that moved the address is not the screen holding the signed-in copy of the truth, so the
    /// drop is announced for whoever owns the root.
    func testMovingTheServerAnnouncesThatTheCredentialsAreGone() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        let heard = expectation(description: "credentials dropped")
        let observer = NotificationCenter.default.addObserver(
            forName: .harnaxCredentialsDropped,
            object: nil,
            queue: nil
        ) { _ in heard.fulfill() }

        _ = await harness.auth.save(serverConfiguration: try ServerConfig(
            adminBaseURL: "https://another.example.com",
            routerBaseURL: "https://another.example.com:28081"
        ))
        await fulfillment(of: [heard], timeout: 2)
        NotificationCenter.default.removeObserver(observer)
    }

    /// An address that did not move ends nothing, so nothing is announced either — and a stray signal
    /// would drop a healthy session out from under the root.
    func testNothingIsAnnouncedWhenTheSessionWasNotEnded() async throws {
        let harness = APIHarness()
        let current = try await harness.configs.current()
        var announcements = 0
        let lock = NSLock()
        let observer = NotificationCenter.default.addObserver(
            forName: .harnaxCredentialsDropped,
            object: nil,
            queue: nil
        ) { _ in
            lock.lock()
            announcements += 1
            lock.unlock()
        }

        _ = await harness.auth.save(serverConfiguration: current)
        NotificationCenter.default.removeObserver(observer)
        XCTAssertEqual(announcements, 0)
    }
}

/// A keychain that refuses one named slot, the way `KeychainStore` does when `SecItem` answers anything
/// but `errSecItemNotFound` (`Sources/HarnaxCore/Secrets/KeychainStore.swift:40-41`). Shared with
/// `TenantFlowTests` and `APIClientTests`, which need the same two failure shapes.
final class FaultySecretStore: SecretStoring, @unchecked Sendable {
    private let inner = MemorySecretStore()
    private let lock = NSLock()
    private var refusedReads: [SecretKey: Int] = [:]
    private var refusedWrites: [SecretKey: Int] = [:]

    func failRead(_ status: Int, for key: SecretKey) {
        lock.lock()
        refusedReads[key] = status
        lock.unlock()
    }

    func failWrite(_ status: Int, for key: SecretKey) {
        lock.lock()
        refusedWrites[key] = status
        lock.unlock()
    }

    func clearFaults() {
        lock.lock()
        refusedReads.removeAll()
        refusedWrites.removeAll()
        lock.unlock()
    }

    private func readStatus(for key: SecretKey) -> Int? {
        lock.lock()
        defer { lock.unlock() }
        return refusedReads[key]
    }

    private func writeStatus(for key: SecretKey) -> Int? {
        lock.lock()
        defer { lock.unlock() }
        return refusedWrites[key]
    }

    func value(for key: SecretKey) throws -> String? {
        if let status = readStatus(for: key) { throw SecretStoreError.unexpectedStatus(status) }
        return try inner.value(for: key)
    }

    func setValue(_ value: String?, for key: SecretKey) throws {
        if let status = writeStatus(for: key) { throw SecretStoreError.unexpectedStatus(status) }
        try inner.setValue(value, for: key)
    }

    func removeAll() throws {
        try inner.removeAll()
    }
}

/// The stack `APIHarness` builds, over a `FaultySecretStore` a test can make refuse.
struct KeychainStack {
    let store: FaultySecretStore
    let transport: StubTransport
    let session: AuthSession
    let configs: ServerConfigStore
    let client: APIClient
    let auth: AuthFlow

    init(now: Date = Date(timeIntervalSince1970: 1_790_592_457)) {
        let store = FaultySecretStore()
        let transport = StubTransport()
        let clock = TestClock(now)
        let session = AuthSession(store: store, now: clock.now)
        let configs = ServerConfigStore(store: store)
        let client = APIClient(
            transport: transport,
            session: session,
            configs: configs,
            refresher: TokenRefresher(transport: transport, configs: configs)
        )
        self.store = store
        self.transport = transport
        self.session = session
        self.configs = configs
        self.client = client
        self.auth = AuthFlow(client: client, session: session, configs: configs, now: clock.now)
    }

    /// A session the client itself considers fresh, so only the server can say otherwise.
    func signIn(token: String = "tok-1") async throws {
        let body = Wire.login(token: token)
        let response = try JSONDecoder().decode(Envelope<LoginResponse>.self, from: Data(body.utf8)).data!
        try await session.signIn(response)
    }
}
