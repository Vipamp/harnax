import Foundation
import HarnaxAPI
import HarnaxCore
import HarnaxKit

/// The whole stack a screen can reach, assembled once at launch. Views are handed two facades and never
/// build a client, so a test can hand them a fake instead.
public struct HarnaxDependencies: Sendable {
    public let auth: any AuthFlowing
    public let agents: any AgentCataloging

    public init(auth: any AuthFlowing, agents: any AgentCataloging) {
        self.auth = auth
        self.agents = agents
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
        return HarnaxDependencies(
            auth: AuthFlow(client: client, session: session, configs: configs),
            agents: AdminClient(client: client)
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
        switch language {
        case .zhHans: return "zh-CN"
        case .en: return "en-US"
        case .system:
            guard let code = Locale.current.language.languageCode?.identifier else { return "en-US" }
            return code == "zh" ? "zh-CN" : "en-US"
        }
    }
}
