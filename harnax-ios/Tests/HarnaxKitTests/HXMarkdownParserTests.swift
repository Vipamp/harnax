import XCTest

@testable import HarnaxKit

/// The parser is a pure function, so every rule of the Markdown subset is provable here without a view.
///
/// A skill document arrives from an npm package or a zip someone else wrote, so the cases below are weighted
/// towards what such a document actually contains — fences full of other syntax, Chinese paragraphs, GFM
/// tables — and towards what must never happen: a stretch of text that becomes tappable on its own.
final class HXMarkdownParserTests: XCTestCase {

    // MARK: helpers

    private func blocks(_ text: String) -> [HXMarkdownBlock] {
        HXMarkdownParser.blocks(in: text)
    }

    private func runs(_ text: String) -> [HXMarkdownInline] {
        HXMarkdownParser.inlineRuns(text)
    }

    /// The paragraph text of a one-paragraph document, which is what most inline cases reduce to.
    private func plain(_ text: String) -> String {
        guard case let .paragraph(inline)? = blocks(text).first else { return "‹no paragraph›" }
        return inline.map(\.text).joined()
    }

    private func paragraph(_ text: String) -> [HXMarkdownBlock] {
        [.paragraph(inline: runs(text))]
    }

    private func heading(_ level: Int, _ text: String) -> [HXMarkdownBlock] {
        [.heading(level: level, inline: runs(text))]
    }

    // MARK: the whole document

    func testEmptyDocumentYieldsNoBlocks() {
        XCTAssertEqual(blocks(""), [])
        XCTAssertEqual(blocks("\n\n  \n"), [])
    }

    func testUndocumentedSyntaxStillRendersAsParagraphs() {
        // Raw HTML is text, not a surface: nothing here becomes a view of its own.
        XCTAssertEqual(
            blocks("<div class=\"x\">\n<b>bold</b>\n</div>"),
            paragraph("<div class=\"x\"> <b>bold</b> </div>")
        )
    }

    func testReferenceLinksStayAsWritten() {
        // Not supported, and the honest failure is the source on screen rather than a dead tap target.
        XCTAssertEqual(plain("See [docs][ref]."), "See [docs][ref].")
    }

    // MARK: headings

    func testAtxHeadingsAcrossSixLevels() {
        for level in 1...6 {
            let hashes = String(repeating: "#", count: level)
            XCTAssertEqual(blocks("\(hashes) Title"), [.heading(level: level, inline: [HXMarkdownInline(text: "Title")])])
        }
        XCTAssertEqual(blocks("####### Seven"), paragraph("####### Seven"))
        XCTAssertEqual(blocks("#NoSpace"), paragraph("#NoSpace"))
    }

    func testAtxHeadingClosingSequenceAndHashInText() {
        XCTAssertEqual(blocks("## Name ##"), heading(2, "Name"))
        XCTAssertEqual(blocks("## C#"), heading(2, "C#"))
        XCTAssertEqual(blocks("#"), [])
    }

    func testSetextHeadingUnderAParagraph() {
        XCTAssertEqual(blocks("Title\n="), heading(1, "Title"))
        XCTAssertEqual(blocks("Title\n-"), heading(2, "Title"))
    }

    /// The rule the frontmatter of every SKILL.md leans on: a `---` with a paragraph open above it is that
    /// paragraph's setext underline, and only a `---` standing on its own is a break — which is exactly how the
    /// web console reads the same document.
    func testSetextOnlyAppliesToAnOpenParagraph() {
        XCTAssertEqual(
            blocks("---\nname: demo\n---\n\n# Body"),
            [.thematicBreak] + heading(2, "name: demo") + heading(1, "Body")
        )
        XCTAssertEqual(blocks("---"), [.thematicBreak])
        // A setext heading's content still runs the inline pass, so the markers do not survive into the text.
        XCTAssertEqual(blocks("**bold**\n---"), [.heading(level: 2, inline: [.init(text: "bold", strong: true)])])
    }

