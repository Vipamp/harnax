import Foundation

/// The non-sensitive OAuth block of one MCP server.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/dto/McpOAuthConfig.kt:13-30`. All four
/// scope-ish fields are `List<String>` / `Boolean` with non-null defaults, so `scopes` and
/// `resourceIndicator` always arrive; `authorizationServer` and `audience` are nullable.
///
/// Client credentials have no field here on purpose — they live in `mcp_oauth_client` and are written
/// through `POST /{id}/oauth/client`, whose body is `McpOAuthClientDraft`.
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

    /// Where the authorization server will send the user back with the code.
    ///
    /// Read out of the request the server just built rather than from another call: it puts the stored
    /// registration's `callbackUrl` into `redirect_uri` verbatim
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/McpOAuthUserServiceImpl.kt:103-107`,
    /// `:152`) and presents the same string again at the token endpoint (`:688`), so this is the exact value a
    /// return leg has to be matched against — and an app that watches for it needs no discovery run first.
    public var redirectURL: String? {
        guard let components = URLComponents(string: authorizeUrl) else { return nil }
        return components.queryItems?.first { $0.name == "redirect_uri" }?.value.flatMap { hxPresented($0) }
    }
}

/// `McpOAuthDiscoveryResponse` (`.../dto/McpOAuthDiscoveryResponse.kt:11-50`) — what one discovery run found,
/// and what it wrote into `mcp_oauth_client` because of it (`McpOAuthController.kt:45`).
///
/// The same shape answers `POST /{id}/oauth/client`, so this type is both the discovery result and the
/// read-back of a saved registration. There is no client secret on it, ever: `clientSecretPresent` is the
/// only thing the server says about one.
public struct McpOAuthDiscovery: Decodable, Equatable, Sendable {
    public let issuer: String?
    /// How the issuer was located: `CONFIG`, `PROTECTED_RESOURCE` or `RESOURCE_METADATA`
    /// (`McpOAuthDiscoveryResponse.kt:19-23`). Kept as the raw column because the panel quotes it back.
    public let issuerSource: String?
    public let authorizationEndpoint: String?
    public let tokenEndpoint: String?
    /// Non-nil only where the AS advertises dynamic registration — which this stack does not implement, so
    /// a client always has to be filled in by hand.
    public let registrationEndpoint: String?
    /// Nil means a revoke clears the stored credential locally and nothing more (no RFC 7009 endpoint).
    public let revocationEndpoint: String?
    public let scopesSupported: [String]
    /// Scopes this server asks for that the AS did not list.
    public let unknownScopes: [String]
    /// Nil until a client is registered — discovery alone stores a row with an empty `client_id`, and
    /// `requireClientRegistration` refuses to send a user to the AS with one (`McpOAuthUserServiceImpl.kt:101-107`).
    public let clientId: String?
    public let clientSecretPresent: Bool
    /// The `redirect_uri` on file, which the AS compares as an exact string.
    public let callbackUrl: String?
    /// What this deployment would build today.
    public let defaultCallbackUrl: String?

    public init(
        issuer: String? = nil,
        clientId: String? = nil,
        clientSecretPresent: Bool = false,
        callbackUrl: String? = nil,
        defaultCallbackUrl: String? = nil,
        scopesSupported: [String] = [],
        unknownScopes: [String] = []
    ) {
        self.issuer = issuer
        self.issuerSource = nil
        self.authorizationEndpoint = nil
        self.tokenEndpoint = nil
        self.registrationEndpoint = nil
        self.revocationEndpoint = nil
        self.scopesSupported = scopesSupported
        self.unknownScopes = unknownScopes
        self.clientId = clientId
        self.clientSecretPresent = clientSecretPresent
        self.callbackUrl = callbackUrl
        self.defaultCallbackUrl = defaultCallbackUrl
    }

    public var knownIssuer: String? { hxPresented(issuer) }
    public var registeredClientID: String? { hxPresented(clientId) }
    /// Whether a user can be sent to the AS yet: a row without a `client_id` is discovery's placeholder, not
    /// a registration (`McpOAuthUserServiceImpl.kt:107-112`).
    public var isClientRegistered: Bool { registeredClientID != nil }
    public var hasRevocationEndpoint: Bool { hxPresented(revocationEndpoint) != nil }

    /// The stored registration predates a change of this deployment's address. The console warns rather
    /// than fixes it (`OAuthPanel.tsx:390-517`) because the AS will refuse the mismatch as an exact string,
    /// and only the operator can re-register the new one at the AS first.
    public var isCallbackStale: Bool {
        guard let stored = hxPresented(callbackUrl), let current = hxPresented(defaultCallbackUrl) else { return false }
        return stored != current
    }
}

/// The body of `POST /api/admin/mcp/{id}/oauth/client`
/// (`.../dto/McpOAuthClientRequest.kt:14-31`).
///
/// The secret is a tri-state on purpose and the three cases must not collapse into one another:
/// `nil` keeps what is stored, any blank value clears it — which makes this a public client relying on
/// PKCE alone (`SecretFieldEncryptor.kt:94-104`) — and anything else replaces it. A mask is never sent: the
/// server keeps the stored value when it sees one, so echoing it back would be a no-op dressed up as an edit.
public struct McpOAuthClientDraft: Encodable, Equatable, Sendable {
    /// The server's own bound (`clientId` is `@NotBlank` `@Size(max = 255)`); an over-long entry is refused
    /// locally with the same number rather than turned into a validation error round-trip. Jakarta counts
    /// `String.length()`, which is UTF-16 units, so that is what is measured here too.
    public static let clientIDMaxLength = 255
    /// `callbackUrl` is `@Size(max = 500)` and then goes through `validateHttpUrl`, which demands the http(s)
    /// scheme and a host (`McpOAuthServiceImpl.kt:103-109`, `:433-448`).
    public static let callbackURLMaxLength = 500

    public let clientId: String
    public let clientSecret: String?
    public let callbackUrl: String?

    public init(clientId: String, clientSecret: String? = nil, callbackUrl: String? = nil) {
        self.clientId = clientId
        self.clientSecret = clientSecret
        self.callbackUrl = callbackUrl
    }

    /// Absent keys mean "unchanged" on this endpoint (`McpOAuthClientRequest.kt:22-31`), so the unset halves
    /// are dropped from the JSON rather than sent as nulls — an explicit null is a different statement than
    /// an omitted key, and Jackson reads the two the same way here.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(clientId, forKey: .clientId)
        try container.encodeIfPresent(clientSecret, forKey: .clientSecret)
        try container.encodeIfPresent(callbackUrl, forKey: .callbackUrl)
    }

    private enum CodingKeys: String, CodingKey {
        case clientId
        case clientSecret
        case callbackUrl
    }

    /// `@NotBlank` on the trimmed value: the service trims before it checks again, so whitespace-only is empty
    /// here as well as there.
    public static func isClientIDUsable(_ raw: String) -> Bool {
        !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && raw.utf16.count <= clientIDMaxLength
    }

    /// Left blank, this field says "keep what is registered", so blank is a legal edit. Otherwise the value
    /// must be one the AS can be pointed at: http(s), a host, and within the same two bounds the server
    /// applies (`URL_MAX_LEN` is 500 there too).
    public static func isCallbackURLUsable(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return true }
        guard trimmed.utf16.count <= callbackURLMaxLength,
              let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty
        else { return false }
        return true
    }
}

/// The body of `POST /api/admin/mcp/oauth/exchange` (`.../dto/McpOAuthExchangeRequest.kt:20-35`) — what the
/// authorization server sent the browser back with.
///
/// All four fields are optional because the AS picks which to send: a consent that went through carries
/// `code` and `state`, a refusal carries `error` and often `error_description`. There is deliberately no MCP
/// id: the pending request the state names says which server this consent was for, and taking an id here
/// would let a captured state be pointed at a server of the caller's choosing
/// (`McpOAuthController.kt:96-100`).
public struct McpOAuthExchangeDraft: Encodable, Equatable, Sendable {
    public let code: String?
    public let state: String?
    public let error: String?
    public let errorDescription: String?

    public init(
        code: String? = nil,
        state: String? = nil,
        error: String? = nil,
        errorDescription: String? = nil
    ) {
        self.code = code
        self.state = state
        self.error = error
        self.errorDescription = errorDescription
    }

    /// A consent the user refused still has to be reported, and the endpoint accepts it: the answer is then
    /// `authorized = false` rather than a transport failure (`McpOAuthController.kt:101-102`).
    public var isRefusal: Bool { hxPresented(error) != nil }
    public var isEmpty: Bool { code == nil && state == nil && error == nil }
}

/// The return leg: recognising the redirect that carries the code, and reading what it carries.
///
/// The web does this in a page (`harnax-webui/src/pages/mcp/oauth-callback.tsx:35-44`). iOS cannot use a
/// custom scheme of its own: `POST /{id}/oauth/client` runs the `callbackUrl` through `validateHttpUrl`, which
/// rejects every scheme but http(s) and demands a host (`McpOAuthServiceImpl.kt:433-448`), so `harnax://…` is
/// unregistrable and the redirect on file is the console's own page. What iOS does instead is watch for that
/// page inside its own authorization session and take the code from the navigation before the page loads —
/// which needs no backend change and no rewrite of a registration the whole tenant shares.
///
/// Two rules carried over from the web page verbatim: the four keys come from the *query* only, and nothing
/// here persists a code or a state, because a spent state that survives on disk is one somebody could replay
/// (`oauth-callback.tsx:19-28`).
public enum McpOAuthCallback {
    /// Whether a navigation is this registration's own return, rather than some page the user browsed to.
    ///
    /// Compared location-by-location with the query dropped, because the AS appends `code`, `state` and
    /// sometimes a `session_state` to the registered value, and because the registration may or may not carry
    /// a trailing slash where the AS redirects with one. Scheme, host, port and path are all compared
    /// case-insensitively: case is the only difference this accepts, and since the host and port still have to
    /// match, a location differing only in case cannot hand the code to a different place.
    public static func matches(_ url: URL, redirect: String?) -> Bool {
        guard let needle = hxPresented(redirect), let left = location(url), let right = locationString(needle) else {
            return false
        }
        return left.caseInsensitiveCompare(right) == .orderedSame
    }

