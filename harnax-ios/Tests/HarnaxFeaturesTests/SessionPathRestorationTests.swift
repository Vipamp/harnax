import XCTest
import Foundation
import HarnaxAPI
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// `DESIGN.md:221` — 「401 单独通道：清凭据、**保留当前页面路径以便登录后回跳**」.
///
/// The web console does the second half with a query parameter: the 401 branch of the request error handler
/// pushes `/login?redirect=<pathname + search>` (`harnax-webui/src/requestErrorConfig.ts:150`, and the same
/// line for the token-already-present case at `:160`), and the login page reads it back and goes there
/// instead of `/welcome` after a successful exchange (`harnax-webui/src/pages/user/login/index.tsx:452-459`).
/// The manual sign-out carries the same parameter (`harnax-webui/src/components/RightContent/AvatarDropdown.tsx:60-72`).
///
/// iOS has no URL bar to carry it in, so the equivalent is the state that outlives the root's `switch`
/// between `LoginView` and the tab bar. These tests drive a real 401 through the real stack — `AuthFlow`
/// over `APIClient` over a stub transport and a memory keychain — rather than a scripted `AuthState`,
/// because which layer decides to end the session is exactly what is being measured.
@MainActor
final class SessionPathRestorationTests: XCTestCase {
    // MARK: - the stack

    private func makeStack() -> (AppModel, AuthFlow, AuthSession, RestorationStub) {
        let http = RestorationStub()
        let store = MemorySecretStore()
        let configs = ServerConfigStore(store: store)
        let session = AuthSession(store: store)
        let client = APIClient(
            transport: http,
            session: session,
            configs: configs,
            refresher: TokenRefresher(transport: http, configs: configs),
            language: { "zh-CN" }
        )
        let auth = AuthFlow(client: client, session: session, configs: configs)
        // The tab shells are never opened here, so the domain catalogs behind them share the failing doubles
        // the other root tests use; only `auth` is real.
        let unwired = UnwiredCatalogs()
        let system = UnwiredSystem()
        let saving = UnwiredSaving()
        let chat = UnwiredChat()
        let model = AppModel(
            dependencies: HarnaxDependencies(
                auth: auth,
                agents: FakeAgents(),
                teams: FakeTeams(),
                executor: chat,
                agentWrite: saving,
                teamWrite: saving,
                tasks: unwired,
                sessionRefresher: FakeRefresher(),
                models: unwired,
                tools: unwired,
                mcp: unwired,
                skills: unwired,
                clis: unwired,
                envVars: system,
                apiKeys: system,
                channels: system,
                tokenStats: system,
                sessions: chat,
                sessionCreate: chat,
                sessionConfig: chat,
                workspace: chat,
                teamArtifacts: chat,
                chatHistory: chat,
                plan: chat,
                contextUsage: chat,
                commands: chat,
                streaming: chat
            ),
            biometrics: NoBiometricUnlock(),
            gateEnabled: false
        )
        return (model, auth, session, http)
    }

    /// The credential exchange the login screen performs. Returns the account the facade handed back.
    private func signIn(_ auth: AuthFlow, _ http: RestorationStub, token: String = "tok-1") async throws {
        http.enqueue(200, RestorationWire.login(token: token))
        let vm = LoginViewModel(auth: auth)
        vm.username = "admin"
        vm.password = "admin123"
        let exchanged = await vm.submit()
        XCTAssertTrue(exchanged, "the harness's own login leg failed")
    }

    // MARK: - the 401

    /// A fresh, unexpired token that the server still refuses: only the 401 answer decides the session, and
    /// the refresh leg has to say 401 too, since a network failure is deliberately not a sign-out
    /// (`Sources/HarnaxAPI/APIClient.swift:62-80`).
    func testARefusedProfileReadEndsTheSessionThroughTheRealClient() async throws {
        let (_, auth, session, http) = makeStack()
        try await signIn(auth, http)
        http.reset()

        http.enqueue(401, RestorationWire.unauthorized)   // GET  /api/admin/auth/me
        http.enqueue(401, RestorationWire.unauthorized)   // POST /api/admin/auth/refresh-token
        let result = await auth.profile()

        guard case .failure(.unauthorized) = result else {
            return XCTFail("a 401 on the profile read must come back as unauthorized, got \(result)")
        }
        let bearer = try await session.accessToken()
        XCTAssertNil(bearer, "the first half of the design sentence — 清凭据 — happened in the keychain")
        XCTAssertEqual(
            http.requests.map { $0.url?.path },
            ["/api/admin/auth/me", TokenRefresher.path],
            "one read, one refresh, and no replay after a refresh that said 401"
        )
    }

    /// The requirement itself, at the level this app can actually hold a 页面路径: the tab.
    ///
    /// `HarnaxRootView` swaps the whole subtree on `authState`, so the only copy of the selection that can
    /// survive the trip through `LoginView` is `AppModel.tab` — and a 401 does not go near it, because the
    /// reset to `.agents` lives in `AppModel.signOut()`, which is the button's path and not the server's.
    /// Pinned here so it stops being an accident of which method the root happens to call.
    func testA401LandsTheOperatorBackOnTheTabTheyWereOnOnceTheySignInAgain() async throws {
        let (model, auth, _, http) = makeStack()
        try await signIn(auth, http)
        // What the root does once the login screen has an exchange to report.
        await model.sync()
        XCTAssertTrue(model.isSignedIn)

        // The operator leaves the default tab.
        model.tab = .context

        http.enqueue(401, RestorationWire.unauthorized)
        http.enqueue(401, RestorationWire.unauthorized)
        _ = await auth.profile()
        // A 401 ends the session inside the API layer, and the root learns it by re-reading — which today is
        // what `MeView` does after a profile read or a tenant tap (`Sources/HarnaxFeatures/Me/MeView.swift:110,118`).
        // Nothing broadcasts the 401, so a screen that does not re-read leaves the tab bar standing.
        await model.sync()
        XCTAssertFalse(model.isSignedIn, "the root is on the login screen")
        XCTAssertEqual(model.tab, .context, "and the tab it will come back to is still the one it left")

        try await signIn(auth, http, token: "tok-2")
        await model.sync()
        XCTAssertTrue(model.isSignedIn)
        XCTAssertEqual(
            model.tab,
            .context,
            "登录后回跳 — the design's promise, which is one binding on TabView(selection:) away"
        )
    }

