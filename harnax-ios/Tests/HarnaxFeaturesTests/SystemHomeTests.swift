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
            [.envVars, .apiKeys, .channels, .tokenMonitor]
        )
    }

    /// The channel and token-monitor routes name no access rule server-side, so a member keeps both rows and
    /// loses only the key list the backend would refuse.
    func testAMemberIsNotOfferedTheKeyListTheBackendWouldRefuse() {
        XCTAssertEqual(SystemRoute.visible(for: AccountSnapshot(username: "liwei")), [.envVars, .channels, .tokenMonitor])
    }

    /// The tab can be drawn before the profile read has landed, and an unknown account is not an
    /// administrator.
    func testAnUnknownAccountGetsTheRowsAnyoneMayOpen() {
        XCTAssertEqual(SystemRoute.visible(for: nil), [.envVars, .channels, .tokenMonitor])
    }

    /// The row label and the screen's own navigation title are one key each (`env.title`, `apikey.title`), so
    /// the two cannot drift apart.
    func testEachRowIsNamedByTheKeyItsOwnScreenTitlesItselfWith() {
        XCTAssertEqual(SystemRoute.envVars.titleKey, "env.title")
        XCTAssertEqual(SystemRoute.apiKeys.titleKey, "apikey.title")
        XCTAssertEqual(SystemRoute.tokenMonitor.titleKey, "monitor.title")
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
