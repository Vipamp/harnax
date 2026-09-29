import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// What the client puts on the wire, and how it turns a dead token into either a fresh one or a signed-out
/// session.
final class APIClientTests: XCTestCase {
    /// A day past the refresh window, so the client believes the token is good and only the server's 401
    /// says otherwise.
    private let farFuture: Int64 = 1_790_592_457_000 + 24 * 3600 * 1000

    private func signedInHarness(language: String = "zh-CN") async -> APIHarness {
        let harness = APIHarness(language: language)
        try? await harness.signIn()
        return harness
    }

    func testHeadersCarryTokenTenantAndLanguage() async throws {
        let harness = await signedInHarness(language: "en-US")
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))
        _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)

        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        XCTAssertEqual(headerValue(request, "X-Tenant-ID"), "1")
        XCTAssertEqual(headerValue(request, "Accept-Language"), "en-US")
        XCTAssertEqual(headerValue(request, "Accept"), "application/json")
        XCTAssertNil(headerValue(request, "Content-Type"), "a GET must not claim a body")
        XCTAssertEqual(request.httpMethod, "GET")
    }

    func testPageQueryParametersReachTheURL() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))
        _ = await harness.agents.page(name: nil, status: nil, num: 3, size: 20)

        let request = try XCTUnwrap(harness.transport.requests.first)
        let query = queryItems(of: try XCTUnwrap(request.url))
        XCTAssertEqual(query.first { $0.name == "pageNum" }?.value, "3")
        XCTAssertEqual(query.first { $0.name == "pageSize" }?.value, "20")
    }

    /// A wrong password is an answer about credentials, not an expired session: the client must not attach
    /// a bearer, must not refresh, and must not retry.
    func testLoginSendsNoTokenAndNeverRetries() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueue(200, Wire.business(400, "用户名或密码错误"))
        let result = try await harness.client.send(
            LoginResponse.self,
            AdminEndpoint.cliLogin(LoginRequest(username: "admin", password: "bad"))
        )

        XCTAssertEqual(result.failure, APIError.business(code: 400, message: "用户名或密码错误"))
        XCTAssertEqual(harness.transport.callCount, 1)
        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertNil(headerValue(request, "Authorization"))
        XCTAssertEqual(headerValue(request, "Content-Type"), "application/json")
    }

    func testLoginBodySendsExactlyTheTwoCredentials() async throws {
        let harness = APIHarness()
        harness.transport.enqueue(200, Wire.login())
        _ = try await harness.client.send(
            LoginResponse.self,
            AdminEndpoint.cliLogin(LoginRequest(username: "admin", password: "admin123"))
        )

        let body = try XCTUnwrap(harness.transport.requests.first?.httpBody)
        let decoded = try JSONDecoder().decode([String: String].self, from: body)
        XCTAssertEqual(decoded, ["username": "admin", "password": "admin123"])
    }

    func testTokenInsideTheRefreshWindowIsRenewedBeforeTheCall() async throws {
        let harness = APIHarness()
        try await harness.signIn(token: "tok-1", expiresIn: 30)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertNotNil(page.value)

        let requests = harness.transport.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].url?.path, TokenRefresher.path)
        XCTAssertEqual(headerValue(requests[1], "Authorization"), "Bearer tok-2")
    }

    /// A token with plenty of life left must not cost an extra round trip.
    func testFreshTokenSkipsTheProactiveRefresh() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))

        _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(harness.transport.callCount, 1)
    }

    func testUnauthorizedIsAnsweredWithOneRefreshAndOneReplay() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))
        harness.transport.enqueue(200, Wire.agents(total: 1, ids: [11]))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.value?.records.first?.id, Int64(11))

        let requests = harness.transport.requests
        XCTAssertEqual(requests.count, 3)
        XCTAssertEqual(requests[2].url?.path, AgentEndpoint.pagePath)
        XCTAssertEqual(headerValue(requests[2], "Authorization"), "Bearer tok-2")
    }

    /// The second 401 is the ceiling: one refresh, one replay, then the answer stands.
    func testReplayIsNotRetriedOnSecondUnauthorized() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))
        harness.transport.enqueue(401, Wire.unauthorized)

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.unauthorized)
        XCTAssertEqual(harness.transport.callCount, 3)
    }

    /// A refresh the server refused with its own 401 has no future, so the credentials are cleared — while
    /// the two addresses the user typed stay put.
    func testFailedRefreshClearsCredentialsButKeepsServerAddresses() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        let config = try ServerConfig(
            adminBaseURL: "https://gateway.example.com",
            routerBaseURL: "https://gateway.example.com:28081"
        )
        try await harness.configs.update(config)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(401, Wire.unauthorized)

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        let token = try await harness.session.accessToken()
        let tenant = try await harness.session.tenantID()
        let stored = try await harness.configs.current()

        XCTAssertEqual(page.failure, APIError.unauthorized)
        XCTAssertNil(token)
        XCTAssertNil(tenant)
        XCTAssertEqual(stored, config)
    }

    /// A hop that never answered is not the server refusing the credential. The web console keeps the same
    /// split (`harnax-webui/src/requestErrorConfig.ts:135-176`): a 401 *response* clears the token, a request
    /// that came back with nothing only asks for a retry.
    func testARefreshThatTimesOutKeepsTheSessionAndReportsTheTimeout() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueueFailure(URLError(.timedOut))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        let token = try await harness.session.accessToken()

        XCTAssertEqual(page.failure, APIError.timeout)
        XCTAssertEqual(token, "tok-1", "a call that never landed cannot have revoked anything")
        XCTAssertEqual(harness.transport.callCount, 2, "a refresh that failed has nothing to replay")
    }

    func testARefreshAgainstAnUnreachableHostKeepsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueueFailure(URLError(.cannotConnectToHost))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)

        XCTAssertEqual(page.failure, APIError.offline)
        let token = try await harness.session.accessToken()
        XCTAssertEqual(token, "tok-1")
    }

    /// 503 is the stack saying it is down. Reading that as a dead credential is how one restart logs
    /// everybody out.
    func testAServiceErrorOnTheRefreshRouteKeepsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(503, #"{"status":503,"message":"upstream down"}"#)

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        let token = try await harness.session.accessToken()

        XCTAssertEqual(page.failure, APIError.business(code: 503, message: "upstream down"))
        XCTAssertEqual(token, "tok-1")
    }

    /// The device refusing to store the fresh token says nothing about the old one, and clearing here would
    /// spend a still-valid session to report a local failure.
    func testAKeychainThatRefusesTheFreshTokenKeepsTheSession() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failWrite(-25308, for: .accessToken)
        stack.transport.enqueue(401, Wire.unauthorized)
        stack.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))

        let result = await stack.client.send([TenantSummary].self, AdminEndpoint.tenants)
        let token = try await stack.session.accessToken()

        XCTAssertEqual(result.failure, APIError.offline)
        XCTAssertEqual(token, "tok-1", "the token still in the keychain is the one the server minted")
    }

    /// The byte route follows the same rule, because a stale token looks identical there.
    func testARawCallWhoseRefreshOnlySawATimeoutKeepsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueueFailure(URLError(.timedOut))

        let result = await harness.client.sendRaw(TeamArtifactEndpoint.download(fileId: "f-1", sessionId: "s-1"))

        XCTAssertEqual(result.failure, APIError.timeout)
        let token = try await harness.session.accessToken()
        XCTAssertEqual(token, "tok-1")
    }

    /// Refused twice — with the old token and with the one the server just minted — is the clearest possible
    /// answer about the credential, so the session ends instead of burning a refresh round trip on every
    /// later call.
    func testAReplayStillRefusedAfterAFreshTokenEndsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))
        harness.transport.enqueue(401, Wire.unauthorized)

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        let token = try await harness.session.accessToken()

        XCTAssertEqual(page.failure, APIError.unauthorized)
        XCTAssertNil(token)
    }

    func testTransportTimeoutIsReportedAsTimeout() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueueFailure(URLError(.timedOut))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.timeout)
    }

    func testUnreachableHostIsReportedAsOffline() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueueFailure(URLError(.cannotConnectToHost))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.offline)
    }

    /// The address sheet writes through the store, so the very next call has to hit the new host.
    func testSavedServerAddressTakesEffectOnTheNextRequest() async throws {
        let harness = await signedInHarness()
        try await harness.configs.update(
            ServerConfig(adminBaseURL: "https://harnax.example.com/", routerBaseURL: "http://127.0.0.1:28081")
        )
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))

        _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        let url = try XCTUnwrap(harness.transport.requests.first?.url)
        XCTAssertEqual(url.absoluteString, "https://harnax.example.com/api/admin/agents/page?pageNum=1&pageSize=20")
    }
}

extension Result {
    var failure: Failure? {
        if case let .failure(error) = self { return error }
        return nil
    }

    var value: Success? {
        if case let .success(value) = self { return value }
        return nil
    }
}