    /// The four keys the authorization server picks from, read out of the query.
    ///
    /// Returns nil for a URL that carries neither a code nor an error — a stray navigation is not a return
    /// leg, and handing an empty body to the exchange endpoint would spend a state the server never had. The
    /// endpoint accepts an all-optional body (`McpOAuthExchangeRequest`), so that check cannot be the server's.
    public static func draft(from url: URL) -> McpOAuthExchangeDraft? {
        guard let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        func value(_ key: String) -> String? {
            items.first { $0.name == key }?.value.flatMap { hxPresented($0) }
        }
        let code = value("code")
        let error = value("error")
        guard code != nil || error != nil else { return nil }
        return McpOAuthExchangeDraft(
            code: code,
            state: value("state"),
            error: error,
            errorDescription: value("error_description")
        )
    }

    /// The registered address with its query and fragment dropped, or nil when it is not a URL at all.
    private static func locationString(_ raw: String) -> String? {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        return location(url)
    }

    private static func location(_ url: URL) -> String? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = components.scheme,
              let host = components.host, !host.isEmpty
        else { return nil }
        var path = components.percentEncodedPath
        if path.hasSuffix("/") { path = String(path.dropLast()) }
        let port = components.port.map { ":\($0)" } ?? ""
        return "\(scheme)://\(host)\(port)\(path)"
    }
}

/// `McpOAuthExchangeResponse` (`.../dto/McpOAuthExchangeResponse.kt:20-31`) — what to tell the user.
///
/// `authorized` is false for every outcome where no grant was stored, *including* a refusal by the
/// authorization server, which still arrives as a successful answer with a sentence to show. So a caller
/// must read this field and never infer the outcome from the transport status. No field here can hold a
/// token — the browser is not supposed to see the exchange result at all.
public struct McpOAuthExchangeOutcome: Decodable, Equatable, Sendable {
    public let authorized: Bool
    public let message: String
    public let scopes: [String]
    public let accessExpiresAt: String?

    public var explanation: String? { hxPresented(message) }
    public var grantedScopes: [String] { scopes.filter { !$0.isEmpty } }
}
