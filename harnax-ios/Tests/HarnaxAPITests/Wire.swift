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

    /// `GET /api/admin/auth/tenants` answers a bare `List<TenantResponse>` inside the usual envelope
    /// (`AuthController.kt:131-144`).
    static func tenants(_ rows: [(id: Int64, name: String, status: Int)]) -> String {
        let body = rows.map { """
        {"id":\($0.id),"name":"\($0.name)","status":\($0.status)}
        """ }.joined(separator: ",")
        return success("[\(body)]")
    }

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

/// One reply that can be held open, so a request is genuinely on the wire while the test does something else
/// with the session. Same triad as the feature suite's `PageReadGate`
/// (`Tests/HarnaxFeaturesTests/ListAppendIdentityTests.swift:845`): `arm()` parks the *next* reply until
/// `release()`, and a transport nobody armed answers exactly as before.
///
/// At most one reply may ever be parked. The state below is deliberately unsynchronised, and `absorb`'s
/// read-of-`armed`-then-clear is not atomic, so two legs parking at once would leave the second continuation
/// overwriting the first and the gate hung. Two concurrent callers do exist now (the session panel's two legs),
/// and what keeps them out of that window is that `arm()` is called once per test for a reply the test then
/// releases — not by anything this class enforces.
final class ReplyGate<Reply>: @unchecked Sendable {
    private var armed = false
    private var waiting: CheckedContinuation<Reply, Never>?
    private var parkedReply: Reply?

    /// Holds the next reply open.
    func arm() {
        armed = true
    }

    /// Parks the reply when armed, hands it back immediately otherwise.
    func absorb(_ reply: Reply) async -> Reply {
        guard armed else { return reply }
        armed = false
        parkedReply = reply
        return await withCheckedContinuation { waiting = $0 }
    }

    /// Lets the parked reply answer, as a server that was simply slow.
    func release() {
        guard let continuation = waiting, let reply = parkedReply else { return }
        waiting = nil
        parkedReply = nil
        continuation.resume(returning: reply)
    }
}

/// One reply per call. Tests read the recorded requests to see what actually went out.
final class StubTransport: HTTPRequesting, @unchecked Sendable {
    private struct Reply {
        let status: Int
        let body: String
        let error: Error?
    }

    /// A reply that waits for the request that names its path rather than for its turn in a queue.
    private struct RoutedReply {
        let path: String
        let reply: Reply
    }

    /// Armed by the one test that needs a reply still outstanding when it ends the session.
    let replyGate = ReplyGate<Void>()

    /// Guards `replies`, `routed` and `recorded`.
    ///
    /// A single-leg call never had to share them: `perform` takes its reply before anything it can suspend on, and
    /// the tests drove one call at a time. A read that issues two legs at once (`async let`) breaks both halves of
    /// that sentence — two executor threads reach `perform` while the other leg is still parked.
    private let lock = NSLock()
    private var replies: [Reply] = []
    private var routed: [RoutedReply] = []
    private var recorded: [URLRequest] = []

    var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    var callCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return recorded.count
    }

    func enqueue(_ status: Int, _ body: String) {
        lock.lock()
        defer { lock.unlock() }
        replies.append(Reply(status: status, body: body, error: nil))
    }

    /// A transport failure for the next request in call order — for the tests that drive one call at a time,
    /// where there is no leg to route between.
    func enqueueFailure(_ error: Error) {
        lock.lock()
        defer { lock.unlock() }
        replies.append(Reply(status: 0, body: "", error: error))
    }

    /// One reply for the request that goes to `path`, whichever leg sends it first.
    ///
    /// Two concurrent legs do not decide between themselves which one reaches the transport first, so a FIFO queue
    /// would hand the nominations page to the directory call and back: the fixture stops matching the call the test
    /// describes, and whichever leg lost the race fails for a reason that has nothing to do with the code.
    func enqueue(forPath path: String, status: Int = 200, _ body: String) {
        route(path: path, Reply(status: status, body: body, error: nil))
    }

    private func route(path: String, _ reply: Reply) {
        lock.lock()
        defer { lock.unlock() }
        routed.append(RoutedReply(path: path, reply: reply))
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        replies.removeAll()
        routed.removeAll()
        recorded.removeAll()
    }

    /// The reply is taken under the lock and nothing else is done there — an armed gate parks *after* it, and the
    /// lock must not be held across a suspension. An exhausted queue answers 599 so a missing expectation fails
    /// loudly.
    func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let reply = takeReply(for: request)
        await replyGate.absorb(())
        if let error = reply.error { throw error }
        guard let url = request.url,
              let http = HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: nil, headerFields: nil)
        else {
            throw URLError(.badURL)
        }
        return (Data(reply.body.utf8), http)
    }

    private func takeReply(for request: URLRequest) -> Reply {
        lock.lock()
        defer { lock.unlock() }
        recorded.append(request)
        let path = request.url?.path
        if let index = routed.firstIndex(where: { $0.path == path }) {
            return routed.remove(at: index).reply
        }
        return replies.isEmpty ? Reply(status: 599, body: "{}", error: nil) : replies.removeFirst()
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
