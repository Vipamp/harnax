#if DEBUG
import Foundation
import HarnaxCore
import HarnaxFeatures
import HarnaxKit
import SwiftUI

/// Screens the appearance walkthrough can reach without a finger.
///
/// The simulator accepts no synthetic input, so the tab bar, the two pushed rows and the list's three
/// states are picked at launch:
/// `xcrun simctl launch <device> com.agnetix.harnax.ios -FIXTURE agents`.
enum HarnaxDebugScreen: String {
    case login
    case server
    case serverSheet
    case agents
    case agentsEmpty
    case agentsFailed
    case agentBindings
    case teams
    case teamBindings
    case refreshSheet
    case me
    case appearance
    case soon

    static var current: HarnaxDebugScreen? {
        HarnaxDebugLaunch.value(for: "FIXTURE").flatMap(HarnaxDebugScreen.init(rawValue:))
    }

    fileprivate var tab: HarnaxTab {
        switch self {
        case .me: return .me
        case .soon: return .chat
        default: return .agents
        }
    }

    fileprivate var isSignedIn: Bool { self != .login }
}

/// Launch arguments the walkthrough varies per screenshot: `-FIXTURE <screen> -THEME <system|light|dark>
/// -LANG <system|en|zh-Hans>`. The two preferences are written exactly as the Settings screen writes them,
/// so a capture exercises the real theme and catalogue paths.
enum HarnaxDebugLaunch {
    static func value(for flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-\(flag)"), index + 1 < arguments.count else { return nil }
        return arguments[index + 1]
    }

    static func applyPreferences() {
        if let raw = value(for: "THEME"), let mode = ThemeMode(rawValue: raw) {
            UserDefaults.standard.set(mode.rawValue, forKey: ThemeMode.storageKey)
        }
        if let raw = value(for: "LANG"), let language = HarnaxLanguage(rawValue: raw) {
            HarnaxCatalog.shared.language = language
        }
    }
}

enum HarnaxDebugEntrance {
    @MainActor static func root() -> some View {
        guard let screen = HarnaxDebugScreen.current else {
            return AnyView(HarnaxRootView(dependencies: .live()))
        }
        HarnaxDebugLaunch.applyPreferences()
        return AnyView(HarnaxDebugView(screen: screen))
    }
}

@MainActor
struct HarnaxDebugView: View {
    let screen: HarnaxDebugScreen
    @StateObject private var model: AppModel

    init(screen: HarnaxDebugScreen) {
        self.screen = screen
        let model = AppModel(dependencies: HarnaxDependencies(
            auth: HarnaxDebugAuth(screen: screen),
            agents: HarnaxDebugAgents(screen: screen),
            teams: HarnaxDebugTeams(),
            sessionRefresher: HarnaxDebugRefresher()
        ))
        model.tab = screen.tab
        _model = StateObject(wrappedValue: model)
    }

    @ViewBuilder var body: some View {
        switch screen {
        case .server, .appearance:
            // Both rows live behind `Me`; their own stack is rebuilt here so a screenshot can frame them.
            NavigationStack {
                if screen == .appearance {
                    AppearanceSettingsView()
                } else {
                    ServerAddressView(auth: model.dependencies.auth)
                }
            }
            .harnaxThemed()
        case .teams:
            // The team column sits behind a segment on the real tab; framed on its own it captures without
            // a finger to switch segments.
            NavigationStack {
                TeamListView(
                    teams: model.dependencies.teams,
                    sessionRefresher: model.dependencies.sessionRefresher,
                    account: model.account
                )
            }
            .harnaxThemed()
        case .agentBindings, .teamBindings, .refreshSheet:
            // Presented surfaces get their own hosting, so this is the capture that can show whether the
            // app-level theme reaches them.
            Color.hx(.background)
                .ignoresSafeArea()
                .sheet(isPresented: .constant(true)) { presented }
                .harnaxThemed()
        case .serverSheet:
            Color.hx(.background)
                .ignoresSafeArea()
                .sheet(isPresented: .constant(true)) {
                    NavigationStack { ServerAddressView(auth: model.dependencies.auth) }
                }
                .harnaxThemed()
        default:
            HarnaxRootView(model: model)
        }
    }

    @ViewBuilder
    private var presented: some View {
        switch screen {
        case .agentBindings:
            if let agent = HarnaxDebugRecord.agent {
                AgentBindingsSheet(agent: agent)
            }
        case .teamBindings:
            if let team = HarnaxDebugRecord.team {
                TeamBindingsSheet(team: team)
            }
        case .refreshSheet:
            if let target = HarnaxDebugRecord.refreshTarget {
                HXSessionRefreshSheet(target: target, refresher: HarnaxDebugRefresher())
            }
        default:
            EmptyView()
        }
    }
}

struct HarnaxDebugAuth: AuthFlowing {
    let screen: HarnaxDebugScreen

    private static let account = AccountSnapshot(
        username: "admin",
        nickname: "Admin Console",
        email: "admin@agnetix.dev",
        tenantID: 1,
        tenantName: "Primary Tenant",
        isAdministrator: true
    )

