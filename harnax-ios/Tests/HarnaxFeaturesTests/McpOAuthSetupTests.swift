import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The two operator steps a per-user grant depends on — a discovery run and a client registration — and the
/// return leg this app can take for itself instead of handing the browser to the console's callback page.
///
/// Three rules hold the whole domain together and each is asserted here rather than left to the layout. The
/// two setup steps reach out to the network *and write* (`McpOAuthServiceImpl.kt:62-76`), so nothing may run
/// them on load; a registration cannot be stored against an issuer nobody has named yet (`:82-88`), which is
/// why the entry is gated rather than validated after the fact; and the client secret is a tri-state
/// (`SecretFieldEncryptor.kt:94-104`), so an untouched field and a deliberately cleared one must stay two
/// different bodies on the wire.
@MainActor
final class McpOAuthSetupTests: XCTestCase {
    private let issuer = "https://auth.example.com/realms/harnax"
    private let redirect = "http://127.0.0.1:28081/mcp/oauth/callback"

    // MARK: - discovery

    /// The one rule the panel's shape has to keep: discovery is operator-triggered and never part of a load,
    /// because the run writes metadata and an issuer back onto the row.
    func testLoadingAnOAuthRowRunsNoDiscovery() async throws {
        let mcp = FakeMcpServers()
        mcp.detailReplies = [.success(try oauthRow())]
        let vm = viewModel(mcp)

        await vm.load()

        XCTAssertEqual(vm.phase, .content, "the row itself arrived")
        XCTAssertTrue(mcp.oauthDiscoverRequests.isEmpty, "a read screen cannot be allowed to write")
        XCTAssertEqual(vm.setupPhase, .idle, "no run, nothing to report about it")
    }

    func testADiscoveryRunBecomesTheReadBack() async throws {
        let mcp = FakeMcpServers()
        let found = McpOAuthDiscovery(issuer: issuer, clientId: "harnax-crm-client-7f3a", clientSecretPresent: true)
        mcp.oauthDiscoverReplies = [.success(found)]
        let vm = viewModel(mcp)

        await vm.discover()

        XCTAssertEqual(mcp.oauthDiscoverRequests, [7], "the run is addressed to this row")
        XCTAssertEqual(vm.setupPhase, .loaded(found))
        XCTAssertEqual(vm.discovery, found)
        XCTAssertTrue(vm.canDiscover, "a finished run leaves the retry offered")
    }

    /// Each run reaches out to the authorization server *and* writes what it found back onto the row, so two
    /// of them for one tap is two documents fetched and two writes for a decision the operator made once.
    func testTheDiscoveryRunIsPostedOnce() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthDiscoverReplies = [
            .success(McpOAuthDiscovery(issuer: issuer)),
            .success(McpOAuthDiscovery(issuer: issuer, clientId: "a-second-client")),
        ]
        let vm = viewModel(mcp)

        mcp.gateWrites = true
        let first = Task { await vm.discover() }
        try await waitUntil { mcp.oauthDiscoverRequests.count == 1 }
        let second = Task { await vm.discover() }
        try await settle()
        XCTAssertEqual(mcp.oauthDiscoverRequests.count, 1, "a second tap cannot run discovery twice")

        mcp.releaseWrites()
        await first.value
        await second.value

