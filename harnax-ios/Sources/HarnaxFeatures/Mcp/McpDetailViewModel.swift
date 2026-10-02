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

    /// Where the setup block is: the discovery run and the client registration.
    ///
    /// Its own phase because it answers a different question from the grant — "what does the authorization
    /// server look like and what is registered against it" — and one run can succeed while the user's own
    /// status read fails.
    public enum OAuthSetupPhase: Equatable {
        case idle
        case running
        case loaded(McpOAuthDiscovery)
        case failed(message: String)
    }

    /// The authorization session the screen has to present, and the address to watch for inside it.
    public struct InAppTarget: Equatable {
        public let url: URL
        public let redirect: String
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
    @Published public private(set) var setupPhase: OAuthSetupPhase = .idle
    /// Where the browser was sent. The URL carries a one-time `state`, so it is displayed and never stored
    /// (`McpOAuthUserServiceImpl.kt:153`, `McpOAuthStateStore.kt:77-82`); showing it is what lets an
    /// operator finish the dance on another device if the hand-off itself did not take.
    @Published public private(set) var authorizationURL: String?
    @Published public private(set) var isRequestingAuthorization = false
    /// The browser tab is open and the grant is not visible yet: this is the wait in between.
    @Published public private(set) var isAwaitingAuthorization = false
    /// What the OAuth block has to say for itself: that the wait's window ran out, or the one-line outcome
    /// the exchange endpoint answered with.
    @Published public private(set) var oauthNotice: String?
    /// The code came back inside the app and is being spent now.
    @Published public private(set) var isExchanging = false
    /// Set only while the in-app session should be on screen; the sheet's presence *is* this value.
    @Published public private(set) var inAppTarget: InAppTarget?
    /// The server's own summary of a revoke, including whether the authorization server was told
    /// (`McpOAuthRevokeResponse.kt:13-21`).
    @Published public private(set) var revokeMessage: String?
    @Published public private(set) var isRevoking = false

    // The client registration form. Three fields because that is the whole body the endpoint takes, and
    // blank means "unchanged" for two of them — which is why the editor starts empty rather than echoing the
    // stored callback back into the field (only the `clientId` is ever readable on the wire).
    @Published public private(set) var showsClientEditor = false
    @Published public var clientID = ""
    @Published public var clientSecret = ""
    @Published public var clientCallbackURL = ""
    @Published public private(set) var clearsClientSecret = false
    @Published public private(set) var isSavingClient = false

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
        guard let authorization = await requestAuthorizeURL(), let url = authorization.url else { return }
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

    /// The one call that makes either hand-off possible. Returns nil, having already said why, when there is
    /// no request to give up.
    private func requestAuthorizeURL() async -> McpOAuthAuthorization? {
        isRequestingAuthorization = true
        defer { isRequestingAuthorization = false }
        switch await mcp.mcpAuthorizeURL(id: serverID, scope: nil) {
        case let .failure(error):
            oauthPhase = .failed(message: ErrorMessage.text(for: error))
            return nil
        case let .success(authorization):
            guard authorization.url != nil else {
                oauthPhase = .failed(message: hx("mcp.oauth.badUrl"))
                return nil
            }
            return authorization
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

    // MARK: - OAuth setup (discovery and the client registration)

    /// Find the authorization server behind this row and store what it advertises.
    ///
    /// Deliberately never run on load: the endpoint reaches out to the network and writes, and the answer it
    /// gives is the read-back of what it wrote (`McpOAuthServiceImpl.kt:62-76`).
    public func discover() async {
        guard setupPhase != .running else { return }
        // Read before the phase is overwritten: asking the phase afterwards can no longer tell whether this
        // screen ever had an answer to keep.
        var previous: McpOAuthDiscovery?
        if case let .loaded(discovery) = setupPhase { previous = discovery }
        setupPhase = .running
        switch await mcp.discoverMcpOAuth(id: serverID) {
        case let .success(discovery):
            setupPhase = .loaded(discovery)
        case let .failure(error):
            let text = ErrorMessage.text(for: error)
            // A failed run leaves the last one's answer on screen and says what went wrong above it.
            // Clearing the block instead would turn "the network refused" into "this server has no
            // registration", which is a claim the answer does not support.
            if let previous {
                setupPhase = .loaded(previous)
                inlineError = text
            } else {
                setupPhase = .failed(message: text)
            }
        }
    }

    public var discovery: McpOAuthDiscovery? {
        if case let .loaded(discovery) = setupPhase { return discovery }
        return nil
    }

    /// Which issuer a registration would be stored against. A row that already names one counts as known
    /// without a discovery run, which is the same test the console makes (`OAuthPanel.tsx:73`).
    public var issuerForClient: String? {
        discovery?.knownIssuer ?? server?.oauthConfig?.issuer
    }

    public var canDiscover: Bool { setupPhase != .running }
    public var canRegisterClient: Bool { issuerForClient != nil }

    public func openClientEditor() {
        clientID = discovery?.registeredClientID ?? ""
        clientSecret = ""
        clientCallbackURL = ""
        clearsClientSecret = false
        showsClientEditor = true
    }

    public func closeClientEditor() {
        showsClientEditor = false
    }

    /// The switch also drops whatever was typed, on the same tick the console clears it on
    /// (`OAuthPanel.tsx:573-578`): pasting a secret and then ticking "delete" would otherwise submit a clear
    /// while the operator believes they replaced it.
    public func setClearsClientSecret(_ value: Bool) {
        clearsClientSecret = value
        if value { clientSecret = "" }
    }

    /// The three fields as the endpoint wants them. Values are trimmed, and a blank half is left off the body
    /// so it reads as "unchanged" — except the secret, where an explicit blank is the only way to say
    /// "clear it" (`SecretFieldEncryptor.kt:94-104`).
    public var clientDraft: McpOAuthClientDraft? {
        guard McpOAuthClientDraft.isClientIDUsable(clientID),
              McpOAuthClientDraft.isCallbackURLUsable(clientCallbackURL)
        else { return nil }
        let secret = clientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        let callback = clientCallbackURL.trimmingCharacters(in: .whitespacesAndNewlines)
        var clientSecret: String?
        if clearsClientSecret {
            clientSecret = ""
        } else if !secret.isEmpty {
            clientSecret = secret
        }
        return McpOAuthClientDraft(
            clientId: clientID.trimmingCharacters(in: .whitespacesAndNewlines),
            clientSecret: clientSecret,
            callbackUrl: callback.isEmpty ? nil : callback
        )
    }

    public var canSaveClient: Bool { !isSavingClient && clientDraft != nil }

    /// Register the client, then take the answer as the new read-back — that response is where a caller
    /// learns whether a secret is now stored, and there is no separate read for it.
    public func saveClient() async {
        guard !isSavingClient, let draft = clientDraft else { return }
        isSavingClient = true
        defer { isSavingClient = false }
        switch await mcp.saveOAuthClient(id: serverID, draft) {
        case let .success(discovery):
            setupPhase = .loaded(discovery)
            showsClientEditor = false
        case let .failure(error):
            inlineError = ErrorMessage.text(for: error)
        }
    }

    // MARK: - The return leg, taken inside the app

    /// Start the authorization in a session this app can watch, so the code is spent by this device instead of
    /// by the console's callback page.
    ///
    /// One call gives back both halves: the request to load and the address to watch for, which is the
    /// registration's own `callbackUrl` (`McpOAuthUserServiceImpl.kt:103-107`, `:152`). Without a redirect
    /// there is nothing that could ever be recognised, so this says so instead of opening a session that
    /// cannot finish.
    public func startInAppAuthorization() async {
        guard let authorization = await requestAuthorizeURL() else { return }
        guard let url = authorization.url, let redirect = authorization.redirectURL else {
            oauthNotice = hx("mcp.oauth.noRedirect")
            return
        }
        oauthNotice = nil
        inAppTarget = InAppTarget(url: url, redirect: redirect)
    }

    /// The operator closed the session before it reached the redirect. The state is simply never spent, and
    /// nothing retries — the server's pending entry ages out on its own.
    ///
    /// Only while a session is actually open: the sheet's dismissal path calls this for every closing
    /// gesture, and the one that follows a completed exchange must not stamp "cancelled" over the sentence
    /// the exchange endpoint answered with.
    public func cancelInAppAuthorization() {
        guard inAppTarget != nil else { return }
        inAppTarget = nil
        oauthNotice = hx("mcp.oauth.cancelled")
    }

    /// Spend the code the session caught.
    ///
    /// The verdict comes from `authorized`, never from the transport: a refusal by the authorization server
    /// arrives as a successful answer saying no (`McpOAuthController.kt:101-102`), and the sentence it carries
    /// is the one worth showing. The status is re-read afterwards because a stored grant is now server-side
    /// state the badge has to catch up with.
    public func finishInAppAuthorization(with draft: McpOAuthExchangeDraft) async {
        inAppTarget = nil
        isExchanging = true
        defer { isExchanging = false }
        stopAwaitingAuthorization()
        switch await mcp.exchangeOAuthCode(draft) {
        case let .success(outcome):
            oauthNotice = outcome.explanation
        case let .failure(error):
            oauthNotice = ErrorMessage.text(for: error)
        }
        await loadOAuth()
    }
}
