import Foundation
import HarnaxCore
@testable import HarnaxAPI

/// Body shapes copied from the running dev stack (admin on 127.0.0.1:28080, 2026-09-28). Nulls are
/// dropped by the server rather than sent, so these strings omit keys on purpose.
enum Wire {
    static func envelope(code: Int, message: String, data: String?) -> String {
        let payload = data.map { ",\"data\":\($0)" } ?? ""
        return """
        {"code":\(code),"message":"\(message)"\(payload),"timestamp":1790592457109,"isSuccess":\(code == 200 ? "true" : "false")}
        """
    }

    static func success(_ data: String? = "null") -> String {
        envelope(code: 200, message: "success", data: data)
    }

    static func business(_ code: Int, _ message: String) -> String {
        envelope(code: code, message: message, data: nil)
    }

    /// What the container answers a rejected bearer with: no `code`, no `message`, no envelope at all.
    static let unauthorized = """
    {"timestamp":1790592457109,"status":401,"error":"Unauthorized","path":"/api/admin/agents/page"}
    """

    static func login(
        token: String = "tok-1",
        expiresIn: Int64? = 3600,
        expiresAt: Int64? = nil,
        nickname: String? = "System Admin",
        tenantID: Int64? = 1,
        tenantName: String? = "Default",
        routerKey: String? = "rk-1"
    ) -> String {
        let lifetime = expiresIn.map { "\"expiresIn\":\($0)," } ?? ""
        let deadline = expiresAt.map { "\"expiresAt\":\($0)," } ?? ""
        let name = nickname.map { "\"nickname\":\"\($0)\"," } ?? ""
        let tenant = tenantID.map { "\"currentTenantId\":\($0)," } ?? ""
        let tenants = tenantID.map {
            "\"tenants\":[{\"id\":\($0),\"name\":\"\(tenantName ?? "")\",\"status\":1}],"
        } ?? ""
        let key = routerKey.map { "\"routerApiKey\":\"\($0)\"," } ?? ""
        return success("""
        {"accessToken":"\(token)","tokenType":"Bearer",\(lifetime)\(deadline)\(key)\(tenants)\(tenant)\
        "userInfo":{"userId":1,"username":"admin",\(name)"email":"admin@harnax.com","isAdmin":1}}
        """)
    }

    static func refreshed(token: String, expiresIn: Int64?, tenantID: Int64 = 1) -> String {
        let lifetime = expiresIn.map { ",\"expiresIn\":\($0)" } ?? ""
        return success("{\"accessToken\":\"\(token)\",\"tenantId\":\(tenantID)\(lifetime)}")
    }

    static let me = """
    {"id":1,"username":"admin","nickname":"System Admin","email":"admin@harnax.com","phone":"",\
    "isAdmin":1,"authMode":"jwt","expiresAt":1790602105000}
    """

    static func agents(total: Int, ids: [Int64]) -> String {
        let rows = ids.map { """
        {"id":\($0),"name":"agent-\($0)","status":1}
        """ }.joined(separator: ",")
        return success("""
        {"pageNum":1,"pageSize":20,"total":\(total),"records":[\(rows)]}
        """
        )
    }
}

final class TestClock: @unchecked Sendable {
    var date: Date

    init(_ date: Date = Date(timeIntervalSince1970: 1_790_592_457)) {
        self.date = date
    }

    var now: @Sendable () -> Date { { self.date } }

    func advance(_ seconds: TimeInterval) {
        date = date.addingTimeInterval(seconds)
    }
}

/// One reply per call, taken in order. Tests read the recorded requests to see what actually went out.
final class StubTransport: HTTPRequesting, @unchecked Sendable {
    private struct Reply {
        let status: Int
        let body: String
        let error: Error?
    }

    private var replies: [Reply] = []
    private var recorded: [URLRequest] = []

    var requests: [URLRequest] { recorded }

    var callCount: Int { recorded.count }

    func enqueue(_ status: Int, _ body: String) {
        replies.append(Reply(status: status, body: body, error: nil))
    }

    func enqueueFailure(_ error: Error) {
        replies.append(Reply(status: 0, body: "", error: error))
    }

    func reset() {
        replies.removeAll()
        recorded.removeAll()
    }

    /// No lock: `perform` never suspends between recording the request and taking its reply, and the tests
    /// drive one call at a time. An exhausted queue answers 599 so a missing expectation fails loudly.
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        recorded.append(request)
        let reply = replies.isEmpty ? Reply(status: 599, body: "{}", error: nil) : replies.removeFirst()
        if let error = reply.error { throw error }
        guard let url = request.url,
              let http = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: nil, headerFields: nil)
        else {
            throw URLError(.badURL)
        }
        return (Data(reply.body.utf8), http)
    }
}

/// The whole stack the feature layer would assemble, wired to a stub transport and a memory keychain.
struct APIHarness {
    let clock: TestClock
    let store: MemorySecretStore
    let transport: StubTransport
    let session: AuthSession
    let configs: ServerConfigStore
    let client: APIClient
    let auth: AuthFlow
    let agents: AdminClient

    init(now: Date? = nil, language: String = "zh-CN") {
        let clock = TestClock(now ?? Date(timeIntervalSince1970: 1_790_592_457))
        let store = MemorySecretStore()
        let transport = StubTransport()
        let session = AuthSession(store: store, now: clock.now)
        let configs = ServerConfigStore(store: store)
        let client = APIClient(
            transport: transport,
            session: session,
            configs: configs,
            refresher: TokenRefresher(transport: transport, configs: configs),
            language: { language }
        )
        self.clock = clock
        self.store = store
        self.transport = transport
        self.session = session
        self.configs = configs
        self.client = client
        self.auth = AuthFlow(client: client, session: session, configs: configs, now: clock.now)
        self.agents = AdminClient(client: client)
    }

    /// Signed-in state as the keychain would hold it after a real login.
    func signIn(token: String = "tok-1", expiresIn: Int64 = 3600) async throws {
        try await session.signIn(decode(Wire.login(token: token, expiresIn: expiresIn)))
    }

    /// A token the client itself considers fresh, so only the server can decide it is stale.
    func signInWithExpiry(_ expiresAtMillis: Int64) async throws {
        try await session.signIn(decode(Wire.login(token: "tok-1", expiresIn: nil, expiresAt: expiresAtMillis)))
    }

    func decode(_ body: String) -> LoginResponse {
        try! JSONDecoder().decode(Envelope<LoginResponse>.self, from: Data(body.utf8)).data!
    }

    /// A flow with no memory of this session — what the next process launch builds over the same keychain.
    func relaunched() -> AuthFlow {
        AuthFlow(client: client, session: session, configs: configs, now: clock.now)
    }
}

func headerValue(_ request: URLRequest, _ name: String) -> String? {
    request.value(forHTTPHeaderField: name)
}

/// Reads a sent URL's parameters back, after whatever percent-encoding the components writer applied.
func queryItems(of url: URL) -> [URLQueryItem] {
    URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
}
