import Foundation

// MARK: - Model

/// One styled piece of a rendered line, in document order.
///
/// The model is flat on purpose. A tree of nested spans is what makes a Markdown renderer hard to test, and a
/// skill document never needs one: the inline pass folds the enclosing traits onto every run it emits, so
/// `` **bold with `code`** `` lands as two runs that both carry `strong` and only the second carries `code`.
public struct HXMarkdownInline: Equatable, Sendable {
    /// The text to paint, with its own delimiters already taken out. A hard line break survives as `\n`.
    public let text: String
    public let strong: Bool
    public let emphasis: Bool
    /// A `` `code` `` span: literal text that took part in no other inline rule.
    public let code: Bool
    public let strikethrough: Bool
    /// A validated `http(s)`/`mailto` address, or `nil` when this run is not tappable.
    public let link: String?

    public init(
        text: String,
        strong: Bool = false,
        emphasis: Bool = false,
        code: Bool = false,
        strikethrough: Bool = false,
        link: String? = nil
    ) {
        self.text = text
        self.strong = strong
        self.emphasis = emphasis
        self.code = code
        self.strikethrough = strikethrough
        self.link = link
    }

    /// The address the renderer hands to `Link`. It is never `nil` beside a `link`, because the parser only
    /// records a destination it could turn into a URL.
    public var destination: URL? {
        guard let link else { return nil }
        return URL(string: link)
    }
}

/// A GFM task-list marker, read off the first line of a list item.
public enum HXMarkdownTaskState: Equatable, Sendable {
    /// The item is an ordinary bullet or number, not a checkbox.
    case absent
    case unchecked
    case checked
}

public struct HXMarkdownList: Equatable, Sendable {
    public struct Item: Equatable, Sendable {
        public let task: HXMarkdownTaskState
        /// The number for an ordered item; `nil` draws a bullet.
        public let number: Int?
        /// The item's own blocks — its paragraph, and any list nested under it — already de-indented, which
        /// is what makes nesting a recursive call rather than a second renderer.
        public let blocks: [HXMarkdownBlock]

        public init(
            task: HXMarkdownTaskState = .absent,
            number: Int? = nil,
            blocks: [HXMarkdownBlock]
        ) {
            self.task = task
            self.number = number
            self.blocks = blocks
        }
    }

    public let ordered: Bool
    public let items: [Item]

    public init(ordered: Bool, items: [Item]) {
        self.ordered = ordered
        self.items = items
    }
}

/// A GFM pipe table. Cells hold inline runs because a table is where skill documents put their commands.
public struct HXMarkdownTable: Equatable, Sendable {
    public enum Alignment: Equatable, Sendable {
        case leading
        case center
        case trailing
    }

    public let header: [[HXMarkdownInline]]
    /// One entry per header column; a body row with more cells than the header gets truncated to them.
    public let alignments: [Alignment]
    public let rows: [[[HXMarkdownInline]]]

    public init(header: [[HXMarkdownInline]], alignments: [Alignment], rows: [[[HXMarkdownInline]]]) {
        self.header = header
        self.alignments = alignments
        self.rows = rows
    }

    public var columnCount: Int { header.count }
}

/// One block of a skill document.
public enum HXMarkdownBlock: Equatable, Sendable {
    case heading(level: Int, inline: [HXMarkdownInline])
    case paragraph(inline: [HXMarkdownInline])
    case list(HXMarkdownList)
    /// A fenced block. `text` is verbatim: no inline rule ever runs over it, and the info string is kept only
    /// as the label beside it.
    case code(language: String?, text: String)
    case quote([HXMarkdownBlock])
    case table(HXMarkdownTable)
    case thematicBreak
}

// MARK: - Parser

/// A hand-written block parser for the Markdown a skill document actually carries.
///
/// It is a pure function of its input, and that is the whole point: iOS 17's `AttributedString(markdown:)`
/// covers inline syntax only, so headings, fences, lists and tables come out of it as one grey run of text,
/// and there is no third-party parser in this package and none is going in. Every rule below is exercisable
/// from a unit test without a view (`Tests/HarnaxKitTests/HXMarkdownTests.swift`).
///
/// What it understands: ATX headings (`#` to `######`) and their setext form, unordered and ordered lists
/// nested by indentation, GFM task markers, fenced code in both fence spellings, paragraphs, block quotes,
/// thematic breaks, GFM pipe tables, and inline `**strong**`, `*emphasis*`, `` `code` ``, `~~strike~~`,
/// `[label](address)`, `<https://autolink>` and backslash escapes.
///
/// What it deliberately does not: HTML of any kind (a tag is literal text, so there is nothing to inject),
/// reference links and footnotes (both stay on screen as the source that was written), remote images (the alt
/// text stands in for a fetch), and indented code blocks (four leading spaces are part of a nested list here,
/// which is the trade the skill documents argue for).
public enum HXMarkdownParser {
    /// The document as blocks. Empty input yields no blocks, and a document nothing matched yields one
    /// paragraph — never a failure, because a skill body nobody can read is worse than one styled plainly.
    public static func blocks(in text: String) -> [HXMarkdownBlock] {
        parse(normalize(text))
    }

