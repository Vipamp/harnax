import Foundation
import HarnaxCore

/// The admin auth endpoints iOS touches. These are the same routes the web console uses — iOS adds no
/// mobile-only path, since `/api/admin/mp/**` is on its way out.
public enum AdminEndpoint {
    public static let cliLoginPath = "/api/admin/auth/cli-login"
    public static let logoutPath = "/api/admin/auth/logout"
    public static let profilePath = "/api/admin/auth/me"
    public static let tenantsPath = "/api/admin/auth/tenants"
    public static let switchTenantPath = "/api/admin/auth/switch-tenant"

    /// `authenticated: false` keeps a wrong password from being treated as an expired session.
    public static func cliLogin(_ request: LoginRequest) throws -> Endpoint {
        Endpoint(
            .post,
            path: cliLoginPath,
            body: try APIClient.encodeBody(request),
            authenticated: false
        )
    }

    /// `POST /api/admin/auth/logout`, the route the web console signs out with
    /// (`harnax-webui/src/services/ant-design-pro/login.ts:16-21`). It takes no body: the server blacklists
    /// the bearer it reads off the `Authorization` header (`AuthController.kt:52-58`,
    /// `AuthServiceImpl.kt:264-277`), so the token still has to ride along even though
    /// `SecurityConfig.kt:33-35` permits the path without one. `mayRefresh` is off because a 401 here is the
    /// server saying that token was already worthless — refreshing it, or replaying the revoke with a fresher
    /// one, would leave the session the user ended alive on the server.
    public static var logout: Endpoint {
        Endpoint(.post, path: logoutPath, mayRefresh: false)
    }

    public static var profile: Endpoint {
        Endpoint(.get, path: profilePath)
    }

    public static var tenants: Endpoint {
        Endpoint(.get, path: tenantsPath)
    }

    public static func switchTenant(_ request: SwitchTenantRequest) throws -> Endpoint {
        Endpoint(.post, path: switchTenantPath, body: try APIClient.encodeBody(request))
    }
}
