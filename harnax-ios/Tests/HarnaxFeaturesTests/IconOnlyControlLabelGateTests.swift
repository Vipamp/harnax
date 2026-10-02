import XCTest

/// Gate for requirement #1 of the accessibility row in DESIGN.md: 全部交互控件有可读标签与轨迹 — every
/// interactive control has a readable label.
///
/// A control whose `label:` block draws nothing but an SF Symbol is silent: the glyph is not text, so what
/// VoiceOver announces is the bare trait — "Button", "Pop up button" — and a screen-reader user reaches the
/// status filter, the create button and the row overflow with nothing to tell them which is which. Every
/// control of that shape in either walked root now carries the catalogue's word for itself
/// (`state.filter.status`, `model.create`, `state.action.more`, `chat.action.jumpToBottom`, …), and this gate
/// is what stops the next one from shipping without.
///
/// Nothing is exempted: `Agents/` and `Teams/`, whose forms are being written elsewhere, are walked like the
/// rest and pass as they stand, so a control added there is charged the day it lands.
///
/// It reads source text for the same reason the colour, copy and O7 gates do: no view is reachable from
/// `swift test` — the screens are instantiated by `Harnax.xcodeproj` through `App/HarnaxDebugScreens.swift` —
/// so a control that loses its label is neither a type error nor a failing assertion. It is a screen that
/// quietly stops being usable.
///
/// Blocks are taken by brace balance over comment-free text, and the chain that answers for a block is walked
/// to its last dot — never by a fixed line window. An icon-only label is as short as one line
/// (`Image(systemName: "plus")`) and as long as eight with a ternary and four styling modifiers, and its
/// `accessibilityLabel` can sit two lines below its own comment, which is exactly where counting lines below
/// mis-counts by an order of magnitude.
final class IconOnlyControlLabelGateTests: XCTestCase {
    /// Walked rather than listed, so a file added to either root is gated the day it lands. The kit is in scope
    /// because a symbol-only button that lives in a component is silent at every call site at once — fixing the
    /// sites one by one would be the wrong place.
    private static let scannedRoots = ["HarnaxFeatures", "HarnaxKit"]

    private static let imageMarker = "Image(systemName:"
    private static let labelMarker = "accessibilityLabel"

    /// A block holding any of these already speaks, so it is not icon-only. `Label(` also covers
    /// `menuLabel(…)`, `typeChipLabel(…)` and `ChatComposerLabel(…)`; `Chip(` and `Badge(` cover `HXChip` and
    /// `HXBadge`, which carry the row's word. `Row(` is `HXRow`, whose title and subtitle are the words a
    /// trailing marker such as `Support/HXEntityPicker.swift`'s lock glyph is only attached to. `hx(…)` is
    /// tested separately, because the colour token `Color.hx(.slot)` shares its spelling and says nothing.
    private static let textMarkers = ["HXText(", "Text(", "Label(", "Chip(", "Badge(", "ProgressView", "Row("]

    /// One `label:` block that draws symbols and no words, and whether anything answered for it by name.
    private struct IconOnlyBlock {
        let location: String
        /// The symbol names the block draws, sorted and joined — what a failure has to be read against.
        let drawnSymbols: String
        let labelled: Bool
    }

    func testNoIconOnlyControlIsSilentToVoiceOver() throws {
        let blocks = try scanAllGatedScreens()
        XCTAssertFalse(
            blocks.isEmpty,
            "the scan found no icon-only `label:` block anywhere in \(Self.scannedRoots) — it has stopped reading"
        )

        let silent = blocks.filter { !$0.labelled }
        XCTAssertTrue(
            silent.isEmpty,
            "\(silent.count) icon-only control(s) speak nothing but their glyph:\n"
                + silent.map { "  \($0.location) — draws \($0.drawnSymbols); give it the catalogue's word" }
                .joined(separator: "\n")
        )
    }

    /// A root that is in the list but reads as no file is the one way this gate can go quietly vacuous: the kit
    /// draws no icon-only control today, so its half of the walk is only worth anything if it really is walking.
    func testEveryWalkedRootIsActuallyRead() throws {
        let paths = relativePaths()
        XCTAssertTrue(paths.isEmpty == false, "the walk read no source file at all")
        for root in Self.scannedRoots {
            XCTAssertTrue(
                paths.contains { $0.hasPrefix(root + "/") },
                "\(root)/ contributes no file to the walk — the folder moved, or the root is misspelled"
            )
        }
        XCTAssertGreaterThanOrEqual(
            paths.filter { $0.hasPrefix("HarnaxKit/Components/") }.count,
            10,
            "the component folder the kit's own buttons live in has stopped being readable"
        )
    }