        XCTAssertEqual(mcp.oauthDiscoverRequests.count, 1, "one tap, one run")
        XCTAssertEqual(vm.setupPhase, .loaded(McpOAuthDiscovery(issuer: issuer)))
    }

    /// The authorization server being unreachable is not the same news as this row having no registration, so
    /// a failed run keeps the previous read-back on screen and reports above it.
    func testAFailedRunKeepsTheLastAnswerAndSaysWhatWentWrong() async throws {
        let mcp = FakeMcpServers()
        let found = McpOAuthDiscovery(issuer: issuer, clientId: "harnax-crm-client-7f3a")
        mcp.oauthDiscoverReplies = [
            .success(found),
            .failure(.offline),
        ]
        let vm = viewModel(mcp)
        await vm.discover()

        await vm.discover()

        XCTAssertEqual(vm.setupPhase, .loaded(found), "the old answer survives the failed run")
        XCTAssertEqual(vm.inlineError, ErrorMessage.text(for: .offline))
        XCTAssertTrue(vm.canDiscover)
    }

    /// With nothing to keep, the block does have to say it never got an answer — and that sentence is the
    /// server's own, since a refusal here is policy (`McpOAuthServiceImpl.kt:146-154`).
    func testAFailureBeforeAnyAnswerFailsTheBlock() async throws {
        let mcp = FakeMcpServers()
        let refusal = APIError.business(code: -1, message: "This server has no URL to discover from")
        mcp.oauthDiscoverReplies = [.failure(refusal)]
        let vm = viewModel(mcp)

        await vm.discover()

        XCTAssertEqual(vm.setupPhase, .failed(message: "This server has no URL to discover from"))
        XCTAssertNil(vm.discovery)
        XCTAssertNil(vm.inlineError, "the block says it itself; there is no old answer to write over")
        XCTAssertTrue(vm.canDiscover, "a refused run is retryable")
    }

    // MARK: - the issuer gate

    /// A row that already names an authorization server counts as known without a discovery run, which is the
    /// same test the console makes (`OAuthPanel.tsx:73`).
    func testAConfiguredIssuerOpensRegistrationWithoutADiscoveryRun() async throws {
        let mcp = FakeMcpServers()
        mcp.detailReplies = [.success(try oauthRow(authorizationServer: issuer))]
        let vm = viewModel(mcp)
        await vm.load()

        XCTAssertEqual(vm.issuerForClient, issuer)
        XCTAssertTrue(vm.canRegisterClient)
    }

    /// The endpoint refuses before it writes anything when the issuer is unknown
    /// (`McpOAuthServiceImpl.kt:82-88`), so the entry is closed rather than left to fail as a round trip.
    func testAnUnknownIssuerHoldsRegistrationShut() async throws {
        let mcp = FakeMcpServers()
        mcp.detailReplies = [.success(try oauthRow())]
        let vm = viewModel(mcp)
        await vm.load()

        XCTAssertEqual(vm.setupPhase, .idle)
        XCTAssertNil(vm.issuerForClient)
        XCTAssertFalse(vm.canRegisterClient)
    }

    /// Discovery writes the issuer it found back onto the row, so its answer is the fresher of the two and
    /// wins even against a row that names another authority.
    func testWhatDiscoveryFoundBeatsWhatTheRowNamed() async throws {
        let mcp = FakeMcpServers()
        mcp.detailReplies = [.success(try oauthRow(authorizationServer: "https://old.example.com"))]
        mcp.oauthDiscoverReplies = [.success(McpOAuthDiscovery(issuer: issuer))]
        let vm = viewModel(mcp)
        await vm.load()

        await vm.discover()

        XCTAssertEqual(vm.issuerForClient, issuer)
    }

    // MARK: - the editor

    /// Only the `clientId` is ever readable on the wire — `clientSecretPresent` is the whole thing the server
    /// says about a secret, and the callback is on the read-back but not a value to re-submit. So the editor
    /// backfills one field and starts the other two empty, which is what makes "unchanged" mean unchanged.
    func testTheEditorBackfillsTheClientIDAndNothingElse() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthDiscoverReplies = [
            .success(McpOAuthDiscovery(
                issuer: issuer,
                clientId: "harnax-crm-client-7f3a",
                clientSecretPresent: true,
                callbackUrl: "http://127.0.0.1:28080/mcp/oauth/callback",
                defaultCallbackUrl: redirect
            ))
        ]
        let vm = viewModel(mcp)
        await vm.discover()

        vm.openClientEditor()

        XCTAssertTrue(vm.showsClientEditor)
        XCTAssertEqual(vm.clientID, "harnax-crm-client-7f3a")
        XCTAssertEqual(vm.clientSecret, "")
        XCTAssertEqual(vm.clientCallbackURL, "")
        XCTAssertFalse(vm.clearsClientSecret)
    }

    /// Reopening is a fresh start from the read-back, not the text left in the fields by an abandoned edit —
    /// a half-typed secret that survived a close would be a write nobody meant.
    func testReopeningStartsFromTheReadBack() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthDiscoverReplies = [.success(McpOAuthDiscovery(issuer: issuer, clientId: "stored-id"))]
        let vm = viewModel(mcp)
        await vm.discover()

        vm.openClientEditor()
        vm.clientID = "typed-over"
        vm.clientSecret = "typed-secret"
        vm.clientCallbackURL = "https://elsewhere.example.com/cb"
        vm.closeClientEditor()
        vm.openClientEditor()

        XCTAssertEqual(vm.clientID, "stored-id")
        XCTAssertEqual(vm.clientSecret, "")
        XCTAssertEqual(vm.clientCallbackURL, "")
    }

    // MARK: - the tri-state secret

    /// An untouched secret is the key going absent, which the server reads as "keep what is stored"
    /// (`McpOAuthClientRequest.kt:22-31`). A mask is never sent back as a value.
    func testAnUntouchedSecretIsLeftOffTheBody() throws {
        let vm = viewModel(FakeMcpServers())
        vm.clientID = "harnax-crm-client-7f3a"

        let draft = try XCTUnwrap(vm.clientDraft)
        XCTAssertNil(draft.clientSecret)
        XCTAssertNil(draft.callbackUrl)
        let json = try XCTUnwrap(JSONObject(draft))
        XCTAssertEqual(Set(json.keys), ["clientId"], "omitted halves stay off the wire")
    }

    /// The clear case is an explicit blank, because blank is the only value `resolveSecret` clears with.
    func testTheClearSwitchSendsABlankSecret() throws {
        let vm = viewModel(FakeMcpServers())
        vm.clientID = "harnax-crm-client-7f3a"
        vm.setClearsClientSecret(true)

        let draft = try XCTUnwrap(vm.clientDraft)
        XCTAssertEqual(draft.clientSecret, "")
        XCTAssertEqual(try XCTUnwrap(JSONObject(draft))["clientSecret"] as? String, "")
    }

    /// Pasting a secret and then ticking "delete" cannot submit both: the console clears the field on the same
    /// tick (`OAuthPanel.tsx:573-578`), because otherwise the operator believes they replaced a value they
    /// are in fact removing.
    func testTickingClearLosesWhateverWasTyped() throws {
        let vm = viewModel(FakeMcpServers())
        vm.clientID = "harnax-crm-client-7f3a"
        vm.clientSecret = "s3cret"

        vm.setClearsClientSecret(true)

        XCTAssertEqual(vm.clientSecret, "")
        XCTAssertEqual(vm.clientDraft?.clientSecret, "")
    }

    /// Unticking it again does not restore the erased text — the field is empty, so the next save keeps what
    /// is stored rather than clearing it by accident.
    func testUntickingClearReturnsToUnchanged() throws {
        let vm = viewModel(FakeMcpServers())
        vm.clientID = "harnax-crm-client-7f3a"
        vm.setClearsClientSecret(true)

        vm.setClearsClientSecret(false)

        XCTAssertNil(vm.clientDraft?.clientSecret)
    }

    /// The three ways this app can be about to send a body the endpoint will refuse, held shut locally instead:
    /// `clientId` is `@NotBlank` on the trimmed value and `@Size(max = 255)` in UTF-16 units, and a callback
    /// has to be an http(s) URL with a host (`McpOAuthServiceImpl.kt:433-448`).
    func testTheSaveButtonHoldsForAnUnusableIDOrCallback() throws {
        let vm = viewModel(FakeMcpServers())
        XCTAssertFalse(vm.canSaveClient, "nothing typed yet")

        vm.clientID = "   "
        XCTAssertNil(vm.clientDraft, "whitespace-only is blank to the server, so blank here too")

        vm.clientID = String(repeating: "a", count: 256)
        XCTAssertNil(vm.clientDraft)

        vm.clientID = "harnax-crm-client-7f3a"
        vm.clientCallbackURL = "harnax://oauth-callback"
        XCTAssertNil(vm.clientDraft, "a custom scheme is unregistrable — that is why the return leg is caught in app")

        vm.clientCallbackURL = "not a url"
        XCTAssertNil(vm.clientDraft)

        vm.clientCallbackURL = ""
        XCTAssertEqual(vm.clientDraft?.callbackUrl, nil, "left blank means keep the registered one")
        XCTAssertTrue(vm.canSaveClient)
    }

    func testValuesReachTheServerTrimmed() throws {
        let vm = viewModel(FakeMcpServers())
        vm.clientID = "  harnax-crm-client-7f3a \n"
        vm.clientSecret = "  s3cret  "
        vm.clientCallbackURL = "  https://crm.example.com/mcp/oauth/callback  "

        let draft = try XCTUnwrap(vm.clientDraft)
        XCTAssertEqual(draft.clientId, "harnax-crm-client-7f3a")
        XCTAssertEqual(draft.clientSecret, "s3cret")
        XCTAssertEqual(draft.callbackUrl, "https://crm.example.com/mcp/oauth/callback")
    }

    // MARK: - the registration write

    /// The answer to `POST /{id}/oauth/client` is the same discovery shape, and it is the only place a caller
    /// learns whether a secret is now stored — there is no separate read for it.
    func testASuccessfulRegistrationTakesTheAnswerAsTheNewReadBack() async throws {
        let mcp = FakeMcpServers()
        let before = McpOAuthDiscovery(issuer: issuer)
        let after = McpOAuthDiscovery(issuer: issuer, clientId: "harnax-crm-client-7f3a", clientSecretPresent: true)
        mcp.oauthDiscoverReplies = [.success(before)]
        mcp.oauthClientReplies = [.success(after)]
        let vm = viewModel(mcp)
        await vm.discover()
        vm.openClientEditor()
        vm.clientID = "harnax-crm-client-7f3a"
        vm.clientSecret = "s3cret"

        await vm.saveClient()

        XCTAssertEqual(mcp.oauthClientRequests.count, 1)
        let (id, draft) = try XCTUnwrap(mcp.oauthClientRequests.first)
        XCTAssertEqual(id, 7)
        XCTAssertEqual(draft.clientId, "harnax-crm-client-7f3a")
        XCTAssertEqual(vm.setupPhase, .loaded(after))
        XCTAssertTrue(vm.discovery?.clientSecretPresent ?? false)
        XCTAssertFalse(vm.showsClientEditor, "registered, so there is nothing left to edit")
        XCTAssertFalse(vm.isSavingClient)
    }

    /// A refused registration leaves the editor exactly as the operator left it, with the server's sentence
    /// above it — the draft is work they may still want to fix and retry.
    func testARefusalKeepsTheEditorOpenWithTheServersSentence() async throws {
        let mcp = FakeMcpServers()
        let refusal = APIError.business(
            code: -1,
            message: "The OAuth issuer is not known for this server; run discovery first"
        )
        mcp.oauthClientReplies = [.failure(refusal)]
        let vm = viewModel(mcp)
        vm.openClientEditor()
        vm.clientID = "harnax-crm-client-7f3a"

        await vm.saveClient()

        XCTAssertEqual(vm.inlineError, "The OAuth issuer is not known for this server; run discovery first")
        XCTAssertTrue(vm.showsClientEditor)
        XCTAssertEqual(vm.clientID, "harnax-crm-client-7f3a")
        XCTAssertEqual(vm.setupPhase, .idle, "no read-back arrived, so nothing was written")
        XCTAssertFalse(vm.isSavingClient)
    }

    func testTheRegistrationIsPostedOnce() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthClientReplies = [.success(McpOAuthDiscovery(issuer: issuer))]
        let vm = viewModel(mcp)
        vm.clientID = "harnax-crm-client-7f3a"

        mcp.gateWrites = true
        let first = Task { await vm.saveClient() }
        try await waitUntil { mcp.oauthClientRequests.count == 1 }
        XCTAssertTrue(vm.isSavingClient)
        let second = Task { await vm.saveClient() }
        try await settle()
        XCTAssertEqual(mcp.oauthClientRequests.count, 1, "a second tap cannot register the same client twice")

        mcp.releaseWrites()
        await first.value
        await second.value

        XCTAssertFalse(vm.isSavingClient)
        XCTAssertEqual(mcp.oauthClientRequests.count, 1, "one save, one POST")
    }

    // MARK: - the return leg, taken inside the app

    /// One call gives back both halves of the hand-off: the request to load and the address to watch for,
    /// which is the registration's own `callbackUrl` read off that request
    /// (`McpOAuthUserServiceImpl.kt:103-107`, `:152`).
    func testASessionOpensOnlyWhenTheHandOffCanBeRecognised() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthURLReplies = [.success(try authorization())]
        let vm = viewModel(mcp)

        await vm.startInAppAuthorization()

        XCTAssertEqual(mcp.oauthURLRequests.count, 1, "one hand-off, one request")
        XCTAssertEqual(mcp.oauthURLRequests.first?.id, 7)
        XCTAssertNil(mcp.oauthURLRequests.first?.scope, "no scope override: the row's own list is what was asked for")
        XCTAssertEqual(vm.inAppTarget?.redirect, redirect)
        XCTAssertEqual(vm.inAppTarget?.url.path, "/authorize")
        XCTAssertNil(vm.oauthNotice)
    }

    /// With no request to load there is no session: the refusal is the server's own sentence about why this
    /// row cannot authorize yet (no recorded authorization endpoint, no client registered), and it belongs to
    /// the status block that already knows how to show one (`McpOAuthUserServiceImpl.kt:95-107`).
    func testAFailedAuthorizeRequestOpensNoSession() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthURLReplies = [.failure(.offline)]
        let vm = viewModel(mcp)

        await vm.startInAppAuthorization()

        XCTAssertNil(vm.inAppTarget, "the sheet's presence is this value, and there is nothing to show in it")
        XCTAssertEqual(vm.oauthPhase, .failed(message: ErrorMessage.text(for: .offline)))
        XCTAssertTrue(mcp.oauthExchangeRequests.isEmpty)
    }

    /// A request without a redirect cannot be recognised, so it is never opened.
    func testARequestWithoutARedirectIsNeverOpened() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthURLReplies = [.success(try authorization(withRedirect: false))]
        let vm = viewModel(mcp)

        await vm.startInAppAuthorization()

        XCTAssertNil(vm.inAppTarget)
        XCTAssertEqual(vm.oauthNotice, hx("mcp.oauth.noRedirect"))
        XCTAssertTrue(mcp.oauthExchangeRequests.isEmpty)
    }

    /// The dismissal path fires for every closing gesture, including the one that follows a completed
    /// exchange — so it may only speak while a session is actually open, or it stamps "cancelled" over the
    /// sentence the exchange endpoint answered with.
    func testCancellingReportsOnlyWhileASessionIsOpen() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthURLReplies = [.success(try authorization()), .success(try authorization())]
        mcp.oauthExchangeReplies = [.success(try outcome(authorized: true, message: "This MCP server is authorized for your account"))]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: true))]
        let vm = viewModel(mcp)

        vm.cancelInAppAuthorization()
        XCTAssertNil(vm.oauthNotice, "nothing was open, so nothing was cancelled")

        await vm.startInAppAuthorization()
        vm.cancelInAppAuthorization()
        XCTAssertNil(vm.inAppTarget)
        XCTAssertEqual(vm.oauthNotice, hx("mcp.oauth.cancelled"))

        await vm.startInAppAuthorization()
        await vm.finishInAppAuthorization(with: McpOAuthExchangeDraft(code: "Sp+end", state: "kQ7x"))
        vm.cancelInAppAuthorization()
        XCTAssertEqual(vm.oauthNotice, "This MCP server is authorized for your account")
    }

    /// `authorized` is the verdict and the transport never is: a refusal by the authorization server arrives as
    /// a successful answer saying no (`McpOAuthController.kt:101-102`), with its own sentence to show.
    func testTheVerdictIsReadOffAuthorizedAndTheStatusReReads() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthExchangeReplies = [.success(try outcome(
            authorized: true,
            message: "This MCP server is authorized for your account"
        ))]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: true))]
        let vm = viewModel(mcp)

        await vm.finishInAppAuthorization(with: McpOAuthExchangeDraft(code: "Sp+end", state: "kQ7x"))

        XCTAssertEqual(vm.oauthNotice, "This MCP server is authorized for your account")
        XCTAssertNil(vm.inAppTarget, "the sheet's presence is this value")
        XCTAssertFalse(vm.isExchanging)
        XCTAssertEqual(mcp.oauthStatusRequests, [7], "a stored grant is server-side state the badge has to catch up with")
    }

    func testARefusalIsShownAsTheServersOwnSentence() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthExchangeReplies = [.success(try outcome(
            authorized: false,
            message: "The authorization server refused: access_denied - please start the authorization again"
        ))]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: false))]
        let vm = viewModel(mcp)

        await vm.finishInAppAuthorization(with: McpOAuthExchangeDraft(error: "access_denied"))

        XCTAssertEqual(
            vm.oauthNotice,
            "The authorization server refused: access_denied - please start the authorization again"
        )
        XCTAssertEqual(mcp.oauthStatusRequests, [7], "a no is still news the badge has to re-read")
    }

    /// The code and state the session caught are what gets posted, and nothing else — the pending request the
    /// state names says which server this was for, so no MCP id travels with it.
    func testTheCodeCaughtByTheSessionIsWhatGetsPosted() async throws {
        let mcp = FakeMcpServers()
        let draft = McpOAuthExchangeDraft(
            code: "Sp+end",
            state: "kQ7x",
            error: nil,
            errorDescription: nil
        )
        mcp.oauthExchangeReplies = [.success(try outcome(authorized: true, message: "ok"))]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: true))]
        let vm = viewModel(mcp)

        await vm.finishInAppAuthorization(with: draft)

        XCTAssertEqual(mcp.oauthExchangeRequests, [draft])
    }

    /// An exchange that never reaches the endpoint still has to re-read: a grant can have been stored before
    /// the transport failed, and the badge is the only thing that knows.
    func testAFailedExchangeStillReReadsTheStatus() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthExchangeReplies = [.failure(.offline)]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: true))]
        let vm = viewModel(mcp)

        await vm.finishInAppAuthorization(with: McpOAuthExchangeDraft(code: "Sp+end", state: "kQ7x"))

        XCTAssertEqual(vm.oauthNotice, ErrorMessage.text(for: .offline))
        XCTAssertEqual(mcp.oauthStatusRequests, [7])
        if case let .loaded(status) = vm.oauthPhase {
            XCTAssertTrue(status.authorized, "the grant says so, whatever the exchange call looked like")
        } else {
            XCTFail("the status read never landed")
        }
    }

    /// The two hand-offs are on screen side by side, so a browser wait can still be asleep when this device
    /// catches its own code. Spending it ends that wait: the poll would otherwise wake, read a status it has
    /// no news about and stamp "still no grant" over the sentence the exchange endpoint answered with — the
    /// one verdict on this screen that came from the server rather than from a timeout.
    func testTheExchangeEndsABrowserWaitThatIsStillRunning() async throws {
        let mcp = FakeMcpServers()
        mcp.oauthURLReplies = [.success(try authorization()), .success(try authorization())]
        mcp.oauthExchangeReplies = [.success(try outcome(
            authorized: true,
            message: "This MCP server is authorized for your account"
        ))]
        mcp.oauthStatusReplies = [.success(McpOAuthStatus(authorized: true))]
        // An hour between reads: the wait is not what settles this device.
        let vm = McpDetailViewModel(
            mcp: mcp,
            authorizer: BrowserHandoff(),
            id: 7,
            pollInterval: 3600,
            pollWindow: 3600
        )

        let flow = Task { await vm.startAuthorization() }
        try await waitUntil { vm.isAwaitingAuthorization }

        await vm.startInAppAuthorization()
        XCTAssertNotNil(vm.inAppTarget, "the session opens while the wait is still asleep")
        await vm.finishInAppAuthorization(with: McpOAuthExchangeDraft(code: "Sp+end", state: "kQ7x"))
        await flow.value

        XCTAssertFalse(vm.isAwaitingAuthorization)
        XCTAssertEqual(vm.oauthNotice, "This MCP server is authorized for your account")
        XCTAssertEqual(mcp.oauthStatusRequests, [7], "one read — the settle the exchange asked for, not a poll")
    }

    // MARK: - the read-back's words

    /// The panel quotes the source column back to the operator (`OAuthPanel.tsx:22-35`), and a raw enum name is
    /// not what they need to see.
    func testTheIssuerSourceIsQuotedAsAPhrase() throws {
        let known = ["CONFIG", "PROTECTED_RESOURCE", "RESOURCE_METADATA"]
        for column in known {
            let text = try XCTUnwrap(McpOAuthPresentation.issuerSource(column))
            XCTAssertNotEqual(text, column, "\(column) reached the screen as itself")
            XCTAssertFalse(text.isEmpty)
        }
        XCTAssertEqual(
            McpOAuthPresentation.issuerSource("PROTECTED_RESOURCE"),
            hx("mcp.oauth.setup.issuerSource.protectedResource")
        )
    }

    /// A fourth way of finding an authorization server reads as itself rather than as one of these three, and
    /// a row with no source column says nothing at all.
    func testAnUnknownIssuerSourceShowsItsOwnValue() throws {
        XCTAssertEqual(McpOAuthPresentation.issuerSource("JWKS_DOCUMENT"), "JWKS_DOCUMENT")
        XCTAssertNil(McpOAuthPresentation.issuerSource(nil))
        XCTAssertNil(McpOAuthPresentation.issuerSource("   "))
    }

    // MARK: - helpers

    private func viewModel(_ mcp: FakeMcpServers) -> McpDetailViewModel {
        McpDetailViewModel(mcp: mcp, authorizer: InAppOnlyHandoff(), id: 7)
    }

    /// An OAUTH2 row, as the wire makes it: `oauthConfig`'s two non-null columns always arrive, so a stub that
    /// leaves them out would be testing a body this server never answers with.
    private func oauthRow(authorizationServer: String? = nil) throws -> McpServerRow {
        var fields: [String: Any] = [
            "id": 7, "name": "crm", "type": "streamablehttp",
            "url": "https://crm.example.com/sse", "authType": "OAUTH2", "status": 1, "isPublic": 1,
        ]
        if let authorizationServer {
            fields["oauthConfig"] = [
                "authorizationServer": authorizationServer,
                "scopes": ["mcp:tools"],
                "resourceIndicator": true,
            ]
        }
        return try XCTUnwrap(PageStub.list([McpServerRow].self, [fields]).first)
    }

    private func authorization(withRedirect: Bool = true) throws -> McpOAuthAuthorization {
        let query = withRedirect
            ? "response_type=code&client_id=harnax-crm-client-7f3a&redirect_uri=\(redirect)&state=kQ7x"
            : "response_type=code&client_id=harnax-crm-client-7f3a&state=kQ7x"
        return try JSONDecoder().decode(
            McpOAuthAuthorization.self,
            from: Data(
                #"{"authorizeUrl":"https://auth.example.com/authorize?\#(query)","issuer":"\#(issuer)","scopes":["mcp:tools"],"expiresIn":300}"#
                    .utf8
            )
        )
    }

    private func outcome(authorized: Bool, message: String) throws -> McpOAuthExchangeOutcome {
        let body: [String: Any] = [
            "authorized": authorized,
            "message": message,
            "scopes": authorized ? ["mcp:tools"] : [],
        ]
        return try JSONDecoder().decode(
            McpOAuthExchangeOutcome.self,
            from: try JSONSerialization.data(withJSONObject: body)
        )
    }

    /// An omitted key and a key sent as null are different statements to this endpoint, and only the bytes
    /// can show which one went out.
    private func JSONObject<T: Encodable>(_ value: T) throws -> [String: Any]? {
        let data = try JSONEncoder().encode(value)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0 ..< 400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}

/// The system-browser hand-off has its own tests; these flows never use one.
private struct InAppOnlyHandoff: McpAuthorizing {
    func presentAuthorizeURL(_ url: URL, for serverID: Int64) async -> McpAuthorizationHandoff {
        XCTFail("the in-app session never leaves the app")
        return .cancelled
    }
}

/// The other hand-off, for the one test that needs a browser wait to be asleep while this device catches its
/// own code. Reaching a browser says nothing about the grant, so this only reports the open.
private struct BrowserHandoff: McpAuthorizing {
    func presentAuthorizeURL(_ url: URL, for serverID: Int64) async -> McpAuthorizationHandoff {
        .openedInBrowser(url: url)
    }
}
