import XCTest
import HarnaxCore
import HarnaxKit
import HarnaxFeatures

final class CopyResolutionTests: XCTestCase {
    func testServerTextWinsWhenTheBackendSaidSomething() {
        XCTAssertEqual(
            ErrorMessage.text(for: .business(code: 400, message: "智能体名称已存在")),
            "智能体名称已存在"
        )
    }

    func testAnEmptyServerMessageFallsBackToTheDictionary() {
        XCTAssertEqual(ErrorMessage.text(for: .business(code: 500, message: "")), hx("error.business"))
    }

    func testTransportFailuresUseTheClientDictionary() {
        XCTAssertEqual(ErrorMessage.text(for: .offline), hx("error.offline"))
        XCTAssertEqual(ErrorMessage.text(for: .timeout), hx("error.timeout"))
        XCTAssertEqual(ErrorMessage.text(for: .unreachable), hx("error.unreachable"))
        XCTAssertEqual(ErrorMessage.text(for: .requestNotSent), hx("error.requestNotSent"))
        XCTAssertEqual(ErrorMessage.text(for: .cancelled), hx("error.cancelled"))
        XCTAssertEqual(ErrorMessage.text(for: .decoding), hx("error.decoding"))
        XCTAssertEqual(ErrorMessage.text(for: .unpackable), hx("error.unpackable"))
        XCTAssertEqual(ErrorMessage.text(for: .invalidServerConfig("admin: x")), hx("error.serverConfig"))
    }

    /// Five different remedies, so one sentence may not cover them: check the connection, wait, check the
    /// server is running, retry, reload. Folding them was how a screen with nothing wrong with the network
    /// came to tell the user their network was down.
    func testPreAnswerFailuresEachRenderTheirOwnSentence() {
        let failures: [APIError] = [.offline, .timeout, .unreachable, .requestNotSent, .cancelled]
        let texts = failures.map { ErrorMessage.text(for: $0) }
        XCTAssertEqual(Set(texts).count, failures.count, "two outcomes share a sentence: \(texts)")
        for text in texts { XCTAssertFalse(text.isEmpty) }
    }

    func testThrottlingCarriesItsSecondCount() {
        XCTAssertEqual(ErrorMessage.text(for: .throttled(seconds: 7)), hx("error.throttled", 7))
    }

    func testHostAndPortWithoutASchemeLine() {
        XCTAssertEqual(ServerAddressSummary.hostPort("http://127.0.0.1:28080"), "127.0.0.1:28080")
        XCTAssertEqual(ServerAddressSummary.hostPort("https://harnax.internal"), "harnax.internal")
        XCTAssertEqual(ServerAddressSummary.hostPort("harnax.internal"), "harnax.internal")
    }

    func testAnAddressWithoutAPortDoesNotGainOne() {
        let line = ServerAddressSummary.line(admin: "https://harnax.internal", router: "https://harnax.internal:28443")
        XCTAssertTrue(line.contains("harnax.internal "))
        XCTAssertTrue(line.hasSuffix("harnax.internal:28443"))
    }

    func testAcceptLanguageCoversTheTwoShippedLanguages() {
        XCTAssertEqual(AcceptLanguage.value(for: .zhHans), "zh-CN")
        XCTAssertEqual(AcceptLanguage.value(for: .en), "en-US")
    }

    func testFollowingTheDeviceStillAsksForOneOfTheTwoLanguages() {
        let value = AcceptLanguage.value(for: .system)
        XCTAssertTrue(value == "zh-CN" || value == "en-US", "unexpected tag \(value)")
    }

    func testEveryThemeModeHasCopyThatResolves() {
        for mode in ThemeMode.allCases {
            let key = AppearanceSummary.themeKey(mode)
            XCTAssertNotEqual(hx(key), key, "\(key) is missing from a catalogue")
        }
    }

    func testLanguageNamesAreNeverEmpty() {
        for language in HarnaxLanguage.allCases {
            XCTAssertFalse(AppearanceSummary.languageLabel(language).isEmpty)
        }
    }
}