    func state() async -> AuthState {
        screen.isSignedIn ? .signedIn(Self.account) : .signedOut
    }

    func login(username: String, password: String) async -> Result<AccountSnapshot, APIError> {
        .success(Self.account)
    }

    func logout() async {}

    /// `MeView` reads only the outcome, but a decode failure here would paint an error banner on the card.
    func profile() async -> Result<MeInfo, APIError> {
        guard let data = Self.profileJSON.data(using: .utf8),
              let info = try? JSONDecoder().decode(MeInfo.self, from: data)
        else { return .failure(.decoding) }
        return .success(info)
    }

    func serverConfiguration() async -> Result<ServerConfig, APIError> {
        guard let config = try? ServerConfig(
            adminBaseURL: ServerConfig.devAdminBaseURL,
            routerBaseURL: ServerConfig.devRouterBaseURL
        ) else { return .failure(.invalidServerConfig(ServerConfig.devAdminBaseURL)) }
        return .success(config)
    }

    func save(serverConfiguration: ServerConfig) async -> Result<Void, APIError> {
        .success(())
    }

    private static let profileJSON = """
    {"id":1,"username":"admin","nickname":"Admin Console","email":"admin@agnetix.dev",\
    "phone":"","isAdmin":1,"tenantId":1,"authMode":"cli-login","expiresAt":1800000000}
    """
}

struct HarnaxDebugAgents: AgentCataloging {
    let screen: HarnaxDebugScreen

    func page(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        if screen == .agentsFailed { return .failure(.offline) }
        let json = screen == .agentsEmpty ? HarnaxDebugPages.emptyJSON : HarnaxDebugPages.agentsJSON
        guard let data = json.data(using: .utf8),
              let page = try? JSONDecoder().decode(Page<AgentSummary>.self, from: data)
        else { return .failure(.decoding) }
        return .success(page)
    }

    func setStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .success(EmptyResponse()) }

    func delete(id: Int64) async -> Result<EmptyResponse, APIError> { .success(EmptyResponse()) }

    func relatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        HarnaxDebugPages.decodeRelatedSessions(HarnaxDebugPages.relatedJSON)
    }
}

struct HarnaxDebugTeams: TeamCataloging {
    func teamPage(name: String?, status: Int?, num: Int, size: Int) async -> Result<Page<TeamSummary>, APIError> {
        guard let data = HarnaxDebugPages.teamsJSON.data(using: .utf8),
              let page = try? JSONDecoder().decode(Page<TeamSummary>.self, from: data)
        else { return .failure(.decoding) }
        return .success(page)
    }

    func setTeamStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .success(EmptyResponse()) }

    func deleteTeam(id: Int64) async -> Result<EmptyResponse, APIError> { .success(EmptyResponse()) }

    func teamRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> {
        HarnaxDebugPages.decodeRelatedSessions(HarnaxDebugPages.relatedJSON)
    }
}

struct HarnaxDebugRefresher: SessionRefreshing {
    func refreshSessions(_ ids: [String]) async -> Result<[SessionRefreshOutcome], APIError> {
        HarnaxDebugPages.decodeOutcomes(HarnaxDebugPages.outcomesJSON)
    }
}

/// The single row the drill-down captures open on. Decoded once from the same fixture the list serves, so
/// a screenshot cannot show a row the list would never have produced.
@MainActor
enum HarnaxDebugRecord {
    private static let debugAgents = HarnaxDebugAgents(screen: .agents)

    static var agent: AgentSummary? { rows(AgentSummary.self, HarnaxDebugPages.agentsJSON).first }
    static var team: TeamSummary? { rows(TeamSummary.self, HarnaxDebugPages.teamsJSON).first }

    static var refreshTarget: SessionRefreshTarget? {
        guard let agent, let id = agent.id else { return nil }
        let catalog = debugAgents
        return SessionRefreshTarget(
            id: id,
            name: agent.title ?? "",
            source: .agent
        ) {
            await catalog.relatedSessions(id: id)
        }
    }

    private static func rows<T: Decodable>(_ type: T.Type, _ json: String) -> [T] {
        guard let data = json.data(using: .utf8),
              let page = try? JSONDecoder().decode(Page<T>.self, from: data)
        else { return [] }
        return page.records
    }
}

private enum HarnaxDebugPages {
    static let emptyJSON = """
    {"pageNum":1,"pageSize":20,"total":0,"records":[]}
    """

    static func decodeRelatedSessions(_ json: String) -> Result<[RelatedSession], APIError> {
        guard let data = json.data(using: .utf8),
              let rows = try? JSONDecoder().decode([RelatedSession].self, from: data)
        else { return .failure(.decoding) }
        return .success(rows)
    }

    static func decodeOutcomes(_ json: String) -> Result<[SessionRefreshOutcome], APIError> {
        guard let data = json.data(using: .utf8),
              let rows = try? JSONDecoder().decode([SessionRefreshOutcome].self, from: data)
        else { return .failure(.decoding) }
        return .success(rows)
    }

