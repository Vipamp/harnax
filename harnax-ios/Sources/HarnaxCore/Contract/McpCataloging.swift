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

    // MARK: - Per-user OAuth (status read, revoke, and the hand-off that starts authorization)
    //
    // Discovery, client registration and the code exchange are deliberately absent: the first two are
    // administrator setup the console gates behind a role, and the exchange needs the browser return leg,
    // which lands in its own milestone.

    /// This user's own grant. A user who never authorized reads `authorized = false` with no `status`
    /// (`McpOAuthUserServiceImpl.kt:228-232`).
    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError>
    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError>
    /// Builds the authorization request server-side (PKCE + state) and returns the URL. The state is only
    /// inside that URL and is spent by the exchange, so nothing here may cache or replay it.
    func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError>
}

/// What came of handing an authorize URL to whoever can run a browser session.
public enum McpAuthorizationHandoff: Equatable, Sendable {
    /// The URL reached a browser session. No grant is implied: the return leg is what stores one, and that
    /// is the `ASWebAuthenticationSession` milestone's job.
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
