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
    /// Where the browser was sent. The URL carries a one-time `state`, so it is displayed and never stored
    /// (`McpOAuthUserServiceImpl.kt:153`, `McpOAuthStateStore.kt:77-82`); showing it is what lets an
    /// operator finish the dance on another device if the hand-off itself did not take.
    @Published public private(set) var authorizationURL: String?
    @Published public private(set) var isRequestingAuthorization = false
    /// The browser tab is open and the grant is not visible yet: this is the wait in between.
    @Published public private(set) var isAwaitingAuthorization = false
    /// What the wait has to say for itself — currently only that its window ran out.
    @Published public private(set) var oauthNotice: String?
    /// The server's own summary of a revoke, including whether the authorization server was told
    /// (`McpOAuthRevokeResponse.kt:13-21`).
    @Published public private(set) var revokeMessage: String?
    @Published public private(set) var isRevoking = false

    public let serverID: Int64

    /// Same local deadline as the list, and injectable for the same reason.
    public let testTimeout: TimeInterval

    /// How often the status is re-read while waiting, and how long that goes on for.
    private let pollInterval: TimeInterval
    private let pollReads: Int

    private let mcp: any McpCataloging
    private let authorizer: any McpAuthorizing
    /// Bumped by whoever wants the running wait to stop: a second hand-off, the manual check, the screen.
    private var waitGeneration = 0

    public init(
        mcp: any McpCataloging,
        authorizer: any McpAuthorizing,
        id: Int64,
        testTimeout: TimeInterval = 15,
        pollInterval: TimeInterval = 2,
        pollWindow: TimeInterval = 120
    ) {
        self.mcp = mcp
        self.authorizer = authorizer
        self.serverID = id
        self.testTimeout = testTimeout
        self.pollInterval = pollInterval
        self.pollReads = max(1, Int(pollWindow / pollInterval))
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

    /// Build the authorization request server-side, hand the URL to whoever can run a browser session, and
    /// then wait for the grant.
    ///
    /// Awaitable to the end on purpose: the caller's task becomes the wait, so the whole flow has one
    /// outcome a test can await rather than a loop racing whoever started it. The grant is stored by the
    /// browser's own return leg, so a status read taken right after the hand-off can only answer "not yet";
    /// a cancelled or refused hand-off left nothing that could change, so it re-reads once.
    public func startAuthorization() async {
        guard let url = await requestAuthorizeURL() else { return }
        authorizationURL = url.absoluteString
        switch await authorizer.presentAuthorizeURL(url, for: serverID) {
        case let .openedInBrowser(opened):
            authorizationURL = opened.absoluteString
            await awaitAuthorization()
        case .cancelled:
            revokeMessage = hx("mcp.oauth.cancelled")
            await loadOAuth()
        case let .refused(message):
            revokeMessage = message
            await loadOAuth()
        }
    }

    /// The one call that makes a hand-off possible. Returns nil, having already said why, when there is no
    /// URL to give up.
    private func requestAuthorizeURL() async -> URL? {
        isRequestingAuthorization = true
        defer { isRequestingAuthorization = false }
        switch await mcp.mcpAuthorizeURL(id: serverID, scope: nil) {
        case let .failure(error):
            oauthPhase = .failed(message: ErrorMessage.text(for: error))
            return nil
        case let .success(authorization):
            guard let url = authorization.url else {
                oauthPhase = .failed(message: hx("mcp.oauth.badUrl"))
                return nil
            }
            return url
        }
    }

    /// The bounded wait. The window is a count of re-reads rather than a clock, so the bound is the same
    /// number in a test as on a device.
    public func awaitAuthorization() async {
        waitGeneration += 1
        let generation = waitGeneration
        oauthNotice = nil
        isAwaitingAuthorization = true
        defer {
            if generation == waitGeneration { isAwaitingAuthorization = false }
        }
        for _ in 0 ..< pollReads {
            guard await sleptOneInterval(generation: generation) else { return }
            await loadOAuth()
            if isAuthorized { return }
        }
        if generation == waitGeneration { oauthNotice = hx("mcp.oauth.timedOut") }
    }

    /// One poll interval, dozed in short slices so `stopAwaitingAuthorization` does not have to wait out an
    /// interval that can be longer than the screen's remaining lifetime. False means somebody ended the
    /// wait while it was asleep.
    private func sleptOneInterval(generation: Int) async -> Bool {
        var remaining = pollInterval
        while remaining > 0 {
            let slice = min(remaining, 0.25)
            remaining -= slice
            try? await Task.sleep(nanoseconds: UInt64(slice * 1_000_000_000))
            if Task.isCancelled || generation != waitGeneration { return false }
        }
        return true
    }

    /// The manual way out of the wait, for a user who finished in the browser before the window closed and
    /// for one whose device never gave the URL up. A read that still says "no grant" needs no extra
    /// sentence: the status row already says that.
    public func confirmAuthorizationDone() async {
        stopAwaitingAuthorization()
        oauthNotice = nil
        await loadOAuth()
    }

    /// Ends the wait from the outside: the manual check, and the screen going away with a loop still
    /// asleep. `awaitAuthorization` clears the flag itself only if nobody restarted it meanwhile.
    public func stopAwaitingAuthorization() {
        waitGeneration += 1
        isAwaitingAuthorization = false
    }

    /// Offer the manual check once a browser has the URL and no grant is on record yet — including after
    /// the wait's window closed, and after a hand-off this device could not complete.
    public var canConfirmAuthorization: Bool {
        authorizationURL != nil && !isAuthorized
    }

    private var isAuthorized: Bool {
        if case let .loaded(status) = oauthPhase { return status.authorized }
        return false
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
        stopAwaitingAuthorization()
        oauthNotice = nil
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
