import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The drawer is the only place `payloadDigest`, `depsApt` and `runtimeEnv` can be seen at all, so these
/// tests are the guard that the three detail-only fields actually reach a row instead of being dropped as
/// "nothing declared".
final class CliDetailPresenterTests: XCTestCase {
    private func detail(
        packageDigest: Any? = "9f2c41d7ab53e0c18f6b4d2a7c5e9130b8f4a6c2d0e5b7a91c3f5d8e0a2b4c6f",
        payloadDigest: Any? = "1b7e53c4092dfa86c3e1f9b47a0d52e8c6b1a9f4d0e7c2b5a8f3d6c1b4e9a2f0",
        depsApt: Any? = nil,
        runtimeEnv: Any? = nil,
        envParams: Any? = nil,
        skill: Any? = nil
    ) throws -> CliSummary {
        var row: [String: Any] = [
            "id": 3,
            "name": "harnax-cli",
            "version": "1.4.0",
            "description": "平台命令行",
            "checkCommand": "harnax --version",
            "status": 1,
            "createTime": "2026-09-12 10:20:30",
        ]
        if let packageDigest { row["packageDigest"] = packageDigest }
        if let payloadDigest { row["payloadDigest"] = payloadDigest }
        if let depsApt { row["depsApt"] = depsApt }
        if let runtimeEnv { row["runtimeEnv"] = runtimeEnv }
        if let envParams { row["envParams"] = envParams }
        if let skill { row["skill"] = skill }
        return try CliSummary.stub(row)
    }

    private func rows(_ sections: [HXBindingSection], titled title: String) -> [HXBindingRow] {
        sections.flatMap(\.rows).filter { $0.title == title }
    }

    func testTheFourGroupsAppearInReadingOrder() throws {
        let sections = CliDetailPresenter.sections(for: try detail())
        XCTAssertEqual(
            sections.map(\.titleKey),
            ["cli.section.overview", "cli.section.fingerprint", "cli.section.apt", "cli.section.runtime"]
        )
        XCTAssertFalse(sections.contains { $0.rows.isEmpty }, "an empty section renders as a dead card")
    }

    func testTheOverviewCarriesIdentityAndStatus() throws {
        let sections = CliDetailPresenter.sections(for: try detail())
        let overview = try XCTUnwrap(sections.first).rows.map(\.title)
        XCTAssertEqual(overview, [
            hx("cli.field.version"),
            hx("cli.field.description"),
            hx("cli.field.status"),
            hx("cli.field.skill"),
            hx("cli.field.createTime"),
        ])
        XCTAssertEqual(rows(sections, titled: hx("cli.field.version")).first?.subtitle, "1.4.0")
        XCTAssertEqual(rows(sections, titled: hx("cli.field.status")).first?.badges, [hx("state.badge.enabled")])
    }

    /// A 64-character hash is only comparable in full; the twelve-character chip the row shows is a label,
    /// not the value (`harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx:150-175`).
    func testBothDigestsRideInFullWithTheShortFormAsAmark() throws {
        let full = try detail()
        let fingerprints = try XCTUnwrap(CliDetailPresenter.sections(for: full)[1].rows)
        XCTAssertEqual(fingerprints.count, 2)
        XCTAssertEqual(fingerprints[0].subtitle, full.packageDigest)
        XCTAssertEqual(fingerprints[0].badges, ["9f2c41d7ab53…"])
        XCTAssertEqual(fingerprints[1].title, hx("cli.field.payloadDigest"))
        XCTAssertEqual(fingerprints[1].subtitle, full.payloadDigest)
        XCTAssertEqual(fingerprints[1].badges, ["1b7e53c4092d…"])
    }

    func testAptDependenciesBecomeChipsOrSayNone() throws {
        let withDeps = CliDetailPresenter.sections(for: try detail(depsApt: ["curl", "git"]))
        XCTAssertEqual(withDeps[2].rows.first?.badges, ["curl", "git"])
        XCTAssertNil(withDeps[2].rows.first?.subtitle)

        let without = CliDetailPresenter.sections(for: try detail(depsApt: []))
        XCTAssertEqual(without[2].rows.first?.subtitle, hx("cli.value.none"))
        XCTAssertTrue(without[2].rows.first!.badges.isEmpty)
    }