    /// One card per variant the list can actually receive: shared, disabled, a row with nothing but a
    /// name, and a row whose `createTime` the backend did not format. The key names are the DTO's own —
    /// a made-up shape decodes to a card with counts and no names.
    static let agentsJSON = """
    {"pageNum":1,"pageSize":20,"total":47,"records":[
      {"id":11,"name":"Support Desk","description":"Answers product questions in the help channel and opens a ticket when it cannot.","modelId":3,"modelName":"qwen3.7-max","status":1,"isPublic":1,"creator":"admin","createTime":"2026-09-12 10:24:31","sessionCount":128,"sessionList":[{"id":881,"title":"Weekly digest","sessionId":"sess-8842","sessionDescription":"web · 3 hours ago"},{"id":872,"sessionId":"sess-8790"}],"mcpList":[{"mcpId":4,"mcpName":"amap-maps","mcpDescription":"Maps and routing","envBindings":[{"envKey":"AMAP_KEY","envValue":"******","envVarId":9,"envVarName":"amap-key"}]}],"skillList":[{"repositoryId":2,"repositoryName":"qoder-skills","skillId":5,"skillName":"Glossary","skillDescription":"Domain terms"},{"repositoryId":2,"skillId":7,"skillName":"Polish"}],"toolList":[{"toolId":2,"toolName":"webSearch","toolDisplayName":"Web Search","toolDisplayNameZh":"联网搜索","toolDescription":"Public web lookup","needConfirm":true,"envBindings":[{"envKey":"SEARCH_QUOTA","customValue":"50"}]},{"toolId":3,"toolName":"writeFile","toolDisplayName":"write-file"},{"toolId":9,"toolName":"   ","toolDisplayName":""}],"cliList":[{"cliId":1,"cliName":"harnax-cli","cliDescription":"Cluster ops","version":"1.30.0","skillList":[{"skillId":21,"skillName":"Notice parser"}]}]},
      {"id":12,"name":"Release Manager","description":"Tracks the release train and pings owners before a cut-off slips.","modelId":3,"modelName":"qwen3.7-max","status":1,"isPublic":0,"creator":"liwei","createTime":"2026-09-08 18:02:09","sessionCount":42,"mcpList":[],"skillList":[{"skillId":6,"skillName":"Changelog"}],"toolList":[],"cliList":[]},
      {"id":13,"name":"Contract Review","description":"Long-form review that has to wrap over two lines at a small width without breaking the badge row above it.","modelId":8,"modelName":"deepseek-v4","status":0,"isPublic":0,"creator":"zhaomin","createTime":"2026-08-19 14:47:55","sessionCount":7,"mcpList":[],"skillList":[],"toolList":[],"cliList":[]},
      {"id":14,"name":null,"description":null,"modelName":"glm-5","status":null,"isPublic":null,"creator":null,"createTime":"not-a-date","sessionCount":null,"mcpList":[],"skillList":[],"toolList":[],"cliList":[]}
    ]}
    """

    /// A lead whose model reference outlives the model row, one disabled member and one gone skill.
    static let teamsJSON = """
    {"pageNum":1,"pageSize":20,"total":2,"records":[
      {"id":5,"name":"Research Desk","description":"From exchange notices to a valuation report.","systemPrompt":"","modelId":7,"modelName":"qwen3.7-max","skillList":[{"skillId":12,"skillName":"Notice parser","skillDescription":"Reads exchange filings","repositoryId":2,"repositoryName":"qoder-skills","skillAvailable":true},{"skillId":15,"skillName":"Valuation sheet","skillAvailable":false}],"memberList":[{"agentId":11,"agentName":"Data Fetch","agentDescription":"Pulls quotes and filings","delegationDescription":"Owns the data path","agentStatus":1,"agentAvailable":true},{"agentId":12,"agentName":"Valuation","agentDescription":"Values the positions","agentStatus":0,"agentAvailable":false}],"status":1,"isPublic":0,"tenantId":1,"creator":"admin","createTime":"2026-09-20 09:12:04","updateTime":"2026-09-26 15:30:00"},
      {"id":6,"name":"Stopped Desk","description":null,"systemPrompt":"","modelId":0,"modelName":null,"skillList":[],"memberList":[{"agentId":13,"agentName":"","agentStatus":1,"agentAvailable":true}],"status":0,"isPublic":1,"tenantId":1,"creator":"liwei","createTime":"2026-09-01 08:00:00"}
    ]}
    """

    /// Both row kinds the refresh endpoint answers with, plus the trimmed long id.
    static let relatedJSON = """
    [{"sessionId":"sess-8842","sourceType":"channel","sourceName":"Support WeChat (wechat)"},
     {"sessionId":"sess-8790-and-its-own-identifier-is-long-enough-to-truncate","sourceType":"session","sourceName":"","agentName":"Support Desk"}]
    """

    /// One clean line and one refusal, so the panel is captured in the shape a partial failure leaves it in.
    static let outcomesJSON = """
    [{"sessionId":"sess-8842","success":true},
     {"sessionId":"sess-8790-and-its-own-identifier-is-long-enough-to-truncate","success":false,"error":"No system API key available"}]
    """
}
#endif
