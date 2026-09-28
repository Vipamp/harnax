import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// The return leg happens in a browser the app cannot see, so everything left to this screen is the wait
/// for the status to change — bounded, interruptible, and with a manual way out.
///
/// `startAuthorization()` is awaitable to the end, which is what lets these assert on the final state and
/// on the exact number of status reads rather than on elapsed time.
@MainActor
final class McpAuthorizationWaitTests: XCTestCase {
    private let authorizeURL = URL(string: "https://example.com/oauth/authorize?state=one-time")!

    private func viewModel(
        _ statuses: [McpOAuthStatus],
        handoff: McpAuthorizationHandoff,
        pollInterval: TimeInterval = 0.005,
        pollWindow: TimeInterval
    ) throws -> (McpDetailViewModel, OAuthCatalog) {
        let catalog = OAuthCatalog(statuses: statuses, authorizeReply: .success(try authorization()))
        let vm = McpDetailViewModel(
            mcp: catalog,
            authorizer: FakeAuthorizer(handoff: handoff),
            id: 7,
            pollInterval: pollInterval,
            pollWindow: pollWindow
        )
        return (vm, catalog)
    }

    private func authorization() throws -> McpOAuthAuthorization {
        try JSONDecoder().decode(
            McpOAuthAuthorization.self,
            from: Data(
                #"{"authorizeUrl":"\#(authorizeURL.absoluteString)","issuer":"https://example.com","scopes":[],"expiresIn":600}"#
                    .utf8
            )
        )
    }

    /// The wait runs in the caller's own task, so a test that wants to interrupt it has to let that task
    /// reach the point of being interruptible first.
    private func waitUntilAwaiting(_ vm: McpDetailViewModel) async throws {
        for _ in 0 ..< 200 {
            if vm.isAwaitingAuthorization { return }
            try await Task.sleep(nanoseconds: 2_000_000)
        }
        XCTFail("the wait never started")
    }

    // MARK: - the wait

    func testAHandOffToTheBrowserWaitsForTheGrantInsteadOfReadingOnce() async throws {
        let (vm, catalog) = try viewModel(
            [.unauthorized, .unauthorized, .grant],
            handoff: .openedInBrowser(url: authorizeURL),
            pollWindow: 1
        )
        await vm.startAuthorization()

        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 3, "the wait re-reads until the grant shows up")
        XCTAssertTrue(vm.authorizedForTest)
        XCTAssertFalse(vm.isAwaitingAuthorization, "the wait ends on its own once the grant is there")
        XCTAssertNil(vm.oauthNotice)
    }

    func testTheWaitGivesUpAfterItsWindowAndSaysSo() async throws {
        let (vm, catalog) = try viewModel(
            [.unauthorized],
            handoff: .openedInBrowser(url: authorizeURL),
            pollWindow: 0.025
        )
        await vm.startAuthorization()

        // 0.025 over a 0.005 interval is the window: five re-reads and no more, however long the browser
        // takes.
        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 5)
        XCTAssertEqual(vm.oauthNotice, hx("mcp.oauth.timedOut"))
        XCTAssertFalse(vm.isAwaitingAuthorization)
    }

    func testAWindowShorterThanOneIntervalStillReadsOnce() async throws {
        let (vm, catalog) = try viewModel(
            [.grant],
            handoff: .openedInBrowser(url: authorizeURL),
            pollWindow: 0.001
        )
        await vm.startAuthorization()

        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 1, "a bounded window never becomes an empty one")
        XCTAssertNil(vm.oauthNotice)
    }

    func testACancelledHandOffReadsOnceAndStartsNoWait() async throws {
        let (vm, catalog) = try viewModel(
            [.unauthorized],
            handoff: .cancelled,
            pollWindow: 1
        )
        await vm.startAuthorization()

        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 1, "nothing was opened, so nothing can change")
        XCTAssertEqual(vm.revokeMessage, hx("mcp.oauth.cancelled"))
        XCTAssertNil(vm.oauthNotice)
        XCTAssertFalse(vm.isAwaitingAuthorization)
    }

    func testARefusedHandOffShowsTheReasonAndStartsNoWait() async throws {
        let (vm, catalog) = try viewModel(
            [.unauthorized],
            handoff: .refused(message: "no browser here"),
            pollWindow: 1
        )
        await vm.startAuthorization()

        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 1)
        XCTAssertEqual(vm.revokeMessage, "no browser here")
        XCTAssertNil(vm.oauthNotice)
    }

    func testAFailedAuthorizeRequestNeverReachesTheBrowser() async throws {
        let catalog = OAuthCatalog(statuses: [.unauthorized], authorizeReply: .failure(.offline))
        let vm = McpDetailViewModel(
            mcp: catalog,
            authorizer: FakeAuthorizer(handoff: .openedInBrowser(url: authorizeURL)),
            id: 7,
            pollWindow: 1
        )
        await vm.startAuthorization()

        XCTAssertEqual(vm.oauthPhase, .failed(message: ErrorMessage.text(for: .offline)))
        XCTAssertNil(vm.authorizationURL, "there was no URL to hand over")
        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 0)
    }

    // MARK: - the manual check

    func testTheManualCheckAnswersNowInsteadOfAtTheNextPoll() async throws {
        let (vm, catalog) = try viewModel(
            [.grant],
            handoff: .openedInBrowser(url: authorizeURL),
            pollInterval: 3600,
            pollWindow: 3600
        )
        let flow = Task { await vm.startAuthorization() }
        try await waitUntilAwaiting(vm)

        await vm.confirmAuthorizationDone()
        await flow.value

        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 1, "the manual read replaces the poll that was still asleep")
        XCTAssertTrue(vm.authorizedForTest)
        XCTAssertFalse(vm.isAwaitingAuthorization)
        XCTAssertNil(vm.oauthNotice)
    }

    func testTheManualCheckIsOfferedOnlyWhileTheBrowserOwesAnAnswer() async throws {
        let (settled, _) = try viewModel(
            [.unauthorized, .grant],
            handoff: .openedInBrowser(url: authorizeURL),
            pollWindow: 1
        )
        XCTAssertFalse(settled.canConfirmAuthorization, "before any hand-off there is nothing to check")
        await settled.startAuthorization()
        XCTAssertFalse(settled.canConfirmAuthorization, "the grant arrived, so the entry retires")

        let (pending, _) = try viewModel(
            [.unauthorized],
            handoff: .openedInBrowser(url: authorizeURL),
            pollWindow: 0.005
        )
        await pending.startAuthorization()
        XCTAssertTrue(pending.canConfirmAuthorization, "the window closed with no grant: check by hand")
    }

    func testLeavingTheScreenEndsTheWait() async throws {
        let (vm, catalog) = try viewModel(
            [.unauthorized],
            handoff: .openedInBrowser(url: authorizeURL),
            pollInterval: 3600,
            pollWindow: 3600
        )
        let flow = Task { await vm.startAuthorization() }
        try await waitUntilAwaiting(vm)

        vm.stopAwaitingAuthorization()
        await flow.value

        XCTAssertFalse(vm.isAwaitingAuthorization)
        let reads = await catalog.statusReads
        XCTAssertEqual(reads, 0, "the first poll was still asleep when the screen left")
        XCTAssertNil(vm.oauthNotice, "a wait the operator abandoned has nothing to report")
    }
}

