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
    case tasks
    case tasksEmpty
    case tasksFailed
    case taskForm
    case taskLogs
    case taskLogDetail
    case taskLogRunning
    case refreshSheet
    case tenantSheet
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
    case sessions
    case sessionsFailed
    case sessionRename
    case chat
    case system
    case envVars
    case apiKeys
    case channels
    case tokenMonitor
    case me
    case appearance

    static var current: HarnaxDebugScreen? {
        HarnaxDebugLaunch.value(for: "FIXTURE").flatMap(HarnaxDebugScreen.init(rawValue:))
    }

    fileprivate var tab: HarnaxTab {
        if rawValue.hasPrefix("context") { return .context }
        if rawValue.hasPrefix("session") { return .chat }
        switch self {
        case .me: return .me
        case .chat: return .chat
        case .system, .envVars, .apiKeys, .channels, .tokenMonitor: return .system
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

#if canImport(UIKit)
import UIKit

/// `-SCROLL <points>`: the one way a page taller than the handset gets captured below its fold, since the
/// simulator takes no synthetic input. It scrolls the screen's own scroll view once the data has landed, which
/// is what lets the token monitor's donuts and lines be reviewed rather than only its filters and cards.
struct HarnaxDebugScroll: UIViewRepresentable {
    func makeUIView(context: Context) -> HarnaxScrollProbe { HarnaxScrollProbe() }

    func updateUIView(_ uiView: HarnaxScrollProbe, context: Context) {}
}

final class HarnaxScrollProbe: UIView {
    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil,
              let raw = HarnaxDebugLaunch.value(for: "SCROLL"),
              let points = Double(raw), points > 0 else { return }
        // After the screen's own `.task`, so the capture shows a scrolled page rather than a scroll that was
        // reset when the rows arrived.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.scroll(points: CGFloat(points)) }
    }

    private func scroll(points: CGFloat) {
        guard let window, let scroll = scrollViews(in: window).first(where: { $0.contentSize.height > $0.bounds.height }) else { return }
        let inset = scroll.adjustedContentInset
        let limit = max(-inset.top, scroll.contentSize.height - scroll.bounds.height + inset.bottom)
        scroll.setContentOffset(CGPoint(x: 0, y: min(limit, points)), animated: false)
    }

    /// Depth-first, so the page's own scroll view wins over one nested further in.
    private func scrollViews(in view: UIView) -> [UIScrollView] {
        if let scroll = view as? UIScrollView { return [scroll] }
        return view.subviews.flatMap { scrollViews(in: $0) }
    }
}
#else
/// Nothing to move on the macOS host this file is typechecked against.
struct HarnaxDebugScroll: View {
    var body: some View { EmptyView() }
}
#endif

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
    /// The rename sheet submits through the list's own model, so the capture hosts one. It reads nothing
    /// until a screen asks it to, so carrying it on the other captures costs no call.
    @StateObject private var sessionsVM = SessionListViewModel(sessions: HarnaxDebugSessions(screen: .sessions))

    init(screen: HarnaxDebugScreen) {
        self.screen = screen
        let model = AppModel(dependencies: HarnaxDependencies(
            auth: HarnaxDebugAuth(screen: screen),
            agents: HarnaxDebugAgents(screen: screen),
            teams: HarnaxDebugTeams(),
            agentWrite: HarnaxDebugSaving(),
            teamWrite: HarnaxDebugSaving(),
            tasks: HarnaxDebugTasks(screen: screen),
            sessionRefresher: HarnaxDebugRefresher(),
            models: HarnaxDebugModels(),
            tools: HarnaxDebugTools(),
            mcp: HarnaxDebugMcp(),
            skills: HarnaxDebugSkills(),
            clis: HarnaxDebugClis(),
            envVars: HarnaxDebugEnvVars(),
            apiKeys: HarnaxDebugApiKeys(),
            channels: HarnaxDebugChannels(),
            tokenStats: HarnaxDebugTokenStats(),
            sessions: HarnaxDebugSessions(screen: screen),
            sessionCreate: HarnaxDebugSessionExtras(),
            sessionConfig: HarnaxDebugSessionExtras(),
            workspace: HarnaxDebugSessionExtras(),
            teamArtifacts: HarnaxDebugSessionExtras(),
            chatHistory: HarnaxDebugHistory(),
            plan: HarnaxDebugSessionExtras(),
            commands: HarnaxDebugCommands(),
            streaming: HarnaxDebugStreaming()
        ))
        model.tab = screen.tab
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        Group {
            // Nothing renders until the launch stub has answered: a sheet builds its view model once and keeps
            // the account it was handed at that moment, so a screen mounted a frame early would hold `nil` for
            // the whole capture and every owner-only control would read as read-only.
            if model.isRestoring {
                Color.hx(.background).ignoresSafeArea()
            } else {
                screenView
            }
        }
        .task { await model.restore() }
    }

    @ViewBuilder
    private var screenView: some View {
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
        case .envVars, .apiKeys, .channels:
            // All three lists hang behind the hub row on the real tab. Framed alone, each captures without a
            // finger to open it.
            NavigationStack {
                switch screen {
                case .envVars:
                    EnvVarListView(catalog: model.dependencies.envVars, account: model.account)
                case .apiKeys:
                    ApiKeyListView(catalog: model.dependencies.apiKeys, account: model.account)
                default:
                    ChannelListView(
                        catalog: model.dependencies.channels,
                        agents: model.dependencies.agents,
                        account: model.account
                    )
                }
            }
            .harnaxThemed()
        case .tokenMonitor:
            // The one chart page sits behind the hub row as well. Framed alone it captures with all five reads
            // landed, which is the only way to see the donuts and the four lines at once.
            NavigationStack {
                TokenMonitorView(catalog: model.dependencies.tokenStats)
                    .background(HarnaxDebugScroll())
            }
            .harnaxThemed()
        case .tasks, .tasksEmpty, .tasksFailed:
            // The task column is the third segment on the agents tab. Framed alone it captures in whatever
            // state the fixture names — rows, the empty fact, or the failed read — with no finger to switch.
            NavigationStack {
                TaskListView(catalog: model.dependencies.tasks, account: model.account)
                    .background(HarnaxDebugScroll())
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
        case .contextMcpDetail, .contextSkillTable, .contextSkillDetail, .chat, .taskLogDetail, .taskLogRunning:
            NavigationStack { pushed }
                .harnaxThemed()
        case .agentBindings, .teamBindings, .refreshSheet, .contextToolDetail, .contextCliDetail, .sessionRename,
             .taskForm, .taskLogs, .tenantSheet:
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
        case .sessionRename:
            if let session = HarnaxDebugRecord.session {
                SessionRenameSheet(vm: sessionsVM, session: session)
            }
        case .taskForm:
            // Seeded from the first row the list serves, so this is the edit sheet with its own row's values,
            // pause warning and target agent all visible at once.
            TaskFormView(row: HarnaxDebugRecord.task, catalog: model.dependencies.tasks) { _ in }
                .background(HarnaxDebugScroll())
        case .tenantSheet:
            TenantSwitchSheet(
                tenants: HarnaxDebugAuth.tenants,
                currentID: model.account?.tenantID,
                isBusy: false,
                errorText: nil,
                onConfirm: { _ in }
            )
        case .taskLogs:
            if let task = HarnaxDebugRecord.task, let id = task.id {
                TaskLogSheet(
                    taskID: id,
                    taskTitle: task.title,
                    catalog: model.dependencies.tasks,
                    account: model.account
                )
                .background(HarnaxDebugScroll())
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
        case .taskLogDetail, .taskLogRunning:
            // The pane has no endpoint of its own — it renders the row the sheet already holds. The two
            // captures differ only by the row: the newest closed one has both stamps and an answer, the live
            // one has neither but is the only shape that offers the pane's own 停止.
            let row = screen == .taskLogDetail ? HarnaxDebugRecord.taskLog : HarnaxDebugRecord.taskLogLive
            if let row {
                TaskLogDetailView(
                    row: row,
                    stoppable: row.stoppable(by: model.account),
                    isStopping: false,
                    onStop: {}
                )
                .background(HarnaxDebugScroll())
            }
        case .chat:
            if let session = HarnaxDebugRecord.session, let sessionId = hxPresented(session.sessionId) {
                ChatView(
                    streaming: model.dependencies.streaming,
                    commands: model.dependencies.commands,
                    history: model.dependencies.chatHistory,
                    config: model.dependencies.sessionConfig,
                    workspace: model.dependencies.workspace,
                    plan: model.dependencies.plan,
                    conversation: ChatConversation(id: sessionId, title: session.displayName ?? "")
                )
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

    /// Three rows: the current one carries its badge, the plain one is the tap target, and a disabled
    /// tenant row is exactly what the membership read does hand back.
    static let tenants = [
        TenantSummary(id: 1, name: "Primary Tenant", status: 1),
        TenantSummary(id: 2, name: "Acme Workspace", status: 1),
        TenantSummary(id: 3, name: "Retired Org", status: 0),
    ]

    func tenantOptions() async -> Result<[TenantSummary], APIError> {
        .success(Self.tenants)
    }

    func switchTenant(to tenant: TenantSummary) async -> Result<Void, APIError> {
        .success(())
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

/// The scheduled tasks of the agents tab.
///
/// The three reads the captures perform all answer — the task page, one task's log, and the target-agent
/// picker the form lists — and the page read is the one that varies with `-FIXTURE`, so the same three list
/// states the other catalogs have are picked at launch rather than by a finger. Every write answers
/// `.offline`: the four state changes and the stop are creator-gated on the wire
/// (`§2.3`/`§5.5`), a screenshot cannot press one, and a capture that ever reached for a control should show
/// a banner rather than a switch that looked like it had moved.
struct HarnaxDebugTasks: AgentTaskCataloging {
    let screen: HarnaxDebugScreen

    func agentTaskPage(
        name: String?,
        taskStatus: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskSummary>, APIError> {
        if screen == .tasksFailed { return .failure(.offline) }
        let json = screen == .tasksEmpty ? HarnaxDebugPages.emptyJSON : HarnaxDebugPages.tasksJSON
        return HarnaxDebugPages.decode(json, Page<AgentTaskSummary>.self)
    }

    func agentTaskLogs(
        taskID: Int64,
        filter: AgentTaskLogFilter,
        num: Int,
        size: Int
    ) async -> Result<Page<AgentTaskLog>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.taskLogsJSON, Page<AgentTaskLog>.self)
    }

    func agentTaskAgents() async -> Result<[AgentTaskAgentOption], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.taskAgentsJSON, [AgentTaskAgentOption].self)
    }

    func createAgentTask(_ draft: AgentTaskDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateAgentTask(id: Int64, _ change: AgentTaskChange) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setAgentTaskStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func triggerAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteAgentTask(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func stopAgentTaskLog(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
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

/// The environment-variable list. Only the page read answers; every write fails, so a capture that ever
/// reached for a row's menu shows an error banner rather than a list that appeared to have acted.
struct HarnaxDebugEnvVars: EnvVarCataloging {
    func envVarPage(keyword: String?, num: Int, size: Int) async -> Result<Page<EnvVarSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.envVarsJSON, Page<EnvVarSummary>.self)
    }

    func createEnvVar(_ draft: EnvVarDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateEnvVar(id: Int64, _ change: EnvVarChange) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setEnvVarStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteEnvVar(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
}

/// The API Key list. The two reads that would hand back a raw key are the ones a screenshot cannot press,
/// so they fail with everything else and the one-time sheet stays unreachable.
struct HarnaxDebugApiKeys: ApiKeyCataloging {
    func apiKeyPage(
        keyword: String?,
        enabled: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ApiKeySummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.apiKeysJSON, Page<ApiKeySummary>.self)
    }

    func createApiKey(_ draft: ApiKeyDraft) async -> Result<ApiKeyCreatedSummary, APIError> { .failure(.offline) }

    func updateApiKey(id: Int64, _ change: ApiKeyChange) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setApiKeyStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteApiKey(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func regenerateApiKey(id: Int64) async -> Result<ApiKeyCreatedSummary, APIError> { .failure(.offline) }
}

/// The channel list.
///
/// The page read and the sandbox lookup both answer, because the row's running badge, its type/mode chips and
/// its sandbox chip are all on screen in one capture. Every write and the whole scan path fail: a screenshot
/// cannot open a form or hold a phone over a QR, and the point of the failing doubles is that a capture which
/// ever reached one shows a banner instead of an action that appears to have worked.
struct HarnaxDebugChannels: ChannelCataloging {
    func channelPage(
        keyword: String?,
        type: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<ChannelSummary>, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.channelsJSON, Page<ChannelSummary>.self)
    }

    func createChannel(_ draft: ChannelDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateChannel(id: Int64, _ change: ChannelChange) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setChannelStatus(id: Int64, running: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteChannel(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func sandboxStatuses(sessionIds: [String]) async -> Result<SandboxStatusMap, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.sandboxJSON, SandboxStatusMap.self)
    }

    func startWechatLogin(id: Int64) async -> Result<WechatQrCode, APIError> { .failure(.offline) }

    func wechatLoginStatus(id: Int64) async -> Result<WechatLoginUpdate, APIError> { .failure(.offline) }

    func cancelWechatLogin(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }
}

/// The token monitor's five reads.
///
/// One payload shape serves all five because the backend answers the same `TokenStatsAggregationResponse` on
/// every route: the aggregation read is the only one that fills the seven cards and the three donuts, each
/// trend read fills only its `timeSeriesData`. The session donut gets eleven rows against the screen's
/// ten-slice cap so a capture shows the truncation rather than implying it, and the last model and session rows
/// have a blank name — the `LEFT JOIN` case, where a deleted model still costs tokens.
struct HarnaxDebugTokenStats: TokenStatsCataloging {
    func tokenAggregation(
        startTime: String?,
        endTime: String?
    ) async -> Result<TokenStatsPayload, APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.tokenAggregateJSON, TokenStatsPayload.self)
    }

    func tokenTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.tokenTrendJSON, TokenStatsPayload.self).map(\.timeSeries)
    }

    func tokenModelTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.tokenModelTrendJSON, TokenStatsPayload.self).map(\.timeSeries)
    }

    func tokenAgentTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.tokenAgentTrendJSON, TokenStatsPayload.self).map(\.timeSeries)
    }

    func tokenSessionTimeSeries(
        startTime: String?,
        endTime: String?,
        granularity: TokenGranularity
    ) async -> Result<[TokenTimePoint], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.tokenSessionTrendJSON, TokenStatsPayload.self).map(\.timeSeries)
    }
}