    /// The kit side's proof of life, without waiting for a component to be born silent: one `Menu` written twice,
    /// once with the catalogue's word and once without, put through the same scanner the walk uses. Both shapes
    /// are real code from `Agents/AgentListView.swift`'s row overflow, restated here so a kit control that ships
    /// without its label is charged rather than waved through by a rule that never matched anything.
    func testASymbolOnlyMenuInAKitComponentIsChargedWithItsLabelAndClearedWithout() {
        let menu = """
            Menu {
                Button { copy() } label: { HXText("common.copy") }
            } label: {
                Image(systemName: "ellipsis")
            }
            .buttonStyle(.plain)
            """
        let silent = iconOnlyBlocks(in: removingComments(from: Array(menu)), relative: "HarnaxKit/Components/Probe.swift")
        XCTAssertEqual(silent.count, 1, "the ellipsis menu is icon-only and has to be seen")
        XCTAssertFalse(silent.first?.labelled ?? true, "…and nothing answered for it here")

        let named = menu.replacingOccurrences(
            of: ".buttonStyle(.plain)",
            with: ".buttonStyle(.plain)\n.accessibilityLabel(hx(\"state.action.more\"))"
        )
        let cleared = iconOnlyBlocks(in: removingComments(from: Array(named)), relative: "HarnaxKit/Components/Probe.swift")
        XCTAssertEqual(cleared.count, 1, "the same block is still icon-only once it is named")
        XCTAssertTrue(cleared.first?.labelled ?? false, "…and the label on the chain is what clears it")
    }

    /// The scan's own proof of life. Reading the tree as it stands only ever says "no offender", which a scan
    /// that had stopped matching anything would also say, so this takes a real file, deletes one
    /// `accessibilityLabel` from its text in memory, and requires the block that lost it to come back silent.
    /// The cleared half reads the kit's `HXListToolbar.swift` rather than a second block beside the deleted one:
    /// every list's `+` and funnel are drawn there now, so those two are the labels the live screens really carry.
    func testADeletedLabelIsStillSeenAsASilentControl() throws {
        let relative = "HarnaxFeatures/Agents/AgentListView.swift"
        let text = try String(contentsOf: sourcesRoot.appendingPathComponent(relative), encoding: .utf8)
        let label = "\n        .accessibilityLabel(hx(\"state.action.more\"))"
        guard let found = text.range(of: label) else {
            return XCTFail("\(relative) no longer carries the row-overflow label this test deletes")
        }
        var stripped = text
        stripped.removeSubrange(found)

        let blocks = iconOnlyBlocks(in: removingComments(from: Array(stripped)), relative: relative)
        XCTAssertTrue(
            blocks.contains { !$0.labelled && $0.drawnSymbols == "ellipsis,hourglass" },
            "with its label gone the row overflow has to be seen and charged as silent"
        )

        let kit = "HarnaxKit/Components/HXListToolbar.swift"
        let kitText = try String(contentsOf: sourcesRoot.appendingPathComponent(kit), encoding: .utf8)
        let kitBlocks = iconOnlyBlocks(in: removingComments(from: Array(kitText)), relative: kit)
        XCTAssertTrue(
            kitBlocks.contains { $0.labelled && $0.drawnSymbols == "plus" },
            "the shared `+` has to be seen as icon-only and cleared by the label on its chain"
        )
        XCTAssertTrue(
            kitBlocks.contains { $0.labelled && $0.drawnSymbols.hasPrefix("line.3.horizontal.decrease.circle") },
            "…and the shared funnel beside it likewise"
        )
    }

    // MARK: - scanning

    private func scanAllGatedScreens() throws -> [IconOnlyBlock] {
        var blocks: [IconOnlyBlock] = []
        for url in scannedFiles() {
            blocks.append(contentsOf: try scan(url: url))
        }
        return blocks
    }

    private func scan(url: URL) throws -> [IconOnlyBlock] {
        let text = try String(contentsOf: url, encoding: .utf8)
        return iconOnlyBlocks(in: removingComments(from: Array(text)), relative: relativePath(of: url))
    }

