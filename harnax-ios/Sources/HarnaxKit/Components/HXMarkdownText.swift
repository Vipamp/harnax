import SwiftUI

// MARK: - Render

/// What the Markdown blocks draw as, decided as values.
///
/// ``HXMarkdownText`` is a view, and views cannot be instantiated in this target's tests, so every choice a
/// reader could disagree with — the colour of a code span, the glyph of a list item, the font of a heading
/// level, the column a table cell belongs to — is taken here and asserted in `HXMarkdownRenderTests`. The
/// view walks these results and never re-derives them.
enum HXMarkdownRender {
    /// One inline run as it will be painted.
    struct Span: Equatable {
        let text: String
        let strong: Bool
        let emphasis: Bool
        let strikethrough: Bool
        /// A `code` span; it draws monospaced whatever its block's font is.
        let monospaced: Bool
        let slot: PaletteSlot
        /// Set only for a run the app is willing to open. A run without one draws no tap target, which is the
        /// same rule the parser applies to a refused address.
        let destination: URL?
    }

    /// A link takes the brand slot so it reads as the one tappable thing in the sentence, and a code span
    /// takes purple so it reads apart from the prose without a background box. Both slots have a documented
    /// contrast pair against `surface` and `background`, because they land inside a paragraph, not a card.
    static let linkSlot: PaletteSlot = .brand
    static let codeSlot: PaletteSlot = .purple

    /// The tier a fenced block is painted on. A fence has to read as a step above whatever holds the document,
    /// and a `surface` bubble is already that step above the screen — painting it `surface` again would leave
    /// the code fused to the card, which is why the chat hands `surfaceAlt` and the skill page keeps `surface`.
    static func codeBackground(on card: HXMarkdownCard) -> PaletteSlot {
        switch card {
        case .background: return .surface
        case .surface: return .surfaceAlt
        }
    }

    /// The schemes this app hands the system. The parser already refuses everything else, and the renderer
    /// asks again because it is the one that puts the tap target on screen: a `HXMarkdownInline` built from
    /// anywhere else must not be able to open a `file://` path or run a `javascript:` payload.
    static let openableSchemes: Set<String> = ["http", "https", "mailto"]

    /// The address a run may be tapped to open, or `nil` when it may not.
    static func destination(for run: HXMarkdownInline) -> URL? {
        guard let link = run.link, let url = URL(string: link) else { return nil }
        guard let scheme = url.scheme?.lowercased(), openableSchemes.contains(scheme) else { return nil }
        return url
    }

    /// The runs of one line, with the block's own colour handed down to the runs that carry no signal of
    /// their own.
    static func spans(_ runs: [HXMarkdownInline], slot: PaletteSlot) -> [Span] {
        runs.map { run in
            let destination = destination(for: run)
            return Span(
                text: run.text,
                strong: run.strong,
                emphasis: run.emphasis,
                strikethrough: run.strikethrough,
                monospaced: run.code,
                slot: destination != nil ? linkSlot : (run.code ? codeSlot : slot),
                destination: destination
            )
        }
    }

    /// One heading level's drawing.
    struct Heading: Equatable {
        let font: Font
        let weight: Font.Weight
        let slot: PaletteSlot
        /// Whether a hairline runs under it — the two section heads, not the six.
        let rule: Bool
    }

    /// Six sizes, one per level, all of them inside the phone's readable range: a document `#` is not the
    /// screen's title. Levels past the sixth are read off the last entry, so a document that says `#` nine
    /// times draws a heading rather than crashing on an index.
    private static let headingFonts: [Font] = [.title2, .title3, .headline, .subheadline, .callout, .footnote]

    static func heading(level: Int) -> Heading {
        let clamped = min(max(level, 1), headingFonts.count)
        return Heading(
            font: headingFonts[clamped - 1],
            weight: clamped <= 2 ? .bold : .semibold,
            slot: .textPrimary,
            rule: clamped <= 2
        )
    }

