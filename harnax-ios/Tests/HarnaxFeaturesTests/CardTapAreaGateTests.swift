import XCTest
@testable import HarnaxFeatures

/// A card that opens something has to open it from anywhere on the card.
///
/// Reported 2026-10-08: on the conversation list, tapping a card "sometimes" did nothing and only one spot on
/// it reached the chat. `SessionRecordCard` attached the open action to the text column under the title, so
/// the title, the badges, the avatar and the card's own 14pt of padding were all inert. Hit area is not
/// something the compiler or XCTest can see — a `Text` only answers for its glyphs, and the gaps between the
/// rows of a `VStack` answer for nothing — so these gates read the source the way the colour and copy gates do.
final class CardTapAreaGateTests: XCTestCase {
    private let sessionFile = "HarnaxFeatures/Chat/SessionListView.swift"

    /// The open action belongs to the card, not to a block inside it.
    func testTheSessionCardOpensFromTheCardInsteadOfFromABlockInsideIt() throws {
        let text = try FeatureSources.code(of: sessionFile)
        XCTAssertTrue(text.contains(".hxCardTap("), "\(sessionFile) gives its card no tap target")
        // Whole-file, not inside the card block: the defect this replaced wrapped the text column in a button
        // held in a separate computed property, so a block-scoped check would have passed on the broken code.
        XCTAssertFalse(
            text.contains("Button { onOpen(session) }"),
            "only the text column opened the conversation: the title, badges, avatar and padding were dead taps"
        )
    }

    /// A card-wide tap and the row's own menu have to keep their layers: the menu is what the card carries in
    /// its top-trailing corner, and it must stay above the region the tap covers.
    func testTheSessionCardKeepsItsMenuAboveTheTap() throws {
        let text = try FeatureSources.code(of: sessionFile)
        let card = try FeatureSources.block(text, from: "HXCard {")
        guard let found = text.range(of: card) else {
            return XCTFail("\(sessionFile) draws no HXCard, so this gate no longer knows what it is checking")
        }
        let after = text[found.upperBound...]
        let tail = after.range(of: "\n    private var")?.lowerBound ?? after.endIndex
        let chain = String(after[...tail])
        guard let tap = chain.range(of: ".hxCardTap(")?.lowerBound,
              let menu = chain.range(of: ".overlay(alignment: .topTrailing)")?.lowerBound else {
            return XCTFail("the card lost either its tap or its corner menu: \(chain)")
        }
        XCTAssertLessThan(tap, menu, "a menu drawn under the card-wide tap cannot be opened")
    }

    /// A row whose `sessionId` the backend left null has no conversation to open, and it must not answer like
    /// a control. The tap is built from the optional action itself, which is what keeps that row inert.
    func testOnlyRowsThatReachTheRuntimeAreTappable() throws {
        let text = try FeatureSources.code(of: sessionFile)
        XCTAssertTrue(
            text.contains("onOpen.map"),
            "\(sessionFile) taps every card, including the rows that have no conversation to open"
        )
    }

    /// The kit side of the invariant: the region has to be the card surface, and the tap has to sit on top of
    /// that region. Without the shape, an `onTapGesture` answers only where the card drew something opaque.
    func testTheCardTapShapesTheRegionBeforeItTakesTheTap() throws {
        let text = try FeatureSources.code(of: "HarnaxKit/Components/HXCard.swift")
        let body = try FeatureSources.block(text, from: "if let action")
        let shape = body.range(of: ".contentShape(")?.lowerBound
        let gesture = body.range(of: ".onTapGesture")?.lowerBound
        guard let shape, let gesture else {
            return XCTFail("the card tap shapes nothing or taps nowhere: \(body)")
        }
        XCTAssertLessThan(shape, gesture, "a gesture below the shape leaves the card's own surface untappable")
        let unwrapped = try FeatureSources.block(text, from: "func body(content: Content)")
        XCTAssertTrue(
            unwrapped.contains("} else {"),
            "a card with nothing to open has to stay inert, not answer taps"
        )
    }

