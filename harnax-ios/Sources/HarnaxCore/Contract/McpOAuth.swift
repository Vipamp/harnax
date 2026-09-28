import Foundation

/// The non-sensitive OAuth block of one MCP server.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt:13-30`. All four
/// scope-ish fields are `List<String>` / `Boolean` with non-null defaults, so `scopes` and
/// `resourceIndicator` always arrive; `authorizationServer` and `audience` are nullable.
///
/// Client credentials have no field here on purpose — they live in `mcp_oauth_client` and are written
/// through `POST /{id}/oauth/client`, which this build does not call yet.
public struct McpOAuthConfigValues: Codable, Equatable, Sendable {
    public let authorizationServer: String?
    public let scopes: [String]
    public let audience: String?
    public let resourceIndicator: Bool

    public init(
        authorizationServer: String? = nil,
        scopes: [String] = [],
        audience: String? = nil,
        resourceIndicator: Bool = true
    ) {
        self.authorizationServer = authorizationServer
        self.scopes = scopes
        self.audience = audience
        self.resourceIndicator = resourceIndicator
    }

    /// `resourceIndicator` defaults to true server-side (RFC 8707 binding), and the console re-applies the
    /// same default when it backfills an edit form (`UpdateForm.tsx:31-49`).
    public static let defaults = McpOAuthConfigValues()

    public var issuer: String? { hxPresented(authorizationServer) }
    public var scopeList: [String] { scopes.filter { !$0.isEmpty } }
}

/// `McpOAuthStatusResponse` (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthStatusResponse.kt:13-33`).
///
/// `authorized` is the only field this app acts on. An expired access token reads as `authorized = false`
/// with `status` still `"ACTIVE"` (`McpOAuthUserServiceImpl.kt:233-237`), and a user who never authorized
/// reads as `authorized = false` with no row at all — the design ruling is to offer one 需要授权 entry from
/// `authorized` and never to claim "expired" (`harnax-ios/specs/04-context-domains.md` 的 OAuth 一节).
/// `status`, `lastRefreshedAt` and `lastError` are kept because they are already on the wire and the
/// detail screen can show them as facts without interpreting them.
public struct McpOAuthStatus: Decodable, Equatable, Sendable {
    public let authorized: Bool
    public let status: String?
    public let scopes: [String]
    public let accessExpiresAt: String?
    public let lastRefreshedAt: String?
    public let lastError: String?

    public init(authorized: Bool, status: String? = nil) {
        self.authorized = authorized
        self.status = status
        self.scopes = []
        self.accessExpiresAt = nil
        self.lastRefreshedAt = nil
        self.lastError = nil
    }

    public var grantedScopes: [String] { scopes.filter { !$0.isEmpty } }
    public var error: String? { hxPresented(lastError) }
}

/// `McpOAuthRevokeResponse` (`.../dto/McpOAuthRevokeResponse.kt:13-21`).
///
/// `revoked` is true even when the user had no grant at all (`McpOAuthUserServiceImpl.kt:253-257`), so the
/// only honest summary is the server's own `message` — which is also what says whether the authorization
/// server was told (`upstreamRevoked`).
public struct McpOAuthRevokeResult: Decodable, Equatable, Sendable {
    public let revoked: Bool
    public let upstreamRevoked: Bool
    public let message: String

    public var explanation: String? { hxPresented(message) }
}

/// `McpOAuthAuthorizeResponse` (`.../dto/McpOAuthAuthorizeResponse.kt:14-25`).
///
/// The one-time `state` is inside `authorizeUrl`, not in this body — which is why the caller hands the URL
/// to whoever can run a browser session and never parses a state itself.
public struct McpOAuthAuthorization: Decodable, Equatable, Sendable {
    public let authorizeUrl: String
    public let issuer: String
    public let scopes: [String]
    public let expiresIn: Int64

    public var url: URL? { URL(string: authorizeUrl) }
    public var requestedScopes: [String] { scopes.filter { !$0.isEmpty } }
}