    /// The glyph in the marker column of one list item.
    enum Marker: Equatable {
        case bullet
        case ordered(Int)
        case unchecked
        case checked
    }

    /// A task marker is the item's marker once GFM has read one, so it wins over both the bullet and the
    /// number. An ordered item carries the number the author wrote; where the parser recorded none, the
    /// item's own position counts.
    static func marker(for item: HXMarkdownList.Item, ordered: Bool, position: Int) -> Marker {
        switch item.task {
        case .checked: return .checked
        case .unchecked: return .unchecked
        case .absent:
            guard ordered else { return .bullet }
            return .ordered(item.number ?? position + 1)
        }
    }

    /// How far in a block of this depth sits. The step is the tree's own indent, and it stops climbing at
    /// `maxDepth` so a document written with forty levels of bullets still has text left on screen.
    static let columnWidth: CGFloat = 14
    static let maxDepth = 6

    static func indent(depth: Int) -> CGFloat {
        CGFloat(min(max(depth, 0), maxDepth)) * columnWidth
    }

    /// One table cell, placed.
    struct Cell: Equatable {
        let runs: [HXMarkdownInline]
        let alignment: HXMarkdownTable.Alignment
        let header: Bool
    }

    /// The header row first, then the body in document order, each row filled to the widest column count.
    /// `HXMarkdownTable` is public and hand-buildable, so a short row or a missing delimiter word is met with
    /// an empty cell and a left alignment rather than a crash.
    static func rows(of table: HXMarkdownTable) -> [[Cell]] {
        let columns = max(table.columnCount, table.rows.map(\.count).max() ?? 0)
        var rows: [[Cell]] = []
        if !table.header.isEmpty {
            rows.append(cells(of: table.header, columns: columns, alignments: table.alignments, header: true))
        }
        rows += table.rows.map {
            cells(of: $0, columns: columns, alignments: table.alignments, header: false)
        }
        return rows
    }

    private static func cells(
        of row: [[HXMarkdownInline]],
        columns: Int,
        alignments: [HXMarkdownTable.Alignment],
        header: Bool
    ) -> [Cell] {
        (0..<columns).map { column in
            Cell(
                runs: column < row.count ? row[column] : [],
                alignment: column < alignments.count ? alignments[column] : .leading,
                header: header
            )
        }
    }
}

extension HXMarkdownTable.Alignment {
    /// The frame alignment a column's delimiter row asked for.
    var hxAlignment: HorizontalAlignment {
        switch self {
        case .leading: return .leading
        case .center: return .center
        case .trailing: return .trailing
        }
    }
}

// MARK: - View

/// What a Markdown document is painted on, which is the only thing the renderer cannot see for itself.
public enum HXMarkdownCard: Sendable {
    /// On the screen's own background — a skill's body tab.
    case background
    /// Inside a `surface` card — a chat bubble.
    case surface
}

/// Markdown drawn as a document: headings that read as headings, lists with their markers, fences, quotes,
/// tables and tappable links.
///
/// The block structure comes from ``HXMarkdownParser``, which exists because iOS 17's
/// `AttributedString(markdown:)` covers inline syntax only — hand it a skill body and the tables, fences and
/// lists arrive as one flat run of text. What is left after that parser is inline styling, so each line is
/// assembled as a single concatenated `Text`: one wrapping paragraph that still carries bold, italics, a
/// strike, a monospaced command and a link inside the same sentence.
///
/// Prose is deliberately not selectable, because a `Text` whose selection is enabled is not reliable about
/// handing a link run its tap, and a link that does not open is the worse half of that trade. The body's own
/// source stays readable in the files tab, and a fenced block — which holds no link — is selectable.
public struct HXMarkdownText: View {
    private let blocks: [HXMarkdownBlock]
    private let codeBackground: PaletteSlot

