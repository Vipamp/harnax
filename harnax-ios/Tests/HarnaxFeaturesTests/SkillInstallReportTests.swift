import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The install answer is a state the sheet sits in, and the sheet opens on a *change* of that state.
///
/// `SkillInstallReport` is `Equatable`, and `SkillSourceListView` watches `lastReport` with `onChange(of:)`,
/// which fires only when the new value differs. Two installs of one source can answer identically — and the
/// second then has nothing to show, however much the report is the point of the run
/// (`harnax-webui/src/pages/skill/components/RepositoryList.tsx:153-183` reopens its modal on every run).
@MainActor
final class SkillInstallReportTests: XCTestCase {
    private let sourceFile = "HarnaxFeatures/Skills/SkillSourceListView.swift"

    /// `installAll` refreshes after a run that landed, so every install consumes a page answer as well: a
    /// test queues one before each run.
    private func seedPage(_ skills: FakeSkills) throws {
        try skills.seedSources([
            [
                "id": 7, "name": "技能源", "sourceType": "GIT", "version": "1.0.0",
                "url": "https://example.com/skills.git", "branch": "main", "description": "",
                "status": 1, "isPublic": 1, "creator": "heqingsong",
                "createTime": "2026-09-01 10:00:00", "updateTime": "2026-09-01 10:00:00",
            ],
        ])
    }

    /// An install that saved one skill — the report the sheet has to show.
    private func outcome(saved name: String) -> SkillInstallOutcome {
        SkillInstallOutcome(
            installed: [name],
            updated: [],
            failed: [],
            flagged: [],
            sourceError: nil,
            emptyReason: nil,
            stale: []
        )
    }

    /// The second run has to clear the report before it goes out, so the sheet's rule ("a change opens the
    /// report") has something to change from. Without the clear, two identical answers are one published
    /// value and the operator sees the first report and never the second.
    func testTheSecondInstallClearsTheReportBeforeItGoesOut() async throws {
        let skills = FakeSkills()
        try seedPage(skills)
        skills.installReplies = [
            .success(outcome(saved: "weekly-report")),
            .success(outcome(saved: "weekly-report")),
        ]
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()
        let source = try XCTUnwrap(vm.items.first)

        try seedPage(skills)
        await vm.installAll(source)
        let first = try XCTUnwrap(vm.lastReport, "the first run has to report what it did")

        try seedPage(skills)
        skills.gateWrites = true
        let second = Task { await vm.installAll(source) }
        try await waitUntil { skills.installRequests.count == 2 }
        XCTAssertNil(
            vm.lastReport,
            "the report of the previous run is still published, so an identical answer is not a change and "
                + "never re-opens the sheet"
        )
        skills.releaseWrites()
        await second.value
        XCTAssertEqual(vm.lastReport, first, "two identical answers really do compare equal — which is the point")
    }

    /// The clear must not swallow the news either: a run that got an answer still publishes it, and a run
    /// that got refused publishes nothing and leaves the server's sentence up instead.
    func testARefusedInstallReportsNothingAndSaysWhy() async throws {
        let skills = FakeSkills()
        try seedPage(skills)
        skills.installReplies = [
            .success(outcome(saved: "weekly-report")),
            .failure(.business(code: 500, message: "仓库不可达")),
        ]
        let vm = SkillSourceListViewModel(skills: skills)
        await vm.refresh()
        let source = try XCTUnwrap(vm.items.first)

        try seedPage(skills)
        await vm.installAll(source)
        XCTAssertNotNil(vm.lastReport)

        await vm.installAll(source)
        XCTAssertNil(vm.lastReport, "a run with no answer has no report to read")
        XCTAssertEqual(vm.inlineError, "仓库不可达")
    }

    /// The other half of the same rule: the sheet has to let go of the report it showed, or a value nobody
    /// has read stays published under the next one and the list cannot tell "nothing yet" from "already
    /// dismissed". `dismissReport()` exists for exactly this and had no caller.
    func testTheSheetHandsTheReportBackWhenItCloses() throws {
        let text = try FeatureSources.contents(of: sourceFile)
        XCTAssertTrue(
            text.contains("onDismiss:"),
            "the report sheet needs a dismissal hook"
        )
        XCTAssertTrue(
            text.contains("vm.dismissReport()"),
            "and it has to call the view model's own clear instead of keeping a local copy"
        )
    }

    private func waitUntil(_ condition: @escaping @MainActor () -> Bool) async throws {
        for _ in 0..<400 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("condition never became true")
    }
}
