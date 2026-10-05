import XCTest

@testable import HarnaxCore

/// What one `COMPACT` reply actually did.
///
/// The three-way reading is the console's (`harnax-webui/src/pages/session/components/contextUsage.ts:61-68`),
/// and the leg worth the most care is the counted no-op: the runtime answers a session too short to keep a tail
/// with `success: true` and the same count on both sides
/// (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/compaction/ContextCompactionService.kt:162-171`),
/// so "it worked" and "there was nothing to do" arrive in one envelope and are not the same report.
final class CompactionOutcomeTests: XCTestCase {
    private func outcome(_ reply: AgentCommandReply) -> CompactionOutcome {
        CompactionOutcome.compact(.success(reply))
    }

    func testCountsThatDifferAreACompaction() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 40, afterMessages: 9))),
            .done
        )
    }

    func testEqualCountsAreTheDocumentedNoOp() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 4, afterMessages: 4))),
            .noop
        )
    }

    func testARefusedCommandFails() {
        XCTAssertEqual(
            outcome(AgentCommandReply(success: false, message: "This session has no live agent state to compact.")),
            .failed
        )
    }

    func testAReplyThatNeverArrivedFails() {
        XCTAssertEqual(
            CompactionOutcome.compact(
                .failure(.business(code: 500, message: "No context held for session s-1 on this instance"))
            ),
            .failed
        )
    }

    /// A success that carried no counts to argue with is still a compaction the server said it ran — the other
    /// commands on this endpoint answer with no payload at all, and reading their silence as a refusal would
    /// turn a working `/compact` into an error message.
    func testACompactionWithNoCountsIsNotCalledAFailure() {
        XCTAssertEqual(outcome(AgentCommandReply(success: true)), .done)
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: nil)), .done)
    }

    /// A no-op has to be *counted*: one side missing is not evidence that nothing was removed.
    func testOneSidedCountsDoNotMakeANoOp() {
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: .init(beforeMessages: 9))), .done)
        XCTAssertEqual(outcome(AgentCommandReply(success: true, result: .init(afterMessages: 9))), .done)
    }

    /// The deliberate fork from the console, which calls a reply with no `success` key a failure: admin answers
    /// `ResultVo.success(null)` for a command that reported nothing, and this side already declines to read that
    /// as a refusal (`ChatViewModel.commandSentence`). A counted no-op is still a no-op here, because that leg
    /// reads `result` rather than the missing key.
    func testAReplyWithNoSuccessKeyIsNotReadAsARefusal() {
        XCTAssertEqual(outcome(AgentCommandReply()), .done)
        XCTAssertEqual(
            outcome(AgentCommandReply(result: .init(beforeMessages: 4, afterMessages: 4))),
            .noop,
            "the counted no-op does not need the flag"
        )
    }
}