    /// Parsed once at construction. A skill body is a few kilobytes and the screen re-creates this view only
    /// when the detail it came from changes, so re-parsing per tap would cost more than holding the blocks.
    /// A chat bubble re-creates it once per frame while a run is talking; the read is a linear scan of text
    /// that is already on screen either way, which is why the frame window is what bounds the cost.
    public init(_ markdown: String, on card: HXMarkdownCard = .background) {
        blocks = HXMarkdownParser.blocks(in: markdown)
        codeBackground = HXMarkdownRender.codeBackground(on: card)
    }

    public var body: some View {
        HXMarkdownBlocks(blocks: blocks, depth: 0, slot: .textPrimary, codeBackground: codeBackground)
    }
}

// MARK: - Blocks

/// One block per line of the document, in order, indented by how deep it sits.
private struct HXMarkdownBlocks: View {
    let blocks: [HXMarkdownBlock]
    let depth: Int
    let slot: PaletteSlot
    let codeBackground: PaletteSlot

    var body: some View {
        VStack(alignment: .leading, spacing: depth == 0 ? 12 : 8) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                HXMarkdownBlockView(block: block, depth: depth, slot: slot, codeBackground: codeBackground)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct HXMarkdownBlockView: View {
    let block: HXMarkdownBlock
    let depth: Int
    let slot: PaletteSlot
    let codeBackground: PaletteSlot

    var body: some View {
        switch block {
        case let .heading(level, inline):
            let heading = HXMarkdownRender.heading(level: level)
            VStack(alignment: .leading, spacing: 4) {
                HXMarkdownInlineText(runs: inline, font: heading.font.weight(heading.weight), slot: heading.slot)
                if heading.rule {
                    rule
                }
            }
            .padding(.top, level <= 2 ? 6 : 0)

        case let .paragraph(inline):
            HXMarkdownInlineText(runs: inline, font: .body, slot: slot)

        case let .list(list):
            HXMarkdownListView(list: list, depth: depth, slot: slot, codeBackground: codeBackground)

        case let .code(language, text):
            HXMarkdownCodeBlock(language: language, text: text, background: codeBackground)

        case let .quote(inner):
            HXMarkdownQuoteView(blocks: inner, depth: depth, codeBackground: codeBackground)

        case let .table(table):
            HXMarkdownTableView(table: table)

        case .thematicBreak:
            rule
                .padding(.vertical, 4)
        }
    }

    /// A hairline from the separator token, so it is visible against both surfaces the palette defines.
    private var rule: some View {
        Rectangle()
            .fill(Color.hx(.separator))
            .frame(height: 1)
            .accessibilityHidden(true)
    }
}

// MARK: - Inline

/// The runs of one block as a single wrapping `Text`.
///
/// Concatenation rather than a stack of views: a bold word, a command in backticks and a link all sit inside
/// the same sentence and have to wrap with it.
private struct HXMarkdownInlineText: View {
    let runs: [HXMarkdownInline]
    let font: Font
    let slot: PaletteSlot

    var body: some View {
        rendered
            .fixedSize(horizontal: false, vertical: true)
    }

    private var rendered: Text {
        var line = Text(verbatim: "")
        for span in HXMarkdownRender.spans(runs, slot: slot) {
            line = line + text(for: span)
        }
        return line
    }

    private func text(for span: HXMarkdownRender.Span) -> Text {
        var piece: Text
        if let destination = span.destination {
            // Only the address rides in the attributed string; the traits below are the same either way, and
            // this is the one path on which SwiftUI hands the run its tap.
            var link = AttributedString(span.text)
            link.link = destination
            piece = Text(link).underline()
        } else {
            piece = Text(verbatim: span.text)
        }
        piece = piece.font(span.monospaced ? font.monospaced() : font)
        if span.strong { piece = piece.bold() }
        if span.emphasis { piece = piece.italic() }
        if span.strikethrough { piece = piece.strikethrough() }
        return piece.foregroundColor(Color.hx(span.slot))
    }
}

// MARK: - List

private struct HXMarkdownListView: View {
    let list: HXMarkdownList
    let depth: Int
    let slot: PaletteSlot
    let codeBackground: PaletteSlot

    /// One column for every marker, so a bullet, a number and a checkbox all put the item's text at the same
    /// x — the alignment a document's list reads on.
    private static let markerColumn: CGFloat = 22

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(list.items.enumerated()), id: \.offset) { position, item in
                HStack(alignment: .top, spacing: 6) {
                    HXMarkdownMarkerView(
                        marker: HXMarkdownRender.marker(for: item, ordered: list.ordered, position: position)
                    )
                    .frame(width: Self.markerColumn, alignment: .trailing)
                    // The item's own blocks carry on under its text, so a list nested in an item indents one
                    // deeper and a fence inside one keeps its own box.
                    HXMarkdownBlocks(blocks: item.blocks, depth: depth + 1, slot: slot,
                                     codeBackground: codeBackground)
                }
                .padding(.leading, HXMarkdownRender.indent(depth: depth))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct HXMarkdownMarkerView: View {
    let marker: HXMarkdownRender.Marker

    var body: some View {
        switch marker {
        case .bullet:
            Circle()
                .fill(Color.hx(.textTertiary))
                .frame(width: 5, height: 5)
                .padding(.top, 9)
                .accessibilityHidden(true)

        case let .ordered(number):
            Text(verbatim: "\(number).")
                .font(.body)
                .foregroundStyle(Color.hx(.textSecondary))

        case .unchecked, .checked:
            let done = marker == .checked
            Image(systemName: done ? "checkmark.square.fill" : "square")
                .font(.body)
                .foregroundStyle(Color.hx(done ? .success : .textTertiary))
                .padding(.top, 2)
        }
    }
}

// MARK: - Code

/// A fenced block: verbatim, monospaced, selectable, and wide enough to keep its own shape.
///
/// The language label is the document's own word for it, so it is a chip rather than a copy key.
private struct HXMarkdownCodeBlock: View {
    let language: String?
    let text: String
    let background: PaletteSlot

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let language {
                HXChip(language, tone: .indigo)
            }
            ScrollView(.horizontal) {
                Text(verbatim: text)
                    .font(.callout.monospaced())
                    .foregroundStyle(Color.hx(.textPrimary))
                    .textSelection(.enabled)
                    .padding(10)
            }
            .background(Color.hx(background), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

// MARK: - Quote

private struct HXMarkdownQuoteView: View {
    let blocks: [HXMarkdownBlock]
    let depth: Int
    let codeBackground: PaletteSlot

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Rectangle()
                .fill(Color.hx(.separator))
                .frame(width: 2)
            HXMarkdownBlocks(blocks: blocks, depth: depth + 1, slot: .textSecondary,
                             codeBackground: codeBackground)
        }
        .padding(.leading, HXMarkdownRender.indent(depth: depth))
    }
}

// MARK: - Table

/// A GFM table, in a grid that shares its column widths across rows and scrolls sideways when the document
/// wrote more columns than a phone can hold.
private struct HXMarkdownTableView: View {
    let table: HXMarkdownTable

    var body: some View {
        let rows = HXMarkdownRender.rows(of: table)
        let columns = Swift.max(rows.first?.count ?? 1, 1)
        ScrollView(.horizontal) {
            Grid(alignment: .topLeading, horizontalSpacing: 12, verticalSpacing: 6) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, cells in
                    GridRow {
                        ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                            HXMarkdownInlineText(
                                runs: cell.runs,
                                font: cell.header ? .callout.weight(.semibold) : .callout,
                                slot: cell.header ? .textPrimary : .textSecondary
                            )
                            .gridColumnAlignment(cell.alignment.hxAlignment)
                        }
                    }
                    if cells.first?.header == true {
                        GridRow {
                            Rectangle()
                                .fill(Color.hx(.separator))
                                .frame(height: 1)
                                .gridCellColumns(columns)
                        }
                    }
                }
            }
            .padding(.vertical, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
