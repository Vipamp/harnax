import XCTest
import HarnaxCore
@testable import HarnaxAPI

/// The three filters the model list adds on top of the keyword, the status and the capability tags.
///
/// All three are server-side, not local: the page is paginated, so filtering the rows already in hand would
/// quietly drop the ones not fetched yet. The backend says so itself — `model_type = #{modelType}` and
/// `price >= #{minPrice} AND price <= #{maxPrice}` are both in the page query
/// (`harnax-entity/src/main/resources/mapper/ModelMapper.xml:131-133`, `:150-155`), and the bounds are
/// inclusive on both sides.
final class ModelFilterWireTests: XCTestCase {
    private let admin = "https://harnax.example.com"

    private func query(of endpoint: Endpoint) -> String {
        endpoint.url(baseURL: admin)?.query ?? ""
    }

    func testTheTypeAndBothPriceBoundsRideTheQuery() {
        XCTAssertEqual(
            query(of: ModelEndpoint.modelPage(
                providerID: 7,
                name: "qwen",
                modelType: "chat",
                status: 1,
                tags: ["vision"],
                minPrice: 0.5,
                maxPrice: 100,
                num: 2,
                size: 20
            )),
            "pageNum=2&pageSize=20&name=qwen&status=1&providerId=7"
                + "&modelType=chat&tags=vision&minPrice=0.5&maxPrice=100"
        )
    }

    func testAnOpenFilterSendsNoneOfTheThree() {
        XCTAssertEqual(
            query(of: ModelEndpoint.modelPage(
                providerID: 7,
                name: nil,
                modelType: nil,
                status: nil,
                tags: [],
                minPrice: nil,
                maxPrice: nil,
                num: 1,
                size: 20
            )),
            "pageNum=1&pageSize=20&providerId=7"
        )
    }

    /// A bound is one number, and a whole one has to reach the server without a trailing `.0` — the shape the
    /// console's own query has, since it puts a JS number straight into the params
    /// (`harnax-webui/src/pages/model/components/ModelListTable.tsx:73-77`). Both bind the same, but the pin
    /// keeps the two apps' wire shapes comparable when a request is diffed by eye.
    func testAWholeBoundIsSentWithoutADecimalPoint() {
        XCTAssertEqual(
            query(of: ModelEndpoint.modelPage(
                providerID: 7,
                name: nil,
                modelType: nil,
                status: nil,
                tags: [],
                minPrice: 3,
                maxPrice: 0.25,
                num: 1,
                size: 20
            )),
            "pageNum=1&pageSize=20&providerId=7&minPrice=3&maxPrice=0.25"
        )
    }

    /// The one failure this guards: a `modelType` of only spaces is not "no filter" to MySQL, it is
    /// `model_type = '   '`, which matches nothing — so a list that thinks it is unfiltered would show an
    /// empty set under a `全部` label. Same rule as the keyword's (`KeywordFilterTests`).
    func testAWhitespaceTypeIsLeftOffTheURL() {
        XCTAssertEqual(
            query(of: ModelEndpoint.modelPage(
                providerID: 7,
                name: nil,
                modelType: "   ",
                status: nil,
                tags: [],
                minPrice: nil,
                maxPrice: nil,
                num: 1,
                size: 20
            )),
            "pageNum=1&pageSize=20&providerId=7"
        )
    }
}
