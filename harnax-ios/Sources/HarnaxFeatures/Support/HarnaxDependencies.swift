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
    public let chatHistory: any ChatHistoryReading
    public let commands: any AgentCommanding
    /// The one dependency that is not on the admin surface: a streamed answer cannot go through the
    /// transport every other client shares, which waits for a whole body.
    public let streaming: any AgentStreaming

    public init(
        auth: any AuthFlowing,
        agents: any AgentCataloging,
        teams: any TeamCataloging,
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
        chatHistory: any ChatHistoryReading,
        commands: any AgentCommanding,
        streaming: any AgentStreaming
    ) {
        self.auth = auth
        self.agents = agents
        self.teams = teams
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
        self.chatHistory = chatHistory
        self.commands = commands
        self.streaming = streaming
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
        // One admin surface, sixteen protocols: every `/api/admin/**` route family hangs off the same client,
        // so the agent, team, task, refresh, five context domains, four system reads and the three session reads
        // share its header injection.
        let admin = AdminClient(client: client)
        return HarnaxDependencies(
            auth: AuthFlow(client: client, session: session, configs: configs),
            agents: admin,
            teams: admin,
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
            chatHistory: admin,
            commands: admin,
            streaming: ChatStreamClient(
                configs: configs,
                session: session,
                language: { AcceptLanguage.current() }
            )
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