/// The conversation list.
///
/// Only the page read answers; the four writes fail, so a capture that ever reached for a row's menu shows
/// an error banner rather than a list that appeared to have acted.
struct HarnaxDebugSessions: SessionCataloging {
    let screen: HarnaxDebugScreen

    func sessionPage(
        keyword: String?,
        status: Int?,
        num: Int,
        size: Int
    ) async -> Result<Page<SessionSummary>, APIError> {
        if screen == .sessionsFailed { return .failure(.offline) }
        return HarnaxDebugPages.decode(HarnaxDebugPages.sessionsJSON, Page<SessionSummary>.self)
    }

    func renameSession(_ session: SessionSummary, to title: String) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func setSessionStatus(id: Int64, enabled: Bool) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func deleteSession(id: Int64) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func clearMessages(sessionId: String) async -> Result<AgentCommandReply, APIError> { .failure(.offline) }
}

/// The chat window's opening read, which is what fills the transcript before this build can stream anything.
struct HarnaxDebugHistory: ChatHistoryReading {
    func history(sessionId: String) async -> Result<[ChatHistoryLog], APIError> {
        HarnaxDebugPages.decode(HarnaxDebugPages.historyJSON, [ChatHistoryLog].self)
    }
}

struct HarnaxDebugCommands: AgentCommanding {
    func command(_ request: CommandAgentRequest) async -> Result<AgentCommandReply, APIError> { .failure(.offline) }
}

