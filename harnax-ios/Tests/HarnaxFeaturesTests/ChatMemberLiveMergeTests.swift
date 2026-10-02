import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The live half of the team merge: how the frames of one stream get split between the lead's bubble and its
/// members'.
///
/// `ChatHistoryReplayTests` already pins the replay door, and `TeamRunTests` the pure routing state, but
/// neither drives `ChatTranscript.fold` with a member-stamped frame — so every rule that only the running
/// stream can show was untested: where a bubble is inserted, which door leaves it open, and which of the
/// three signals (the member's own end frame, the lead's end frame, the delegate card coming back) retires
/// it. The three are not interchangeable: the console's whole reason for reading the card's result is that
/// the server filters a member's own end frame today (`TeamOrchestrator.kt:352-356`).
///
/// Frames come through `ChatEvent.decode` rather than by building structs, so a test breaks when the wire
/// shape it claims to follow changes and not when the reducer's internals do.
final class ChatMemberLiveMergeTests: XCTestCase {
    private let delegateArgs = #"{"member_agent_id":3,"task":"把第三段翻成英文"}"#

    private func fold(_ transcript: inout ChatTranscript, _ payloads: String...) throws {
        for payload in payloads {
            transcript.fold(try ChatEvent.decode(payload))
        }
    }

    /// A round under way: the user has sent, so the lead's bubble is open and waiting — which is exactly the
    /// state a member frame arrives into, since delegation blocks the lead.
    private func started() -> ChatTranscript {
        var transcript = ChatTranscript()
        transcript.send("翻一下")
        return transcript
    }

    // MARK: - where the bubble goes

    func testAMemberFrameOpensItsOwnBubbleAboveTheLead() throws {
        var transcript = started()
        try fold(&transcript, MemberFrames.text("译文第一段", member: 3, run: "run-3"))

        XCTAssertEqual(
            transcript.turns.map(\.isMemberBubble),
            [false, true, false],
            "the member speaks before the lead has the last word: its bubble sits above his answer"
        )
        XCTAssertEqual(transcript.turns[1].role, .assistant)
        XCTAssertEqual(transcript.turns[1].member?.memberAgentId, 3)
    }

    /// Two doors open a member bubble and they must not agree: a replayed one has no lifecycle frames left to
    /// read, so it arrives closed, while a live one is still working. Folding `isReplay: true` into the live
    /// path would retire every running bubble on the first delta and the screen would stop showing work.
    func testALiveMemberBubbleStaysOpenWhereAReplayedOneOpensClosed() throws {
        var transcript = started()
        try fold(&transcript, MemberFrames.text("还在说", member: 3, run: "run-3"))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        XCTAssertEqual(member.member?.status, .running)
        XCTAssertTrue(member.member?.isOpen ?? false)
        XCTAssertNil(member.member?.endedAt, "a run that is still going has no end time to show")
        XCTAssertEqual(member.outcome, .streaming)
    }

    func testMemberDeltasNeverReachTheLeadAnswer() throws {
        var transcript = started()
        try fold(&transcript, MemberFrames.text("成员的话", member: 3, run: "run-3"))

        let lead = try XCTUnwrap(transcript.turns.last(where: { !$0.isMemberBubble }))
        XCTAssertTrue(lead.segments.isEmpty, "the member's words would read as the lead's own answer")
    }

