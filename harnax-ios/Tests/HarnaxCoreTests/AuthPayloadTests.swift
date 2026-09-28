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
}
