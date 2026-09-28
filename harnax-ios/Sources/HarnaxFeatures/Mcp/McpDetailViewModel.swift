import Foundation
import Combine
import HarnaxCore
import HarnaxKit

/// The detail screen's own three-way state, kept apart from the list's so a failed row read does not look
/// like a failed tool read.
@MainActor
public final class McpDetailViewModel: ObservableObject {
    public enum Phase: Equatable {
        case loading
        case content
        case failed(String)
    }

    /// Where the per-user authorization block is.
    ///
    /// Four states because the read itself can fail, and "we could not ask" is not the same news as
    /// "this user has no grant" — the second one offers an entry, the first offers a retry
    /// (`McpOAuthUserServiceImpl.kt:228-237`).
    public enum OAuthPhase: Equatable {
        case idle
        case loading
        case loaded(McpOAuthStatus)
        case failed(message: String)
    }

    @Published public private(set) var phase: Phase = .loading
    @Published public private(set) var server: McpServerRow?
    /// A refused write or a refresh that could not reach the stack, shown above a screen that stays put.
    @Published public private(set) var inlineError: String?
    @Published public private(set) var tools: McpToolState = .notEnabled
    @Published public private(set) var testOutcome: McpTestOutcome?
    @Published public private(set) var isTesting = false
    @Published public private(set) var isEnabled = false
    @Published public private(set) var oauthPhase: OAuthPhase = .idle
    /// The URL the browser hand-off was asked to open, shown so the operator can finish it by hand while
    /// the return leg is not wired up yet. It carries a one-time `state`, so it is displayed and never
    /// stored (`McpOAuthUserServiceImpl.kt:153`, `McpOAuthStateStore.kt:77-82`).
    @Published public private(set) var authorizationURL: String?
    @Published public private(set) var isRequestingAuthorization = false
    /// The server's own summary of a revoke, including whether the authorization server was told
    /// (`McpOAuthRevokeResponse.kt:13-21`).
    @Published public private(set) var revokeMessage: String?
    @Published public private(set) var isRevoking = false

    public let serverID: Int64

    /// Same local deadline as the list, and injectable for the same reason.
    public let testTimeout: TimeInterval

    private let mcp: any McpCataloging
    private let authorizer: any McpAuthorizing

    public init(mcp: any McpCataloging, authorizer: any McpAuthorizing, id: Int64, testTimeout: TimeInterval = 15) {
        self.mcp = mcp
        self.authorizer = authorizer
        self.serverID = id
        self.testTimeout = testTimeout
    }

    /// The OAuth block only exists for a row that authorizes per user
    /// (`harnax-webui/src/pages/mcp/detail.tsx:345-347`).
    public var showsOAuth: Bool { server?.requiresPerUserOAuth ?? false }
    public var showsStdioNotice: Bool { server?.transport.isStdio ?? false }
    public var title: String { server?.title ?? "" }

    /// One read of the row, then the two things that follow from it.
    public func load() async {
        inlineError = nil
        if server == nil { phase = .loading }
        switch await mcp.mcpServer(id: serverID) {
        case let .success(row):
            server = row
            isEnabled = row.isEnabled
            phase = .content
            await loadTools()
            if row.requiresPerUserOAuth { await loadOAuth() }
        case let .failure(error):
            let text = ErrorMessage.text(for: error)
            if server == nil {
                phase = .failed(text)
            } else {
                inlineError = text
            }
        }
    }

    /// Re-read the row after a write the stack refused, so the switch snaps back to what the server says
    /// instead of keeping an answer it never accepted.
    private func reconcile() async {
        if case let .success(row) = await mcp.mcpServer(id: serverID) {
            server = row
            isEnabled = row.isEnabled
        }
    }

    /// Ask for the tool surface, or say why nothing was asked.
    ///
    /// `list_tools` refuses a disabled row before it opens a connection
    /// (`McpServerServiceImpl.kt:441-447`), and the console only asks when `status == 1`
    /// (`harnax-webui/src/pages/mcp/detail.tsx:79-83`) — so an off row is its own state, not a failure.
    public func loadTools() async {
        guard let server else { return }
        guard isEnabled, server.isEnabled else {
            tools = .notEnabled
            return
        }
        tools = .loading
        switch await mcp.mcpTools(id: serverID) {
        case let .success(rows):
            tools = .loaded(tools: rows)
        case let .failure(error):
            // The refusal usually names a policy (stdio disabled, per-user OAuth), and that sentence is the
            // useful one; a generic "could not load" sends the operator to check the wrong thing.
            tools = .failed(message: ErrorMessage.text(for: error))
        }
    }