/// A screenshot cannot press send, so the stream reports the same refusal every other unfaked write does.
struct HarnaxDebugStreaming: AgentStreaming {
    func chat(_ request: ChatAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> { unfaked() }

    func confirm(_ request: ConfirmAgentRequest) async -> AsyncThrowingStream<ChatEvent, any Error> { unfaked() }

    private func unfaked() -> AsyncThrowingStream<ChatEvent, any Error> {
        AsyncThrowingStream { $0.finish(throwing: APIError.offline) }
    }
}

/// The four save routes a capture cannot submit: no screenshot fills a wizard, so each answers the same
/// refusal every other unfaked write does.
struct HarnaxDebugSaving: AgentWriting, TeamWriting {
    func createAgent(_ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateAgent(id: Int64, _ draft: AgentSaveDraft) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }

    func createTeam(_ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func updateTeam(id: Int64, _ draft: TeamSaveDraft) async -> Result<EmptyResponse, APIError> {
        .failure(.offline)
    }
}

/// The five session reads and writes a capture cannot drive: no screenshot presses send, uploads a file, or
/// picks an executor, so each answers the same refusal every other unfaked write does.
struct HarnaxDebugSessionExtras: SessionCreating, SessionConfiguring, SessionWorkspaceReading,
    TeamArtifactReading, PlanReading {
    func sessionTitleTaken(_ title: String) async -> Result<Bool, APIError> { .failure(.offline) }

    func executorChoices() async -> Result<SessionExecutorChoices, APIError> { .failure(.offline) }

    func createSession(_ draft: SessionCreateDraft) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func sessionConfig(sessionId: String) async -> Result<SessionSummary, APIError> { .failure(.offline) }

    func updateSessionConfig(
        sessionId: String,
        _ change: SessionChatChange
    ) async -> Result<EmptyResponse, APIError> { .failure(.offline) }

    func sandboxStatus(sessionId: String) async -> Result<SandboxStatus, APIError> { .failure(.offline) }

    func workspaceFiles(sessionId: String, path: String) async -> Result<[WorkspaceFile], APIError> {
        .failure(.offline)
    }

    func readWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceFileContent, APIError> {
        .failure(.offline)
    }

    func uploadWorkspaceFile(
        sessionId: String,
        path: String,
        fileName: String,
        mimeType: String,
        payload: Data
    ) async -> Result<WorkspaceUpload, APIError> { .failure(.offline) }

    func downloadWorkspaceFile(sessionId: String, path: String) async -> Result<WorkspaceDownload, APIError> {
        .failure(.offline)
    }

    func downloadAttachment(
        _ attachment: ChatFileAttachment,
        sessionId: String
    ) async -> Result<WorkspaceDownload, APIError> { .failure(.offline) }

    func teamArtifacts(sessionId: String) async -> Result<[TeamArtifact], APIError> { .failure(.offline) }

    func downloadTeamArtifact(
        fileId: String,
        sessionId: String
    ) async -> Result<TeamArtifactFile, APIError> { .failure(.offline) }

    func planNotes(sessionId: String) async -> Result<[PlanNote], APIError> { .failure(.offline) }

    func currentPlan(sessionId: String) async -> Result<CurrentPlan, APIError> { .failure(.offline) }
}

/// The single row the drill-down captures open on. Decoded once from the same fixture the list serves, so
/// a screenshot cannot show a row the list would never have produced.
@MainActor
enum HarnaxDebugRecord {
    private static let debugAgents = HarnaxDebugAgents(screen: .agents)

