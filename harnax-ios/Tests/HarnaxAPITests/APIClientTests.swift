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
        try await harness.signIn(token: "tok-1", expiresIn: 90)
        // 29 seconds left of a 90-second life is below one third, the threshold DESIGN §5.3 sets.
        harness.clock.advance(61)
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

    /// The replay is the same call, not a call of the same shape. The body the server refused under the old
    /// bearer has to reach it byte for byte under the new one — a replay that re-encoded it, dropped it or
    /// moved it to a stream would post something other than what the user asked for.
    func testTheReplaySendsTheOriginalBodyByteForByte() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        let endpoint = try AdminEndpoint.switchTenant(SwitchTenantRequest(tenantId: 2))
        let original = try XCTUnwrap(endpoint.body)
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600, tenantID: 2))
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600, tenantID: 2))

        let result = await harness.client.send(RefreshedToken.self, endpoint)
        XCTAssertNotNil(result.value)

        let requests = harness.transport.requests
        XCTAssertEqual(requests.count, 3)
        let replayed = try XCTUnwrap(requests.last)
        XCTAssertEqual(replayed.httpBody, original, "what went out the second time is what went out first")
        XCTAssertEqual(replayed.httpBody, requests[0].httpBody)
        XCTAssertNil(replayed.httpBodyStream, "a body carried as a stream is not the body that was sent")
        XCTAssertEqual(replayed.httpMethod, "POST")
        XCTAssertEqual(replayed.url?.path, AdminEndpoint.switchTenantPath)
        XCTAssertEqual(headerValue(replayed, "Content-Type"), "application/json")
        XCTAssertEqual(headerValue(replayed, "Authorization"), "Bearer tok-2", "only the bearer changed")
    }

    /// A token inside the refresh window is normally renewed before the call goes out. For the one call that
    /// ends the session that is exactly wrong: the server would be left holding the bearer the user is
    /// throwing away while the fresh one, which nothing ever revokes, becomes the live credential.
    func testASessionEndingCallDoesNotRenewTheTokenItIsDiscarding() async throws {
        let harness = APIHarness()
        try await harness.signIn(token: "tok-1", expiresIn: 90)
        // Inside the renewal threshold, so this test really is about the call that opts out of it.
        harness.clock.advance(61)
        harness.transport.enqueue(200, Wire.success())

        _ = await harness.client.send(EmptyResponse.self, AdminEndpoint.logout)

        XCTAssertEqual(harness.transport.callCount, 1, "no refresh round trip in front of the revoke")
        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(request.url?.path, AdminEndpoint.logoutPath)
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        let token = try await harness.session.accessToken()
        XCTAssertEqual(token, "tok-1", "the sign-out in progress mints nothing")
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

    /// A list read that ends the session is not the sign-out button, so nothing above the transport knows the
    /// account is gone: without the announcement the tab bar keeps greeting a dead bearer. This is the refused
    /// refresh (`APIClient.settleRefresh`), and exactly one signal per ended session.
    func testARefusedRefreshAnnouncesThatTheCredentialsAreGone() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        let announcements = try await Self.countDrops {
            harness.transport.enqueue(401, Wire.unauthorized)
            harness.transport.enqueue(401, Wire.unauthorized)
            _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        }
        XCTAssertEqual(announcements, 1)
    }

    /// The second 401 is the other place a session is known dead (`APIClient.settleReplay`): the refresh minted
    /// a token and the server still refused it. Same requirement, and the keychain really did go with it.
    func testAReplayThatIsStillRefusedAnnouncesTheDroppedCredentials() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        let announcements = try await Self.countDrops {
            harness.transport.enqueue(401, Wire.unauthorized)
            harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: 3600))
            harness.transport.enqueue(401, Wire.unauthorized)
            _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        }
        XCTAssertEqual(announcements, 1, "a session ended from a pushed screen has to announce itself")
        let token = try await harness.session.accessToken()
        XCTAssertNil(token, "and the fresh bearer that was refused went with it")
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

        XCTAssertEqual(page.failure, APIError.unreachable)
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

        XCTAssertEqual(result.failure, APIError.requestNotSent)
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

    /// DESIGN §15 O8: with `minio.enabled=false` the whole `OutputFileController` is unregistered (`:38`) and a
    /// bare deployment leaves the switch off (`application.yml:96`), so admin answers the path as unknown —
    /// `GlobalExceptionHandler.kt:101-105` writes its own 404 envelope for it. That is one fact about the server
    /// rather than a mystery about the file the user tapped.
    func testAnUnregisteredArtifactStoreRouteNamesTheStoreDisabled() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(
            404,
            Wire.envelope(code: 404, message: "Requested resource not found", data: nil)
        )

        let result = await harness.agents.downloadAttachment(
            Self.attachment(), sessionId: "s-1"
        )

        XCTAssertEqual(result.failure, APIError.objectStoreDisabled)
    }

    /// Same rule on the list leg, and it is provable there rather than merely ruled: `listArtifacts` answers an
    /// envelope even when it refuses (`TeamArtifactController.kt:64`) and never a 404, so admin's own
    /// route-missing sentence can only be the controller being unregistered.
    func testAnUnregisteredTeamArtifactListNamesTheStoreDisabled() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(
            404,
            Wire.envelope(code: 404, message: "Requested resource not found", data: nil)
        )

        let result = await harness.agents.teamArtifacts(sessionId: "sess-team-1")

        XCTAssertEqual(result.failure, APIError.objectStoreDisabled)
    }

    /// The bytes leg keeps the plain answer. By the time a row is tappable the list has already come back, so the
    /// controller is registered and its 404 is `ResponseEntity.notFound().build()` — a reference that is gone or
    /// somebody else's (`:80-86`) — which must not be dressed up as a deployment switch.
    func testATeamArtifactThatIsSimplyGoneIsNotReportedAsTheStoreDisabled() async throws {
        let harness = APIHarness()
        try await harness.signInWithExpiry(farFuture)
        harness.transport.enqueue(404, "")

        let result = await harness.client.sendRaw(TeamArtifactEndpoint.download(fileId: "f-1", sessionId: "s-1"))

        XCTAssertEqual(result.failure, APIError.business(code: 404, message: ""))
    }

    /// And the same on the attachment leg: the controller's own miss has no body, so the file the user tapped is
    /// reported as missing rather than the store as disabled.
    func testAnAttachmentThatIsSimplyGoneIsNotReportedAsTheStoreDisabled() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(404, "")

        let result = await harness.agents.downloadAttachment(
            Self.attachment(), sessionId: "s-1"
        )

        XCTAssertEqual(result.failure, APIError.business(code: 404, message: ""))
    }

    /// `ChatFileAttachment` keeps an internal memberwise init, so JSON is the only door into it from here.
    private static func attachment() -> ChatFileAttachment {
        let body = """
        {"fileId":"3f1a2b3c-0000-0000-0000-0000000000aa","fileName":"report.md",\
        "filePath":"web/s-1/report.md","fileSize":12,"mimeType":"text/markdown","url":"",\
        "objectKey":"web/s-1/3f1a2b3c-0000-0000-0000-0000000000aa"}
        """
        return try! JSONDecoder().decode(ChatFileAttachment.self, from: Data(body.utf8))
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

    /// A host that answered nothing is not the phone having no network. `error.offline` sends the user to
    /// their Wi-Fi settings for a stack that is simply not running, so the two stay separate outcomes.
    func testAConnectionThatWasNeverMadeIsReportedAsUnreachable() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueueFailure(URLError(.cannotConnectToHost))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.unreachable)
    }

    /// The one case that keeps the network sentence: the device has no route to anywhere.
    func testNoConnectionAtAllIsStillReportedAsOffline() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueueFailure(URLError(.notConnectedToInternet))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.offline)
    }

    /// A read this side stopped is not a network verdict. `.refreshable` and a superseded query cancel the
    /// task in flight, and the answer that never came used to arrive here as the network-down sentence.
    func testACancelledReadIsNotReportedAsANetworkFailure() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueueFailure(URLError(.cancelled))

        let page = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)
        XCTAssertEqual(page.failure, APIError.cancelled)
    }

    /// The bearer lives in the keychain, and a read that was refused happens before any socket opens. The
    /// remedy is nowhere near the network settings.
    func testACredentialThatCannotBeReadIsReportedBeforeAnythingIsSent() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        stack.store.failRead(-25308, for: .accessToken)

        let result = await stack.client.send([TenantSummary].self, AdminEndpoint.tenants)

        XCTAssertEqual(result.failure, APIError.requestNotSent)
        XCTAssertEqual(stack.transport.callCount, 0, "nothing went on the wire")
    }

    /// A stored address this side cannot parse is a settings problem, and the catalogue already names it —
    /// it must not be downgraded to a transport failure on the way out.
    func testASavedAddressThatIsNotAnAddressKeepsItsOwnCopy() async throws {
        let stack = KeychainStack()
        try await stack.signIn()
        try stack.store.setValue("not a url", for: .adminBaseURL)
        await stack.configs.invalidate()

        let result = await stack.client.send([TenantSummary].self, AdminEndpoint.tenants)

        XCTAssertEqual(result.failure, APIError.invalidServerConfig("admin: not a url"))
        XCTAssertEqual(stack.transport.callCount, 0)
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

    // MARK: - which credential the router takes

    /// The router's filter reads `Authorization` before it ever looks at `X-Api-Key`, and a bearer whose
    /// signature it cannot verify ends the request right there
    /// (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/UnifiedAuthFilter.kt:47-93`). Admin signs a user
    /// JWT with `JWT_SECRET` (`harnax-admin/src/main/resources/application.yml:80-81`) while the router
    /// verifies with `HARNAX_AUTH_SECRET`
    /// (`harnax-session-router/src/main/resources/application.yml:141`) — two different secrets by design
    /// (`harnax-auth/src/main/kotlin/com/agnetix/harnax/auth/InternalTokenProvider.kt:59-67`). Measured on the
    /// live stack 2026-09-30: the key alone 200, the bearer alone 401, both together 401. So the key has to go
    /// alone, which is the shape the console sends (`ChatWindow.tsx:158-169`).
    func testARouterCallCarriesThePermanentKeyAndNothingThatCouldPreemptIt() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueue(200, Wire.success("[]"))
        _ = await harness.agents.history(sessionId: "s-1")

        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(headerValue(request, "X-Api-Key"), "rk-1")
        XCTAssertNil(headerValue(request, "Authorization"), "the filter 401s on the bearer before it reads the key")
        XCTAssertNil(headerValue(request, "X-Tenant-ID"), "the key already names the tenant it validates to")
    }

    /// A keyless account is the pre-permanent-key shape, and the bearer is the only credential it owns.
    func testAKeylessAccountGoesToTheRouterOnTheBearerAlone() async throws {
        let harness = APIHarness()
        try await harness.session.signIn(harness.decode(Wire.login(routerKey: nil)))
        harness.transport.enqueue(200, Wire.success("[]"))
        _ = await harness.agents.history(sessionId: "s-1")

        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        XCTAssertNil(headerValue(request, "X-Api-Key"))
    }

    /// The key is the router's credential and no one else's: admin reads the bearer, and its internal filter
    /// would read an `X-Api-Key` as an external caller instead.
    func testAnAdminCallCarriesNoPermanentKey() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueue(200, Wire.agents(total: 0, ids: []))
        _ = await harness.agents.page(name: nil, status: nil, num: 1, size: 20)

        let request = try XCTUnwrap(harness.transport.requests.first)
        XCTAssertEqual(headerValue(request, "Authorization"), "Bearer tok-1")
        XCTAssertNil(headerValue(request, "X-Api-Key"))
    }

    /// The bounce these rules exist to stop. A router route that went out on the bearer alone came back 401,
    /// the client renewed the bearer (admin accepts it, so the renewal succeeds), replayed, was refused again,
    /// and the second 401 emptied the keychain — which is the one door to the login screen
    /// (`AuthSession.signOut()` posts what `AppModel` listens for).
    func testARejectedPermanentKeyDoesNotEndTheAdminSession() async throws {
        let harness = await signedInHarness()
        harness.transport.enqueue(401, Wire.unauthorized)
        // A second refusal, for whatever the replay asks with: without it the stub runs dry and the old
        // behaviour would look like it had kept the session for a reason it never had.
        harness.transport.enqueue(401, Wire.unauthorized)
        var refusal: APIError?
        let drops = try await Self.countDrops {
            refusal = await harness.agents.history(sessionId: "s-1").failure
        }

        XCTAssertEqual(refusal, APIError.unauthorized)
        XCTAssertEqual(drops, 0, "a dead router key is not a dead admin session")
        XCTAssertEqual(harness.transport.callCount, 1, "a bearer renewal cannot fix a rejected key")
        let stillSignedIn = try await harness.session.isSignedIn()
        XCTAssertTrue(stillSignedIn)
    }

    /// How many times the credentials were announced gone while `body` ran. The post is synchronous and lands
    /// inside the call that clears the keychain, so the count is final the moment that call returns.
    private static func countDrops(_ body: () async throws -> Void) async throws -> Int {
        let lock = NSLock()
        var announcements = 0
        let observer = NotificationCenter.default.addObserver(
            forName: .harnaxCredentialsDropped,
            object: nil,
            queue: nil
        ) { _ in
            lock.lock()
            announcements += 1
            lock.unlock()
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        try await body()
        return announcements
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