    /// Every `label:` block in the file that draws an SF Symbol and no words. A `label:` nested inside a block
    /// already taken is still visited, since the outer one being labelled says nothing about the inner one.
    private func iconOnlyBlocks(in source: [Character], relative: String) -> [IconOnlyBlock] {
        var blocks: [IconOnlyBlock] = []
        var cursor = 0
        while let start = findLabelColon(source, from: cursor) {
            let afterColon = start + 6
            cursor = afterColon
            let open = skipSpaces(source, from: afterColon)
            guard open < source.count, source[open] == "{" else { continue }
            let close = balancedEnd(source, from: open, open: "{", close: "}")
            let content = String(source[(open + 1)..<min(max(close - 1, open + 1), source.count)])
            guard content.contains(Self.imageMarker) else { continue }
            let labelled = content.contains(Self.labelMarker) || chainCarriesLabel(source, from: close)
            // `accessibilityLabel(…)` is the answer, not the content: it carries `Label(` and often an `hx(`,
            // and neither is a word the block itself draws.
            guard !speaks(String(removingLabelCalls(Array(content)))) else { continue }
            blocks.append(
                IconOnlyBlock(
                    location: "\(relative):\(line(of: start, in: source))",
                    drawnSymbols: symbols(in: content),
                    labelled: labelled
                )
            )
        }
        return blocks
    }

    /// `label:` standing alone as an argument name — never the tail of an identifier such as `typeChipLabel:`.
    private func findLabelColon(_ source: [Character], from: Int) -> Int? {
        let needle = Array("label:")
        var index = max(from, 0)
        while index + needle.count <= source.count {
            if Array(source[index..<index + needle.count]) == needle, !isTail(source, at: index) { return index }
            index += 1
        }
        return nil
    }

    /// True when the match continues an identifier or a member access, so it is not this argument name.
    private func isTail(_ source: [Character], at index: Int) -> Bool {
        guard index > 0 else { return false }
        let previous = source[index - 1]
        return previous.isLetter || previous.isNumber || previous == "_" || previous == "."
    }

    /// A block that holds text, a text-taking component, or a catalogue lookup is not icon-only.
    private func speaks(_ content: String) -> Bool {
        if Self.textMarkers.contains(where: { content.contains($0) }) { return true }
        return hasCatalogLookup(Array(content))
    }

    private func hasCatalogLookup(_ text: [Character]) -> Bool {
        let needle = Array("hx(")
        var index = 0
        while index + needle.count <= text.count {
            if Array(text[index..<index + needle.count]) == needle, !isTail(text, at: index) { return true }
            index += 1
        }
        return false
    }

    /// Drops each `accessibilityLabel(…)` out of a block, arguments and all.
    private func removingLabelCalls(_ content: [Character]) -> [Character] {
        let needle = Array(Self.labelMarker)
        var output: [Character] = []
        var index = 0
        while index < content.count {
            if index + needle.count <= content.count, Array(content[index..<index + needle.count]) == needle {
                let head = skipSpaces(content, from: index + needle.count)
                if head < content.count, content[head] == "(" {
                    index = balancedEnd(content, from: head, open: "(", close: ")")
                    continue
                }
            }
            output.append(content[index])
            index += 1
        }
        return output
    }

    /// The modifier chain after the block, walked to its last dot: `.foo(…)` and a trailing closure both
    /// continue it, and a blank or commented line in between does not end it — the comments are already gone.
    private func chainCarriesLabel(_ source: [Character], from: Int) -> Bool {
        var index = from
        while true {
            index = skipSpaces(source, from: index)
            guard index < source.count, source[index] == "." else { return false }
            var name = index + 1
            while name < source.count, source[name].isLetter || source[name].isNumber || source[name] == "_" {
                name += 1
            }
            if String(source[(index + 1)..<name]) == Self.labelMarker { return true }
            let head = skipSpaces(source, from: name)
            guard head < source.count, source[head] == "(" || source[head] == "{" else { return false }
            index = balancedEnd(source, from: head, open: source[head], close: source[head] == "(" ? ")" : "}")
        }
    }

    // MARK: - text