// MARK: - fakes

private extension McpOAuthStatus {
    static let grant = McpOAuthStatus(authorized: true)
    static let unauthorized = McpOAuthStatus(authorized: false)
}

private extension McpDetailViewModel {
    /// The same reading the status badge makes, without the phase switch at every call site.
    var authorizedForTest: Bool {
        if case let .loaded(status) = oauthPhase { return status.authorized }
        return false
    }
}

/// Answers the status read from a script whose last entry repeats, and counts how often it was asked —
/// the count is the assertion, not the timing.
private actor OAuthCatalog: McpCataloging {
    private let statuses: [McpOAuthStatus]
    private let authorizeReply: Result<McpOAuthAuthorization, APIError>
    private(set) var statusReads = 0

    init(statuses: [McpOAuthStatus], authorizeReply: Result<McpOAuthAuthorization, APIError>) {
        self.statuses = statuses
        self.authorizeReply = authorizeReply
    }

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> {
        defer { statusReads += 1 }
        guard !statuses.isEmpty else { return .failure(.offline) }
        return .success(statuses[min(statusReads, statuses.count - 1)])
    }

    func mcpAuthorizeURL(id: Int64, scope: String?) async -> Result<McpOAuthAuthorization, APIError> {
        authorizeReply
    }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> {
        .failure(notThisTest())
    }

    // Everything else is a list, a form or a tool read; a screen reaching them in this test is a bug.
    private func notThisTest() -> APIError {
        .business(code: -1, message: "not this test")
    }

    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> { .failure(notThisTest()) }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> { .failure(notThisTest()) }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> {
        .failure(notThisTest())
    }

    func updateMCPServer(id: Int64, patch: McpServerPatch) async -> Result<EmptyResponse, APIError> {
        .failure(notThisTest())
    }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        .failure(notThisTest())
    }

    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(notThisTest()) }

    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(notThisTest()) }

    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> { .failure(notThisTest()) }

    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> { .failure(notThisTest()) }
}

private struct FakeAuthorizer: McpAuthorizing {
    let handoff: McpAuthorizationHandoff

    func presentAuthorizeURL(_ url: URL, for serverID: Int64) async -> McpAuthorizationHandoff {
        handoff
    }
}
