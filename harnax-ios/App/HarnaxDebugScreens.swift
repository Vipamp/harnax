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
    case context
    case contextTools
    case contextToolDetail
    case contextMcp
    case contextMcpDetail
    case contextSkill
    case contextSkillTable
    case contextSkillDetail
    case contextCli
    case contextCliDetail
    case me
    case appearance
    case soon

    static var current: HarnaxDebugScreen? {
        HarnaxDebugLaunch.value(for: "FIXTURE").flatMap(HarnaxDebugScreen.init(rawValue:))
    }

    fileprivate var tab: HarnaxTab {
        if rawValue.hasPrefix("context") { return .context }
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
            sessionRefresher: HarnaxDebugRefresher(),
            models: HarnaxDebugModels(),
            tools: HarnaxDebugTools(),
            mcp: HarnaxDebugMcp(),
            skills: HarnaxDebugSkills(),
            clis: HarnaxDebugClis()
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
        case .context:
            // The segment row is this tab's whole navigation shape, so the shell is framed on its own.
            NavigationStack {
                ContextView(dependencies: model.dependencies, account: model.account)
            }
            .harnaxThemed()
        case .contextTools, .contextMcp, .contextSkill, .contextCli:
            // The other four columns sit behind a segment. Framed alone, each captures without a finger to
            // switch, the same way the team column does.
            NavigationStack {
                switch screen {
                case .contextTools:
                    ToolListView(tools: model.dependencies.tools)
                case .contextMcp:
                    McpListView(mcp: model.dependencies.mcp)
                case .contextSkill:
                    SkillHomeView(skills: model.dependencies.skills)
                case .contextCli:
                    CliListView(
                        clis: model.dependencies.clis,
                        sessionRefresher: model.dependencies.sessionRefresher
                    )
                default:
                    EmptyView()
                }
            }
            .harnaxThemed()
        case .contextMcpDetail, .contextSkillTable, .contextSkillDetail:
            NavigationStack { pushed }
                .harnaxThemed()
        case .agentBindings, .teamBindings, .refreshSheet, .contextToolDetail, .contextCliDetail:
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
        case .contextToolDetail:
            if let tool = HarnaxDebugRecord.tool {
                ToolDetailSheet(tools: model.dependencies.tools, tool: tool)
            }
        case .contextCliDetail:
            if let cli = HarnaxDebugRecord.cli {
                CliDetailSheet(clis: model.dependencies.clis, cli: cli)
            }
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var pushed: some View {
        switch screen {
        case .contextMcpDetail:
            if let id = HarnaxDebugRecord.mcpServer?.id {
                McpDetailView(mcp: model.dependencies.mcp, authorizer: SystemBrowserAuthorizer(), id: id)
            }
        case .contextSkillTable:
            if let source = HarnaxDebugRecord.skillSource {
                SkillTableView(sourceID: source.id, sourceName: hxPresented(source.title), skills: model.dependencies.skills)
            }
        case .contextSkillDetail:
            if let id = HarnaxDebugRecord.skill?.id {
                SkillDetailView(id: id, skills: model.dependencies.skills)
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
        HarnaxDebugPages.decode(HarnaxDebugPages.relatedJSON, [RelatedSession].self)
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
        HarnaxDebugPages.decode(HarnaxDebugPages.relatedJSON, [RelatedSession].self)
    }
}

struct HarnaxDebugRefresher: SessionRefreshing {
    func refreshSessions(_ ids: [String]) async -> Result<[SessionRefreshOutcome], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.outcomesJSON, [SessionRefreshOutcome].self)
    }
}

