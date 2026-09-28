import XCTest

@testable import HarnaxCore

/// The CLI wire shape, read against captures of `CliResponse` — one DTO answers both the page and the
/// detail, and the three detail-only fields are simply absent from a page row because the stack serialises
/// with `default-property-inclusion: non_null` (`CliResponse.kt:14-18`).
final class CliPackageTests: XCTestCase {
    // MARK: - page shape

    func testEmptyEnvelopeStillDecodes() throws {
        let body = #"{"code":200,"message":"success","timestamp":1759041600000,"data":{"pageNum":1,"pageSize":10,"total":0,"records":[]}}"#
        let envelope = try JSONDecoder().decode(Envelope<Page<CliSummary>>.self, from: Data(body.utf8))
        XCTAssertEqual(envelope.data?.total, 0)
        XCTAssertTrue(envelope.data?.records.isEmpty == true)
    }

    func testPageRowsDecodeWithTheCountsTheCardNeeds() throws {
        let page = try Fixture.decode(Envelope<Page<CliSummary>>.self, "cli-page-two-rows").data!
        XCTAssertEqual(page.total, 2)
        XCTAssertEqual(page.records.count, 2)

        let first = page.records[0]
        XCTAssertEqual(first.id, 3)
        XCTAssertEqual(first.title, "harnax-cli")
        XCTAssertEqual(first.version, "1.4.0")
        XCTAssertEqual(first.checkCommand, "harnax --version")
        XCTAssertTrue(first.isEnabled)
        XCTAssertEqual(first.shippedSkillName, "harnax-cli")
        XCTAssertEqual(first.shippedSkillId, 27)
        XCTAssertEqual(first.envParamEntries.count, 2)
        // The row shows twelve characters of a 64-character hash, not the whole thing.
        XCTAssertEqual(first.packageDigestAbbrev, "9f2c41d7ab53…")
    }

    /// Row 2 is what a package with nothing filled in looks like: the table defaults `description`,
    /// `version` and `package_digest` to the empty string and the DTO drops every null, so an empty digest
    /// must not render as a chip and a missing `status` must not read as disabled.
    func testSparseRowKeepsItsPlaceAndReportsNoOptionalValues() throws {
        let page = try Fixture.decode(Envelope<Page<CliSummary>>.self, "cli-page-two-rows").data!
        let second = page.records[1]
        XCTAssertEqual(second.id, 4)
        XCTAssertEqual(second.title, "kubectl")
        XCTAssertNil(second.description)
        XCTAssertNil(second.skill)
        XCTAssertNil(second.shippedSkillName)
        XCTAssertTrue(second.envParamEntries.isEmpty)
        XCTAssertNil(second.packageDigestAbbrev, "an empty digest is not a digest")
        XCTAssertFalse(second.isEnabled)
    }

    /// The three detail-only fields have no key at all on a page row — that absence is the reason the
    /// drawer exists, so the model must not invent a value for them.
    func testDetailOnlyFieldsAreAbsentFromEveryPageRow() throws {
        let page = try Fixture.decode(Envelope<Page<CliSummary>>.self, "cli-page-two-rows").data!
        for row in page.records {
            XCTAssertNil(row.payloadDigest)
            XCTAssertNil(row.depsApt)
            XCTAssertNil(row.runtimeEnv)
        }
    }

    // MARK: - detail shape

    func testDetailCarriesTheImageFingerprintDepsAndSlots() throws {
        let detail = try Fixture.decode(Envelope<CliSummary>.self, "cli-detail-success").data!
        XCTAssertEqual(detail.id, 3)
        XCTAssertEqual(detail.payloadDigest?.count, 64)
        XCTAssertEqual(detail.payloadDigestAbbrev, "1b7e53c4092d…")
        XCTAssertEqual(detail.aptDependencies, ["curl", "ca-certificates", "git"])
        XCTAssertEqual(detail.envParamEntries.count, 1)
        XCTAssertEqual(detail.envParamEntries.first?.envParamName, "HARNAX_TOKEN")
        XCTAssertNil(detail.runtimeEnv?["MISSING"])
    }

    /// A JSON object has no order, and the drawer renders one row per slot: unsorted, the same package
    /// would list its slots in a different order on each open.
    func testRuntimeSlotsAreListedByKeyOrder() throws {
        let detail = try Fixture.decode(Envelope<CliSummary>.self, "cli-detail-success").data!
        XCTAssertEqual(detail.runtimeEnvEntries.map(\.key), ["CLI_HOME", "HARNAX_URL", "PYTHONUNBUFFERED"])
        XCTAssertEqual(detail.runtimeEnvEntries.first?.value, "/opt/harnax")
    }

    /// `required` and `secret` are real booleans on this DTO, unlike every 0/1 column elsewhere
    /// (`ToolEnvParamEntry.kt:22-27`), and a masked secret value arrives already masked.
    func testEnvParamFlagsArriveAsBooleans() throws {
        let detail = try Fixture.decode(Envelope<CliSummary>.self, "cli-detail-success").data!
        let entry = try XCTUnwrap(detail.envParamEntries.first)
        XCTAssertTrue(entry.required)
        XCTAssertTrue(entry.secret)
        XCTAssertEqual(entry.defaultValue, "hur****abcd")
    }

    // MARK: - digest abbreviation

    func testShortDigestIsShownWhole() {
        XCTAssertEqual(CliSummary.abbreviate("abc123"), "abc123")
        XCTAssertEqual(CliSummary.abbreviate(String(repeating: "a", count: 12)), String(repeating: "a", count: 12))
        XCTAssertEqual(CliSummary.abbreviate(String(repeating: "a", count: 13))?.count, 13, "12 kept + ellipsis")
        XCTAssertNil(CliSummary.abbreviate("   "))
        XCTAssertNil(CliSummary.abbreviate(nil))
    }

    // MARK: - blast-radius reads

    /// `RelatedAgentInfo` declares all three fields non-null, but the client keeps them optional so one
    /// unexpected absence cannot blank the whole gate read
    /// (`harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/service/impl/AgentSessionRefreshService.kt:175-188`).
    func testRelatedAgentsAndSessionsDecode() throws {
        let agents = try JSONDecoder().decode(
            [RelatedAgent].self,
            from: Data(#"[{"agentId":7,"agentName":"翻译助手","status":1},{"agentId":8,"agentName":"投研","status":0}]"#.utf8)
        )
        XCTAssertEqual(agents.count, 2)
        XCTAssertTrue(agents[0].isEnabled)
        XCTAssertFalse(agents[1].isEnabled)

        let sessions = try JSONDecoder().decode(
            [RelatedSession].self,
            from: Data(#"[{"sessionId":"11111111-2222-3333-4444-555555555555","sourceType":"channel","sourceName":"运维群","agentName":"翻译助手"},{"sessionId":"s-2","sourceType":"session"}]"#.utf8)
        )
        XCTAssertTrue(sessions[0].isChannel)
        XCTAssertEqual(sessions[0].ownerName, "翻译助手")
        XCTAssertFalse(sessions[1].isChannel)
        XCTAssertNil(sessions[1].ownerName, "agentName only rides a CLI-scoped query")
        XCTAssertEqual(sessions[1].displayName, "s-2", "a session with no title falls back to its id")
    }
}
