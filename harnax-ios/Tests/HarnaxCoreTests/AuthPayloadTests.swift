import XCTest

@testable import HarnaxCore

final class AuthPayloadTests: XCTestCase {
    func testLoginResponseDecodesEveryFieldFromRealCapture() throws {
        let login = try Fixture.decode(Envelope<LoginResponse>.self, "login-success").data!
        XCTAssertEqual(login.accessToken, "eyJhbGciOiJIUzUxMiJ9.REDACTED.signature")
        XCTAssertEqual(login.tokenType, "Bearer")
        XCTAssertEqual(login.expiresIn, 7200)
        XCTAssertEqual(login.expiresAt, 1_790_599_645_734)
        XCTAssertEqual(login.currentTenantId, 1)
        XCTAssertEqual(login.routerApiKey, "hnx_sk_live_REDACTED")

        let user = try XCTUnwrap(login.userInfo)
        XCTAssertEqual(user.userId, 1)
        XCTAssertEqual(user.username, "admin")
        XCTAssertEqual(user.nickname, "System Admin")
        XCTAssertEqual(user.isAdmin, 1)
        XCTAssertTrue(user.isAdministrator)
        /// `avatar` and `phone` come back as empty strings, not null — the login screen must not
        /// treat "" as a renderable avatar URL.
        XCTAssertEqual(user.avatar, "")
        XCTAssertEqual(user.phone, "")

        let tenants = try XCTUnwrap(login.tenants)
        XCTAssertEqual(tenants.count, 1)
        XCTAssertEqual(tenants[0].id, 1)
        XCTAssertEqual(tenants[0].name, "Default Organization")
        XCTAssertEqual(tenants[0].status, 1)
    }

    /// `auth/me` returns a `Map<String, Any?>`; the admin row has no tenant column value, so
    /// Jackson drops the key entirely instead of emitting null.
    func testMeInfoToleratesAbsentTenantIdKey() throws {
        let me = try Fixture.decode(Envelope<MeInfo>.self, "me-success").data!
        XCTAssertEqual(me.id, 1)
        XCTAssertNil(me.tenantId)
        XCTAssertEqual(me.authMode, "jwt")
        XCTAssertEqual(me.expiresAt, 1_790_599_645_000)
        XCTAssertTrue(me.isAdministrator)
    }

    func testLoginRequestEncodesOnlyUsernameAndPassword() throws {
        let data = try JSONEncoder().encode(LoginRequest(username: "admin", password: "admin123"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(Set(object.keys), ["username", "password"])
        XCTAssertEqual(object["password"], "admin123")
    }

    func testRefreshedTokenHasNoExpiresAtOnTheWire() throws {
        let data = Data(#"{"accessToken":"a.b.c","tenantId":7,"expiresIn":7200}"#.utf8)
        let refreshed = try JSONDecoder().decode(RefreshedToken.self, from: data)
        XCTAssertEqual(refreshed.accessToken, "a.b.c")
        XCTAssertEqual(refreshed.tenantId, 7)
        XCTAssertEqual(refreshed.expiresIn, 7200)
    }

    func testSwitchTenantRequestEncodesOnlyTheTenantId() throws {
        let data = try JSONEncoder().encode(SwitchTenantRequest(tenantId: 7))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Int])
        XCTAssertEqual(object, ["tenantId": 7])
    }

    /// `POST /auth/switch-tenant` answers `{accessToken, tenantId}` and nothing else
    /// (`AuthController.kt:172-177`), so a missing lifetime has to decode as absent rather than fail the
    /// whole switch.
    func testTheSwitchReplyCarriesNoLifetime() throws {
        let data = Data(#"{"accessToken":"a.b.c","tenantId":7}"#.utf8)
        let refreshed = try JSONDecoder().decode(RefreshedToken.self, from: data)
        XCTAssertEqual(refreshed.accessToken, "a.b.c")
        XCTAssertEqual(refreshed.tenantId, 7)
        XCTAssertNil(refreshed.expiresIn)
    }

    /// The switch moves the tenant, not the person: everything the identity card reads besides the tenant
    /// pair stays byte for byte, including the administrator flag that gates the whole system tab.
    func testWithTenantRewritesOnlyTheTenantPair() {
        let account = AccountSnapshot(
            username: "admin",
            nickname: "System Admin",
            email: "admin@harnax.com",
            tenantID: 1,
            tenantName: "Default",
            isAdministrator: true
        )
        let moved = account.withTenant(id: 2, name: "Acme Workspace")

        XCTAssertEqual(moved.tenantID, 2)
        XCTAssertEqual(moved.tenantName, "Acme Workspace")
        XCTAssertEqual(moved.username, account.username)
        XCTAssertEqual(moved.nickname, account.nickname)
        XCTAssertEqual(moved.email, account.email)
        XCTAssertEqual(moved.isAdministrator, account.isAdministrator)
    }

    /// The reconcile's tenant rule, and the case the stale comments in `Facades.swift` denied: `me` answers no
    /// tenant *name* but does answer a tenant id (`AuthController.kt:121` —
    /// `TenantContext.getTenantId() ?: currentUser.tenantId`). So a card that carried the old name over would
    /// label the new tenant with the workspace the session has left — a name and an id that disagree, with the
    /// id the one every later `X-Tenant-ID` is read from.
    func testAReconciledCardDropsANameItDidNotReadForTheTenantItTook() {
        let cached = AccountSnapshot(username: "admin", tenantID: 1, tenantName: "Default")
        let moved = AccountSnapshot(username: "admin", tenantID: 2).mergingWith(cached)

        XCTAssertEqual(moved.tenantID, 2, "the server's tenant wins")
        XCTAssertNil(moved.tenantName, "and the old workspace's label goes with the old workspace")
    }

    /// The ordinary launch, where `me` either repeats the current tenant or omits the key entirely
    /// (`testMeInfoToleratesAbsentTenantIdKey`): nothing moved, so the label the login response filled in is
    /// still true and has to survive — an empty name here is an identity card that lost its tenant line.
    func testAReconciledCardKeepsTheTenantNameWhenTheTenantDidNotMove() {
        let cached = AccountSnapshot(username: "admin", tenantID: 1, tenantName: "Default")

        XCTAssertEqual(AccountSnapshot(username: "admin", tenantID: 1).mergingWith(cached).tenantName, "Default")
        XCTAssertEqual(AccountSnapshot(username: "admin", tenantID: nil).mergingWith(cached).tenantName, "Default")
        XCTAssertEqual(AccountSnapshot(username: "admin", tenantID: nil).mergingWith(cached).tenantID, 1)
    }
}