/// The five context-tab catalogs.
///
/// Each answers the reads its own captures perform — the first page, plus the one detail read a drill-down
/// asks for as it appears — and fails everything else with `.offline`. A capture that ever strayed past its
/// screen would then show an error banner rather than a list that looked like it had acted.
struct HarnaxDebugModels: ModelCataloging {
    func providerPage(
        name: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ModelProviderSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.providersJSON, Page<ModelProviderSummary>.self)
    }

    /// Per id, so a card never shows counts its own row could not have produced.
    func providerStats(id: Int64) async -> Result<ModelProviderStats, APIError> {
        .success(id == 3
            ? ModelProviderStats(totalModels: 5, enabledModels: 4, disabledModels: 1)
            : ModelProviderStats(totalModels: 2, enabledModels: 0, disabledModels: 2))
    }

    func setProviderStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteProvider(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func saveProvider(
        id: Int64?,
        request: ModelProviderSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func testProvider(id: Int64) async -> Result<Bool, APIError> { .failure(.offline) }

    func modelPage(
        providerID: Int64,
        name: String?,
        status: Int?,
        tags: [String],
        num: Int,
        size: Int
    ) async -> Result<Page<ModelSummary>, APIError> { .failure(.offline) }

    func setModelStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteModel(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func saveModel(
        id: Int64?,
        request: ModelSaveRequest
    ) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
}

struct HarnaxDebugTools: ToolCataloging {
    func toolPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ToolSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.toolsJSON, Page<ToolSummary>.self)
    }

    func toolDetail(id: Int64) async -> Result<ToolSummary, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.toolDetailJSON, ToolSummary.self)
    }
}

struct HarnaxDebugMcp: McpCataloging {
    func mcpPage(
        keyword: String?,
        status: Int?,
        type: String?,
        num: Int,
        size: Int
    ) async -> Result<Page<McpServerRow>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.mcpServersJSON, Page<McpServerRow>.self)
    }

    func mcpServer(id: Int64) async -> Result<McpServerRow, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.mcpDetailJSON, McpServerRow.self)
    }

    func mcpTools(id: Int64) async -> Result<[McpToolRow], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.mcpToolsJSON, [McpToolRow].self)
    }

    func mcpOAuthStatus(id: Int64) async -> Result<McpOAuthStatus, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.mcpOAuthStatusJSON, McpOAuthStatus.self)
    }

    func createMCPServer(_ draft: McpServerDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateMCPServer(
        id: Int64,
        patch: McpServerPatch
    ) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func setMcpStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func mcpRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(.offline) }

    func deleteMCPServer(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func testMcpConnectivity(id: Int64) async -> Result<Bool, APIError> { .failure(.offline) }

    func revokeMcpOAuth(id: Int64) async -> Result<McpOAuthRevokeResult, APIError> { .failure(.offline) }

    func mcpAuthorizeURL(
        id: Int64,
        scope: String?
    ) async -> Result<McpOAuthAuthorization, APIError> { .failure(.offline) }
}

struct HarnaxDebugSkills: SkillCataloging {
    func sourcePage(
        name: String?,
        sourceType: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillSourceSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.skillSourcesJSON, Page<SkillSourceSummary>.self)
    }

    func skillPage(
        name: String?,
        repositoryID: Int64?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SkillItem>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.skillsJSON, Page<SkillItem>.self)
    }

    func skill(id: Int64) async -> Result<SkillItem, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.skillDetailJSON, SkillItem.self)
    }

    func source(id: Int64) async -> Result<SkillSourceSummary, APIError> { .failure(.offline) }

    func preview(sourceID: Int64) async -> Result<[SkillPreviewItem], APIError> { .failure(.offline) }

    func install(
        sourceID: Int64,
        names: [String]?
    ) async -> Result<SkillInstallOutcome, APIError> { .failure(.offline) }

    func createSource(
        _ payload: SkillSourceCreatePayload
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(.offline) }

    func updateSource(
        id: Int64,
        _ payload: SkillSourceUpdatePayload
    ) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteSource(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func setSourceStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func uploadSource(
        name: String,
        fileName: String,
        payload: Data
    ) async -> Result<SkillSourceInstallResult, APIError> { .failure(.offline) }

    func setSkillStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
}

struct HarnaxDebugClis: CliCataloging {
    func cliPage(
        name: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<CliSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.cliJSON, Page<CliSummary>.self)
    }

    func cliDetail(id: Int64) async -> Result<CliSummary, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.cliDetailJSON, CliSummary.self)
    }

    func cliRelatedAgents(id: Int64) async -> Result<[RelatedAgent], APIError> { .failure(.offline) }

    func cliRelatedSessions(id: Int64) async -> Result<[RelatedSession], APIError> { .failure(.offline) }

    func setCliStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
}

/// The single row the drill-down captures open on. Decoded once from the same fixture the list serves, so
/// a screenshot cannot show a row the list would never have produced.
@MainActor
enum HarnaxDebugRecord {
    private static let debugAgents = HarnaxDebugAgents(screen: .agents)

