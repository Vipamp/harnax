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
    public let sessionRefresher: any SessionRefreshing

    public init(
        auth: any AuthFlowing,
        agents: any AgentCataloging,
        teams: any TeamCataloging,
        sessionRefresher: any SessionRefreshing
    ) {
        self.auth = auth
        self.agents = agents
        self.teams = teams
        self.sessionRefresher = sessionRefresher
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
        // One admin surface, three protocols: the agent, team and session-refresh routes all sit behind the
        // same client and the same auth session.
        let admin = AdminClient(client: client)
        return HarnaxDependencies(
            auth: AuthFlow(client: client, session: session, configs: configs),
            agents: admin,
            teams: admin,
            sessionRefresher: admin
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