    static var agent: AgentSummary? { rows(AgentSummary.self, HarnaxDebugPages.agentsJSON).first }
    static var session: SessionSummary? { rows(SessionSummary.self, HarnaxDebugPages.sessionsJSON).first }
    static var team: TeamSummary? { rows(TeamSummary.self, HarnaxDebugPages.teamsJSON).first }
    static var tool: ToolSummary? { rows(ToolSummary.self, HarnaxDebugPages.toolsJSON).first }
    static var cli: CliSummary? { rows(CliSummary.self, HarnaxDebugPages.cliJSON).first }
    static var mcpServer: McpServerRow? { rows(McpServerRow.self, HarnaxDebugPages.mcpServersJSON).first }
    static var skillSource: SkillSourceSummary? { rows(SkillSourceSummary.self, HarnaxDebugPages.skillSourcesJSON).first }
    static var skill: SkillItem? { rows(SkillItem.self, HarnaxDebugPages.skillsJSON).first }
    static var task: AgentTaskSummary? { rows(AgentTaskSummary.self, HarnaxDebugPages.tasksJSON).first }
    /// The detail pane has no endpoint of its own, so the capture takes the newest run that has *closed*: the
    /// first row is the one still going, and on it every block the pane exists to show reads empty.
    static var taskLog: AgentTaskLog? { rows(AgentTaskLog.self, HarnaxDebugPages.taskLogsJSON).first { $0.endTime != nil } }
    /// The newest run still going — the same fixture's head row, since a live row is always the newest one.
    static var taskLogLive: AgentTaskLog? { rows(AgentTaskLog.self, HarnaxDebugPages.taskLogsJSON).first { $0.endTime == nil } }

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

    /// One card per shape the task list can receive. The entity answers every key but the two joined ones, so
    /// the never-ran row is the only one that leaves `lastRunStatus`/`lastRunTime` off; the rest of the
    /// nullability is the column set (`§2.1`), with the blank name and the unparseable date the console also
    /// has to survive. Together these six rows put all six badges, both switch readings, the shared badge, the
    /// read-only row, the missing target agent and the three next-run lines on one screen.
    static let tasksJSON = """
    {"pageNum":1,"pageSize":10,"total":6,"records":[
      {"id":41,"tenantId":1,"name":"每日晨报","agentId":12,"agentName":"夜间归档","prompt":"汇总昨天的构建失败并给出下一步建议","cronExpression":"0 0 9 * * ?","taskStatus":1,"concurrent":1,"timeoutSeconds":300,"description":"工作日每天早上九点跑一次","isPublic":0,"creator":"admin","active":1,"createTime":"2026-09-20 09:14:02","updateTime":"2026-09-28 09:05:41","lastRunStatus":3,"lastRunTime":"2026-09-29 09:00:01"},
      {"id":42,"tenantId":1,"name":"依赖巡检","agentId":7,"agentName":"翻译助手","prompt":"检查依赖是否有可用的新版本","cronExpression":"0 30 8 ? * MON","taskStatus":1,"concurrent":1,"timeoutSeconds":600,"description":"每周一早上检查一次","isPublic":1,"creator":"liwei","active":1,"createTime":"2026-09-21 12:00:00","updateTime":"2026-09-28 08:30:12","lastRunStatus":1,"lastRunTime":"2026-09-28 08:30:04"},
      {"id":43,"tenantId":1,"name":"周报草稿","agentId":9,"agentName":"数据核对","prompt":"按上周的会话记录起草一份周报","cronExpression":"0 30 1 * * ?","taskStatus":0,"concurrent":0,"timeoutSeconds":900,"description":"停用的是因为上周它自己把自己跑超时了","isPublic":0,"creator":"admin","active":1,"createTime":"2026-09-15 20:02:00","updateTime":"2026-09-27 01:45:20","lastRunStatus":2,"lastRunTime":"2026-09-27 01:45:00"},
      {"id":44,"tenantId":1,"name":"告警转发核对","agentId":null,"agentName":"","prompt":"核对昨天的告警是否都转到了值班群","cronExpression":"0 */15 9-18 * * ?","taskStatus":1,"concurrent":0,"timeoutSeconds":120,"description":"","isPublic":0,"creator":"zhaomin","active":1,"createTime":"2026-09-26 16:40:00","updateTime":"2026-09-26 16:40:00"},
      {"id":45,"tenantId":1,"name":"数据口径同步","agentId":9,"agentName":"数据核对","prompt":"同步术语表的最新口径","cronExpression":"0 0 0 * * *","taskStatus":1,"concurrent":0,"timeoutSeconds":300,"description":"日与周两段都没写 ?，服务端放过、调度器不放","isPublic":1,"creator":"admin","active":1,"createTime":"2026-09-24 11:00:00","updateTime":"2026-09-25 10:00:00","lastRunStatus":7,"lastRunTime":"2026-09-25 10:00:00"},
      {"tenantId":null,"name":"   ","agentId":null,"agentName":"","prompt":"","cronExpression":"","taskStatus":0,"concurrent":0,"timeoutSeconds":0,"description":"","isPublic":0,"creator":"   ","active":1,"createTime":"not-a-date","updateTime":null}
    ]}
    """