    static var agent: AgentSummary? { rows(AgentSummary.self, HarnaxDebugPages.agentsJSON).first }
    static var team: TeamSummary? { rows(TeamSummary.self, HarnaxDebugPages.teamsJSON).first }
    static var tool: ToolSummary? { rows(ToolSummary.self, HarnaxDebugPages.toolsJSON).first }
    static var cli: CliSummary? { rows(CliSummary.self, HarnaxDebugPages.cliJSON).first }
    static var mcpServer: McpServerRow? { rows(McpServerRow.self, HarnaxDebugPages.mcpServersJSON).first }
    static var skillSource: SkillSourceSummary? { rows(SkillSourceSummary.self, HarnaxDebugPages.skillSourcesJSON).first }
    static var skill: SkillItem? { rows(SkillItem.self, HarnaxDebugPages.skillsJSON).first }

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

    /// A fixture that will not decode against its DTO answers as a failed read, which is what the same
    /// drift would do against the real server.
    static func decode<T: Decodable>(_ json: String, _ type: T.Type) -> Result<T, APIError> {
        guard let data = json.data(using: .utf8), let value = try? JSONDecoder().decode(type, from: data)
        else { return .failure(.decoding) }
        return .success(value)
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

    /// A shared enabled provider with a masked credential, and a private stopped one whose name column is
    /// blank and whose `description`/`apiKey`/`baseUrl` the server dropped as nulls.
    static let providersJSON = """
    {"pageNum":1,"pageSize":20,"total":9,"records":[
      {"id":3,"type":"dashscope","name":"阿里云百炼","description":"生产环境主账号","apiKey":"sk****3f9c","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","status":1,"isPublic":1,"creator":"heqingsong","createTime":"2026-08-30 09:14:02","updateTime":"2026-09-18 16:41:07"},
      {"id":4,"type":"openai","name":"   ","status":0,"isPublic":0,"creator":"luwen","createTime":"2026-09-01 11:00:00","updateTime":"2026-09-01 11:00:00"}
    ]}
    """

    /// One tool with a masked secret parameter and a Chinese display name, one that only carries its key
    /// names and no `envParams` block at all.
    static let toolsJSON = """
    {"pageNum":1,"pageSize":20,"total":31,"records":[
      {"id":1,"name":"send_email","displayName":"Send Email","displayNameZh":"发送邮件","description":"按收件人邮箱发送已渲染的正文","beanName":"emailTool","methodName":"send","envParams":[{"id":3,"envParamName":"SMTP_PASSWORD","description":"SMTP 登录口令","required":true,"secret":true,"defaultValue":"abc****wxyz"},{"id":4,"envParamName":"SMTP_TIMEOUT_SECONDS","required":false,"secret":false,"defaultValue":"30"}],"readOnly":1,"needConfirm":1,"isRequired":0,"requiredEnvParamKeys":["SMTP_PASSWORD"],"status":1,"creator":"heqingsong","createTime":"2026-09-12 10:20:30","updateTime":"2026-09-28 08:01:12"},
      {"id":2,"name":"web_search","displayName":"Web Search","envParams":[],"requiredEnvParamKeys":["SEARCH_QUOTA","SEARCH_REGION"],"status":1}
    ]}
    """

    /// The drill-down row: same `id` as the card it opened from, with the implementation columns the page
    /// does not need.
    static let toolDetailJSON = """
    {"id":1,"name":"send_email","displayName":"Send Email","displayNameZh":"发送邮件","description":"按收件人邮箱发送已渲染的正文","beanName":"emailTool","methodName":"send","envParams":[{"id":3,"envParamName":"SMTP_PASSWORD","description":"SMTP 登录口令","required":true,"secret":true,"defaultValue":"******"}],"readOnly":1,"needConfirm":1,"isRequired":1,"requiredEnvParamKeys":["SMTP_PASSWORD"],"status":1,"creator":"heqingsong","createTime":"2026-09-12 10:20:30","updateTime":"2026-10-02 19:44:05"}
    """

    /// One of each transport, one of each auth kind, and the legacy stdio row the create form can no
    /// longer produce. Row order is the detail capture's: it opens on the OAuth one, which has the most to
    /// show there.
    static let mcpServersJSON = """
    {"pageNum":1,"pageSize":20,"total":3,"records":[
      {"id":8,"name":"github-enterprise","description":"企业 GitHub 的仓库与工单","type":"sse","url":"https://github-mcp.example.com/sse","authType":"OAUTH2","oauthConfig":{"authorizationServer":"https://github.example.com","scopes":["repo","read:user"],"resourceIndicator":true},"status":1,"isPublic":1,"creator":"heqingsong","createTime":"2026-09-15 10:02:11","updateTime":"2026-09-27 20:41:33","headers":[],"envParams":[{"id":21,"envParamName":"GITHUB_ORG","description":"默认组织","required":true,"secret":false,"defaultValue":"agnetix"}]},
      {"id":7,"name":"amap-maps","description":"地图与路径规划","type":"streamablehttp","url":"https://mcp.amap.com/mcp","authType":"STATIC_HEADER","status":1,"isPublic":0,"creator":"liwei","createTime":"2026-09-04 08:20:00","updateTime":"2026-09-20 12:05:47","headers":[{"key":"X-API-KEY","value":"sk-****c1d9","secret":true},{"key":"X-TRACE","value":"on","secret":false}]},
      {"id":9,"name":"   ","type":"stdio","command":"npx -y @modelcontextprotocol/server-everything","status":0,"isPublic":0,"createTime":"2026-08-02 17:31:00","updateTime":"2026-08-02 17:31:00"}
    ]}
    """

    /// The same row the card showed, as the single-object answer of `GET /api/admin/mcp/{id}`.
    static let mcpDetailJSON = """
    {"id":8,"name":"github-enterprise","description":"企业 GitHub 的仓库与工单","type":"sse","url":"https://github-mcp.example.com/sse","authType":"OAUTH2","oauthConfig":{"authorizationServer":"https://github.example.com","scopes":["repo","read:user"],"resourceIndicator":true},"status":1,"isPublic":1,"creator":"heqingsong","createTime":"2026-09-15 10:02:11","updateTime":"2026-09-27 20:41:33","headers":[],"envParams":[{"id":21,"envParamName":"GITHUB_ORG","description":"默认组织","required":true,"secret":false,"defaultValue":"agnetix"}]}
    """

    /// Flattened `inputSchema`: two tools with parameters, one with none, and one parameter whose
    /// description the upstream left blank.
    static let mcpToolsJSON = """
    [{"name":"search_repositories","parameters":[{"name":"query","type":"string","description":"搜索关键词"},{"name":"per_page","type":"integer","description":""}]},
     {"name":"get_issue","parameters":[{"name":"owner","type":"string","description":"仓库所有者"},{"name":"number","type":"integer","description":"工单编号"}]},
     {"name":"ping","parameters":[]}]
    """

    /// A live grant. `lastError` is the one key the server drops here, so nothing shows in its row.
    static let mcpOAuthStatusJSON = """
    {"authorized":true,"status":"ACTIVE","scopes":["repo","read:user"],"accessExpiresAt":"2026-09-28 18:40:12","lastRefreshedAt":"2026-09-26 09:15:44"}
    """

    /// A GIT source that synced and reported a partial run, and an NPM one that never synced at all.
    /// Every column of this DTO carries a non-null default, so both rows name all of them.
    static let skillSourcesJSON = """
    {"pageNum":1,"pageSize":20,"total":2,"records":[
      {"id":2,"name":"qoder-skills","sourceType":"GIT","sourceConfig":{"url":"https://github.com/qoder/skills.git","branch":"main"},"version":"0.4.1","url":"https://github.com/qoder/skills.git","branch":"main","description":"平台内置技能库","status":1,"isPublic":1,"creator":"heqingsong","createTime":"2026-08-21 09:00:00","updateTime":"2026-09-27 21:14:03","lastSyncStatus":"PARTIAL","lastSyncTime":"2026-09-27 21:14:03","lastSyncDetail":{"saved":6,"installed":["Glossary"],"updated":["Polish"],"failed":[{"name":"Broken","reason":"SKILL.md 缺少 frontmatter"}],"flagged":[{"name":"Secrets","reasons":["hardcoded-token"]}],"stale":["Legacy"]},"enabledSkillCount":6},
      {"id":5,"name":"ops-toolkit","sourceType":"NPM","sourceConfig":{"packageName":"@agnetix/ops-skills","registry":"https://registry.npmjs.org"},"version":"1.0.0","url":"","branch":"","description":"","status":0,"isPublic":0,"creator":"luwen","createTime":"2026-09-19 15:44:02","updateTime":"2026-09-19 15:44:02","enabledSkillCount":0}
    ]}
    """

    /// One enabled skill bound to two agents and one stopped skill bound to a team — the binding counts
    /// are what make a row inert, so both kinds have to be visible in one page.
    static let skillsJSON = """
    {"pageNum":1,"pageSize":20,"total":6,"records":[
      {"id":5,"name":"Glossary","repositoryId":2,"repositoryName":"qoder-skills","repositoryUrl":"https://github.com/qoder/skills.git","repositoryBranch":"main","description":"领域术语表，回答前先对齐口径","status":1,"boundAgentCount":2,"boundTeamCount":0,"isPublic":1,"creator":"heqingsong","createTime":"2026-08-21 09:07:11","updateTime":"2026-09-27 21:14:03"},
      {"id":7,"name":"Polish","repositoryId":2,"repositoryName":"qoder-skills","description":"按品牌口径重写草稿","status":0,"boundAgentCount":0,"boundTeamCount":1,"isPublic":0,"creator":"liwei","createTime":"2026-09-02 11:20:00","updateTime":"2026-09-27 21:14:03"}
    ]}
    """

    /// The detail read adds the two columns the page never selects: the `SKILL.md` body and the resource
    /// map, which is stored as a JSON *string* rather than as an object.
    static let skillDetailJSON = """
    {"id":5,"name":"Glossary","repositoryId":2,"repositoryName":"qoder-skills","repositoryUrl":"https://github.com/qoder/skills.git","repositoryBranch":"main","description":"领域术语表，回答前先对齐口径","skillmd":"# Glossary\\n\\n回答前先确认术语口径：\\n\\n1. 会话：一次渠道到智能体的连续对话\\n2. 委派：主管把子任务交给成员\\n","resources":"{\\"SKILL.md\\":\\"按口径解释术语\\",\\"terms.json\\":\\"{}\\"}","status":1,"boundAgentCount":2,"boundTeamCount":0,"isPublic":1,"creator":"heqingsong","createTime":"2026-08-21 09:07:11","updateTime":"2026-09-27 21:14:03"}
    """

    /// A registered package with a secret parameter and a bound skill, and one whose optional columns the
    /// server dropped because they are blank.
    static let cliJSON = """
    {"pageNum":1,"pageSize":20,"total":2,"records":[
      {"id":3,"name":"harnax-cli","description":"Harnax 平台命令行工具包","version":"1.4.0","checkCommand":"harnax --version","packageDigest":"9f2c41d7ab53e0c18f6b4d2a7c5e9130b8f4a6c2d0e5b7a91c3f5d8e0a2b4c6f","envParams":[{"id":11,"envParamName":"HARNAX_TOKEN","description":"平台下发的短期令牌","required":true,"secret":true,"defaultValue":"hur****abcd"},{"id":12,"envParamName":"HARNAX_REGION","required":false,"secret":false,"defaultValue":"cn-hangzhou"}],"skill":{"skillId":27,"skillName":"harnax-cli","skillDescription":"怎么用这个命令行包"},"status":1,"createTime":"2026-09-12 10:20:30","updateTime":"2026-09-26 08:41:07"},
      {"id":4,"name":"kubectl","packageDigest":"","version":"","status":0,"createTime":"2026-09-20 15:02:11"}
    ]}
    """

    /// The three columns only the detail read adds.
    static let cliDetailJSON = """
    {"id":3,"name":"harnax-cli","description":"Harnax 平台命令行工具包","version":"1.4.0","checkCommand":"harnax --version","packageDigest":"9f2c41d7ab53e0c18f6b4d2a7c5e9130b8f4a6c2d0e5b7a91c3f5d8e0a2b4c6f","payloadDigest":"1b7e53c4092dfa86c3e1f9b47a0d52e8c6b1a9f4d0e7c2b5a8f3d6c1b4e9a2f0","envParams":[{"id":11,"envParamName":"HARNAX_TOKEN","description":"平台下发的短期令牌","required":true,"secret":true,"defaultValue":"hur****abcd"}],"depsApt":["curl","ca-certificates","git"],"runtimeEnv":{"HARNAX_URL":"platform.adminUrl","CLI_HOME":"/opt/harnax","PYTHONUNBUFFERED":"1"},"skill":{"skillId":27,"skillName":"harnax-cli","skillDescription":"怎么用这个命令行包"},"status":1,"createTime":"2026-09-12 10:20:30","updateTime":"2026-09-26 08:41:07"}
    """
}
#endif
