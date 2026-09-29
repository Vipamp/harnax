import XCTest
import HarnaxCore

/// E5 — what the five `/api/admin/token-stats/**` routes actually put on the wire, and what
/// `TokenStatsPayload` does with it.
///
/// Two of the replies here are captured from the running dev stack (`harnax-admin` on 127.0.0.1:28080,
/// 2026-09-28) and they carry the shape no hand-written fixture would think to test: one class answers all
/// five routes (`TokenStatsAggregationResponse.kt:14-24`), and with `default-property-inclusion: non_null`
/// (`application.yml:22-25`) each reply simply *omits* the members that route never fills. The aggregation
/// reply has no `timeSeriesData` key at all; a time-series reply has no `overall` and no dimension lists.
///
/// That stack holds no token rows, so both captures are the zero reading — which is the case the page's whole
/// empty judgement hangs on. The populated shapes below are therefore written out, in the field names the DTO
/// declares.
final class TokenStatsContractTests: XCTestCase {
    private func payload(_ json: String) throws -> TokenStatsPayload {
        try JSONDecoder().decode(TokenStatsPayload.self, from: Data(json.utf8))
    }

    /// The keys the server actually put in `data` — the point of a capture is the omission, and a decoder
    /// assertion alone cannot tell an absent key from a key set to null.
    private func sentKeys(_ fixture: String) throws -> [String: Any] {
        let raw = try Fixture.data(fixture)
        let envelope = try XCTUnwrap(
            JSONSerialization.jsonObject(with: raw) as? [String: Any],
            "\(fixture) is not a JSON object"
        )
        return try XCTUnwrap(envelope["data"] as? [String: Any])
    }

    // MARK: - the two captures

    /// `timeSeriesData` is absent from this reply and `totalFee` arrives as `0.0` — a decimal point on a
    /// column the database declares `decimal(10,0)`.
    func testRealAggregationReplyNormalisesTheAbsentTimeSeries() throws {
        XCTAssertNil(try sentKeys("token-aggregation-empty")["timeSeriesData"])

        let envelope = try Fixture.decode(Envelope<TokenStatsPayload>.self, "token-aggregation-empty")
        XCTAssertEqual(envelope.code, 200)
        let body = try XCTUnwrap(envelope.data)

        XCTAssertEqual(body.overall, TokenOverallStats())
        XCTAssertFalse(body.overall.hasConsumption)
        XCTAssertEqual(body.overall.totalFee, Decimal(0))
        XCTAssertEqual(body.modelStats, [])
        XCTAssertEqual(body.agentStats, [])
        XCTAssertEqual(body.sessionStats, [])
        XCTAssertEqual(body.timeSeries, [], "the absent list normalises to empty rather than staying optional")
    }

    /// The same class on a trend route: only `timeSeriesData` is present, so the seven numbers come back as
    /// zeros rather than as an optional the screen has to unwrap five ways.
    ///
    /// Eight buckets for a seven-day window is the server's own day-truncation of `now-7d … now`, and the
    /// `timePoint` text is what it selected — `yyyy-MM-dd HH:mm:ss` with a space and no zone.
    func testRealTimeSeriesReplyAnswersOnlyItsOwnRows() throws {
        let keys = try sentKeys("token-time-series-zero-buckets")
        XCTAssertNil(keys["overall"])
        XCTAssertNil(keys["modelStats"])

        let body = try XCTUnwrap(
            try Fixture.decode(Envelope<TokenStatsPayload>.self, "token-time-series-zero-buckets").data
        )

        XCTAssertEqual(body.overall, TokenOverallStats())
        XCTAssertEqual(body.modelStats, [])
        XCTAssertEqual(body.timeSeries.count, 8)
        XCTAssertEqual(body.timeSeries.first?.timePoint, "2026-09-22 00:00:00")
        XCTAssertEqual(body.timeSeries.last?.timePoint, "2026-09-29 00:00:00")
        XCTAssertEqual(body.timeSeries.first?.label, "2026-09-22 00:00:00")
        XCTAssertNil(body.timeSeries.first?.name, "the plain route sends no dimension at all")
    }

    // MARK: - the populated shapes

    /// All five members at once is what a future route could answer, and nothing may be dropped by the
    /// normalising decoder.
    func testEveryMemberDecodesWhenTheReplyCarriesAllFive() throws {
        let body = try payload("""
        {"overall":{"totalInputToken":1000,"totalOutputToken":500,"grandTotalToken":1500,"totalFee":7,
                    "agentCount":2,"sessionCount":3,"modelCount":4},
         "modelStats":[{"modelId":9,"modelName":"qwen3.7-max","providerName":"阿里云百炼",
                        "totalInputToken":600,"totalOutputToken":300,"grandTotalToken":900,"totalFee":4}],
         "sessionStats":[{"sessionId":"web-8842","sessionTitle":"Weekly digest",
                          "totalInputToken":400,"totalOutputToken":200,"grandTotalToken":600,"totalFee":3}],
         "agentStats":[{"agentId":3,"agentName":"客服助手",
                        "totalInputToken":500,"totalOutputToken":250,"grandTotalToken":750,"totalFee":2}],
         "timeSeriesData":[{"timePoint":"2026-09-26 00:00:00","dimensionId":"9","dimensionName":"qwen3.7-max",
                            "totalInputToken":100,"totalOutputToken":50,"grandTotalToken":150,"totalFee":1}]}
        """)

        XCTAssertEqual(body.overall.grandTotalToken, 1500)
        XCTAssertEqual(body.overall.totalFee, Decimal(7))
        XCTAssertTrue(body.overall.hasConsumption)
        XCTAssertEqual(body.modelStats.count, 1)
        XCTAssertEqual(body.modelStats[0].provider, "阿里云百炼")
        XCTAssertEqual(body.sessionStats[0].identity, "web-8842")
        XCTAssertEqual(body.agentStats[0].title, "客服助手")
        XCTAssertEqual(body.timeSeries[0].dimensionId, "9")
    }