    /// One line of body text as inline runs. Public so a caller can style a stored string without a block.
    public static func inlineRuns(_ text: String) -> [HXMarkdownInline] {
        runs(Array(text), strong: false, emphasis: false, strikethrough: false)
    }

    // MARK: blocks

    private static func parse(_ lines: [String]) -> [HXMarkdownBlock] {
        var blocks: [HXMarkdownBlock] = []
        var paragraph: [String] = []
        var index = 0

        while index < lines.count {
            let line = lines[index]

            if isBlank(line) {
                paragraphBlock(paragraph, into: &blocks)
                paragraph = []
                index += 1
                continue
            }

            // A line of only `=` or only `-` under an open paragraph is its underline, not a break. This has
            // to be asked before the break rule, or a skill's YAML frontmatter would close as a rule and open
            // as one two lines later.
            if !paragraph.isEmpty, let level = setextLevel(of: line) {
                blocks.append(.heading(level: level, inline: inlineRuns(join(paragraph))))
                paragraph = []
                index += 1
                continue
            }

            if !paragraph.isEmpty, opens(lines, at: index) {
                paragraphBlock(paragraph, into: &blocks)
                paragraph = []
            }

            if let (block, next) = fencedCode(lines, from: index) {
                blocks.append(block)
                index = next
                continue
            }
            if isThematicBreak(line) {
                blocks.append(.thematicBreak)
                index += 1
                continue
            }
            if let (level, text) = atxHeading(line) {
                // `#` on its own is an empty box on screen, so it is consumed and not emitted.
                if !text.isEmpty {
                    blocks.append(.heading(level: level, inline: inlineRuns(text)))
                }
                index += 1
                continue
            }
            if isQuote(line) {
                let (block, next) = quote(lines, from: index)
                blocks.append(block)
                index = next
                continue
            }
            if let (block, next) = table(lines, from: index) {
                blocks.append(block)
                index = next
                continue
            }
            if marker(of: line) != nil {
                let (block, next) = list(lines, from: index)
                blocks.append(block)
                index = next
                continue
            }

            paragraph.append(stripIndent(line))
            index += 1
        }
        paragraphBlock(paragraph, into: &blocks)
        return blocks
    }

    private static func paragraphBlock(_ lines: [String], into blocks: inout [HXMarkdownBlock]) {
        guard !lines.isEmpty else { return }
        blocks.append(.paragraph(inline: inlineRuns(join(lines))))
    }

    /// Whether this line starts something that has to break an open paragraph.
    private static func opens(_ lines: [String], at index: Int) -> Bool {
        let line = lines[index]
        if isFenceOpener(line) { return true }
        if isThematicBreak(line) { return true }
        if atxHeading(line) != nil { return true }
        if isQuote(line) { return true }
        if marker(of: line) != nil { return true }
        if table(lines, from: index) != nil { return true }
        return false
    }

    // MARK: fenced code

    private static func isFenceOpener(_ line: String) -> Bool {
        fenceOpener(line) != nil
    }

    /// The fence character, how many of it, and the info string left over.
    private static func fenceOpener(_ line: String) -> (Character, Int, String)? {
        let body = String(dropIndent(line))
        guard let first = body.first, first == "`" || first == "~" else { return nil }
        let count = body.prefix(while: { $0 == first }).count
        guard count >= 3 else { return nil }
        let rest = String(body.dropFirst(count))
        // A backtick fence may name a language; a tilde fence may not, and neither may hold its own character.
        if first == "`", rest.contains("`") { return nil }
        return (first, count, rest.trimmingCharacters(in: .whitespaces))
    }

    private static func fencedCode(_ lines: [String], from start: Int) -> (HXMarkdownBlock, Int)? {
        guard let (character, length, info) = fenceOpener(lines[start]) else { return nil }
        let carried = indent(of: lines[start])
        var index = start + 1
        var body: [String] = []
        while index < lines.count {
            let line = String(dropIndent(lines[index])).trimmingCharacters(in: .whitespaces)
            if line.count >= length, line.allSatisfy({ $0 == character }) {
                return (.code(language: codeLabel(info), text: body.joined(separator: "\n")), index + 1)
            }
            body.append(String(dropIndent(lines[index], upTo: carried)))
            index += 1
        }
        // An unclosed fence runs to the end of the document: the code stays code, because half a program
        // re-interpreted as prose is the worse failure.
        return (.code(language: codeLabel(info), text: body.joined(separator: "\n")), index)
    }

