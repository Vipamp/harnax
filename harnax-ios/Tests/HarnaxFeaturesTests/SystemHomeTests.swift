import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

/// E1 — the hub's two contract points: which rows an account may see, and the labels those rows can show.
///
/// The API Key gate is the whole reason the group is built from a list rather than written out twice, and the
/// copy keys are the only text a row has.
final class SystemHomeTests: XCTestCase {
    func testAnAdministratorSeesEveryDomainInTheMockupOrder() {
        XCTAssertEqual(
            SystemRoute.visible(for: AccountSnapshot(username: "admin", isAdministrator: true)),
            [.envVars, .apiKeys, .channels]
        )
    }

    /// The channel route carries no access rule server-side, so a member keeps that row and loses only the
    /// key list the backend would refuse.
    func testAMemberIsNotOfferedTheKeyListTheBackendWouldRefuse() {
        XCTAssertEqual(SystemRoute.visible(for: AccountSnapshot(username: "liwei")), [.envVars, .channels])
    }

    /// The tab can be drawn before the profile read has landed, and an unknown account is not an
    /// administrator.
    func testAnUnknownAccountGetsTheRowsAnyoneMayOpen() {
        XCTAssertEqual(SystemRoute.visible(for: nil), [.envVars, .channels])
    }

    /// The row label and the screen's own navigation title are one key each (`env.title`, `apikey.title`), so
    /// the two cannot drift apart.
    func testEachRowIsNamedByTheKeyItsOwnScreenTitlesItselfWith() {
        XCTAssertEqual(SystemRoute.envVars.titleKey, "env.title")
        XCTAssertEqual(SystemRoute.apiKeys.titleKey, "apikey.title")
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