    /// The record cards a tap opens. Two shapes answer for the whole card: the kit's card-level tap, or a
    /// `Button`/`NavigationLink` whose label is the card itself — a control already takes its label's frame,
    /// including the padding drawn inside it. What neither may do is hand the action to a block drawn inside
    /// the card, which leaves the padding ring and every row beside that block dead.
    private let openingCards = [
        "HarnaxFeatures/Chat/SessionListView.swift",
        "HarnaxFeatures/Agents/AgentListView.swift",
        "HarnaxFeatures/Teams/TeamListView.swift",
        "HarnaxFeatures/Cli/CliListView.swift",
        "HarnaxFeatures/Mcp/McpListView.swift",
        "HarnaxFeatures/Tools/ToolListView.swift",
        "HarnaxFeatures/Tasks/TaskLogListView.swift",
        "HarnaxFeatures/Models/ModelProviderListView.swift",
        "HarnaxFeatures/SkillDrafts/SkillDraftListView.swift",
        "HarnaxFeatures/SkillDrafts/SkillDraftEntryRow.swift"
    ]

    func testNoCardHandsItsOpenActionToABlockInsideIt() throws {
        for path in openingCards {
            let text = try FeatureSources.code(of: path)
            let card = try FeatureSources.block(text, from: "HXCard {")
            XCTAssertFalse(
                card.contains("onTapGesture"),
                "\(path) taps a block inside its card: the padding ring and the rows beside it answer for nothing"
            )
            for action in ["onOpen", "onDrillDown", "onDetail", "onTap"] {
                XCTAssertFalse(
                    card.contains("Button(action: \(action)") || card.contains("Button { \(action)"),
                    "\(path) opens through \(action) drawn inside the card"
                )
            }
        }
    }

    /// The skill table's rows carried the same defect one screen over: the link's label was the title alone, so
    /// the description, the bound/shared/origin chips and the byline answered for nothing.
    func testTheSkillTableRowLinksItsWholeBody() throws {
        let path = "HarnaxFeatures/Skills/SkillTableView.swift"
        let text = try FeatureSources.code(of: path)
        let link = try FeatureSources.block(text, from: "NavigationLink(value: route) {")
        XCTAssertTrue(link.contains("rowBody(skill)"), "\(path) links less than the row draws: \(link)")
        XCTAssertFalse(
            link.contains("detailTitle(skill)"),
            "\(path) links only the title again — the rest of the row is a dead tap"
        )
        XCTAssertFalse(
            link.contains("statusMenu"),
            "\(path) puts the row's status menu inside the link's label, where a control cannot answer taps"
        )
    }

    /// The six cards that open on a gesture share the one kit shape rather than each hand-rolling its own.
    func testTheGestureCardsTapThroughTheKit() throws {
        for path in Array(openingCards.prefix(6)) {
            let text = try FeatureSources.code(of: path)
            XCTAssertTrue(text.contains(".hxCardTap("), "\(path) rolls its own card tap instead of using hxCardTap")
        }
    }

    /// The other four open through a control, which is already whole-card — a control takes its label's frame,
    /// padding included. The invariant is that the *card* is that label: move it down to a block inside the
    /// card and this reads as a pass while the hit area shrinks back to one row.
    func testTheFourCardsThatOpenThroughAControlWrapTheWholeCard() throws {
        let openers: [(path: String, pattern: String)] = [
            ("HarnaxFeatures/Tasks/TaskLogListView.swift", #"(Button\(action: onTap\) \{\s*HXCard \{)"#),
            ("HarnaxFeatures/Models/ModelProviderListView.swift", #"(NavigationLink\(value: provider\) \{\s*HXCard \{)"#),
            ("HarnaxFeatures/SkillDrafts/SkillDraftListView.swift", #"(NavigationLink\(value: ref\) \{ card \})"#),
            ("HarnaxFeatures/SkillDrafts/SkillDraftEntryRow.swift",
             #"(NavigationLink\(value: SkillDraftQueueRoute\(\)\) \{\s*HXCard \{)"#)
        ]
        for entry in openers {
            let text = try FeatureSources.code(of: entry.path)
            XCTAssertFalse(
                try FeatureSources.captures(entry.pattern, in: text).isEmpty,
                "\(entry.path) no longer puts its whole card behind the control that opens it"
            )
        }
    }
}
