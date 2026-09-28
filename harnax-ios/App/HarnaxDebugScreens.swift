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
            agents: HarnaxDebugAgents(screen: screen)
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
        case .serverSheet:
            // `A1` opens the addresses as a sheet. Presented surfaces get their own hosting, so this is the
            // one capture that can show whether the app-level theme reaches them.
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

    func page(num: Int, size: Int) async -> Result<Page<AgentSummary>, APIError> {
        if screen == .agentsFailed { return .failure(.offline) }
        let json = screen == .agentsEmpty ? HarnaxDebugPages.emptyJSON : HarnaxDebugPages.recordsJSON
        guard let data = json.data(using: .utf8),
              let page = try? JSONDecoder().decode(Page<AgentSummary>.self, from: data)
        else { return .failure(.decoding) }
        return .success(page)
    }
}

private enum HarnaxDebugPages {
    static let emptyJSON = """
    {"pageNum":1,"pageSize":20,"total":0,"records":[]}
    """

    /// One row per card variant the list can actually receive: shared, disabled, count-free, long copy,
    /// the all-null row, and a row whose `createTime` the backend did not format.
    static let recordsJSON = """
    {"pageNum":1,"pageSize":20,"total":47,"records":[
      {"id":1,"name":"Support Desk","description":"Answers product questions in the help channel and opens a ticket when it cannot.","modelName":"qwen3.7-max","status":1,"isPublic":1,"sessionCount":128,"creator":"admin","createTime":"2026-09-12 10:24:31","mcpList":[{"id":1},{"id":2}],"skillList":[{"id":3}],"toolList":[{"id":7},{"id":8},{"id":9}],"cliList":[{"id":4}]},
      {"id":2,"name":"Release Manager","description":"Tracks the release train, pings owners before a cut-off slips.","modelName":"qwen3.7-max","status":1,"isPublic":0,"sessionCount":42,"creator":"liwei","createTime":"2026-09-08 18:02:09","mcpList":[],"skillList":[{"id":5},{"id":6}],"toolList":[],"cliList":[]},
      {"id":3,"name":"Data Analyst","description":"Reads the warehouse and writes the weekly metric digest.","modelName":"deepseek-v4","status":1,"isPublic":1,"sessionCount":0,"creator":"admin","createTime":"2026-08-31 09:15:00","mcpList":[{"id":3}],"skillList":[],"toolList":[{"id":11}],"cliList":[{"id":2},{"id":5}]},
      {"id":4,"name":"Contract Review","description":"Long-form review that has to wrap over two lines at a small width without breaking the badge row above it.","modelName":"qwen3.7-max","status":0,"isPublic":0,"sessionCount":7,"creator":"zhaomin","createTime":"2026-08-19 14:47:55","mcpList":[],"skillList":[{"id":9}],"toolList":[],"cliList":[]},
      {"id":5,"name":"Empty Agent","description":null,"modelName":null,"status":1,"isPublic":null,"sessionCount":null,"creator":null,"createTime":null,"mcpList":null,"skillList":null,"toolList":null,"cliList":null},
      {"id":6,"name":null,"description":null,"modelName":"glm-5","status":null,"isPublic":null,"sessionCount":null,"creator":"admin","createTime":"not-a-date","mcpList":[],"skillList":[],"toolList":[],"cliList":[]}
    ]}
    """
}
#endif
