import XCTest
import Foundation
import HarnaxCore
@testable import HarnaxAPI

/// The tenant switch, tested as the session mutation it really is.
///
/// The web console renders no switcher (`harnax-webui/src/components/TenantSwitcher/index.tsx:92` returns
/// `null` unconditionally and nothing in that repo ever calls the route), so these expectations come from
/// the backend alone: `AuthController.kt:149-182` mints a fresh token for the target tenant and answers
/// `{accessToken, tenantId}` with no lifetime, and `TenantInterceptor.kt:28-58` resolves the working
/// tenant from the `X-Tenant-ID` header before it looks at the token claim.
final class TenantFlowTests: XCTestCase {
    func testTheTenantListIsReadOverTheAuthRoute() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.tenants([(1, "Default", 1), (2, "Acme Workspace", 1)]))

        let result = await harness.auth.tenantOptions()
        let rows = try XCTUnwrap(result.value)
        let request = try XCTUnwrap(harness.transport.requests.first)

        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/admin/auth/tenants")
        XCTAssertEqual(rows.map(\.id), [1, 2])
        XCTAssertEqual(rows.map(\.name), ["Default", "Acme Workspace"])
    }

    /// A membership row can name a tenant whose own row is disabled — the read filters memberships by
    /// `status=1` but never the tenant (`UserTenantServiceImpl.kt:31-43`) — so the flag has to survive.
    func testADisabledTenantRowStillDecodesWithItsFlag() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.tenants([(3, "Retired Org", 0)]))

        let result = await harness.auth.tenantOptions()
        let rows = try XCTUnwrap(result.value)
        XCTAssertEqual(rows.first?.status, 0)
    }

    func testSwitchingSendsTheIdAndNothingElse() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 2))

        _ = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let request = try XCTUnwrap(harness.transport.requests.last)
        let body = try XCTUnwrap(request.httpBody)

        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/admin/auth/switch-tenant")
        let sent = try JSONDecoder().decode([String: Int64].self, from: body)
        XCTAssertEqual(sent, ["tenantId": 2])
    }

    /// The switch is a re-authentication, not a header override: the old token names the tenant the user
    /// just left, and the interceptor would keep sending them there.
    func testASuccessfulSwitchReplacesTheTokenAndPinsTheNewTenant() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 2))

        let result = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let token = try await harness.session.accessToken()
        let tenant = try await harness.session.tenantID()

        XCTAssertNil(result.failure)
        XCTAssertEqual(token, "tok-2")
        XCTAssertEqual(tenant, "2")
    }

    /// The next request carries the header the interceptor reads, so the switch is only real once it moves.
    func testTheTenantHeaderFollowsTheSwitch() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 2))
        _ = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))

        harness.transport.enqueue(200, Wire.success(Wire.me))
        _ = await harness.auth.profile()
        let request = try XCTUnwrap(harness.transport.requests.last)

        XCTAssertEqual(headerValue(request, "X-Tenant-ID"), "2")
    }

    /// The reply has no `expiresIn` (`AuthController.kt:172-177`), so the stored deadline is the one the
    /// login minted. Re-timing it here would mean the client deciding how long the server's token lives.
    func testASwitchReplyWithoutALifetimeKeepsTheStoredDeadline() async throws {
        let harness = APIHarness()
        try await harness.signIn(expiresIn: 3600)
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 2))

        _ = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let remaining = try await harness.session.secondsUntilExpiry()

        XCTAssertEqual(remaining, 3600)
    }

    /// The identity card reads the keychain, not the network, so it has to be rewritten here or the user
    /// would sit on a card naming the tenant they left until the next profile refresh landed.
    func testSwitchingRewritesTheCachedIdentity() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 2))

        _ = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let state = await harness.relaunched().state()

        XCTAssertEqual(state.account?.tenantID, 2)
        XCTAssertEqual(state.account?.tenantName, "Acme Workspace")
        XCTAssertEqual(state.account?.username, "admin", "the switch moves the tenant, not the person")
    }

    /// The response echoes a `tenantId`, but the request is what was asked for. A refusal or a stale claim
    /// must not send the session somewhere else.
    func testTheRequestedTenantWinsOverTheEchoedOne() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.refreshed(token: "tok-2", expiresIn: nil, tenantID: 9))

        _ = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let tenant = try await harness.session.tenantID()

        XCTAssertEqual(tenant, "2")
    }

    func testARejectedSwitchLeavesTheSessionWhereItWas() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(200, Wire.business(403, "无权切换到该租户"))

        let result = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let token = try await harness.session.accessToken()
        let tenant = try await harness.session.tenantID()

        XCTAssertEqual(result.failure, APIError.business(code: 403, message: "无权切换到该租户"))
        XCTAssertEqual(token, "tok-1")
        XCTAssertEqual(tenant, "1")
    }

    /// A 401 here says the current token is already dead, which no retry against another tenant fixes.
    func testAnUnauthorizedSwitchEndsTheSession() async throws {
        let harness = APIHarness()
        try await harness.signIn()
        harness.transport.enqueue(401, Wire.unauthorized)
        harness.transport.enqueue(401, Wire.unauthorized)

        let result = await harness.auth.switchTenant(to: TenantSummary(id: 2, name: "Acme Workspace", status: 1))
        let state = await harness.auth.state()

        XCTAssertEqual(result.failure, APIError.unauthorized)
        XCTAssertEqual(state, .signedOut)
    }

    /// `tenantId` is the only thing that goes out, so a row without one cannot be switched to at all — and
    /// guessing an id would move the session into a tenant nobody chose.
    func testARowWithoutAnIdNeverReachesTheNetwork() async throws {
        let harness = APIHarness()
        try await harness.signIn()

        let result = await harness.auth.switchTenant(to: TenantSummary(id: nil, name: "Nameless", status: 1))

        XCTAssertEqual(result.failure, APIError.decoding)
        XCTAssertEqual(harness.transport.callCount, 0, "a row with no id cannot be addressed, so nothing goes out")
    }
}