    public func runTest() async {
        isTesting = true
        testOutcome = nil
        defer { isTesting = false }
        let catalog = mcp
        let id = serverID
        testOutcome = await McpConnectivity.probe(timeout: testTimeout) {
            await catalog.testMcpConnectivity(id: id)
        }
    }

    public func dismissTest() {
        testOutcome = nil
    }

    /// The switch on the detail screen is the same write as the list's, and the tool list is the answer
    /// that changes with it.
    public func setEnabled(_ value: Bool) async {
        isEnabled = value
        testOutcome = nil
        if case let .failure(error) = await mcp.setMcpStatus(id: serverID, enabled: value) {
            inlineError = ErrorMessage.text(for: error)
            await reconcile()
        }
        await loadTools()
    }

    // MARK: - OAuth

    /// The one side-effect-free call of the block, and the state machine's starting point
    /// (`OAuthPanel.tsx:70-95`).
    public func loadOAuth() async {
        oauthPhase = .loading
        switch await mcp.mcpOAuthStatus(id: serverID) {
        case let .success(status):
            oauthPhase = .loaded(status)
        case let .failure(error):
            oauthPhase = .failed(message: ErrorMessage.text(for: error))
        }
    }

    /// The verdict the screen is allowed to show: one entry, one badge, and no claim about expiry.
    public var oauthBadge: String? {
        guard case let .loaded(status) = oauthPhase else { return nil }
        return McpOAuthPresentation.badge(status)
    }

    public var needsAuthorization: Bool {
        guard case let .loaded(status) = oauthPhase else { return false }
        return McpOAuthPresentation.needsAuthorization(status)
    }

    public var oauthActionKey: String {
        guard case let .loaded(status) = oauthPhase else { return "mcp.oauth.action.authorize" }
        return McpOAuthPresentation.actionKey(for: status)
    }

    /// The status read itself failed, so the block offers a retry rather than an action whose premise the
    /// screen does not know.
    public var oauthReadFailed: Bool {
        if case .failed = oauthPhase { return true }
        return false
    }

    /// Revoke only where a grant is on record: `authorized` is the single field the design ruling allows
    /// to drive a verdict, and an answer of "no" is what the 需要授权 entry is for.
    public var canRevoke: Bool {
        guard case let .loaded(status) = oauthPhase else { return false }
        return status.authorized
    }

    public var grantedScopes: [String] {
        guard case let .loaded(status) = oauthPhase else { return [] }
        return status.grantedScopes
    }

    public var oauthError: String? {
        guard case let .loaded(status) = oauthPhase else { return nil }
        return status.error
    }

    /// Build the authorization request server-side and hand the URL to whoever can run a browser session.
    ///
    /// The return leg is a later milestone, so the outcome this can report is only "the URL is in a
    /// browser": the grant is stored by the exchange, which needs the code coming back. The status is
    /// re-read when the URL actually reached a browser session, because a cancelled or refused hand-off
    /// left nothing that could have changed.
    public func startAuthorization() async {
        isRequestingAuthorization = true
        defer { isRequestingAuthorization = false }
        let request = await mcp.mcpAuthorizeURL(id: serverID, scope: nil)
        switch request {
        case let .failure(error):
            oauthPhase = .failed(message: ErrorMessage.text(for: error))
            return
        case let .success(authorization):
            guard let url = authorization.url else {
                oauthPhase = .failed(message: hx("mcp.oauth.badUrl"))
                return
            }
            authorizationURL = url.absoluteString
            switch await authorizer.presentAuthorizeURL(url, for: serverID) {
            case let .openedInBrowser(opened):
                authorizationURL = opened.absoluteString
            case .cancelled:
                revokeMessage = hx("mcp.oauth.cancelled")
            case let .refused(message):
                revokeMessage = message
            }
        }
        await loadOAuth()
    }

    public func clearAuthorizationURL() {
        authorizationURL = nil
    }

    /// Revoke, then re-read: `revoked` is true even when there was nothing to revoke
    /// (`McpOAuthUserServiceImpl.kt:253-257`), so the badge is only trustworthy after the status call —
    /// which is also the order the console uses (`OAuthPanel.tsx:117-148`).
    public func revoke() async {
        isRevoking = true
        defer { isRevoking = false }
        switch await mcp.revokeMcpOAuth(id: serverID) {
        case let .success(result):
            revokeMessage = result.explanation
        case let .failure(error):
            revokeMessage = ErrorMessage.text(for: error)
        }
        await loadOAuth()
    }

    public func dismissRevokeMessage() {
        revokeMessage = nil
    }
}
