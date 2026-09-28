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

    var authState: AuthState = .signedOut
    var loginResult: Result<AccountSnapshot, APIError> = .success(AccountSnapshot(username: "admin", tenantID: 1))
    var profileResult: Result<MeInfo, APIError>?
    var serverConfigurationResult: Result<ServerConfig, APIError> = .success(FakeAuth.devConfig)
    var saveResult: Result<Void, APIError> = .success(())

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

    func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError> {
        savedConfigs.append(serverConfiguration)
        return saveResult
    }
}

/// Pages served in the order the test queues them. A request nobody queued answers as a decoding failure
/// and still shows up in `requests`, so an extra call reads as a wrong number rather than a crash.
final class FakeAgents: AgentCataloging, @unchecked Sendable {
    private(set) var requests: [(num: Int, size: Int)] = []
    var replies: [Result<Page<AgentSummary>, APIError>] = []

    func page(num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        requests.append((num: num, size: size))
        return replies.isEmpty ? .failure(.decoding) : replies.removeFirst()
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
        let bodies = try records.map { try JSONSerialization.data(withJSONObject: $0) }
            .map { String(decoding: $0, as: UTF8.self) }
            .joined(separator: ",")
        let body = #"{"pageNum":\#(pageNum),"pageSize":\#(pageSize),"total":\#(total ?? records.count),"records":[\#(bodies)]}"#
        return try JSONDecoder().decode(Page<AgentSummary>.self, from: Data(body.utf8))
    }
}

extension AgentSummary {
    static func stub(_ fields: [String: Any]) throws -> AgentSummary {
        let data = try JSONSerialization.data(withJSONObject: fields)
        return try JSONDecoder().decode(AgentSummary.self, from: data)
    }
}