    /// The same task's log, in the `create_time DESC` order the route answers it in. All six statuses are here
    /// plus one number the enum does not name, and the two live rows are the ones without an end to report:
    /// the entity declares `endTime` nullable and non-null inclusion drops the key rather than sending null.
    ///
    /// Each row is a state some write path can actually leave behind (`§5.2`): only a `1` carries an answer, since
    /// a `0` comes from the catch that never assigns one; a `5` may carry both an answer and the stop text, because
    /// `finalizeStopped` writes the executing thread's own result; a `2` is the reaper's guess, so it has neither
    /// answer nor a duration it computed itself; a live `3`/`4` has `durationMs` 0, because nothing writes that
    /// column before close-out; and both live rows are minutes old, since `expireStale` reclaims anything still
    /// live 1.5x past `timeoutSeconds`. Two live rows are what task 41's 允许并发 is on for.
    static let taskLogsJSON = """
    {"pageNum":1,"pageSize":10,"total":7,"records":[
      {"id":912,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"","sessionId":"task-41-12-88b0d3f1","status":3,"errorInfo":"","tokenUsage":"","startTime":"2026-09-29 09:00:01","durationMs":0,"creator":"admin","createTime":"2026-09-29 09:00:01"},
      {"id":911,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"","sessionId":"task-41-12-5277ac04","status":4,"errorInfo":"Stopping...","tokenUsage":"","startTime":"2026-09-29 08:55:07","durationMs":0,"creator":"liwei","createTime":"2026-09-29 08:55:07"},
      {"id":907,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"构建失败共 3 条，其中 2 条已在今晨合并修复；剩余 1 条是 e2e 用例的环境依赖，建议为 harnax-deploy 增加 preflight。","sessionId":"task-41-12-1c7f4a90","status":1,"errorInfo":"","tokenUsage":"TokenUsage(inputTokens=1240, outputTokens=386, totalTokens=1626, costTime=68.4, timestamp=1759021275000)","startTime":"2026-09-28 09:00:02","endTime":"2026-09-28 09:01:15","durationMs":73000,"creator":"admin","createTime":"2026-09-28 09:01:15"},
      {"id":906,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"","sessionId":"task-41-12-d0e5b7a9","status":0,"errorInfo":"agent execution failed: HTTP 429 from model endpoint","tokenUsage":"","startTime":"2026-09-28 08:30:03","endTime":"2026-09-28 08:35:41","durationMs":338000,"creator":"admin","createTime":"2026-09-28 08:35:41"},
      {"id":905,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"已汇总 2 条，第 3 条尚未取回。","sessionId":"task-41-12-6b1d02ce","status":5,"errorInfo":"Task stopped by user","tokenUsage":"TokenUsage(inputTokens=410, outputTokens=133, totalTokens=543, costTime=45.8, timestamp=1758934852000)","startTime":"2026-09-27 09:00:04","endTime":"2026-09-27 09:00:52","durationMs":48000,"creator":"admin","createTime":"2026-09-27 09:00:52"},
      {"id":904,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"","sessionId":"task-41-12-a33f7e01","status":2,"errorInfo":"Auto-expired: no completion within 300s (likely service restart)","tokenUsage":"","startTime":"2026-09-26 09:00:01","endTime":"2026-09-26 09:10:01","durationMs":600000,"creator":"admin","createTime":"2026-09-26 09:10:01"},
      {"id":903,"taskId":41,"taskName":"每日晨报","prompt":"汇总昨天的构建失败并给出下一步建议","response":"","sessionId":"task-41-12-c07b52d8","status":9,"errorInfo":"","tokenUsage":"","startTime":"2026-09-25 09:00:02","endTime":"2026-09-25 09:00:03","durationMs":1000,"creator":"zhaomin","createTime":"2026-09-25 09:00:03"}
    ]}
    """