    /// The info string is the document's own word for what language it wrote, so it is shown verbatim — but
    /// only when it really looks like one name, because the fence came out of a fetched skill.
    static func codeLabel(_ info: String) -> String? {
        let name = info.split(whereSeparator: { $0 == " " || $0 == "," || $0 == "{" }).first.map(String.init) ?? ""
        guard name.count <= 24 else { return nil }
        guard let first = name.first, first.isASCII, first.isLetter else { return nil }
        return name.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "-" || $0 == "." || $0 == "_") }
            ? name
            : nil
    }

    // MARK: headings, breaks, quotes

    private static func atxHeading(_ line: String) -> (Int, String)? {
        let body = String(dropIndent(line))
        let level = body.prefix(while: { $0 == "#" }).count
        guard level >= 1, level <= 6 else { return nil }
        let rest = String(body.dropFirst(level))
        guard rest.isEmpty || rest.first == " " || rest.first == "\t" else { return nil }
        var text = rest.trimmingCharacters(in: .whitespaces)
        // The optional closing sequence, and only that: `## name ##` says `name`, while `## C#` says `C#`.
        let trailing = text.reversed().prefix(while: { $0 == "#" }).count
        if trailing > 0 {
            let head = String(text.dropLast(trailing))
            if head.isEmpty || head.hasSuffix(" ") || head.hasSuffix("\t") {
                text = head.trimmingCharacters(in: .whitespaces)
            }
        }
        return (level, text)
    }

    private static func setextLevel(of line: String) -> Int? {
        let body = String(dropIndent(line))
        guard !body.isEmpty, body == body.filter({ $0 != " " }) else { return nil }   // spaces rule it out
        guard body.allSatisfy({ $0 == "-" }) || body.allSatisfy({ $0 == "=" }) else { return nil }
        return body.first == "=" ? 1 : 2
    }

    private static func isThematicBreak(_ line: String) -> Bool {
        let body = String(dropIndent(line)).replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\t", with: "")
        guard body.count >= 3 else { return false }
        return body.allSatisfy { $0 == "-" } || body.allSatisfy { $0 == "*" } || body.allSatisfy { $0 == "_" }
    }

    private static func isQuote(_ line: String) -> Bool {
        String(dropIndent(line)).hasPrefix(">")
    }

    private static func quote(_ lines: [String], from start: Int) -> (HXMarkdownBlock, Int) {
        var body: [String] = []
        var index = start
        while index < lines.count {
            let line = lines[index]
            if isQuote(line) {
                var rest = String(dropIndent(line).dropFirst())
                if rest.hasPrefix(" ") { rest = String(rest.dropFirst()) }
                body.append(rest)
                index += 1
                continue
            }
            // Lazy continuation: the quoted paragraph is still running and nothing new opens here.
            if !isBlank(line), !body.isEmpty, !opens(lines, at: index) {
                body.append(stripIndent(line))
                index += 1
                continue
            }
            break
        }
        return (.quote(parse(body)), index)
    }

    // MARK: lists

    private struct Marker {
        /// Columns the marker takes, indentation counted in.
        let width: Int
        let ordered: Bool
        let number: Int?
        /// `-`, `*` or `+` for an unordered item, `nil` for a numbered one. A sibling that spells the marker
        /// differently starts a new list, which is the only difference between `* a / + b` and `* a / * b`.
        let bullet: Character?
    }

    private static func marker(of line: String) -> Marker? {
        let chars = Array(line)
        var index = 0
        while index < chars.count, chars[index] == " " { index += 1 }
        guard index <= 3, index < chars.count else { return nil }
        var ordered = false
        var number: Int?
        var length = 0
        let head = chars[index]
        var bullet: Character?
        if head == "-" || head == "*" || head == "+" {
            length = 1
            bullet = head
        } else if head.isASCII, head.isNumber {
            var cursor = index
            while cursor < chars.count, chars[cursor].isASCII, chars[cursor].isNumber { cursor += 1 }
            guard cursor - index <= 9, cursor < chars.count, chars[cursor] == "." || chars[cursor] == ")" else {
                return nil
            }
            guard let value = Int(String(chars[index..<cursor])) else { return nil }
            ordered = true
            number = value
            length = cursor - index + 1
        } else {
            return nil
        }
        var spaces = 0
        var cursor = index + length
        while cursor < chars.count, chars[cursor] == " " || chars[cursor] == "\t" {
            spaces += 1
            cursor += 1
        }
        // One space at least, or `**bold**` at the head of a line would read as a bullet; four at most, which
        // is where the content has stopped belonging to the marker.
        guard spaces >= 1, spaces <= 4, cursor < chars.count else { return nil }
        return Marker(width: index + length + spaces, ordered: ordered, number: number, bullet: bullet)
    }

    private static func list(_ lines: [String], from start: Int) -> (HXMarkdownBlock, Int) {
        // `parse` only gets here with a marker under the cursor, so the fallback is a formality.
        guard let head = marker(of: lines[start]) else {
            return (.list(HXMarkdownList(ordered: false, items: [])), start + 1)
        }
        var items: [HXMarkdownList.Item] = []
        var index = start
        while index < lines.count {
            let base = lines[index]
            guard let mark = marker(of: base), mark.ordered == head.ordered, mark.bullet == head.bullet else { break }
            let column = mark.width
            var body = [String(dropFirst(base, column))]
            var task = HXMarkdownTaskState.absent
            if let (state, rest) = checkbox(body[0]) {
                task = state
                body[0] = rest
            }
            index += 1
            var blanks = 0
            while index < lines.count {
                let line = lines[index]
                if isBlank(line) {
                    blanks += 1
                    index += 1
                    continue
                }
                let spaces = indent(of: line)
                // At or past the item's content column the line is the item's, whatever it holds: that is what
                // makes an indented `- ` below this one a nested list rather than a sibling.
                if spaces < column {
                    if marker(of: line) != nil || opens(lines, at: index) { break }
                    // A blank line has closed the item's paragraph, so what follows at column zero is the
                    // document's prose. Lazy continuation does not cross a blank.
                    if blanks > 0 { break }
                }
                if blanks > 0 {
                    body.append(contentsOf: Array(repeating: "", count: blanks))
                    blanks = 0
                }
                body.append(String(dropFirst(line, Swift.min(spaces, column))))
                index += 1
            }
            items.append(HXMarkdownList.Item(task: task, number: mark.number, blocks: parse(body)))
        }
        return (.list(HXMarkdownList(ordered: head.ordered, items: items)), index)
    }

    /// A GFM task marker, and the item's text without it.
    private static func checkbox(_ line: String) -> (HXMarkdownTaskState, String)? {
        let chars = Array(line)
        guard chars.first == "[" else { return nil }
        guard chars.count >= 3, chars[2] == "]" else { return nil }
        let state: HXMarkdownTaskState
        switch chars[1] {
        case " ": state = .unchecked
        case "x", "X": state = .checked
        default: return nil
        }
        var rest = String(chars.dropFirst(3))
        guard rest.hasPrefix(" ") || rest.hasPrefix("\t") else { return nil }
        rest = String(dropIndent(rest))
        return (state, rest)
    }

    // MARK: tables

    private static func table(_ lines: [String], from start: Int) -> (HXMarkdownBlock, Int)? {
        guard start + 1 < lines.count, lines[start].contains("|") else { return nil }
        guard let alignments = delimiterRow(lines[start + 1]) else { return nil }
        let header = cells(of: lines[start])
        guard header.count >= 2, alignments.count >= 2, header.count == alignments.count else { return nil }
        var rows: [[[HXMarkdownInline]]] = []
        var index = start + 2
        while index < lines.count {
            let line = lines[index]
            // Asked directly rather than through `opens`, which calls back in here for the next line and would
            // turn a table-heavy document into an exponential walk.
            guard !isBlank(line), line.contains("|"), atxHeading(line) == nil, !isQuote(line),
                  !isFenceOpener(line) else { break }
            let row = cells(of: line)
            rows.append((0..<header.count).map { column in
                column < row.count ? inlineRuns(row[column]) : []
            })
            index += 1
        }
        return (
            .table(
                HXMarkdownTable(
                    header: header.map(inlineRuns),
                    alignments: alignments,
                    rows: rows
                )
            ),
            index
        )
    }

    /// The `| --- | :-: |` row that tells a pair of pipe lines apart from two paragraphs.
    private static func delimiterRow(_ line: String) -> [HXMarkdownTable.Alignment]? {
        let body = String(dropIndent(line)).trimmingCharacters(in: .whitespaces)
        guard body.contains("-") else { return nil }
        var cells = splitCells(body)
        if cells.first?.isEmpty == true { cells.removeFirst() }
        if cells.last?.isEmpty == true { cells.removeLast() }
        guard !cells.isEmpty else { return nil }
        return try? cells.map { cell in
            let value = cell.trimmingCharacters(in: .whitespaces)
            guard value.count >= 1, value.allSatisfy({ $0 == "-" || $0 == ":" }), value.contains("-") else {
                throw ParseRefusal.notADelimiter
            }
            if value.hasPrefix(":"), value.hasSuffix(":") { return .center }
            if value.hasSuffix(":") { return .trailing }
            return .leading
        }
    }

    private enum ParseRefusal: Error { case notADelimiter }

    private static func cells(of line: String) -> [String] {
        var cells = splitCells(String(dropIndent(line)).trimmingCharacters(in: .whitespaces))
        if cells.first?.isEmpty == true { cells.removeFirst() }
        if cells.last?.isEmpty == true { cells.removeLast() }
        return cells.map { cell in
            cell.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\\|", with: "|")
        }
    }

    /// Split on the pipes that are not escaped, so a cell can hold one.
    private static func splitCells(_ body: String) -> [String] {
        var cells: [String] = []
        var current = ""
        let chars = Array(body)
        var cursor = 0
        while cursor < chars.count {
            let character = chars[cursor]
            if character == "\\", cursor + 1 < chars.count, chars[cursor + 1] == "|" {
                current.append("\\|")
                cursor += 2
                continue
            }
            if character == "|" {
                cells.append(current)
                current = ""
                cursor += 1
                continue
            }
            current.append(character)
            cursor += 1
        }
        cells.append(current)
        return cells
    }

    // MARK: paragraphs and lines

    /// The document's newlines, with the soft breaks turned back into spaces — except where the author ended a
    /// line with two spaces or a backslash, which is a break they meant.
    private static func join(_ lines: [String]) -> String {
        var out = ""
        // The marker sits at the end of the line above the break, so the decision has to survive an iteration.
        var breaking = false
        for (position, line) in lines.enumerated() {
            let backslash = line.hasSuffix("\\") && position + 1 < lines.count
            var text = line.trimmingCharacters(in: .whitespaces)
            if backslash { text = String(text.dropLast()) }
            if out.isEmpty {
                out = text
            } else if breaking {
                out += "\n" + text
            } else {
                // Two Han characters need no space between them, and inserting one is the mistake a plain join
                // makes on a Chinese skill document.
                let needsGap = !(isHan(out.last) && isHan(text.first))
                out += needsGap ? " " + text : text
            }
            breaking = backslash || line.hasSuffix("  ")
        }
        return out
    }

    private static func isHan(_ character: Character?) -> Bool {
        guard let scalar = character?.unicodeScalars.first else { return false }
        let value = scalar.value
        return (0x4E00...0x9FFF).contains(value) || (0x3040...0x30FF).contains(value)
            || (0xAC00...0xD7AF).contains(value) || (0x3400...0x4DBF).contains(value)
    }

    static func normalize(_ text: String) -> [String] {
        text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
    }

    private static func isBlank(_ line: String) -> Bool {
        line.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Leading spaces, up to the three that still count as block content.
    private static func indent(of line: String) -> Int {
        var count = 0
        for character in line {
            if character == " " { count += 1 } else if character == "\t" { count += 4 } else { break }
        }
        return count
    }

    /// The line without its leading whitespace, which is all the indentation this subset cares about.
    private static func dropIndent(_ line: String) -> Substring {
        line.drop(while: { $0 == " " || $0 == "\t" })
    }

    /// At most `columns` of leading whitespace, which is how a fenced body keeps the indentation the author
    /// wrote once the fence's own indent has been paid for.
    private static func dropIndent(_ line: String, upTo columns: Int) -> Substring {
        var left = columns
        var index = line.startIndex
        while left > 0, index < line.endIndex, line[index] == " " || line[index] == "\t" {
            if line[index] == "\t" { left -= 4 } else { left -= 1 }
            index = line.index(after: index)
        }
        return line[index...]
    }

    private static func stripIndent(_ line: String) -> String {
        String(dropIndent(line))
    }

    private static func dropFirst(_ line: String, _ columns: Int) -> String {
        String(Array(line).dropFirst(Swift.min(columns, line.count)))
    }

    // MARK: inline

    private static func runs(
        _ chars: [Character],
        strong: Bool,
        emphasis: Bool,
        strikethrough: Bool
    ) -> [HXMarkdownInline] {
        var out: [HXMarkdownInline] = []
        var plain = ""

        func flushPlain() {
            guard !plain.isEmpty else { return }
            out.append(
                HXMarkdownInline(
                    text: plain,
                    strong: strong,
                    emphasis: emphasis,
                    strikethrough: strikethrough
                )
            )
            plain = ""
        }

        var index = 0
        while index < chars.count {
            let character = chars[index]
            switch character {
            case "\\":
                if index + 1 < chars.count, isASCIIPunctuation(chars[index + 1]) {
                    plain.append(chars[index + 1])
                    index += 2
                } else {
                    plain.append(character)
                    index += 1
                }
            case "`":
                let length = run(of: "`", at: index, in: chars)
                if let close = closingRun(of: "`", length: length, from: index + length, in: chars, exact: true) {
                    flushPlain()
                    out.append(
                        HXMarkdownInline(
                            text: codeText(chars, from: index + length, to: close),
                            strong: strong,
                            emphasis: emphasis,
                            code: true,
                            strikethrough: strikethrough
                        )
                    )
                    index = close + length
                } else {
                    plain.append(String(repeating: "`", count: length))
                    index += length
                }
            case "!" where index + 1 < chars.count && chars[index + 1] == "[":
                if let link = linkLike(chars, from: index + 1) {
                    flushPlain()
                    // No fetch, no address: an image's alt text stands where the picture would be, unlinked,
                    // because a management app that loads a remote image on a document's say-so is a privacy
                    // leak with a nice face.
                    out.append(contentsOf: inherit(
                        runs(Array(link.label), strong: strong, emphasis: emphasis, strikethrough: strikethrough),
                        strong: strong,
                        emphasis: emphasis,
                        strikethrough: strikethrough
                    ))
                    index = link.next
                } else {
                    plain.append(character)
                    index += 1
                }
            case "[":
                if let link = linkLike(chars, from: index), let destination = validDestination(link.destination) {
                    flushPlain()
                    out.append(contentsOf: runs(Array(link.label), strong: strong, emphasis: emphasis, strikethrough: strikethrough)
                        .map { HXMarkdownInline(text: $0.text, strong: $0.strong, emphasis: $0.emphasis, code: $0.code, strikethrough: $0.strikethrough, link: destination) })
                    index = link.next
                } else {
                    // Either the brackets never closed or the address was refused. Both stay as the text the
                    // author wrote, so nothing becomes tappable that was not a link.
                    plain.append(character)
                    index += 1
                }
            case "h", "H":
                if let (address, next) = bareURL(chars, at: index) {
                    flushPlain()
                    out.append(
                        HXMarkdownInline(
                            text: address,
                            strong: strong,
                            emphasis: emphasis,
                            strikethrough: strikethrough,
                            link: address
                        )
                    )
                    index = next
                } else {
                    plain.append(character)
                    index += 1
                }
            case "<":
                if let (address, next) = autolink(chars, from: index) {
                    flushPlain()
                    out.append(
                        HXMarkdownInline(
                            text: address,
                            strong: strong,
                            emphasis: emphasis,
                            strikethrough: strikethrough,
                            link: address
                        )
                    )
                    index = next
                } else {
                    plain.append(character)
                    index += 1
                }
            case "~":
                let length = run(of: "~", at: index, in: chars)
                if length >= 2, let close = closingRun(of: "~", length: 2, from: index + 2, in: chars), close > index + 2 {
                    flushPlain()
                    out.append(contentsOf: runs(
                        Array(chars[(index + 2)..<close]),
                        strong: strong,
                        emphasis: emphasis,
                        strikethrough: true
                    ))
                    index = close + 2
                } else {
                    plain.append(String(repeating: "~", count: length))
                    index += length
                }
            case "*", "_":
                if let (next, inner) = delimiter(chars, at: index, strong: strong, emphasis: emphasis, strikethrough: strikethrough) {
                    flushPlain()
                    out.append(contentsOf: inner)
                    index = next
                } else {
                    let length = run(of: character, at: index, in: chars)
                    plain.append(String(repeating: character, count: length))
                    index += length
                }
            default:
                plain.append(character)
                index += 1
            }
        }
        flushPlain()
        return merged(out)
    }

    /// `*`, `**`, `_` and `__`: the closer has to exist, the content has to be bounded by non-spaces, and an
    /// underscore inside a word is a separator, not a delimiter — `skill_source_name` is what makes that rule
    /// worth its lines in a skill document.
    /// `*`, `**`, `***` and their underscore spellings. The closer has to exist, the content has to be bounded
    /// by non-spaces, and an underscore inside a word is a separator, not a delimiter — `skill_source_name` is
    /// what makes that rule worth its lines in a skill document.
    private static func delimiter(
        _ chars: [Character],
        at index: Int,
        strong: Bool,
        emphasis: Bool,
        strikethrough: Bool
    ) -> (Int, [HXMarkdownInline])? {
        let character = chars[index]
        let opening = run(of: character, at: index, in: chars)
        if character == "_", index > 0, isWordCharacter(chars[index - 1]) { return nil }
        let content = index + opening
        guard content < chars.count, !chars[content].isWhitespace else { return nil }
        // Three markers carry both traits, two carry strong, one carries emphasis. A run that cannot find its
        // closer at one width steps down rather than giving up on the phrase.
        for level in [3, 2, 1] where level <= opening {
            guard let close = closingRun(of: character, length: level, from: content, in: chars, valid: { start in
                // A closer is bounded by non-spaces on both sides, and an underscore may not close inside a
                // word. A run that fails this is not the end of the phrase, so the search moves past it:
                // `*a **b** c*` has to reach the last asterisk, not stop at the strong one.
                start > content
                    && !chars[start - 1].isWhitespace
                    && !(character == "_" && start + level < chars.count && isWordCharacter(chars[start + level]))
            }) else { continue }
            return (
                close + level,
                runs(
                    Array(chars[content..<close]),
                    strong: strong || level >= 2,
                    emphasis: emphasis || level != 2,
                    strikethrough: strikethrough
                )
            )
        }
        return nil
    }

    private static func inherit(
        _ runs: [HXMarkdownInline],
        strong: Bool,
        emphasis: Bool,
        strikethrough: Bool
    ) -> [HXMarkdownInline] {
        runs.map {
            HXMarkdownInline(
                text: $0.text,
                strong: $0.strong || strong,
                emphasis: $0.emphasis || emphasis,
                code: $0.code,
                strikethrough: $0.strikethrough || strikethrough,
                link: $0.link
            )
        }
    }

    private static func merged(_ runs: [HXMarkdownInline]) -> [HXMarkdownInline] {
        var out: [HXMarkdownInline] = []
        for run in runs {
            if let last = out.last,
               last.strong == run.strong, last.emphasis == run.emphasis, last.code == run.code,
               last.strikethrough == run.strikethrough, last.link == run.link {
                out[out.count - 1] = HXMarkdownInline(
                    text: last.text + run.text,
                    strong: last.strong,
                    emphasis: last.emphasis,
                    code: last.code,
                    strikethrough: last.strikethrough,
                    link: last.link
                )
                continue
            }
            out.append(run)
        }
        return out
    }

    /// One space trimmed off each edge, the way a code span reads `` ` a ` `` as `a`.
    private static func codeText(_ chars: [Character], from: Int, to: Int) -> String {
        var body = String(chars[from..<to])
        if body.count >= 2, body.hasPrefix(" "), body.hasSuffix(" "),
           !body.trimmingCharacters(in: .whitespaces).isEmpty {
            body = String(body.dropFirst().dropLast())
        }
        return body
    }

    private static func run(of character: Character, at index: Int, in chars: [Character]) -> Int {
        var count = 0
        while index + count < chars.count, chars[index + count] == character { count += 1 }
        return max(count, 1)
    }

    /// The next run of `length` of the character that can close what opened at the caller's position. A code
    /// span needs the very same count (`exact`); an asterisk run may close on a longer one. `valid` lets the
    /// caller reject a run that is in the wrong place — a rejected run is stepped over, not treated as the end
    /// of the search, because the real closer may be further along the line.
    private static func closingRun(
        of character: Character,
        length: Int,
        from: Int,
        in chars: [Character],
        exact: Bool = false,
        valid: (Int) -> Bool = { _ in true }
    ) -> Int? {
        var wider: Int?
        var index = from
        while index < chars.count {
            if chars[index] == character {
                let count = run(of: character, at: index, in: chars)
                if count == length, valid(index) { return index }
                // A longer run can close a shorter opener, but only when no run of the very same width is
                // waiting further along the line: `*a **b** c*` ends at the last asterisk, not at the pair in
                // the middle.
                if !exact, count > length, valid(index), wider == nil { wider = index }
                index += count
                continue
            }
            if chars[index] == "\\", index + 1 < chars.count { index += 2; continue }
            index += 1
        }
        return wider
    }

    private static func isWordCharacter(_ character: Character) -> Bool {
        character.isLetter || character.isNumber || character == "_"
    }

    private static func isASCIIPunctuation(_ character: Character) -> Bool {
        character.isASCII && character.isPunctuation
    }

    // MARK: links

    private struct LinkParts {
        let label: String
        let destination: String
        /// Where to carry on after the closing parenthesis.
        let next: Int
    }

    /// `[label](destination)`, from the bracket. `nil` means it is not a link at all: an unclosed bracket, a
    /// reference-style pair, or a parenthesis that never came back.
    private static func linkLike(_ chars: [Character], from start: Int) -> LinkParts? {
        guard start < chars.count, chars[start] == "[" else { return nil }
        var index = start + 1
        var depth = 1
        var label = ""
        while index < chars.count {
            let character = chars[index]
            if character == "\\", index + 1 < chars.count {
                label.append(chars[index + 1])
                index += 2
                continue
            }
            if character == "[" { depth += 1 }
            if character == "]" {
                depth -= 1
                if depth == 0 { break }
            }
            label.append(character)
            index += 1
        }
        guard depth == 0, index < chars.count, chars[index] == "]", !label.isEmpty else { return nil }
        var cursor = index + 1
        guard cursor < chars.count, chars[cursor] == "(" else { return nil }
        cursor += 1

        var destination = ""
        var angle = false
        var balance = 0
        while cursor < chars.count {
            let character = chars[cursor]
            if character == "\\", cursor + 1 < chars.count, isASCIIPunctuation(chars[cursor + 1]) {
                destination.append(chars[cursor + 1])
                cursor += 2
                continue
            }
            if angle {
                if character == ">" {
                    angle = false
                    cursor += 1
                    continue
                }
                destination.append(character)
                cursor += 1
                continue
            }
            if character == "<", destination.isEmpty {
                angle = true
                cursor += 1
                continue
            }
            if character == "(" { balance += 1 }
            if character == ")" {
                if balance == 0 { break }
                balance -= 1
            }
            if character.isWhitespace { break }
            destination.append(character)
            cursor += 1
        }
        if chars[safe: cursor] != ")" {
            // A title may sit between the address and the closer: `[a](url "note")`.
            var probe = cursor
            while chars[safe: probe] == " " || chars[safe: probe] == "\t" { probe += 1 }
            if let end = skipTitle(chars, from: probe) {
                probe = end
                while chars[safe: probe] == " " || chars[safe: probe] == "\t" { probe += 1 }
            }
            guard chars[safe: probe] == ")" else { return nil }
            cursor = probe
        }
        return LinkParts(label: label, destination: destination, next: cursor + 1)
    }

    private static func skipTitle(_ chars: [Character], from: Int) -> Int? {
        guard let opener = chars[safe: from] else { return nil }
        let closer: Character = opener == "(" ? ")" : opener
        guard opener == "\"" || opener == "'" || opener == "(" else { return nil }
        var index = from + 1
        while index < chars.count {
            if chars[index] == "\\", index + 1 < chars.count { index += 2; continue }
            if chars[index] == closer { return index + 1 }
            index += 1
        }
        return nil
    }

    /// `<https://example.com>` as written in the document.
    private static func autolink(_ chars: [Character], from start: Int) -> (String, Int)? {
        guard let closer = chars[start...].firstIndex(of: ">") else { return nil }
        let body = chars[(start + 1)..<closer]
        guard !body.isEmpty, !body.contains(where: { $0.isWhitespace }) else { return nil }
        let text = String(body)
        guard validDestination(text) != nil else { return nil }
        return (text, closer + 1)
    }

    /// GFM's bare address: `https://host/path` with no brackets around it. It is only a link where a word could
    /// start — at the head of the text or after a space — so an address caught inside unclosed `[label](` syntax
    /// stays the text it was written as. The scan stops at whitespace and at the characters that cannot be in a
    /// bare address, keeps a balanced pair of parentheses (a wiki URL ends in one), and hands the punctuation
    /// that closed a sentence back to the text.
    private static func bareURL(_ chars: [Character], at index: Int) -> (String, Int)? {
        guard index == 0 || chars[index - 1].isWhitespace else { return nil }
        var cursor = index
        for expected in "http" {
            let want = String(expected).lowercased()
            guard cursor < chars.count, chars[cursor].lowercased() == want else { return nil }
            cursor += 1
        }
        if cursor < chars.count, chars[cursor].lowercased() == "s" { cursor += 1 }
        guard cursor + 2 < chars.count, chars[cursor] == ":", chars[cursor + 1] == "/", chars[cursor + 2] == "/"
        else { return nil }
        cursor += 3
        var end = cursor
        var depth = 0
        while end < chars.count {
            let character = chars[end]
            if character.isWhitespace || "<>\"'`".contains(character) { break }
            if character == "(" { depth += 1 }
            if character == ")" {
                if depth == 0 { break }
                depth -= 1
            }
            end += 1
        }
        guard end > cursor else { return nil }
        while end > cursor, ".,;:!?*_~".contains(chars[end - 1]) { end -= 1 }
        let text = String(chars[index..<end])
        guard let address = validDestination(text) else { return nil }
        return (address, end)
    }

    /// The address, only when it is one the app is willing to hand to the system: `http`, `https` or `mailto`
    /// with something after it. Everything else — `javascript:`, a bare word, a scheme-relative `//host` — is
    /// refused, and a refused address is never tappable.
    static func validDestination(_ raw: String) -> String? {
        let body = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return nil }
        guard !body.contains(where: { $0.isWhitespace || $0 == "<" || $0 == ">" }) else { return nil }
        guard let url = URL(string: body), let scheme = url.scheme?.lowercased() else { return nil }
        switch scheme {
        case "http", "https":
            guard let host = url.host, !host.isEmpty else { return nil }
            return url.absoluteString
        case "mailto":
            guard body.count > "mailto:".count else { return nil }
            return url.absoluteString
        default:
            return nil
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
