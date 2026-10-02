import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// Item 2 of the display pass: the MCP delete dialog has to name the agents that lose a binding, the way the
/// web console does (`harnax-webui/src/pages/mcp/index.tsx:327-337`), and it has to do it in the language
/// being read — the console joins with a hardcoded 顿号, which is a stray character on an English sentence.
final class McpDeleteCopyTests: XCTestCase {
    override func tearDown() {
        HarnaxCatalog.shared.language = .system
        super.tearDown()
    }

    /// The `names` the console builds, and the `count` it still prints beside them, for three dependents.
    func testThreeBoundAgentsAreAllNamed() throws {
        HarnaxCatalog.shared.language = .en
        let message = McpDeleteCopy.message(for: try agents("Alpha", "Beta", "Gamma"))
        XCTAssertEqual(
            message,
            "3 agent(s) bind this server (Alpha, Beta, Gamma). Deleting it removes those bindings as well."
        )
    }

    /// Seven dependents, five names: the count is the real one and the tail says something was left out.
    ///
    /// The console stops at `slice(0, 5)` and prints no hint, so a sentence reading "7 agent(s)" over five
    /// names silently contradicts itself. The tail marker is this app's addition
    /// (`mcp.delete.names.more`), which is what this case pins.
    func testSevenBoundAgentsNameFiveAndAdmitTheRest() throws {
        HarnaxCatalog.shared.language = .en
        let rows = try agents("One", "Two", "Three", "Four", "Five", "Six", "Seven")
        let message = McpDeleteCopy.message(for: rows)
        XCTAssertTrue(message.hasPrefix("7 agent(s)"), "the count is the whole blast radius: \(message)")
        XCTAssertTrue(
            message.contains("(One, Two, Three, Four, Five, …)"),
            "the first five, then the tail: \(message)"
        )
        XCTAssertFalse(message.contains("Six"), "the sixth name does not fit the sentence")
        XCTAssertFalse(message.contains("Seven"), "nor the seventh")
    }

    /// Chinese reads a list with 顿号, and the separator is catalog copy rather than a character in a view.
    func testTheSeparatorIsTheOneThisLanguageUses() throws {
        let rows = try agents("甲", "乙", "丙")
        HarnaxCatalog.shared.language = .zhHans
        XCTAssertEqual(
            McpDeleteCopy.nameList(for: rows),
            "甲、乙、丙",
            "顿号 comes from mcp.delete.nameSeparator"
        )
        XCTAssertTrue(
            McpDeleteCopy.message(for: rows)
                .contains("3 个智能体绑定了该服务（甲、乙、丙）"),
            "the whole sentence resolves in Chinese, not only the name list"
        )
        HarnaxCatalog.shared.language = .en
        XCTAssertEqual(McpDeleteCopy.nameList(for: rows), "甲, 乙, 丙")
    }

    /// An exactly-at-the-limit list gets no tail: the marker means "there are more", and there are not.
    func testFiveBoundAgentsCarryNoTailMarker() throws {
        HarnaxCatalog.shared.language = .en
        let rows = try agents("One", "Two", "Three", "Four", "Five")
        XCTAssertEqual(McpDeleteCopy.nameList(for: rows), "One, Two, Three, Four, Five")
    }

    /// Nothing binds the server, so the dialog says the delete will simply go through.
    func testAnEmptyListKeepsTheClearSentence() throws {
        HarnaxCatalog.shared.language = .en
        let message = McpDeleteCopy.message(for: [])
        XCTAssertEqual(message, hx("mcp.delete.clear"))
        XCTAssertFalse(message.contains("("), "no empty parenthesis where a name list belongs")
    }

    /// `agentName` is non-null on `RelatedAgentInfo`, so a payload without names is one that lost them in
    /// transit. The count-only sentence stays true; "(, )" would not.
    func testANamelessPayloadFallsBackToTheCountAlone() throws {
        HarnaxCatalog.shared.language = .en
        let rows = [try relatedAgent(1, NSNull()), try relatedAgent(2, "   ")]
        let message = McpDeleteCopy.message(for: rows)
        XCTAssertEqual(message, "2 agent(s) bind this server. Deleting it removes those bindings as well.")
        // The count-only sentence carries "agent(s)" of its own, so the defect is not a parenthesis but an
        // empty name clause: the brackets the named sentence puts the list inside, left behind with nothing.
        XCTAssertFalse(message.contains("()"), "the name clause is dropped with the names")
        XCTAssertFalse(message.contains("(, )"), "and no separator survives on its own")
    }

    /// A single nameless row in the middle must not leave a doubled separator behind, which is what the
    /// console's plain `join` does with an undefined name.
    func testANamelessRowInTheMiddleIsDroppedRatherThanJoined() throws {
        HarnaxCatalog.shared.language = .en
        let rows = [
            try relatedAgent(1, "Alpha"),
            try relatedAgent(2, NSNull()),
            try relatedAgent(3, "Gamma"),
        ]
        XCTAssertEqual(McpDeleteCopy.nameList(for: rows), "Alpha, Gamma")
    }

    /// The bound agent's enable flag says nothing about what a delete costs, and the console does not read it
    /// here. Kept out on purpose: a disabled agent still loses its binding row.
    func testTheEnabledFlagDoesNotEnterTheSentence() throws {
        HarnaxCatalog.shared.language = .en
        let enabled = try relatedAgent(1, "Alpha", status: 1)
        let disabled = try relatedAgent(1, "Alpha", status: 0)
        XCTAssertEqual(
            McpDeleteCopy.message(for: [enabled, disabled]),
            "2 agent(s) bind this server (Alpha, Alpha). Deleting it removes those bindings as well."
        )
    }

    /// The limit lives in one place and the two screens that could print this sentence read it from there.
    func testTheNameLimitMatchesTheConsole() throws {
        XCTAssertEqual(McpDeleteCopy.nameLimit, 5, "`slice(0, 5)` in pages/mcp/index.tsx")
    }

    private func agents(_ names: String...) throws -> [RelatedAgent] {
        try names.enumerated().map { try relatedAgent($0.offset + 1, $0.element) }
    }
}

/// The view-side half of item 2: the dialog has to ask the copy maker, because a count-only sentence in the
/// screen would keep the names invisible even while `McpDeleteCopy` was correct.
final class McpDeleteConfirmationGateTests: XCTestCase {
    func testTheDeleteDialogHandsTheSentenceToTheCopyMaker() throws {
        let text = try SystemDomainSources.contents(of: "HarnaxFeatures/Mcp/McpListView.swift")
        let dialog = try SystemDomainSources.block(text, from: "func deleteConfirmation")
        XCTAssertTrue(
            dialog.contains("McpDeleteCopy.message"),
            "the message closure has to render the named sentence"
        )
        XCTAssertFalse(
            dialog.contains("mcp.delete.bound\""),
            "the count-only key belongs to the fallback inside McpDeleteCopy, not to the screen"
        )
        XCTAssertFalse(
            dialog.contains("、"),
            "and the screen builds no name list of its own, so no separator can hide in it"
        )
    }
}
