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
}
