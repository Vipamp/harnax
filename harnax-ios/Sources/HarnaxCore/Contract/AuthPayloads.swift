import Foundation

/// `POST /api/admin/auth/cli-login` body.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginRequest.kt:9-29`.
/// `captcha` / `captchaKey` / `autoLogin` are deliberately not encoded: the CLI口 ignores them
/// (`AuthController.kt:45-49`), and iOS sends the plain password because the server applies
/// `sha256Hex` before the BCrypt comparison (`AuthServiceImpl.kt:195-200`).
public struct LoginRequest: Encodable, Equatable, Sendable {
    public let username: String
    public let password: String

    public init(username: String, password: String) {
        self.username = username
        self.password = password
    }
}

/// `LoginResponse` from either login口.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/LoginResponse.kt:14-40`.
/// Every field is nullable there, so iOS mirrors the optionality and validates at the use site
/// rather than pretending the contract guarantees more than it does.
public struct LoginResponse: Decodable, Equatable, Sendable {
    public let accessToken: String?
    public let tokenType: String?
    public let expiresIn: Int64?
    public let expiresAt: Int64?
    public let userInfo: LoginUserInfo?
    public let tenants: [TenantSummary]?
    public let currentTenantId: Int64?
    public let routerApiKey: String?
}

/// Nested `LoginResponse.UserInfo` (`LoginResponse.kt:79-96`).
public struct LoginUserInfo: Decodable, Equatable, Sendable {
    public let userId: Int64?
    public let username: String?
    public let nickname: String?
    public let avatar: String?
    public let email: String?
    public let phone: String?
    public let gender: Int?
    public let isAdmin: Int?

    public var isAdministrator: Bool { isAdmin == 1 }
}

/// `TenantResponse` as far as iOS reads it (`dto/response/TenantResponse.kt:9-31`).
///
/// `creator` / `createTime` / `updateTime` are not decoded: they are ISO-8601 strings the tenant
/// picker never shows, and decoding a subset keeps the login path immune to date-format drift.
public struct TenantSummary: Decodable, Equatable, Sendable {
    public let id: Int64?
    public let name: String?
    public let status: Int?
}

/// `GET /api/admin/auth/me` payload — a `Map<String, Any?>` on the wire
/// (`AuthController.kt:106-124`). Keys disappear when the backend value is null, which is why
/// every field here is optional, including `id`.
public struct MeInfo: Decodable, Equatable, Sendable {
    public let id: Int64?
    public let username: String?
    public let nickname: String?
    public let email: String?
    public let phone: String?
    public let isAdmin: Int?
    public let tenantId: Int64?
    public let authMode: String?
    public let expiresAt: Int64?

    public var isAdministrator: Bool { isAdmin == 1 }
}

/// `POST /api/admin/auth/refresh-token` payload (`TokenController.kt:56-62`).
///
/// No `expiresAt` on the wire — the client converts `expiresIn` against its own clock.
public struct RefreshedToken: Decodable, Equatable, Sendable {
    public let accessToken: String?
    public let tenantId: Int64?
    public let expiresIn: Int64?
}
