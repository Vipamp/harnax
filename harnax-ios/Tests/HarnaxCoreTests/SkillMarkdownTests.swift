import XCTest

@testable import HarnaxCore

/// The body a skill screen renders, with the manifest's own header taken off.
///
/// `SKILL.md` opens with `---`, a `name:`/`description:` pair, `---`. That block is the manifest's metadata —
/// both screens already show the two fields in their own header — so leaving it in the document renders a
/// `description:` line as prose and puts a rule above the title. The rule below is a verbatim mirror of the
/// server's own reader (`SkillFileParser.stripFrontmatter`,
/// `harnax-admin/src/main/kotlin/com/agnetix/harnax/admin/skill/loader/SkillFileParser.kt:138-143`), including
/// where that reader gives up: a delimiter is a line whose trimmed text is exactly `---`, and an opener that is
/// never closed is not an opener. A screen and a loader must not disagree about where the body starts.
final class SkillMarkdownTests: XCTestCase {

    func testTheBodyStartsAfterTheClosingDelimiter() {
        let doc = """
        ---
        name: invoice-mail-review
        description: Review inbound invoices
        ---

        # Invoice

        Open the PDF first.
        """

        let body = SkillMarkdown.body(doc)
        XCTAssertFalse(body.contains("name:"), "the metadata stays out of the body: \(body)")
        XCTAssertEqual(body, "\n# Invoice\n\nOpen the PDF first.")
    }

    func testADocumentWithNoFrontmatterIsLeftAlone() {
        let doc = """
        # Invoice

        Rules to separate:

        ---

        Check the total.

        ---

        Check the tax.
        """

        XCTAssertEqual(SkillMarkdown.body(doc), doc, "a body delimiter is not an opener")
    }

    func testAnUnclosedOpenerNeverDropsTheDocument() {
        let doc = """
        ---
        name: broken

        # The opener had no closer
        """

        XCTAssertEqual(SkillMarkdown.body(doc), doc, "a broken manifest still shows everything it has")
    }

    func testADelimiterWithSurroundingWhitespaceStillClosesTheBlock() {
        XCTAssertEqual(SkillMarkdown.body("---  \nname: a\n  ---\nbody"), "body")
    }

    func testAnEmptyDocumentStaysEmpty() {
        XCTAssertEqual(SkillMarkdown.body(""), "")
    }
}