    /// The picker's whole source, and it is admin's list of *running* agents rather than the agent page — so
    /// the task's own agent 12 is legitimately missing here and the form has to re-add it from the row.
    static let taskAgentsJSON = """
    [{"id":7,"name":"翻译助手"},{"id":9,"name":"数据核对"},{"id":10,"name":"   "}]
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

    /// One card per variant the conversation list can receive: a plain agent row with both binding lists, a
    /// shared team row whose description has to wrap, a stopped row whose optional columns the server
    /// dropped, and a row with neither `id` nor `sessionId` — the shape that leaves a card with no write and
    /// no conversation to open. `mcpList`/`skillList` are on every row because the DTO declares them
    /// non-optional, which is what the server's own default makes them.
    static let sessionsJSON = """
    {"pageNum":1,"pageSize":20,"total":4,"records":[
      {"id":21,"title":"翻译一组周报","sessionDescription":"把上周的三条客户反馈翻成英文，并给出对内版本","sessionId":"web-3f2a9c41","agentId":11,"name":"Support Desk","description":"Answers product questions in the help channel","modelId":3,"modelName":"qwen3.7-max","enableThink":1,"enableSearch":0,"permissionMode":"DEFAULT","mcpList":[{"mcpId":4,"mcpName":"amap-maps","mcpDescription":"Maps and routing"}],"skillList":[{"repositoryId":2,"repositoryName":"qoder-skills","skillId":5,"skillName":"Glossary"},{"skillId":7,"skillName":"Polish"}],"status":1,"isPublic":0,"creator":"admin","createTime":"2026-09-26 10:24:31","updateTime":"2026-09-27 08:02:11"},
      {"id":22,"title":"Research Desk · 交易所公告","sessionDescription":"从公告到估值表的整条链路，这一条要跑三个成员，所以这行说明会很长，用来检查它换行的时候会不会把上面那排徽章挤走。","sessionId":"web-77c04e18","teamId":5,"name":"Research Desk","modelId":7,"modelName":"qwen3.7-max","permissionMode":"ACCEPT_EDITS","mcpList":[],"skillList":[{"skillId":12,"skillName":"Notice parser"}],"status":1,"isPublic":1,"creator":"liwei","createTime":"2026-09-25 18:02:09"},
      {"id":23,"title":"合同条款复核","sessionId":"web-1a5d80f3","agentId":13,"name":"Contract Review","modelName":"deepseek-v4","mcpList":[],"skillList":[],"status":0,"isPublic":0,"creator":"zhaomin","createTime":"2026-08-19 14:47:55"},
      {"title":"","mcpList":[],"skillList":[],"createTime":"not-a-date"}
    ]}
    """

    /// Four rows in the order the endpoint answers them: a question, an answer that thought first and then
    /// called a tool, that call's result, and the closing sentence the result fed.
    static let historyJSON = """
    [
      {"role":"USER","message":"帮我把这条反馈翻成英文","timestamp":1762500000000},
      {"role":"ASSISTANT","thinking":"先对齐术语口径，再给对内和对外两版。","text":"对外版本：","toolUseLog":[{"name":"shell","input":{"command":"ls skills"}}],"timestamp":1762500001000},
      {"role":"TOOL","name":"shell","result":"Glossary\\nPolish","timestamp":1762500002000},
      {"role":"ASSISTANT","thinking":"","text":"英文稿已经能读；对内版本保留原来的说法，只调整了语序。","toolUseLog":[],"timestamp":1762500003000}
    ]
    """

    /// A plain value, a sensitive one the server has already masked, a stopped row whose description the
    /// console left blank, and a row with no key at all — the last one also carries an unformatted
    /// `createTime`, so the byline has to fall back rather than print a date it never parsed.
    static let envVarsJSON = """
    {"pageNum":1,"pageSize":20,"total":4,"records":[
      {"id":31,"envKey":"AMAP_KEY","envValue":"9f2c4a71b8e04d5aa3c1","description":"高德地图服务的访问密钥","sensitive":0,"enabled":1,"creator":"admin","createTime":"2026-09-20 10:12:04","updateTime":"2026-09-27 15:30:00"},
      {"id":32,"envKey":"SMTP_PASSWORD","envValue":"******","description":"126 邮箱 SMTP 登录口令","sensitive":1,"enabled":1,"creator":"liwei","createTime":"2026-09-18 08:41:19"},
      {"id":33,"envKey":"SEARCH_QUOTA","envValue":"50","description":"   ","sensitive":0,"enabled":0,"creator":"admin","createTime":"2026-09-11 19:05:00"},
      {"envKey":"   ","envValue":null,"sensitive":null,"enabled":null,"creator":null,"createTime":"not-a-date"}
    ]}
    """

    /// One key with a single scope and an expiry, one with both scopes and no rate limit, one already
    /// expired, and one whose name and prefix the server sent blank.
    static let apiKeysJSON = """
    {"pageNum":1,"pageSize":20,"total":4,"records":[
      {"id":7,"name":"CI 流水线","keyPrefix":"hnx_a1b2c3d4e5f6...9d2c","scopes":"chat","tenantId":1,"rateLimit":60,"enabled":1,"expiresAt":"2026-12-31 23:59:59","creator":"admin","createTime":"2026-09-21 09:30:00","updateTime":"2026-09-21 09:30:00"},
      {"id":8,"name":"Ops console","keyPrefix":"hnx_77aa3bb9cc44...01ef","scopes":"chat,manager","tenantId":1,"rateLimit":null,"enabled":1,"expiresAt":null,"creator":"liwei","createTime":"2026-09-05 13:07:44"},
      {"id":9,"name":"Old notebook","keyPrefix":"hnx_0011aabbccdd...4455","scopes":"chat","tenantId":2,"rateLimit":10,"enabled":1,"expiresAt":"2026-08-15 00:00:00","creator":"admin","createTime":"2026-06-02 11:00:00"},
      {"name":"   ","keyPrefix":"   ","scopes":"","enabled":0,"creator":null,"createTime":"not-a-date"}
    ]}
    """

    /// Five rows: a running 飞书 websocket with a masked secret, a stopped 钉钉 stream, a personal 微信 whose
    /// scan has written a token, a 企微 row whose three capability columns are all null, and a blank row whose
    /// `createTime` the formatter has to refuse. Together they put the type/mode chip pair, the WeChat bound
    /// badge, both switch readings and the unknown sandbox badge on one screen.
    static let channelsJSON = """
    {"pageNum":1,"pageSize":20,"total":5,"records":[
      {"id":11,"tenantId":1,"name":"飞书助理","type":"feishu","typeDisplayName":"飞书","agentId":3,"agentName":"客服助手","sessionId":"chn-1f0a9c66-2b4d-4c11-9a3e-7d5c1b0a4e21","communicationMode":"websocket","permissionMode":"DEFAULT","enabled":1,"status":1,"configJson":"{\\"appId\\":\\"cli_a9f3c81d44e0\\",\\"appSecret\\":\\"******3c81\\"}","enableThink":1,"enableSearch":0,"enablePlan":0,"description":"飞书群里的客服入口","creator":"admin","createTime":"2026-09-20 10:12:04","updateTime":"2026-09-26 18:02:11"},
      {"id":12,"tenantId":1,"name":"钉钉值班群","type":"dingtalk","typeDisplayName":"钉钉","agentId":5,"agentName":"运维值班","sessionId":"chn-6b1d02ce-8f4a-4b7e-93c1-0a2d5e7f9b44","communicationMode":"stream","permissionMode":"DEFAULT","enabled":1,"status":0,"configJson":"{\\"appId\\":\\"ding24ab77\\",\\"appSecret\\":\\"******91ab\\"}","enableThink":0,"enableSearch":1,"enablePlan":0,"description":"告警转发的钉钉群","creator":"liwei","createTime":"2026-09-14 09:31:20"},
      {"id":13,"tenantId":1,"name":"个人微信","type":"wechat","typeDisplayName":"微信","agentId":3,"agentName":"客服助手","sessionId":"chn-a33f7e01-6c25-4f18-8b0d-2e94c17fa553","communicationMode":"long_polling","permissionMode":"DEFAULT","enabled":1,"status":1,"configJson":"{\\"botToken\\":\\"wx_5c41b9a702d8\\",\\"userId\\":\\"oGZQ0uAb1234\\",\\"baseUrl\\":\\"https://wx.example.com\\"}","enableThink":1,"enableSearch":0,"enablePlan":1,"description":"扫码绑定的个人号","creator":"system","createTime":"2026-09-24 21:07:45"},
      {"id":14,"tenantId":1,"name":"企微机器人","type":"wecom","typeDisplayName":"企业微信","agentId":7,"agentName":"周报助手","sessionId":"chn-c07b52d8-1a94-4e6d-82f0-39bb15d74e06","communicationMode":"websocket","permissionMode":"DEFAULT","enabled":0,"status":1,"configJson":"{\\"botId\\":\\"aibot_9c14d\\",\\"secret\\":\\"******77ab\\"}","enableThink":null,"enableSearch":null,"enablePlan":null,"creator":"system","createTime":"2026-09-08 16:20:03"},
      {"name":"   ","type":"   ","agentId":null,"agentName":null,"sessionId":null,"communicationMode":null,"enabled":null,"status":null,"configJson":null,"creator":null,"createTime":"not-a-date"}
    ]}
    """

    /// The runtime answers for the four rows above: one active, one idle, and the other two missing from the
    /// map entirely, which is what the unknown badge is for.
    static let sandboxJSON = """
    {"chn-1f0a9c66-2b4d-4c11-9a3e-7d5c1b0a4e21":{"active":true},"chn-6b1d02ce-8f4a-4b7e-93c1-0a2d5e7f9b44":{"active":false}}
    """

    /// The aggregation reply: seven numbers and the three donut sources, each list in the server's own
    /// descending order. The three lists all sum to the overall `grandTotalToken`, so the share each slice
    /// prints is the real one.
    static let tokenAggregateJSON = """
    {"overall":{"totalInputToken":1240000,"totalOutputToken":600000,"grandTotalToken":1840000,"totalFee":12,"agentCount":3,"sessionCount":11,"modelCount":4},
    "modelStats":[
      {"modelId":3,"modelName":"qwen3.7-max","providerName":"阿里云百炼","totalInputToken":520000,"totalOutputToken":260000,"grandTotalToken":780000,"totalFee":6},
      {"modelId":8,"modelName":"deepseek-v4","providerName":"DeepSeek","totalInputToken":380000,"totalOutputToken":170000,"grandTotalToken":550000,"totalFee":4},
      {"modelId":12,"modelName":"gpt-5-mini","totalInputToken":250000,"totalOutputToken":120000,"grandTotalToken":370000,"totalFee":2},
      {"modelName":"   ","totalInputToken":90000,"totalOutputToken":50000,"grandTotalToken":140000,"totalFee":0}
    ],
    "agentStats":[
      {"agentId":3,"agentName":"客服助手","totalInputToken":610000,"totalOutputToken":300000,"grandTotalToken":910000,"totalFee":6},
      {"agentId":5,"agentName":"运维值班","totalInputToken":390000,"totalOutputToken":180000,"grandTotalToken":570000,"totalFee":4},
      {"agentId":7,"agentName":"周报助手","totalInputToken":240000,"totalOutputToken":120000,"grandTotalToken":360000,"totalFee":2}
    ],
    "sessionStats":[
      {"sessionId":"web-8842","sessionTitle":"Weekly digest","totalInputToken":230000,"totalOutputToken":110000,"grandTotalToken":340000,"totalFee":3},
      {"sessionId":"web-8790","sessionTitle":"Release notes","totalInputToken":200000,"totalOutputToken":100000,"grandTotalToken":300000,"totalFee":2},
      {"sessionId":"chn-1f0a","sessionTitle":"合同审阅","totalInputToken":170000,"totalOutputToken":90000,"grandTotalToken":260000,"totalFee":2},
      {"sessionId":"web-8712","sessionTitle":"值班告警","totalInputToken":150000,"totalOutputToken":70000,"grandTotalToken":220000,"totalFee":1},
      {"sessionId":"chn-6b1d","sessionTitle":"翻译请求","totalInputToken":120000,"totalOutputToken":60000,"grandTotalToken":180000,"totalFee":1},
      {"sessionId":"web-8634","sessionTitle":"工单 4821","totalInputToken":100000,"totalOutputToken":50000,"grandTotalToken":150000,"totalFee":1},
      {"sessionId":"web-8601","sessionTitle":"数据核对","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":1},
      {"sessionId":"chn-a33f","sessionTitle":"会议纪要","totalInputToken":65000,"totalOutputToken":30000,"grandTotalToken":95000,"totalFee":0},
      {"sessionId":"web-8540","sessionTitle":"周报草稿","totalInputToken":55000,"totalOutputToken":25000,"grandTotalToken":80000,"totalFee":0},
      {"sessionId":"web-8511","sessionTitle":"FAQ 补充","totalInputToken":45000,"totalOutputToken":20000,"grandTotalToken":65000,"totalFee":0},
      {"sessionId":"web-8490","sessionTitle":"   ","totalInputToken":20000,"totalOutputToken":10000,"grandTotalToken":30000,"totalFee":0}
    ]}
    """

    /// The overall trend: seven day buckets, and no dimension on any of them — the plain `/time-series` route
    /// is the one chart that is not multi-series.
    static let tokenTrendJSON = """
    {"timeSeriesData":[
      {"timePoint":"2026-09-20 00:00:00","totalInputToken":150000,"totalOutputToken":70000,"grandTotalToken":220000,"totalFee":1},
      {"timePoint":"2026-09-21 00:00:00","totalInputToken":180000,"totalOutputToken":95000,"grandTotalToken":275000,"totalFee":2},
      {"timePoint":"2026-09-22 00:00:00","totalInputToken":210000,"totalOutputToken":105000,"grandTotalToken":315000,"totalFee":2},
      {"timePoint":"2026-09-23 00:00:00","totalInputToken":160000,"totalOutputToken":80000,"grandTotalToken":240000,"totalFee":2},
      {"timePoint":"2026-09-24 00:00:00","totalInputToken":120000,"totalOutputToken":55000,"grandTotalToken":175000,"totalFee":1},
      {"timePoint":"2026-09-25 00:00:00","totalInputToken":200000,"totalOutputToken":100000,"grandTotalToken":300000,"totalFee":2},
      {"timePoint":"2026-09-26 00:00:00","totalInputToken":220000,"totalOutputToken":95000,"grandTotalToken":315000,"totalFee":2}
    ]}
    """

    /// A dimension reply is one row per *bucket × dimension*, ordered by bucket — the shape the screen has to
    /// group before it can draw a line. These three fixtures are that, with one unnamed series each so the
    /// legend's fallback shows.
    static let tokenModelTrendJSON = """
    {"timeSeriesData":[
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":90000,"totalOutputToken":40000,"grandTotalToken":130000,"totalFee":1},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":100000,"totalOutputToken":55000,"grandTotalToken":155000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":120000,"totalOutputToken":60000,"grandTotalToken":180000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":95000,"totalOutputToken":45000,"grandTotalToken":140000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":70000,"totalOutputToken":30000,"grandTotalToken":100000,"totalFee":1},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":110000,"totalOutputToken":50000,"grandTotalToken":160000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"3","dimensionName":"qwen3.7-max","totalInputToken":125000,"totalOutputToken":55000,"grandTotalToken":180000,"totalFee":1},
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":60000,"totalOutputToken":30000,"grandTotalToken":90000,"totalFee":0},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":90000,"totalOutputToken":45000,"grandTotalToken":135000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":65000,"totalOutputToken":35000,"grandTotalToken":100000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":50000,"totalOutputToken":25000,"grandTotalToken":75000,"totalFee":0},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"   ","dimensionName":"   ","totalInputToken":90000,"totalOutputToken":50000,"grandTotalToken":140000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"8","dimensionName":"deepseek-v4","totalInputToken":95000,"totalOutputToken":40000,"grandTotalToken":135000,"totalFee":1}
    ]}
    """

    static let tokenAgentTrendJSON = """
    {"timeSeriesData":[
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":100000,"totalOutputToken":50000,"grandTotalToken":150000,"totalFee":1},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":110000,"totalOutputToken":55000,"grandTotalToken":165000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":130000,"totalOutputToken":65000,"grandTotalToken":195000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":100000,"totalOutputToken":50000,"grandTotalToken":150000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":80000,"totalOutputToken":35000,"grandTotalToken":115000,"totalFee":1},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":120000,"totalOutputToken":60000,"grandTotalToken":180000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"3","dimensionName":"客服助手","totalInputToken":135000,"totalOutputToken":60000,"grandTotalToken":195000,"totalFee":1},
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":50000,"totalOutputToken":20000,"grandTotalToken":70000,"totalFee":0},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":70000,"totalOutputToken":40000,"grandTotalToken":110000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":60000,"totalOutputToken":30000,"grandTotalToken":90000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":40000,"totalOutputToken":20000,"grandTotalToken":60000,"totalFee":0},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"5","dimensionName":"运维值班","totalInputToken":85000,"totalOutputToken":35000,"grandTotalToken":120000,"totalFee":1}
    ]}
    """

    static let tokenSessionTrendJSON = """
    {"timeSeriesData":[
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":70000,"totalOutputToken":30000,"grandTotalToken":100000,"totalFee":1},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":95000,"totalOutputToken":45000,"grandTotalToken":140000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":75000,"totalOutputToken":35000,"grandTotalToken":110000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":55000,"totalOutputToken":25000,"grandTotalToken":80000,"totalFee":0},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":90000,"totalOutputToken":45000,"grandTotalToken":135000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"web-8842","dimensionName":"Weekly digest","totalInputToken":100000,"totalOutputToken":45000,"grandTotalToken":145000,"totalFee":1},
      {"timePoint":"2026-09-20 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":80000,"totalOutputToken":40000,"grandTotalToken":120000,"totalFee":0},
      {"timePoint":"2026-09-21 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":100000,"totalOutputToken":55000,"grandTotalToken":155000,"totalFee":1},
      {"timePoint":"2026-09-22 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":115000,"totalOutputToken":60000,"grandTotalToken":175000,"totalFee":1},
      {"timePoint":"2026-09-23 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":85000,"totalOutputToken":45000,"grandTotalToken":130000,"totalFee":1},
      {"timePoint":"2026-09-24 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":65000,"totalOutputToken":30000,"grandTotalToken":95000,"totalFee":1},
      {"timePoint":"2026-09-25 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":110000,"totalOutputToken":55000,"grandTotalToken":165000,"totalFee":1},
      {"timePoint":"2026-09-26 00:00:00","dimensionId":"chn-1f0a","dimensionName":"   ","totalInputToken":120000,"totalOutputToken":50000,"grandTotalToken":170000,"totalFee":1}
    ]}
    """
}
#endif
