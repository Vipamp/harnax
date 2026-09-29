import Foundation
import HarnaxCore

/// Hand-written stand-ins for the two facades the screens talk to. Nothing here reaches a socket or the
/// keychain, which is what makes "the user tapped this, so that went out, so this is on screen" a
/// one-second test.
final class FakeAuth: AuthFlowing, @unchecked Sendable {
    private(set) var loginCalls: [(username: String, password: String)] = []
    private(set) var savedConfigs: [ServerConfig] = []
    private(set) var profileCalls = 0
    private(set) var logoutCalls = 0
    private(set) var serverConfigurationCalls = 0
    private(set) var tenantOptionCalls = 0
    private(set) var switchCalls: [TenantSummary] = []

    var authState: AuthState = .signedOut
    var loginResult: Result<AccountSnapshot, APIError> = .success(AccountSnapshot(username: "admin", tenantID: 1))
    var profileResult: Result<MeInfo, APIError>?
    var serverConfigurationResult: Result<ServerConfig, APIError> = .success(FakeAuth.devConfig)
    var saveResult: Result<Void, APIError> = .success(())
    /// One row is the ordinary account, so the default leaves the switch entry hidden and every existing
    /// `me` test blind to it.
    var tenantOptionsResult: Result<[TenantSummary], APIError> = .success([])
    var switchResult: Result<Void, APIError> = .success(())

    static let devConfig = try! ServerConfig(
        adminBaseURL: "http://127.0.0.1:28080",
        routerBaseURL: "http://127.0.0.1:28081"
    )

    /// The `me` payload is decode-only, so a test spells the fields it cares about instead of building one.
    func seedProfile(_ fields: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: fields)
        profileResult = .success(try JSONDecoder().decode(MeInfo.self, from: data))
    }

    func state() async -> AuthState { authState }

    func login(username: String, password: String) async -> Result<AccountSnapshot, APIError> {
        loginCalls.append((username, password))
        if case let .success(account) = loginResult { authState = .signedIn(account) }
        return loginResult
    }

    func logout() async {
        logoutCalls += 1
        authState = .signedOut
    }

    func profile() async -> Result<MeInfo, APIError> {
        profileCalls += 1
        return profileResult ?? .failure(.unpackable)
    }

    func serverConfiguration() async -> Result<ServerConfig, APIError> {
        serverConfigurationCalls += 1
        return serverConfigurationResult
    }

    func tenantOptions() async -> Result<[TenantSummary], APIError> {
        tenantOptionCalls += 1
        return tenantOptionsResult
    }

    func switchTenant(to tenant: TenantSummary) async -> Result<Void, APIError> {
        switchCalls.append(tenant)
        return switchResult
    }

    func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError> {
        savedConfigs.append(serverConfiguration)
        return saveResult
    }
}

/// Pages served in the order the test queues them. A request nobody queued answers as a decoding failure
/// and still shows up in `requests`, so an extra call reads as a wrong number rather than a crash.
final class FakeAgents: AgentCataloging, @unchecked Sendable {
    private(set) var requests: [(num: Int, size: Int)] = []
    private(set) var filters: [(name: String?, status: Int?)] = []
    var replies: [Result<Page<AgentSummary>, APIError>] = []

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var deleteCalls: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var relatedRequests: [Int64] = []
    var relatedReply: Result<[RelatedSession], APIError> = .success([])

    func page(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        requests.append((num: num, size: size))
        filters.append((name: name, status: status))
        return replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
    }

    func setStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        return statusReplies.isEmpty ? .success(EmptyResponse()) : statusReplies.removeFirst()
    }

    func delete(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteCalls.append(id)
        return deleteReplies.isEmpty ? .success(EmptyResponse()) : deleteReplies.removeFirst()
    }

    func relatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        relatedRequests.append(id)
        return relatedReply
    }
}

/// The team surface, with the same reply-queue discipline as `FakeAgents`.
final class FakeTeams: TeamCataloging, @unchecked Sendable {
    private(set) var requests: [(num: Int, size: Int)] = []
    private(set) var filters: [(name: String?, status: Int?)] = []
    var replies: [Result<Page<TeamSummary>, APIError>] = []

    private(set) var statusCalls: [(id: Int64, enabled: Bool)] = []
    var statusReplies: [Result<EmptyResponse, APIError>] = []
    private(set) var deleteCalls: [Int64] = []
    var deleteReplies: [Result<EmptyResponse, APIError>] = []

    private(set) var relatedRequests: [Int64] = []
    var relatedReply: Result<[RelatedSession], APIError> = .success([])

    func teamPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<TeamSummary>, APIError> {
        requests.append((num: num, size: size))
        filters.append((name: name, status: status))
        return replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
    }

    func setTeamStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> {
        statusCalls.append((id: id, enabled: enabled))
        return statusReplies.isEmpty ? .success(EmptyResponse()) : statusReplies.removeFirst()
    }

    func deleteTeam(id: Int64) async -> Result<EmptyResponse, APIError> {
        deleteCalls.append(id)
        return deleteReplies.isEmpty ? .success(EmptyResponse()) : deleteReplies.removeFirst()
    }

    func teamRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        relatedRequests.append(id)
        return relatedReply
    }
}

/// The refresh endpoint answers `200` with a per-session verdict, so a test needs to control the lines,
/// not just the outcome.
final class FakeRefresher: SessionRefreshing, @unchecked Sendable {
    private(set) var batches: [[String]] = []
    var reply: Result<[SessionRefreshOutcome], APIError> = .success([])

    func refreshSessions(_ ids: [String]) async -> Result<[SessionRefreshOutcome], APIError> {
        batches.append(ids)
        return reply
    }
}

enum PageStub {
    /// Field maps rather than string literals: every field on a row is optional on the wire, and a test
    /// about paging does not want to spell out thirteen keys.
    static func page(
        _ records: [[String: Any]],
        pageNum: Int = 1,
        total: Int? = nil,
        pageSize: Int = 20
    ) throws -> Page<AgentSummary> {
        try page(AgentSummary.self, records, pageNum: pageNum, total: total, pageSize: pageSize)
    }

    static func page<T: Decodable>(
        _ type: T.Type,
        _ records: [[String: Any]],
        pageNum: Int = 1,
        total: Int? = nil,
        pageSize: Int = 20
    ) throws -> Page<T> {
        let bodies = try records.map { try JSONSerialization.data(withJSONObject: $0) }
            .map { String(decoding: $0, as: UTF8.self) }
            .joined(separator: ",")
        let body = #"{"pageNum":\#(pageNum),"pageSize":\#(pageSize),"total":\#(total ?? records.count),"records":[\#(bodies)]}"#
        return try JSONDecoder().decode(Page<T>.self, from: Data(body.utf8))
    }

    /// A wire array of objects — the related-session list and the refresh verdicts both arrive this way.
    static func list<T: Decodable>(_ type: [T].Type, _ records: [[String: Any]]) throws -> [T] {
        let data = try JSONSerialization.data(withJSONObject: records)
        return try JSONDecoder().decode([T].self, from: data)
    }
}

extension AgentSummary {
    static func stub(_ fields: [String: Any]) throws -> AgentSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(AgentSummary.self, from: data)
    }
}

extension TeamSummary {
    static func stub(_ fields: [String: Any]) throws -> TeamSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(TeamSummary.self, from: data)
    }
}