    func testThematicBreakSpellings() {
        for line in ["---", "***", "___", "- - -", "  ***  "] {
            XCTAssertEqual(blocks(line), [.thematicBreak], "\(line)")
        }
        XCTAssertEqual(blocks("--"), paragraph("--"))
        XCTAssertEqual(blocks("* a\n* b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a")),
                HXMarkdownList.Item(blocks: paragraph("b")),
            ])),
        ])
    }

    // MARK: paragraphs

    func testSoftBreakBecomesASpace() {
        XCTAssertEqual(blocks("one\ntwo\nthree"), paragraph("one two three"))
    }

    func testSoftBreakBetweenHanCharactersAddsNoSpace() {
        // ICU's own line breaking would put a gap here; a joined Chinese sentence must read joined.
        XCTAssertEqual(blocks("第一步\n第二步"), paragraph("第一步第二步"))
        // A boundary that is not Han on both sides still needs the space back.
        XCTAssertEqual(blocks("第一步\ndone"), paragraph("第一步 done"))
    }

    func testHardBreakSurvivesAsANewline() {
        XCTAssertEqual(blocks("one  \ntwo"), paragraph("one\ntwo"))
        XCTAssertEqual(blocks("one\\\ntwo"), paragraph("one\ntwo"))
    }

    func testBlankLineEndsAParagraph() {
        XCTAssertEqual(blocks("one\n\ntwo"), paragraph("one") + paragraph("two"))
    }

    func testParagraphIsBrokenByTheNextBlock() {
        XCTAssertEqual(blocks("text\n# Head"), paragraph("text") + heading(1, "Head"))
        XCTAssertEqual(blocks("text\n- item"), paragraph("text") + [
            .list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph("item"))])),
        ])
        // A lazy line with nothing opening after it stays in the paragraph.
        XCTAssertEqual(blocks("text\nmore"), paragraph("text more"))
    }

    // MARK: fenced code

    func testFencedCodeKeepsItsBodyLiteral() {
        let doc = """
        ```swift
        // heading? # nope
        let x = "**not bold**"
        [link](https://inside.code)
        ```
        """
        XCTAssertEqual(blocks(doc), [.code(
            language: "swift",
            text: """
            // heading? # nope
            let x = "**not bold**"
            [link](https://inside.code)
            """
        )])
    }

    func testBothFenceSpellingsAndLengths() {
        XCTAssertEqual(blocks("~~~\nplain\n~~~"), [.code(language: nil, text: "plain")])
        XCTAssertEqual(blocks("````\nuse ``` inside\n````"), [.code(language: nil, text: "use ``` inside")])
        XCTAssertEqual(blocks("``` bash\nls\n```"), [.code(language: "bash", text: "ls")])
    }

    func testUnclosedFenceRunsToTheEndAsCode() {
        XCTAssertEqual(blocks("```\nlet x = 1"), [.code(language: nil, text: "let x = 1")])
    }

    func testFenceBodyKeepsIndentationPastTheFence() {
        XCTAssertEqual(blocks("  ```\n  func f() {\n    1\n  }\n  ```"), [.code(language: nil, text: "func f() {\n  1\n}")])
    }

    func testCodeLabelOnlyAcceptsAPlausibleLanguageName() {
        XCTAssertEqual(HXMarkdownParser.codeLabel("swift"), "swift")
        XCTAssertEqual(HXMarkdownParser.codeLabel("c++"), "c++")
        XCTAssertEqual(HXMarkdownParser.codeLabel("typescript meta=true"), "typescript")
        XCTAssertNil(HXMarkdownParser.codeLabel("javascript:alert(1)"))
        XCTAssertNil(HXMarkdownParser.codeLabel("中文"))
        XCTAssertNil(HXMarkdownParser.codeLabel(""))
    }

    // MARK: lists

    func testUnorderedAndOrderedListsWithTheirMarkers() {
        XCTAssertEqual(blocks("- a\n- b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a")),
                HXMarkdownList.Item(blocks: paragraph("b")),
            ])),
        ])
        XCTAssertEqual(blocks("1. a\n2. b"), [
            .list(HXMarkdownList(ordered: true, items: [
                HXMarkdownList.Item(number: 1, blocks: paragraph("a")),
                HXMarkdownList.Item(number: 2, blocks: paragraph("b")),
            ])),
        ])
        XCTAssertEqual(blocks("* a\n+ b"), [
            .list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph("a"))])),
            .list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph("b"))])),
        ])
    }

    /// The project's own hard fact: `\d` in an ICU regular expression matches Han digits, so the marker rule
    /// is written against ASCII and a full-width number is not a list.
    func testListItemNumbersAreAsciiOnly() {
        XCTAssertEqual(blocks("１. a"), paragraph("１. a"))
        XCTAssertEqual(blocks("3．a"), paragraph("3．a"))
    }

    func testListItemNeedsItsSpaceAndItsContent() {
        XCTAssertEqual(blocks("**bold**"), paragraph("**bold**"))
        // A lone `-` is a two-level underline before it is a bullet, exactly as CommonMark reads it.
        XCTAssertEqual(blocks("-\n-"), heading(2, "-"))
        XCTAssertEqual(blocks("- "), paragraph("-"))
    }

    func testNestedListByIndentation() {
        XCTAssertEqual(blocks("- a\n  - b\n    - c"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a") + [
                    .list(HXMarkdownList(ordered: false, items: [
                        HXMarkdownList.Item(blocks: paragraph("b") + [
                            .list(HXMarkdownList(ordered: false, items: [
                                HXMarkdownList.Item(blocks: paragraph("c")),
                            ])),
                        ]),
                    ])),
                ]),
            ])),
        ])
    }

    func testListParagraphContinuationAndNestedOrderedUnderBullet() {
        XCTAssertEqual(blocks("- a\n  b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a b")),
            ])),
        ])
        XCTAssertEqual(blocks("- a\n  1. b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a") + [
                    .list(HXMarkdownList(ordered: true, items: [
                        HXMarkdownList.Item(number: 1, blocks: paragraph("b")),
                    ])),
                ]),
            ])),
        ])
    }

    func testLooseListKeepsItsItemsAndDropsTheBlanks() {
        XCTAssertEqual(blocks("- a\n\n- b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a")),
                HXMarkdownList.Item(blocks: paragraph("b")),
            ])),
        ])
        // A blank line and then prose at column zero is the document's, not the item's.
        XCTAssertEqual(blocks("- a\n\nplain"), [
            .list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph("a"))])),
        ] + paragraph("plain"))
    }

    func testIndentedContentAfterABlankStaysInTheItem() {
        XCTAssertEqual(blocks("- a\n\n  b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(blocks: paragraph("a") + paragraph("b")),
            ])),
        ])
    }

    func testTaskMarkers() {
        XCTAssertEqual(blocks("- [ ] todo\n- [x] done\n- [X] also done"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(task: .unchecked, blocks: paragraph("todo")),
                HXMarkdownList.Item(task: .checked, blocks: paragraph("done")),
                HXMarkdownList.Item(task: .checked, blocks: paragraph("also done")),
            ])),
        ])
        XCTAssertEqual(blocks("- [ ] a\n  - [x] b"), [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(task: .unchecked, blocks: paragraph("a") + [
                    .list(HXMarkdownList(ordered: false, items: [
                        HXMarkdownList.Item(task: .checked, blocks: paragraph("b")),
                    ])),
                ]),
            ])),
        ])
        // `[ ]` glued to the text is a bracket, not a checkbox.
        XCTAssertEqual(blocks("- [a] b"), paragraphItem("[a] b"))
    }

    private func paragraphItem(_ text: String) -> [HXMarkdownBlock] {
        [.list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph(text))]))]
    }

    // MARK: quotes

    func testBlockQuoteParsesItsOwnBody() {
        XCTAssertEqual(blocks("> quoted text\n> more"), [
            .quote([.paragraph(inline: [HXMarkdownInline(text: "quoted text more")])]),
        ])
        XCTAssertEqual(blocks("> ## Head\n> - a"), [
            .quote(heading(2, "Head") + [
                .list(HXMarkdownList(ordered: false, items: [HXMarkdownList.Item(blocks: paragraph("a"))])),
            ]),
        ])
        XCTAssertEqual(blocks("> one\ntwo"), [.quote(paragraph("one two"))])
    }

    // MARK: tables

    func testGfmTableWithAlignments() {
        let table = HXMarkdownTable(
            header: [runs("name"), runs("kind")],
            alignments: [.leading, .center],
            rows: [[runs("web"), runs("http")], [runs("cli"), []]]
        )
        XCTAssertEqual(blocks("| name | kind |\n| --- | :-: |\n| web | http |\n| cli |"), [.table(table)])
        XCTAssertEqual(table.columnCount, 2)
    }

    func testTableCellsHoldInlineRuns() {
        XCTAssertEqual(blocks("| a | b |\n|---|---|\n| **x** | `y` |"), [
            .table(HXMarkdownTable(
                header: [runs("a"), runs("b")],
                alignments: [.leading, .leading],
                rows: [[
                    [.init(text: "x", strong: true)],
                    [.init(text: "y", code: true)],
                ]]
            )),
        ])
    }

    func testEscapedPipeStaysInsideItsCell() {
        XCTAssertEqual(blocks("| a | b |\n|---|---|\n| x \\| y | z |"), [
            .table(HXMarkdownTable(
                header: [runs("a"), runs("b")],
                alignments: [.leading, .leading],
                rows: [[runs("x | y"), runs("z")]]
            )),
        ])
    }

    func testPipeLinesWithoutADelimiterRowStayParagraphs() {
        XCTAssertEqual(
            blocks("| a | b |\n| x | y |"),
            paragraph("| a | b | | x | y |")
        )
    }

    // MARK: inline

    func testStrongEmphasisAndCodeAndStrike() {
        XCTAssertEqual(runs("**b**"), [.init(text: "b", strong: true)])
        XCTAssertEqual(runs("*i*"), [.init(text: "i", emphasis: true)])
        XCTAssertEqual(runs("`c`"), [.init(text: "c", code: true)])
        XCTAssertEqual(runs("~~s~~"), [.init(text: "s", strikethrough: true)])
        XCTAssertEqual(runs("a **b** c"), [
            .init(text: "a "), .init(text: "b", strong: true), .init(text: " c"),
        ])
    }

    func testTraitsFoldOntoEveryRunWithinThem() {
        XCTAssertEqual(runs("**b `c` i**"), [
            .init(text: "b ", strong: true),
            .init(text: "c", strong: true, code: true),
            .init(text: " i", strong: true),
        ])
        XCTAssertEqual(runs("*a **b** c*"), [
            .init(text: "a ", emphasis: true),
            .init(text: "b", strong: true, emphasis: true),
            .init(text: " c", emphasis: true),
        ])
    }

    func testCodeSpanContentTakesNoOtherRule() {
        XCTAssertEqual(runs("`**x** [a](b)`"), [.init(text: "**x** [a](b)", code: true)])
        XCTAssertEqual(runs("``a ` b``"), [.init(text: "a ` b", code: true)])
        // One space trimmed off each edge, the way a code span is written when it needs a literal backtick.
        XCTAssertEqual(runs("` x `"), [.init(text: "x", code: true)])
        XCTAssertEqual(runs("no closing `"), [.init(text: "no closing `")])
    }

    func testUnderscoreInsideAWordIsNotEmphasis() {
        XCTAssertEqual(runs("skill_source_name"), [.init(text: "skill_source_name")])
        XCTAssertEqual(runs("_em_"), [.init(text: "em", emphasis: true)])
        XCTAssertEqual(runs("snake_case **and bold**"), [
            .init(text: "snake_case "), .init(text: "and bold", strong: true),
        ])
    }

    func testDelimiterNeedsItsContentBounded() {
        XCTAssertEqual(runs("** bold**"), [.init(text: "** bold**")])
        XCTAssertEqual(runs("2 * 3 * 4"), [.init(text: "2 * 3 * 4")])
        XCTAssertEqual(runs("*a*b"), [.init(text: "a", emphasis: true), .init(text: "b")])
        XCTAssertEqual(runs("***x***"), [.init(text: "x", strong: true, emphasis: true)])
    }

    func testBackslashEscapes() {
        XCTAssertEqual(runs(#"\*not italic\*"#), [.init(text: "*not italic*")])
        XCTAssertEqual(runs(#"a\_b"#), [.init(text: "a_b")])
        XCTAssertEqual(runs(#"c:\\path"#), [.init(text: #"c:\path"#)])
    }

    // MARK: links, and what must never be one

    func testLinkBecomesATappableRun() {
        XCTAssertEqual(runs("[docs](https://harnax.dev/x)"), [
            .init(text: "docs", link: "https://harnax.dev/x"),
        ])
        XCTAssertEqual(runs("see [docs](https://a.b) and more"), [
            .init(text: "see "),
            .init(text: "docs", link: "https://a.b"),
            .init(text: " and more"),
        ])
    }

    func testLinkTitleAndAngleBracketsAndNestedParens() {
        XCTAssertEqual(runs("[a](https://x.y \"note\")"), [.init(text: "a", link: "https://x.y")])
        XCTAssertEqual(runs("[a](<https://x.y>)"), [.init(text: "a", link: "https://x.y")])
        XCTAssertEqual(runs("[a](https://x.y/a(b)c)"), [.init(text: "a", link: "https://x.y/a(b)c")])
    }

    func testUnclosedPseudoLinkIsNeverTappable() {
        for text in [
            "[label](", "[label](https://a.b", "[label(https://a.b)", "[](https://a.b)",
            "[a][b]", "[a](  )", "[a](javascript:alert(1))", "[a](data:text/html;base64,PHN2Zz4=)",
            "[a](//evil.example)", "[a](vbscript:x)", "[a](mailto:)",
        ] {
            XCTAssertFalse(
                runs(text).contains(where: { $0.link != nil }),
                "nothing in \(text) may be tapped"
            )
        }
    }

    func testRefusedDestinationStaysOnScreenAsWritten() {
        XCTAssertEqual(runs("[a](javascript:alert(1))"), [.init(text: "[a](javascript:alert(1))")])
        XCTAssertEqual(runs("[label](",), [.init(text: "[label](")])
    }

    func testValidDestinationAcceptsOnlyWebAndMail() {
        XCTAssertNotNil(HXMarkdownParser.validDestination("https://a.b/c?d=1#e"))
        XCTAssertNotNil(HXMarkdownParser.validDestination("http://a.b"))
        XCTAssertNotNil(HXMarkdownParser.validDestination("mailto:ops@example.com"))
        XCTAssertNil(HXMarkdownParser.validDestination(""))
        XCTAssertNil(HXMarkdownParser.validDestination("tel:+123"))
        XCTAssertNil(HXMarkdownParser.validDestination("https://"))
        XCTAssertNil(HXMarkdownParser.validDestination("not a url at all"))
        XCTAssertNil(HXMarkdownParser.validDestination("javascript:alert(1)"))
    }

    func testAutolink() {
        XCTAssertEqual(runs("<https://a.b>"), [.init(text: "https://a.b", link: "https://a.b")])
        XCTAssertEqual(runs("<mailto:a@b.c>"), [.init(text: "mailto:a@b.c", link: "mailto:a@b.c")])
        XCTAssertEqual(runs("<not a link>"), [.init(text: "<not a link>")])
        XCTAssertEqual(runs("<div>"), [.init(text: "<div>")])
    }

    /// A bare address is GFM's, not CommonMark's, and the scheme is the whole rule: `http` or `https` and then
    /// `://`. Reading the optional `s` as coming after the colon makes every https address in a document plain
    /// text while every http one still works — a one-scheme bug no other test can see.
    func testABareAddressNeedsItsFullScheme() {
        XCTAssertEqual(runs("https://a.b"), [.init(text: "https://a.b", link: "https://a.b")])
        XCTAssertEqual(runs("http://a.b"), [.init(text: "http://a.b", link: "http://a.b")])
        // The scheme is matched case-insensitively; the address itself is kept as written.
        XCTAssertEqual(runs("HTTP://a.b"), [.init(text: "HTTP://a.b", link: "HTTP://a.b")])
        // Only where a word could start: an address caught inside one stays the text it was written as.
        XCTAssertEqual(runs("worthttps://a.b"), [.init(text: "worthttps://a.b")])
        XCTAssertEqual(runs("see https://a.b/x?y=1 and more"), [
            .init(text: "see "),
            .init(text: "https://a.b/x?y=1", link: "https://a.b/x?y=1"),
            .init(text: " and more"),
        ])
        // A scheme that only looks like one stays the words the author wrote.
        XCTAssertEqual(runs("httpx://a.b"), [.init(text: "httpx://a.b")])
        XCTAssertEqual(runs("http:/a.b"), [.init(text: "http:/a.b")])
        XCTAssertEqual(runs("htt://a.b"), [.init(text: "htt://a.b")])
    }

    func testImageBecomesItsAltTextAndNothingElse() {
        // No fetch and no address: the alt text stands where the picture would be.
        XCTAssertEqual(runs("![logo](https://a.b/x.png)"), [.init(text: "logo")])
        // The three pieces share every trait, so they fold back into the sentence they sat in.
        XCTAssertEqual(runs("a ![alt](https://a.b/x.png) b"), [.init(text: "a alt b")])
        XCTAssertEqual(runs("!["), [.init(text: "![")])
    }

    func testLinkLabelKeepsItsOwnEmphasisAndBecomesTappable() {
        // The whole label is tappable, but only the part the author marked is bold.
        XCTAssertEqual(runs("[**bold** link](https://a.b)"), [
            .init(text: "bold", strong: true, link: "https://a.b"),
            .init(text: " link", link: "https://a.b"),
        ])
    }

    // MARK: a document shaped like a real one

    func testFullSkillDocument() {
        let doc = """
        ---
        name: deploy
        description: Deploy the app
        ---

        # Deploy

        Push the build, then **restart** the service:

        ```bash
        make build && appctl restart
        ```

        Steps:

        1. Build
        2. Ship
           - staging
           - prod

        | env | url |
        | --- | --- |
        | prod | https://app.example.com |

        - [ ] verified
        """
        XCTAssertEqual(blocks(doc), [
            .thematicBreak,
            // The closing `---` underlines the two YAML lines, which is how the web console reads them too.
            .heading(level: 2, inline: [.init(text: "name: deploy description: Deploy the app")]),
        ] + heading(1, "Deploy") + [
            .paragraph(inline: [
                .init(text: "Push the build, then "),
                .init(text: "restart", strong: true),
                .init(text: " the service:"),
            ]),
            .code(language: "bash", text: "make build && appctl restart"),
            .paragraph(inline: [.init(text: "Steps:")]),
            .list(HXMarkdownList(ordered: true, items: [
                HXMarkdownList.Item(number: 1, blocks: paragraph("Build")),
                HXMarkdownList.Item(number: 2, blocks: paragraph("Ship") + [
                    .list(HXMarkdownList(ordered: false, items: [
                        HXMarkdownList.Item(blocks: paragraph("staging")),
                        HXMarkdownList.Item(blocks: paragraph("prod")),
                    ])),
                ]),
            ])),
            .table(HXMarkdownTable(
                header: [runs("env"), runs("url")],
                alignments: [.leading, .leading],
                rows: [[
                    [.init(text: "prod")],
                    [.init(text: "https://app.example.com", link: "https://app.example.com")],
                ]]
            )),
        ] + [
            .list(HXMarkdownList(ordered: false, items: [
                HXMarkdownList.Item(task: .unchecked, blocks: paragraph("verified")),
            ])),
        ])
    }
}
