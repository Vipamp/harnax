import Foundation
import HarnaxAPI
import HarnaxCore
import HarnaxKit

/// The whole stack a screen can reach, assembled once at launch. Views are handed two facades and never
/// build a client, so a test can hand them a fake instead.
public struct HarnaxDependencies: Sendable {
    public let auth: any AuthFlowing
    public let agents: any AgentCataloging
    public let teams: any TeamCataloging
    /// The two by-id reads a conversation's detail sheet needs for its tools, CLI and member panels — the same
    /// routes the console fires when that modal opens. No other screen in the app asks for them, because no
    /// other screen shows an executor's bindings without its own list row.
    public let executor: any ExecutorReading
    public let agentWrite: any AgentWriting
    public let teamWrite: any TeamWriting
    public let tasks: any AgentTaskCataloging
    public let sessionRefresher: any SessionRefreshing
    public let models: any ModelCataloging
    public let tools: any ToolCataloging
    public let mcp: any McpCataloging
    public let skills: any SkillCataloging
    public let clis: any CliCataloging
    public let envVars: any EnvVarCataloging
    public let apiKeys: any ApiKeyCataloging
    public let channels: any ChannelCataloging
    public let tokenStats: any TokenStatsCataloging
    public let sessions: any SessionCataloging
    public let sessionCreate: any SessionCreating
    public let sessionConfig: any SessionConfiguring
    public let workspace: any SessionWorkspaceReading
    public let teamArtifacts: any TeamArtifactReading
    public let chatHistory: any ChatHistoryReading
    public let plan: any PlanReading
    /// The conversation's context-occupancy read, behind the chat header's tag.
    public let contextUsage: any ContextUsageReading
    public let commands: any AgentCommanding
    /// The one dependency that is not on the admin surface: a streamed answer cannot go through the
    /// transport every other client shares, which waits for a whole body.
    public let streaming: any AgentStreaming
    /// Separate from `streaming` on purpose: a host that can start a turn is not automatically a host that
    /// can answer one, and the chat screen has to be able to show a parked run without offering a control
    /// that can only fail.
    public let toolConfirm: (any ToolConfirming)?

    public init(
        auth: any AuthFlowing,
        agents: any AgentCataloging,
        teams: any TeamCataloging,
        executor: any ExecutorReading,
        agentWrite: any AgentWriting,
        teamWrite: any TeamWriting,
        tasks: any AgentTaskCataloging,
        sessionRefresher: any SessionRefreshing,
        models: any ModelCataloging,
        tools: any ToolCataloging,
        mcp: any McpCataloging,
        skills: any SkillCataloging,
        clis: any CliCataloging,
        envVars: any EnvVarCataloging,
        apiKeys: any ApiKeyCataloging,
        channels: any ChannelCataloging,
        tokenStats: any TokenStatsCataloging,
        sessions: any SessionCataloging,
        sessionCreate: any SessionCreating,
        sessionConfig: any SessionConfiguring,
        workspace: any SessionWorkspaceReading,
        teamArtifacts: any TeamArtifactReading,
        chatHistory: any ChatHistoryReading,
        plan: any PlanReading,
        contextUsage: any ContextUsageReading,
        commands: any AgentCommanding,
        streaming: any AgentStreaming,
        toolConfirm: (any ToolConfirming)? = nil
    ) {
        self.auth = auth
        self.agents = agents
        self.teams = teams
        self.executor = executor
        self.agentWrite = agentWrite
        self.teamWrite = teamWrite
        self.tasks = tasks
        self.sessionRefresher = sessionRefresher
        self.models = models
        self.tools = tools
        self.mcp = mcp
        self.skills = skills
        self.clis = clis
        self.envVars = envVars
        self.apiKeys = apiKeys
        self.channels = channels
        self.tokenStats = tokenStats
        self.sessions = sessions
        self.sessionCreate = sessionCreate
        self.sessionConfig = sessionConfig
        self.workspace = workspace
        self.teamArtifacts = teamArtifacts
        self.chatHistory = chatHistory
        self.plan = plan
        self.contextUsage = contextUsage
        self.commands = commands
        self.streaming = streaming
        self.toolConfirm = toolConfirm
    }

    public static func live() -> HarnaxDependencies {
        let store = KeychainStore()
        let configs = ServerConfigStore(store: store)
        let session = AuthSession(store: store)
        let transport = URLSessionTransport()
        let refresher = TokenRefresher(transport: transport, configs: configs)
        let client = APIClient(
            transport: transport,
            session: session,
            configs: configs,
            refresher: refresher,
            // Read per request: a language picked inside the app has to reach the next call, not the
            // next launch.
            language: { AcceptLanguage.current() }
        )
        // One admin surface, twenty-six protocols: every `/api/admin/**` route family hangs off the same
        // client, so the agent and team reads, their two detail reads, their two save surfaces, the task,
        // refresh, five context domains, four system reads and the nine session reads and writes share its
        // header injection.
        let admin = AdminClient(client: client)
        // One client for both legs: an answer to a parked run comes back as a stream of its own.
        let stream = ChatStreamClient(
            configs: configs,
            session: session,
            language: { AcceptLanguage.current() }
        )
        return HarnaxDependencies(
            auth: AuthFlow(client: client, session: session, configs: configs),
            agents: admin,
            teams: admin,
            executor: admin,
            agentWrite: admin,
            teamWrite: admin,
            tasks: admin,
            sessionRefresher: admin,
            models: admin,
            tools: admin,
            mcp: admin,
            skills: admin,
            clis: admin,
            envVars: admin,
            apiKeys: admin,
            channels: admin,
            tokenStats: admin,
            sessions: admin,
            sessionCreate: admin,
            sessionConfig: admin,
            workspace: admin,
            teamArtifacts: admin,
            chatHistory: admin,
            plan: admin,
            contextUsage: admin,
            commands: admin,
            streaming: stream,
            toolConfirm: stream
        )
    }
}

/// `Accept-Language` for the backend's message catalogue. It only knows Chinese and English variants,
/// so anything else asks for English rather than sending a tag the server would fail to match.
public enum AcceptLanguage {
    public static func current() -> String {
        value(for: HarnaxCatalog.shared.language)
    }

    public static func value(for language: HarnaxLanguage) -> String {
        language.prefersChinese ? "zh-CN" : "en-US"
    }
}