    /// The declared parameter keys and the platform's own runtime slots look alike and mean different
    /// things, so the slots keep one row each in `key = value` form
    /// (`harnax-webui/src/pages/cli/components/CliDetailDrawer.tsx:59-60`).
    func testRuntimeGroupHoldsCheckCommandDeclarationsAndSlots() throws {
        let cli = try detail(
            runtimeEnv: ["HARNAX_URL": "platform.adminUrl", "CLI_HOME": "/opt/harnax"],
            envParams: [["envParamName": "HARNAX_TOKEN", "required": true, "secret": true]]
        )
        let runtime = CliDetailPresenter.sections(for: cli)[3].rows
        XCTAssertEqual(runtime.first?.title, hx("cli.field.checkCommand"))
        XCTAssertEqual(runtime.first?.subtitle, "harnax --version")
        XCTAssertEqual(runtime[1].title, hx("cli.field.envParams"))
        XCTAssertEqual(runtime[1].badges, ["HARNAX_TOKEN"])
        let slotTitles = runtime.dropFirst(2).map(\.title)
        XCTAssertEqual(slotTitles, ["CLI_HOME", "HARNAX_URL"], "slots stay sorted, so the drawer does not reshuffle")
        XCTAssertEqual(runtime[2].subtitle, "/opt/harnax")
    }

    /// A package that declares no slots still gets the labelled row — dropping it would read as a rendering
    /// bug rather than as an empty declaration.
    func testAnAbsentDetailFieldReadsAsNoneRatherThanVanishing() throws {
        let sections = CliDetailPresenter.sections(for: try detail(
            packageDigest: nil,
            payloadDigest: nil,
            skill: ["skillDescription": "只剩描述的包"]
        ))
        let runtime = sections[3].rows
        XCTAssertEqual(runtime.last?.title, hx("cli.field.runtimeEnv"))
        XCTAssertEqual(runtime.last?.subtitle, hx("cli.value.none"))
        let fingerprints = sections[1].rows
        XCTAssertEqual(fingerprints.map(\.subtitle), [hx("cli.value.none"), hx("cli.value.none")])
        XCTAssertTrue(fingerprints.allSatisfy { $0.badges.isEmpty })
        let skill = try XCTUnwrap(rows(sections, titled: hx("cli.field.skill")).first)
        XCTAssertEqual(skill.badges, [hx("cli.value.none")], "no skill name means no shipped skill")
        XCTAssertEqual(skill.subtitle, "只剩描述的包")
    }
}

/// The drawer reads its own row: the page shape cannot supply the detail-only fields, so a sheet that
/// rendered the list row instead would show three permanent "无" values.
@MainActor
final class CliDetailModelTests: XCTestCase {
    private func stubDetail() throws -> CliSummary {
        try CliSummary.stub([
            "id": 3,
            "name": "harnax-cli",
            "payloadDigest": "1b7e53c4092d",
            "runtimeEnv": ["CLI_HOME": "/opt/harnax"],
        ])
    }

    func testTheSheetAsksForItsOwnRowById() async throws {
        let clis = FakeClis()
        clis.detailReplies = [.success(try stubDetail())]
        let model = CliDetailModel(clis: clis, id: 3)
        XCTAssertEqual(model.phase, .loading)
        await model.load()
        XCTAssertEqual(model.phase, .ready)
        XCTAssertEqual(clis.detailRequests, [3])
        XCTAssertEqual(model.detail?.payloadDigest, "1b7e53c4092d")
        XCTAssertEqual(model.sections.count, 4)
    }

    func testA404OnTheDetailReadIsAFailureNotAnEmptySheet() async throws {
        let clis = FakeClis()
        clis.detailReplies = [
            .failure(.business(code: 404, message: "CLI not found")),
            .success(try stubDetail()),
        ]
        let model = CliDetailModel(clis: clis, id: 9)
        await model.load()
        XCTAssertEqual(model.phase, .failed(ErrorMessage.text(for: .business(code: 404, message: "CLI not found"))))
        XCTAssertNil(model.detail)
        XCTAssertTrue(model.hasAnswered, "the sheet has to tell still-loading apart from nothing-to-show")
        XCTAssertTrue(model.sections.isEmpty)

        await model.load()
        XCTAssertEqual(model.phase, .ready, "the retry control on the error state works")
    }
}
