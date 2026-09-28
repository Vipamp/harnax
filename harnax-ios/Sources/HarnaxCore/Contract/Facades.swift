import Foundation

/// What the signed-in account looks like to the UI, assembled from the login payload.
public struct AccountSnapshot: Codable, Equatable, Sendable {
    public let username: String
    public let nickname: String?
    public let email: String?
    public let tenantID: Int64?
    public let tenantName: String?
    public let isAdministrator: Bool

    public init(
        username: String,
        nickname: String? = nil,
        email: String? = nil,
        tenantID: Int64? = nil,
        tenantName: String? = nil,
        isAdministrator: Bool = false
    ) {
        self.username = username
        self.nickname = nickname
        self.email = email
        self.tenantID = tenantID
        self.tenantName = tenantName
        self.isAdministrator = isAdministrator
    }

    /// Nickname when the server supplied a non-empty one, otherwise the login name.
    public var displayName: String {
        guard let nickname, !nickname.isEmpty else { return username }
        return nickname
    }

    public static func live(from response: LoginResponse) -> AccountSnapshot {
        let user = response.userInfo
        let tenant = response.tenants?.first(where: { $0.id == response.currentTenantId }) ?? response.tenants?.first
        return AccountSnapshot(
            username: user?.username ?? "",
            nickname: user?.nickname,
            email: user?.email,
            tenantID: response.currentTenantId,
            tenantName: tenant?.name,
            isAdministrator: user?.isAdministrator ?? false
        )
    }

    /// Cold restore has no login response to read, so the identity card is filled from `me`. That payload
    /// answers no tenant fields at all — `mergingWith` folds the cached ones back in.
    public static func live(from me: MeInfo) -> AccountSnapshot {
        AccountSnapshot(
            username: me.username ?? "",
            nickname: me.nickname,
            email: me.email,
            tenantID: me.tenantId,
            tenantName: nil,
            isAdministrator: me.isAdministrator
        )
    }

    public func store(in store: SecretStoring) throws {
        let data = try JSONEncoder().encode(self)
        try store.setValue(String(decoding: data, as: UTF8.self), for: .cachedAccount)
    }

    /// A blob this side cannot read is treated as absent — the caller refetches the profile either way.
    public static func load(from store: SecretStoring) throws -> AccountSnapshot? {
        guard let raw = try store.value(for: .cachedAccount), let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(AccountSnapshot.self, from: data)
    }

    /// Fills the fields `me` does not answer with — the tenant has no key there at all, and an omitted
    /// username would otherwise blank the card after one refresh.
    public func mergingWith(_ previous: AccountSnapshot?) -> AccountSnapshot {
        guard let previous else { return self }
        return AccountSnapshot(
            username: username.isEmpty ? previous.username : username,
            nickname: nickname ?? previous.nickname,
            email: email ?? previous.email,
            tenantID: tenantID ?? previous.tenantID,
            tenantName: tenantName ?? previous.tenantName,
            isAdministrator: isAdministrator
        )
    }

    /// The one rule behind edit and delete on every management screen: an administrator reaches any row,
    /// anyone else only the rows they created. Whether the row is public plays no part, and the
    /// enable/disable switch is not gated by this either — both match the web console
    /// (`harnax-webui/src/utils/permissionUtil.ts:111-123`).
    ///
    /// A row that names no creator is not manageable: the console would answer `undefined === undefined`
    /// and hand out permission to an unattributed row.
    public func canManage(creator: String?) -> Bool {
        if isAdministrator { return true }
        guard let creator = hxPresented(creator), !username.isEmpty else { return false }
        return creator == username
    }
}

/// `unknown` is the restore-in-progress state, kept separate from `signedOut` so the app does not flash
/// the login screen while the keychain is being read.
public enum AuthState: Equatable, Sendable {
    case unknown
    case signedOut
    case signedIn(AccountSnapshot)

    public var account: AccountSnapshot? {
        if case let .signedIn(account) = self { return account }
        return nil
    }
}

/// The seam the feature screens are built against. Everything below it — transport, keychain, refresh —
/// stays invisible to the views, which makes them fakeable in tests.
public protocol AuthFlowing: Sendable {
    func state() async -> AuthState
    func login(username: String, password: String) async -> Result<AccountSnapshot, APIError>
    func logout() async
    func profile() async -> Result<MeInfo, APIError>
    func serverConfiguration() async -> Result<ServerConfig, APIError>
    func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError>
}

/// Everything the agent card screen does, read and write.
///
/// There is no per-row detail call: `GET /api/admin/agents/page` already fills the four binding lists and
/// the session list for each row (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentServiceImpl.kt:265-395`),
/// which is where the card, its drill-down sheets and a later edit form all read from.
///
/// `name` is a keyword the backend matches with `LIKE`, `status` the raw 0/1 flag; both are optional
/// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/AgentController.kt:36-55`).
public protocol AgentCataloging: Sendable {
    func page(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError>
    func setStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    func delete(id: Int64) async -> Result<EmptyResponse, APIError>
    /// Who has this agent's configuration loaded right now. A failure here must never be rendered as an
    /// empty list — the two mean opposite things (`harnax-webui/src/pages/team/index.tsx:147-158`).
    func relatedSessions(id: Int64) async -> Result<[RelatedSession], APIError>
}

/// The team surface, with the same four capabilities. One client conforms to both protocols, so the team
/// methods carry their domain in the name — identical signatures with a different return type could not
/// sit on the same type.
///
/// Backend: `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/controller/TeamController.kt:33-125`
public protocol TeamCataloging: Sendable {
    func teamPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<TeamSummary>, APIError>
    func setTeamStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError>
    func deleteTeam(id: Int64) async -> Result<EmptyResponse, APIError>
    /// Teams only ever produce `sourceType == "session"` rows
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/TeamServiceImpl.kt:207-220`).
    func teamRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError>
}
