import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// Task #84 — the plan card inside the transcript, and `plan_exit`'s bubble break.
///
/// The authority is `harnax-webui/src/pages/session/components/ChatWindow.tsx`: a plan call opens the panel and
/// starts the load (`:1499-1504`), `plan_exit`'s result ends the answer and opens an empty one
/// (`:1553-1591`), and the current plan reads back as one card in the message stream that every later reading
/// updates in place and that the plan going away leaves on screen (`:2479-2585`, `:2964-2970`).
///
/// The card is a **segment of the lead's answer**, never a turn of its own (`ChatTranscript.swift`'s
/// `currentLeadIndex` / `segments` reads are the reason: a second non-member assistant turn would win the lead
/// search and become what the screen reads). The first half below pins both sides of that decision — the card lands
/// in the answer it belongs to, and the answer keeps taking words around it. The second half pins the screen's
/// triggers: which frame opens the drawer and starts the read, which one breaks the bubble, and what `enablePlan`
/// gates. Main-actor because that half drives `ChatViewModel` and `PlanPanelViewModel`, like
/// `ChatViewModelTests.swift:8`.
@MainActor
final class ChatPlanCardTests: XCTestCase {
    private func decode(_ payload: String) throws -> ChatEvent {
        try ChatEvent.decode(payload)
    }

    private func fold(_ transcript: inout ChatTranscript, _ payloads: String...) throws {
        for payload in payloads {
            transcript.fold(try decode(payload))
        }
    }

    /// A transcript with a turn already open, which is what the screen holds when a stream starts.
    private func started() -> ChatTranscript {
        var transcript = ChatTranscript()
        transcript.send("列个计划")
        return transcript
    }

    private func plan(_ id: String, name: String, done: Int = 0, total: Int = 0) -> PlanNote {
        PlanFixtures.note(
            id,
            name: name,
            subtasks: (0..<total).map { PlanFixtures.subtask("步骤\($0)", state: $0 < done ? .done : .todo) }
        )
    }

    /// The plan block of an answer, newest last.
    private func cards(_ transcript: ChatTranscript) -> [PlanNote] {
        transcript.turns.flatMap(\.segments).compactMap(\.plan)
    }

    // MARK: - the card is a block of the answer

