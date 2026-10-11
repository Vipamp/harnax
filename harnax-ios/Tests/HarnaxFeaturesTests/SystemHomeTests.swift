import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// E1 — the group's three contract points: which rows an account may see, the labels those rows can show, and
/// the tab that now carries them.
///
/// The API Key gate is the whole reason the group is built from a list rather than written out twice, the copy
/// keys are the only text a row has, and the tab list is what proves the 系统 slot really went away rather than
/// just moving off screen.
final class SystemHomeTests: XCTestCase {
    /// Four slots, 「我的」 last. Written as the title keys so a fifth case fails the count rather than the
    /// names, and a renamed case fails the names rather than silently renumbering the bar.
    func testTheBottomBarHasFourTabsAndNoSystemSlot() {
        XCTAssertEqual(
            HarnaxTab.allCases.map(\.titleKey),
            ["tab.chat", "tab.agents", "tab.context", "tab.me"]
        )
    }

    func testAnAdministratorSeesEveryDomainInTheMockupOrder() {
        XCTAssertEqual(
            SystemRoute.visible(for: AccountSnapshot(username: "admin", isAdministrator: true)),
            [.envVars, .apiKeys, .channels]
        )
    }

    /// The channel and env-variable routes name no access rule server-side, so a member keeps both rows and
    /// loses only the key list the backend would refuse.
    func testAMemberIsNotOfferedTheKeyListTheBackendWouldRefuse() {
        XCTAssertEqual(SystemRoute.visible(for: AccountSnapshot(username: "liwei")), [.envVars, .channels])
    }

    /// The tab can be drawn before the profile read has landed, and an unknown account is not an
    /// administrator.
    func testAnUnknownAccountGetsTheRowsAnyoneMayOpen() {
        XCTAssertEqual(SystemRoute.visible(for: nil), [.envVars, .channels])
    }

    /// A row and the screen it opens are one name: the row's `titleKey` is the key the destination screen hands
    /// its own `navigationTitle`. Two keys naming one place do drift — the 会话记忆审批 row opened a screen that
    /// titled itself 记忆合并候选 — so this reads the nine screens rather than restating the routes' constants.
    func testEachRowIsNamedByTheKeyItsOwnScreenTitlesItselfWith() throws {
        let rows: [(titleKey: String, screen: String)] = [
            (SystemRoute.envVars.titleKey, "HarnaxFeatures/SystemDomain/EnvVarListView.swift"),
            (SystemRoute.apiKeys.titleKey, "HarnaxFeatures/SystemDomain/ApiKeyListView.swift"),
            (SystemRoute.channels.titleKey, "HarnaxFeatures/SystemDomain/ChannelListView.swift"),
            (MonitorRoute.token.titleKey, "HarnaxFeatures/TokenMonitor/TokenMonitorView.swift"),
            (MonitorRoute.skillUsage.titleKey, "HarnaxFeatures/SkillUsage/SkillUsageView.swift"),
            (MonitorRoute.callMetrics.titleKey, "HarnaxFeatures/CallMetrics/CallMetricsView.swift"),
            (GovernanceRoute.memoryDrafts.titleKey, "HarnaxFeatures/MemoryDrafts/MemoryDraftListView.swift"),
            (GovernanceRoute.memory.titleKey, "HarnaxFeatures/Memory/MemoryListView.swift"),
            (GovernanceRoute.skillDrafts.titleKey, "HarnaxFeatures/SkillDrafts/SkillDraftListView.swift"),
        ]
        for row in rows {
            let titles = try FeatureSources.captures(
                #"navigationTitle\(.*hx\("([^"]+)"\)"#,
                in: try FeatureSources.code(of: row.screen)
            )
            XCTAssertEqual(
                titles,
                [row.titleKey],
                "\(row.screen) has to title itself with the key its row is labelled by, got \(titles)"
            )
        }
    }

    /// The three monitors are reads, not records, so they left the administration array and are not gated.
    /// The order is the console's 「集群监控」 group (`harnax-webui/config/routes.ts:160-181`).
    func testTheMonitorsAreTheirOwnBlockInConsoleOrder() {
        XCTAssertEqual(MonitorRoute.allCases, [.skillUsage, .callMetrics, .token])
        XCTAssertFalse(
            SystemRoute.allCases.map(\.titleKey).contains(MonitorRoute.token.titleKey),
            "the token monitor is still listed as an administration row, so 「我的」 draws it twice"
        )
    }

    func testEveryMonitorLabelResolvesInBothLanguages() {
        for language in [HarnaxLanguage.zhHans, .en] {
            HarnaxCatalog.shared.language = language
            for route in MonitorRoute.allCases {
                for key in [route.titleKey, route.subtitleKey] {
                    let label = hx(key)
                    XCTAssertFalse(label.isEmpty, "\(key) resolved to nothing in \(language.rawValue)")
                    XCTAssertNotEqual(label, key, "\(key) is missing from the catalogue")
                }
            }
        }
        HarnaxCatalog.shared.language = .system
    }

    func testEveryRowLabelResolvesInBothLanguages() {
        for language in [HarnaxLanguage.zhHans, .en] {
            HarnaxCatalog.shared.language = language
            for route in SystemRoute.allCases {
                for key in [route.titleKey, route.subtitleKey] {
                    let label = hx(key)
                    XCTAssertFalse(label.isEmpty, "\(key) resolved to nothing in \(language.rawValue)")
                    XCTAssertNotEqual(label, key, "\(key) is missing from the catalogue")
                }
            }
        }
        HarnaxCatalog.shared.language = .system
    }
}
