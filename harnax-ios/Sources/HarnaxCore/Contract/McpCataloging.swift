import Foundation

/// Everything the MCP screens do, read and write.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/McpServerController.kt:32-175`
/// and `.../controller/McpOAuthController.kt:45-135`, both under `/api/admin/mcp`. The method names carry
/// the domain because one `AdminClient` conforms to this and to `AgentCataloging`/`TeamCataloging`, and
/// identical signatures with a different return type could not sit on one type.
///
/// Four of these refuse for reasons that are *policy*, not network failure, and all four say so the same
/// way: `code != 200` with the sentence in `message`. Creation and the switch into stdio are refused while
/// `harnax.mcp.stdio-enabled` is off (`McpServerServiceImpl.kt:352-361`), and both `list_tools` and
/// `connectivity-test` go through the same gate plus two more: a disabled row and an OAUTH2 row are both
/// refused there (`McpServerServiceImpl.kt:441-464`). Callers therefore show the message and do not
/// translate it into "network error".
public protocol McpCataloging: Sendable {
    /// `keyword` matches name and description, `status` is the raw 0/1 column, `type` one of
    /// `stdio`/`sse`/`streamablehttp`; all three are optional (`McpServerController.kt:32-57`).
    func mcpPage(keyword: String?, status: Int?, type: String?, num: Int, size: Int) async -> Result<Page<McpServerRow>, APIError>
    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError>
    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError>
    /// Absent key means unchanged (`McpServerServiceImpl.kt:175-252`), so the patch carries only edits.
    func updateMCPServer(id: Int64, patch: McpServerPatch) async -> Result<EmptyResponse, APIError>
    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    /// Who would lose this server. The delete itself is not gated on it — the backend cascades the
    /// bindings away (`McpServerServiceImpl.kt:320-337`) — so this read exists to tell the operator which
    /// agents are about to change, and a failure here must not be read as "nothing is bound".
    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError>
    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError>
    /// True only when the server answered success; a refusal arrives as a failure whose message is the
    /// reason. There is no tool count or latency on this endpoint.
    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError>
    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError>

    // MARK: - OAuth setup (discovery and the client registration)
    //
    // Neither is gated on a role, here or in the console: single-row access goes through
    // `getVisibleMcpServer`, which checks tenant and visibility and not ownership
    // (`McpOAuthServiceImpl.kt:141-155`). Both do reach out to the network and write, though — discovery
    // stores metadata and an issuer back onto the row — so they are operator-triggered actions and never run
    // on load, which is the one rule the panel's shape has to keep.

    /// `POST /{id}/oauth/discover` (`McpOAuthController.kt:45`). The answer is also the read-back of whatever
    /// the run wrote, so a client that was already on file comes back in the same response. Refused for a row
    /// that is not OAUTH2 and for one with no url (`McpOAuthServiceImpl.kt:146-154`).
    func discoverMcpOAuth(id: Int64) async -> Result<McpOAuthDiscovery, APIError>
    /// `POST /{id}/oauth/client` (`:59`). Answers the same discovery shape, with the secret reduced to
    /// `clientSecretPresent`. Refused before anything is written when the issuer is still unknown
    /// (`McpOAuthServiceImpl.kt:82-88`) — which is why the panel disables it until a discovery run names one.
    func saveOAuthClient(id: Int64, _ draft: McpOAuthClientDraft) async -> Result<McpOAuthDiscovery, APIError>

    // MARK: - Per-user OAuth (status read, revoke, the hand-off that starts authorization, the return leg)

    /// This user's own grant. A user who never authorized reads `authorized = false` with no `status`
    /// (`McpOAuthUserServiceImpl.kt:228-232`).
    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError>
    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError>
    /// Builds the authorization request server-side (PKCE + state) and returns the URL. The state is only
    /// inside that URL and is spent by the exchange, so nothing here may cache or replay it.
    func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError>
    /// `POST /oauth/exchange` (`:90`), the leg that stores the grant.
    ///
    /// Two facts a caller has to respect. It carries no MCP id — the pending request the state names decides
    /// which server this consent was for (`McpOAuthController.kt:96-100`) — and a refusal (unknown state,
    /// another session's state, the AS said no) arrives as a *successful* answer with `authorized = false`
    /// (`:101-102`), so the outcome is read from that field and never from the transport status.
    func exchangeOAuthCode(_ draft: McpOAuthExchangeDraft) async -> Result<McpOAuthExchangeOutcome, APIError>
}

/// What came of handing an authorize URL to whoever can run a browser session.
public enum McpAuthorizationHandoff: Equatable, Sendable {
    /// The URL reached a browser session. No grant is implied.
    ///
    /// There are two live return legs and this is the observed one: the console's own callback page receives
    /// the code and admin stores the grant, so nothing here can see it happen and the caller re-reads the
    /// status instead (`McpBrowserAuthorizer.swift:10-13`, `McpDetailViewModel.swift:265-267`). The other is
    /// the in-app `WKWebView`, which takes the code out of the navigation before that page loads and spends
    /// it from this device (`McpDetailViewModel.swift:491-499`).
    case openedInBrowser(url: URL)
    /// The operator closed the session before it finished. Not a network failure, so nothing retries — the
    /// state is spent either way (`McpOAuthUserServiceImpl.kt:170-174`).
    case cancelled
    /// The session could not be started, with the sentence to show.
    case refused(message: String)
}

/// The seam the detail screen's authorize button calls.
///
/// Implementations must treat the URL as sensitive material — it carries a one-time state — and must not
/// persist it. Reaching a browser is not a grant: the return leg stores one server-side, so the caller
/// re-reads the status afterwards rather than taking this return value as approval.
public protocol McpAuthorizing: Sendable {
    func presentAuthorizeURL(_ url: URL, for serverID: Int64) async -> McpAuthorizationHandoff
}
