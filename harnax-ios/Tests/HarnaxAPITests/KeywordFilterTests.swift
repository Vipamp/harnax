import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The two page routes that carry their search box as `keyword`.
///
/// `hxPresented` already has its own cases for what a blank string means; what these pin is that the call
/// sites go through it at all. The difference is visible on the wire: a keyword of only spaces reaches
/// MySQL as `LIKE '%   %'`, which matches almost no row — so a screen that shows it as "no filter in
/// effect" (every list view model's `isFiltered` reads it that way) would be listing a filtered set.
final class KeywordFilterTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func query(of endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.query ?? ""
    }

    func testTheVariablePageSendsNoKeywordForSpacesAndTrimsOneThatMeansSomething() {
        XCTAssertEqual(
            query(of: EnvVarEndpoint.page(keyword: "   ", num: 1, size: 20)),
            "pageNum=1&pageSize=20"
        )
        XCTAssertEqual(
            query(of: EnvVarEndpoint.page(keyword: " HARNAX_TOKEN ", num: 1, size: 20)),
            "pageNum=1&pageSize=20&keyword=HARNAX_TOKEN"
        )
    }

    func testTheKeyPageKeepsItsEnabledFilterWhileDroppingABlankKeyword() {
        XCTAssertEqual(
            query(of: ApiKeyEndpoint.page(keyword: "\t ", enabled: 0, num: 3, size: 20)),
            "pageNum=3&pageSize=20&enabled=0"
        )
        XCTAssertEqual(
            query(of: ApiKeyEndpoint.page(keyword: " ci ", enabled: nil, num: 1, size: 20)),
            "pageNum=1&pageSize=20&keyword=ci"
        )
    }
}
