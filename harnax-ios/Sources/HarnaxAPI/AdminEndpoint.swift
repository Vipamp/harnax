import Foundation
import HarnaxCore

/// The admin endpoints M0 touches. These are the same routes the web console uses — iOS adds no
/// mobile-only path, since `/api/admin/mp/**` is on its way out.
public enum AdminEndpoint {
    public static let cliLoginPath = "/api/admin/auth/cli-login"
    public static let profilePath = "/api/admin/auth/me"

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
}
