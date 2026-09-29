import Foundation
import HarnaxCore

/// The admin auth endpoints iOS touches. These are the same routes the web console uses — iOS adds no
/// mobile-only path, since `/api/admin/mp/**` is on its way out.
public enum AdminEndpoint {
    public static let cliLoginPath = "/api/admin/auth/cli-login"
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
