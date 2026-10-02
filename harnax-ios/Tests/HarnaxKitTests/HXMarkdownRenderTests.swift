import SwiftUI
import XCTest

@testable import HarnaxKit

/// The renderer's own decisions, tested as values.
///
/// ``HXMarkdownText`` cannot be instantiated here, so everything the view would otherwise decide inline —
/// what colour a run paints with, what glyph a list item draws, how wide a heading is, which column a table
/// cell belongs to — lives in ``HXMarkdownRender`` and is asserted the way `HXMarkdownParserTests` asserts
/// the parser. The view then only draws what these return.
final class HXMarkdownRenderTests: XCTestCase {

    // MARK: helpers

    private func runs(_ text: String) -> [HXMarkdownInline] {
        HXMarkdownParser.inlineRuns(text)
    }

    private func cell(_ text: String) -> [HXMarkdownInline] {
        runs(text)
    }

    /// The file the renderer lives in, without its comments — the same shape the kit's colour gates read.
    private func rendererCode() throws -> String {
        let url = TestSources.sourcesRoot.appendingPathComponent("HarnaxKit/Components/HXMarkdownText.swift")
        let source = try TestSources.contents(of: url)
        return source
            .replacingOccurrences(of: #"/\*[\s\S]*?\*/"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"//.*"#, with: "", options: .regularExpression)
    }

    // MARK: inline runs

    func testAPlainRunPaintsInTheColourOfItsBlock() {
        let spans = HXMarkdownRender.spans([HXMarkdownInline(text: "skill")], slot: .textSecondary)
        XCTAssertEqual(spans.count, 1)
        XCTAssertEqual(spans[0].text, "skill")
        XCTAssertEqual(spans[0].slot, .textSecondary)
        XCTAssertFalse(spans[0].monospaced)
        XCTAssertNil(spans[0].destination)
    }

    func testATraitSurvivesIntoItsSpan() {
        let spans = HXMarkdownRender.spans(runs("**bold** and *slanted* and ~~gone~~"), slot: .textPrimary)
        XCTAssertEqual(spans.filter(\.strong).map(\.text), ["bold"])
        XCTAssertEqual(spans.filter(\.emphasis).map(\.text), ["slanted"])
        XCTAssertEqual(spans.filter(\.strikethrough).map(\.text), ["gone"])
        XCTAssertEqual(spans.filter(\.monospaced), [], "only a code span draws monospaced")
    }

    func testACodeRunDrawsMonospacedInItsOwnColour() {
        let spans = HXMarkdownRender.spans(runs("run `npm init` now"), slot: .textPrimary)
        let code = spans.filter(\.monospaced)
        XCTAssertEqual(code.map(\.text), ["npm init"])
        XCTAssertEqual(code.map(\.slot), [.purple])
        XCTAssertNil(code.first?.destination, "a command in backticks is not a link")
    }

    func testALinkRunIsTappableInTheBrandColour() {
        let spans = HXMarkdownRender.spans(runs("see [the docs](https://example.com/docs) first"), slot: .textPrimary)
        XCTAssertEqual(spans.map(\.text), ["see ", "the docs", " first"], "the line keeps its order")
        let tapped = spans.filter { $0.destination != nil }
        XCTAssertEqual(tapped.map(\.text), ["the docs"])
        XCTAssertEqual(tapped.map(\.destination), [URL(string: "https://example.com/docs")])
        XCTAssertEqual(tapped.map(\.slot), [.brand])
        XCTAssertEqual(spans.filter { $0.destination == nil }.map(\.slot), [.textPrimary, .textPrimary],
                       "the prose around the link keeps the paragraph's colour")
    }

    func testAMailAddressOpensAndIsSpokenAsOneRun() {
        let spans = HXMarkdownRender.spans(runs("write [us](mailto:ops@example.com)"), slot: .textPrimary)
        XCTAssertEqual(spans.compactMap(\.destination), [URL(string: "mailto:ops@example.com")])
    }

    func testAnEmphasisedLinkKeepsBothItsTraits() {
        let spans = HXMarkdownRender.spans(runs("[**strong link**](https://example.com)"), slot: .textPrimary)
        XCTAssertEqual(spans.count, 1)
        XCTAssertTrue(spans[0].strong)
        XCTAssertEqual(spans[0].destination, URL(string: "https://example.com"))
        XCTAssertEqual(spans[0].slot, .brand, "the address wins the colour over the trait")
    }

    func testARunTheRendererWillNotOpenIsNotDrawnAsALink() {
        for address in ["javascript:alert(1)", "file:///etc/hosts", "data:text/html,x"] {
            let spans = HXMarkdownRender.spans([HXMarkdownInline(text: "x", link: address)], slot: .textPrimary)
            XCTAssertNil(spans[0].destination, "\(address) must not become tappable")
            XCTAssertEqual(spans[0].slot, .textPrimary, "a run nothing can open must not look like a link")
        }
    }

    func testADocumentWithNoRunsDrawsNoSpans() {
        XCTAssertEqual(HXMarkdownRender.spans([], slot: .textPrimary), [])
    }

    // MARK: headings

    func testSixHeadingLevelsDrawSixDifferentFonts() {
        var fonts = [Font]()
        for level in 1...6 {
            let heading = HXMarkdownRender.heading(level: level)
            if fonts.contains(where: { $0 == heading.font }) {
                XCTFail("level \(level) shares a font with an earlier one — the hierarchy would be invisible")
            }
            fonts.append(heading.font)
        }
        XCTAssertEqual(fonts.count, 6)
    }

    func testTheTopTwoHeadingsDrawTheirRule() {
        XCTAssertTrue(HXMarkdownRender.heading(level: 1).rule)
        XCTAssertTrue(HXMarkdownRender.heading(level: 2).rule)
        for level in 3...6 {
            XCTAssertFalse(HXMarkdownRender.heading(level: level).rule, "level \(level) is not a section head")
        }
    }

    func testAHeadingOutsideTheRangeClampsRatherThanCrashes() {
        XCTAssertEqual(HXMarkdownRender.heading(level: 0), HXMarkdownRender.heading(level: 1))
        XCTAssertEqual(HXMarkdownRender.heading(level: -3), HXMarkdownRender.heading(level: 1))
        XCTAssertEqual(HXMarkdownRender.heading(level: 40), HXMarkdownRender.heading(level: 6))
        XCTAssertEqual(HXMarkdownRender.heading(level: 1).slot, .textPrimary)
    }

    // MARK: fenced blocks

    /// A fence reads as a step above the card holding it, and a chat bubble is already one step above the
    /// screen — a fence that painted `surface` inside a `surface` bubble would fuse into the card.
    func testAFenceStepsAboveTheCardThatHoldsIt() {
        XCTAssertEqual(HXMarkdownRender.codeBackground(on: .background), .surface)
        XCTAssertEqual(HXMarkdownRender.codeBackground(on: .surface), .surfaceAlt)
    }

    /// The contrast table only checks the pairs it has been told about, and a fence puts primary text on its
    /// tier in both kinds of card, so both tiers have to be registered before either is painted.
    func testEveryFenceTierIsAPairTheContrastGateKnows() {
        for card in [HXMarkdownCard.background, .surface] {
            let tier = HXMarkdownRender.codeBackground(on: card)
            let registered = Palette.contrastRequirements.contains {
                $0.foreground == .textPrimary && $0.background == tier
            }
            XCTAssertTrue(registered, "primary text on \(tier.rawValue) is painted by a fence but is not a pair")
        }
    }

    // MARK: list markers

    func testABulletAndANumberDrawTheirMarkers() {
        let bullets = HXMarkdownList(ordered: false, items: [
            HXMarkdownList.Item(blocks: [.paragraph(inline: runs("one"))]),
        ])
        XCTAssertEqual(HXMarkdownRender.marker(for: bullets.items[0], ordered: false, position: 0), .bullet)

        let numbers = HXMarkdownList(ordered: true, items: [
            HXMarkdownList.Item(number: 3, blocks: [.paragraph(inline: runs("three"))]),
            HXMarkdownList.Item(number: 7, blocks: [.paragraph(inline: runs("seven"))]),
        ])
        XCTAssertEqual(HXMarkdownRender.marker(for: numbers.items[0], ordered: true, position: 0), .ordered(3),
                       "the author's own numbering is what the document says")
        XCTAssertEqual(HXMarkdownRender.marker(for: numbers.items[1], ordered: true, position: 1), .ordered(7))
    }

    func testAnOrderedListWithoutAParsedNumberFallsBackToItsPosition() {
        let list = HXMarkdownList(ordered: true, items: [
            HXMarkdownList.Item(blocks: [.paragraph(inline: runs("first"))]),
            HXMarkdownList.Item(blocks: [.paragraph(inline: runs("second"))]),
        ])
        XCTAssertEqual(HXMarkdownRender.marker(for: list.items[0], ordered: true, position: 0), .ordered(1))
        XCTAssertEqual(HXMarkdownRender.marker(for: list.items[1], ordered: true, position: 1), .ordered(2))
    }

    func testATaskItemDrawsItsBoxWhicheverMarkerSpelledIt() {
        let bullets = HXMarkdownList(ordered: false, items: [
            HXMarkdownList.Item(task: .unchecked, blocks: [.paragraph(inline: runs("todo"))]),
            HXMarkdownList.Item(task: .checked, blocks: [.paragraph(inline: runs("done"))]),
        ])
        XCTAssertEqual(HXMarkdownRender.marker(for: bullets.items[0], ordered: false, position: 0), .unchecked)
        XCTAssertEqual(HXMarkdownRender.marker(for: bullets.items[1], ordered: false, position: 1), .checked)

        let numbers = HXMarkdownList(ordered: true, items: [
            HXMarkdownList.Item(task: .checked, number: 2, blocks: [.paragraph(inline: runs("done"))]),
        ])
        XCTAssertEqual(HXMarkdownRender.marker(for: numbers.items[0], ordered: true, position: 1), .checked,
                       "a checkbox is the item's marker once GFM has read one")
    }

    func testIndentGrowsWithDepthAndStopsClimbing() {
        XCTAssertEqual(HXMarkdownRender.indent(depth: 0), 0)
        XCTAssertEqual(HXMarkdownRender.indent(depth: 1), 14)
        XCTAssertEqual(HXMarkdownRender.indent(depth: 2), 28)
        XCTAssertGreaterThan(HXMarkdownRender.indent(depth: 4), HXMarkdownRender.indent(depth: 3))
        XCTAssertEqual(HXMarkdownRender.indent(depth: 99), HXMarkdownRender.indent(depth: 6),
                       "a pathological document must not push its text off the screen")
        XCTAssertEqual(HXMarkdownRender.indent(depth: -2), 0)
    }

    // MARK: tables

    func testTheHeaderRowComesFirstAndTakesItsColumnsAlignment() {
        let table = HXMarkdownTable(
            header: [cell("cmd"), cell("mode"), cell("notes")],
            alignments: [.leading, .center, .trailing],
            rows: [[cell("sync"), cell("auto"), cell("nightly")]]
        )
        let rows = HXMarkdownRender.rows(of: table)
        XCTAssertEqual(rows.count, 2, "the header is a row of its own")
        XCTAssertEqual(rows[0].map(\.header), [true, true, true])
        XCTAssertEqual(rows[1].map(\.header), [false, false, false])
        XCTAssertEqual(rows[0].map(\.alignment), [.leading, .center, .trailing])
        XCTAssertEqual(rows[1].map(\.alignment), [.leading, .center, .trailing],
                       "a body cell lines up under its header")
        XCTAssertEqual(rows[1].flatMap(\.runs).map(\.text), ["sync", "auto", "nightly"])
    }

    func testARowShorterThanTheHeaderStillFillsEveryColumn() {
        let table = HXMarkdownTable(
            header: [cell("a"), cell("b"), cell("c")],
            alignments: [.leading, .center],
            rows: [[cell("1")]]
        )
        let rows = HXMarkdownRender.rows(of: table)
        XCTAssertEqual(rows.map(\.count), [3, 3], "the grid keeps its shape whatever the row holds")
        XCTAssertEqual(rows[1][0].runs.map(\.text), ["1"])
        XCTAssertEqual(rows[1][1].runs, [])
        XCTAssertEqual(rows[1][2].runs, [])
        XCTAssertEqual(rows[1].map(\.alignment), [.leading, .center, .leading],
                       "a column with no delimiter word is left-aligned")
    }

    func testATableWithNoHeaderDrawsNoRows() {
        let rows = HXMarkdownRender.rows(of: HXMarkdownTable(header: [], alignments: [], rows: [[cell("orphan")]]))
        XCTAssertEqual(rows.count, 1, "an orphan cell still draws rather than vanishing")
        XCTAssertEqual(rows[0].map(\.header), [false])
        XCTAssertEqual(rows[0].first?.runs.map(\.text), ["orphan"])
    }

    // MARK: the component's own gates

    /// Every colour in this app has to come off a token for both appearances to work; the kit-wide gate
    /// checks the phrase, this one checks the renderer cannot reach past it.
    func testTheRendererPaintsOnlyTokenColours() throws {
        let code = try rendererCode()
        XCTAssertFalse(code.isEmpty, "the renderer file is what this gate reads")
        let colors = try NSRegularExpression(pattern: #"Color\.([A-Za-z]+)\s*\("#)
        let range = NSRange(code.startIndex..., in: code)
        let names = colors.matches(in: code, range: range).compactMap { match -> String? in
            guard let slot = Range(match.range(at: 1), in: code) else { return nil }
            return String(code[slot])
        }
        XCTAssertFalse(names.isEmpty, "the renderer draws colour at all, so this reader has to be reading")
        for name in names where name != "hx" && name != "hxFill" {
            XCTFail("Color.\(name)( is not a token; the renderer has to ask PaletteSlot for it")
        }
        XCTAssertFalse(code.contains(".colorScheme"), "appearance is resolved in HarnaxKit/Theme alone")
    }
}