    /// The other half of the sentence's boundary: an explicit sign-out does go back to the first tab, and
    /// that is a product decision rather than this requirement. Kept as a test because the two paths differ
    /// by one statement, and moving that statement into `publish(_:readBefore:)` would silently break the
    /// 401 return.
    func testTheSignOutButtonIsStillTheOnlyPathThatSendsTheOperatorToTheFirstTab() async throws {
        let (model, auth, _, http) = makeStack()
        try await signIn(auth, http)
        await model.sync()
        model.tab = .me

        await model.signOut()
        XCTAssertFalse(model.isSignedIn)
        XCTAssertEqual(model.tab, .agents, "a deliberate sign-out starts over; a refused token does not")
    }

    // MARK: - the binding the restore rests on

    /// Three tests above are only about the model; the screen has to read the same copy. `TabView` bound to
    /// a `@State` inside `HarnaxTabView` would rebuild at `.agents` every time the root came back from the
    /// login screen, whatever `AppModel.tab` says, and no model test would notice.
    func testTheTabBarIsBoundToTheSelectionThatOutlivesTheLoginSwap() throws {
        let root = try RestorationSources.contents(of: "HarnaxFeatures/Root/HarnaxRootView.swift")
        XCTAssertTrue(
            root.contains("TabView(selection: $model.tab)"),
            "the tab bar must select through AppModel, the one object the root keeps across a 401"
        )
        XCTAssertFalse(
            root.contains("@State private var selectedTab"),
            "a view-local tab selection would put the 401 return back to square one"
        )
    }

    /// The tenant switch re-keys the whole tree, which is what makes every screen re-read for the tenant the
    /// session is now in. The tab selection is deliberately *not* part of that key — a tenant change is not
    /// a sign-out — so this pins the other design sentence this file must not regress.
    func testATenantSwitchIsWhatRebuildsTheTreeRatherThanASignOut() throws {
        let root = try RestorationSources.contents(of: "HarnaxFeatures/Root/HarnaxRootView.swift")
        XCTAssertTrue(
            root.contains("HarnaxTabView(model: model).id(model.account?.tenantID)"),
            "the tree is identified by the tenant, so a switch replaces it and a 401 does not"
        )
    }
}

// MARK: - harness

/// One reply per call, taken in order. The 401 legs need a real status line, so the canned bodies below are
/// what the running stack answers, envelope included.
private final class RestorationStub: HTTPRequesting, @unchecked Sendable {
    private struct Reply {
        let status: Int
        let body: String
    }

    private var replies: [Reply] = []
    private var recorded: [URLRequest] = []

    var requests: [URLRequest] { recorded }

    func enqueue(_ status: Int, _ body: String) {
        replies.append(Reply(status: status, body: body))
    }

    func reset() {
        replies.removeAll()
        recorded.removeAll()
    }

    /// No lock, and one call at a time: same reasoning as the API suite's transport
    /// (`Tests/HarnaxAPITests/Wire.swift:157-159`). An exhausted queue answers 599 so a missing expectation
    /// fails loudly instead of quietly reusing a reply.
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        recorded.append(request)
        let reply = replies.isEmpty ? Reply(status: 599, body: "{}") : replies.removeFirst()
        let url = request.url!
        let http = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: nil, headerFields: nil)!
        return (Data(reply.body.utf8), http)
    }
}

/// Body shapes copied from the dev stack the API suite collected its fixtures from (admin on
/// 127.0.0.1:28080, 2026-09-28). Spelled out here rather than shared, because the two suites build
/// separately and this file owns its own 401.
private enum RestorationWire {
    static func envelope(_ data: String) -> String {
        """
        {"code":200,"message":"success","data":\(data),"timestamp":1790592457109,"isSuccess":true}
        """
    }

    static func login(token: String) -> String {
        envelope("""
        {"accessToken":"\(token)","tokenType":"Bearer","expiresIn":3600,"routerApiKey":"rk-1",\
        "tenants":[{"id":1,"name":"Default","status":1}],"currentTenantId":1,\
        "userInfo":{"userId":1,"username":"admin","nickname":"System Admin","isAdmin":1}}
        """)
    }

    /// What the container answers a rejected bearer with: no `code`, no `message`, no envelope at all.
    static let unauthorized = """
    {"timestamp":1790592457109,"status":401,"error":"Unauthorized","path":"/api/admin/auth/me"}
    """
}

/// Reads the shipped SwiftUI source, the way the copy and navigation gates do
/// (`Tests/HarnaxFeaturesTests/SkillNavigationTests.swift:12-24`). A view-state fact this suite cannot
/// exercise by running still has to be checked by something.
private enum RestorationSources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    static func contents(of relative: String) throws -> String {
        let url = sourcesRoot.appendingPathComponent(relative)
        return try String(contentsOf: url, encoding: .utf8)
    }
}