    /// The metric columns are Kotlin non-null with `0` defaults, and the service itself answers `?: 0L` when
    /// its result map lacks the key (`TokenStatsAggregationResponse.kt:31`). A missing column is a zero, not a
    /// failed reply.
    func testAbsentMetricColumnsReadAsTheServersOwnZero() throws {
        let body = try payload(#"{"modelStats":[{"modelId":9,"modelName":"qwen3.7-max"}]}"#)
        let row = try XCTUnwrap(body.modelStats.first)

        XCTAssertEqual(row.totalInputToken, 0)
        XCTAssertEqual(row.totalOutputToken, 0)
        XCTAssertEqual(row.grandTotalToken, 0)
        XCTAssertEqual(row.totalFee, Decimal(0))
        XCTAssertNil(row.providerName, "an absent provider stays absent rather than becoming the empty text")
    }

    /// `totalFee` is a `BigDecimal` on the wire: an integer, a fraction and a quoted number all have to
    /// survive as themselves, because a `Double` here would round a fee this side cannot recover.
    func testFeeArrivesInThreeFormsAndKeepsItsFraction() throws {
        let whole = try payload(#"{"overall":{"totalFee":12}}"#).overall
        let fraction = try payload(#"{"overall":{"totalFee":0.25}}"#).overall
        let quoted = try payload(#"{"overall":{"totalFee":"0.75"}}"#).overall

        XCTAssertEqual(whole, TokenOverallStats(totalFee: Decimal(12)))
        XCTAssertEqual(fraction.totalFee, Decimal(string: "0.25"))
        XCTAssertEqual(quoted.totalFee, Decimal(string: "0.75"))
    }

    /// `dimensionId`, `sessionId` and `timePoint` are all `String?` filled by `toString()` on whatever the
    /// driver handed back (`TokenStatsAggregationResponse.kt:106-118`), so a numeric-looking column can arrive
    /// as a JSON number. It still reads as its own text instead of failing the reply.
    func testStringColumnsSentAsNumbersStillComeBackAsText() throws {
        let body = try payload("""
        {"timeSeriesData":[{"timePoint":1790000000000,"dimensionId":9,"dimensionName":"qwen3.7-max"}],
         "sessionStats":[{"sessionId":8842,"sessionTitle":"Weekly digest"}]}
        """)

        XCTAssertEqual(body.timeSeries[0].timePoint, "1790000000000")
        XCTAssertEqual(body.timeSeries[0].identity, "9")
        XCTAssertEqual(body.sessionStats[0].identity, "8842")
    }

    /// A `LEFT JOIN` row keeps its numbers and loses its name (`TokenStatsMapper.xml:74-76`): a deleted model
    /// still cost tokens. Blank text counts as no name, the same reading every other screen uses.
    func testAJoinedRowWithNoNameKeepsItsFigures() throws {
        let body = try payload("""
        {"modelStats":[{"modelId":null,"modelName":"   ","grandTotalToken":140000},
                        {"modelName":"gpt-5-mini","grandTotalToken":2000}]}
        """)
        let unnamed = body.modelStats[0]

        XCTAssertNil(unnamed.title)
        XCTAssertNil(unnamed.identity)
        XCTAssertEqual(unnamed.grandTotalToken, 140000)
        XCTAssertEqual(body.modelStats[1].title, "gpt-5-mini")
    }

    // MARK: - the two levers the screen sends

    /// The four bucket codes and the default are the controller's own: `defaultValue = "day"` on all four
    /// trend routes (`TokenStatsController.kt:77`, `:106`, `:134`, `:162`), and the raw values *are* the query
    /// values, so a picker cannot offer a bucket the service would silently downgrade.
    func testGranularityCodesAreTheQueryValues() {
        XCTAssertEqual(TokenGranularity.allCases.map(\.rawValue), ["hour", "day", "week", "month"])
        XCTAssertEqual(TokenGranularity.serverDefault, .day)
    }

    /// Both ends of every window go out in the one text all five routes parse
    /// (`@DateTimeFormat` `yyyy-MM-dd HH:mm:ss`, `TokenStatsController.kt:44-49`), in the handset's own
    /// calendar — the same wall-clock reading the console sends.
    func testWindowTextIsThePatternTheRoutesParse() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .autoupdatingCurrent
        let date = try XCTUnwrap(
            calendar.date(from: DateComponents(year: 2026, month: 9, day: 28, hour: 10, minute: 20, second: 30))
        )
        XCTAssertEqual(hxWallClockString(date), "2026-09-28 10:20:30")
    }
}
