import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The keychain is the only thing that decides whether launch shows the login screen or the tabs, so its
/// write and clear sets are pinned here.
final class AuthSessionTests: XCTestCase {
    private func makeSession() -> (AuthSession, MemorySecretStore, TestClock) {
        let clock = TestClock()
        let store = MemorySecretStore()
        return (AuthSession(store: store, now: clock.now), store, clock)
    }

    private func response(_ body: String) -> LoginResponse {
        try! JSONDecoder().decode(Envelope<LoginResponse>.self, from: Data(body.utf8)).data!
    }

    private func refreshed(_ body: String) -> RefreshedToken {
        try! JSONDecoder().decode(Envelope<RefreshedToken>.self, from: Data(body.utf8)).data!
    }

    /// Runs an actor call expected to throw and hands back the failure, so the assertion can look at its
    /// payload instead of only at the fact that something was thrown.
    private func apiError(_ body: () async throws -> Void) async -> APIError? {
        do {
            try await body()
            return nil
        } catch let error as APIError {
            return error
        } catch {
            return nil
        }
    }

    func testSignInWritesCredentialsAndIdentityCache() async throws {
        let (session, store, _) = makeSession()
        try await session.signIn(response(Wire.login(token: "tok-1", expiresIn: 3600)))

        let token = try await session.accessToken()
        let tenant = try await session.tenantID()
        let routerKey = try await session.routerAPIKey()
        let signedIn = try await session.isSignedIn()
        let cached = try await session.cachedAccount()

        XCTAssertEqual(token, "tok-1")
        XCTAssertEqual(tenant, "1")
        XCTAssertEqual(routerKey, "rk-1")
        XCTAssertEqual(try store.value(for: .tokenLifetimeMillis), "3600")
        XCTAssertTrue(signedIn)
        XCTAssertEqual(
            cached,
            AccountSnapshot(
                username: "admin", nickname: "System Admin", email: "admin@harnax.com",
                tenantID: 1, tenantName: "Default", isAdministrator: true
            )
        )
    }

    /// A login answer without a token is not a session; storing a half-written one would leave the app
    /// convinced it is signed in and then fail every call.
    func testSignInWithoutTokenIsRejected() async throws {
        let (session, store, _) = makeSession()
        let error = await apiError { try await session.signIn(self.response(Wire.success("{}"))) }
        XCTAssertEqual(error, .unpackable)
        XCTAssertNil(try store.value(for: .accessToken))
    }

    /// `refresh-token` only ever sends a lifetime, so the deadline has to be folded in on this clock.
    func testAdoptFoldsLifetimeIntoAnAbsoluteDeadline() async throws {
        let (session, store, clock) = makeSession()
        try await session.signIn(response(Wire.login(token: "tok-1", expiresIn: 3600)))
        try await session.adopt(refreshed(Wire.refreshed(token: "tok-2", expiresIn: 1800, tenantID: 7)))

        let token = try await session.accessToken()
        let tenant = try await session.tenantID()
        let deadline = try XCTUnwrap(store.value(for: .tokenExpiresAtMillis))

        XCTAssertEqual(token, "tok-2")
        XCTAssertEqual(tenant, "7")
        XCTAssertEqual(Int64(deadline), Int64(clock.date.timeIntervalSince1970 * 1000) + 1_800_000)
    }

    func testAdoptWithoutTokenEndsTheSession() async throws {
        let (session, _, _) = makeSession()
        let token = refreshed(Wire.refreshed(token: "", expiresIn: nil))
        let error = await apiError { try await session.adopt(token) }
        XCTAssertEqual(error, .unauthorized)
    }

    /// DESIGN §5.3 puts the trigger at one third of the lifetime instead of a fixed lead: a renewal only
    /// works while the old token still verifies, so a production JWT (`JWT_EXPIRATION` = 7 200 000 ms) has to
    /// start renewing 40 minutes out rather than in its last minute.
    func testNeedsRefreshTriggersBelowOneThirdOfTheLifetime() async throws {
        let (session, _, clock) = makeSession()
        try await session.signIn(response(Wire.login(token: "tok-1", expiresIn: 7200)))

        clock.advance(7200 - 2401)
        let aboveThird = try await session.needsRefresh()
        XCTAssertFalse(aboveThird, "2 401 seconds of a 7 200 second life is still above one third")

        clock.advance(2)
        let belowThird = try await session.needsRefresh()
        XCTAssertTrue(belowThird, "2 399 seconds left is below one third, and the old token is still valid")
    }

    /// A login answer naming only an absolute deadline records no lifetime to divide, so there the flat window
    /// is the whole rule.
    func testMissingLifetimeFallsBackToTheSixtySecondWindow() async throws {
        let (session, _, clock) = makeSession()
        let deadline = Int64(clock.date.timeIntervalSince1970 * 1000) + 61_000
        try await session.signIn(response(Wire.login(token: "tok-1", expiresIn: nil, expiresAt: deadline)))
        let beyond = try await session.needsRefresh()
        XCTAssertFalse(beyond)

        clock.advance(2)
        let inside = try await session.needsRefresh()
        XCTAssertTrue(inside)
    }

    /// A token with no recorded deadline cannot be judged, so it is refreshed rather than trusted.
    func testMissingExpiryIsTreatedAsNeedingRefresh() async throws {
        let (session, _, _) = makeSession()
        try await session.signIn(response(Wire.login(token: "tok-1", expiresIn: nil, expiresAt: nil)))
        let needsRefresh = try await session.needsRefresh()
        XCTAssertTrue(needsRefresh)
        let seconds = try await session.secondsUntilExpiry()
        XCTAssertNil(seconds)
    }

    func testNeedsRefreshIsFalseBeforeAnySignIn() async throws {
        let (session, _, _) = makeSession()
        let needsRefresh = try await session.needsRefresh()
        XCTAssertFalse(needsRefresh)
    }

    /// Signing out must not send the user back to re-typing an address they already configured.
    func testSignOutClearsCredentialsButKeepsServerAddresses() async throws {
        let (session, store, _) = makeSession()
        try await session.signIn(response(Wire.login()))
        let config = try ServerConfig(
            adminBaseURL: "https://harnax.example.com",
            routerBaseURL: "https://harnax.example.com:28081"
        )
        try config.save(into: store)

        try await session.signOut()

        let token = try await session.accessToken()
        let routerKey = try await session.routerAPIKey()
        let tenant = try await session.tenantID()
        let cached = try await session.cachedAccount()
        let signedIn = try await session.isSignedIn()

        XCTAssertNil(token)
        XCTAssertNil(routerKey)
        XCTAssertNil(tenant)
        XCTAssertNil(cached)
        XCTAssertFalse(signedIn)
        XCTAssertEqual(try ServerConfig.load(from: store), config)
    }

    func testCachedAccountSurvivesAKeychainRoundTrip() throws {
        let store = MemorySecretStore()
        let account = AccountSnapshot(
            username: "admin", nickname: "System Admin", email: "admin@harnax.com",
            tenantID: 1, tenantName: "Default", isAdministrator: true
        )
        try account.store(in: store)
        XCTAssertEqual(try AccountSnapshot.load(from: store), account)
    }

    /// A blob written by an older build is only dangerous if it throws; nil lets the screen refetch.
    func testCorruptCachedAccountReadsAsAbsent() throws {
        let store = MemorySecretStore()
        try store.setValue("not json", for: .cachedAccount)
        XCTAssertNil(try AccountSnapshot.load(from: store))
    }
}
