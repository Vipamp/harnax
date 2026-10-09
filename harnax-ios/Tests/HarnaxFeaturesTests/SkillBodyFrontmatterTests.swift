import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// Every `SKILL.md` opens with its manifest block — `---`, then `name` and `description`, then `---` — and that
/// block is metadata, not prose. The server's own reader drops it before it parses
/// (`SkillFileParser.kt:138-143`, mirrored by `SkillMarkdown.body`), so both bodies on this screen have to drop
/// it too: the detail screen already prints the name and the description in its header, so nothing is lost.
///
/// The two view models are the load-bearing half, since a rewire back to the raw field there is exactly the
/// defect this pins. The draft pane is checked on bytes because a SwiftUI view cannot be instantiated here.
@MainActor
final class SkillBodyFrontmatterTests: XCTestCase {
    private let manifest = "---\nname: invoice-mail-review\ndescription: reads the invoice\n---\n# Invoice\n\nOpen the PDF first.\n"
    private let prose = "# Invoice\n\nOpen the PDF first."

    private func loadedDraft(_ skillmd: String) async throws -> SkillDraftDetailViewModel {
        let drafts = FakeSkillDrafts()
        drafts.detailReplies = [.success(try SkillDraftDetail.stub(["id": 1, "skillmd": skillmd]))]
        let vm = SkillDraftDetailViewModel(id: 1, drafts: drafts)
        await vm.load()
        return vm
    }

    func testTheSkillDetailBodyStartsAfterTheManifest() async throws {
        let skills = FakeSkills()
        skills.skillDetailReplies = [
            .success(try SkillItem.stub([
                "id": 7,
                "boundAgentCount": 0,
                "boundTeamCount": 0,
                "skillmd": manifest,
            ]))
        ]
        let vm = SkillDetailViewModel(id: 7, skills: skills)
        await vm.load()

        XCTAssertEqual(vm.body, prose, "the manifest is what the header shows, not the document")
    }

    func testTheDraftBodyStartsAfterTheManifest() async throws {
        let vm = try await loadedDraft(manifest)

        XCTAssertEqual(vm.bodyMarkdown, prose)
    }

    /// A document that is nothing but a manifest has no body — and the pane's empty state is the honest answer,
    /// which means the emptiness has to be judged **after** the strip, not before it.
    func testADraftWhoseWholeDocumentIsAManifestShowsNoBody() async throws {
        let vm = try await loadedDraft("---\nname: a\ndescription: b\n---\n")

        XCTAssertNil(vm.bodyMarkdown)
    }

    func testTheDraftBodyPaneReadsTheStrippedBody() throws {
        let block = try GateSources.block(
            try GateSources.contents(of: "HarnaxFeatures/SkillDrafts/SkillDraftDetailView.swift"),
            from: "private func bodyPane"
        )

        XCTAssertTrue(block.contains("vm.bodyMarkdown"), "the pane has to render the body: \(block)")
        XCTAssertFalse(block.contains("draft.skillmd"), "and must not go back to the stored document: \(block)")
    }
}
