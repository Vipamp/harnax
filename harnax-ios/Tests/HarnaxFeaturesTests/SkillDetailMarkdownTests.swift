import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// Where the skill detail screen sends its two bodies.
///
/// `SkillDetailView.swift` cannot be instantiated in a test, so this reads the file's bytes the way the
/// colour and copy gates in `HarnaxKitTests` do. The defect it pins is the one that shipped: the SKILL.md
/// body went to `SkillCodeText`, which paints it monospaced and flat, while the resource files under the
/// tree genuinely are code and have to stay there. A wiring change that swaps the two — or that renders a
/// Python source file as prose — fails here rather than on a phone.
enum GateSources {
    static let sourcesRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Sources")

    static func contents(of relative: String) throws -> String {
        try String(contentsOf: sourcesRoot.appendingPathComponent(relative), encoding: .utf8)
    }

    /// The text from `marker` up to and including the brace that closes it, so a gate can be scoped to one
    /// declaration without a parser.
    static func block(_ text: String, from marker: String) throws -> String {
        guard let found = text.range(of: marker) else {
            throw XCTSkip("nothing in the file matches \(marker)")
        }
        var depth = 0
        var opened = false
        var body = ""
        var index = found.lowerBound
        while index < text.endIndex {
            let character = text[index]
            if character == "{" {
                depth += 1
                opened = true
            } else if character == "}" {
                depth -= 1
            }
            body.append(character)
            if opened && depth == 0 { return body }
            index = text.index(after: index)
        }
        return body
    }
}

final class SkillDetailMarkdownTests: XCTestCase {
    private let detailFile = "HarnaxFeatures/Skills/SkillDetailView.swift"
    private let viewModelFile = "HarnaxFeatures/Skills/SkillDetailViewModel.swift"

    /// SKILL.md is prose with headings, fences, tables and task lists in it; the plain-text pane flattens
    /// all of that (`specs/04-context-domains.md` iOS 适配注意点 2).
    func testTheBodyTabRendersMarkdownBlocks() throws {
        let block = try GateSources.block(try GateSources.contents(of: detailFile), from: "private var body_")
        XCTAssertTrue(
            block.contains("HXMarkdownText("),
            "the body has to reach the Markdown renderer: \(block)"
        )
        XCTAssertFalse(
            block.contains("SkillCodeText"),
            "and must not go back to the monospaced plain-text pane: \(block)"
        )
    }

    /// The other half of the split: a resource file is source code, so the tree's pane keeps showing it
    /// monospaced, selectable and horizontally scrollable.
    func testTheFilePaneStillShowsItsSourceAsCode() throws {
        let block = try GateSources.block(try GateSources.contents(of: detailFile), from: "struct SkillFilePane: View")
        XCTAssertTrue(
            block.contains("SkillCodeText(text: content)"),
            "a fetched source file loses its shape when read as prose: \(block)"
        )
        XCTAssertFalse(
            block.contains("HXMarkdownText"),
            "the file pane renders no Markdown, whatever the selection is: \(block)"
        )
    }

    /// The comment that documented the gap promised plain text and pointed at `AttributedString(markdown:)`.
    /// Left in place it would read as a still-open limitation, so the promise is gated out of both files
    /// that carried it.
    func testTheDetailScreenNoLongerPromisesPlainText() throws {
        for file in [detailFile, viewModelFile] {
            let text = try GateSources.contents(of: file)
            XCTAssertFalse(
                text.contains("plain text this round"),
                "\(file) still claims the body is drawn as plain text"
            )
        }
    }
}