    func testThePlanCardIsABlockOfTheAnswerItBelongsTo() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("先拆控制器"))

        transcript.showPlan(plan("p-1", name: "拆分会话路由"))

        XCTAssertEqual(transcript.turns.count, 2, "the card is not a bubble of its own")
        XCTAssertEqual(transcript.turns.map(\.role), [.user, .assistant])
        XCTAssertEqual(
            transcript.turns.last?.segments.map(\.kind),
            [.text("先拆控制器"), .plan(plan("p-1", name: "拆分会话路由"))]
        )
        XCTAssertEqual(transcript.planCard?.planId, "p-1")
    }

    /// Every later reading rewrites the block that is already there (`:2558-2572`), so a plan that moves on
    /// through six polls is still one card on screen.
    func testASecondReadingUpdatesThatSameCard() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("先拆控制器"))
        transcript.showPlan(plan("p-1", name: "拆分会话路由", total: 3))

        transcript.showPlan(plan("p-1", name: "拆分会话路由", done: 2, total: 3))

        XCTAssertEqual(cards(transcript).count, 1, "the second reading never stacks a second card")
        let card = try XCTUnwrap(transcript.planCard)
        XCTAssertEqual(card.progress.done, 2, "and the card carries the newer reading")
        XCTAssertEqual(transcript.turns.last?.segments.count, 2, "in the same block, in the same answer")
    }

    /// The regression the design decision exists to prevent (`currentLeadIndex`, `ChatTranscript.swift:695-697`,
    /// and `openAnswer` at `:1082-1085`): with the card as a turn of its own, the lead's next delta would have
    /// been dropped on the floor.
    func testTheCardDoesNotStopTheAnswerItLandedInFromGoingOn() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("前半"))
        transcript.showPlan(plan("p-1", name: "拆分会话路由"))
        try fold(&transcript, ChatFrames.text("后半"))

        XCTAssertEqual(transcript.turns.count, 2, "the words after the card stayed in this answer")
        XCTAssertEqual(transcript.turns.last?.segments.compactMap(\.text), ["前半", "后半"])
        XCTAssertEqual(transcript.turns.last?.segments.count, 3, "text, card, text")
        XCTAssertFalse(transcript.isTerminated, "and the answer is still open for the next frame")
    }

    /// The same fact on the confirmation side (`confirmationTurn`, `:749-763`): the search is for a
    /// `.confirmation` block, and a card sitting in the answer is not one and does not hide one.
    func testTheConfirmationSearchLooksPastTheCard() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("要动配置文件了"))
        transcript.showPlan(plan("p-1", name: "拆分会话路由"))
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.confirm(ChatFrames.pending(id: "t-1", name: "bash")))

        XCTAssertEqual(transcript.pendingConfirmation?.tools.map(\.toolName), ["bash"])
        transcript.resolveConfirmation(["t-1": .allowed])
        let answered = try XCTUnwrap(transcript.turns.last?.segments.compactMap(\.tool).first)
        XCTAssertEqual(answered.confirmAnswer, .allowed)
        XCTAssertEqual(cards(transcript).count, 1, "answering a tool never touches the card")
        XCTAssertEqual(transcript.turns.count, 2)
    }

    // MARK: - the card freezes rather than leaving

    func testTheCardStaysWhenThePlanGoesAway() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("先拆控制器"))
        transcript.showPlan(plan("p-1", name: "拆分会话路由", done: 3, total: 3))

        // `:2576-2584`: with no plan data left the card keeps its last content. The reducer's side of that is
        // simply that nothing removes it — the freeze is the absence of a removal.
        try fold(&transcript, ChatFrames.text("接着写测试"))

        XCTAssertEqual(cards(transcript).count, 1)
        XCTAssertEqual(transcript.planCard?.progress.done, 3, "still the reading it was last given")
        XCTAssertEqual(transcript.turns.last?.segments.compactMap(\.text), ["先拆控制器", "接着写测试"])
    }

    /// A plan with no name is not a plan (`hasValidCurrentPlan`, `:2965-2970`, and the card's own render test at
    /// `:2786`), so it never becomes an empty block in the stream.
    func testAnUnnamedPlanIsNoCard() throws {
        var transcript = started()
        transcript.showPlan(PlanFixtures.note("p-9", name: nil, subtasks: [PlanFixtures.subtask("读路由配置")]))

        XCTAssertTrue(transcript.turns.last?.segments.isEmpty ?? false)
        XCTAssertNil(transcript.planCard)
    }

    /// Nothing to hang it on: the card belongs to an answer, and inventing a turn for it is what this design
    /// rejects. With no assistant answer on screen the reading is dropped rather than drawn.
    func testAPlanWithNoAnswerOnScreenDrawsNothing() throws {
        var transcript = ChatTranscript()
        transcript.appendUserMessage("列个计划")
        transcript.showPlan(plan("p-1", name: "拆分会话路由"))

        XCTAssertEqual(transcript.turns.count, 1, "the user's own bubble and nothing else")
        XCTAssertNil(transcript.planCard)
    }

    func testAPlanBeforeAnyTurnAtAllOpensNoBubble() throws {
        var transcript = ChatTranscript()
        transcript.showPlan(plan("p-1", name: "拆分会话路由"))

        XCTAssertTrue(transcript.turns.isEmpty)
        XCTAssertNil(transcript.planCard)
    }

    // MARK: - plan_exit

    func testPlanExitEndsThisAnswerAndOpensAFreshOne() throws {
        var transcript = started()
        try fold(&transcript, ChatFrames.text("计划讲完了"))
        transcript.showPlan(plan("p-1", name: "拆分会话路由"))

        transcript.endAnswerForPlanExit()

        XCTAssertEqual(transcript.turns.count, 3, "plan phase, then execution phase")
        let closed = transcript.turns[1]
        XCTAssertEqual(closed.outcome, .ended)
        XCTAssertEqual(closed.segments.map(\.kind), [.text("计划讲完了"), .plan(plan("p-1", name: "拆分会话路由"))],
                       "the card stays in the answer it landed in")
        let opened = try XCTUnwrap(transcript.turns.last)
        XCTAssertEqual(opened.role, .assistant)
        XCTAssertFalse(opened.hasContent, "the new bubble is empty until the execution phase says something")
        XCTAssertEqual(opened.outcome, .streaming)
        XCTAssertNil(transcript.planCard, "and this answer is no longer following a plan")

        try fold(&transcript, ChatFrames.text("开始执行"))
        XCTAssertEqual(transcript.segments.map(\.text), ["开始执行"], "the next word lands in the new bubble")
    }

    /// A new plan after an exit is a new card, not a rewrite of the frozen one (`:1579-1590` clears the ref the
    /// updates look for).
    func testAPlanAfterAnExitOpensItsOwnCard() throws {
        var transcript = started()
        transcript.showPlan(plan("p-1", name: "第一阶段"))
        transcript.endAnswerForPlanExit()
        try fold(&transcript, ChatFrames.text("第二个计划"))

        transcript.showPlan(plan("p-2", name: "第二阶段"))

        XCTAssertEqual(cards(transcript).count, 2, "the frozen card and the new one are two blocks")
        XCTAssertEqual(transcript.turns[1].segments.compactMap(\.plan).map(\.planId), ["p-1"])
        XCTAssertEqual(transcript.planCard?.planId, "p-2")
        XCTAssertEqual(transcript.turns.last?.segments.compactMap(\.plan).map(\.planId), ["p-2"])
    }

    // MARK: - plan frames stay out of the fold

    /// The filter itself (`planTools`, `:231`, used by `foldCall` and `foldConfirm`): pinned here so a future
    /// edit cannot start drawing plan cards without this test saying so. The card comes from the plan read, and
    /// only from the plan read.
    func testAPlanFrameDrawsNoCardAndBreaksNoSentence() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.text("我"),
                 ChatFrames.call(id: "p-1", name: "plan_enter"),
                 ChatFrames.call(id: "p-2", name: "plan_write", arguments: #"{"name":"拆控制器"}"#),
                 ChatFrames.confirm(ChatFrames.pending(id: "p-3", name: "plan_write")),
                 ChatFrames.result(id: "p-2", name: "plan_write", message: "saved"),
                 ChatFrames.text("要说"),
                 ChatFrames.result(id: "p-1", name: "plan_exit", message: "ok"),
                 ChatFrames.text("完"))

        XCTAssertEqual(transcript.turns.count, 2, "no bubble of its own and no break in this one")
        // 我 + 要说 + 完 are one segment: the fold drops every plan frame through `planTools`, so nothing closes
        // the text run between them. `plan_exit`'s break is not the reducer's business — it comes from
        // `endAnswerForPlanExit`, which the view model calls when it sees the frame (`ChatViewModel.swift`).
        XCTAssertEqual(transcript.turns.last?.segments.map(\.kind), [.text("我要说完")])
        XCTAssertTrue(cards(transcript).isEmpty, "a plan frame never brings a card with it")
        XCTAssertNil(transcript.pendingConfirmation, "and a plan confirmation opens no panel")
    }

    /// A plan frame inside an answer that already has tool cards leaves those cards alone: the fold's tool
    /// pairing must not be reached by a plan name (`ChatHistoryReplayTests.swift:188-195` pins the replay half).
    func testAPlanFrameClaimsNoToolCard() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "t-1", name: "bash"),
                 ChatFrames.call(id: "p-1", name: "plan_write"),
                 ChatFrames.result(id: "p-1", name: "plan_write", message: "saved"),
                 ChatFrames.result(id: "t-1", name: "bash", message: "out"))

        let runs = transcript.turns.last?.segments.compactMap(\.tool) ?? []
        XCTAssertEqual(runs.map(\.toolName), ["bash"], "the plan call drew no card to pair with")
        XCTAssertEqual(runs.first?.result?.message, "out")
    }
}