    func testConsecutiveDeltasOfOneRunGrowOneBubble() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("你好", member: 3, run: "run-3"),
                 MemberFrames.text("，世界", member: 3, run: "run-3"))

        let bubbles = transcript.turns.filter(\.isMemberBubble)
        XCTAssertEqual(bubbles.count, 1)
        XCTAssertEqual(bubbles[0].segments.map(\.text), ["你好，世界"])
    }

    /// The bubble is keyed on the run, not on the member: `childRunId` names the member's child session and is
    /// reused by the next delegation to it, so keying by member would weld two delegations into one bubble
    /// (`specs/02-session-chat.md` line 394).
    func testTwoRunsOfOneMemberAreTwoBubbles() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("第一次", member: 3, run: "run-3"),
                 MemberFrames.text("第二次", member: 3, run: "run-4"))

        let bubbles = transcript.turns.filter(\.isMemberBubble)
        XCTAssertEqual(bubbles.count, 2)
        XCTAssertEqual(bubbles.map { $0.member?.memberAgentId }, [3, 3])
        XCTAssertEqual(bubbles.compactMap { $0.segments.first?.text }, ["第一次", "第二次"])
    }

    /// A source that names the team and the member but leaves the run id blank is the lead's own speech, which
    /// is where the console's truthy test puts it too (`ChatWindow.tsx:1400`).
    func testAMemberWithoutARunIdBelongsToTheLead() throws {
        var transcript = started()
        try fold(&transcript, MemberFrames.text("主管自己说的", member: 3, run: ""))

        XCTAssertFalse(transcript.turns.contains(where: \.isMemberBubble))
        XCTAssertEqual(transcript.segments.map(\.text), ["主管自己说的"])
    }

    // MARK: - what retires a run

    func testAMembersOwnEndFrameClosesOnlyItsBubble() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("说完了", member: 3, run: "run-3"),
                 MemberFrames.end(member: 3, run: "run-3"))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        XCTAssertEqual(member.outcome, .ended)
        XCTAssertEqual(member.member?.status, .done)
        XCTAssertNotNil(member.member?.endedAt)
        XCTAssertEqual(transcript.turns.last?.outcome, .streaming, "the lead still has its own answer to give")
    }

    /// Delegation blocks the lead, so a lead turn that is over cannot have left a member working.
    func testTheLeadEndFrameClosesEveryOpenMemberRun() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("第一段", member: 3, run: "run-3"),
                 MemberFrames.text("第二段", member: 5, run: "run-5"),
                 ChatFrames.end())

        let bubbles = transcript.turns.filter(\.isMemberBubble)
        XCTAssertEqual(bubbles.count, 2)
        XCTAssertEqual(bubbles.map { $0.member?.status }, [.done, .done])
        XCTAssertEqual(bubbles.map(\.outcome), [.ended, .ended])
    }

    /// The card coming back is the member's only offline signal on today's stream, and it closes the run of
    /// that member alone — not the next one, and not another member's.
    func testADelegateCardComingBackClosesThatMembersRun() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "card-1", name: "team_delegate", arguments: delegateArgs),
                 MemberFrames.text("译文中", member: 3, run: "run-3"),
                 MemberFrames.text("别组的活", member: 5, run: "run-5"),
                 ChatFrames.result(id: "card-1", name: "team_delegate", message: "done"))

        let bubbles = transcript.turns.filter(\.isMemberBubble)
        XCTAssertEqual(bubbles.map { $0.member?.status }, [.done, .running])
        XCTAssertEqual(bubbles.map { $0.member?.isOpen }, [false, true])
    }

    func testARefusedDelegateResultClosesTheRunAsFailed() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "card-1", name: "team_delegate", arguments: delegateArgs),
                 MemberFrames.text("干不下去", member: 3, run: "run-3"),
                 ChatFrames.result(id: "card-1", name: "team_delegate", message: "成员报错", success: false))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        XCTAssertEqual(member.member?.status, .failed)
        XCTAssertEqual(member.outcome, .interrupted)
    }

    /// The task and the card a bubble answers both come off the lead's own call, because a member's frames
    /// carry neither (`noteDelegateCall`, `claimDelegateCard`).
    func testABubbleClaimsTheLeadDelegateCardAndItsTask() throws {
        var transcript = started()
        try fold(&transcript,
                 ChatFrames.call(id: "card-1", name: "team_delegate", arguments: delegateArgs),
                 MemberFrames.text("开始翻", member: 3, run: "run-3"))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        XCTAssertEqual(member.member?.parentToolId, "card-1")
        XCTAssertEqual(member.member?.task, "把第三段翻成英文")
        XCTAssertEqual(member.member?.headline, "把第三段翻成英文")
    }

    // MARK: - what the bubble says about itself

    /// A member's ask belongs inside its own bubble as an inline card, and it stays answerable: the answer has
    /// to go back to that parked run, and only the run id on the frame says which one.
    func testAMemberAskSitsInlineInItsOwnBubbleAndStaysAnswerable() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("要执行了", member: 3, run: "run-3"),
                 MemberFrames.confirm(member: 3, run: "run-3",
                                      rows: ChatFrames.pending(id: "t-1", name: "write_file")))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        let asked = try XCTUnwrap(member.segments.last?.rows)
        XCTAssertEqual(asked, [
            ChatPendingTool(toolId: "t-1", toolName: "write_file", arguments: [:], isDangerous: false,
                            childRunId: "run-3"),
        ])
        XCTAssertEqual(member.member?.status, .awaitingConfirm)
        XCTAssertTrue(member.member?.isOpen ?? false)

        let lead = try XCTUnwrap(transcript.turns.last)
        XCTAssertFalse(lead.segments.contains { $0.rows.isEmpty == false },
                       "the lead's modal would hold the whole team behind one member's ask")

        let pending = try XCTUnwrap(transcript.pendingConfirmation)
        XCTAssertEqual(pending.childRunId, "run-3", "the answer has to resume this run and not another")
    }

    func testAMemberToolCallCountsIntoItsSummary() throws {
        var transcript = started()
        try fold(&transcript,
                 MemberFrames.text("先看", member: 3, run: "run-3"),
                 MemberFrames.call(member: 3, run: "run-3", id: "t-1", name: "read_file"),
                 MemberFrames.call(member: 3, run: "run-3", id: "t-2", name: "write_file"))

        let member = try XCTUnwrap(transcript.turns.first(where: \.isMemberBubble))
        XCTAssertEqual(member.member?.toolCount, 2)
    }
}

/// Member-stamped frames, spelled once. The payloads are the router's wire shape: `eventType` is the Jackson
/// discriminator and a router leaves a nullable field as an explicit `null` rather than dropping the key.
private enum MemberFrames {
    static func source(_ member: Int64, run: String) -> String {
        """
        {"teamId":7,"teamName":"翻译组","memberAgentId":\(member),\
        "memberAgentName":"审校","childRunId":"\(run)","childSessionId":"s-\(member)"}
        """
    }

    static func text(_ message: String, member: Int64, run: String) -> String {
        """
        {"eventType":"TextEvent","message":"\(message)","isLast":false,\
        "source":\(source(member, run: run))}
        """
    }

    static func call(member: Int64, run: String, id: String, name: String) -> String {
        """
        {"eventType":"CallToolEvent","toolId":"\(id)","toolName":"\(name)","arguments":{},\
        "tokenUsage":null,"source":\(source(member, run: run))}
        """
    }

    static func confirm(member: Int64, run: String, rows: String) -> String {
        """
        {"eventType":"ToolConfirmEvent","pendingCallTools":[\(rows)],\
        "source":\(source(member, run: run))}
        """
    }

    static func end(member: Int64, run: String) -> String {
        """
        {"eventType":"EndEvent","attachments":[],"source":\(source(member, run: run))}
        """
    }
}