    /// Comments quote the very shapes this gate looks for, and a line comment can hide inside a string literal
    /// as `//` in a URL — so strings are stepped over and only real comments come off.
    private func removingComments(from source: [Character]) -> [Character] {
        var output: [Character] = []
        var index = 0
        while index < source.count {
            let character = source[index]
            if character == "\"" {
                let end = endOfString(source, from: index)
                output.append(contentsOf: source[index..<min(end, source.count)])
                index = max(end, index + 1)
                continue
            }
            if character == "/", index + 1 < source.count, source[index + 1] == "/" {
                while index < source.count, source[index] != "\n" { index += 1 }
                continue
            }
            if character == "/", index + 1 < source.count, source[index + 1] == "*" {
                index += 2
                while index + 1 < source.count, !(source[index] == "*" && source[index + 1] == "/") {
                    if source[index] == "\n" { output.append("\n") }
                    index += 1
                }
                index = min(index + 2, source.count)
                continue
            }
            output.append(character)
            index += 1
        }
        return output
    }

    /// Past the literal that starts at `from`: a single-line one ends at its quote or at the newline, a
    /// multi-line one only at its own closing triple quote.
    private func endOfString(_ source: [Character], from: Int) -> Int {
        let multiline = from + 2 < source.count && source[from + 1] == "\"" && source[from + 2] == "\""
        var index = multiline ? from + 3 : from + 1
        while index < source.count {
            let character = source[index]
            if character == "\\" {
                index += 2
                continue
            }
            if character == "\"" {
                if !multiline { return index + 1 }
                if index + 2 < source.count, source[index + 1] == "\"", source[index + 2] == "\"" { return index + 3 }
            }
            if character == "\n", !multiline { return index }
            index += 1
        }
        return source.count
    }

    /// The index just past the pair that opens at `from`, with string literals stepped over so a brace inside
    /// one never closes a block.
    private func balancedEnd(_ source: [Character], from: Int, open: Character, close: Character) -> Int {
        var depth = 0
        var index = from
        while index < source.count {
            let character = source[index]
            if character == "\"" {
                index = endOfString(source, from: index)
                continue
            }
            if character == open {
                depth += 1
            } else if character == close {
                depth -= 1
                if depth == 0 { return index + 1 }
            }
            index += 1
        }
        return source.count
    }

    private func skipSpaces(_ source: [Character], from: Int) -> Int {
        var index = from
        while index < source.count, source[index].isWhitespace { index += 1 }
        return index
    }

    private func line(of index: Int, in source: [Character]) -> Int {
        var number = 1
        var cursor = 0
        while cursor < min(index, source.count) {
            if source[cursor] == "\n" { number += 1 }
            cursor += 1
        }
        return number
    }

    /// The names the block draws, sorted and joined — how a silent block is named in a failure, and how the
    /// mutation test points at one specific block in a file that holds several.
    private func symbols(in content: String) -> String {
        Set(stringLiterals(in: Array(content))).sorted().joined(separator: ",")
    }

    private func stringLiterals(in text: [Character]) -> [String] {
        var names: [String] = []
        var index = 0
        while index < text.count {
            guard text[index] == "\"" else {
                index += 1
                continue
            }
            var body = ""
            var cursor = index + 1
            var closed = false
            while cursor < text.count {
                let character = text[cursor]
                if character == "\n" { break }
                if character == "\\" {
                    if cursor + 1 < text.count { body.append(text[cursor + 1]) }
                    cursor += 2
                    continue
                }
                if character == "\"" {
                    closed = true
                    cursor += 1
                    break
                }
                body.append(character)
                cursor += 1
            }
            if closed { names.append(body) }
            index = cursor > index ? cursor : index + 1
        }
        return names
    }

    // MARK: - locating

    /// `…/harnax-ios/Sources`, reached from this file's own compiled-in path the way the kit's gates do it.
    private var sourcesRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // Tests/HarnaxFeaturesTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // harnax-ios
            .appendingPathComponent("Sources")
    }

    private func scannedFiles() -> [URL] {
        var found: [URL] = []
        for root in Self.scannedRoots {
            let directory = sourcesRoot.appendingPathComponent(root)
            guard let walker = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil) else {
                continue
            }
            found.append(
                contentsOf: walker.compactMap { $0 as? URL }
                    .filter { $0.pathExtension == "swift" }
            )
        }
        return found.sorted { $0.path < $1.path }
    }

    /// A root the scan was pointed at but never read — a renamed folder, a typo in the list — has to be named
    /// rather than silently contribute nothing to the walk.
    private func relativePaths() -> [String] {
        scannedFiles().map { relativePath(of: $0) }
    }

    private func relativePath(of url: URL) -> String {
        url.path.replacingOccurrences(of: sourcesRoot.path + "/", with: "")
    }
}
